package com.past9.phoneaos.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Why a live stretch ended without words. */
enum class LiveError {
    /** Nothing it could match, or no speech before its timeout. Not a reason to stop: he may be thinking. */
    NO_SPEECH,
    /** The recogniser lost its service (mobile data, a dead zone, a switch between networks). */
    NETWORK,
    BUSY,
    /** It can't run here (no audio source support, no language, no permission). */
    BROKEN,
}

interface LiveEvents {
    fun partial(text: String)
    fun endOfSpeech()
    fun result(text: String)
    fun error(kind: LiveError)
}

/**
 * The live recogniser, one stretch at a time, fed with OUR audio (never its own mic): so its trouble never loses audio.
 * Every call and every event happens on the engine's thread.
 */
interface LiveWords {
    /** Start a stretch. False: it could not start at all. */
    fun begin(events: LiveEvents): Boolean
    /** The stretch's audio, in order. */
    fun feed(pcm: ByteArray)
    /** Bytes handed to [feed] that the recogniser has not taken yet. */
    fun backlog(): Long
    /** No more audio for this stretch: let it give its last words. */
    fun end()
    /** Drop this stretch: no more events from it. */
    fun cancel()
}

/** What the box and its status line show. */
data class DictationView(
    val text: String,
    val phase: Phase,
    /** Seconds of speech still being written down from the saved audio. */
    val pendingMs: Long = 0,
    /** Speech that could not be written down (kept on the phone; Retry tries again). */
    val lostMs: Long = 0,
    val elapsedMs: Long = 0,
    /** Live words from the recogniser, or pieces written down as he goes (no recogniser that takes our audio). */
    val live: Boolean = true,
) {
    enum class Phase { LISTENING, RECONNECTING, PAUSED, FINISHING, DONE, KEPT, CANCELLED }
    val ended: Boolean get() = phase == Phase.DONE || phase == Phase.KEPT || phase == Phase.CANCELLED
}

/**
 * Dictation that only ends when he taps stop.
 *
 * The audio is the truth: every byte goes to the [tape] first. A live recogniser ([live]) turns it into words stretch by
 * stretch; a stretch that ends badly (network, a stretch it could not match while he was talking) becomes a MISSED
 * range of the tape, and [transcribe] writes it down from the saved audio in pieces of at most 30 s, while he keeps
 * talking or after he stops. Silence never stops it, a network drop never stops it (the recogniser restarts with
 * backoff and catches up from the tape), and a recogniser that will not take our audio hands over to pieces mode.
 *
 * Single-threaded: every method runs on [scope]'s thread (the main thread in the app, a test dispatcher in tests).
 */
class DictationEngine(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val tape: DictationTape,
    private var live: LiveWords?,
    private val transcribe: (suspend (ByteArray) -> String)?,
    private val onView: (DictationView) -> Unit,
    private val log: (String) -> Unit = {},
) {
    enum class State { LIVE, HEARD, MISSED, FIXING, FIXED, LOST, SILENT }
    class Seg(val start: Long, var end: Long, var text: String, var state: State)

    private val segs = ArrayList<Seg>()
    private var cur: Seg? = null
    private var curEvents: LiveEvents? = null
    /** Where the next stretch (or piece) starts: everything before it belongs to a segment. */
    private var cursor = 0L
    /** How far the current stretch has been fed. */
    private var fed = 0L
    private var stretchBegun = 0L
    private var restartJob: Job? = null
    private var queueJob: Job? = null
    private var liveFailures = 0
    private var brokenTries = 0
    private var netTrouble = false
    private var queueTrouble = false
    private var silenced = false
    private var finishing = false
    private var phase = DictationView.Phase.LISTENING
    private val startedAt = clock()
    private var stoppedAt = 0L

    val segments: List<Seg> get() = segs

    fun start() {
        if (live != null) beginStretch() else log("pieces mode: no live recogniser")
        pump()
        show()
    }

    /** New audio is on the tape (the mic thread appended it). */
    fun audio(pcm: ByteArray) {
        if (finishing || phase.let { it == DictationView.Phase.CANCELLED }) return
        val l = live
        val c = cur
        if (l != null && c != null && curEvents != null) {
            l.feed(pcm); fed += pcm.size
            // A recogniser that will not take our audio (it opened its own mic, or ignores the pipe): pieces from now on.
            if (l.backlog() > DictationTape.bytesFor(4_000) && clock() - stretchBegun > 4_000) { log("recogniser is not reading the audio: pieces mode"); liveOff() }
        } else if (l == null) {
            val cut = Quiet.nextCut(tape.levels(), cursor, tape.bytes)
            if (cut != null && cut > cursor) closeRange(cursor, cut)
        }
    }

    /** The mic was taken by another app (a call, a recorder) or given back. */
    fun micSilenced(on: Boolean) { if (silenced != on) { silenced = on; log(if (on) "mic silenced by another app" else "mic back"); show() } }

    /** The network came back: try now instead of waiting out a backoff. */
    fun networkBack() {
        if (phase == DictationView.Phase.CANCELLED || phase == DictationView.Phase.DONE || phase == DictationView.Phase.KEPT) return
        if (!finishing && restartJob?.isActive == true && cur == null && live != null) { restartJob?.cancel(); beginStretch() }
        // A queue waiting out a backoff tries again now.
        if (queueTrouble && queueJob?.isActive == true) {
            queueJob?.cancel(); queueJob = null
            segs.filter { it.state == State.FIXING }.forEach { it.state = State.MISSED }
        }
        pump()
    }

    /** Done with it (the tape file stays on the phone). */
    fun release() { runCatching { tape.close() } }

    private fun beginStretch() {
        val l = live ?: return
        if (finishing) return
        val seg = Seg(cursor, cursor, "", State.LIVE)
        val ev = object : LiveEvents {
            override fun partial(text: String) { if (curEvents === this && text.isNotBlank()) { seg.text = text.trim(); show() } }
            override fun endOfSpeech() {
                if (curEvents !== this) return
                // It heard the end of what he said. Stop feeding it; the next stretch starts here, a little back into
                // the silence it just heard (it only ends after ~2.5 s of quiet), so nothing he says next is missed.
                endedAt = maxOf(seg.start, cursor + fed - DictationTape.bytesFor(OVERLAP_MS))
            }
            var endedAt = -1L
            override fun result(text: String) {
                if (curEvents !== this) return
                val t = text.trim().ifBlank { seg.text }
                val end = if (endedAt > 0) endedAt else cursor + fed
                closeStretch(seg, end, t, if (t.isNotBlank()) State.HEARD else State.SILENT, null)
            }
            override fun error(kind: LiveError) {
                if (curEvents !== this) return
                closeStretch(seg, cursor + fed, seg.text, State.MISSED, kind)
            }
        }
        cur = seg; curEvents = ev; fed = 0; stretchBegun = clock()
        segs.add(seg)
        if (!l.begin(ev)) { curEvents = null; cur = null; segs.remove(seg); onStretchFailed(LiveError.BROKEN); return }
        // Catch up: whatever was captured since the last stretch ended goes in first (fast), then live audio follows.
        val gap = tape.read(cursor, tape.bytes)
        if (gap.isNotEmpty()) { l.feed(gap); fed += gap.size }
    }

    private fun closeStretch(seg: Seg, end: Long, text: String, state: State, error: LiveError?) {
        curEvents = null; cur = null
        seg.end = end.coerceIn(seg.start, tape.bytes)
        seg.text = text
        val speech = Quiet.hasSpeech(tape.levels(), seg.start, seg.end)
        seg.state = when {
            state == State.HEARD -> State.HEARD
            // No words, but he was talking (or it lost its connection mid-word): write it down from the tape.
            speech -> State.MISSED
            else -> State.SILENT
        }
        cursor = seg.end
        if (seg.state == State.SILENT && seg.text.isBlank()) segs.remove(seg)
        if (error != null) log("stretch ended: $error, ${DictationTape.ms(seg.end - seg.start)} ms, ${seg.state}")
        if (seg.state == State.HEARD) { liveFailures = 0; brokenTries = 0; netTrouble = false }
        if (finishing) { finishLiveDone(); return }
        if (error == null || error == LiveError.NO_SPEECH) restart(if (error == null) 0 else 100)
        else onStretchFailed(error)
        if (seg.state == State.MISSED) pump()
        show()
    }

    private fun onStretchFailed(kind: LiveError) {
        when (kind) {
            LiveError.NETWORK -> { netTrouble = true; liveFailures++; restart(minOf(8_000L, 1_000L shl (liveFailures - 1).coerceIn(0, 3))) }
            // Busy or broken again and again with no words in between: it can't run here, pieces take over.
            LiveError.BUSY -> { brokenTries++; if (brokenTries >= 6 && transcribe != null) liveOff() else restart(400) }
            LiveError.BROKEN -> { brokenTries += 2; if (brokenTries >= 6 && transcribe != null) liveOff() else restart(500) }
            LiveError.NO_SPEECH -> restart(100)
        }
        show()
    }

    private fun restart(afterMs: Long) {
        restartJob?.cancel()
        restartJob = scope.launch { if (afterMs > 0) delay(afterMs); if (!finishing && cur == null && live != null) beginStretch() }
    }

    /** No more live recogniser: what it had not finished, and everything from now on, is written down in pieces. */
    private fun liveOff() {
        val l = live ?: return
        curEvents = null
        cur?.let { seg -> seg.end = tape.bytes; seg.state = if (Quiet.hasSpeech(tape.levels(), seg.start, seg.end)) State.MISSED else State.SILENT; cursor = seg.end; if (seg.state == State.SILENT && seg.text.isBlank()) segs.remove(seg) }
        cur = null
        runCatching { l.cancel() }
        live = null
        restartJob?.cancel()
        pump(); show()
    }

    /** A piece of the tape that only [transcribe] can write down. */
    private fun closeRange(from: Long, to: Long) {
        if (to <= from) return
        val seg = Seg(from, to, "", if (Quiet.hasSpeech(tape.levels(), from, to)) State.MISSED else State.SILENT)
        cursor = to
        if (seg.state == State.MISSED) { segs.add(seg); pump() }
        show()
    }

    // ---- writing down MISSED ranges from the tape ----

    private fun pump() {
        if (transcribe == null || queueJob?.isActive == true) return
        if (segs.none { it.state == State.MISSED }) return
        queueJob = scope.launch { drain() }
    }

    private suspend fun drain() {
        var backoff = 2_000L
        while (true) {
            val seg = segs.firstOrNull { it.state == State.MISSED } ?: break
            seg.state = State.FIXING; show()
            val ok = fix(seg)
            if (ok) { backoff = 2_000L; queueTrouble = false; show(); continue }
            seg.state = State.MISSED
            queueTrouble = true; show()
            delay(if (finishing) 1_500L else backoff)
            backoff = minOf(30_000L, backoff * 2)
        }
        queueTrouble = false
        if (finishing) settle()
        show()
    }

    /** One range: its pieces written down (three at a time once he has stopped), each tried twice. */
    private suspend fun fix(seg: Seg): Boolean {
        val t = transcribe ?: return false
        val pieces = Quiet.split(tape.levels(), seg.start, seg.end)
        val texts = arrayOfNulls<String>(pieces.size)
        suspend fun one(i: Int) {
            val p = pieces[i]
            if (!Quiet.hasSpeech(tape.levels(), p.first, p.last + 1)) { texts[i] = ""; return }
            val audio = tape.read(p.first, p.last + 1)
            for (attempt in 0 until 2) {
                val r = withTimeoutOrNull(pieceLimitMs(audio.size.toLong())) { try { t(audio) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { log("piece failed: ${e.message}"); null } }
                if (r != null) { texts[i] = r.trim(); return }
            }
        }
        if (finishing) pieces.indices.chunked(3).forEach { group -> group.map { i -> scope.async { one(i) } }.awaitAll() }
        else pieces.indices.forEach { one(it) }
        if (texts.any { it == null }) return false
        seg.text = texts.filterNotNull().filter { it.isNotBlank() }.joinToString(" ")
        seg.state = State.FIXED
        log("wrote down ${DictationTape.ms(seg.end - seg.start)} ms in ${pieces.size} piece(s)")
        return true
    }

    // ---- stop, cancel, retry ----

    /** He tapped stop. Last words from the live stretch, then whatever was missed, then it ends. Never hangs. */
    fun finish() {
        if (finishing || phase == DictationView.Phase.CANCELLED) return
        finishing = true; stoppedAt = clock()
        restartJob?.cancel()
        val l = live
        if (l != null && cur != null && curEvents != null) {
            val rest = tape.read(cursor + fed, tape.bytes)
            if (rest.isNotEmpty()) { l.feed(rest); fed += rest.size }
            l.end()
            val ev = curEvents
            scope.launch {
                delay(LAST_WORDS_MS)
                // Its last words never came: keep what it showed and write the stretch down from the tape.
                if (curEvents === ev && ev != null) { l.cancel(); ev.error(LiveError.NETWORK) }
            }
        } else finishLiveDone()
        show()
    }

    private fun finishLiveDone() {
        if (cursor < tape.bytes) {
            val seg = Seg(cursor, tape.bytes, "", if (Quiet.hasSpeech(tape.levels(), cursor, tape.bytes)) State.MISSED else State.SILENT)
            cursor = tape.bytes
            if (seg.state == State.MISSED) segs.add(seg)
        }
        runCatching { live?.cancel() }
        live = null
        val missedMs = segs.filter { it.state == State.MISSED || it.state == State.FIXING }.sumOf { DictationTape.ms(it.end - it.start) }
        val limit = minOf(FINISH_MAX_MS, FINISH_BASE_MS + missedMs / 2)
        scope.launch {
            delay(limit)
            if (!phase.let { it == DictationView.Phase.DONE || it == DictationView.Phase.KEPT || it == DictationView.Phase.CANCELLED }) {
                log("finishing limit reached after $limit ms")
                queueJob?.cancel()
                segs.filter { it.state == State.MISSED || it.state == State.FIXING }.forEach { it.state = State.LOST }
                settle()
            }
        }
        if (transcribe == null) segs.filter { it.state == State.MISSED }.forEach { it.state = State.LOST }
        if (segs.any { it.state == State.MISSED }) pump() else if (queueJob?.isActive != true) settle()
        show()
    }

    private fun settle() {
        if (phase == DictationView.Phase.DONE || phase == DictationView.Phase.KEPT || phase == DictationView.Phase.CANCELLED) return
        if (segs.any { it.state == State.MISSED || it.state == State.FIXING }) return
        phase = if (segs.any { it.state == State.LOST }) DictationView.Phase.KEPT else DictationView.Phase.DONE
        runCatching { tape.seal() }
        log("ended: $phase")
        show()
    }

    /** Throw it away: nothing more is delivered. */
    fun cancel() {
        phase = DictationView.Phase.CANCELLED
        restartJob?.cancel(); queueJob?.cancel()
        curEvents = null; cur = null
        runCatching { live?.cancel() }; live = null
        runCatching { tape.close() }
    }

    /** After KEPT: try the lost ranges again from the saved audio. */
    fun retry() {
        if (phase != DictationView.Phase.KEPT || transcribe == null) return
        segs.filter { it.state == State.LOST }.forEach { it.state = State.MISSED }
        phase = DictationView.Phase.FINISHING
        val missedMs = segs.filter { it.state == State.MISSED }.sumOf { DictationTape.ms(it.end - it.start) }
        val limit = minOf(FINISH_MAX_MS, FINISH_BASE_MS + missedMs / 2)
        scope.launch {
            delay(limit)
            if (phase == DictationView.Phase.FINISHING) { queueJob?.cancel(); segs.filter { it.state == State.MISSED || it.state == State.FIXING }.forEach { it.state = State.LOST }; phase = DictationView.Phase.KEPT; show() }
        }
        queueJob = scope.launch {
            drain()
            if (phase == DictationView.Phase.FINISHING) { phase = if (segs.any { it.state == State.LOST }) DictationView.Phase.KEPT else DictationView.Phase.DONE; show() }
        }
        show()
    }

    // ---- what he sees ----

    fun text(): String = segs.sortedBy { it.start }.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    fun view(): DictationView {
        val pending = segs.filter { it.state == State.MISSED || it.state == State.FIXING }.sumOf { DictationTape.ms(it.end - it.start) }
        val lost = segs.filter { it.state == State.LOST }.sumOf { DictationTape.ms(it.end - it.start) }
        val p = when {
            phase == DictationView.Phase.DONE || phase == DictationView.Phase.KEPT || phase == DictationView.Phase.CANCELLED -> phase
            finishing || phase == DictationView.Phase.FINISHING -> DictationView.Phase.FINISHING
            silenced -> DictationView.Phase.PAUSED
            netTrouble || queueTrouble -> DictationView.Phase.RECONNECTING
            else -> DictationView.Phase.LISTENING
        }
        val elapsed = (if (stoppedAt > 0) stoppedAt else clock()) - startedAt
        return DictationView(text(), p, pending, lost, elapsed, live != null)
    }

    private fun show() { if (phase != DictationView.Phase.CANCELLED) onView(view()) }

    companion object {
        /** How far back into the silence a new stretch starts after a clean end of speech. */
        const val OVERLAP_MS = 1_000L
        /** How long the live recogniser has for its last words after stop. */
        const val LAST_WORDS_MS = 4_000L
        const val FINISH_BASE_MS = 30_000L
        const val FINISH_MAX_MS = 120_000L
        /** One piece's limit: 20 s, plus one for every two said. */
        fun pieceLimitMs(bytes: Long): Long = 20_000L + DictationTape.ms(bytes) / 2
    }
}

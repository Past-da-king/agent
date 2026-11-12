package com.past9.phoneaos.wearproto

import org.json.JSONObject

/**
 * Calling the agent from the watch (docs/wear.md, "Call"). The phone runs the live voice session (the same one the phone's own
 * Live voice call uses, alone or through the linked computer); the watch is the call surface: state, mute, end, one caption
 * line, and, when it has a working mic and a speaker, the sound too.
 *
 *  watch -> phone  MessageClient  CALL_START {id, caps, phoneAudio}   (phone answers ACK, then CALL_PLAN)
 *  watch -> phone  MessageClient  CALL_END {id}, CALL_MUTE {id, on}, CALL_BEAT {id}   (beat: the watch is still there)
 *  watch -> phone  ChannelClient  CALL_MIC/<id>   16 kHz mono PCM16, continuous (only when the plan says the watch mic is used)
 *  phone -> watch  MessageClient  CALL_PLAN {id, mic, spk, why}, CALL_STATE {id, ...} (every second while the call lives), CALL_FLUSH {id}
 *  phone -> watch  ChannelClient  CALL_SPK/<id>   24 kHz mono PCM16, continuous (only when the plan says the watch speaker is used)
 */
object CallPaths {
    const val START = "/agent/call/start"
    const val END = "/agent/call/end"
    const val MUTE = "/agent/call/mute"
    const val BEAT = "/agent/call/beat"
    const val PLAN = "/agent/call/plan"
    const val STATE = "/agent/call/state"
    const val FLUSH = "/agent/call/flush"
    const val MIC_CHANNEL = "/agent/call/mic"
    const val SPK_CHANNEL = "/agent/call/spk"
    const val MIC_RATE = 16_000
    const val SPK_RATE = 24_000
    /** The phone sends its picture this often while a call lives; the watch treats silence as a dropped link. */
    const val STATE_EVERY_MS = 1_000L
    const val BEAT_EVERY_MS = 2_000L
}

/** Which device a sound is on. */
object Side {
    const val WATCH = "watch"
    const val PHONE = "phone"
}

/**
 * What this watch can do for a call. [mic] is true only when the watch has a microphone that works (the feature is not
 * enough: v1 judges the mic from what it records, see MicPolicy). [speaker] is true when sound can come out of the watch:
 * its own speaker, or earbuds, a headset or a hearing aid connected to it.
 */
data class WatchAudioCaps(val mic: Boolean, val speaker: Boolean, val earbuds: Boolean = false)

/** [phoneAudio]: he chose "use the phone's sound" (or the watch said so), so the watch is only the screen of the call. */
data class CallStart(val id: String, val caps: WatchAudioCaps, val phoneAudio: Boolean = false)

/** Where the two directions of sound go. The phone only needs its own mic/speaker for the sides that are [Side.PHONE]. */
data class CallPlan(val id: String, val mic: String, val spk: String, val why: String = "") {
    val watchMic get() = mic == Side.WATCH
    val watchSpeaker get() = spk == Side.WATCH
    val phoneMic get() = mic == Side.PHONE
    val phoneSpeaker get() = spk == Side.PHONE
    /** True when a vanished watch means the call cannot be heard or spoken to, so the phone ends it cleanly. */
    val endsWhenWatchLost get() = watchMic || watchSpeaker
}

object CallAudioPlan {
    /**
     * The watch's mic and speaker lead when they exist and work; each direction falls back to the phone on its own, so a watch
     * with a speaker but a dead mic still plays the agent's voice and listens through the phone's mic (and the reverse). With
     * neither, or when he asks for the phone's sound, both directions are the phone's, which is also how earbuds connected
     * to the phone work (the phone's own audio router picks them).
     */
    fun choose(req: CallStart): CallPlan {
        if (req.phoneAudio) return CallPlan(req.id, Side.PHONE, Side.PHONE, "You chose the phone's sound.")
        val mic = if (req.caps.mic) Side.WATCH else Side.PHONE
        val spk = if (req.caps.speaker) Side.WATCH else Side.PHONE
        val why = when {
            mic == Side.WATCH && spk == Side.WATCH -> if (req.caps.earbuds) "Watch mic, earbuds on the watch." else "Watch mic and speaker."
            mic == Side.PHONE && spk == Side.PHONE -> "This watch has no usable mic or speaker, so the phone's sound is used."
            mic == Side.PHONE -> "The watch mic is not available, so the phone listens."
            else -> "The watch has no speaker or earbuds, so the phone speaks."
        }
        return CallPlan(req.id, mic, spk, why)
    }
}

/** What the watch draws of a call: the picture the phone sends every second. */
data class CallView(
    val id: String, val phase: String, val muted: Boolean = false, val line: String = "", val who: String = "",
    val mic: String = Side.PHONE, val spk: String = Side.PHONE, val error: String = "", val agent: String = "",
) {
    val active get() = phase in LIVE_PHASES
    companion object {
        /** The phone's CallCore phases (voice/CallCore.kt). */
        const val CONNECTING = "connecting"; const val LISTENING = "listening"; const val THINKING = "thinking"
        const val SPEAKING = "speaking"; const val RECONNECTING = "reconnecting"; const val ENDED = "ended"; const val FAILED = "failed"
        val LIVE_PHASES = setOf(CONNECTING, LISTENING, THINKING, SPEAKING, RECONNECTING)
        const val LINE_MAX = 140
    }
}

object CallWire {
    private fun bytes(o: JSONObject) = o.toString().toByteArray(Charsets.UTF_8)
    private fun obj(b: ByteArray?): JSONObject? = if (b == null) null else runCatching { JSONObject(String(b, Charsets.UTF_8)) }.getOrNull()

    fun start(s: CallStart): ByteArray = bytes(JSONObject().put("id", s.id).put("phoneAudio", s.phoneAudio)
        .put("mic", s.caps.mic).put("speaker", s.caps.speaker).put("earbuds", s.caps.earbuds))
    fun parseStart(b: ByteArray?): CallStart? {
        val o = obj(b) ?: return null
        val id = o.optString("id"); if (id.isBlank()) return null
        return CallStart(id, WatchAudioCaps(o.optBoolean("mic"), o.optBoolean("speaker"), o.optBoolean("earbuds")), o.optBoolean("phoneAudio"))
    }

    fun id(id: String): ByteArray = bytes(JSONObject().put("id", id))
    fun parseId(b: ByteArray?): String? = obj(b)?.optString("id")?.takeIf { it.isNotBlank() }

    fun mute(id: String, on: Boolean): ByteArray = bytes(JSONObject().put("id", id).put("on", on))
    fun parseMute(b: ByteArray?): Pair<String, Boolean>? { val o = obj(b) ?: return null; val i = o.optString("id"); return if (i.isBlank()) null else i to o.optBoolean("on") }

    fun plan(p: CallPlan): ByteArray = bytes(JSONObject().put("id", p.id).put("mic", p.mic).put("spk", p.spk).put("why", p.why))
    fun parsePlan(b: ByteArray?): CallPlan? {
        val o = obj(b) ?: return null
        val id = o.optString("id"); if (id.isBlank()) return null
        val mic = o.optString("mic").takeIf { it == Side.WATCH || it == Side.PHONE } ?: Side.PHONE
        val spk = o.optString("spk").takeIf { it == Side.WATCH || it == Side.PHONE } ?: Side.PHONE
        return CallPlan(id, mic, spk, o.optString("why"))
    }

    fun view(v: CallView): ByteArray = bytes(JSONObject().put("id", v.id).put("phase", v.phase).put("muted", v.muted)
        .put("line", Limits.clip(v.line, CallView.LINE_MAX)).put("who", v.who).put("mic", v.mic).put("spk", v.spk).put("error", v.error).put("agent", v.agent))
    fun parseView(b: ByteArray?): CallView? {
        val o = obj(b) ?: return null
        val id = o.optString("id"); if (id.isBlank()) return null
        return CallView(id, o.optString("phase"), o.optBoolean("muted"), o.optString("line"), o.optString("who"), o.optString("mic", Side.PHONE),
            o.optString("spk", Side.PHONE), o.optString("error"), o.optString("agent"))
    }
}

/**
 * Is the other end still there? Both sides keep one: the phone feeds it with the watch's beats and audio frames, the watch with
 * the phone's one-second picture. A short gap is a drop-out (the call is shown as reconnecting, sound is kept running); a long
 * one means the link is gone, and the call ends cleanly on this side.
 */
class LinkWatch(private val degradedAfterMs: Long = 6_000, private val deadAfterMs: Long = 20_000, start: Long = 0L) {
    enum class Health { OK, DEGRADED, DEAD }
    @Volatile private var last = start
    fun beat(now: Long) { if (now > last) last = now }
    fun reset(now: Long) { last = now }
    fun health(now: Long): Health {
        val gap = now - last
        return when { gap >= deadAfterMs -> Health.DEAD; gap >= degradedAfterMs -> Health.DEGRADED; else -> Health.OK }
    }
}

/**
 * The watch's picture of a call, apart from Android, the Data Layer and audio, so every path can be tested: one call at a time,
 * everything tied to the call it belongs to, and [release] (stop the mic and the speaker on the watch) runs exactly once however
 * the call ends: he ends it, the phone ends it, the link drops, the phone refuses, or it never starts.
 */
class WatchCallCore(private val release: (callId: String) -> Unit, private val startTimeoutMs: Long = 25_000L, private val deadAfterMs: Long = 20_000L) {
    data class State(
        val id: String = "", val phase: String = IDLE, val muted: Boolean = false, val line: String = "", val who: String = "",
        val plan: CallPlan? = null, val error: String = "", val degraded: Boolean = false,
        /** What the agent is doing on the call, in the phone's words: listening, thinking or speaking. */
        val sub: String = "",
    ) {
        val active get() = phase == STARTING || phase == LIVE
    }

    @Volatile var state = State()
        private set
    private var startedAt = 0L
    private val link = LinkWatch(deadAfterMs = deadAfterMs)
    private var released = false

    /** Begin call [id]. False when one is already on (a second tap changes nothing). */
    @Synchronized fun begin(id: String, now: Long): Boolean {
        if (state.active) return false
        state = State(id = id, phase = STARTING); startedAt = now; released = false; link.reset(now)
        return true
    }

    @Synchronized fun onPlan(p: CallPlan, now: Long) { if (p.id == state.id && state.active) { state = state.copy(plan = p); link.beat(now) } }

    /** The phone's one-second picture. Anything about another call is ignored. */
    @Synchronized fun onPhone(v: CallView, now: Long) {
        if (v.id != state.id || state.phase == IDLE || state.phase == ENDED || state.phase == FAILED) return
        link.beat(now)
        when (v.phase) {
            CallView.ENDED -> finish(ENDED, v.error)
            CallView.FAILED -> finish(FAILED, v.error.ifBlank { "The call could not start." })
            CallView.CONNECTING -> state = state.copy(muted = v.muted, degraded = false)
            CallView.RECONNECTING -> state = state.copy(phase = LIVE, degraded = true, muted = v.muted)
            else -> state = state.copy(phase = LIVE, degraded = false, muted = v.muted, sub = v.phase, line = v.line.ifBlank { state.line }, who = if (v.line.isBlank()) state.who else v.who,
                plan = state.plan ?: CallPlan(v.id, v.mic, v.spk))
        }
    }

    /** The phone said no (the ack failed, or a message could not be sent). */
    @Synchronized fun refused(id: String, why: String) { if (id == state.id && state.active) finish(FAILED, why) }

    /** Run regularly while a call is on: a call that never starts, or a phone that went quiet, ends here. */
    @Synchronized fun tick(now: Long) {
        if (!state.active) return
        if (state.phase == STARTING) {
            // Until it is live only the start timeout speaks: a phone that never answers is a call that did not start, not one that was lost.
            if (now - startedAt >= startTimeoutMs) finish(FAILED, "The call took too long to start.")
            return
        }
        when (link.health(now)) {
            LinkWatch.Health.DEAD -> finish(ENDED, "Lost the phone.")
            LinkWatch.Health.DEGRADED -> if (state.phase == LIVE && !state.degraded) state = state.copy(degraded = true)
            LinkWatch.Health.OK -> {}
        }
    }

    @Synchronized fun muted(on: Boolean) { if (state.active) state = state.copy(muted = on) }

    /** He ended it (the button, a gesture, the back swipe). The phone is told by the caller; nothing waits for it. */
    @Synchronized fun end() { if (state.active) finish(ENDED, "") }

    private fun finish(phase: String, error: String) {
        val id = state.id
        state = state.copy(phase = phase, error = error, degraded = false, muted = false)
        if (!released) { released = true; release(id) }
    }

    companion object { const val IDLE = "idle"; const val STARTING = "starting"; const val LIVE = "live"; const val ENDED = "ended"; const val FAILED = "failed" }
}

/**
 * The speaker side of the watch call: what the phone streams arrives in bursts, so a little is kept back before it plays
 * ([prebufferMs]); when the link stalls and then catches up, the backlog is dropped down to [catchUpMs] rather than played late
 * (a voice that lags the conversation is worse than a skipped syllable); when the agent is cut off ("interrupted"), everything
 * queued is dropped at once ([flush]).
 */
class PcmJitter(private val rate: Int, private val prebufferMs: Int = 200, private val maxMs: Int = 1_500, private val catchUpMs: Int = 400) {
    private val chunks = ArrayDeque<ByteArray>()
    private var queued = 0
    private var playing = false
    var droppedBytes = 0L; private set
    var underruns = 0; private set

    private fun ms(bytes: Int) = bytes * 1000 / (rate * 2)
    val bufferedMs: Int @Synchronized get() = ms(queued)

    @Synchronized fun push(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        chunks.addLast(pcm); queued += pcm.size
        if (ms(queued) > maxMs) {
            val keep = catchUpMs * (rate * 2 / 1000)
            while (chunks.size > 1 && queued - chunks.first().size >= keep) { val d = chunks.removeFirst(); queued -= d.size; droppedBytes += d.size }
            // One burst bigger than the limit: cut the old end of it, on a sample boundary.
            val excess = (queued - keep) and 1.inv()
            if (excess > 0 && chunks.isNotEmpty()) {
                val first = chunks.removeFirst()
                val cut = minOf(excess, first.size)
                if (cut < first.size) chunks.addFirst(first.copyOfRange(cut, first.size))
                queued -= cut; droppedBytes += cut
            }
        }
    }

    /** Up to [max] bytes to play now, or null when nothing should play yet (still filling, or it ran dry and is refilling). */
    @Synchronized fun pull(max: Int): ByteArray? {
        if (!playing) { if (ms(queued) < prebufferMs) return null; playing = true }
        if (queued == 0) { underruns++; playing = false; return null }
        val first = chunks.removeFirst()
        return if (first.size <= max) { queued -= first.size; first }
        else { chunks.addFirst(first.copyOfRange(max, first.size)); queued -= max; first.copyOfRange(0, max) }
    }

    @Synchronized fun flush() { chunks.clear(); queued = 0; playing = false }
}

package com.past9.phoneaos.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

/**
 * Instant send (19 Nov 2026, Ayanda: "never make him wait"): a dictated message goes into the chat at once with its live
 * caption, and the WHOLE take is written down afterwards in the background. This is the phone's durable half: the take
 * (its audio and the caption that went out) is written to disk before anything is sent, so a force-stop right after Send
 * loses nothing, and every step is keyed by the recording id so nothing is sent or replaced twice.
 *
 * Two ways a take is finished:
 * - linked: the audio goes to the computer that owns the chat (`voice.takeAudio`), which runs ONE pass over the whole audio,
 *   replaces the message in place and holds the agent's turn until then (docs/dictation-whole-audio.md);
 * - not linked, with this phone's own key: the phone runs the pass itself, edits its own message and releases its agent.
 */
data class Take(
    val id: String,
    /** The chat the message went to (null: the main chat). */
    val chat: String?,
    /** The live caption that was sent with the message. */
    val live: String,
    /** queued (not with anyone yet), running, handed (the computer has it), done, failed. */
    val state: String,
    val seconds: Int,
    val error: String = "",
    val text: String = "",
    val acceptedAt: Long = 0,
    val attempts: Int = 0,
)

/** The takes on disk: `<id>.json` and `<id>.pcm` in [dir]. The audio is written first, the record is the commit. */
class TakeQueue(private val dir: File) {
    init { dir.mkdirs() }

    private fun json(id: String) = File(dir, "$id.json")
    private fun pcmFile(id: String) = File(dir, "$id.pcm")

    /** False when this id is already there (a second tap of the same take never queues it twice). */
    @Synchronized fun add(id: String, chat: String?, live: String, pcm: ByteArray, now: Long = System.currentTimeMillis()): Boolean {
        require(ID.matches(id)) { "not a recording id: $id" }
        if (json(id).exists()) return false
        pcmFile(id).writeBytes(pcm)
        save(Take(id, chat, live, "queued", (pcm.size / 32_000), acceptedAt = now))
        return true
    }

    @Synchronized fun get(id: String): Take? = runCatching { parse(json(id).readText()) }.getOrNull()

    @Synchronized fun all(): List<Take> = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).mapNotNull { f -> runCatching { parse(f.readText()) }.getOrNull() }.sortedBy { it.acceptedAt }

    @Synchronized fun pcm(id: String): ByteArray? = pcmFile(id).takeIf { it.isFile }?.readBytes()

    @Synchronized fun update(id: String, change: (Take) -> Take): Take? {
        val now = get(id) ?: return null
        val next = change(now)
        save(next)
        return next
    }

    /** Finished takes lose their audio at once; their small record stays a day for the marker, then goes. */
    @Synchronized fun tidy(now: Long = System.currentTimeMillis(), keepMs: Long = 24 * 3_600_000L) {
        for (t in all()) {
            if (t.state == "done" || t.state == "handed") pcmFile(t.id).delete()
            if ((t.state == "done" || t.state == "failed") && now - t.acceptedAt > keepMs) { json(t.id).delete(); pcmFile(t.id).delete() }
        }
    }

    @Synchronized fun drop(id: String) { json(id).delete(); pcmFile(id).delete() }

    private fun save(t: Take) {
        val o = JSONObject().put("id", t.id).put("live", t.live).put("state", t.state).put("seconds", t.seconds).put("error", t.error)
            .put("text", t.text).put("acceptedAt", t.acceptedAt).put("attempts", t.attempts)
        t.chat?.let { o.put("chat", it) }
        val tmp = File(dir, "${t.id}.json.tmp")
        tmp.writeText(o.toString()); tmp.renameTo(json(t.id))
    }

    private fun parse(s: String): Take {
        val o = JSONObject(s)
        return Take(o.getString("id"), o.optString("chat").ifBlank { null }, o.optString("live"), o.optString("state", "queued"), o.optInt("seconds"),
            o.optString("error"), o.optString("text"), o.optLong("acceptedAt"), o.optInt("attempts"))
    }

    companion object { val ID = Regex("^[\\w:-]{1,64}$") }
}

/** What the agent's turn gets for a take: the words to use and, when they are not the clean ones, the note to tell it. */
data class TakeText(val id: String, val text: String, val clean: Boolean, val note: String?)

/** How a take leaves this phone. The linked way and the own-key way are injected so the flow is testable without a phone. */
interface TakeEnv {
    /** A computer is linked (even while the link is down): the take belongs to it. */
    fun linked(): Boolean
    /** Gives the take to the computer. True: it has it. False: no link right now (try again when it is back). Throws when it refused. */
    suspend fun handOver(take: Take, pcm: ByteArray): Boolean
    /** This phone's own pass over the whole take, or null when it has no key. */
    suspend fun writeHere(pcm: ByteArray): String?
    /** The message in this phone's own chat becomes [text] (local mode only), keyed by the take's id. Idempotent. */
    suspend fun replaceHere(take: Take, text: String)
    /** Asks the computer to run a failed take again. */
    suspend fun retryRemote(id: String): Boolean
}

/** Runs the takes of the queue. One job per id, idempotent: resuming or retrying never runs or replaces a take twice. */
class TakeFlow(private val queue: TakeQueue, private val scope: CoroutineScope, private val env: TakeEnv, private val now: () -> Long = System::currentTimeMillis) {
    private val running = HashMap<String, Job>()
    private val waiters = HashMap<String, MutableList<CompletableDeferred<TakeText>>>()
    private val _states = MutableStateFlow<Map<String, Take>>(emptyMap())
    /** Every take on this phone by id, for the Refining marker and its Retry. */
    val states: StateFlow<Map<String, Take>> = _states

    init { publish() }

    private fun publish() { _states.value = queue.all().associateBy { it.id } }

    /** Writes [pcm] down as a take. False when it was there already (nothing happens twice). */
    fun enqueue(id: String, chat: String?, live: String, pcm: ByteArray): Boolean = queue.add(id, chat, live, pcm, now()).also { publish() }

    /** The take is on its way to whoever finishes it. [chat] is where the message went. Safe to call again. */
    @Synchronized fun submit(id: String, chat: String? = null) {
        if (chat != null) queue.update(id) { it.copy(chat = chat) }
        if (running[id]?.isActive == true) return
        val t = queue.get(id) ?: return
        if (t.state == "done" || t.state == "handed") return
        running[id] = scope.launch { run(id) }
    }

    /** At start and whenever a link comes up: every take that was waiting or running carries on. */
    fun resume() {
        for (t in queue.all()) if (t.state == "queued" || t.state == "running") submit(t.id)
        queue.tidy(now()); publish()
    }

    /** His Retry under a message that was not refined. */
    suspend fun retry(id: String): Boolean {
        val t = queue.get(id) ?: return false
        if (t.state == "handed") return env.retryRemote(id).also { if (it) { queue.update(id) { x -> x.copy(acceptedAt = now()) }; publish() } }
        if (t.state != "failed") return false
        queue.update(id) { it.copy(state = "queued", error = "", attempts = 0, acceptedAt = now()) }; publish()
        submit(id)
        return true
    }

    /** The take left the queue (the message was answered some other way). */
    fun discard(id: String) { running.remove(id)?.cancel(); queue.drop(id); publish() }

    /** Waits for the clean text for the agent's turn: at most the take's cap, then the caption with a note. Null: no such take. */
    suspend fun settled(id: String, capMs: Long? = null): TakeText? {
        val t = queue.get(id) ?: return null
        done(t)?.let { return it }
        val cap = capMs ?: holdCapMs(t.seconds)
        val later = CompletableDeferred<TakeText>()
        synchronized(this) { waiters.getOrPut(id) { ArrayList() }.add(later) }
        // Closed between the look and the wait.
        queue.get(id)?.let { done(it) }?.let { release(id, it) }
        return withTimeoutOrNull(cap) { later.await() } ?: TakeText(id, t.live, false, FAILED_NOTE)
    }

    private fun done(t: Take): TakeText? = when (t.state) {
        "done" -> TakeText(t.id, t.text.ifBlank { t.live }, true, null)
        "failed" -> TakeText(t.id, t.live, false, FAILED_NOTE)
        else -> null
    }

    @Synchronized private fun release(id: String, text: TakeText) { waiters.remove(id)?.forEach { it.complete(text) } }

    private suspend fun run(id: String) {
        var take = queue.update(id) { it.copy(state = "running", attempts = it.attempts + 1) } ?: return
        publish()
        val pcm = queue.pcm(id)
        if (pcm == null) { fail(id, "The audio of this take is gone."); return }
        try {
            if (env.linked()) {
                if (!env.handOver(take, pcm)) { queue.update(id) { it.copy(state = "queued") }; publish(); return }
                // The computer has it and answers for it (replaces the message, holds the agent's turn).
                queue.update(id) { it.copy(state = "handed") }; publish(); release(id, TakeText(id, take.live, false, null)); return
            }
            val text = env.writeHere(pcm)
            if (text == null) { fail(id, "No way to write this take down on this phone."); return }
            if (text.isBlank()) { fail(id, "The whole take came back empty."); return }
            take = queue.update(id) { it.copy(state = "done", text = text.trim()) } ?: return
            publish()
            env.replaceHere(take, take.text)
            release(id, TakeText(id, take.text, true, null))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            if (take.attempts < MAX_ATTEMPTS) { queue.update(id) { it.copy(state = "queued", error = e.message.orEmpty()) }; publish(); delay(RETRY_MS[minOf(take.attempts - 1, RETRY_MS.size - 1)]); run(id) }
            else fail(id, e.message.orEmpty().ifBlank { "Could not write this take down." })
        }
    }

    private fun fail(id: String, why: String) {
        val t = queue.update(id) { it.copy(state = "failed", error = why) } ?: return
        publish(); release(id, TakeText(id, t.live, false, FAILED_NOTE))
    }

    companion object {
        const val FAILED_NOTE = "dictated; clean transcript failed, this is the live caption"
        const val MAX_ATTEMPTS = 3
        val RETRY_MS = longArrayOf(2_000, 5_000, 10_000)
        /** How long the agent's turn may wait for a take: 30 s + 0.5 s per second said, at most 100 s. */
        fun holdCapMs(seconds: Int): Long = minOf(100_000L, 30_000L + seconds * 500L)
    }
}

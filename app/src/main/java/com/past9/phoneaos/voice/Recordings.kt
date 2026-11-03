package com.past9.phoneaos.voice

import android.content.Context
import com.past9.phoneaos.data.QuietSaver
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** One saved dictation: the words beside the audio. The words outlive the audio. */
data class SavedDictation(
    val id: String,
    val startedAt: Long,
    val durationMs: Long,
    val text: String,
    /** recording, done, kept (some words missing), cancelled, interrupted (the app ended while it recorded), audio (a tape from before words were saved). */
    val state: String,
    val chat: String,
    val audio: File?,
    /** How many characters of it the computer has. */
    val syncedChars: Int = -1,
    val syncedState: String = "",
) {
    val needsSync: Boolean get() = state != "recording" && text.isNotBlank() && (syncedChars != text.length || syncedState != state)
}

/**
 * The folder of tapes (voice/DictationTape): `<stamp>.wav` and, beside each, `<stamp>.json` with its words, updated as
 * they arrive. A tape with no JSON (recorded before 0.15.23) is listed too, so its words can still be had.
 */
class DictationFolder(val dir: File, private val saver: QuietSaver = QuietSaver(), private val now: () -> Long = System::currentTimeMillis) {
    private fun noteOf(id: String) = File(dir, "$id.json")
    private fun wavOf(id: String) = File(dir, "$id.wav")

    private fun read(id: String): JSONObject? = runCatching { JSONObject(noteOf(id).readText()) }.getOrNull()

    private fun toJson(d: SavedDictation) = JSONObject().put("id", d.id).put("startedAt", d.startedAt).put("durationMs", d.durationMs)
        .put("text", d.text).put("state", d.state).put("chat", d.chat).put("syncedChars", d.syncedChars).put("syncedState", d.syncedState)

    /** The words so far. Soon on disk, and at once when [now] is set. A later call never leaves less than an earlier one. */
    fun note(id: String, startedAt: Long, durationMs: Long, text: String, state: String, chat: String, flush: Boolean = false) {
        val old = get(id)
        val keep = if (old != null && Words.less(old.text, text)) old.text else text
        val d = SavedDictation(id, startedAt, maxOf(durationMs, old?.durationMs ?: 0), keep, state, chat, null, old?.syncedChars ?: -1, old?.syncedState.orEmpty())
        pendingNotes[id] = d
        saver.save(noteOf(id), toJson(d).toString())
        if (flush) saver.flush()
    }

    /** Newer than the file, while a save is still waiting. */
    private val pendingNotes = java.util.concurrent.ConcurrentHashMap<String, SavedDictation>()

    /** The computer has this much of it. */
    fun synced(d: SavedDictation) {
        val cur = get(d.id) ?: d
        val n = cur.copy(syncedChars = d.text.length, syncedState = d.state)
        pendingNotes[d.id] = n
        saver.save(noteOf(d.id), toJson(n).toString())
    }

    fun flush() = saver.flush()

    /** Length of a tape, from the file itself: its header can be up to a second behind after a kill. */
    private fun audioMs(f: File) = DictationTape.ms((f.length() - DictationTape.HEADER).coerceAtLeast(0))

    private fun stampTime(id: String, f: File?): Long = runCatching {
        java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).parse(id)?.time
    }.getOrNull() ?: f?.lastModified() ?: 0L

    fun get(id: String): SavedDictation? {
        pendingNotes[id]?.let { return it.copy(audio = wavOf(id).takeIf { f -> f.isFile }) }
        val w = wavOf(id).takeIf { it.isFile }
        val o = read(id)
        if (o == null) return w?.let { SavedDictation(id, stampTime(id, it), audioMs(it), "", "audio", "", it) }
        return SavedDictation(id, o.optLong("startedAt").takeIf { it > 0 } ?: stampTime(id, w), maxOf(o.optLong("durationMs"), w?.let(::audioMs) ?: 0),
            o.optString("text"), o.optString("state"), o.optString("chat"), w, o.optInt("syncedChars", -1), o.optString("syncedState"))
    }

    /** Newest first. */
    fun list(): List<SavedDictation> {
        val ids = (dir.listFiles().orEmpty()).filter { it.name.endsWith(".wav") || it.name.endsWith(".json") }.map { it.name.substringBeforeLast('.') }.toSet()
        return ids.mapNotNull { get(it) }.sortedByDescending { it.startedAt }
    }

    /** After a restart: a dictation still marked as recording was cut off. Returns them. */
    fun closeInterrupted(activeId: String? = null): List<SavedDictation> =
        list().filter { it.state == "recording" && it.id != activeId }.map { d ->
            val n = d.copy(state = "interrupted")
            pendingNotes[d.id] = n
            saver.save(noteOf(d.id), toJson(n).toString())
            n
        }

    /**
     * Room for new tapes: the audio of all but the newest [keepAudio] goes, and audio past [maxBytes] in total; a tape
     * whose words were never had is the last to go. The words stay, up to the newest [keepNotes].
     */
    fun prune(keepAudio: Int = KEEP_AUDIO, keepNotes: Int = KEEP_NOTES, maxBytes: Long = MAX_AUDIO_BYTES) {
        val all = list()
        var total = 0L
        all.filter { it.audio != null }.forEachIndexed { i, d ->
            val f = d.audio ?: return@forEachIndexed
            total += f.length()
            val wanted = i < keepAudio || d.text.isBlank()
            if (!wanted || total > maxBytes) runCatching { f.delete() }
        }
        all.filter { noteOf(it.id).isFile }.drop(keepNotes).forEach { runCatching { noteOf(it.id).delete() }; pendingNotes.remove(it.id) }
    }

    fun forget(id: String) { pendingNotes.remove(id); runCatching { noteOf(id).delete() }; runCatching { wavOf(id).delete() } }

    /**
     * Write a saved tape down again, piece by piece. Keeps the longer text: a fresh attempt that hears less changes nothing.
     * Returns the words it now holds. Throws when the transcriber can't be reached.
     */
    suspend fun transcribeAgain(id: String, transcribe: suspend (ByteArray) -> String, progress: (Int, Int) -> Unit = { _, _ -> }): String {
        val d = get(id) ?: throw java.io.IOException("That recording is gone.")
        val f = d.audio ?: throw java.io.IOException("The audio of this one is gone. Only its words are kept.")
        val parts = RandomAccessFile(f, "r").use { raf ->
            val bytes = (raf.length() - DictationTape.HEADER).coerceAtLeast(0) and 1L.inv()
            val levels = levelsOf(raf, bytes)
            val pieces = Quiet.split(levels, 0, bytes)
            val out = ArrayList<String>()
            pieces.forEachIndexed { i, p ->
                progress(i, pieces.size)
                if (Quiet.hasSpeech(levels, p.first, p.last + 1)) {
                    val pcm = ByteArray((p.last + 1 - p.first).toInt())
                    raf.seek(DictationTape.HEADER + p.first); raf.readFully(pcm)
                    var heard: String? = null
                    var last: Exception? = null
                    for (attempt in 0 until 3) {
                        try { heard = transcribe(pcm).trim(); break }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { last = e }
                    }
                    out.add(heard ?: throw (last ?: java.io.IOException("Couldn't write that down.")))
                    // Nothing saved yet: the words show as they come, and a drop half way keeps them.
                    if (d.text.isBlank()) note(id, d.startedAt, d.durationMs, out.filter { it.isNotBlank() }.joinToString(" "), d.state, d.chat)
                }
            }
            progress(pieces.size, pieces.size)
            out
        }
        val text = parts.filter { it.isNotBlank() }.joinToString(" ")
        val keep = if (text.isBlank() || text.length < d.text.length) d.text else text
        note(id, d.startedAt, d.durationMs, keep, if (d.state == "audio" || d.state == "interrupted" || d.state == "kept") "done" else d.state, d.chat, flush = true)
        return keep
    }

    private fun levelsOf(raf: RandomAccessFile, bytes: Long): FloatArray {
        val n = (bytes / DictationTape.BLOCK).toInt()
        val out = FloatArray(n)
        val buf = ByteArray(DictationTape.BLOCK)
        raf.seek(DictationTape.HEADER)
        for (i in 0 until n) {
            raf.readFully(buf)
            var sum = 0.0
            var j = 0
            while (j + 1 < buf.size) { val s = ((buf[j + 1].toInt() shl 8) or (buf[j].toInt() and 0xff)).toShort().toInt(); sum += s.toDouble() * s; j += 2 }
            out[i] = Math.sqrt(sum / (buf.size / 2)).toFloat() / 32768f
        }
        return out
    }

    companion object {
        const val KEEP_AUDIO = 50
        const val KEEP_NOTES = 500
        const val MAX_AUDIO_BYTES = 600L * 1024 * 1024
    }
}

/** The phone's own folder. */
object Dictations {
    @Volatile private var folder: DictationFolder? = null
    fun of(context: Context): DictationFolder = folder ?: synchronized(this) {
        folder ?: DictationFolder(File(context.applicationContext.filesDir, "dictation").apply { mkdirs() }).also { folder = it }
    }

    /** What the computer has of it: words and when, never the audio. Small, so it is fine on mobile data. */
    fun payload(d: SavedDictation): JSONObject = JSONObject().put("id", d.id).put("startedAt", d.startedAt).put("durationMs", d.durationMs)
        .put("text", d.text.take(200_000)).put("state", d.state).put("chat", d.chat)

    /** Everything the computer does not have yet, oldest first. An older computer without the method: stops, tried again next link. */
    suspend fun push(context: Context, call: suspend (JSONObject) -> Unit) {
        val f = of(context)
        for (d in f.list().filter { it.needsSync }.sortedBy { it.startedAt }.takeLast(50)) {
            try { call(payload(d)); f.synced(d) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { return }
        }
    }
}

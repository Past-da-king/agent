package com.past9.phoneaos.screen

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One thing that happened on the background screen during a run: what was opened, looked at, pressed, typed, asked.
 * [say] is the line a person reads under the picture. [sel] says which element it was, in the app's own terms (its
 * id, words, kind and place), so the same step can be found again on another day: that is what a learned app replays.
 */
data class RunStep(
    val at: Long, val kind: String, val say: String, val sel: JSONObject? = null,
    /** What was typed, or the words waited for. Never kept for a password or a text marked secret. */
    val text: String = "", val secret: Boolean = false,
    /** The picture taken right after this step: its number in the run, or -1. */
    val frame: Int = -1,
    /** By whom: "agent" or "you" (the person showing it by hand). */
    val by: String = "agent", val extra: JSONObject? = null, val tookMs: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("at", at).put("kind", kind).put("say", say).put("text", text).put("secret", secret).put("frame", frame).put("by", by).put("ms", tookMs)
        .apply { sel?.let { put("sel", it) }; extra?.let { put("extra", it) } }

    companion object {
        fun fromJson(o: JSONObject) = RunStep(o.optLong("at"), o.optString("kind"), o.optString("say"), o.optJSONObject("sel"), o.optString("text"), o.optBoolean("secret"),
            o.optInt("frame", -1), o.optString("by", "agent"), o.optJSONObject("extra"), o.optLong("ms"))
    }
}

/** A finished or running recording, as the list shows it. */
data class RunInfo(val id: String, val app: String, val pkg: String, val owner: String, val startedAt: Long, val endedAt: Long, val steps: Int, val frames: Int, val bytes: Long)

/**
 * Recordings of what happened on the background screen, kept on this phone only: for each run a folder with the
 * pictures (one after every step) and the list of steps. The person can watch a run afterwards, step by step, and
 * delete it. Old runs are cleaned up by themselves when the recordings pass [maxBytes] or [maxRuns].
 */
class ScreenRuns(private val dir: File, private val maxBytes: Long = 150L * 1024 * 1024, private val maxRuns: Int = 40, private val now: () -> Long = { System.currentTimeMillis() }) {

    /** A run being recorded. */
    inner class Run internal constructor(val id: String, val pkg: String, val app: String, val owner: String) {
        val folder = File(dir, id)
        val startedAt = now()
        private val list = ArrayList<RunStep>()
        var frames = 0; private set
        val steps: List<RunStep> get() = synchronized(list) { list.toList() }

        /** Keep a picture. [jpeg] must already have secrets painted over. Returns its number, or -1 when the run is full. */
        fun picture(jpeg: ByteArray?): Int {
            if (jpeg == null || frames >= MAX_FRAMES) return -1
            return runCatching { folder.mkdirs(); File(folder, frameName(frames)).writeBytes(jpeg); frames++ }.getOrDefault(-1)
        }

        fun add(step: RunStep) { synchronized(list) { if (list.size < MAX_STEPS) list.add(step) }; save(0) }

        internal fun save(endedAt: Long) {
            runCatching {
                folder.mkdirs()
                File(folder, "run.json").writeText(JSONObject().put("id", id).put("pkg", pkg).put("app", app).put("owner", owner).put("started", startedAt).put("ended", endedAt)
                    .put("frames", frames).put("steps", JSONArray(steps.map { it.toJson() })).toString())
            }
        }

        fun finish() { save(now()); clean() }
    }

    fun begin(pkg: String, app: String, owner: String): Run {
        val id = "r" + now().toString(36) + (1000..9999).random().toString(36)
        return Run(id, pkg, app, owner).also { it.save(0) }
    }

    private fun read(f: File): JSONObject? = runCatching { JSONObject(File(f, "run.json").readText()) }.getOrNull()

    /** Newest first. */
    fun list(): List<RunInfo> = (dir.listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { f ->
        val o = read(f) ?: return@mapNotNull null
        RunInfo(f.name, o.optString("app"), o.optString("pkg"), o.optString("owner"), o.optLong("started"), o.optLong("ended"), o.optJSONArray("steps")?.length() ?: 0, o.optInt("frames"),
            (f.listFiles() ?: emptyArray()).sumOf { it.length() })
    }.sortedByDescending { it.startedAt }

    fun steps(id: String): List<RunStep> = read(File(dir, safe(id)))?.optJSONArray("steps")?.let { a -> (0 until a.length()).map { RunStep.fromJson(a.getJSONObject(it)) } } ?: emptyList()

    fun picture(id: String, n: Int): File? = File(File(dir, safe(id)), frameName(n)).takeIf { it.isFile }

    fun delete(id: String) { File(dir, safe(id)).takeIf { it.isDirectory && it.parentFile == dir }?.deleteRecursively() }
    fun deleteAll() { (dir.listFiles() ?: emptyArray()).forEach { it.deleteRecursively() } }
    fun totalBytes(): Long = list().sumOf { it.bytes }

    /** Drop the oldest runs until the recordings fit. A run still being recorded (no end time) is left alone. */
    fun clean() {
        val all = list()
        var bytes = all.sumOf { it.bytes }; var count = all.size
        for (r in all.reversed()) {
            if (bytes <= maxBytes && count <= maxRuns) break
            if (r.endedAt == 0L && now() - r.startedAt < 6 * 3600_000L) continue
            delete(r.id); bytes -= r.bytes; count--
        }
    }

    private fun safe(id: String) = id.filter { it.isLetterOrDigit() }.ifBlank { "none" }

    companion object {
        const val MAX_FRAMES = 400
        const val MAX_STEPS = 600
        fun frameName(n: Int) = "f%04d.jpg".format(n)
    }
}

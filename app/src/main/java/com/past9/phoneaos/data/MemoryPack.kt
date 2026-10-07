package com.past9.phoneaos.data

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * A memory pack: a JSON array of wiki pages ({title, body, kind, tags, pinned}) dropped next to the app,
 * e.g. by A.O.S over adb into Android/data/<pkg>/files/memory-pack.json. Imported once on app open, then renamed.
 * Pages from an earlier pack are replaced; a page the agent or the user wrote is kept and the pack's text is appended.
 */
object MemoryPack {
    const val FILE = "memory-pack.json"
    const val SOURCE = "import"
    private const val MARK = "\n\n## From A.O.S memory bank\n\n"
    // "skill" is reserved for helper skills, so a pack can never write one.
    private val kinds = setOf("fact", "preference", "person", "decision", "how-to", "event")

    fun pending(context: Context): List<File> =
        listOfNotNull(context.getExternalFilesDir(null), context.filesDir).map { File(it, FILE) }.filter { it.isFile }

    /** Imports every waiting pack; returns how many pages were written. */
    suspend fun importPending(context: Context, dao: MemoryDao): Int = pending(context).sumOf { f ->
        val n = runCatching { import(f.readText(), dao) }.getOrDefault(-1)
        f.renameTo(File(f.parentFile, "$FILE.${if (n < 0) "failed" else "imported"}-${System.currentTimeMillis()}"))
        n.coerceAtLeast(0)
    }

    suspend fun import(json: String, dao: MemoryDao): Int {
        val arr = JSONArray(json)
        var n = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").trim().take(200)
            val body = o.optString("body").trim()
            if (title.isEmpty() || body.isEmpty()) continue
            val kind = o.optString("kind").lowercase().let { if (it in kinds) it else "fact" }
            val tags = o.opt("tags").let { t -> if (t is JSONArray) (0 until t.length()).joinToString(",") { t.optString(it) } else t?.toString().orEmpty() }
            val pinned = o.optBoolean("pinned", false)
            val now = System.currentTimeMillis()
            val cur = dao.byTitle(title)
            when {
                cur?.kind == "skill" -> dao.byTitle("$title (A.O.S)").let { mine ->
                    if (mine == null) dao.insert(MemoryRow(title = "$title (A.O.S)", body = body, kind = kind, topics = tags, pinned = pinned, source = SOURCE))
                    else dao.update(mine.copy(body = body, kind = kind, topics = tags, pinned = pinned, updatedAt = now))
                }
                cur == null -> dao.insert(MemoryRow(title = title, body = body, kind = kind, topics = tags, pinned = pinned, source = SOURCE))
                cur.source == SOURCE -> dao.update(cur.copy(body = body, kind = kind, topics = tags, pinned = pinned, updatedAt = now))
                else -> {
                    val own = cur.body.substringBefore(MARK)
                    val merged = (cur.topics.split(",") + tags.split(",")).map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")
                    dao.update(cur.copy(body = own + MARK + body, topics = merged, pinned = cur.pinned || pinned, updatedAt = now))
                }
            }
            n++
        }
        return n
    }
}

package com.past9.phoneaos.data

import android.content.Context
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
    // "skill" is reserved for helper skills, so a pack can never write one (MemoryTransfer.write enforces it).

    fun pending(context: Context): List<File> =
        listOfNotNull(context.getExternalFilesDir(null), context.filesDir).map { File(it, FILE) }.filter { it.isFile }

    /** Imports every waiting pack; returns how many pages were written. */
    suspend fun importPending(context: Context, dao: MemoryDao): Int = pending(context).sumOf { f ->
        val n = runCatching { import(f.readText(), dao) }.getOrDefault(-1)
        f.renameTo(File(f.parentFile, "$FILE.${if (n < 0) "failed" else "imported"}-${System.currentTimeMillis()}"))
        n.coerceAtLeast(0)
    }

    /** Writes a JSON pack (the A.O.S one, or any JSON memory list) and returns how many pages it held. */
    suspend fun import(json: String, dao: MemoryDao): Int {
        // A file that isn't JSON at all is set aside as failed, not read as an empty pack.
        json.trim().let { t -> runCatching { if (t.startsWith("[")) org.json.JSONArray(t) else org.json.JSONObject(t) }.getOrElse { throw IllegalArgumentException("not a memory pack") } }
        val pages = MemoryTransfer.fromJson(json)
        val r = MemoryTransfer.write(pages, dao, mark = MARK, suffix = "(A.O.S)")
        return r.added + r.updated + r.skipped
    }
}

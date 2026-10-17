package com.past9.phoneaos.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** One memory page on its way in, whatever file it came from. A null kind means the file didn't say. */
data class PageIn(
    val title: String, val body: String, val kind: String?, val tags: List<String>, val pinned: Boolean,
    val created: Long? = null, val updated: Long? = null, val source: String? = null,
)

/**
 * Memory in and out of the app as one zip: a manifest and one markdown page per file, each with a small header
 * (title, kind, tags, pinned). The same reader takes other tools' notes too (JSON, JSONL, plain markdown), so a
 * memory kept anywhere can be brought in, tagged "imported" and "unsorted" to classify later.
 * The format is written up in MEMORY-ZIP-FORMAT.md next to the exports.
 */
object MemoryTransfer {
    const val FORMAT = "agent-memory"
    /** Every pinned page is pasted whole into every prompt, so an import can't pin more than this many in total. */
    const val MAX_PINNED = 8
    const val MARK = "\n\n## Imported memory\n\n"
    const val IMPORTED = "imported"
    const val UNSORTED = "unsorted"

    val KINDS = setOf("fact", "preference", "person", "decision", "how-to", "event", "place", "company", "project")
    private const val MAX_FILE = 8 * 1024 * 1024
    private const val MAX_TOTAL = 64 * 1024 * 1024
    private const val MAX_ENTRIES = 20_000
    private const val MAX_BODY = 30_000

    class Parsed(val pages: List<PageIn>, val own: Boolean, val ignored: Int)
    data class Result(val added: Int = 0, val updated: Int = 0, val skipped: Int = 0, val pinnedDropped: Int = 0, val unsorted: Int = 0)

    // ---- reading -------------------------------------------------------------------------------

    /** Reads a zip or a single file. Anything it can't make a page from is counted in `ignored`, never fatal. */
    fun read(name: String, input: InputStream): Parsed {
        val bin = input.buffered(); bin.mark(8)
        val head = ByteArray(4); val n = bin.read(head); bin.reset()
        val zipped = n == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        if (!zipped) return Parsed(pagesFrom(name, capped(bin, MAX_FILE) ?: return Parsed(emptyList(), false, 1)), false, 0)
        val files = LinkedHashMap<String, ByteArray>(); var ignored = 0; var total = 0L
        ZipInputStream(bin).use { z ->
            while (files.size + ignored < MAX_ENTRIES) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val bytes = capped(z, MAX_FILE)
                if (bytes == null || total + bytes.size > MAX_TOTAL) { ignored++; continue }
                total += bytes.size; files[e.name] = bytes
            }
        }
        val manifest = files.entries.firstOrNull { it.key.substringAfterLast('/').equals("manifest.json", true) }?.value
            ?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() }
        val own = manifest?.optString("format") == FORMAT
        val pages = mutableListOf<PageIn>()
        for ((entry, bytes) in files) {
            if (entry.substringAfterLast('/').equals("manifest.json", true)) continue
            val found = pagesFrom(entry, bytes)
            if (found.isEmpty()) ignored++ else pages += found
        }
        return Parsed(pages, own, ignored)
    }

    private fun capped(s: InputStream, max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024)
        while (true) { val r = s.read(buf); if (r < 0) break; out.write(buf, 0, r); if (out.size() > max) return null }
        return out.toByteArray()
    }

    private fun pagesFrom(name: String, bytes: ByteArray): List<PageIn> {
        val text = String(bytes, Charsets.UTF_8).removePrefix("﻿")
        return when (name.substringAfterLast('.', "").lowercase()) {
            "json" -> fromJson(text)
            "jsonl", "ndjson" -> text.lineSequence().map { it.trim() }.filter { it.startsWith("{") }.flatMap { fromJson(it).asSequence() }.toList()
            "md", "markdown", "txt" -> listOfNotNull(fromMarkdown(name, text))
            else -> emptyList()
        }
    }

    /** A JSON array of pages, an object holding one under pages/memories/items/notes/entries, or one page. */
    fun fromJson(text: String): List<PageIn> {
        val t = text.trim()
        val arr: JSONArray = runCatching {
            when {
                t.startsWith("[") -> JSONArray(t)
                t.startsWith("{") -> JSONObject(t).let { o ->
                    listOf("pages", "memories", "items", "notes", "entries").firstNotNullOfOrNull { k -> o.optJSONArray(k) } ?: JSONArray().put(o)
                }
                else -> JSONArray()
            }
        }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::fromJsonObject) }
    }

    private fun first(o: JSONObject, vararg keys: String) = keys.firstNotNullOfOrNull { k -> o.opt(k)?.takeIf { it != JSONObject.NULL }?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }

    private fun strs(o: JSONObject, vararg keys: String): List<String> = keys.flatMap { k ->
        when (val v = o.opt(k)) {
            is JSONArray -> (0 until v.length()).map { v.optString(it) }
            null, JSONObject.NULL -> emptyList()
            else -> splitList(v.toString())
        }
    }

    private fun fromJsonObject(o: JSONObject): PageIn? {
        val title = first(o, "title", "name", "heading", "subject") ?: return null
        val body = first(o, "body", "content", "text", "memory", "note", "description") ?: return null
        val pinned = (first(o, "pinned", "important", "favorite", "favourite") ?: "").equals("true", true)
        return PageIn(title, body, first(o, "kind", "type", "category"), tagsOf(strs(o, "tags", "topics", "keywords", "labels") + strs(o, "aliases", "synonyms")), pinned,
            time(o.opt("created") ?: o.opt("createdAt") ?: o.opt("created_at")), time(o.opt("updated") ?: o.opt("updatedAt") ?: o.opt("modified") ?: o.opt("updated_at")), first(o, "source"))
    }

    private fun time(v: Any?): Long? = when (v) {
        is Number -> v.toLong().let { if (it in 1..99_999_999_999L) it * 1000 else it }.takeIf { it > 0 }
        is String -> v.trim().let { s -> s.toLongOrNull()?.let { time(it) } ?: runCatching { Instant.parse(s).toEpochMilli() }.getOrNull() }
        else -> null
    }

    private fun splitList(s: String) = s.trim().removePrefix("[").removeSuffix("]").split(',', ';').map { it.trim().trim('"', '\'') }

    /** Lower-case search words: no '#', blanks and repeats gone, a sensible length. */
    fun tagsOf(raw: List<String>): List<String> =
        raw.map { it.trim().removePrefix("#").trim().replace(Regex("\\s+"), " ").lowercase() }.filter { it.isNotEmpty() && it.length <= 40 }.distinct().take(24)

    /** A markdown page: an optional `---` header of `key: value` lines, then the body. Without one, the title is the first heading or the file name. */
    fun fromMarkdown(name: String, text: String): PageIn? {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val header = LinkedHashMap<String, String>()
        var start = 0
        if (lines.firstOrNull()?.trim() == "---") {
            val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
            if (end != null) {
                for (i in 1 until end) { val l = lines[i]; val c = l.indexOf(':'); if (c > 0) header[l.substring(0, c).trim().lowercase()] = l.substring(c + 1).trim() }
                start = end + 1
            }
        }
        var body = lines.drop(start).toMutableList()
        var title = header["title"]?.trim('"', '\'')?.takeIf { it.isNotBlank() }
        if (title == null) {
            val h = body.indexOfFirst { it.isNotBlank() }
            if (h >= 0 && Regex("^#\\s+\\S").containsMatchIn(body[h])) { title = body[h].trimStart('#').trim(); body.removeAt(h) }
        }
        val tags = (header["tags"]?.let(::splitList).orEmpty() + header["keywords"]?.let(::splitList).orEmpty() + header["aliases"]?.let(::splitList).orEmpty()).toMutableList()
        if (header.isEmpty()) {
            // Notes with no header: a "tags: a, b" line and lines of only #hashtags are tags, not text.
            body = body.filter { l ->
                val t = l.trim()
                when {
                    t.startsWith("tags:", true) -> { tags += splitList(t.substringAfter(':')); false }
                    t.isNotEmpty() && Regex("^(#[\\p{L}\\p{N}_-]+\\s*)+$").matches(t) -> { tags += t.split(Regex("\\s+")); false }
                    else -> true
                }
            }.toMutableList()
        }
        val text2 = body.joinToString("\n").trim()
        if (text2.isEmpty()) return null
        val fileTitle = name.substringAfterLast('/').substringBeforeLast('.').replace('-', ' ').replace('_', ' ').trim()
        return PageIn((title ?: fileTitle).ifBlank { return null }, text2, header["kind"]?.trim()?.lowercase()?.takeIf { it.isNotBlank() }, tagsOf(tags),
            header["pinned"]?.equals("true", true) == true, time(header["created"]), time(header["updated"]), header["source"])
    }

    // ---- writing -------------------------------------------------------------------------------

    /**
     * Puts pages into memory. A page that isn't there yet is added; one that came from an earlier import is replaced;
     * one the agent or user wrote keeps its text and the imported text goes under [mark] (and an identical one is left
     * alone). A helper's skill is never overwritten by a foreign page: the page lands beside it as "<title> <suffix>".
     * [own] means the file is this app's own export (its manifest said so): skills and who wrote a page come back as they were.
     */
    suspend fun write(pages: List<PageIn>, dao: MemoryDao, mark: String = MARK, suffix: String = "(imported)", own: Boolean = false,
                      tagAll: List<String> = emptyList(), tagUnsorted: Boolean = false): Result {
        var added = 0; var updated = 0; var skipped = 0; var dropped = 0; var unsorted = 0
        var pins = dao.pinned().size
        for (p in pages) {
            val title = p.title.trim().take(200); val body = p.body.trim().take(MAX_BODY)
            if (title.isEmpty() || body.isEmpty()) continue
            val rawKind = p.kind?.lowercase()
            val kind = rawKind?.takeIf { it in KINDS || (own && it == "skill") } ?: "fact"
            val sorted = rawKind != null && (rawKind in KINDS || (own && rawKind == "skill"))
            val extra = tagAll + if (tagUnsorted && !sorted) { unsorted++; listOf(UNSORTED) } else emptyList()
            val tags = tagsOf(p.tags + extra)
            val topics = tags.joinToString(",")
            val now = System.currentTimeMillis()
            val source = if (own && p.source in setOf("user", "agent")) p.source!! else MemoryPack.SOURCE
            fun pin(was: Boolean): Boolean = when { was -> true; p.pinned && pins < MAX_PINNED -> { pins++; true }; p.pinned -> { dropped++; false }; else -> false }
            val cur = dao.byTitle(title)
            when {
                cur?.kind == "skill" && !(own && kind == "skill") -> {
                    val twin = "$title $suffix"
                    val mine = dao.byTitle(twin)
                    if (mine == null) { dao.insert(MemoryRow(title = twin, body = body, kind = if (kind == "skill") "fact" else kind, topics = topics, pinned = pin(false), source = MemoryPack.SOURCE, createdAt = p.created ?: now, updatedAt = p.updated ?: now)); added++ }
                    else if (mine.body == body && mine.topics == topics) skipped++
                    else { dao.update(mine.copy(body = body, kind = if (kind == "skill") "fact" else kind, topics = topics, pinned = pin(mine.pinned), updatedAt = now)); updated++ }
                }
                cur == null -> { dao.insert(MemoryRow(title = title, body = body, kind = kind, topics = topics, pinned = pin(false), source = source, createdAt = p.created ?: now, updatedAt = p.updated ?: now)); added++ }
                cur.source == MemoryPack.SOURCE || (own && cur.kind == "skill") -> {
                    val pinned = pin(cur.pinned)
                    if (cur.body == body && cur.kind == kind && cur.topics == topics && cur.pinned == pinned) skipped++
                    else { dao.update(cur.copy(body = body, kind = kind, topics = topics, pinned = pinned, updatedAt = now)); updated++ }
                }
                else -> {
                    val mine = cur.body.substringBefore(mark)
                    val merged = tagsOf(cur.topics.split(",") + tags).joinToString(",")
                    // The same words already on the page: only new tags and the pin can change.
                    val newBody = if (mine.trim() == body) cur.body else mine + mark + body
                    val pinned = pin(cur.pinned)
                    if (newBody == cur.body && merged == cur.topics && pinned == cur.pinned) skipped++
                    else { dao.update(cur.copy(body = newBody, topics = merged, pinned = pinned, updatedAt = now)); updated++ }
                }
            }
        }
        return Result(added, updated, skipped, dropped, unsorted)
    }

    /** What the user is told after an import from a file. */
    data class Summary(val result: Result, val ignored: Int, val own: Boolean) {
        val pages get() = result.added + result.updated + result.skipped
        fun message(): String = buildString {
            if (pages == 0) { append("Nothing to import: no memory pages found in that file."); if (ignored > 0) append(" $ignored file${if (ignored == 1) "" else "s"} couldn't be read."); return@buildString }
            append("Imported $pages page${if (pages == 1) "" else "s"}: ${result.added} new, ${result.updated} updated, ${result.skipped} already there.")
            if (result.unsorted > 0) append(" ${result.unsorted} came in unsorted: open Memory and tap Unsorted to classify them.")
            if (result.pinnedDropped > 0) append(" ${result.pinnedDropped} weren't pinned: only $MAX_PINNED pages can be pinned.")
            if (ignored > 0) append(" $ignored file${if (ignored == 1) "" else "s"} skipped.")
        }
    }

    /** Import a zip, JSON or markdown file the user picked. */
    suspend fun importFrom(name: String, input: InputStream, dao: MemoryDao): Summary {
        val parsed = read(name, input)
        val r = write(parsed.pages, dao, own = parsed.own, tagAll = if (parsed.own) emptyList() else listOf(IMPORTED), tagUnsorted = !parsed.own)
        return Summary(r, parsed.ignored, parsed.own)
    }

    // ---- export --------------------------------------------------------------------------------

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    fun pageText(m: MemoryRow): String = buildString {
        appendLine("---")
        appendLine("title: ${m.title.replace('\n', ' ').trim()}")
        appendLine("kind: ${m.kind}")
        appendLine("tags: [${tagsOf(m.topics.split(",")).joinToString(", ")}]")
        appendLine("pinned: ${m.pinned}")
        appendLine("source: ${m.source}")
        appendLine("created: ${iso(m.createdAt)}")
        appendLine("updated: ${iso(m.updatedAt)}")
        appendLine("---")
        appendLine()
        append(m.body.trim()); append('\n')
    }

    fun slug(title: String, used: MutableSet<String>): String {
        val base = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60).ifEmpty { "page" }
        var s = base; var i = 2
        while (!used.add(s)) s = "$base-${i++}"
        return s
    }

    /** Every page as one zip. Returns how many pages were written. */
    suspend fun export(dao: MemoryDao, out: OutputStream): Int {
        val rows = dao.list().sortedBy { it.title.lowercase() }
        ZipOutputStream(out.buffered()).use { z ->
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write(JSONObject().put("format", FORMAT).put("version", 1).put("exportedAt", iso(System.currentTimeMillis())).put("count", rows.size)
                .put("app", "com.past9.phoneaos").put("note", "Memory pages, one markdown file each. Import this zip in the Agent app: Memory, menu, Import memories.").toString(2).toByteArray())
            z.closeEntry()
            val used = HashSet<String>()
            for (m in rows) {
                z.putNextEntry(ZipEntry("pages/${slug(m.title, used)}.md"))
                z.write(pageText(m).toByteArray()); z.closeEntry()
            }
        }
        return rows.size
    }
}

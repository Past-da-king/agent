package com.past9.phoneaos.tools

import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.bool
import com.past9.phoneaos.agent.enumOf
import com.past9.phoneaos.agent.int
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.data.MemoryDao
import com.past9.phoneaos.data.MemoryRow
import org.json.JSONObject

/** On-device recall: keyword overlap with a little weight for title, topics and recency. */
object Recall {
    private val stop = setOf("the", "a", "an", "and", "or", "to", "of", "in", "on", "for", "is", "it", "my", "me", "i", "you", "what", "do", "with", "at", "be", "this", "that", "can", "please")
    fun terms(q: String) = q.lowercase().split(Regex("[^\\p{L}\\p{N}@.]+")).filter { it.length > 1 && it !in stop }.toSet()

    fun rank(query: String, rows: List<MemoryRow>, limit: Int): List<MemoryRow> {
        val q = terms(query); if (q.isEmpty()) return rows.take(limit)
        val now = System.currentTimeMillis()
        return rows.map { m ->
            val title = terms(m.title); val body = terms(m.body); val topics = terms(m.topics)
            var s = 0.0
            q.forEach { t ->
                if (t in title) s += 3; if (t in topics) s += 2; if (t in body) s += 1
                if (t.length > 3 && (m.body.contains(t, true) || m.title.contains(t, true))) s += 0.5
            }
            if (m.pinned) s += 0.5
            s += 0.3 / (1 + (now - m.updatedAt) / 86_400_000.0)
            m to s
        }.filter { it.second >= 1.0 }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    fun format(m: MemoryRow) = "#${m.id} [${m.kind}${if (m.pinned) ", pinned" else ""}] ${m.title}: ${m.body}" + if (m.topics.isNotBlank()) " (topics: ${m.topics})" else ""
}

class MemorySearchTool(private val dao: MemoryDao) : Tool {
    override val spec = ToolSpec("memory_search", "Search everything you have remembered about the user (facts, preferences, people, decisions). Search before asking the user anything they may already have told you.",
        schema(listOf("query"), "query" to str("What to recall, in plain words"), "limit" to int("Max results, default 8")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val q = input.optString("query")
        val hits = Recall.rank(q, dao.list(), input.optInt("limit", 8))
        ctx.activity("Searched memory for \"$q\" · ${hits.size} found", JSONObject().put("tool", "memory"))
        return if (hits.isEmpty()) "No memories match." else hits.joinToString("\n") { Recall.format(it) }
    }
}

/** Wiki links in a memory page: [[Name]]. */
val wikiLink = Regex("""\[\[([^\]|]+)(?:\|[^\]]*)?]]""")
fun linksIn(body: String) = wikiLink.findAll(body).map { it.groupValues[1].trim() }.toSet()

/**
 * Memory is a small wiki: one page per thing (a person, a company, a place, a plan, a preference),
 * written in markdown, with [[Name]] links to other pages and tags for search.
 */
class MemorySaveTool(private val dao: MemoryDao) : Tool {
    override val spec = ToolSpec("memory_save", "Write or update a wiki page in your memory about ONE thing: a person, company, place, project, plan or preference. Title = its name (e.g. 'Lerato', 'Acme', 'Seat preferences'). Body = markdown, detailed but no padding; refer to other people/companies/places as [[Name]] links (e.g. 'Lerato is [[Sam]]'s sister and works at [[Acme]]'). If a page with the title exists your body REPLACES it, so memory_get it first and merge. Always add tags.",
        schema(listOf("title", "body", "tags"),
            "title" to str("The thing's name"), "body" to str("Markdown page with [[links]]"),
            "kind" to enumOf("What it is", "person", "company", "place", "project", "preference", "fact", "event", "how-to"),
            "tags" to str("Comma-separated tags for search, e.g. 'family, durban, birthday'"), "pinned" to bool("Pin it (core facts like the user's own page)")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val title = input.optString("title").trim()
        // An empty body would wipe an existing page and still read as saved.
        if (input.optString("body").isBlank()) return "Not saved: body is empty. Pass the whole page as body (memory_get an existing page first and merge)."
        val existing = dao.byTitle(title)
        val tags = input.optString("tags").ifBlank { input.optString("topics") }
        return if (existing != null) {
            dao.update(existing.copy(body = input.optString("body"), kind = input.optString("kind").ifBlank { existing.kind },
                topics = tags.ifBlank { existing.topics }, pinned = existing.pinned || input.optBoolean("pinned"), updatedAt = System.currentTimeMillis()))
            ctx.activity("Updated memory: $title", JSONObject().put("tool", "memory"))
            "Updated page #${existing.id} '$title'"
        } else {
            val id = dao.insert(MemoryRow(title = title, body = input.optString("body"), kind = input.optString("kind", "fact").ifBlank { "fact" }, topics = tags, pinned = input.optBoolean("pinned")))
            ctx.activity("Remembered: $title", JSONObject().put("tool", "memory"))
            "Saved page #$id '$title'"
        }
    }
}

class MemoryGetTool(private val dao: MemoryDao) : Tool {
    override val spec = ToolSpec("memory_get", "Read one memory page in full by its title, plus which pages link to it.", schema(listOf("title"), "title" to str("Page title")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val t = input.optString("title"); val m = dao.byTitle(t) ?: return "No page called '$t'."
        val back = dao.list().filter { o -> o.id != m.id && linksIn(o.body).any { it.equals(m.title, true) } }.map { it.title }
        return "# ${m.title} (#${m.id}, ${m.kind}; tags: ${m.topics})\n${m.body}" + if (back.isNotEmpty()) "\n\nLinked from: ${back.joinToString()}" else ""
    }
}

class MemoryUpdateTool(private val dao: MemoryDao) : Tool {
    override val spec = ToolSpec("memory_update", "Correct or delete a memory by id when it turns out to be wrong or out of date.",
        schema(listOf("id"), "id" to int("Memory id"), "body" to str("New text"), "title" to str("New title"), "delete" to bool("Delete it instead")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val id = input.getLong("id"); val m = dao.get(id) ?: return "No memory #$id"
        if (input.optBoolean("delete")) { dao.delete(id); ctx.activity("Forgot: ${m.title}"); return "Deleted #$id" }
        if (input.optString("body").isBlank() && input.optString("title").isBlank()) return "Not changed: pass a new body or title (or delete)."
        dao.update(m.copy(title = input.optString("title").ifBlank { m.title }, body = input.optString("body").ifBlank { m.body }, updatedAt = System.currentTimeMillis()))
        ctx.activity("Updated memory: ${m.title}")
        return "Updated #$id"
    }
}

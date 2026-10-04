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

class MemorySaveTool(private val dao: MemoryDao) : Tool {
    override val spec = ToolSpec("memory_save", "Remember something durable about the user for later: a fact, preference, person, decision or how-to. Save the moment you learn it. Write it so it makes sense on its own later.",
        schema(listOf("title", "body"),
            "title" to str("Short label"), "body" to str("The thing to remember, self-contained"),
            "kind" to enumOf("Type", "fact", "preference", "person", "decision", "how-to", "event"),
            "topics" to str("Comma-separated tags"), "pinned" to bool("Pin it so it is always in your context (use for core facts like the user's name)")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val id = dao.insert(MemoryRow(title = input.optString("title"), body = input.optString("body"), kind = input.optString("kind", "fact").ifBlank { "fact" },
            topics = input.optString("topics"), pinned = input.optBoolean("pinned")))
        ctx.activity("Remembered: ${input.optString("title")}", JSONObject().put("tool", "memory"))
        return "Saved memory #$id"
    }
}

class MemoryUpdateTool(private val dao: MemoryDao) : Tool {
    override val spec = ToolSpec("memory_update", "Correct or delete a memory by id when it turns out to be wrong or out of date.",
        schema(listOf("id"), "id" to int("Memory id"), "body" to str("New text"), "title" to str("New title"), "delete" to bool("Delete it instead")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val id = input.getLong("id"); val m = dao.get(id) ?: return "No memory #$id"
        if (input.optBoolean("delete")) { dao.delete(id); ctx.activity("Forgot: ${m.title}"); return "Deleted #$id" }
        dao.update(m.copy(title = input.optString("title").ifBlank { m.title }, body = input.optString("body").ifBlank { m.body }, updatedAt = System.currentTimeMillis()))
        ctx.activity("Updated memory: ${m.title}")
        return "Updated #$id"
    }
}

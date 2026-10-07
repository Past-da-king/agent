package com.past9.phoneaos.tools

import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.agent.strList
import com.past9.phoneaos.data.MemoryDao
import com.past9.phoneaos.data.MemoryRow
import org.json.JSONObject

/**
 * Skills: how-tos helpers leave for the next helper. When one struggles with something and gets it
 * done (a site that hides its prices, a sign-in that needs a trick), it writes down what worked, so
 * nobody has to fight the same thing twice. They live in memory as kind "skill".
 */
const val SKILL = "skill"

class SkillSaveTool(private val dao: MemoryDao) : Tool {
    override val spec = ToolSpec("skill_save", "Save how you got something done that was hard, so the next helper doesn't struggle: the steps that worked, in order, the traps you hit and how to spot them, and how to tell it worked. Use it after you struggled and then succeeded. Same name updates the skill (merge, don't lose what was there).",
        schema(listOf("name", "when", "steps"), "name" to str("Short name, e.g. 'Takealot prices behind the cookie wall'"),
            "when" to str("One line: when to use it"), "steps" to str("The procedure in markdown: steps, traps, how to check"), "topics" to strList("Tags: sites, apps, kinds of job")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val name = input.optString("name").trim().ifBlank { return "A skill needs a name." }
        val body = "**Use when:** ${input.optString("when").trim()}\n\n${input.optString("steps").trim()}"
        val topics = (0 until (input.optJSONArray("topics")?.length() ?: 0)).joinToString(", ") { input.getJSONArray("topics").getString(it) }
        val old = dao.byTitle(name)?.takeIf { it.kind == SKILL }
        val id = if (old != null) { dao.update(old.copy(body = body, topics = topics.ifBlank { old.topics }, updatedAt = System.currentTimeMillis())); old.id }
            else dao.insert(MemoryRow(title = name, body = body, kind = SKILL, topics = listOf("skill", topics).filter { it.isNotBlank() }.joinToString(", ")))
        ctx.activity("${if (old != null) "Updated" else "Saved"} a skill: $name", JSONObject().put("tool", "memory"))
        return "Skill #$id saved. The next helper doing this will see it."
    }
}

class SkillFindTool(private val dao: MemoryDao) : Tool {
    override val spec = ToolSpec("skill_find", "Look for skills earlier helpers left (how-tos for sites, apps and jobs that were hard). Check before you start anything fiddly, and again when you get stuck.",
        schema(listOf("query"), "query" to str("What you're trying to do, in plain words")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val hits = Recall.rank(input.optString("query"), dao.list().filter { it.kind == SKILL }, 4)
        return if (hits.isEmpty()) "No skills for that yet. If it turns out to be hard and you get it done, skill_save what worked."
            else hits.joinToString("\n\n---\n\n") { "## ${it.title}\n${it.body}" }
    }
}

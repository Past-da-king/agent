package com.past9.phoneaos.tools

import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.enumOf
import com.past9.phoneaos.agent.int
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.agent.strList
import com.past9.phoneaos.data.TriggerDao
import com.past9.phoneaos.data.TriggerRow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/**
 * Invisible helper agents. The main agent hands out pieces of work; each helper runs its own
 * loop with the same tools (minus talking to the user), all at once, and reports back.
 * The user only ever sees one line per helper in the chat.
 */
class DelegateTool(private val runHelper: suspend (task: String, label: String, ctx: ToolContext) -> String) : Tool {
    override val spec = ToolSpec("delegate", "Hand pieces of work to helper agents that run in parallel and report back (e.g. research three options at once, read five pages). Each task must be self-contained: say exactly what to find and what to return.",
        schema(listOf("tasks"), "tasks" to strList("1 to 5 self-contained task briefs")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val arr = input.optJSONArray("tasks") ?: return "No tasks given"
        val tasks = (0 until arr.length()).map { arr.getString(it) }.take(5)
        val results = coroutineScope {
            tasks.mapIndexed { i, t -> async { "## Helper ${i + 1}: ${t.take(80)}\n" + runCatching { runHelper(t, "Helper ${i + 1}", ctx) }.getOrElse { "Failed: ${it.message}" } } }.awaitAll()
        }
        return results.joinToString("\n\n")
    }
}

/** A hard stop before anything goes out in the user's name or costs money. */
class ApprovalTool : Tool {
    override val spec = ToolSpec("request_approval", "Show the user EXACTLY what you are about to do (send this email, book this, pay this) and wait for Approve or Decline. Required before sending, posting, buying, deleting or anything in their name.",
        schema(listOf("action", "details"), "action" to str("Short verb phrase, e.g. 'Send email to Lerato'"), "details" to str("Exactly what will happen: recipient, subject, full text, amount")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val a = ctx.ask("APPROVAL|${input.optString("action")}|${input.optString("details")}", listOf("Approve", "Decline"))
        return when (a) { "Approve" -> "APPROVED. Go ahead."; null -> "No answer (background run). Do NOT do it; leave it for the user."; else -> "DECLINED. Do not do it." }
    }
}

class ScheduleCreateTool(private val dao: TriggerDao, private val onChange: suspend (TriggerRow) -> Unit) : Tool {
    override val spec = ToolSpec("routine_create", "Create a routine: a prompt you will run later by yourself. kind 'at' = once at a time (spec ISO like 2026-10-06T07:30); 'daily' = every day (spec HH:mm); 'weekly' = on certain weekdays (spec 'SUN 18:00' or 'MON,WED,FRI 07:30'), use this for anything weekly, never daily-with-a-day-check; 'interval' = every N minutes (spec minutes, min 15); 'email' = when a new email matches a Gmail search (spec e.g. 'from:bank@x.com', needs Gmail connected); 'notification' = the moment a phone notification arrives (spec 'App name|keyword', keyword optional, app must be allowed in Connections).",
        schema(listOf("name", "kind", "spec", "prompt"), "name" to str("Short name"), "kind" to enumOf("When it runs", "at", "daily", "weekly", "interval", "email", "notification"),
            "spec" to str("Time/interval/query per kind"), "prompt" to str("What you will do each time, written as an instruction to yourself")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val kind = input.optString("kind")
        val spec = input.optString("spec").trim()
        val err = when (kind) {
            "at" -> if (parseWhen(spec) == null) "spec must be an ISO date-time" else if (parseWhen(spec)!! < System.currentTimeMillis()) "that time is in the past" else null
            "daily" -> if (!Regex("^\\d{1,2}:\\d{2}$").matches(spec)) "spec must be HH:mm" else null
            "weekly" -> if (com.past9.phoneaos.triggers.Routines.parseWeekly(spec) == null) "spec must be days then time, e.g. 'SUN 18:00' or 'MON,WED,FRI 07:30'" else null
            "interval" -> if ((spec.toIntOrNull() ?: 0) < 15) "spec must be minutes, at least 15" else null
            "email" -> null
            "notification" -> if (spec.substringBefore('|').isBlank()) "spec must start with the app name" else null
            else -> "unknown kind"
        }
        if (err != null) return "Not created: $err"
        val row = TriggerRow(name = input.optString("name"), kind = kind, spec = spec, prompt = input.optString("prompt"))
        val id = dao.upsert(row)
        onChange(row.copy(id = id))
        ctx.activity("New routine: ${row.name}", JSONObject().put("tool", "routines"))
        return "Routine #$id created."
    }
}

class ScheduleListTool(private val dao: TriggerDao) : Tool {
    override val spec = ToolSpec("routine_list", "List the routines (scheduled and triggered runs).", schema())
    override suspend fun run(input: JSONObject, ctx: ToolContext): String =
        dao.list().joinToString("\n") { "#${it.id} ${it.name} [${it.kind} ${it.spec}]${if (!it.enabled) " (paused)" else ""}: ${it.prompt.take(100)}" }.ifBlank { "No routines." }
}

class ScheduleDeleteTool(private val dao: TriggerDao, private val onDelete: suspend (Long) -> Unit) : Tool {
    override val spec = ToolSpec("routine_delete", "Delete a routine by id.", schema(listOf("id"), "id" to int("Routine id")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val id = input.getLong("id"); dao.delete(id); onDelete(id); ctx.activity("Removed routine #$id"); return "Deleted."
    }
}

package com.past9.phoneaos.tools

import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.bool
import com.past9.phoneaos.agent.enumOf
import com.past9.phoneaos.agent.int
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.agent.strList
import com.past9.phoneaos.data.TriggerDao
import com.past9.phoneaos.data.TriggerRow
import org.json.JSONObject

/**
 * Helper agents do ALL the work. The main agent writes each one a precise brief and sets it going
 * in the background, then ends its turn, so the user can keep talking to it while helpers run.
 * When a helper finishes, its result comes back to the main agent as a new message.
 */
class DelegateTool(private val start: suspend (task: String, label: String, model: String?, profile: String?, batch: String?) -> Long) : Tool {
    override val spec = ToolSpec("delegate", "Set helper agents working in the background. They do ALL the actual work: browsing, searching the web, apps, booking, " +
        "sending, files, code, machines, creating routines. Each brief must stand alone: the goal, everything you know that matters (names, dates, the user's " +
        "preferences and constraints from memory), exactly what to do or find, what to hand back, and anything that needs the user's approval. Returns at once; " +
        "each helper's result arrives later as a message from it. Several briefs run at the same time.",
        schema(listOf("tasks"), "tasks" to strList("1 to 5 self-contained briefs, one per helper"),
            "labels" to strList("A 2 to 4 word name per helper, same order (e.g. 'Uber to airport'). The user sees these."),
            "models" to strList("Optional: which helper model runs each brief, same order (an id or name from YOUR HELPERS). Leave out for the default."),
            "profiles" to strList("Optional: the user's profile each helper works in, same order (e.g. 'Northwind work'). It can then only use that profile's connected accounts."),
            "together" to bool("These briefs are parts of ONE answer: hold every result until all are done and hand them to you as one message. Default false.")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val arr = input.optJSONArray("tasks") ?: return "No tasks given"
        val labels = input.optJSONArray("labels"); val models = input.optJSONArray("models"); val profiles = input.optJSONArray("profiles")
        val tasks = (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }.take(5)
        if (tasks.isEmpty()) return "No tasks given"
        val batch = if (input.optBoolean("together") && tasks.size > 1) "b" + java.util.UUID.randomUUID().toString().take(8) else null
        val started = tasks.mapIndexed { i, t ->
            val label = labels?.optString(i)?.trim()?.takeIf { it.isNotBlank() }?.take(40) ?: t.trim().split(Regex("\\s+")).take(4).joinToString(" ")
            val id = runCatching { start(t, label, models?.optString(i)?.takeIf { it.isNotBlank() }, profiles?.optString(i)?.takeIf { it.isNotBlank() }, batch) }
                .getOrElse { return "Couldn't start helpers: ${it.message}" }
            "#$id $label"
        }
        return "Started: ${started.joinToString(", ")}. They run in the background and " +
            (if (batch != null) "their results will arrive TOGETHER as one message once all are done. " else "each result will arrive as a message. ") +
            "Tell the user in one short line what you set going, then end your turn. Do not wait or poll."
    }
}

/** What the helpers are doing right now, so the main agent can answer "how's it going?". */
class HelpersStatusTool(private val status: suspend () -> String) : Tool {
    override val spec = ToolSpec("helpers_status", "See every helper: running ones with their latest steps, and recently finished ones with their results.", schema())
    override suspend fun run(input: JSONObject, ctx: ToolContext): String = status()
}

/** Tell a working helper more: it reads it before its next step, or restarts with it. */
class HelperSteerTool(private val steer: suspend (Long, String, Boolean) -> String) : Tool {
    override val spec = ToolSpec("helper_steer", "Send a helper more information or a correction while it works ('the user means the 7pm show, not 9pm'). By default it reads it before its next step and keeps going. restart true: stop it and start it again with this information, for when it's heading the wrong way. On a finished helper it sends it back to work.",
        schema(listOf("id", "message"), "id" to int("Helper id"), "message" to str("What it needs to know, as an instruction to it"), "restart" to bool("Stop and start again with this (default false)")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String = steer(input.optLong("id"), input.optString("message"), input.optBoolean("restart"))
}

/** Send a helper that gave up (or came back thin) back to work, in its own conversation. */
class HelperPushTool(private val push: suspend (Long, String) -> String) : Tool {
    override val spec = ToolSpec("helper_push", "Send a finished helper back to work: it keeps everything it found and carries on with your message. Use it when a helper says it can't, or its result is thin: name a concrete next thing to try and tell it you believe it can do it.",
        schema(listOf("id", "message"), "id" to int("Helper id"), "message" to str("What to try next, plus encouragement")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String = push(input.optLong("id"), input.optString("message"))
}

/** Stop a helper that is no longer needed (the user changed their mind, or it is going the wrong way). */
class HelperStopTool(private val stop: suspend (Long) -> Boolean) : Tool {
    override val spec = ToolSpec("helper_stop", "Stop a running helper by its id (from delegate or helpers_status). Then delegate again with a better brief if the work is still needed.",
        schema(listOf("id"), "id" to int("Helper id")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String =
        if (stop(input.optLong("id"))) "Stopped helper #${input.optLong("id")}." else "No running helper #${input.optLong("id")}."
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

package com.past9.phoneaos.tools

import android.content.Context
import com.past9.phoneaos.App
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.bool
import com.past9.phoneaos.agent.enumOf
import com.past9.phoneaos.agent.int
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.agent.strList
import com.past9.phoneaos.triggers.Check
import com.past9.phoneaos.triggers.QuietHours
import com.past9.phoneaos.triggers.Wake
import com.past9.phoneaos.triggers.WakePlan
import com.past9.phoneaos.triggers.Wakes
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

private fun whenText(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime().withSecond(0).withNano(0).toString().replace('T', ' ')

class WakeScheduleTool(private val context: Context) : Tool {
    override val spec = ToolSpec("wake_schedule",
        "Schedule a follow-up: wake yourself later FOR A REASON, to do a job. Use it whenever something will need following up (a reply you're waiting for, an application to chase, a deadline coming, a price to recheck, a meeting's aftermath). " +
            "Give the cheapest check that tells, with no AI, whether there's anything to do; you are only woken when it finds something: " +
            "'notification' = a phone notification arrived since now from app (e.g. Gmail) mentioning any of words (e.g. Brightwater); 'task_open' = only if that to-do is still not done; " +
            "'script' = a tested Node script with the watcher contract (last line {\"fire\":bool,\"message\":\"...\"}); 'none' = always wake (only when you must act regardless). " +
            "With retry_every_minutes and max_tries the check looks again (doubling the gap) before giving up. Wakes within 10 minutes of each other go off together as one run, so check wake_list and reuse a nearby time. Never use this for repeating check-ins.",
        schema(listOf("reason", "job", "check"),
            "reason" to str("Why, in a few words: 'Brightwater reply', 'Chase Northwind application'"),
            "job" to str("What you'll do when it fires, as an instruction to yourself"),
            "at" to str("When, ISO local time like 2026-10-20T08:30"),
            "in_minutes" to int("Or: this many minutes from now"),
            "check" to enumOf("How code decides whether to wake you", *Check.TYPES.filter { it != Check.HEADS_UP && it != Check.TRANSCRIPT }.toTypedArray()),
            "app" to str("notification check: app name, e.g. Gmail, Outlook, WhatsApp (blank = any allowed app)"),
            "words" to strList("notification check: any of these words"),
            "task_id" to int("task_open check: the to-do"),
            "script" to str("script check: the tested Node script"),
            "retry_every_minutes" to int("If the check finds nothing, look again after this long (at least 15), doubling each time"),
            "max_tries" to int("How many looks in all (default 1)"),
            "urgent" to bool("May break the user's quiet hours (rarely)")))

    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val now = System.currentTimeMillis()
        val at = when {
            input.has("in_minutes") -> now + input.optLong("in_minutes") * 60_000
            input.optString("at").isNotBlank() -> parseWhen(input.optString("at")) ?: return "Not scheduled: 'at' must be ISO like 2026-10-20T08:30."
            else -> return "Not scheduled: give 'at' or 'in_minutes'."
        }
        val words = input.optJSONArray("words")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        val check = Check(input.optString("check", Check.NONE), input.optString("app"), words, input.optLong("task_id"), input.optString("script"))
        if (check.type == Check.NOTIFICATION) {
            val allowed = App.graph(context).settings.state.value.notifApps
            if (allowed.isEmpty()) return "Not scheduled: the phone isn't reading any app's notifications, so this check could never see anything. Ask the user to allow the app in Connections, or use another check."
        }
        // Follow-ups grow with use: more open ones are allowed the more the user has been talking to the agent.
        val talked = App.graph(context).db.chat().userSince(now - 14 * 24 * 3_600_000L)
        val budget = WakePlan.followUpBudget(talked)
        val open = Wakes.Store(context).all().count { it.source == "agent" }
        if (open >= budget) return "Not scheduled: $open follow-ups are already open and the limit for now is $budget (it grows as the user uses you more). Merge this into one you already have (wake_list) or cancel one that no longer matters (wake_cancel)."
        val w = Wake(reason = input.optString("reason"), job = input.optString("job"), at = at, check = check, urgent = input.optBoolean("urgent"),
            retryMinutes = input.optInt("retry_every_minutes"), maxTries = input.optInt("max_tries", 1).coerceIn(1, 8))
        val (id, err) = Wakes.engine(context).schedule(w, now)
        if (err != null) return "Not scheduled: $err"
        Wakes.rearm(context)
        ctx.activity("Follow-up ${whenText(at)}: ${w.reason}", JSONObject().put("tool", "routines"))
        val near = Wakes.Store(context).all().filter { it.id != id && kotlin.math.abs(it.at - at) <= WakePlan.MERGE }
        return "Wake #$id set for ${whenText(at)} (${check.type} check)." + if (near.isNotEmpty()) " It goes off together with: ${near.joinToString { "#${it.id} ${it.reason}" }}." else ""
    }
}

class WakeListTool(private val context: Context) : Tool {
    override val spec = ToolSpec("wake_list", "The follow-ups (wakes) scheduled now: when, why, the check, and which go off together. Look here before adding one.", schema())
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val all = Wakes.Store(context).all()
        if (all.isEmpty()) return "No wakes scheduled. Quiet hours: ${Wakes.quiet(context)}."
        val groups = WakePlan.clusters(all, Wakes.quiet(context), ZoneId.systemDefault())
        return "Quiet hours: ${Wakes.quiet(context)}.\n" + groups.joinToString("\n") { g ->
            g.joinToString("\n") { w -> "#${w.id} ${whenText(w.at)} [${w.source}, ${w.check.type}${if (w.tries > 0) ", try ${w.tries + 1}/${w.maxTries}" else ""}] ${w.reason}" } +
                if (g.size > 1) "\n  (these ${g.size} go off together)" else ""
        }
    }
}

class WakeCancelTool(private val context: Context) : Tool {
    override val spec = ToolSpec("wake_cancel", "Cancel a scheduled wake that is no longer needed (the reply came, the thing got done).", schema(listOf("id"), "id" to int("Wake id")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val id = input.optLong("id")
        val row = App.graph(context).db.triggers().get(id)?.takeIf { it.kind == Wakes.KIND } ?: return "No wake #$id."
        App.graph(context).db.triggers().delete(id)
        Wakes.rearm(context)
        return "Cancelled wake #$id (${row.name})."
    }
}

class QuietHoursTool(private val context: Context) : Tool {
    override val spec = ToolSpec("quiet_hours", "See or set the user's quiet hours: no notifications from wakes then, except urgent ones (reminders they set for a time still ring). With no range it reports them.",
        schema(emptyList(), "range" to str("Local time like 23:00-07:00, or 'off'"), "after_meetings" to bool("Read transcripts after calendar meetings (default on)")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val s = App.graph(context).settings
        input.optString("range").takeIf { it.isNotBlank() }?.let { r ->
            val q = QuietHours.parse(r) ?: return "Not changed: use a range like 23:00-07:00, or 'off'."
            s.setExtra("quiet_hours", q.toString())
        }
        if (input.has("after_meetings")) s.setExtra("after_meeting", if (input.optBoolean("after_meetings")) null else "off")
        Wakes.rearm(context)
        return "Quiet hours: ${Wakes.quiet(context)}. After-meeting action items: ${if (Wakes.afterMeeting(context)) "on" else "off"}. Calendar: ${if (com.past9.phoneaos.triggers.Calendar.allowed(context)) "readable" else "not allowed yet (Settings > Background > Reminders on time)"}."
    }
}

package com.past9.phoneaos.tools

import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.int
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.data.NotificationDao
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What arrived on the phone from the apps the user allowed (banking SMS, WhatsApp, deliveries...). */
class NotificationsTool(private val dao: NotificationDao, private val allowed: () -> Set<String>) : Tool {
    override val spec = ToolSpec("notifications_read", "Read recent phone notifications from the apps the user allowed (messages, bank alerts, deliveries, reminders). Filter by app name and/or words.",
        schema(emptyList(), "app" to str("App name to filter, e.g. WhatsApp"), "query" to str("Words to look for"), "hours" to int("How far back, default 24")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        if (allowed().isEmpty()) return "The user hasn't allowed any apps yet. They can pick apps in Connections, Notifications."
        val since = System.currentTimeMillis() - input.optInt("hours", 24).coerceIn(1, 24 * 7) * 3_600_000L
        val app = input.optString("app"); val q = Recall.terms(input.optString("query"))
        val rows = dao.since(since).filter { (app.isBlank() || it.app.contains(app, true)) && (q.isEmpty() || q.any { t -> it.title.contains(t, true) || it.text.contains(t, true) }) }
        ctx.activity("Read ${rows.size} notification${if (rows.size == 1) "" else "s"}${if (app.isNotBlank()) " from $app" else ""}", JSONObject().put("tool", "notifications"))
        val fmt = DateTimeFormatter.ofPattern("EEE HH:mm").withZone(ZoneId.systemDefault())
        return rows.take(80).joinToString("\n") { "${fmt.format(Instant.ofEpochMilli(it.postedAt))} · ${it.app} · ${it.title}: ${it.text.take(300)}" }.ifBlank { "Nothing matching in that window." }
    }
}

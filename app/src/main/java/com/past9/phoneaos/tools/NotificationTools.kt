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

/**
 * Use a button a notification carries (Mark as read, Archive, Reply) without opening the app. Only for the apps the
 * user lets the agent read, never a banking or money app, and the user approves each press.
 */
class NotificationActTool(private val allowed: () -> Set<String>) : Tool {
    override val spec = ToolSpec("notification_act", "Use a button on a notification that is showing on the phone right now (Reply, Mark as read, Archive...), without opening the app. " +
        "Leave action out to list the showing notifications and their buttons. The user approves every press on the phone; a reply is sent in their name.",
        schema(emptyList(), "app" to str("App name, e.g. WhatsApp"), "query" to str("Words in the notification (who it is from, what it says)"),
            "action" to str("The button to press, by its words, e.g. Reply"), "reply" to str("The text to send, for a button that takes a reply")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val reader = com.past9.phoneaos.system.NotificationReader.live ?: return "Notification access is not on (or the phone has not bound it yet). The user switches it on in Connections, Notifications."
        val ok = allowed()
        if (ok.isEmpty()) return "The user hasn't allowed any apps yet. They can pick apps in Connections, Notifications."
        val app = input.optString("app").trim(); val q = input.optString("query").trim(); val want = input.optString("action").trim(); val reply = input.optString("reply")
        val pm = reader.packageManager
        fun label(pkg: String) = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        val money = com.past9.phoneaos.screen.Screens.config(reader).blocklist
        fun words(n: android.app.Notification) = (n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()) to
            ((n.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT))?.toString().orEmpty())
        val hits = (runCatching { reader.activeNotifications }.getOrNull() ?: emptyArray()).filter { s ->
            val n = s.notification ?: return@filter false
            val (title, text) = words(n)
            s.packageName in ok && s.packageName != reader.packageName && !n.actions.isNullOrEmpty() && money.blocked(s.packageName, label(s.packageName)) == null &&
                (app.isBlank() || label(s.packageName).contains(app, true) || s.packageName.equals(app, true)) && (q.isBlank() || title.contains(q, true) || text.contains(q, true))
        }
        fun list() = hits.take(12).joinToString("\n") { s -> val (title, text) = words(s.notification)
            "${label(s.packageName)} · $title: ${text.take(160)} [buttons: ${s.notification.actions.joinToString { a -> a.title.toString() + if (a.remoteInputs.isNullOrEmpty()) "" else " (takes a reply)" }}]" }
        if (hits.isEmpty()) return "No showing notification with buttons matches, from the apps the user allowed (banking and money apps are never used)."
        if (want.isBlank()) return list()
        val hit = hits.firstOrNull { s -> s.notification.actions.any { it.title?.toString().orEmpty().contains(want, true) } } ?: return "None of them has a button \"$want\":\n" + list()
        val a = hit.notification.actions.first { it.title?.toString().orEmpty().contains(want, true) }
        val inputs = a.remoteInputs
        if (reply.isNotBlank() && inputs.isNullOrEmpty()) return "\"${a.title}\" does not take a reply. Leave reply out to press it."
        if (reply.isBlank() && !inputs.isNullOrEmpty()) return "\"${a.title}\" needs the text to send: give reply."
        val (title, text) = words(hit.notification); val name = label(hit.packageName)
        val what = if (reply.isNotBlank()) "Reply to $title in $name" else "Press \"${a.title}\" on a $name notification"
        when (kotlinx.coroutines.withTimeoutOrNull(80_000) { ctx.ask("APPROVAL|$what|${if (reply.isNotBlank()) "It sends, in your name: \"$reply\"\n" else ""}The notification: $title: ${text.take(200)}", listOf("Approve", "Decline")) }) {
            "Approve" -> {}
            null -> return "Not done: this needs the user's yes on the phone and no answer came in time. It was NOT declined."
            else -> return "The user declined. Not done."
        }
        val intent = android.content.Intent()
        if (reply.isNotBlank()) {
            val b = android.os.Bundle(); inputs!!.forEach { b.putCharSequence(it.resultKey, reply) }
            android.app.RemoteInput.addResultsToIntent(inputs, intent, b)
        }
        runCatching { a.actionIntent.send(reader, 0, intent) }.onFailure { return "The button could not be pressed: ${it.message}. If it sends something, it MAY have gone: check before trying again." }
        ctx.activity(what, JSONObject().put("tool", "notifications"))
        return if (reply.isNotBlank()) "Sent the reply through the $name notification. It is not retried: read the chat or wait for the next notification to confirm it landed." else "Pressed \"${a.title}\" on the $name notification."
    }
}

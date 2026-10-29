package com.past9.phoneaos.remote

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.past9.phoneaos.App
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.system.AndroidPhone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The tools this phone lends to the agent on a linked computer (protocol s11): its browser, the notifications the
 * person let it read, its files, notifications and questions with buttons. They are the phone's own tools, the same
 * code the phone's agent uses. Each group can be switched off in Settings > Linked devices.
 */
object DeviceTools {
    /** group -> on by default. Shell (run_code) is never lent. */
    val GROUPS = linkedMapOf("browser" to true, "notifications" to true, "files" to true, "approval" to true, "phone" to true, "location" to true)
    val LABELS = mapOf("browser" to "Browser", "notifications" to "Notifications it can read", "files" to "Files", "approval" to "Questions and approvals", "phone" to "Notifications to you", "location" to "Your location (asked once)")

    fun groupOf(name: String): String? = when {
        name.startsWith("browser_") || name == "site_probe" || name == "site_catalog" -> "browser"
        name.startsWith("notifications") -> "notifications"
        name == "phone_files" -> "files"
        name == "ask_user" || name == "request_approval" -> "approval"
        name == "notify_user" -> "phone"
        name == "phone_location" -> "location"
        else -> null
    }

    fun allowed(context: Context, group: String): Boolean =
        App.graph(context).settings.extra("remote_share_$group")?.let { it == "1" } ?: (GROUPS[group] ?: false)

    fun setAllowed(context: Context, group: String, on: Boolean) = App.graph(context).settings.setExtra("remote_share_$group", if (on) "1" else "0")

    private fun all(context: Context): List<Tool> {
        val g = App.graph(context)
        return com.past9.phoneaos.tools.browserTools(context) + listOf(
            com.past9.phoneaos.tools.NotificationsTool(g.db.notifications()) { g.settings.state.value.notifApps },
            com.past9.phoneaos.tools.PhoneFilesTool(context),
            com.past9.phoneaos.tools.AskUserTool(), com.past9.phoneaos.tools.ApprovalTool(), com.past9.phoneaos.tools.NotifyTool(),
            com.past9.phoneaos.tools.PhoneLocationTool(context),
        )
    }

    /** The tools this phone offers right now (only switched-on groups). */
    fun tools(context: Context): List<Pair<Tool, String>> =
        all(context).mapNotNull { t -> groupOf(t.spec.name)?.takeIf { allowed(context, it) }?.let { t to it } }

    private val READS = listOf("read", "look", "screenshot", "console", "catalog", "list", "search", "wait")

    /** DeviceTool[] for hello: { name, description, params, group, readOnly }. */
    fun catalogue(context: Context): JSONArray = JSONArray(tools(context).map { (t, group) ->
        JSONObject().put("name", t.spec.name).put("description", t.spec.description).put("params", t.spec.schema).put("group", group)
            .put("readOnly", group == "notifications" || group == "location" || READS.any { t.spec.name.contains(it) })
    })

    /** Run one lent tool. Returns (ok, value or error); a value with pictures is a list of text and image parts. */
    suspend fun run(context: Context, name: String, args: JSONObject, deadlineMs: Long, session: RemoteSession? = null): Pair<Boolean, Any> {
        val (tool, _) = tools(context).firstOrNull { it.first.spec.name == name }
            ?: return false to "This phone doesn't lend $name (it's off in its settings, or it has no such tool)."
        val out = withTimeoutOrNull(deadlineMs.coerceIn(1_000, 600_000)) { runCatching { tool.run(args, LentContext(context, session)) } }
            ?: return false to "The phone didn't finish $name in time."
        return out.fold({ true to withImages(it) }, { false to (it.message ?: it::class.java.simpleName) })
    }

    private val IMAGE = Regex("""\[\[image:([^\]]+)]]""")

    /**
     * The phone's tools hand pictures over as [[image:/path]] lines. Over the wire they travel as parts
     * (protocol s11): { type: "text", text } and { type: "image", mime, data (base64) }.
     */
    fun withImages(text: String): Any {
        val paths = IMAGE.findAll(text).map { it.groupValues[1] }.toList()
        if (paths.isEmpty()) return text
        val parts = JSONArray().put(JSONObject().put("type", "text").put("text", IMAGE.replace(text, "").trim()))
        for (p in paths) {
            val f = File(p)
            if (!f.isFile) continue
            val mime = if (p.endsWith(".png", true)) "image/png" else if (p.endsWith(".webp", true)) "image/webp" else "image/jpeg"
            parts.put(JSONObject().put("type", "image").put("mime", mime).put("data", java.util.Base64.getEncoder().encodeToString(f.readBytes())))
        }
        return parts
    }

    /** A tool running for the agent on another device: questions come up as a notification with buttons. */
    private class LentContext(private val context: Context, private val session: RemoteSession?) : ToolContext {
        override val agentLabel = "linked computer"
        override suspend fun activity(text: String, meta: JSONObject): Long = 0
        override suspend fun updateActivity(id: Long, text: String, meta: JSONObject) {}
        override suspend fun ask(question: String, options: List<String>): String? = RemoteAsk.ask(context, question, options, session = session)
        override suspend fun notify(title: String, body: String) = App.graph(context).phone.notify(title, body)
    }
}

/** A question from the agent on another device: a notification with buttons, and the app's own question card in the chat. */
object RemoteAsk {
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    /** Chat row id -> notification id, for questions shown in the chat. */
    private val rows = ConcurrentHashMap<Long, Int>()
    private val next = AtomicInteger(7_100)

    fun itemIds(): Set<Long> = rows.keys.toSet()

    /** Answered by tapping the card in the chat. False when the row isn't one of these questions. */
    fun answerItem(itemId: Long, option: String): Boolean {
        val id = rows[itemId] ?: return false
        answer(id, option); return true
    }

    suspend fun ask(context: Context, question: String, options: List<String>, timeoutMs: Long = 600_000, session: RemoteSession? = null): String? {
        val id = next.incrementAndGet()
        val d = CompletableDeferred<String>()
        waiting[id] = d
        val opts = options.ifEmpty { listOf("Yes", "No") }.take(3)
        // The same question as a card in the chat (the question and approval cards the app already draws).
        var row: Long? = null
        if (session != null) runCatching {
            val now = System.currentTimeMillis()
            val item = com.past9.phoneaos.data.ChatItem(session.ids.local("lent-ask-$id-$now", now), "question", question,
                JSONObject().put("options", JSONArray(opts)).put("whyBy", "Your agent").toString(), now)
            session.db.chat().insert(item); row = item.id; rows[item.id] = id
        }
        // request_approval asks "APPROVAL|<action>|<details>".
        val parts = question.split('|', limit = 3)
        val approval = parts.size == 3 && parts[0] == "APPROVAL"
        val title = if (approval) "Approve: ${parts[1]}?" else "Your agent asks"
        val text = if (approval) parts[2] else question
        val b = NotificationCompat.Builder(context, AndroidPhone.CH_MESSAGES).setSmallIcon(com.past9.phoneaos.system.Identity.statIcon(context, App.graph(context).settings.state.value.mascot))
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setAutoCancel(true)
        opts.forEachIndexed { i, o ->
            val pi = PendingIntent.getBroadcast(context, id * 10 + i, Intent(context, RemoteAnswerReceiver::class.java).putExtra("id", id).putExtra("option", o),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(0, o, pi)
        }
        runCatching { NotificationManagerCompat.from(context).notify(id, b.build()) }
        val answer = try { withTimeoutOrNull(timeoutMs) { d.await() } } finally { waiting.remove(id) }
        row?.let { r ->
            rows.remove(r)
            runCatching { session!!.db.chat().get(r)?.let { session.db.chat().update(it.copy(meta = JSONObject(it.meta).put("answer", answer ?: "No answer").toString())) } }
        }
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
        return answer
    }

    fun answer(id: Int, option: String) { waiting.remove(id)?.complete(option) }
}

class RemoteAnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        RemoteAsk.answer(id, intent.getStringExtra("option") ?: return)
        NotificationManagerCompat.from(context).cancel(id)
    }
}

/** Device commands (protocol s5.2): things the agent on a computer does on this phone. Each is acked. */
class DeviceCommands(private val context: Context, private val session: RemoteSession) {
    suspend fun handle(c: JSONObject) {
        val id = c.optString("id")
        if (id.isBlank()) return
        when (c.optString("kind")) {
            "tool" -> {
                val (ok, value) = DeviceTools.run(context, c.optString("tool"), c.optJSONObject("args") ?: JSONObject(), c.optLong("deadlineMs", 120_000), session)
                runCatching { session.call("device.result", listOf(id, ok, value)) }
                return
            }
            else -> {
                val (ok, note) = runCatching { act(c) }.getOrElse { false to (it.message ?: "failed") }
                runCatching { session.call("device.ack", listOf(id, ok, note)) }
            }
        }
    }

    private suspend fun act(c: JSONObject): Pair<Boolean, String> = when (c.optString("kind")) {
        "open_url" -> {
            val url = c.optString("url")
            if (url.startsWith("/v1/")) receive(c.put("path", url), open = true)
            else if (url.startsWith("https://") || url.startsWith("http://")) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true to "opened"
            } else false to "Not a web address."
        }
        "send_file" -> receive(c, open = false)
        "show_image" -> receive(c, open = true)
        "notify" -> { App.graph(context).phone.notify(c.optString("title").ifBlank { "Your agent" }, c.optString("body")); true to "shown" }
        else -> false to "This phone doesn't know ${c.optString("kind")}."
    }

    /** Download the file with this phone's pass into Downloads, then a notification that opens it. */
    private fun receive(c: JSONObject, open: Boolean): Pair<Boolean, String> {
        val (api, url) = session.downloader() ?: return false to "Not connected."
        val src = c.optString("url").takeIf { it.startsWith("/v1/") } ?: c.optString("path").ifBlank { c.optString("url") }
        if (src.isBlank()) return false to "No file named."
        val name = c.optString("name").ifBlank { Uri.decode(src.substringAfterLast('/').substringAfterLast("path=")).substringAfterLast('/').substringAfterLast('\\') }.ifBlank { "file" }
        var saved: Uri? = null
        var mime = "application/octet-stream"
        api.download(url, src, session.pass) { input, type ->
            mime = type?.substringBefore(';')?.takeIf { it.isNotBlank() } ?: mimeOf(name)
            saved = save(name, mime, input)
        }
        val uri = saved ?: return false to "Couldn't save it."
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (open) runCatching { context.startActivity(view) }
        val pi = PendingIntent.getActivity(context, name.hashCode(), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, AndroidPhone.CH_MESSAGES).setSmallIcon(com.past9.phoneaos.system.Identity.statIcon(context, App.graph(context).settings.state.value.mascot))
            .setContentTitle(name).setContentText("Saved to Downloads. Tap to open.").setContentIntent(pi).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(context).notify(name.hashCode(), n) }
        return true to "saved to Downloads as $name"
    }

    private fun save(name: String, mime: String, input: java.io.InputStream): Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = context.contentResolver
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime); put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("Downloads is not available.")
            cr.openOutputStream(uri)!!.use { input.copyTo(it) }
            cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return uri
        }
        // Android 9: the app's own folder, opened through its file provider (no storage permission needed).
        val f = File(context.filesDir, "downloads/linked/$name").apply { parentFile?.mkdirs() }
        f.outputStream().use { input.copyTo(it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    }

    private fun mimeOf(name: String): String =
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
}

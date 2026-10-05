package com.past9.phoneaos.tools

import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.sharedHttp
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.agent.strList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class NowTool : Tool {
    override val spec = ToolSpec("now", "The current date, time and timezone on the phone.", schema())
    override suspend fun run(input: JSONObject, ctx: ToolContext): String =
        ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm z (xxx)"))
}

class AskUserTool : Tool {
    override val spec = ToolSpec("ask_user", "Ask the user a question with tappable answer buttons and wait for the reply. Use for real choices only; do the rest yourself.",
        schema(listOf("question", "options"), "question" to str("The question, one short line"), "options" to strList("2 to 4 short button labels")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val arr = input.optJSONArray("options")
        val opts = (0 until (arr?.length() ?: 0)).map { arr!!.getString(it) }.ifEmpty { listOf("Yes", "No") }
        return ctx.ask(input.optString("question"), opts)?.let { "The user answered: $it" }
            ?: "Nobody could answer right now (this is a background run). Make the safe choice or leave it for the user."
    }
}

class NotifyTool : Tool {
    override val spec = ToolSpec("notify_user", "Send the user a phone notification. Use for results of background work or anything time-sensitive.",
        schema(listOf("title", "body"), "title" to str("Short title"), "body" to str("One or two lines")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String { ctx.notify(input.optString("title"), input.optString("body")); return "Notified." }
}

/** Plain HTTP fetch for pages that do not need a real browser (APIs, RSS, simple pages). */
class WebFetchTool : Tool {
    override val spec = ToolSpec("web_fetch", "Fetch a URL over HTTP and return its text (HTML is stripped). Fast. For sites that need JavaScript, sign-in or clicking, use the browser_* tools instead.",
        schema(listOf("url"), "url" to str("http(s) URL")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val url = input.optString("url")
        val id = ctx.activity("Reading $url", JSONObject().put("tool", "web"))
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Mobile").build()
        sharedHttp.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            val type = res.header("Content-Type").orEmpty()
            val text = if (type.contains("html")) htmlToText(body) else body
            ctx.updateActivity(id, "Read ${res.request.url.host} · ${res.code}")
            "HTTP ${res.code}\n" + text.take(20_000)
        }
    }

    companion object {
        fun htmlToText(html: String): String = html
            .replace(Regex("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>|</tr>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n\n").trim()
    }
}

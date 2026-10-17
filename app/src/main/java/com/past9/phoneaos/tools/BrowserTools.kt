package com.past9.phoneaos.tools

import android.content.Context
import com.past9.phoneaos.agent.AskOptions
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.bool
import com.past9.phoneaos.agent.enumOf
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.browser.BrowserEngine
import com.past9.phoneaos.browser.BrowserService
import org.json.JSONObject

/** The browser tools share one engine; they start the background service on first use. */
abstract class BrowserTool(protected val context: Context) : Tool {
    // Each agent drives its own browser, so helpers can browse in parallel without fighting.
    protected suspend fun engine(ctx: ToolContext): BrowserEngine =
        BrowserService.await(context, ctx.agentLabel, profiles().firstOrNull { it.equals(lastProfile[ctx.agentLabel], true) } ?: profiles().first())

    companion object {
        /** Which browser profile each agent last chose with browser_open. */
        val lastProfile = java.util.concurrent.ConcurrentHashMap<String, String>()
        var profiles: () -> List<String> = { listOf("Personal") }
    }
}

class BrowserOpenTool(c: Context) : BrowserTool(c) {
    override val spec = ToolSpec("browser_open", "Open a URL in your own background browser (a real phone browser that keeps working while the user is in other apps). Returns the page outline with numbered elements. Pick a profile (the user's browser accounts, e.g. Personal or Work) when it matters which account is signed in.",
        schema(listOf("url"), "url" to str("URL or domain"), "profile" to str("Browser profile name; default is the first one"),
            "desktop" to bool("Load the site as a desktop browser (WhatsApp Web and other desktop-first apps refuse a phone). Stays on for later pages until you pass false.")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val url = input.optString("url")
        input.optString("profile").takeIf { it.isNotBlank() }?.let { want -> profiles().firstOrNull { it.equals(want, true) }?.let { lastProfile[ctx.agentLabel] = it } }
        val id = ctx.activity("Browsing $url", JSONObject().put("tool", "browser"))
        val e = engine(ctx)
        if (input.has("desktop")) e.setDesktop(input.optBoolean("desktop"))
        val p = e.goto(url)
        ctx.updateActivity(id, "Opened ${p.title.ifBlank { p.url }}", JSONObject().put("tool", "browser").put("url", p.url))
        return e.snapshot()
    }
}

class BrowserReadTool(c: Context) : BrowserTool(c) {
    override val spec = ToolSpec("browser_read", "Re-read the current page: URL, numbered interactive elements and visible text.", schema())
    override suspend fun run(input: JSONObject, ctx: ToolContext) = engine(ctx).snapshot()
}

class BrowserClickTool(c: Context) : BrowserTool(c) {
    override val spec = ToolSpec("browser_click", "Click an element: by its [number] from the outline (preferred), a CSS selector, or its visible text.",
        schema(emptyList(), "ref" to str("Element number from the outline"), "selector" to str("CSS selector"), "text" to str("Visible text")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val e = engine(ctx)
        val r = e.click(input.optString("ref").ifBlank { null }, input.optString("selector").ifBlank { null }, input.optString("text").ifBlank { null })
        ctx.activity("Clicked ${input.optString("text").ifBlank { r.removePrefix("clicked ") }.take(50)}", JSONObject().put("tool", "browser"))
        return r + "\n\n" + e.snapshot(8_000)
    }
}

class BrowserTypeTool(c: Context) : BrowserTool(c) {
    override val spec = ToolSpec("browser_type", "Type text into a field (by [number], selector, or the focused field). Set submit to press Enter.",
        schema(listOf("text"), "text" to str("What to type"), "ref" to str("Element number"), "selector" to str("CSS selector"), "submit" to bool("Press Enter after")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val e = engine(ctx)
        val r = e.type(input.optString("ref").ifBlank { null }, input.optString("selector").ifBlank { null }, input.optString("text"), input.optBoolean("submit"))
        ctx.activity("Typed into the page", JSONObject().put("tool", "browser"))
        return r + if (input.optBoolean("submit")) "\n\n" + e.snapshot(8_000) else ""
    }
}

class BrowserKeyTool(c: Context) : BrowserTool(c) {
    override val spec = ToolSpec("browser_press_key", "Press a key in the page: Enter, Tab or Escape.", schema(listOf("key"), "key" to enumOf("Key", "Enter", "Tab", "Escape")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String { val e = engine(ctx); return e.pressKey(input.optString("key", "Enter")) + "\n\n" + e.snapshot(8_000) }
}

class BrowserWaitTool(c: Context) : BrowserTool(c) {
    override val spec = ToolSpec("browser_wait_for", "Wait until the page shows some text (up to 30 seconds), for slow pages and sign-in steps.",
        schema(listOf("text"), "text" to str("Text to wait for"), "seconds" to com.past9.phoneaos.agent.int("Max seconds, default 15")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val e = engine(ctx); val ok = e.waitForText(input.optString("text"), input.optInt("seconds", 15).coerceIn(1, 30))
        return (if (ok) "It's there." else "Didn't appear in time.") + "\n\n" + e.snapshot(8_000)
    }
}

class BrowserScrollTool(c: Context) : BrowserTool(c) {
    override val spec = ToolSpec("browser_scroll", "Scroll the page down (or up) one screen and return the new outline.", schema(emptyList(), "up" to bool("Scroll up instead")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String { val e = engine(ctx); e.scroll(!input.optBoolean("up")); return e.snapshot(8_000) }
}

class BrowserBackTool(c: Context) : BrowserTool(c) {
    override val spec = ToolSpec("browser_back", "Go back one page.", schema())
    override suspend fun run(input: JSONObject, ctx: ToolContext): String { val e = engine(ctx); e.back(); return e.snapshot(8_000) }
}

class BrowserScreenshotTool(c: Context) : BrowserTool(c) {
    override val spec = ToolSpec("browser_show_user", "Show the user a screenshot of the page in the chat (for receipts, results, things they should see).",
        schema(emptyList(), "caption" to str("One line saying what it shows")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val f = engine(ctx).screenshot() ?: return "Screenshot failed"
        ctx.activity(input.optString("caption").ifBlank { "Screenshot" }, JSONObject().put("tool", "browser").put("image", f.absolutePath))
        return "Shown to the user. You can see it too:\n[[image:${f.absolutePath}]]"
    }
}

/** The agent looks at the page itself (layouts, images, captchas, charts) without bothering the user. */
class BrowserLookTool(c: Context) : BrowserTool(c) {
    override val spec = ToolSpec("browser_look", "Take a screenshot of the current page for YOURSELF to look at (when text isn't enough: images, layouts, maps, charts, a button you can't find).", schema())
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val f = engine(ctx).screenshot() ?: return "Screenshot failed"
        return "Here is the page:\n[[image:${f.absolutePath}]]"
    }
}

class BrowserHandoffTool(c: Context) : BrowserTool(c) {
    override val spec = ToolSpec("browser_handoff", "When a page needs a human (sign-in, captcha, 2FA code, consent), hand the browser to the user and wait until they say they are done.",
        schema(listOf("reason"), "reason" to str("What they need to do, one line")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        engine(ctx)
        // No notification of its own: a helper's handoff reaches the user only if the main agent forwards it (and that notifies).
        val a = ctx.ask("${input.optString("reason")} Open the browser, do it, then tap Done.", listOf("Open browser", "Done", "Skip"))
        return when (a) { null -> "No one is around to help right now."; "Skip" -> "The user skipped it."; AskOptions.NO -> AskOptions.TURNED_DOWN; else -> "The user says they are done. Read the page to check." }
    }
}

class BrowserUploadTool(c: Context) : BrowserTool(c) {
    override val spec = ToolSpec("browser_upload", "Upload a file from the phone into the page: give the file's path, and the upload button or file field (by [number], selector or text). Works with your workspace files (run_code output), documents the user attached in chat, and photos. Tip: if you can't read a file well yourself, upload it to a site that can (e.g. gemini.google.com, signed in) and ask there.",
        schema(listOf("path"), "path" to str("Full path, or a path inside your workspace"), "ref" to str("Element number from the outline"), "selector" to str("CSS selector"), "text" to str("Visible text of the upload button")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val raw = input.optString("path")
        val ws = java.io.File(context.filesDir, "workspace")
        val f = listOf(java.io.File(raw), java.io.File(ws, raw), java.io.File(ws, "attachments/$raw")).firstOrNull { it.isFile }
            ?: return "No such file: $raw. Files in your workspace: " + ws.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.take(30).joinToString { it.relativeTo(ws).path }
        val e = engine(ctx)
        val before = e.uploadsServed
        e.pendingUpload = listOf(android.net.Uri.fromFile(f))
        val ref = input.optString("ref").ifBlank { null }; val sel = input.optString("selector").ifBlank { null }; val txt = input.optString("text").ifBlank { null }
        e.click(ref, sel ?: if (ref == null && txt == null) "input[type=file]" else null, txt)
        repeat(20) { if (e.uploadsServed > before) return "Uploaded ${f.name} (${f.length() / 1024} KB). Read the page to confirm it was accepted.".also { ctx.activity("Uploaded ${f.name}", JSONObject().put("tool", "browser")) }; kotlinx.coroutines.delay(200) }
        e.pendingUpload = null
        return "That click didn't open a file picker. Find the real upload button or the input[type=file] in the outline and try again."
    }
}

fun browserTools(c: Context): List<Tool> = listOf(BrowserUploadTool(c), BrowserOpenTool(c), BrowserReadTool(c), BrowserClickTool(c), BrowserTypeTool(c), BrowserKeyTool(c), BrowserWaitTool(c), BrowserScrollTool(c), BrowserBackTool(c), BrowserScreenshotTool(c), BrowserLookTool(c), BrowserHandoffTool(c), BrowserEvalTool(c), BrowserNetworkTool(c), BrowserConsoleTool(c))


/** Run JavaScript in the page the agent is looking at: read its structure and storage, call the site's own endpoints, test a tool body. */
class BrowserEvalTool(c: Context) : BrowserTool(c) {
    override val spec = ToolSpec("browser_eval", "Run JavaScript in the current page and get back what you return. The body of an async function: it sees `args`, may use await and fetch(url, {credentials:'include'}) (the page's signed-in session), and returns a JSON-able value. Use it to read the page (document.querySelector, outerHTML slices, aria-labels), list localStorage keys or cookie NAMES, call the site's own JSON endpoints, and to test tool code before webapp_save. It runs as the signed-in user: reading is fine, but anything that sends, posts or deletes needs request_approval first. Never return tokens or cookie values.",
        schema(listOf("js"), "js" to str("Async function body, e.g. const r = await fetch('/api/me', {credentials:'include'}); return await r.json();"), "args" to str("Optional JSON object the body sees as `args`")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val args = runCatching { JSONObject(input.optString("args").ifBlank { "{}" }) }.getOrElse { return "args must be a JSON object." }
        val e = engine(ctx)
        val out = com.past9.phoneaos.connections.WebApps.result(e.runAsync(input.optString("js"), args))
        ctx.activity("Ran a script on ${e.page.value.url.substringAfter("//").substringBefore('/')}", JSONObject().put("tool", "browser"))
        return out
    }
}

/** Chrome's Network tab for the agent: what the site sends while it does something. */
class BrowserNetworkTool(c: Context) : BrowserTool(c) {
    override val spec = ToolSpec("browser_network", "See what the site sends. action 'start' begins recording (a page reload clears the page-side part), then you do the thing in the page (click, type, scroll, open a chat), then 'log' shows it: the page's own fetch / XHR calls with method, URL, status and a slice of the request and reply, WebSocket opens, and the browser's own requests with header names (login values hidden). filter narrows by a word in the URL. 'stop' ends it, 'clear' empties it. Look for JSON endpoints: they are the best thing to build tools on.",
        schema(listOf("action"), "action" to enumOf("What to do", "start", "log", "stop", "clear"), "filter" to str("Only calls whose method or URL contain this word"), "limit" to com.past9.phoneaos.agent.int("How many of the newest calls to show, default 40")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val e = engine(ctx)
        val P = com.past9.phoneaos.connections.PageJs
        return when (input.optString("action")) {
            "start" -> { e.netControl(true); "Recording. Now do the action in the page, then call browser_network with action log." }
            "stop" -> { e.netControl(false); "Stopped." }
            "clear" -> { e.nativeLog.clear(); e.js(P.netClear()); "Cleared." }
            else -> {
                val filter = input.optString("filter").trim().lowercase(); val limit = input.optInt("limit", 40).coerceIn(1, 100)
                val page = runCatching { org.json.JSONArray(e.js(P.netLog(filter, limit))) }.getOrDefault(org.json.JSONArray())
                val native = e.nativeLog.toList().filter { filter.isEmpty() || (it.method + " " + it.url).lowercase().contains(filter) }.takeLast(limit)
                if (page.length() == 0 && native.isEmpty()) return "Nothing recorded${if (filter.isNotEmpty()) " for \"$filter\"" else ""}. Did you start recording first (action start), and then do something in the page?"
                buildString {
                    appendLine("PAGE CALLS (fetch / XHR / WebSocket, with a slice of what was sent and received):")
                    for (i in 0 until page.length()) page.getJSONObject(i).let { o -> appendLine("${o.optString("m")} ${o.optString("u")} -> ${if (o.has("s")) o.optInt("s") else "?"}" + o.optString("rq").takeIf { it.isNotBlank() }?.let { "\n    sent: ${it.replace('\n', ' ')}" }.orEmpty() + o.optString("rs").takeIf { it.isNotBlank() }?.let { "\n    got: ${it.replace('\n', ' ')}" }.orEmpty()) }
                    if (page.length() == 0) appendLine("(none: the site may use a worker, or you reloaded after start; start again and repeat the action)")
                    appendLine("\nBROWSER REQUESTS (everything the browser sent, scripts and workers included, header names only):")
                    native.forEach { appendLine("${it.method} ${it.url}\n    ${it.headers}") }
                }.take(24_000)
            }
        }
    }
}

class BrowserConsoleTool(c: Context) : BrowserTool(c) {
    override val spec = ToolSpec("browser_console", "The page's console messages (errors and logs), newest last. Useful when something on a site doesn't work or you are testing a script.", schema(emptyList(), "clear" to bool("Empty the log after reading")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val e = engine(ctx); val lines = e.consoleLog.toList().takeLast(60)
        if (input.optBoolean("clear")) e.consoleLog.clear()
        return if (lines.isEmpty()) "The console is empty." else lines.joinToString("\n")
    }
}

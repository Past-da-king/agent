package com.past9.phoneaos.tools

import android.content.Context
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.bool
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.browser.BrowserEngine
import com.past9.phoneaos.browser.BrowserService
import org.json.JSONObject

/** The browser tools share one engine; they start the background service on first use. */
abstract class BrowserTool(protected val context: Context) : Tool {
    protected suspend fun engine(): BrowserEngine = BrowserService.await(context)
}

class BrowserOpenTool(c: Context) : BrowserTool(c) {
    override val spec = ToolSpec("browser_open", "Open a URL in your own background browser (a real phone browser that keeps working while the user is in other apps). Returns the page outline with numbered elements.",
        schema(listOf("url"), "url" to str("URL or domain")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val url = input.optString("url")
        val id = ctx.activity("Browsing $url", JSONObject().put("tool", "browser"))
        val e = engine(); val p = e.goto(url)
        ctx.updateActivity(id, "Opened ${p.title.ifBlank { p.url }}", JSONObject().put("tool", "browser").put("url", p.url))
        return e.snapshot()
    }
}

class BrowserReadTool(c: Context) : BrowserTool(c) {
    override val spec = ToolSpec("browser_read", "Re-read the current page: URL, numbered interactive elements and visible text.", schema())
    override suspend fun run(input: JSONObject, ctx: ToolContext) = engine().snapshot()
}

class BrowserClickTool(c: Context) : BrowserTool(c) {
    override val spec = ToolSpec("browser_click", "Click an element: by its [number] from the outline (preferred), a CSS selector, or its visible text.",
        schema(emptyList(), "ref" to str("Element number from the outline"), "selector" to str("CSS selector"), "text" to str("Visible text")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val e = engine()
        val r = e.click(input.optString("ref").ifBlank { null }, input.optString("selector").ifBlank { null }, input.optString("text").ifBlank { null })
        ctx.activity("Clicked ${input.optString("text").ifBlank { r.removePrefix("clicked ") }.take(50)}", JSONObject().put("tool", "browser"))
        return r + "\n\n" + e.snapshot(8_000)
    }
}

class BrowserTypeTool(c: Context) : BrowserTool(c) {
    override val spec = ToolSpec("browser_type", "Type text into a field (by [number], selector, or the focused field). Set submit to press Enter.",
        schema(listOf("text"), "text" to str("What to type"), "ref" to str("Element number"), "selector" to str("CSS selector"), "submit" to bool("Press Enter after")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val e = engine()
        val r = e.type(input.optString("ref").ifBlank { null }, input.optString("selector").ifBlank { null }, input.optString("text"), input.optBoolean("submit"))
        ctx.activity("Typed into the page", JSONObject().put("tool", "browser"))
        return r + if (input.optBoolean("submit")) "\n\n" + e.snapshot(8_000) else ""
    }
}

class BrowserScrollTool(c: Context) : BrowserTool(c) {
    override val spec = ToolSpec("browser_scroll", "Scroll the page down (or up) one screen and return the new outline.", schema(emptyList(), "up" to bool("Scroll up instead")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String { val e = engine(); e.scroll(!input.optBoolean("up")); return e.snapshot(8_000) }
}

class BrowserBackTool(c: Context) : BrowserTool(c) {
    override val spec = ToolSpec("browser_back", "Go back one page.", schema())
    override suspend fun run(input: JSONObject, ctx: ToolContext): String { val e = engine(); e.back(); return e.snapshot(8_000) }
}

class BrowserScreenshotTool(c: Context) : BrowserTool(c) {
    override val spec = ToolSpec("browser_show_user", "Show the user a screenshot of the page in the chat (for receipts, results, things they should see).",
        schema(emptyList(), "caption" to str("One line saying what it shows")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val f = engine().screenshot() ?: return "Screenshot failed"
        ctx.activity(input.optString("caption").ifBlank { "Screenshot" }, JSONObject().put("tool", "browser").put("image", f.absolutePath))
        return "Shown to the user."
    }
}

class BrowserHandoffTool(c: Context) : BrowserTool(c) {
    override val spec = ToolSpec("browser_handoff", "When a page needs a human (sign-in, captcha, 2FA code, consent), hand the browser to the user and wait until they say they are done.",
        schema(listOf("reason"), "reason" to str("What they need to do, one line")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        engine()
        ctx.notify("Your agent needs a hand", input.optString("reason"))
        val a = ctx.ask("${input.optString("reason")} Open the browser, do it, then tap Done.", listOf("Open browser", "Done", "Skip"))
        return when (a) { null -> "No one is around to help right now."; "Skip" -> "The user skipped it."; else -> "The user says they are done. Read the page to check." }
    }
}

fun browserTools(c: Context): List<Tool> = listOf(BrowserOpenTool(c), BrowserReadTool(c), BrowserClickTool(c), BrowserTypeTool(c), BrowserScrollTool(c), BrowserBackTool(c), BrowserScreenshotTool(c), BrowserHandoffTool(c))

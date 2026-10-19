package com.past9.phoneaos.connections

import android.content.Context
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.browser.BrowserService
import com.past9.phoneaos.tools.BrowserTool
import org.json.JSONArray
import org.json.JSONObject

/** What the learned-app tools need from a browser. Tests hand in a fake; the app uses [EnginePageRunner]. */
interface PageRunner {
    /** Get the browser onto the app's site, as a desktop or phone browser, in the right profile. Signing in is the user's / the agent's job. */
    suspend fun ensure(app: LearnedApp, ctx: ToolContext)
    /** Run an async function body that sees `args` in THIS agent's browser; returns JSON {"ok":true,"v":...} or {"ok":false,"e":"..."}. */
    suspend fun run(body: String, args: JSONObject, ctx: ToolContext): String
    /** "" when [body] parses (or can't be checked right now), else the error. */
    suspend fun syntaxError(body: String, ctx: ToolContext): String
}

/** Every agent works in its own browser (helpers in parallel must not share one), so the browser always comes from the caller. */
class EnginePageRunner(private val context: Context) : PageRunner {
    private suspend fun engine(app: LearnedApp?, ctx: ToolContext): com.past9.phoneaos.browser.BrowserEngine {
        val profiles = BrowserTool.profiles()
        val profile = profiles.firstOrNull { it.equals(app?.profile, true) } ?: profiles.firstOrNull { it.equals(BrowserTool.lastProfile[ctx.agentLabel], true) } ?: profiles.first()
        return BrowserService.await(context, ctx.agentLabel, profile)
    }

    override suspend fun ensure(app: LearnedApp, ctx: ToolContext) {
        val e = engine(app, ctx)
        val now = runCatching { java.net.URI(e.page.value.url).host.orEmpty().lowercase().removePrefix("www.") }.getOrDefault("")
        val onSite = app.host.isNotBlank() && (now == app.host.lowercase() || now.endsWith("." + app.host.lowercase()))
        // How to present is learned per site (the catalog), unless the app was saved with desktop on. A refusal is retried the other way by itself.
        val catalog = com.past9.phoneaos.browser.SiteCatalog(com.past9.phoneaos.App.graph(context).settings)
        val want = app.desktop || catalog.get(app.startUrl)?.takeIf { it.outcome == "works" }?.desktop == true
        if (e.desktop != want || !onSite) com.past9.phoneaos.browser.SiteAdapt.open(e.port, catalog, app.startUrl, if (app.desktop) true else null)
    }

    override suspend fun run(body: String, args: JSONObject, ctx: ToolContext): String = engine(null, ctx).runAsync(body, args)
    /** Checks in the browser the agent already has open; if it has none, the check is skipped (the first run finds a mistake just as well). */
    override suspend fun syntaxError(body: String, ctx: ToolContext): String = BrowserService.existing(ctx.agentLabel)?.syntaxError(body) ?: ""
}

object WebApps {
    const val GUIDE = """TEACHING YOURSELF A WEB APP (WhatsApp Web, Reddit, a site with no API, anything you would otherwise click through every time)

The idea: map the app once, then write tools so next time you call a tool instead of clicking. Tools run as JavaScript inside the page of the signed-in browser, so the user's login is used and no password is stored.

1. OPEN IT. browser_open the site. browser_open works out by itself whether the site wants a phone or a desktop browser (it retries the other way when refused, and remembers); site_probe checks a new site first and site_catalog shows what is known. Only pass desktop=true or false to force it. If it needs a sign-in, QR code or code, use browser_handoff and let the user do it; the session stays signed in for next time. The user is on the SAME phone as the browser, so they cannot scan a QR code with it: choose the code or phone-number sign-in when the site offers one (WhatsApp Web: "Log in with phone number", then they type the code it shows into the WhatsApp app under Linked devices).
2. WATCH WHAT IT SENDS. browser_network start, then do the real action in the UI (open a chat, search, post, load the next page), then browser_network log (filter by a word in the URL). You see each call's method, URL, status, a slice of the request and the reply, plus the browser's own requests with header names. Look for JSON endpoints: those are the best thing to build on. browser_console shows the page's own errors.
3. LOOK INSIDE. browser_eval runs JavaScript in the page and returns what you return: read the DOM (document.querySelector...), find stable selectors and aria-labels, list localStorage / sessionStorage keys (never print tokens back to the user), read cookie names, call the site's own endpoints with fetch(url, {credentials:'include'}). Prefer in this order: (a) the site's internal JSON endpoints with fetch, (b) data the page already holds, (c) reading and driving the DOM (find the element, dispatch real events: for typing use the native setter then an 'input' event; for clicks element.click()). Avoid coordinates and visible-text guesses that break when the page changes.
4. WRITE SMALL TOOLS. One tool per thing: list_chats, read_chat, send_message, search_posts, get_post... Each tool is the BODY of an async function that sees `args` and returns something JSON-able (keep it short: trim lists to what matters, at most a few hundred lines). Throw an Error with a plain message when it can't work (not signed in, element missing) so you can tell the user what to do. Mark read_only=true only if it changes nothing; sending, posting, deleting, buying are read_only=false and the user is asked before they run (unless the user trusts the app).
5. TEST BEFORE SAVING. Run the body with browser_eval first (set `const args = {...}` at the top for the test), then save with webapp_save, then run each tool once with webapp_test. If a tool fails, fix it and save again (same name replaces).
6. IT SAVES FOR NEXT TIME. webapp_save asks the user to approve the tool set. After that the tools exist as web_<app>_<tool> for you and your helpers. If the site changes and a tool breaks, remap that tool and save again.

BACKSLASHES: your tool call is JSON, and backslashes inside code are the number one reason a save fails. Write code that needs none: [0-9] not \d, [A-Za-z0-9_] not \w, [.] not \., [/] not \/, single quotes inside selectors ([data-x='y']) and inside strings, and new RegExp(...) built from those. If you really need a backslash character, use String.fromCharCode(92). A regex literal containing \/ is the worst offender: the slash loses its backslash on the way in and the rest of the line becomes a comment.

Rules: never put a password, token or cookie value in a tool or in your reply. Reading is fine; anything that sends, posts or deletes on the user's behalf goes through the normal approval. If a site's terms forbid automation, or it needs a CAPTCHA every time, say so instead of working around it."""

    /** What the model is shown for an example of the contract. */
    const val EXAMPLE = "e.g. Reddit: const r = await fetch('/r/' + args.sub + '/new.json?limit=' + (args.limit || 10), {credentials:'include'}); const j = await r.json(); return j.data.children.map(c => ({title: c.data.title, score: c.data.score, url: c.data.permalink}));"

    fun toolName(app: LearnedApp, tool: LearnedTool) = "web_${app.id}_${tool.name}"

    /** The JSON the model sees for a tool's inputs. */
    fun schemaOf(t: LearnedTool): JSONObject = JSONObject().put("type", "object").put("properties", t.params).put("required", JSONArray(t.required.filter { t.params.has(it) }))

    /** Result text for the model: the value as JSON, trimmed; or the error. */
    fun result(json: String, max: Int = 20_000): String {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return "Error: unreadable result ${json.take(200)}"
        if (!o.optBoolean("ok")) return "Error: ${o.optString("e").ifBlank { "failed" }}"
        val v = o.opt("v")?.takeIf { it != JSONObject.NULL }?.let { if (it is String) it else it.toString() } ?: "null"
        return if (v.length > max) v.take(max) + "\n[cut: ${v.length - max} more characters. Make the tool return less.]" else v
    }

    private val toolNameRx = Regex("^[a-z][a-z0-9_]{1,29}$")
    private val paramTypes = setOf("string", "number", "integer", "boolean")

    /** Why a tool definition can't be saved, or null. */
    fun problem(t: LearnedTool): String? = when {
        !toolNameRx.matches(t.name) -> "Tool name \"${t.name}\" must be lower-case letters, digits and underscores, 2 to 30 characters, starting with a letter."
        t.description.isBlank() -> "${t.name}: add a description saying what it does and when to use it."
        t.js.isBlank() -> "${t.name}: the js body is empty (0 characters arrived). If you did write it, the call may have been cut off: send fewer tools per call."
        t.js.length > LearnedStore.MAX_JS -> "${t.name}: the js is too long (${t.js.length} characters, limit ${LearnedStore.MAX_JS}). Split it into smaller tools."
        t.params.keys().asSequence().any { k -> !Regex("^[a-zA-Z][a-zA-Z0-9_]{0,29}$").matches(k) || t.params.optJSONObject(k)?.optString("type") !in paramTypes } ->
            "${t.name}: each input needs a plain name and a type of string, number, integer or boolean."
        t.required.any { !t.params.has(it) } -> "${t.name}: required lists an input that isn't defined."
        else -> null
    }

    fun toolFromJson(o: JSONObject): LearnedTool {
        val p = o.optJSONObject("params") ?: JSONObject()
        val params = JSONObject().also { out -> p.keys().forEach { k -> out.put(k, p.optJSONObject(k) ?: JSONObject().put("type", "string")) } }
        val req = o.optJSONArray("required")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        return LearnedTool(o.optString("name").trim(), o.optString("description").trim(), params, req, o.optString("js"), o.optBoolean("read_only", o.optBoolean("readOnly", true)))
    }
}

class WebAppGuideTool : Tool {
    override val spec = ToolSpec("webapp_guide", "How to teach yourself a web app (WhatsApp Web, Reddit, any site you'd otherwise click through every time): map it once, write your own tools, and use them from then on. Read this before you start.", schema())
    override suspend fun run(input: JSONObject, ctx: ToolContext): String = WebApps.GUIDE + "\n\n" + WebApps.EXAMPLE
}

class WebAppListTool(private val store: LearnedStore) : Tool {
    override val spec = ToolSpec("webapp_list", "The web apps you have taught yourself, and the tools each has.", schema())
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val apps = store.apps.value
        if (apps.isEmpty()) return "None yet. Read webapp_guide to teach yourself one."
        return apps.joinToString("\n\n") { a -> "${a.name} (${a.startUrl}${if (a.desktop) ", desktop" else ""}${if (a.trusted) ", user trusts its changes" else ""})\n" + a.about.takeIf { it.isNotBlank() }?.let { "$it\n" }.orEmpty() +
            a.tools.joinToString("\n") { t -> "- ${WebApps.toolName(a, t)} [${if (t.readOnly) "reads" else "CHANGES things"}]: ${t.description}" } }
    }
}

/** One learned tool, as a tool the agent can call. Anything that changes things asks first unless the user trusts the app. */
class LearnedCallTool(private val appId: String, private val tool: LearnedTool, private val store: LearnedStore, private val runner: PageRunner) : Tool {
    private val app0 get() = store.apps.value.firstOrNull { it.id == appId }
    override val spec = ToolSpec("web_${appId}_${tool.name}", "[${app0?.name ?: appId}] ${tool.description}" + if (tool.readOnly) "" else " CHANGES things on the user's account: they are asked first.", WebApps.schemaOf(tool))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val app = app0 ?: return "That app was removed."
        val t = app.tools.firstOrNull { it.name == tool.name } ?: return "That tool was removed."
        return WebAppRun.call(app, t, input, ctx, runner)
    }
}

object WebAppRun {
    suspend fun call(app: LearnedApp, t: LearnedTool, input: JSONObject, ctx: ToolContext, runner: PageRunner): String {
        val missing = t.required.filter { !input.has(it) || input.isNull(it) }
        if (missing.isNotEmpty()) return "Missing input: ${missing.joinToString()}."
        if (!t.readOnly && !app.trusted) {
            val what = if (input.length() == 0) "No inputs." else input.keys().asSequence().joinToString("\n") { "${it}: ${input.opt(it).toString().take(600)}" }
            when (ctx.ask("APPROVAL|${app.name}: ${t.description.take(90)}|$what", listOf("Approve", "Decline"))) {
                "Approve" -> {}
                null -> return "No answer (background run). Do NOT do it; leave it for the user."
                else -> return "DECLINED. Do not do it."
            }
        }
        val id = ctx.activity("${app.name}: ${t.name.replace('_', ' ')}", JSONObject().put("tool", "browser"))
        val out = try {
            runner.ensure(app, ctx)
            WebApps.result(runner.run(t.js, input, ctx))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
            android.util.Log.w("AgentWebApp", "${app.name}.${t.name} failed", e)
            "Error: couldn't run ${t.name} (${e.message ?: e::class.java.simpleName}). If it says too many browsers, finish a helper or close one and try again."
        }
        ctx.updateActivity(id, "${app.name}: ${t.name.replace('_', ' ')}${if (out.startsWith("Error:")) " (failed)" else ""}", JSONObject().put("tool", "browser"))
        return out
    }
}

class WebAppSaveTool(private val store: LearnedStore, private val runner: PageRunner) : Tool {
    private val toolSchema = JSONObject().put("type", "object").put("properties", JSONObject()
        .put("name", str("Tool name: lower-case letters, digits, underscores, 2 to 30 characters, e.g. send_message"))
        .put("description", str("What it does and when to use it, one or two lines"))
        .put("params", JSONObject().put("type", "object").put("description", "Inputs: {inputName: {type: string|number|integer|boolean, description: ...}}"))
        .put("required", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
        .put("js", str("The BODY of an async function. It sees `args` and returns something JSON-able. No function wrapper."))
        .put("read_only", JSONObject().put("type", "boolean").put("description", "true only if it changes nothing. Sending, posting, deleting, buying: false."))).put("required", JSONArray(listOf("name", "description", "js", "read_only")))
    override val spec = ToolSpec("webapp_save", "Save the tools you wrote for a web app, so next time you call them instead of clicking through the app. Read webapp_guide first, and test each body with browser_eval. The user is shown the tool list and approves it. Saving an app that exists replaces its tools.",
        schema(listOf("name", "start_url", "tools"), "name" to str("The app's name, e.g. WhatsApp Web"), "start_url" to str("Where it lives: https://web.whatsapp.com"),
            "about" to str("One or two lines: what it is and anything to know (how sign-in works, quirks). Never write a password, key or cookie here or in a tool: say that the user signs in, not with what"), "desktop" to JSONObject().put("type", "boolean").put("description", "Load it as a desktop browser (WhatsApp Web needs this)"),
            "profile" to str("Browser profile that is signed in to it; default is the one you used"),
            "logo" to str("Optional https URL of the app's icon, shown in the app's list; default is the site's favicon"), "tools" to JSONObject().put("type", "array").put("items", toolSchema)))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val name = input.optString("name").trim()
        val url = input.optString("start_url").trim().let { if (it.startsWith("http")) it else "https://$it" }
        if (name.length !in 2..40) return "Not saved: give the app a name of 2 to 40 characters."
        val id = LearnedApp.slug(name).ifBlank { return "Not saved: that name has nothing usable in it." }
        if (runCatching { java.net.URI(url).host.isNullOrBlank() }.getOrDefault(true) || !url.startsWith("https://")) return "Not saved: start_url must be an https address."
        val arr = input.optJSONArray("tools") ?: return "Not saved: tools is empty."
        val tools = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { WebApps.toolFromJson(it) }
        if (tools.isEmpty()) return "Not saved: tools is empty."
        if (tools.size > LearnedStore.MAX_TOOLS) return "Not saved: at most ${LearnedStore.MAX_TOOLS} tools per app."
        tools.groupBy { it.name }.filter { it.value.size > 1 }.keys.firstOrNull()?.let { return "Not saved: the tool name $it is used twice." }
        tools.firstNotNullOfOrNull { WebApps.problem(it) }?.let { return "Not saved: $it" }
        if (store.find(id) == null && store.apps.value.size >= LearnedStore.MAX_APPS) return "Not saved: too many apps (${LearnedStore.MAX_APPS}). Remove one first."
        // A syntax check that can't run (no browser open right now) never blocks the save.
        for (t in tools) runCatching { runner.syntaxError(t.js, ctx) }.getOrDefault("").takeIf { it.isNotBlank() }?.let {
            return "Not saved: the js of ${t.name} doesn't parse: $it. It arrived as ${t.js.length} characters, starting \"${t.js.take(50).replace('\n', ' ')}\" and ending \"${t.js.takeLast(50).replace('\n', ' ')}\". " +
                "If it ends mid-statement the reply was cut off: send fewer or shorter tools per call. If it has a regex or a selector with backslashes or escaped quotes, rewrite it without them ([0-9] for a digit, [/] for a slash, single quotes inside selectors): backslashes do not survive the trip."
        }
        val app = LearnedApp(id, name, url, input.optString("about").trim(), input.optString("profile").trim(), input.optBoolean("desktop", false), tools,
            logo = input.optString("logo").trim().takeIf { it.startsWith("https://") } ?: "")
        val replacing = store.find(id) != null
        val details = buildString {
            appendLine("Site: $url${if (app.desktop) " (as a desktop browser)" else ""}")
            appendLine("These tools run inside your signed-in session there. Nothing is stored but the tools themselves.")
            tools.forEach { appendLine("${if (it.readOnly) "Reads" else "CHANGES things"}: ${it.name} - ${it.description}") }
            if (tools.any { !it.readOnly }) append("Tools that change things ask you first each time, unless you trust the app in Connections.")
        }
        when (ctx.ask("APPROVAL|${if (replacing) "Update" else "Save"} the $name tools (${tools.size})|$details", listOf("Approve", "Decline"))) {
            "Approve" -> {}
            null -> return "No answer (background run). Not saved."
            else -> return "DECLINED. Not saved. Ask what the user wants changed."
        }
        store.save(app)
        return "Saved $name with ${tools.size} tool${if (tools.size == 1) "" else "s"}: " + tools.joinToString { WebApps.toolName(app, it) } + ". Try each with webapp_test now; from here on they are tools you and your helpers can call."
    }
}

class WebAppTestTool(private val store: LearnedStore, private val runner: PageRunner) : Tool {
    override val spec = ToolSpec("webapp_test", "Run one tool of a saved web app now, to check it works. Same rules as using it: tools that change things ask the user first.",
        schema(listOf("app", "tool"), "app" to str("The app's name"), "tool" to str("The tool's name, e.g. read_chat"), "args" to str("Inputs as a JSON object, e.g. {\"chat\":\"Mom\"}")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val app = store.find(input.optString("app")) ?: return "No saved app called ${input.optString("app")}. webapp_list shows them."
        val t = app.tools.firstOrNull { it.name == input.optString("tool").trim() } ?: return "${app.name} has no tool ${input.optString("tool")}: ${app.tools.joinToString { it.name }}."
        val args = runCatching { JSONObject(input.optString("args").ifBlank { "{}" }) }.getOrElse { return "args must be a JSON object." }
        return WebAppRun.call(app, t, args, ctx, runner)
    }
}

class WebAppRemoveTool(private val store: LearnedStore) : Tool {
    override val spec = ToolSpec("webapp_remove", "Remove a saved web app and its tools (the user is asked first).", schema(listOf("app"), "app" to str("The app's name")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val app = store.find(input.optString("app")) ?: return "No saved app called ${input.optString("app")}."
        when (ctx.ask("APPROVAL|Remove the ${app.name} tools|${app.tools.size} tools will be deleted. You can teach them again later.", listOf("Approve", "Decline"))) {
            "Approve" -> {}
            null -> return "No answer (background run). Not removed."
            else -> return "DECLINED. Keep it."
        }
        store.remove(app.id); return "Removed ${app.name}."
    }
}

/** Every tool the learned apps offer, read from the store when asked, so a new app shows up without a restart. */
fun learnedTools(store: LearnedStore, runner: PageRunner): List<Tool> =
    listOf(WebAppGuideTool(), WebAppListTool(store), WebAppSaveTool(store, runner), WebAppTestTool(store, runner), WebAppRemoveTool(store)) +
        store.apps.value.flatMap { a -> a.tools.map { LearnedCallTool(a.id, it, store, runner) } }

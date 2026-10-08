package com.past9.phoneaos.tools

import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.sharedHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Composio Connect, for CONSUMER keys (ck_...). Those don't open Composio's REST API at all: they
 * open an MCP server at connect.composio.dev/mcp, which hands the agent Composio's own tools
 * (find a tool, connect an app, run actions). We speak MCP's streamable HTTP directly and offer
 * each of its tools to the agent as one of ours.
 */
object ComposioConnect {
    private const val URL = "https://connect.composio.dev/mcp"
    private val ids = AtomicInteger(1)
    private val lock = Mutex()
    @Volatile private var session: String? = null
    @Volatile private var sessionKey: String? = null
    @Volatile var tools: List<JSONObject> = emptyList(); private set

    fun isConsumerKey(key: String?) = key?.trim()?.startsWith("ck_") == true

    @Volatile private var protocol = "2025-06-18"

    /** Composio Connect sits behind Cloudflare: a 502/503/504 is their server hiccuping, so retry a few times. */
    private suspend fun send(key: String, body: JSONObject): JSONObject? {
        var last: Exception? = null
        for (attempt in 0 until 3) {
            try { return sendOnce(key, body) } catch (e: ServerBusy) { last = e; kotlinx.coroutines.delay(2000L * (attempt + 1)) }
        }
        throw IllegalStateException("Composio Connect isn't answering right now (${last?.message}). Their server is having trouble; try again in a few minutes.")
    }
    private class ServerBusy(code: Int) : Exception("error $code")

    private suspend fun sendOnce(key: String, body: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(URL).post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("x-consumer-api-key", key).header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", protocol)
            .apply { session?.let { header("Mcp-Session-Id", it) } }.build()
        sharedHttp.newCall(req).execute().use { res ->
            res.header("Mcp-Session-Id")?.let { session = it }
            val text = res.body?.string().orEmpty()
            if (res.code in 500..599) throw ServerBusy(res.code)
            if (!res.isSuccessful) throw IllegalStateException("Composio Connect ${res.code}: " +
                (res.header("X-Mcp-Auth-Failure-Reason") ?: runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull() ?: text.take(200)))
            if (!body.has("id")) return@use null
            // The reply is plain JSON, or a server-sent event stream carrying it.
            val json = if (text.trimStart().startsWith("{")) text else text.lineSequence().filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }.lastOrNull { runCatching { JSONObject(it).optInt("id", -1) == body.getInt("id") }.getOrDefault(false) } ?: "{}"
            val o = JSONObject(json)
            o.optJSONObject("error")?.let { throw IllegalStateException(it.optString("message", "MCP error")) }
            o.optJSONObject("result") ?: JSONObject()
        }
    }

    private fun rpc(method: String, params: JSONObject = JSONObject()) = JSONObject().put("jsonrpc", "2.0").put("id", ids.getAndIncrement()).put("method", method).put("params", params)

    private suspend fun ensureSession(key: String) {
        // Their server is stateless (no Mcp-Session-Id), so "initialised for this key" is what we track.
        if (sessionKey == key) return
        session = null
        fun init(v: String) = rpc("initialize", JSONObject().put("protocolVersion", v).put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "Agent").put("version", "1")))
        // Newest protocol first; if their server falls over on it, try the previous one.
        try { protocol = "2025-06-18"; send(key, init(protocol)) }
        catch (e: IllegalStateException) { protocol = "2025-03-26"; session = null; send(key, init(protocol)) }
        sessionKey = key
        send(key, JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"))
        sessionKey = key
    }

    /** Connect and fetch the tool list. Null when it worked, otherwise why not. */
    suspend fun load(key: String): String? = lock.withLock {
        runCatching {
            ensureSession(key)
            val all = mutableListOf<JSONObject>(); var cursor: String? = null
            do {
                val r = send(key, rpc("tools/list", JSONObject().apply { cursor?.let { put("cursor", it) } }))!!
                r.optJSONArray("tools")?.let { a -> for (i in 0 until a.length()) all += a.getJSONObject(i) }
                cursor = r.optString("nextCursor").takeIf { it.isNotBlank() && it != "null" }
            } while (cursor != null && all.size < 200)
            tools = all
            if (all.isEmpty()) "Composio Connect accepted the key but offered no tools." else null
        }.getOrElse { session = null; sessionKey = null; it.message ?: "Couldn't reach Composio Connect" }
    }

    suspend fun call(key: String, name: String, args: JSONObject): String = lock.withLock {
        suspend fun once(): String {
            ensureSession(key)
            val r = send(key, rpc("tools/call", JSONObject().put("name", name).put("arguments", args)))!!
            val content = r.optJSONArray("content") ?: JSONArray()
            val text = (0 until content.length()).mapNotNull { content.optJSONObject(it)?.optString("text")?.takeIf { t -> t.isNotBlank() } }.joinToString("\n")
            return (if (r.optBoolean("isError")) "Error: " else "") + text.ifBlank { r.toString().take(4000) }
        }
        // Sessions expire: start a fresh one once before giving up.
        runCatching { once() }.getOrElse { session = null; sessionKey = null; runCatching { once() }.getOrElse { "Error: ${it.message}" } }
    }

    /** A Composio Connect tool's JSON reply ({data, error, successful}), as its data object. */
    private suspend fun data(key: String, name: String, args: JSONObject): JSONObject {
        val text = call(key, name, args)
        val o = runCatching { JSONObject(text) }.getOrNull() ?: throw IllegalStateException(text.removePrefix("Error: ").take(300))
        if (o.has("successful") && !o.optBoolean("successful")) throw IllegalStateException(o.optString("error").ifBlank { "Composio Connect failed" })
        return o.optJSONObject("data") ?: JSONObject()
    }

    /** One signed-in account of an app (a person can have several Gmails). */
    data class Account(val id: String, val slug: String, val status: String, val email: String, val alias: String, val isDefault: Boolean) {
        val label get() = alias.ifBlank { email }
    }

    /** Every account per app, active ones first. Apps never connected map to an empty list. */
    suspend fun accounts(key: String, slugs: List<String>): Map<String, List<Account>> {
        if (slugs.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, List<Account>>()
        // Composio caps how many toolkits one call may carry; ask in chunks.
        slugs.distinct().chunked(50).forEach { chunk ->
            val res = data(key, "COMPOSIO_MANAGE_CONNECTIONS", JSONObject().put("toolkits", JSONArray().apply { chunk.forEach { put(JSONObject().put("name", it).put("action", "list")) } }))
                .optJSONObject("results") ?: JSONObject()
            chunk.forEach { slug ->
                val arr = res.optJSONObject(slug)?.optJSONArray("accounts") ?: JSONArray()
                out[slug] = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { a ->
                    Account(a.optString("id"), slug, if (a.optString("status").equals("active", true)) "ACTIVE" else "INITIATED",
                        identity(a), a.optString("alias").takeIf { it != "null" } ?: "", a.optBoolean("is_default"))
                }.sortedBy { if (it.status == "ACTIVE") 0 else 1 }
            }
        }
        return out
    }

    /**
     * Who an account signed in as. Mail apps share an email; others (GitHub, Instagram, Slack) a username, handle
     * or name, under whatever key that app uses, so look for the usual ones before giving up.
     */
    fun identity(a: JSONObject): String {
        val keys = listOf("email", "username", "login", "user_name", "screen_name", "handle", "display_name", "name", "team_name", "workspace")
        fun pick(o: JSONObject?): String? = o?.let { obj -> keys.firstNotNullOfOrNull { k -> obj.optString(k).takeIf { v -> v.isNotBlank() && v != "null" } } }
        return pick(a.optJSONObject("user_info")) ?: pick(a.optJSONObject("user_info")?.optJSONObject("user"))
            ?: pick(a.optJSONObject("data")) ?: pick(a.optJSONObject("profile")) ?: ""
    }

    /** Two accounts of one app are the same sign-in when they name the same person, or neither names anyone. */
    fun sameAccount(a: Account, b: Account) = a.slug == b.slug && a.email.trim().equals(b.email.trim(), true)

    /** Connection status per app: ACTIVE, INITIATED (sign-in started, not finished) or "" (never connected), with an account id. */
    suspend fun statuses(key: String, slugs: List<String>): Map<String, Pair<String, String>> =
        accounts(key, slugs).mapValues { (_, l) -> l.firstOrNull()?.let { it.status to it.id } ?: ("" to "") }

    /** Start connecting an app; returns the sign-in link (valid about 10 minutes). */
    suspend fun connectLink(key: String, slug: String): String {
        val r = data(key, "COMPOSIO_MANAGE_CONNECTIONS", JSONObject().put("toolkits", JSONArray().put(JSONObject().put("name", slug).put("action", "add"))))
        return r.optJSONObject("results")?.optJSONObject(slug)?.optString("redirect_url")?.takeIf { it.startsWith("http") }
            ?: throw IllegalStateException("Composio didn't return a sign-in link for ${AppCatalog.name(slug)}.")
    }

    suspend fun disconnect(key: String, slug: String, accountId: String) {
        data(key, "COMPOSIO_MANAGE_CONNECTIONS", JSONObject().put("toolkits", JSONArray().put(JSONObject().put("name", slug).put("action", "remove").put("account_id", accountId))))
    }

    /** Any of Composio's 500+ apps by name: what it is and whether it's connected. */
    suspend fun searchApps(key: String, query: String): List<Pair<Toolkit, Boolean>> {
        val d = data(key, "COMPOSIO_SEARCH_TOOLS", JSONObject().put("queries", JSONArray().put(JSONObject().put("use_case", "use the ${query.trim()} app"))))
        val a = d.optJSONArray("toolkit_connection_statuses") ?: JSONArray()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { t ->
            val slug = t.optString("toolkit")
            (AppCatalog.popular.firstOrNull { it.slug == slug } ?: Toolkit(slug, AppCatalog.name(slug), t.optString("description"), AppCatalog.logo(slug), 0, false, true)) to t.optBoolean("has_active_connection")
        }.filter { it.first.slug.isNotBlank() }
    }

    /**
     * The agent wants an app connected: instead of pasting a link into the chat, show a Connect card
     * (logo, name, why). On Connect the sign-in opens and we wait until it's ACTIVE, then the agent carries on.
     */
    private suspend fun connectViaCard(k: String, input: JSONObject, ctx: ToolContext, openLink: suspend (String) -> Unit,
                                       onConnected: suspend (ToolContext, com.past9.phoneaos.data.AccountRef) -> Unit): String? {
        val items = input.optJSONArray("toolkits") ?: return null
        val adds = (0 until items.length()).mapNotNull { items.optJSONObject(it) }.filter { it.optString("action", "add").ifBlank { "add" } == "add" }.map { it.optString("name").lowercase().trim() }.filter { it.isNotBlank() }
        if (adds.isEmpty()) return null
        val reason = input.optString("reason")
        val out = StringBuilder()
        for (slug in adds) {
            val req = ConnectRequest.of(slug, reason)
            // Already connected: the agent wants ANOTHER account (a second Gmail), so say so on the card.
            val existing = accounts(k, listOf(slug))[slug].orEmpty().filter { it.status == "ACTIVE" }
            // An app that doesn't say who an account is can only tell them apart by guesswork: one is enough.
            if (existing.isNotEmpty() && existing.all { it.email.isBlank() }) { out.appendLine("${req.name} is already connected. Use it."); continue }
            val beforeIds = existing.map { it.id }.toSet()
            val before = beforeIds.size
            val a = ctx.ask(if (before > 0) req.copy(reason = "add another ${req.name} account" + if (req.reason.isNotBlank()) ", so I can ${req.reason.removePrefix("so I can ")}" else "").text else req.text, ConnectRequest.OPTIONS)
            if (a == null) { out.appendLine("Nobody is around to connect ${req.name}. Tell the user they can connect it from Connections in the app."); continue }
            if (a != "Connect") { out.appendLine("The user declined connecting ${req.name}${if (a != "Decline") " and said: $a" else ""}. Don't send a link; carry on without it."); continue }
            val link = runCatching { connectLink(k, slug) }.getOrElse { out.appendLine("Couldn't start connecting ${req.name}: ${it.message}"); continue }
            openLink(link)
            val id = ctx.activity("Waiting for you to sign in to ${req.name}", JSONObject().put("tool", "apps").put("link", link).put("app", slug))
            val deadline = System.currentTimeMillis() + 5 * 60_000
            var done = false
            while (System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(4000)
                val now = runCatching { accounts(k, listOf(slug))[slug].orEmpty().filter { it.status == "ACTIVE" } }.getOrNull().orEmpty()
                if (now.size > before) {
                    done = true
                    val fresh = now.filter { it.id !in beforeIds }
                    // The same account signed in again: keep the one there was, quietly drop the copy.
                    val dupes = fresh.filter { f -> now.any { o -> o.id in beforeIds && sameAccount(o, f) } }
                    dupes.forEach { d -> runCatching { disconnect(k, slug, d.id) } }
                    if (dupes.isNotEmpty()) { out.appendLine("That ${req.name} account (${dupes.first().label.ifBlank { req.name }}) was already connected, so nothing changed. Carry on with it."); break }
                    // The new account: offer to put it in a profile (and a helper's own profile gets it straight away).
                    fresh.forEach { a -> runCatching { onConnected(ctx, com.past9.phoneaos.data.AccountRef(a.id, slug, a.label.ifBlank { AppCatalog.name(slug) })) } }
                    break
                }
            }
            ctx.updateActivity(id, if (done) "${req.name} connected" else "${req.name} sign-in not finished")
            if (done && out.contains("already connected")) continue
            out.appendLine(if (done) "${req.name} ($slug) is connected now. Carry on with the task." else "The user hasn't finished signing in to ${req.name} yet. Don't paste a link; say you'll continue once it's connected (they can also connect it from Connections).")
        }
        return out.toString().trim()
    }

    /** Each Composio Connect tool as one of the agent's tools. Actions that change things need the user's yes. */
    fun asTools(key: () -> String?, openLink: (suspend (String) -> Unit)? = null, guard: AppGuard = AppGuard.DEFAULT,
                onConnected: suspend (ToolContext, com.past9.phoneaos.data.AccountRef) -> Unit = { _, _ -> }): List<Tool> = tools.map { t ->
        val name = t.optString("name")
        val manage = name == "COMPOSIO_MANAGE_CONNECTIONS" && openLink != null
        val schema = t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object")
        // Connecting shows the user a Connect card; the reason is the line on it.
        if (manage) schema.optJSONObject("properties")?.put("reason", JSONObject().put("type", "string")
            .put("description", "For action add: what you need the app for, finishing 'so I can...'. Shown to the user on the Connect card."))
        object : Tool {
            override val spec = ToolSpec(name, t.optString("description").take(1500) +
                (if (manage) "\nIn this app, action add shows the user a Connect card and opens the sign-in for them, then waits until it is connected. Never paste the sign-in link yourself." else ""), schema)
            override suspend fun run(input: JSONObject, ctx: ToolContext): String {
                val k = key() ?: return "No Composio key."
                if (manage) connectViaCard(k, input, ctx, openLink!!, onConnected)?.let { return it }
                input.remove("reason")
                // Executing app actions: the account's rules (and a helper's profile) decide, in code.
                if (name.contains("EXECUTE", true)) {
                    val slugs = Regex("\"(?:tool_slug|slug|action)\"\\s*:\\s*\"([A-Z0-9_]+)\"").findAll(input.toString()).map { it.groupValues[1] }.toList()
                    val v = guard.check(ctx, slugs, input.optString("account").takeIf { it.isNotBlank() }, input.toString(2).take(1500))
                    v.refuse?.let { return it }
                    v.account?.let { input.put("account", it) }
                }
                ctx.activity("Composio: ${name.removePrefix("COMPOSIO_").lowercase().replace('_', ' ')}", JSONObject().put("tool", "apps"))
                return call(k, name, input)
            }
        }
    }
}

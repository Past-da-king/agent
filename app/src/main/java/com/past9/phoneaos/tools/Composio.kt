package com.past9.phoneaos.tools

import com.past9.phoneaos.agent.ProviderException
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.int
import com.past9.phoneaos.agent.postJson
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.sharedHttp
import com.past9.phoneaos.agent.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class Toolkit(val slug: String, val name: String, val description: String, val logo: String, val toolsCount: Int, val noAuth: Boolean, val managedAuth: Boolean)
data class Connection(val id: String, val toolkit: String, val status: String, val label: String)

/**
 * Composio over plain REST, with the USER'S OWN API key (we never hold a key of ours).
 * One Composio "user" per install; every connection the user makes hangs off it.
 */
class ComposioClient(
    private val apiKey: () -> String?,
    private val userId: String,
    private val base: String = "https://backend.composio.dev/api/v3.1",
    private val http: OkHttpClient = sharedHttp,
) {
    private fun key() = apiKey()?.trim()?.takeIf { it.isNotBlank() } ?: throw ProviderException("No Composio key yet. Add one in Connections.")

    /**
     * Composio has three kinds of key, each in its own header: a PROJECT key in x-api-key, a personal
     * USER key in x-user-api-key and an ORGANIZATION key in x-org-api-key. User and org keys may also
     * need the project named (x-project-id). Sending a key in the wrong header is a 401, so the first
     * call works out which one this key is and every call after reuses it.
     */
    @Volatile private var auth: Pair<String, Map<String, String>>? = null
    @Volatile private var authFor: String? = null
    /** Until a call has proven otherwise, guess the header from how the key looks. */
    private fun guess(k: String) = when { k.startsWith("uak_") -> "x-user-api-key"; k.startsWith("oak_") -> "x-org-api-key"; else -> "x-api-key" }
    private fun headers(): Map<String, String> = key().let { k -> (auth?.takeIf { authFor == k } ?: (guess(k) to emptyMap())).let { (h, extra) -> mapOf(h to k) + extra } }

    /** A 401/403 with an unproven key: work out its real header once, then retry. */
    private suspend fun <T> withAuth(block: suspend () -> T): T = try { block() } catch (e: ProviderException) {
        if ((e.status == 401 || e.status == 403) && authFor != runCatching { key() }.getOrNull() && verify() == null) block() else throw e
    }

    private suspend fun raw(url: String, hs: Map<String, String>): Pair<Int, String> = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).apply { hs.forEach { (k, v) -> header(k, v) } }.build()).execute().use { it.code to (it.body?.string().orEmpty()) }
    }
    private fun message(code: Int, text: String) = "Composio $code: " + (runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()?.takeIf { it.isNotBlank() } ?: text.take(200))

    private suspend fun get(path: String, query: Map<String, String> = emptyMap()): JSONObject = withAuth {
        withContext(Dispatchers.IO) {
            val url = "$base$path".toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
            http.newCall(Request.Builder().url(url).apply { headers().forEach { (k, v) -> header(k, v) } }.build()).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw ProviderException(message(res.code, text), res.code)
                JSONObject(text)
            }
        }
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject = withAuth { postJson(http, "$base$path", headers(), body, attempts = 2) }

    /** Work out how this key authenticates. Null when it works, otherwise what Composio said. */
    suspend fun verify(): String? {
        val k = runCatching { key() }.getOrElse { return it.message }
        val probe = "$base/toolkits?limit=1"
        var last = ""
        suspend fun ok(h: String, extra: Map<String, String> = emptyMap()): Boolean {
            val (code, text) = raw(probe, mapOf(h to k) + extra)
            if (code in 200..299) { auth = h to extra; authFor = k; return true }
            last = message(code, text); return false
        }
        // Most likely first, by how the key looks.
        val order = when { k.startsWith("uak_") -> listOf("x-user-api-key", "x-api-key", "x-org-api-key"); k.startsWith("oak_") || k.startsWith("org_") -> listOf("x-org-api-key", "x-api-key", "x-user-api-key"); else -> listOf("x-api-key", "x-user-api-key", "x-org-api-key") }
        for (h in order) {
            if (ok(h)) return null
            // User and org keys can need the project spelled out: find it, then try again.
            val project = when (h) {
                "x-user-api-key" -> raw("$base/auth/session/info", mapOf(h to k)).takeIf { it.first in 200..299 }?.let { (_, t) ->
                    JSONObject(t).optJSONObject("project")?.let { p -> p.optString("nano_id").ifBlank { p.optString("id") } } }
                "x-org-api-key" -> listOf("$base/org/projects", base.replace("/v3.1", "/v3") + "/org/projects").firstNotNullOfOrNull { u ->
                    raw(u, mapOf(h to k)).takeIf { it.first in 200..299 }?.let { (_, t) ->
                        val o = runCatching { JSONObject(t) }.getOrNull(); val arr = o?.optJSONArray("items") ?: o?.optJSONArray("data") ?: runCatching { org.json.JSONArray(t) }.getOrNull()
                        arr?.optJSONObject(0)?.let { p -> p.optString("nano_id").ifBlank { p.optString("id") } } } }
                else -> null
            }?.takeIf { it.isNotBlank() }
            if (project != null && ok(h, mapOf("x-project-id" to project))) return null
        }
        return "Composio didn't accept that key ($last). Use a key from your project's Settings, API Keys."
    }

    suspend fun toolkits(search: String = "", limit: Int = 500): List<Toolkit> {
        val q = mutableMapOf("limit" to "$limit", "sort_by" to "usage")
        if (search.isNotBlank()) q["search"] = search
        val items = get("/toolkits", q).optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).map { i ->
            val t = items.getJSONObject(i); val meta = t.optJSONObject("meta") ?: JSONObject()
            Toolkit(t.optString("slug"), t.optString("name"), meta.optString("description"), meta.optString("logo"),
                meta.optInt("tools_count"), t.optBoolean("no_auth"), (t.optJSONArray("composio_managed_auth_schemes")?.length() ?: 0) > 0)
        }
    }

    suspend fun connections(): List<Connection> {
        val items = get("/connected_accounts", mapOf("user_ids" to userId, "limit" to "200")).optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).map { i ->
            val c = items.getJSONObject(i)
            val alias = if (c.isNull("alias")) "" else c.optString("alias")
            Connection(c.optString("id"), c.optJSONObject("toolkit")?.optString("slug") ?: "", c.optString("status"), alias.ifBlank { c.optJSONObject("toolkit")?.optString("slug") ?: "" })
        }
    }

    /** Find or create a Composio-managed auth config for a toolkit. */
    private suspend fun authConfigFor(toolkit: String): String {
        val items = get("/auth_configs", mapOf("toolkit_slug" to toolkit)).optJSONArray("items") ?: JSONArray()
        for (i in 0 until items.length()) {
            val a = items.getJSONObject(i)
            if (a.optJSONObject("toolkit")?.optString("slug").equals(toolkit, true) && a.optString("status", "ENABLED") != "DISABLED") return a.getString("id")
        }
        val created = post("/auth_configs", JSONObject().put("toolkit", JSONObject().put("slug", toolkit)).put("auth_config", JSONObject().put("type", "use_composio_managed_auth")))
        return created.optJSONObject("auth_config")?.optString("id")?.takeIf { it.isNotBlank() } ?: created.getString("id")
    }

    /** Start connecting an app. Returns the link the user opens to sign in to that app. */
    suspend fun connect(toolkit: String): String {
        val res = post("/connected_accounts/link", JSONObject().put("auth_config_id", authConfigFor(toolkit)).put("user_id", userId))
        return res.optString("redirect_url").ifBlank { throw ProviderException("Composio returned no sign-in link for $toolkit") }
    }

    suspend fun disconnect(connectionId: String) = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("$base/connected_accounts/$connectionId").delete().apply { headers().forEach { (k, v) -> header(k, v) } }.build()).execute().close()
    }

    suspend fun searchTools(query: String, toolkit: String?, limit: Int): JSONArray {
        val q = mutableMapOf("limit" to "$limit")
        if (!toolkit.isNullOrBlank()) q["toolkit_slug"] = toolkit
        if (query.isNotBlank()) q["query"] = query
        val items = get("/tools", q).optJSONArray("items") ?: JSONArray()
        return JSONArray().apply {
            for (i in 0 until items.length()) {
                val t = items.getJSONObject(i)
                put(JSONObject().put("slug", t.optString("slug")).put("description", t.optString("description").take(400))
                    .put("parameters", t.optJSONObject("input_parameters") ?: JSONObject()))
            }
        }
    }

    suspend fun execute(slug: String, args: JSONObject): JSONObject =
        post("/tools/execute/$slug", JSONObject().put("user_id", userId).put("arguments", args))
}

class AppsListTool(private val c: ComposioClient) : Tool {
    override val spec = ToolSpec("apps_connected", "List the user's connected apps (Gmail, Calendar, Drive, Slack... via Composio).", schema())
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val list = c.connections()
        return if (list.isEmpty()) "No apps connected yet. Use apps_connect to offer one." else list.joinToString("\n") { "${it.toolkit}: ${it.status}" }
    }
}

/**
 * The agent connects an app itself when it needs one: it asks, the user taps Connect, the
 * sign-in opens in the built-in browser, and the tool waits until Composio reports the
 * connection ACTIVE, then the agent carries on.
 */
class AppsConnectTool(private val c: ComposioClient, private val openLink: suspend (String, String) -> Unit) : Tool {
    override val spec = ToolSpec("apps_connect", "Connect an app the task needs (e.g. gmail, googlecalendar, googledrive, googledocs, slack, notion). Asks the user, opens the sign-in in the app's browser, and waits until it's connected (up to 5 minutes).",
        schema(listOf("toolkit", "reason"), "toolkit" to str("Composio toolkit slug, lower case"), "reason" to str("What you need it for, finishing 'so I can...'")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val slug = input.optString("toolkit").lowercase().trim()
        if (c.connections().any { it.toolkit == slug && it.status == "ACTIVE" }) return "$slug is already connected."
        val req = ConnectRequest.of(slug, input.optString("reason"))
        val name = req.name
        val a = ctx.ask(req.text, ConnectRequest.OPTIONS)
        if (a != "Connect") return if (a == null) "Nobody around to approve connecting $slug." else "The user doesn't want to connect $slug right now."
        val link = c.connect(slug)
        openLink(slug, link)
        val id = ctx.activity("Waiting for you to sign in to $name", JSONObject().put("tool", "apps").put("link", link))
        val deadline = System.currentTimeMillis() + 5 * 60_000
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(4000)
            if (runCatching { c.connections() }.getOrNull()?.any { it.toolkit == slug && it.status == "ACTIVE" } == true) {
                ctx.updateActivity(id, "$name connected"); return "$slug is connected now. Carry on."
            }
        }
        ctx.updateActivity(id, "$name sign-in not finished")
        return "The user hasn't finished signing in to $slug yet. Tell them you'll continue once it's connected."
    }
}

class AppsFindToolsTool(private val c: ComposioClient) : Tool {
    override val spec = ToolSpec("apps_find_tools", "Find the actions available in a connected app (e.g. toolkit gmail, query 'search unread'). Returns tool slugs with their parameters, for apps_run.",
        schema(listOf("query"), "query" to str("What you want to do"), "toolkit" to str("Toolkit slug to search within"), "limit" to int("Max results, default 6")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String =
        c.searchTools(input.optString("query"), input.optString("toolkit").takeIf { it.isNotBlank() }, input.optInt("limit", 6)).toString(1).take(16_000)
}

class AppsRunTool(private val c: ComposioClient) : Tool {
    override val spec = ToolSpec("apps_run", "Run one action in a connected app by its tool slug (from apps_find_tools), e.g. GMAIL_FETCH_EMAILS. Sending messages or changing things on the user's behalf needs their yes first (ask_user).",
        schema(listOf("slug", "arguments"), "slug" to str("Tool slug"), "arguments" to JSONObject().put("type", "object").put("description", "Arguments per the tool's parameters")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val slug = input.optString("slug")
        val id = ctx.activity("Using ${slug.substringBefore('_').lowercase()}: ${slug.substringAfter('_').lowercase().replace('_', ' ')}", JSONObject().put("tool", "apps"))
        val res = c.execute(slug, input.optJSONObject("arguments") ?: JSONObject())
        val ok = res.optBoolean("successful", true)
        ctx.updateActivity(id, (if (ok) "Used " else "Failed: ") + slug.lowercase().replace('_', ' '))
        return if (ok) (res.opt("data") ?: res).toString() else "Error: ${res.optString("error")}"
    }
}

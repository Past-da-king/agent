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

    private val writeWords = Regex("(SEND|REPLY|FORWARD|CREATE|DELETE|REMOVE|TRASH|UPDATE|PATCH|POST|PUBLISH|PAY|PURCHASE|ARCHIVE|MOVE|INVITE|SHARE|INSERT|UPLOAD|ACCEPT|DECLINE|CANCEL)")

    /** Each Composio Connect tool as one of the agent's tools. Actions that change things need the user's yes. */
    fun asTools(key: () -> String?): List<Tool> = tools.map { t ->
        val name = t.optString("name")
        object : Tool {
            override val spec = ToolSpec(name, t.optString("description").take(1500), t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object"))
            override suspend fun run(input: JSONObject, ctx: ToolContext): String {
                val k = key() ?: return "No Composio key."
                // Executing app actions: ask before anything that sends, posts, pays or deletes.
                if (name.contains("EXECUTE", true)) {
                    val slugs = Regex("\"(?:tool_slug|slug|action)\"\\s*:\\s*\"([A-Z0-9_]+)\"").findAll(input.toString()).map { it.groupValues[1] }.toList()
                    if (slugs.any { writeWords.containsMatchIn(it.substringAfter('_')) }) {
                        val a = ctx.ask("APPROVAL|${slugs.joinToString(", ") { it.lowercase().replace('_', ' ') }}|${input.toString(2).take(1500)}", listOf("Approve", "Decline"))
                        if (a != "Approve") return if (a == null) "Needs the user's approval and nobody is around. Not done." else "The user declined. Not done."
                    }
                }
                ctx.activity("Composio: ${name.removePrefix("COMPOSIO_").lowercase().replace('_', ' ')}", JSONObject().put("tool", "apps"))
                return call(k, name, input)
            }
        }
    }
}

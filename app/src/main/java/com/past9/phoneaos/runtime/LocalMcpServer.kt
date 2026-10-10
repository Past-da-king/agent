package com.past9.phoneaos.runtime

import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Our tools (memory, tasks, browser, apps, routines, ask/approve, notify) exposed to Claude Code
 * as an MCP server over Streamable HTTP on 127.0.0.1 only. Every request must carry the
 * per-install bearer token, so no other app on the phone can drive the agent's tools.
 *
 * Implements the slice of MCP the Agent SDK uses: initialize, the notifications, ping,
 * tools/list, tools/call. Responses are plain JSON (no SSE), which the spec allows.
 */
class LocalMcpServer(
    private val token: String,
    private val tools: () -> List<Tool>,
    private val context: () -> ToolContext,
    private val port: Int = 0,
    /** Helpers get their own endpoint, /mcp/h/<id>: the worker tools, acting as that helper. Null when it isn't running. */
    private val helper: ((Long) -> Pair<List<Tool>, ToolContext>?)? = null,
    /** Card scripts reading connected apps: /mcp/card, read-only app tools and nobody to approve anything. */
    private val card: (() -> Pair<List<Tool>, ToolContext>)? = null,
    /** A branch of the main agent (double texting) has its own endpoint, /mcp/b/<item id>: the main tools, with its own step budget. */
    private val branch: ((Long) -> Pair<List<Tool>, ToolContext>?)? = null,
    /** A routine run on a subscription has its own endpoint, /mcp/r/<run id>: the worker tools, acting as that routine. */
    private val routine: ((Long) -> Pair<List<Tool>, ToolContext>?)? = null,
) {
    private var server: ServerSocket? = null
    val boundPort: Int get() = server?.localPort ?: -1
    val url: String get() = "http://127.0.0.1:$boundPort/mcp"

    fun start(): LocalMcpServer {
        val ss = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1")); server = ss
        thread(name = "mcp-http", isDaemon = true) {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { try { handle(s) } catch (_: Exception) {} finally { runCatching { s.close() } } }
            }
        }
        return this
    }

    fun stop() { runCatching { server?.close() }; server = null }

    private fun readLine(inp: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) { val c = inp.read(); if (c < 0) return if (sb.isEmpty()) null else sb.toString(); if (c == '\n'.code) return sb.toString().trimEnd('\r'); sb.append(c.toChar()) }
    }

    private fun handle(s: Socket) {
        val inp = BufferedInputStream(s.getInputStream())
        val req = readLine(inp) ?: return
        val method = req.substringBefore(' '); val path = req.split(' ').getOrElse(1) { "/" }.substringBefore('?')
        val headers = mutableMapOf<String, String>()
        while (true) { val h = readLine(inp) ?: break; if (h.isEmpty()) break; headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim() }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val bodyBytes = ByteArray(len); var read = 0
        while (read < len) { val n = inp.read(bodyBytes, read, len - read); if (n < 0) break; read += n }
        val out = s.getOutputStream()

        val route: Pair<List<Tool>, ToolContext>? = when {
            path == "/mcp" -> null
            path == "/mcp/card" -> card?.invoke() ?: return reply(out, 404, "text/plain", "not found")
            path.startsWith("/mcp/b/") -> path.removePrefix("/mcp/b/").toLongOrNull()?.let { branch?.invoke(it) } ?: return reply(out, 404, "text/plain", "no such branch")
            path.startsWith("/mcp/h/") -> path.removePrefix("/mcp/h/").toLongOrNull()?.let { helper?.invoke(it) } ?: return reply(out, 404, "text/plain", "no such helper")
            path.startsWith("/mcp/r/") -> path.removePrefix("/mcp/r/").toLongOrNull()?.let { routine?.invoke(it) } ?: return reply(out, 404, "text/plain", "no such routine run")
            else -> return reply(out, 404, "text/plain", "not found")
        }
        if (headers["authorization"] != "Bearer $token") return reply(out, 401, "application/json", """{"error":"unauthorized"}""")
        if (method == "GET") return reply(out, 405, "text/plain", "SSE stream not supported")
        if (method == "DELETE") return reply(out, 200, "text/plain", "")
        if (method != "POST") return reply(out, 405, "text/plain", "method")

        val body = String(bodyBytes, Charsets.UTF_8).trim()
        val result = if (body.startsWith("[")) {
            val arr = JSONArray(body); val res = JSONArray()
            for (i in 0 until arr.length()) dispatch(arr.getJSONObject(i), route)?.let { res.put(it) }
            if (res.length() == 0) null else res.toString()
        } else dispatch(JSONObject(body), route)?.toString()
        if (result == null) reply(out, 202, "application/json", "") else reply(out, 200, "application/json", result, mapOf("Mcp-Session-Id" to "phone"))
    }

    /** Returns null for notifications (no id). */
    fun dispatch(msg: JSONObject, route: Pair<List<Tool>, ToolContext>? = null): JSONObject? {
        val tools: () -> List<Tool> = route?.let { r -> { r.first } } ?: this.tools
        val context: () -> ToolContext = route?.let { r -> { r.second } } ?: this.context
        val id = msg.opt("id") ?: return null
        val params = msg.optJSONObject("params") ?: JSONObject()
        fun ok(r: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", r)
        fun err(code: Int, m: String) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", JSONObject().put("code", code).put("message", m))
        return when (msg.optString("method")) {
            "initialize" -> ok(JSONObject()
                .put("protocolVersion", params.optString("protocolVersion").ifBlank { "2025-06-18" })
                .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                .put("serverInfo", JSONObject().put("name", "phone").put("version", "0.1.0"))
                .put("instructions", "These tools act on the user's phone: their memory, tasks, routines, the background browser, their connected apps, and asking or notifying them."))
            "ping" -> ok(JSONObject())
            "tools/list" -> ok(JSONObject().put("tools", JSONArray().apply {
                tools().forEach { put(JSONObject().put("name", it.spec.name).put("description", it.spec.description).put("inputSchema", it.spec.schema)) }
            }))
            "tools/call" -> {
                val name = params.optString("name"); val tool = tools().firstOrNull { it.spec.name == name } ?: return err(-32602, "Unknown tool $name")
                val (text, isErr) = try { runBlocking { tool.run(params.optJSONObject("arguments") ?: JSONObject(), context()) } to false }
                    catch (e: CancellationException) { throw e } catch (e: Exception) { "Error: ${e.message}" to true }
                ok(JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))).put("isError", isErr))
            }
            else -> err(-32601, "Method not found")
        }
    }

    private fun reply(out: OutputStream, code: Int, type: String, body: String, extra: Map<String, String> = emptyMap()) {
        val b = body.toByteArray()
        val status = mapOf(200 to "OK", 202 to "Accepted", 401 to "Unauthorized", 404 to "Not Found", 405 to "Method Not Allowed")[code] ?: "OK"
        out.write(("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${b.size}\r\nConnection: close\r\n" +
            extra.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n").toByteArray())
        out.write(b); out.flush()
    }
}

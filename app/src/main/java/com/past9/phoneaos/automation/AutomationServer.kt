package com.past9.phoneaos.automation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The socket under [AutomationApi]: MacroDroid, Tasker or any other app on this phone (GitHub issue #18).
 *
 * Routes (all JSON, all behind `Authorization: Bearer <token>`):
 *   GET  /v1/health            this endpoint is up, and whether outside runs are allowed
 *   GET  /v1/routines          every routine: id, name, enabled, schedule, lastResult
 *   POST /v1/routines/run      {"name":"..."} or {"id":12}, optional "context": fire it now, get a run id
 *   GET  /v1/runs/{id}         the status and the FULL result text of that run
 *   POST /v1/instruction       {"text":"..."}: send the agent an instruction, get a run id
 *   GET  /v1/instructions/{id} the status and the reply of that instruction
 *
 * 127.0.0.1 only, never 0.0.0.0: nothing off this phone can reach it. A request and its answer are short, so one
 * connection carries one request and closes. This file never calls the runtime: the handlers are supplied by it.
 */
class AutomationServer(
    /** The first port tried; the next free one is used when it is taken (another app, or a second copy running). */
    private val port: Int = PORT,
) {
    /** What the runtime gives the endpoint. Resolving a name to a routine happens here, with [routines]. */
    class Handlers(
        /** The user's switch: off means every call is refused instead of running anything. */
        val allowed: () -> Boolean,
        val routines: () -> List<AutomationRoutine>,
        /** Fire this routine now with an extra note; returns the run id to poll, or null when it cannot start. */
        val fire: (AutomationRoutine, String) -> String?,
        /** Send the agent an instruction with nobody watching; returns the run id to poll, or null. */
        val instruct: (String) -> String?,
        /** A run or instruction by id, or null when this process never started it. */
        val run: (String) -> AutomationRuns.State?,
    )

    private var server: ServerSocket? = null

    @Volatile
    var boundPort: Int = -1
        private set

    val url: String get() = "http://127.0.0.1:$boundPort"
    val running: Boolean get() = server?.isClosed == false

    /** Start listening. Safe to call twice: the second call does nothing. */
    fun start(scope: CoroutineScope, token: () -> String?, handlers: () -> Handlers?): AutomationServer {
        if (running) return this
        var socket: ServerSocket? = null
        var p = port
        while (socket == null && p <= port + 10) {
            socket = try { ServerSocket(p, 16, InetAddress.getByName("127.0.0.1")) } catch (_: Exception) { null }
            if (socket == null) p++
        }
        val ss = socket ?: return this
        server = ss
        boundPort = ss.localPort
        scope.launch(Dispatchers.IO) {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (_: Exception) { break }
                // Each request is short and answered in place; a stuck client must never hold the accept loop.
                scope.launch(Dispatchers.IO) { try { handle(s, token, handlers) } catch (_: Exception) { } finally { runCatching { s.close() } } }
            }
        }
        return this
    }

    /** Stop listening. Safe to call twice, and safe before [start]. */
    fun stop() {
        runCatching { server?.close() }
        server = null
        boundPort = -1
    }

    private fun handle(s: Socket, token: () -> String?, handlers: () -> Handlers?) {
        val inp = BufferedInputStream(s.getInputStream())
        val out = s.getOutputStream()
        val request = readLine(inp) ?: return
        val method = request.substringBefore(' ').ifBlank { "GET" }.uppercase()
        val path = request.split(' ').getOrElse(1) { "/" }.substringBefore('?')
        val headers = mutableMapOf<String, String>()
        while (true) {
            val h = readLine(inp) ?: break
            if (h.isEmpty()) break
            headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim()
        }
        val body = readBody(inp, headers["content-length"]?.toIntOrNull() ?: 0) ?: return reply(out, 413, AutomationApi.error(413, "the body is over 64 KB"))

        // The token comes first, before anything is looked at: no token, no answers.
        if (!AutomationApi.authorised(headers["authorization"], token().orEmpty())) {
            return reply(out, 401, AutomationApi.error(401, "unauthorized: send Authorization: Bearer <token> from Settings, Automation from other apps"))
        }
        val h = handlers() ?: return reply(out, 503, AutomationApi.error(503, "the agent is still starting"))
        // The user's switch comes before the routes: off means nothing runs, and the answer says why.
        if (path.trimEnd('/') != "/v1/health" && !h.allowed()) {
            return reply(out, 403, AutomationApi.error(403, "automation is switched off: Settings, Automation from other apps"))
        }
        val (code, json) = route(method, path, body, h)
        reply(out, code, json)
    }

    /** The route table. Everything past the token check lands here. */
    private fun route(method: String, path: String, body: String, h: Handlers): Pair<Int, String> {
        val p = path.trimEnd('/').ifBlank { "/" }
        return when {
            p == "/v1/health" && method == "GET" ->
                200 to JSONObject().put("ok", true).put("app", "Agent").put("server", "automation")
                    .put("allowed", h.allowed()).toString()

            p == "/v1/routines" && method == "GET" -> 200 to AutomationApi.routines(h.routines())

            p == "/v1/routines/run" && method == "POST" -> {
                val b = parse(body) ?: return 400 to AutomationApi.error(400, "the body must be JSON: {\"name\":\"Morning brief\"} or {\"id\":12}")
                val id = b.optLong("id", 0L).takeIf { it > 0 }
                val name = b.optString("name").trim()
                val all = h.routines()
                val found = when {
                    id != null -> all.firstOrNull { it.id == id }
                    name.isNotEmpty() -> all.firstOrNull { it.name.equals(name, ignoreCase = true) }
                        ?: all.firstOrNull { it.name.contains(name, ignoreCase = true) }
                    else -> null
                } ?: return 404 to JSONObject(AutomationApi.error(404, if (name.isNotEmpty()) "no routine called \"$name\"" else "send a name or an id"))
                    .put("routines", org.json.JSONArray(all.map { it.name })).toString()
                val runId = h.fire(found, b.optString("context")) ?: return 503 to AutomationApi.error(503, "could not start \"${found.name}\"")
                200 to AutomationApi.runAck(runId)
            }

            p == "/v1/instruction" && method == "POST" -> {
                val b = parse(body) ?: return 400 to AutomationApi.error(400, "the body must be JSON: {\"text\":\"...\"}")
                val text = b.optString("text").trim()
                if (text.isEmpty()) return 400 to AutomationApi.error(400, "the instruction is empty")
                val runId = h.instruct(text) ?: return 503 to AutomationApi.error(503, "the instruction could not be started")
                200 to AutomationApi.runAck(runId)
            }

            p.startsWith("/v1/runs/") && method == "GET" -> {
                val id = p.removePrefix("/v1/runs/").trim()
                h.run(id)?.let { 200 to AutomationApi.runState(it) } ?: (404 to AutomationApi.error(404, "no such run: this process never started \"$id\""))
            }

            p.startsWith("/v1/instructions/") && method == "GET" -> {
                val id = p.removePrefix("/v1/instructions/").trim()
                h.run(id)?.let { 200 to AutomationApi.runState(it) } ?: (404 to AutomationApi.error(404, "no such instruction: this process never started \"$id\""))
            }

            p in ROUTES -> 405 to AutomationApi.error(405, "$p is $method only")
            else -> 404 to JSONObject(AutomationApi.error(404, "no such route"))
                .put("routes", org.json.JSONArray(ROUTES)).toString()
        }
    }

    private fun parse(body: String): JSONObject? = runCatching { JSONObject(body.trim().ifEmpty { "{}" }) }.getOrNull()

    /** The body, or null when it runs past the cap. 64 KB is far more than an instruction needs and stops a flood. */
    private fun readBody(inp: InputStream, length: Int): String? {
        if (length <= 0) return ""
        if (length > MAX_BODY) return null
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) { val n = inp.read(bytes, read, length - read); if (n < 0) break; read += n }
        return String(bytes, 0, read, Charsets.UTF_8)
    }

    private fun readLine(inp: BufferedInputStream): String? {
        val line = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c < 0) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length > MAX_LINE) return null
            line.append(c.toChar())
        }
    }

    private fun reply(out: OutputStream, code: Int, json: String) {
        val bytes = json.toByteArray()
        val status = STATUS[code] ?: "OK"
        out.write(("HTTP/1.1 $code $status\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    companion object {
        /** Fixed so a macro's address never changes; the next free port is used if this one is taken. */
        const val PORT = 47321
        private const val MAX_BODY = 64 * 1024
        private const val MAX_LINE = 8 * 1024
        private val ROUTES = listOf("/v1/health", "/v1/routines", "/v1/routines/run", "/v1/instruction")
        private val STATUS = mapOf(
            200 to "OK", 400 to "Bad Request", 401 to "Unauthorized", 404 to "Not Found",
            405 to "Method Not Allowed", 413 to "Payload Too Large", 503 to "Service Unavailable",
        )
    }
}

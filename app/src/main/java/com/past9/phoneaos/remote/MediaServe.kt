package com.past9.phoneaos.remote

import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A tiny HTTP server on 127.0.0.1 that this phone's own player reads a shown video through.
 *
 * A player needs an address it can seek in, and a file that is still arriving cannot be scrubbed. This answers Range
 * requests out of [MediaFiles], which fetches only the bytes that are missing from the computer over the link that is
 * already open, so pressing play starts on the first chunk and dragging to the middle fetches from the middle. The
 * file grows in the cache as it plays, so a scrub back costs nothing.
 *
 * Loopback only, and every address carries a secret made when the app starts, so no other app on this phone can read
 * what the agent showed him. It answers reads and nothing else: no folder, no listing, no path.
 */
class MediaServe(private val files: MediaFiles, private val ask: suspend (ShownFile, Long, Int) -> ByteArray?) {

    private var server: ServerSocket? = null
    private val secret = java.util.UUID.randomUUID().toString().replace("-", "")

    val port: Int get() = server?.localPort ?: -1

    @Synchronized
    fun start(): MediaServe {
        if (server != null) return this
        val bound = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server = bound
        thread(name = "shown-media", isDaemon = true) {
            while (!bound.isClosed) {
                val socket = try { bound.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { runCatching { handle(socket) }; runCatching { socket.close() } }
            }
        }
        return this
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    /** The address this phone's player reads one shown file at, or null when the server is not running. */
    fun url(token: String): String? {
        val bound = port
        if (bound <= 0) return null
        return "http://127.0.0.1:" + bound + "/m/" + secret + "/" + token
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val first = lines.firstOrNull()?.trim()?.split(" ") ?: return
        if (first.size < 2) return
        val method = first[0].uppercase()
        val path = first[1]
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        if (method != "GET" && method != "HEAD") { plain(output, 405, "Only a read is allowed."); return }
        val prefix = "/m/" + secret + "/"
        if (!path.startsWith(prefix)) { plain(output, 404, "Not found."); return }
        val token = path.removePrefix(prefix).substringBefore('?').trim()
        val file = files.heldFor(token) ?: run { plain(output, 404, "That file is not on this phone."); return }
        val size = file.info.size
        val range = MediaRange.of(headers["range"], size)
        if (range is MediaRange.Bad) { plain(output, 416, "That range is not in the file."); return }
        val from: Long
        val to: Long
        when (range) {
            is MediaRange.Part -> { from = range.from; to = range.to }
            else -> { from = 0; to = size - 1 }
        }
        val length = to - from + 1
        val status = if (range is MediaRange.Part) "206 Partial Content" else "200 OK"
        val answer = StringBuilder()
            .append("HTTP/1.1 ").append(status).append("\r\n")
            .append("Content-Type: ").append(file.info.mime).append("\r\n")
            .append("Accept-Ranges: bytes\r\n")
            .append("Content-Length: ").append(length).append("\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("Connection: close\r\n")
        if (range is MediaRange.Part) answer.append("Content-Range: bytes ").append(from).append("-").append(to).append("/").append(size).append("\r\n")
        output.write((answer.toString() + "\r\n").toByteArray())
        output.flush()
        if (method == "HEAD") return
        var at = from
        while (at <= to) {
            val want = minOf(SLICE.toLong(), to - at + 1).toInt()
            val bytes = runBlocking { files.bytes(file, at, want) { offset, count -> ask(file.info, offset, count) } }
            if (bytes == null) {
                // The headers are already out, so the only honest thing left is to stop: the player says it failed.
                return
            }
            output.write(bytes)
            output.flush()
            at += want
        }
    }

    /** Reads the request head, up to the blank line. Null when the connection said nothing usable. */
    private fun readHead(input: InputStream): String? {
        val text = StringBuilder()
        while (!text.endsWith("\r\n\r\n")) {
            val c = input.read()
            if (c < 0) return if (text.isEmpty()) null else text.toString()
            text.append(c.toChar())
            if (text.length > 8192) return text.toString()
        }
        return text.toString()
    }

    private fun plain(output: OutputStream, code: Int, why: String) {
        val body = why.toByteArray()
        val head = "HTTP/1.1 " + code + " " + (if (code == 404) "Not Found" else if (code == 416) "Range Not Satisfiable" else "Method Not Allowed") +
            "\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + body.size + "\r\nConnection: close\r\n\r\n"
        runCatching {
            output.write(head.toByteArray())
            output.write(body)
            output.flush()
        }
    }

    companion object {
        /** How much is written to the player at a time: small enough to show the first frame early. */
        private const val SLICE = 64 * 1024
    }
}

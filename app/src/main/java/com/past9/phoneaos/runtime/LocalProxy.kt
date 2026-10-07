package com.past9.phoneaos.runtime

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A tiny HTTP CONNECT proxy on 127.0.0.1. Codex is a static musl binary, and musl resolves
 * names only through /etc/resolv.conf, which Android doesn't have. Pointing HTTPS_PROXY here
 * lets Android do the DNS lookup and Codex just tunnels TLS through us. Loopback only.
 */
class LocalProxy {
    private var server: ServerSocket? = null
    val port: Int get() = server?.localPort ?: -1

    @Synchronized fun start(): LocalProxy {
        if (server != null) return this
        val ss = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1")); server = ss
        thread(name = "local-proxy", isDaemon = true) {
            while (!ss.isClosed) {
                val c = try { ss.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { runCatching { handle(c) }; runCatching { c.close() } }
            }
        }
        return this
    }

    private fun readHead(inp: InputStream): String {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) { val b = inp.read(); if (b < 0) break; sb.append(b.toChar()); if (sb.length > 16_384) break }
        return sb.toString()
    }

    private fun handle(client: Socket) {
        val cin = client.getInputStream(); val cout = client.getOutputStream()
        val head = readHead(cin)
        val first = head.substringBefore("\r\n").split(" ")
        if (first.size < 2) return
        if (first[0].equals("CONNECT", true)) {
            val host = first[1].substringBeforeLast(':'); val port = first[1].substringAfterLast(':').toIntOrNull() ?: 443
            val up = try { Socket().apply { connect(InetSocketAddress(host, port), 20_000) } }
                catch (e: Exception) { cout.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray()); cout.flush(); return }
            cout.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray()); cout.flush()
            pipe(cin, cout, up)
        } else {
            // Plain http://host/path through the proxy: forward the request as-is.
            val url = java.net.URI(first[1])
            val up = Socket().apply { connect(InetSocketAddress(url.host, if (url.port > 0) url.port else 80), 20_000) }
            val path = (url.rawPath ?: "/").ifEmpty { "/" } + (url.rawQuery?.let { "?$it" } ?: "")
            up.getOutputStream().write(head.replaceFirst(first[1], path).toByteArray())
            pipe(cin, cout, up)
        }
    }

    private fun pipe(cin: InputStream, cout: OutputStream, up: Socket) {
        val t = thread(isDaemon = true) { runCatching { cin.copyTo(up.getOutputStream()) }; runCatching { up.shutdownOutput() } }
        runCatching { up.getInputStream().copyTo(cout) }
        runCatching { up.close() }; t.join(1000)
    }
}

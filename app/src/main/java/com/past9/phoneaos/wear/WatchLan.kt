package com.past9.phoneaos.wear

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.past9.phoneaos.wearproto.PairLan
import com.past9.phoneaos.wearproto.PairReply
import com.past9.phoneaos.wearproto.PairWire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * The phone's end of the second pairing road (see [PairLan]): find the watch on the local network (the QR gives its address, a typed code
 * has to look for it) and send the same pairing request the Data Layer carries. Only addresses on a home or office network are dialled.
 */
object WatchLan {
    /** Ask the watch at [host]:[port]; null when nothing there answered. */
    suspend fun ask(host: String, port: Int, request: ByteArray, timeoutMs: Int = 7_000, strict: Boolean = true): PairReply? = withContext(Dispatchers.IO) {
        if (strict && PairLan.parseHost("$host:$port") == null) return@withContext null
        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 3_000)
                s.soTimeout = timeoutMs
                s.getOutputStream().apply { write(PairLan.line(request)); flush() }
                val out = ByteArrayOutputStream(); val input = s.getInputStream()
                while (out.size() < PairLan.MAX_LINE) { val b = input.read(); if (b < 0 || b == '\n'.code) break; out.write(b) }
                PairWire.parseResult(out.toByteArray())
            }
        }.getOrNull()
    }

    /** Watches advertising themselves on this network: address and port, found within [maxMs] (a short grace after the first, for a second watch). */
    suspend fun discover(c: Context, maxMs: Long = 5_000, graceMs: Long = 700): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
        val nsd = c.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return@withContext emptyList()
        val found = ConcurrentHashMap<String, Pair<String, Int>>()
        val gate = Mutex()
        val pending = java.util.concurrent.LinkedBlockingQueue<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) {}
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
            override fun onServiceLost(i: NsdServiceInfo) {}
            override fun onServiceFound(i: NsdServiceInfo) { if (i.serviceName.startsWith(PairLan.SERVICE_NAME)) pending.add(i) }
        }
        try {
            nsd.discoverServices(PairLan.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            withTimeoutOrNull(maxMs) {
                var firstAt = 0L
                while (true) {
                    val next = pending.poll()
                    if (next != null) {
                        gate.withLock {
                            val r = withTimeoutOrNull(2_500) {
                                suspendCancellableCoroutine<NsdServiceInfo?> { k ->
                                    @Suppress("DEPRECATION")
                                    nsd.resolveService(next, object : NsdManager.ResolveListener {
                                        override fun onResolveFailed(i: NsdServiceInfo, code: Int) { if (k.isActive) k.resume(null) }
                                        override fun onServiceResolved(i: NsdServiceInfo) { if (k.isActive) k.resume(i) }
                                    })
                                }
                            }
                            @Suppress("DEPRECATION")
                            val ip = r?.host?.hostAddress
                            if (r != null && ip != null && PairLan.parseHost("$ip:${r.port}") != null) {
                                found["$ip:${r.port}"] = ip to r.port
                                if (firstAt == 0L) firstAt = System.currentTimeMillis()
                            }
                        }
                    } else delay(100)
                    if (firstAt != 0L && System.currentTimeMillis() - firstAt > graceMs && pending.isEmpty()) break
                }
            }
        } finally { runCatching { nsd.stopServiceDiscovery(listener) } }
        found.values.toList()
    }
}

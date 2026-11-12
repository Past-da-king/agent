package com.past9.phoneaos.wear

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.past9.phoneaos.wearproto.PairLan
import com.past9.phoneaos.wearproto.PairLog
import com.past9.phoneaos.wearproto.PairReply
import com.past9.phoneaos.wearproto.PairWire
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * The second road for pairing (see [PairLan]): while the pairing screen is up the watch listens on a TCP port on the local network and
 * advertises it, so a phone on the same Wi-Fi can pair even when the Data Layer message never reaches this app (a maker's battery manager
 * freezing the app, the phone app in a work or Secure Folder profile). One request line in, one answer line out; the proof is the code or QR
 * secret, checked by the same [com.past9.phoneaos.wearproto.PairHost] the Data Layer road uses.
 */
object WatchLanPair {
    private const val TAG = "WatchLanPair"
    private var server: ServerSocket? = null
    private var thread: Thread? = null
    private var nsd: NsdManager? = null
    private var listener: NsdManager.RegistrationListener? = null

    /** The address to put in the QR ("192.168.1.49:41475"), or "" when this watch is not on a network. Starts listening if it is not already. */
    @Synchronized fun start(c: Context): String {
        if (server == null) {
            val s = runCatching { ServerSocket(0) }.getOrElse { PairLog.i("watch: lan listen failed: ${it.message}"); return "" }
            server = s
            PairLog.i("watch: lan listener added port=${s.localPort}")
            thread = Thread({ serve(c.applicationContext, s) }, "watch-lan-pair").apply { isDaemon = true; start() }
            advertise(c.applicationContext, s.localPort)
        }
        val ip = localAddress() ?: return ""
        return "$ip:${server!!.localPort}"
    }

    /** The port being listened on, or 0 when not listening (for the log). */
    @Synchronized fun port(): Int = server?.localPort ?: 0

    @Synchronized fun stop() {
        if (server != null) PairLog.i("watch: lan listener removed port=${server?.localPort}")
        runCatching { listener?.let { nsd?.unregisterService(it) } }
        listener = null; nsd = null
        runCatching { server?.close() }
        server = null; thread = null
    }

    private fun advertise(c: Context, port: Int) {
        val info = NsdServiceInfo().apply { serviceName = PairLan.SERVICE_NAME; serviceType = PairLan.SERVICE_TYPE; setPort(port) }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { PairLog.i("watch: lan advertised as ${i.serviceName} port=$port") }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { PairLog.i("watch: lan advertise failed code=$code") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        nsd = c.getSystemService(Context.NSD_SERVICE) as? NsdManager
        listener = l
        runCatching { nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { Log.w(TAG, "advertise: ${it.message}") }
    }

    private fun serve(c: Context, s: ServerSocket) {
        while (!s.isClosed) {
            val sock = runCatching { s.accept() }.getOrNull() ?: break
            runCatching { handle(c, sock) }.onFailure { Log.w(TAG, "request: ${it.message}") }
            runCatching { sock.close() }
        }
    }

    private fun handle(c: Context, sock: Socket) {
        sock.soTimeout = 5_000
        PairLog.i("watch: lan connection from ${sock.inetAddress?.hostAddress}")
        val line = readLine(sock) ?: run { PairLog.i("watch: lan request empty"); return }
        val reply: PairReply = runBlocking { withTimeoutOrNull(8_000) { WatchPairingState.onLanRequest(c, line) } } ?: PairReply.Refused("wrong_code")
        sock.getOutputStream().apply { write(PairLan.line(PairWire.result(reply))); flush() }
    }

    internal fun readLine(sock: Socket): ByteArray? {
        val out = ByteArrayOutputStream(); val input = sock.getInputStream()
        while (out.size() < PairLan.MAX_LINE) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            out.write(b)
        }
        return if (out.size() == 0) null else out.toByteArray()
    }

    /** The watch's address on a home or office network (Wi-Fi), not the Bluetooth link or a mobile network. */
    internal fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.sortedByDescending { it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .map { it.hostAddress.orEmpty() }.firstOrNull { PairLan.parseHost("$it:4000") != null }
    }.getOrNull()
}

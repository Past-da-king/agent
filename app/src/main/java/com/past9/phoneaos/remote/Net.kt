package com.past9.phoneaos.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Each computer makes its own certificate; no certificate authority vouches for it. So the phone trusts exactly the
 * certificates it was given when it linked (by fingerprint, protocol s2) and nothing else. The address in the URL may
 * be any of the computer's addresses (LAN, VPN, a public name), so the host name is not checked: the pin is the check.
 */
object Pinning {
    class PinnedTrust(private val allowed: () -> Collection<String>, private val trustOnFirstUse: Boolean = false) : X509TrustManager {
        /** The fingerprint of the last certificate a server showed, whether accepted or not. */
        @Volatile var seen: String? = null
            private set

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("Not a server")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("The computer sent no certificate.")
            val fp = Pairing.fingerprint(leaf.encoded)
            seen = fp
            if (trustOnFirstUse) return
            if (fp !in allowed()) throw CertificateException("This computer's certificate is not the one this phone was linked with.")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun client(base: OkHttpClient, trust: X509TrustManager): OkHttpClient {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(trust), SecureRandom())
        return base.newBuilder().sslSocketFactory(ctx.socketFactory, trust).hostnameVerifier { _, _ -> true }.build()
    }

    /** For a WebView's SSL error: proceed only when the page's certificate is the pinned one. */
    fun matches(der: ByteArray?, fp: String): Boolean = der != null && fp.isNotBlank() && Pairing.fingerprint(der) == fp
}

/** GET /v1/health (protocol s5). */
data class Health(val id: String, val name: String, val version: String, val ready: Boolean, val busy: Boolean, val meshId: String)

/** [unreachable]: the address did not answer at all (try the next one); otherwise the computer said no. */
class PairException(message: String, val unreachable: Boolean = false) : Exception(message)

/** The plain HTTP calls to a computer, over a pinned client. */
class RemoteApi(private val http: OkHttpClient) {
    private val jsonType = "application/json".toMediaType()

    fun health(url: String, timeoutMs: Long = 3_000): Health? = runCatching {
        http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).connectTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
            .newCall(Request.Builder().url("$url/v1/health").build()).execute().use { r ->
                if (!r.isSuccessful) return null
                val o = JSONObject(r.body?.string().orEmpty())
                Health(o.optString("id"), o.optString("name"), o.optString("version"), o.optBoolean("ready"), o.optBoolean("busy"), o.optString("meshId"))
            }
    }.getOrNull()

    /** POST /v1/pair. Returns the answer's text; a refusal throws [PairException] with words a person can act on. */
    fun pair(url: String, code: String, facts: DeviceFacts): String {
        val body = JSONObject().put("code", code).put("device", facts.toJson()).put("wantRoles", JSONArray().put("screen")).toString().toRequestBody(jsonType)
        val r = try {
            http.newBuilder().callTimeout(20, TimeUnit.SECONDS).build().newCall(Request.Builder().url("$url/v1/pair").post(body).build()).execute()
        } catch (e: javax.net.ssl.SSLException) {
            throw PairException("Something else answered at ${url.removePrefix("https://")}: its certificate isn't the one in the QR code.", unreachable = true)
        } catch (e: java.io.IOException) {
            throw PairException("Couldn't reach the computer at ${url.removePrefix("https://")}. Is the Agent app open there, with Devices on, and on the same network?", unreachable = true)
        }
        r.use {
            val text = it.body?.string().orEmpty()
            if (it.isSuccessful) return text
            val why = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
            throw PairException(when (it.code) {
                400, 401, 403, 404, 410 -> "That code didn't work. Codes last 10 minutes and work once: make a new one on the computer."
                429 -> "Too many wrong tries. Make a new code on the computer."
                else -> "The computer said no (${it.code}${if (why.isNotBlank()) ": $why" else ""})."
            })
        }
    }

    /** GET /v1/devices: the brains this phone may use now, or null when the computer can't be asked. */
    fun brains(url: String, pass: String): List<BrainRef>? = runCatching {
        http.newCall(Request.Builder().url("$url/v1/devices").header("Authorization", "Bearer $pass").build()).execute().use { r ->
            if (!r.isSuccessful) return null
            Pairing.parseBrains(r.body?.string().orEmpty())
        }
    }.getOrNull()

    /** GET /v1/file?path= with the pass, or a path the computer already made into /v1/file?... ; [sink] reads the bytes. */
    fun download(url: String, pathOrUrl: String, pass: String, sink: (InputStream, String?) -> Unit) {
        val target = when {
            pathOrUrl.startsWith("https://") -> pathOrUrl
            pathOrUrl.startsWith("/v1/") -> url + pathOrUrl
            else -> "$url/v1/file?path=" + java.net.URLEncoder.encode(pathOrUrl, "UTF-8")
        }
        http.newBuilder().callTimeout(10, TimeUnit.MINUTES).readTimeout(60, TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url(target).header("Authorization", "Bearer $pass").build()).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("The computer answered ${r.code} for that file.")
                val b = r.body ?: throw java.io.IOException("Empty file.")
                sink(b.byteStream(), b.contentType()?.toString())
            }
    }
}

class RpcException(message: String) : Exception(message)

/**
 * The app contract over a WebSocket (protocol s5.1): hello with this phone's facts first, then calls by dotted name
 * and events from the computer. One instance per connection; make a new one to reconnect.
 */
class RpcClient(
    private val http: OkHttpClient,
    private val url: String,
    private val pass: String,
    private val hello: () -> JSONObject,
    private val listener: Listener,
) {
    interface Listener {
        fun onOpen(rpc: RpcClient)
        fun onEvent(rpc: RpcClient, name: String, payload: Any?)
        fun onClosed(rpc: RpcClient, why: String)
    }

    private val calls = ConcurrentHashMap<String, CompletableDeferred<Any?>>()
    private val seq = AtomicLong()
    private val ended = AtomicBoolean(false)
    @Volatile private var ws: WebSocket? = null
    @Volatile var isOpen = false
        private set

    fun connect() {
        val req = Request.Builder().url(url.replaceFirst("https://", "wss://") + "/v1/rpc")
            .header("Authorization", "Bearer $pass").header("Cookie", "agent_pass=$pass").build()
        ws = http.newBuilder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build().newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isOpen = true
                sendHello()
                listener.onOpen(this@RpcClient)
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = end("closed ($code${if (reason.isNotBlank()) " $reason" else ""})")
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = end(t.message ?: t::class.java.simpleName)
        })
    }

    /** Again whenever the facts change (rotation, a new window size). */
    fun sendHello() { ws?.send(JSONObject(hello().toString()).put("type", "hello").toString()) }

    suspend fun call(method: String, args: List<Any?>, timeoutMs: Long = 30_000): Any? {
        val id = seq.incrementAndGet().toString()
        val d = CompletableDeferred<Any?>()
        calls[id] = d
        val frame = JSONObject().put("type", "call").put("id", id).put("method", method).put("args", JSONArray(args.map { it ?: JSONObject.NULL }))
        if (ws?.send(frame.toString()) != true) { calls.remove(id); throw RpcException("Not connected.") }
        return try { withTimeout(timeoutMs) { d.await() } } finally { calls.remove(id) }
    }

    fun close() { ws?.close(1000, "bye"); end("closed by the phone") }

    internal fun handle(text: String) {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (o.optString("type")) {
            "result" -> {
                val d = calls.remove(o.opt("id")?.toString() ?: return) ?: return
                if (o.optBoolean("ok")) d.complete(o.opt("value").takeUnless { it == JSONObject.NULL })
                else d.completeExceptionally(RpcException(o.optString("error").ifBlank { "The computer couldn't do that." }))
            }
            "event" -> runCatching { listener.onEvent(this, o.optString("name"), o.opt("payload").takeUnless { it == JSONObject.NULL }) }
        }
    }

    private fun end(why: String) {
        isOpen = false
        if (!ended.compareAndSet(false, true)) return
        calls.values.forEach { it.completeExceptionally(RpcException(why)) }
        calls.clear()
        listener.onClosed(this, why)
    }
}

package com.past9.phoneaos.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64

/*
 * Linking this phone to a computer or server that runs the agent (docs/devices-protocol.md in the desktop repo,
 * sections 2, 3, 8 and 9). Everything in this file is plain Kotlin with no Android in it, so it is unit tested.
 */

/** A computer or server that can run the agent. Lower [priority] is tried first. */
data class BrainRef(val id: String, val name: String, val urls: List<String>, val fp: String, val priority: Int) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("urls", JSONArray(urls)).put("fp", fp).put("priority", priority)

    companion object {
        fun from(o: JSONObject?): BrainRef? {
            if (o == null) return null
            val id = o.optString("id").trim()
            val urls = o.optJSONArray("urls").strings().mapNotNull(Pairing::cleanUrl).distinct()
            if (id.isEmpty() || urls.isEmpty()) return null
            return BrainRef(id, o.optString("name").trim().ifEmpty { "Computer" }, urls, o.optString("fp").trim(), o.optInt("priority", 0))
        }
    }
}

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { i -> (opt(i) as? String)?.takeIf { it.isNotBlank() } }

/** What a pairing QR code carries. [fp] is null on the short-code path, where the certificate is trusted on first use. */
data class PairOffer(val meshId: String, val nodeId: String, val name: String, val urls: List<String>, val fp: String?, val code: String)

/** What the phone tells a computer about itself (protocol s3, DeviceFacts). A phone never has a certificate or urls of its own. */
data class DeviceFacts(val name: String, val kind: String, val appVersion: String, val widthDp: Int, val heightDp: Int) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("kind", kind).put("platform", "android").put("appVersion", appVersion)
        .put("screen", JSONObject().put("width", widthDp).put("height", heightDp))
        .put("canOpenLinks", true).put("canReceiveFiles", true).put("fingerprint", "").put("urls", JSONArray())
}

/** The answer to POST /v1/pair. */
data class Paired(val deviceId: String, val pass: String, val meshId: String, val nodeId: String, val nodeName: String, val brains: List<BrainRef>)

/** This phone's link: its pass and the brains it may use. Kept only in Keystore-encrypted prefs. [on] false = linked but using its own agent. */
data class Link(val meshId: String, val deviceId: String, val pass: String, val nodeName: String, val brains: List<BrainRef>, val pairedAt: Long, val on: Boolean = true) {
    fun toJson(): String = JSONObject().put("meshId", meshId).put("deviceId", deviceId).put("pass", pass).put("nodeName", nodeName)
        .put("brains", JSONArray(brains.map { it.toJson() })).put("pairedAt", pairedAt).put("on", on).toString()

    /** Every certificate this phone accepts: one per brain it was told about. */
    val fingerprints: Set<String> get() = brains.map { it.fp }.filter { it.isNotBlank() }.toSet()

    companion object {
        fun from(text: String?): Link? = runCatching {
            val o = JSONObject(text ?: return null)
            val brains = o.optJSONArray("brains")?.let { a -> (0 until a.length()).mapNotNull { BrainRef.from(a.optJSONObject(it)) } }.orEmpty()
            Link(o.getString("meshId"), o.getString("deviceId"), o.getString("pass"), o.optString("nodeName"), brains, o.optLong("pairedAt"), o.optBoolean("on", true))
                .takeIf { it.pass.isNotBlank() && it.brains.isNotEmpty() }
        }.getOrNull()
    }
}

object Pairing {
    const val DEFAULT_PORT = 7787
    /** Short codes use only letters and digits that can't be mixed up: no 0 or O, no 1 or I. */
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val FP = Regex("^[A-Za-z0-9_-]{43}$")

    fun isFingerprint(s: String) = FP.matches(s)

    /** agent://pair?v=1&mesh=..&node=..&name=..&u=url1,url2&fp=..&c=.. -> the offer, or null when it isn't one. */
    fun parseQr(text: String): PairOffer? {
        val t = text.trim()
        if (!t.startsWith("agent://pair", ignoreCase = true)) return null
        val query = t.substringAfter('?', "")
        if (query.isEmpty()) return null
        val p = HashMap<String, String>()
        for (kv in query.split('&')) {
            val k = kv.substringBefore('=', "")
            if (k.isEmpty()) continue
            runCatching { URLDecoder.decode(kv.substringAfter('='), "UTF-8") }.getOrNull()?.let { p[k] = it }
        }
        if ((p["v"] ?: "1") != "1") return null
        val code = p["c"]?.trim().orEmpty()
        val fp = p["fp"]?.trim().orEmpty()
        val urls = p["u"].orEmpty().split(',').mapNotNull { cleanUrl(it) }.distinct()
        if (code.isEmpty() || !isFingerprint(fp) || urls.isEmpty()) return null
        return PairOffer(p["mesh"].orEmpty(), p["node"].orEmpty(), p["name"]?.trim().orEmpty().ifEmpty { "Computer" }, urls, fp, code)
    }

    /** "abcd-efgh", "ABCD EFGH", "abcdefgh" -> "ABCDEFGH"; null when it isn't 8 characters of [ALPHABET]. */
    fun shortCode(text: String): String? {
        val c = text.uppercase().filter { !it.isWhitespace() && it != '-' }
        return c.takeIf { it.length == 8 && it.all { ch -> ch in ALPHABET } }
    }

    fun prettyCode(code: String): String = if (code.length == 8) code.take(4) + "-" + code.drop(4) else code

    /**
     * The computer's address as the person typed it -> https://host:port (port 7787 when none is given). Plain http is
     * refused: the pass only ever travels over TLS.
     */
    fun address(text: String): String? {
        var t = text.trim().trimEnd('/')
        if (t.isEmpty() || t.any { it.isWhitespace() } || t.startsWith("http://", true)) return null
        if (t.startsWith("https://", true)) t = t.substring(8)
        t = t.substringBefore('/')
        if (t.isEmpty()) return null
        val bracketed = t.startsWith("[")
        val colons = t.count { it == ':' }
        val bareV6 = !bracketed && colons > 1
        val hasPort = if (bracketed) t.contains("]:") else colons == 1
        if (hasPort) {
            val port = t.substringAfterLast(':').toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            if (t.substringBeforeLast(':').let { it.isEmpty() || it == "[]" }) return null
            return "https://$t"
        }
        if (bracketed && !t.endsWith("]")) return null
        return "https://" + (if (bareV6) "[$t]" else t) + ":$DEFAULT_PORT"
    }

    fun cleanUrl(u: String): String? {
        val t = u.trim().trimEnd('/')
        if (!t.startsWith("https://", true) || t.length < 12 || t.any { it.isWhitespace() }) return null
        return t
    }

    /** base64url(sha256(certificate DER)), no padding: how every device names a certificate. */
    fun fingerprint(der: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(der))

    /** A short form of a fingerprint to compare by eye with what the computer shows: "Ab3x-9KqZ-pL0w". */
    fun fingerprintWords(fp: String): String = fp.take(12).chunked(4).joinToString("-")

    /** Android's smallest screen width says phone or tablet (600 dp is the usual line). */
    fun kindFor(smallestWidthDp: Int): String = if (smallestWidthDp >= 600) "tablet" else "phone"

    /**
     * The answer to POST /v1/pair. When it names no brains, the computer that answered is the one brain, with the
     * address and certificate this phone just used.
     */
    fun parsePaired(body: String, answeredBy: BrainRef): Paired? = runCatching {
        val o = JSONObject(body)
        val node = o.optJSONObject("node")
        val nodeId = node?.optString("id").orEmpty()
        val nodeName = node?.optString("name").orEmpty().ifBlank { answeredBy.name }
        val listed = o.optJSONArray("brains")?.let { a -> (0 until a.length()).mapNotNull { BrainRef.from(a.optJSONObject(it)) } }.orEmpty()
            .map { b -> if ((b.id == nodeId || b.id == answeredBy.id) && b.fp.isBlank()) b.copy(fp = answeredBy.fp) else b }
            .filter { it.fp.isNotBlank() }
        val pass = o.optString("pass")
        if (pass.isBlank()) return null
        Paired(o.optString("deviceId"), pass, o.optString("meshId", ""), nodeId, nodeName,
            listed.ifEmpty { listOf(answeredBy.copy(id = nodeId.ifBlank { answeredBy.id }, name = nodeName)) })
    }.getOrNull()

    /**
     * The brains in a GET /v1/devices answer: rows whose roles include brain and that are not revoked. Accepts a bare
     * list, or an object holding it as devices/rows/list, and rows that wrap their fields in payload.
     */
    fun parseBrains(body: String): List<BrainRef> = runCatching {
        val trimmed = body.trim()
        val arr: JSONArray = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).let { o ->
            o.optJSONArray("devices") ?: o.optJSONArray("rows") ?: o.optJSONArray("list") ?: JSONArray()
        }
        (0 until arr.length()).mapNotNull { i ->
            val raw = arr.optJSONObject(i) ?: return@mapNotNull null
            val row = raw.optJSONObject("payload")?.let { p -> if (!p.has("id")) p.put("id", raw.optString("id")) else p } ?: raw
            val roles = row.optJSONArray("roles").strings()
            if ("brain" !in roles || row.optBoolean("revoked")) return@mapNotNull null
            BrainRef.from(row)?.takeIf { it.fp.isNotBlank() }
        }
    }.getOrDefault(emptyList())
}

/** One brain at one of its addresses. */
data class Pick(val brain: BrainRef, val url: String)

/**
 * Which brain answers (protocol s8): the highest-ranked one that is ready. A brain that stops answering is left at
 * once; a higher brain that comes back is moved to only between turns, so a reply is never cut in half.
 */
object BrainChooser {
    sealed interface Move {
        data object Stay : Move
        data class To(val pick: Pick) : Move
        data object NoneAvailable : Move
    }

    fun ordered(brains: List<BrainRef>): List<BrainRef> =
        brains.sortedWith(compareBy<BrainRef>({ it.priority }, { it.name.lowercase() }, { it.id }))

    /** How long one address gets before the next one is tried beside it. */
    private const val ADDRESS_HEAD_START_MS = 500L

    /** The first brain, by rank and not in [skip], with an address that answers ready. */
    suspend fun firstReady(brains: List<BrainRef>, skip: Set<String> = emptySet(), ready: suspend (BrainRef, String) -> Boolean): Pick? {
        for (b in ordered(brains)) {
            if (b.id in skip) continue
            val u = firstAddress(b.urls) { ready(b, it) } ?: continue
            return Pick(b, u)
        }
        return null
    }

    /**
     * Each address in turn, the next one starting beside it after a head start: a computer can hold a connection open
     * and answer nothing (frozen), and an address that does that must not make the good one wait its whole timeout.
     * The checks are blocking calls that end by their own timeout, so they run outside this coroutine's children.
     */
    private suspend fun firstAddress(urls: List<String>, ok: suspend (String) -> Boolean): String? {
        val win = CompletableDeferred<String?>()
        val jobs = ArrayList<Job>()
        val runner = CoroutineScope(Dispatchers.IO)
        for (u in urls) {
            if (win.isCompleted) break
            val job = runner.launch { if (runCatching { ok(u) }.getOrDefault(false)) win.complete(u) }
            jobs += job
            withTimeoutOrNull(ADDRESS_HEAD_START_MS) { job.join() }
        }
        runner.launch { jobs.joinAll(); win.complete(null) }
        return win.await()
    }

    fun decide(brains: List<BrainRef>, current: Pick?, currentOk: Boolean, best: Pick?, turnRunning: Boolean): Move {
        if (best == null) return if (current != null && currentOk) Move.Stay else Move.NoneAvailable
        if (current == null || !currentOk) return Move.To(best)
        if (best.brain.id == current.brain.id) return Move.Stay
        val rank = ordered(brains).map { it.id }
        val bestRank = rank.indexOf(best.brain.id).let { if (it < 0) Int.MAX_VALUE else it }
        val curRank = rank.indexOf(current.brain.id).let { if (it < 0) Int.MAX_VALUE else it }
        return if (bestRank < curRank && !turnRunning) Move.To(best) else Move.Stay
    }
}

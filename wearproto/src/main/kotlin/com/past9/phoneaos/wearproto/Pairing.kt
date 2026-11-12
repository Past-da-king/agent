package com.past9.phoneaos.wearproto

import org.json.JSONObject
import java.security.MessageDigest

/**
 * Explicit pairing between one watch and one phone (docs/wear.md, "Pairing"). Until a phone is paired, the watch shows a QR and a short
 * code and serves nobody; the phone scans the QR (or types the code), and from then on each side answers only the other. The Data Layer is
 * still the transport. The QR carries the watch's node id and a one-time secret; the short code is the first eight characters of that
 * secret, for a phone that has no camera or is not told which watch (it asks the watches near it, and only the one showing that code answers).
 *
 *  phone -> watch  MessageClient  REQUEST {secret | code, name}   watch answers RESULT {ok, reason, name}
 *  either -> other MessageClient  UNPAIR {}                        (only from the paired device)
 *  phone -> watch  DataClient     SETTINGS                         (the phone's customisations, see [Customisation])
 */
object PairPaths {
    const val REQUEST = "/agent/pair/request"
    const val RESULT = "/agent/pair/result"
    const val UNPAIR = "/agent/pair/unpair"
    const val SETTINGS = "/agent/settings"
    /** The phone's data item "this watch is paired with me" ([PairedNote]): the watch reads it when it wakes, so the two sides cannot disagree for long. */
    const val PAIRED = "/agent/paired"
}

object PairReason {
    const val NO_OFFER = "no_offer"
    const val EXPIRED = "expired"
    const val WRONG = "wrong_code"
    const val LOCKED = "locked"
    const val PAIRED_OTHER = "paired_other"
    /** The reason the phone gives a watch it is not paired with, so the watch shows its pairing screen. */
    const val NOT_PAIRED = "pair"
    /** A request over the local network that did not say which phone it is from. */
    const val NO_NODE = "no_node"

    fun text(reason: String): String = when (reason) {
        NO_OFFER -> "The watch is not showing a code. Open the pairing screen on the watch."
        EXPIRED -> "That code has expired. Get a new one on the watch."
        WRONG -> "That code is not right."
        LOCKED -> "Too many wrong codes. Get a new code on the watch."
        PAIRED_OTHER -> "This watch is already paired with another phone."
        NOT_PAIRED -> "Pair this watch with the phone first."
        NO_NODE -> "The phone could not tell the watch who it is. Turn Bluetooth on, check the watch shows as connected in the Galaxy Wearable app, then try again."
        else -> "Pairing did not work."
    }
}

/** What a phone holds: the secret from the QR, or the short code he typed. */
data class PairProof(val secret: String = "", val code: String = "")

data class PairOffer(val node: String, val secret: String, val expiresAt: Long) {
    /** The short code, as shown: "ABCD-EFGH". */
    val code: String get() = PairCode.show(secret)
    /** [host] is the watch's own address on the local network ("192.168.1.49:41475"), so a phone that cannot reach it over the Data Layer can still find it. */
    fun qr(host: String = ""): String = "agentwatch://pair?n=" + node + "&s=" + secret + (if (host.isNotBlank()) "&h=$host" else "")
}

data class ScannedOffer(val node: String, val secret: String, val host: String = "")

object PairCode {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH = 8

    fun secretOf(bytes: ByteArray): String = buildString {
        var bits = 0; var acc = 0
        for (b in bytes) { acc = (acc shl 8) or (b.toInt() and 0xFF); bits += 8; while (bits >= 5) { append(ALPHABET[(acc shr (bits - 5)) and 31]); bits -= 5 } }
        if (bits > 0) append(ALPHABET[(acc shl (5 - bits)) and 31])
    }

    fun show(secret: String): String = secret.take(LENGTH).chunked(4).joinToString("-")

    /** What was typed, made comparable: case, spaces and dashes do not matter, and the letters people mix up (O, I, L) read as the digits. */
    fun normalise(typed: String): String = typed.uppercase().filter { it.isLetterOrDigit() }.map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }.joinToString("")

    /** What the code field holds while he types or pastes: only code characters, upper case, at most eight, with the dash after the fourth ("9H4T-5PBS"). */
    fun clean(typed: String): String = normalise(typed).filter { it in ALPHABET }.take(LENGTH)
    fun format(typed: String): String = clean(typed).chunked(4).joinToString("-")
    fun complete(typed: String): Boolean = clean(typed).length == LENGTH

    fun parseQr(text: String): ScannedOffer? {
        val t = text.trim()
        if (!t.startsWith("agentwatch://pair?")) return null
        val q = t.substringAfter('?').split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val n = q["n"].orEmpty(); val s = q["s"].orEmpty()
        return if (n.isBlank() || s.length < 16) null else ScannedOffer(n, s, q["h"].orEmpty())
    }
}

sealed interface PairReply {
    /** [node] is the Data Layer node id of the watch, when the answer came by another road than the Data Layer. */
    data class Accepted(val watchName: String, val node: String = "") : PairReply
    data class Refused(val reason: String) : PairReply
}

/**
 * The watch's side of pairing, pure: one offer at a time, valid for [validMs], thrown away when it works, and locked after [maxWrong] wrong
 * tries (the short code is only forty bits). Once paired it answers only that phone: a second phone is refused even with the right code,
 * and the way to free the watch is to unpair from either side.
 */
class PairHost(
    private val random: () -> ByteArray, private val validMs: Long = 10 * 60_000L, private val maxWrong: Int = 5,
    boundPhone: String? = null, boundName: String = "", private val store: PairOfferStore? = null,
) {
    var phone: String? = boundPhone; private set
    var phoneName: String = boundName; private set
    private var offer: PairOffer? = null
    private var wrong = 0

    init {
        // A process that came back (or a service the system started cold) keeps the code on the watch's screen, for as long as it is good.
        if (boundPhone == null) store?.load()?.let { s -> offer = s.offer; wrong = s.wrong }
    }

    private fun persist() { val o = offer; if (o == null) store?.clear() else store?.save(o, wrong) }

    val paired: Boolean @Synchronized get() = phone != null

    /** A fresh offer (the QR and the code to show). Replaces the last one and clears its wrong tries. */
    @Synchronized fun newOffer(node: String, now: Long): PairOffer? {
        if (phone != null) return null
        wrong = 0
        return PairOffer(node, PairCode.secretOf(random()), now + validMs).also { offer = it; persist() }
    }

    /** The code already on show if it is still good (so backing out and coming back keeps it), else a fresh one. */
    @Synchronized fun offerOrNew(node: String, now: Long): PairOffer? = current(now) ?: newOffer(node, now)

    /**
     * The phone says this watch is paired with it and the watch missed the answer (asleep, frozen, backed out). Bind to that phone.
     * The caller has already checked the note ([PairedRule]).
     */
    @Synchronized fun adopt(phoneNode: String, name: String): Boolean {
        if (phone != null || phoneNode.isBlank()) return false
        phone = phoneNode; phoneName = name; offer = null; wrong = 0; persist()
        return true
    }

    @Synchronized fun current(now: Long): PairOffer? = offer?.takeIf { now < it.expiresAt }

    @Synchronized fun request(from: String, proof: PairProof, name: String, watchName: String, now: Long): PairReply {
        val bound = phone
        if (bound != null) return if (bound == from) PairReply.Accepted(watchName) else PairReply.Refused(PairReason.PAIRED_OTHER)
        val o = offer ?: return PairReply.Refused(PairReason.NO_OFFER)
        if (now >= o.expiresAt) { offer = null; persist(); return PairReply.Refused(PairReason.EXPIRED) }
        if (wrong >= maxWrong) return PairReply.Refused(PairReason.LOCKED)
        val ok = (proof.secret.isNotEmpty() && same(proof.secret, o.secret)) ||
            (proof.code.isNotEmpty() && same(PairCode.normalise(proof.code), PairCode.normalise(o.secret.take(PairCode.LENGTH))))
        if (!ok) { wrong++; persist(); return PairReply.Refused(PairReason.WRONG) }
        offer = null; wrong = 0; phone = from; phoneName = name; persist()
        return PairReply.Accepted(watchName)
    }

    /** Unpair. [from] is who asked: null = this watch, else a node, which counts only if it is the paired phone. */
    @Synchronized fun unpair(from: String?): Boolean {
        if (phone == null) return false
        if (from != null && from != phone) return false
        phone = null; phoneName = ""; offer = null; wrong = 0; persist()
        return true
    }

    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

/** The phone's side: which watch it is bound to. It serves that watch and no other. */
class PhoneBinding(initial: String? = null, initialName: String = "") {
    var watch: String? = initial; private set
    var watchName: String = initialName; private set

    /** An answer to a pairing request this phone made: bind on accepted. */
    @Synchronized fun onResult(from: String, reply: PairReply): Boolean {
        if (reply !is PairReply.Accepted) return false
        watch = from; watchName = reply.watchName; return true
    }

    /** May this node talk to the relay? Only the bound watch. (Pairing paths are handled before this is asked.) */
    @Synchronized fun serves(node: String): Boolean = watch == node

    @Synchronized fun unpair(from: String?): Boolean {
        if (watch == null) return false
        if (from != null && from != watch) return false
        watch = null; watchName = ""; return true
    }
}

object PairWire {
    private fun bytes(o: JSONObject) = o.toString().toByteArray(Charsets.UTF_8)
    private fun obj(b: ByteArray?): JSONObject? = if (b == null) null else runCatching { JSONObject(String(b, Charsets.UTF_8)) }.getOrNull()

    fun request(p: PairProof, phoneName: String, phoneNode: String = ""): ByteArray = bytes(JSONObject().put("secret", p.secret).put("code", p.code).put("name", phoneName.take(40)).put("node", phoneNode))
    /** The phone's own node id, when it said it (the local-network road has no sender to read it from). */
    fun parseRequestNode(b: ByteArray?): String = obj(b)?.optString("node").orEmpty()
    fun parseRequest(b: ByteArray?): Pair<PairProof, String>? {
        val o = obj(b) ?: return null
        val s = o.optString("secret"); val c = o.optString("code")
        if (s.isBlank() && c.isBlank()) return null
        return PairProof(s, c) to o.optString("name")
    }

    fun result(r: PairReply): ByteArray = bytes(when (r) {
        is PairReply.Accepted -> JSONObject().put("ok", true).put("name", r.watchName).put("node", r.node)
        is PairReply.Refused -> JSONObject().put("ok", false).put("reason", r.reason)
    })
    fun parseResult(b: ByteArray?): PairReply? {
        val o = obj(b) ?: return null
        return if (o.optBoolean("ok")) PairReply.Accepted(o.optString("name"), o.optString("node")) else PairReply.Refused(o.optString("reason", PairReason.WRONG))
    }
}

/**
 * What the watch takes from the phone's settings: who the agent is and how it looks and sounds. [rev] only goes up, so an old item
 * that arrives late never replaces a newer one. Empty fields mean "not set": the watch keeps its own default.
 */
data class Customisation(
    val agentName: String = "", val mascot: String = "", val accent: String = "", val theme: String = THEME_SYSTEM,
    val language: String = "", val voiceProvider: String = "", val voice: String = "", val rev: Long = 0L,
) {
    companion object { const val THEME_SYSTEM = "system"; const val THEME_LIGHT = "light"; const val THEME_DARK = "dark"; val THEMES = setOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK) }
}

object CustomisationWire {
    fun encode(c: Customisation): ByteArray = JSONObject().put("v", 1).put("rev", c.rev).put("agent", c.agentName).put("mascot", c.mascot)
        .put("accent", c.accent).put("theme", c.theme).put("lang", c.language).put("voiceProvider", c.voiceProvider).put("voice", c.voice).toString().toByteArray(Charsets.UTF_8)

    fun parse(b: ByteArray?): Customisation? {
        val o = if (b == null) null else runCatching { JSONObject(String(b, Charsets.UTF_8)) }.getOrNull() ?: return null
        if (o == null || !o.has("v")) return null
        val theme = o.optString("theme").takeIf { it in Customisation.THEMES } ?: Customisation.THEME_SYSTEM
        return Customisation(o.optString("agent"), Mascots.known(o.optString("mascot")), o.optString("accent"), theme, o.optString("lang"), o.optString("voiceProvider"), o.optString("voice"), o.optLong("rev"))
    }
}

/**
 * The phone side of the sync: publish only what changed (the revision is the time of the change, so it goes up), nothing before a watch is
 * bound, and never twice for the same content.
 */
class CustomisationPublisher {
    private var last: Customisation? = null
    private var rev = 0L

    /** The item to put in the Data Layer, or null when there is nothing new to say. [bound]: a watch is paired. */
    @Synchronized fun next(now: Customisation, bound: Boolean, at: Long): Customisation? {
        if (!bound) { last = null; return null }
        if (last?.copy(rev = 0) == now.copy(rev = 0)) return null
        rev = maxOf(at, rev + 1)
        return now.copy(rev = rev).also { last = it }
    }

    /** A watch just paired or opened and may have missed the last item: say it again. */
    @Synchronized fun forget() { last = null }
}

/** The watch side: keep the newest. An item with a lower or equal revision than the one held is ignored, and so is one from anybody but the paired phone. */
class CustomisationStore(private var held: Customisation = Customisation()) {
    val current: Customisation @Synchronized get() = held
    @Synchronized fun apply(incoming: Customisation, fromPaired: Boolean): Boolean {
        if (!fromPaired || incoming.rev <= held.rev) return false
        held = incoming; return true
    }
    /** Unpaired: nothing of the phone's is kept. */
    @Synchronized fun clear() { held = Customisation() }
}

/** The phone's mascot ids (its `mascot` setting). The watch draws whichever it is told; an id it does not know becomes "" and the watch keeps its default. */
object Mascots {
    val IDS = listOf("scout", "ghost", "clover", "bloom", "puff", "gem", "pebble", "heart", "bun")
    fun known(id: String): String = if (id in IDS) id else ""
}

/**
 * The second road for pairing, used when the Data Layer does not carry the request (the phone app is in a work or Secure Folder profile, the
 * watch app is frozen by the maker's battery manager, Play Services is slow): both are on the same Wi-Fi. The watch listens on a TCP port while
 * its pairing screen is up and advertises it as a service; the phone finds it (the QR carries the address, the typed code uses discovery) and sends
 * the same request line. The code or the QR secret is the proof, exactly as on the Data Layer; one line out, one line back.
 */
object PairLan {
    const val SERVICE_TYPE = "_agentwatch._tcp."
    const val SERVICE_NAME = "agent-watch"
    const val MAX_LINE = 2_048

    /** "192.168.1.49:41475" to its two parts, or null when it is not an address this road should ever dial (no names, no public hosts). */
    fun parseHost(text: String): Pair<String, Int>? {
        val i = text.lastIndexOf(':')
        if (i <= 0) return null
        val host = text.substring(0, i); val port = text.substring(i + 1).toIntOrNull() ?: return null
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { (it.toIntOrNull() ?: -1) !in 0..255 }) return null
        if (port !in 1024..65535) return null
        return if (isPrivate(parts.map { it.toInt() })) host to port else null
    }

    /** Only addresses on a home or office network: a scanned QR must never make the phone dial somebody else's server. */
    fun isPrivate(o: List<Int>): Boolean = o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168) || (o[0] == 169 && o[1] == 254)

    fun line(b: ByteArray): ByteArray = b + 10.toByte()
}

/** What went wrong, decided from facts the phone has, so the sheet can say it instead of "no watch answered". */
enum class PairProblem { NOT_MAIN_PROFILE, NO_PLAY_SERVICES, WATCH_NOT_CONNECTED, WATCH_DID_NOT_ANSWER, WIFI_UNREACHABLE, TOLD_NO }

data class PairFacts(
    /** The Android user this app runs in (0 is the owner; a work profile or Secure Folder is another). */
    val userId: Int,
    val playServices: Boolean,
    /** Watches the Data Layer lists as connected to this phone. */
    val connectedWatches: Int,
    /** Requests that the Data Layer accepted for sending. */
    val sentOverDataLayer: Int,
    /** Watches found on the local network. */
    val foundOnWifi: Int,
    val wifiConnected: Boolean,
)

object PairDiagnosis {
    fun of(f: PairFacts): PairProblem = when {
        f.userId != 0 -> PairProblem.NOT_MAIN_PROFILE
        !f.playServices -> PairProblem.NO_PLAY_SERVICES
        f.connectedWatches == 0 && f.foundOnWifi == 0 -> PairProblem.WATCH_NOT_CONNECTED
        f.foundOnWifi == 0 && !f.wifiConnected -> PairProblem.WIFI_UNREACHABLE
        else -> PairProblem.WATCH_DID_NOT_ANSWER
    }
}

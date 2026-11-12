package com.past9.phoneaos.wearproto

import org.json.JSONArray
import org.json.JSONObject

/**
 * The watch <-> phone protocol (docs/wear.md). The watch holds no account, key or model: it sends words and answers
 * over the Wearable Data Layer, and the phone relays them to the agent, whichever brain the phone uses.
 *
 *  watch -> phone  MessageClient  ASK, ANSWER, STOP, SPEAK_ON_PHONE, HELLO      (phone answers ACK)
 *  watch -> phone  ChannelClient  AUDIO_CHANNEL/<id>                            (16 kHz mono PCM16, phone answers HEARD)
 *  phone -> watch  DataClient     STATE                                         (the whole picture, one item, replaced)
 *  phone -> watch  MessageClient  ACK, HEARD
 */
object WearPaths {
    const val ASK = "/agent/ask"
    const val ANSWER = "/agent/answer"
    const val STOP = "/agent/stop"
    const val SPEAK_ON_PHONE = "/agent/speak-on-phone"
    const val HELLO = "/agent/hello"
    const val AUDIO_CHANNEL = "/agent/audio"
    const val ACK = "/agent/ack"
    const val HEARD = "/agent/heard"
    const val STATE = "/agent/state"
    /** Declared by the phone app (res/values/wear.xml); the watch finds the phone with it. */
    const val CAP_PHONE = "agent_phone_relay"
    /** Declared by the watch app. */
    const val CAP_WATCH = "agent_watch"
    const val PCM_RATE = 16_000
}

/** How a message was made, for the agent's note and for the logs. */
object Via {
    const val KEYBOARD = "keyboard"
    const val QUICK = "quick"
    const val SPEECH = "speech"
    const val PHONE = "phone"
    val ALL = setOf(KEYBOARD, QUICK, SPEECH, PHONE)
}

/** Where the turn on the watch is, in the words of the phone's own assist sheet (assist/AssistCore.kt). */
object Phase {
    const val SENDING = "sending"
    const val THINKING = "thinking"
    const val ACTING = "acting"
    const val ANSWERING = "answering"
    const val APPROVAL = "approval"
    const val ASKING = "asking"
    const val ERROR = "error"
}

object Limits {
    const val ASK_MAX = 2_000
    const val ANSWER_MAX = 3_000
    const val DETAILS_MAX = 1_200
    const val RECENT = 8
    const val RECENT_REPLY_MAX = 1_500
    const val PENDING_MAX = 5
    const val AUDIO_MAX_BYTES = 30 * WearPaths.PCM_RATE * 2
    const val OPTION_MAX = 28
    const val OPTIONS_MAX = 4
    fun clip(s: String, n: Int): String = if (s.length <= n) s else s.take(n - 1).trimEnd() + "…"
}

data class Ask(val id: String, val text: String, val via: String = Via.KEYBOARD)
data class Answer(val card: Long, val option: String)
data class Ack(val id: String, val ok: Boolean, val reason: String = "")
data class Heard(val id: String, val ok: Boolean, val text: String = "", val reason: String = "")

/** An approval or a question that waits for him. Approvals always carry exactly Approve and Decline. */
data class Pending(val id: Long, val kind: String, val title: String, val details: String, val options: List<String>, val askedBy: String) {
    val isApproval get() = kind == KIND_APPROVAL
    /** The only answers the phone will pass on: nothing outside what the card itself offers. */
    fun accepts(option: String): Boolean = option in options
    companion object {
        const val KIND_APPROVAL = "approval"
        const val KIND_QUESTION = "question"
        val APPROVAL_OPTIONS = listOf("Approve", "Decline")
    }
}

data class Exchange(val id: Long, val you: String, val reply: String, val at: Long)

data class TurnView(val phase: String, val heard: String = "", val label: String = "", val answer: String = "", val answerId: Long = 0, val problem: String = "")

/**
 * Everything the watch draws, in one item. [agent] is the agent's name from the phone's settings ("" = not named yet:
 * the watch writes its own word). [link]: "local" (the phone runs the agent), "pc" (the computer does) or "offline".
 */
data class WatchState(
    val agent: String = "", val link: String = "local", val working: Boolean = false,
    val turn: TurnView? = null, val pending: List<Pending> = emptyList(), val recent: List<Exchange> = emptyList(),
    val v: Int = VERSION,
) {
    companion object { const val VERSION = 1 }
}

object Wire {
    private fun bytes(o: JSONObject): ByteArray = o.toString().toByteArray(Charsets.UTF_8)
    private fun obj(b: ByteArray?): JSONObject? = if (b == null) null else runCatching { JSONObject(String(b, Charsets.UTF_8)) }.getOrNull()

    fun ask(a: Ask): ByteArray = bytes(JSONObject().put("id", a.id).put("text", a.text).put("via", a.via))
    fun parseAsk(b: ByteArray?): Ask? {
        val o = obj(b) ?: return null
        val id = o.optString("id"); if (id.isBlank()) return null
        val via = o.optString("via").takeIf { it in Via.ALL } ?: Via.KEYBOARD
        return Ask(id, o.optString("text"), via)
    }

    fun answer(a: Answer): ByteArray = bytes(JSONObject().put("card", a.card).put("option", a.option))
    fun parseAnswer(b: ByteArray?): Answer? {
        val o = obj(b) ?: return null
        if (!o.has("card")) return null
        val opt = o.optString("option"); if (opt.isBlank()) return null
        return Answer(o.optLong("card"), opt)
    }

    fun ack(a: Ack): ByteArray = bytes(JSONObject().put("id", a.id).put("ok", a.ok).put("reason", a.reason))
    fun parseAck(b: ByteArray?): Ack? { val o = obj(b) ?: return null; return Ack(o.optString("id"), o.optBoolean("ok"), o.optString("reason")) }

    fun heard(h: Heard): ByteArray = bytes(JSONObject().put("id", h.id).put("ok", h.ok).put("text", h.text).put("reason", h.reason))
    fun parseHeard(b: ByteArray?): Heard? { val o = obj(b) ?: return null; return Heard(o.optString("id"), o.optBoolean("ok"), o.optString("text"), o.optString("reason")) }

    fun state(s: WatchState): ByteArray {
        val o = JSONObject().put("v", s.v).put("agent", s.agent).put("link", s.link).put("working", s.working)
        s.turn?.let { t ->
            o.put("turn", JSONObject().put("phase", t.phase).put("heard", t.heard).put("label", t.label).put("answer", t.answer).put("answerId", t.answerId).put("problem", t.problem))
        }
        o.put("pending", JSONArray().also { a ->
            s.pending.forEach { p ->
                a.put(JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("details", p.details).put("by", p.askedBy)
                    .put("options", JSONArray().also { oa -> p.options.forEach(oa::put) }))
            }
        })
        o.put("recent", JSONArray().also { a -> s.recent.forEach { e -> a.put(JSONObject().put("id", e.id).put("you", e.you).put("reply", e.reply).put("at", e.at)) } })
        return bytes(o)
    }

    fun parseState(b: ByteArray?): WatchState? {
        val o = obj(b) ?: return null
        if (!o.has("v")) return null
        val turn = o.optJSONObject("turn")?.let { t -> TurnView(t.optString("phase"), t.optString("heard"), t.optString("label"), t.optString("answer"), t.optLong("answerId"), t.optString("problem")) }
        val pending = o.optJSONArray("pending")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val p = a.optJSONObject(i) ?: return@mapNotNull null
                val opts = p.optJSONArray("options")?.let { oa -> (0 until oa.length()).map { oa.optString(it) } } ?: emptyList()
                Pending(p.optLong("id"), p.optString("kind"), p.optString("title"), p.optString("details"), opts, p.optString("by"))
            }
        } ?: emptyList()
        val recent = o.optJSONArray("recent")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { e -> Exchange(e.optLong("id"), e.optString("you"), e.optString("reply"), e.optLong("at")) } }
        } ?: emptyList()
        return WatchState(o.optString("agent"), o.optString("link", "local"), o.optBoolean("working"), turn, pending, recent, o.optInt("v"))
    }
}

/** What the phone does with a request. */
data class RelayResult(val ok: Boolean, val reason: String = "")

/** The phone side of the relay: every method goes through the phone's own paths (the same ones its buttons use). */
interface RelaySink {
    suspend fun ask(text: String, via: String): RelayResult
    suspend fun answer(card: Long, option: String): RelayResult
    suspend fun stop(): RelayResult
    suspend fun speakOnPhone(): RelayResult
    suspend fun hello()
}

class Reply(val path: String, val data: ByteArray)

/**
 * Takes a message from the watch and hands it to the [sink]. A request that arrives twice (the Data Layer retries)
 * runs once: the same ask id is answered again without being sent again. Nothing here decides what a request means.
 */
class WatchRouter(private val sink: RelaySink) {
    private val seen = object : LinkedHashMap<String, Ack>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Ack>?) = size > 64
    }

    suspend fun route(path: String, data: ByteArray?): Reply? = when (path) {
        WearPaths.ASK -> handleAsk(data)
        WearPaths.ANSWER -> {
            val a = Wire.parseAnswer(data)
            if (a == null) ack("answer", false, "That answer was not readable.")
            else sink.answer(a.card, a.option).let { ack("card:${a.card}", it.ok, it.reason) }
        }
        WearPaths.STOP -> sink.stop().let { ack("stop", it.ok, it.reason) }
        WearPaths.SPEAK_ON_PHONE -> sink.speakOnPhone().let { ack("speak", it.ok, it.reason) }
        WearPaths.HELLO -> { sink.hello(); null }
        else -> null
    }

    private suspend fun handleAsk(data: ByteArray?): Reply {
        val a = Wire.parseAsk(data) ?: return ack("", false, "That message was not readable.")
        val text = a.text.trim()
        if (text.isEmpty()) return ack(a.id, false, "There was nothing to send.")
        synchronized(seen) { seen[a.id] }?.let { return Reply(WearPaths.ACK, Wire.ack(it)) }
        val r = sink.ask(Limits.clip(text, Limits.ASK_MAX), a.via)
        val ack = Ack(a.id, r.ok, r.reason)
        // A failure may be tried again with the same id; only a delivered message is remembered.
        if (r.ok) synchronized(seen) { seen[a.id] = ack }
        return Reply(WearPaths.ACK, Wire.ack(ack))
    }

    private fun ack(id: String, ok: Boolean, reason: String) = Reply(WearPaths.ACK, Wire.ack(Ack(id, ok, reason)))
}

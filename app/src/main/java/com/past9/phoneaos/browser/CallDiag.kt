package com.past9.phoneaos.browser

import org.json.JSONArray
import org.json.JSONObject

/**
 * What happened in one WhatsApp call the agent spoke in, kept so the call can explain itself afterwards: a timeline
 * of the events that matter (from the app, the voice and the page), a counter line every five seconds, the page's
 * last errors, and a verdict on who or what ended the call. Nothing here touches the call; it only remembers.
 *
 * Times are this phone's clock in milliseconds. The page stamps its own events with the same clock.
 */
class CallDiag(private val now: () -> Long = System::currentTimeMillis) {
    class Ev(val at: Long, val from: String, val kind: String, val detail: String)

    private val lock = Any()
    private val events = ArrayList<Ev>()
    private val samples = ArrayList<JSONObject>()
    private val errors = ArrayList<Pair<Long, String>>()
    private var dropped = 0
    private var startedAt = 0L
    private var answeredAt = 0L
    private var endedAt = 0L
    private var whatsapp: JSONObject? = null
    private var agent = ""
    private var agentWhy = ""
    private var voiceFailed: String? = null
    private var voiceClosed: String? = null
    private var endAsked = false
    private var pageSilent = false
    private var last: JSONObject? = null
    private var lastHeard = 0
    private var lastSent = 0

    /** A new call voice starts: everything from the call before is forgotten. */
    fun reset() = synchronized(lock) {
        events.clear(); samples.clear(); errors.clear(); dropped = 0
        startedAt = now(); answeredAt = 0; endedAt = 0; whatsapp = null; agent = ""; agentWhy = ""
        voiceFailed = null; voiceClosed = null; endAsked = false; pageSilent = false; last = null; lastHeard = 0; lastSent = 0
    }

    val started: Boolean get() = synchronized(lock) { startedAt > 0 }
    val answered: Boolean get() = synchronized(lock) { answeredAt > 0 }
    val over: Boolean get() = synchronized(lock) { endedAt > 0 }

    /** One thing that happened. [from] is app, voice, page or whatsapp. */
    fun add(from: String, kind: String, detail: String = "", at: Long = now()) = synchronized(lock) {
        if (startedAt == 0L) return@synchronized
        val t = if (at <= 0L) now() else at
        when (kind) {
            WA_CONNECTED -> if (answeredAt == 0L) answeredAt = t
            WA_ENDED -> { if (endedAt == 0L) endedAt = t; runCatching { JSONObject(detail) }.getOrNull()?.let { whatsapp = it } }
            VOICE_FAILED -> voiceFailed = detail
            VOICE_CLOSED -> voiceClosed = detail
            END_CALL -> endAsked = true
            PAGE_SILENT -> pageSilent = true
            PAGE_BACK -> pageSilent = false
        }
        // A long call must not push its own beginning or its end out: once full, only the events that decide the verdict are kept.
        if (events.size >= MAX_EVENTS && !(decides(kind, detail) && events.size < HARD_EVENTS)) { dropped++; return@synchronized }
        events += Ev(t, from, kind, detail.take(600))
    }

    fun error(text: String, at: Long = now()) = synchronized(lock) {
        if (startedAt == 0L) return@synchronized
        errors += (if (at <= 0L) now() else at) to text.take(300)
        while (errors.size > MAX_ERRORS) errors.removeAt(0)
    }

    /** The agent ended the call itself ([by] is one of the AGENT_ words), or the caller knows more than the page said. */
    fun ended(by: String, why: String, whatsappEnded: JSONObject?, at: Long = now()) = synchronized(lock) {
        if (startedAt == 0L) return@synchronized
        if (by.isNotEmpty()) { agent = by; agentWhy = why }
        if (whatsappEnded != null && whatsapp == null) whatsapp = whatsappEnded
        if (endedAt == 0L) endedAt = at
    }

    /**
     * A counter line: what the page counted ([page], cumulative) with what the app counted ([heard] frames from the
     * other person, [sent] of them on to the voice). Stored as what happened since the line before.
     */
    fun sample(page: JSONObject, heard: Int, sent: Int) = synchronized(lock) {
        if (startedAt == 0L) return@synchronized
        val at = page.optLong("at").takeIf { it > 0 } ?: now()
        val p = last
        fun d(k: String) = (page.optLong(k) - (p?.optLong(k) ?: 0L)).coerceAtLeast(0)
        val o = JSONObject().put("s", seconds(at))
            .put("voice_chunks", d("played")).put("heard_frames", (heard - lastHeard).coerceAtLeast(0)).put("frames_to_voice", (sent - lastSent).coerceAtLeast(0))
            .put("page_frames", d("remote_frames")).put("bytes_sent", d("bytes_sent")).put("bytes_received", d("bytes_received"))
            .put("bytes_sent_total", page.optLong("bytes_sent")).put("bytes_received_total", page.optLong("bytes_received"))
            .put("connections", page.optInt("pcs")).put("ice", page.optString("ice")).put("mic", page.optString("mic"))
            .put("hidden", page.optBoolean("hidden")).put("errors", page.optInt("errors"))
        last = page; lastHeard = heard; lastSent = sent
        samples += o
        // The start and the end of a call are what explain it: the middle of a long one is thinned out.
        if (samples.size > MAX_SAMPLES) samples.removeAt(KEEP_FIRST)
    }

    /** Seconds since the answer (negative before it), or since the voice was armed when nobody answered. One decimal. */
    private fun seconds(at: Long): Double { val zero = if (answeredAt > 0) answeredAt else startedAt; return Math.round((at - zero) / 100.0) / 10.0 }

    fun timeline(): JSONArray = synchronized(lock) {
        JSONArray().also { a -> events.sortedBy { it.at }.forEach { e -> a.put(JSONObject().put("s", seconds(e.at)).put("from", e.from).put("event", e.kind).also { o -> if (e.detail.isNotEmpty()) o.put("detail", e.detail) }) } }
    }

    fun counters(): JSONArray = synchronized(lock) { JSONArray(samples.toList()) }

    fun pageErrors(): JSONArray = synchronized(lock) { JSONArray().also { a -> errors.forEach { (at, m) -> a.put(JSONObject().put("s", seconds(at)).put("error", m)) } } }

    /**
     * Who or what ended the call, with what is known. [pageState] is the page's last word on itself (microphone,
     * tracks, whether WhatsApp's own call events are being read).
     */
    fun ended(pageState: JSONObject?): JSONObject = synchronized(lock) {
        val names = events.filter { it.kind == WA_EVENT }.map { it.detail.substringBefore(' ') }
        val reading = pageState?.optBoolean("wa_events") ?: false
        val v = verdict(agent, agentWhy, names, whatsapp, voiceFailed, pageSilent, endedAt > 0, reading)
        val wa = whatsapp?.let { w -> JSONObject(w.toString()).also { c -> if (w.has("callLogResult") && !w.isNull("callLogResult")) c.put("result", resultName(w.optInt("callLogResult", -1))) } }
        JSONObject().put("by", v.first).put("detail", v.second)
            .put("seconds_after_answer", if (answeredAt > 0 && endedAt > 0) Math.round((endedAt - answeredAt) / 100.0) / 10.0 else JSONObject.NULL)
            .put("answered", answeredAt > 0)
            .put("whatsapp", wa ?: JSONObject.NULL)
            .put("whatsapp_events_read", reading)
            .put("voice", JSONObject().put("failed", voiceFailed ?: JSONObject.NULL).put("closed", voiceClosed ?: JSONObject.NULL).put("end_call_asked", endAsked))
            .put("microphone", JSONObject().put("context", pageState?.optString("mic") ?: "").put("tracks", pageState?.optInt("tracks_out") ?: 0)
                .put("tracks_ended", pageState?.optInt("tracks_ended") ?: 0).put("tracks_muted", pageState?.optInt("tracks_muted") ?: 0).put("context_resumed", pageState?.optInt("mic_resumes") ?: 0))
            .put("page", JSONObject().put("answering", !pageSilent).put("hidden", pageState?.optBoolean("hidden") ?: false).put("errors", errors.size))
            .put("signs", JSONArray(signs(voiceFailed, voiceClosed, pageSilent, pageState, samples, events.any { it.kind == "page_loaded_again" })))
            .put("events_left_out", dropped)
            .put("timed_from", if (answeredAt > 0) "answer" else "voice armed")
    }

    /** Everything, for a tool result: the verdict, the timeline, the counter lines and the page's last errors. */
    fun json(pageState: JSONObject?): JSONObject =
        JSONObject().put("ended", ended(pageState)).put("timeline", timeline()).put("counters", counters()).put("page_errors", pageErrors())

    companion object {
        const val MAX_EVENTS = 160
        const val HARD_EVENTS = 220
        const val MAX_ERRORS = 20
        /** Five seconds apart: the first minute and the last four are kept whole. */
        const val MAX_SAMPLES = 60
        const val KEEP_FIRST = 12

        const val WA_CONNECTED = "wa_connected"
        const val WA_ENDED = "wa_ended"
        const val WA_EVENT = "wa_event"
        const val VOICE_FAILED = "voice_failed"
        const val VOICE_CLOSED = "voice_closed"
        const val END_CALL = "end_call_asked"
        const val PAGE_SILENT = "page_silent"
        const val PAGE_BACK = "page_back"

        const val AGENT_END_CALL = "agent_end_call"
        const val AGENT_MAX_MINUTES = "max_minutes"
        const val AGENT_VOICE_FAILED = "voice_failed"
        const val AGENT_OTHER = "agent"
        const val BY_PAGE = "page"
        const val BY_FAILURE = "whatsapp_failure"
        const val BY_REMOTE = "remote_hangup"
        const val BY_HERE = "ended_in_this_browser"
        const val BY_UNKNOWN = "unknown"
        const val NOT_ENDED = "not_ended"

        /** WhatsApp's own call events that mean the call broke (WAWebVoipWaCallEnums.CallEvent, read 10 Nov 2026). */
        val FAILURES = setOf("CallFatal", "RxTimeout", "TxTimeout", "RelayBindsFailed", "DataChannelConnectionTimeout", "MediaStreamError", "MediaStreamStartError",
            "AudioInitError", "SoundPortCreateFailed", "NoSamplingRatesForAudioRecord", "P2PTransportCreateFailed", "RelayElectionSendFailed", "MissingRelayInfo")
        /** The ones that mean the other side ended it. */
        val REMOTE = setOf("CallTerminateReceived", "RTCPByeReceived", "CallRejectReceived")

        private fun decides(kind: String, detail: String): Boolean = when (kind) {
            WA_EVENT -> detail.substringBefore(' ').let { it in FAILURES || it in REMOTE || it == "CallEnding" }
            WA_CONNECTED, WA_ENDED, VOICE_FAILED, VOICE_CLOSED, END_CALL, PAGE_SILENT, "voice_stopped", "agent_ended" -> true
            else -> false
        }

        /**
         * Things on our own side that were not as they should be, in plain words, whatever the verdict: a voice that
         * closed, a microphone that ended or stopped, a page that went away, traffic that stopped before the end.
         * Empty when nothing of ours looks wrong.
         */
        fun signs(voiceFailed: String?, voiceClosed: String?, pageSilent: Boolean, pageState: JSONObject?, samples: List<JSONObject>, reloaded: Boolean): List<String> {
            val out = ArrayList<String>()
            voiceFailed?.let { out += "The voice failed: $it" }
            voiceClosed?.let { out += "The voice's connection closed: $it" }
            if (reloaded) out += "WhatsApp Web loaded again during the call."
            if (pageSilent) out += "The page stopped answering the app."
            val ended = pageState?.optInt("tracks_ended") ?: 0
            if (ended > 0) out += "$ended microphone track(s) ended by themselves."
            val mic = pageState?.optString("mic").orEmpty()
            if (mic.isNotEmpty() && mic != "running" && mic != "none") out += "The microphone's audio context is $mic."
            val resumed = pageState?.optInt("mic_resumes") ?: 0
            if (resumed > 0) out += "The microphone's audio context stopped and was started again $resumed time(s)."
            // The last lines while the call was still up: bytes one way and none the other is a stopped stream.
            val live = samples.filter { it.optInt("connections") > 0 }
            val tail = live.takeLast(2)
            if (live.size >= 3 && tail.all { it.optLong("bytes_received") == 0L }) out += "Nothing arrived from the other side in the last ${tail.size * 5} seconds of counters."
            if (live.size >= 3 && tail.all { it.optLong("bytes_sent") == 0L }) out += "Nothing was sent to the other side in the last ${tail.size * 5} seconds of counters."
            if (live.any { it.optBoolean("hidden") }) out += "The page was hidden during the call."
            return out
        }

        /** WAWebVoipWaCallEnums.CallLogResult. 5 only says the call was connected, not who ended it. */
        fun resultName(code: Int): String = when (code) {
            0 -> "Invalid"; 1 -> "Canceled"; 2 -> "Missed"; 3 -> "Unavailable"; 4 -> "Rejected"; 5 -> "Connected"; 6 -> "AcceptedElsewhere"
            7 -> "Failed"; 8 -> "ConnectedLonely"; 9 -> "ScreenedElsewhere"; else -> "unknown $code"
        }

        /**
         * Who ended the call, and why we think so. What the agent did itself comes first (it knows); then a page that
         * went away; then WhatsApp saying the call broke; then WhatsApp saying the other side ended it; then an end
         * pressed in this browser. A call WhatsApp logged as a normal end with none of those is said to be unknown.
         */
        fun verdict(agent: String, agentWhy: String, events: List<String>, whatsapp: JSONObject?, voiceFailed: String?, pageSilent: Boolean, over: Boolean, reading: Boolean): Pair<String, String> {
            when (agent) {
                AGENT_END_CALL -> return agent to "The voice finished the conversation (end_call) and the agent hung up."
                AGENT_MAX_MINUTES -> return agent to agentWhy.ifEmpty { "The call reached max_minutes and the agent hung up." }
                AGENT_VOICE_FAILED -> return agent to (agentWhy.ifEmpty { "The voice stopped (${voiceFailed ?: "no reason given"}), so the agent hung up." })
                AGENT_OTHER -> return agent to agentWhy.ifEmpty { "The agent hung up." }
                BY_PAGE -> return BY_PAGE to agentWhy.ifEmpty { "WhatsApp Web stopped answering during the call." }
            }
            if (pageSilent && whatsapp == null) return BY_PAGE to "The page stopped answering the app (reloaded, frozen or closed)."
            val broke = events.filter { it in FAILURES }.distinct()
            val reason = whatsapp?.takeIf { it.has("callFailedReason") && !it.isNull("callFailedReason") }?.optString("callFailedReason").orEmpty()
            val failedResult = whatsapp?.optInt("callLogResult", -1) == 7
            if (broke.isNotEmpty() || reason.isNotEmpty() || failedResult)
                return BY_FAILURE to ("WhatsApp reported a failure: " + (broke + listOfNotNull(reason.takeIf { it.isNotEmpty() }?.let { "callFailedReason $it" }, "result Failed".takeIf { failedResult })).joinToString(", ") + ".")
            val remote = events.filter { it in REMOTE }.distinct()
            if (remote.isNotEmpty()) return BY_REMOTE to "WhatsApp reported that the other side ended the call (${remote.joinToString(", ")})."
            if (whatsapp?.optBoolean("userEndedCall") == true) return BY_HERE to "The call was ended from this browser (End call was pressed here, or the page left)."
            if (!over) return NOT_ENDED to "No end has been seen."
            return BY_UNKNOWN to (if (reading) "WhatsApp logged a normal end: no failure reason, not ended here, and no terminate from the other side was seen."
            else "WhatsApp logged an end with no failure reason and it was not ended here. WhatsApp's own call events could not be read in this build, so a hangup on the other side and a silent drop look the same.")
        }
    }
}

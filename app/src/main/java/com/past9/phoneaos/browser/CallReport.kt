package com.past9.phoneaos.browser

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the call tools hand back, made to fit.
 *
 * A tool result that travels to a linked computer is cut there at [LIMIT] characters (the computer's device tools
 * keep 12,000), and a call's own story (words, timeline, counters) grows with the call. The first real call lost its
 * transcript that way. So nothing a call tool returns is ever longer than [OUT_MAX], by construction:
 *
 *  - `browser_call_voice status` is a summary of at most [SUMMARY_MAX] characters: state, outcome, who ended it,
 *    signs, duration, the last few timeline events, counter totals and how long the transcript is.
 *  - The whole parts are asked for one at a time (`part`: transcript, timeline, counters, errors, ended, page). A part
 *    that does not fit comes in pages: `more` is true and `next` is the `from` of the page after.
 *  - The result of `place_whatsapp_call` has the transcript and the ended block first, then as much of the timeline
 *    and counters as fits, and says under `trimmed` what was left out and how to fetch it.
 *
 * Everything is written key by key in a fixed order, so what matters most comes first whatever cuts it later.
 */
object CallReport {
    /** Where a linked computer cuts a phone tool's result. */
    const val LIMIT = 12_000
    /** No call tool result is longer than this. */
    const val OUT_MAX = 10_000
    /** The summary that `status` gives by default. */
    const val SUMMARY_MAX = 5_500

    val PARTS = listOf("transcript", "timeline", "counters", "errors", "ended", "page")
    const val HOW = "browser_call_voice with action 'status' and part 'transcript', 'timeline', 'counters', 'errors', 'ended' or 'page'. A long part comes in pages: when 'more' is true, ask again with from = 'next'."

    /** The timeline events that explain a call: kept first when the timeline has to be cut. */
    private val KEY_EVENTS = setOf(CallDiag.WA_CONNECTED, CallDiag.WA_ENDED, CallDiag.END_CALL, CallDiag.END_REFUSED, CallDiag.VOICE_FAILED, CallDiag.VOICE_CLOSED,
        CallDiag.PAGE_SILENT, CallDiag.PAGE_BACK, CallDiag.PAGE_HIDDEN, CallDiag.PAGE_VISIBLE, "agent_ended", "voice_stopped", "voice_armed", "first_words_heard", "first_words_spoken",
        "min_seconds_reached", "page_loaded_again", "wa_not_connected")

    // ---- writing, in order ------------------------------------------------------------------------------------------

    private fun value(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(v)
        is JSONObject, is JSONArray, is Number, is Boolean -> v.toString()
        is Collection<*> -> JSONArray(v).toString()
        else -> JSONObject.quote(v.toString())
    }

    /** A JSON object with its keys in the order given, one per line. */
    fun write(pairs: List<Pair<String, Any?>>): String = pairs.joinToString(",\n", "{\n", "\n}") { (k, v) -> " " + JSONObject.quote(k) + ": " + value(v) }

    private fun clip(s: String, max: Int): String = if (s.length <= max) s else s.take((max - 1).coerceAtLeast(0)) + "…"

    private fun list(a: JSONArray?): List<Any> = if (a == null) emptyList() else (0 until a.length()).map { a.get(it) }

    /** Text too long for its place: its beginning and its end, with a line saying how much is missing between them. */
    fun middleCut(text: String, max: Int): String {
        if (text.length <= max) return text
        val keep = (max - 60).coerceAtLeast(0); val head = keep * 35 / 100; val tail = keep - head
        return text.take(head) + "\n[… ${text.length - head - tail} characters left out here …]\n" + text.takeLast(tail)
    }

    /** The last line of defence for anything a call tool returns: never longer than [OUT_MAX]. */
    fun cap(out: String): String =
        if (out.length <= OUT_MAX) out else middleCut(out, OUT_MAX - 400) + "\n[Cut to fit a tool result. The whole of it: $HOW]"

    // ---- status: the summary ------------------------------------------------------------------------------------------

    private fun pick(full: JSONObject, call: JSONObject?, key: String): Any? = full.opt(key) ?: call?.opt(key)

    private fun words(full: JSONObject, call: JSONObject?): String = full.optString("transcript").ifBlank { call?.optString("transcript").orEmpty() }

    private fun state(full: JSONObject, callRunning: Boolean, phase: String): String {
        val ended = full.optJSONObject("ended")
        return when {
            callRunning -> phase.ifBlank { "running" }
            full.optBoolean("running") -> if (ended?.optBoolean("answered") == true && ended.isNull("seconds_after_answer")) "in call" else if (ended?.optBoolean("answered") == true) "call over, voice still on" else "voice armed, no call answered yet"
            ended != null || full.has("ended") -> "ended"
            else -> "idle"
        }
    }

    private fun endedShort(e: JSONObject?, text: Int, signs: Int): JSONObject? = e?.let {
        JSONObject().put("by", it.optString("by")).put("detail", clip(it.optString("detail"), text)).put("seconds_after_answer", it.opt("seconds_after_answer") ?: JSONObject.NULL)
            .put("answered", it.optBoolean("answered"))
            .put("signs", JSONArray(list(it.optJSONArray("signs")).take(signs).map { s -> clip(s.toString(), text) }))
            .put("notes", JSONArray(list(it.optJSONArray("notes")).take(signs).map { s -> clip(s.toString(), text + 100) }))
            .also { o -> it.optJSONObject("page")?.let { p -> o.put("page", p) }; it.optJSONObject("voice")?.let { v -> o.put("voice", JSONObject(v.toString()).also { c -> for (k in listOf("failed", "closed")) if (!c.isNull(k)) c.put(k, clip(c.optString(k), text)) }) } }
    }

    private fun events(a: JSONArray?, n: Int, detail: Int): JSONArray = JSONArray(list(a).takeLast(n).map { e ->
        (e as? JSONObject)?.let { o -> JSONObject().put("s", o.opt("s")).put("from", o.optString("from")).put("event", o.optString("event")).also { c -> if (detail > 0 && o.optString("detail").isNotEmpty()) c.put("detail", clip(o.optString("detail"), detail)) } } ?: e
    })

    /**
     * What `status` says by default. [full] is [CallBridge.status], [call] the last result of place_whatsapp_call (or
     * null), [callRunning] and [phase] a call that tool is still running.
     */
    fun summary(full: JSONObject, call: JSONObject?, callRunning: Boolean = false, phase: String = ""): String {
        val ended = pick(full, call, "ended") as? JSONObject
        val timeline = pick(full, call, "timeline") as? JSONArray
        val errors = pick(full, call, "page_errors") as? JSONArray
        val text = words(full, call)
        // Tighter and tighter until it fits; the last one is a handful of numbers.
        for ((n, detail, tail, textMax) in listOf(listOf(6, 140, 400, 300), listOf(4, 80, 200, 200), listOf(3, 0, 0, 120), listOf(0, 0, 0, 60))) {
            val c = if (callRunning) JSONObject().put("running", true).put("phase", phase) else call?.let { r ->
                JSONObject().put("running", false).put("outcome", r.optString("outcome")).put("reason", clip(r.optString("reason"), textMax)).put("contact", clip(r.optString("contact"), 80)).put("chat", clip(r.optString("chat"), 80))
                    .put("rang_seconds", r.optLong("rang_seconds")).put("duration_seconds", r.optLong("duration_seconds")).put("ended_by", r.optString("ended_by")).put("min_seconds", r.optInt("min_seconds"))
                    .put("saved_to_chat", r.optBoolean("saved_to_chat")).put("calling_offered", r.opt("calling_offered") ?: JSONObject.NULL).put("test_account", clip(r.optString("test_account"), textMax)).put("at", r.optLong("at"))
            }
            val out = write(listOfNotNull(
                "state" to state(full, callRunning, phase),
                "call" to (c ?: JSONObject.NULL),
                ended?.let { "ended" to endedShort(it, textMax, if (n == 0) 2 else 6) },
                "duration_seconds" to (ended?.opt("seconds_after_answer")?.takeIf { it != JSONObject.NULL } ?: call?.opt("duration_seconds") ?: JSONObject.NULL),
                "voice" to JSONObject().put("running", full.optBoolean("running")).put("page_ready", full.optBoolean("page_ready")).put("armed", full.optBoolean("armed")).put("ready", full.optBoolean("voice_ready"))
                    .put("end_requested", full.optBoolean("end_requested")).put("failed", full.opt("failed")?.let { if (it == JSONObject.NULL) it else clip(it.toString(), textMax) } ?: JSONObject.NULL)
                    .put("min_seconds", full.optInt("min_seconds")).put("min_seconds_left", full.optInt("min_seconds_left")).put("end_call_refused", full.optInt("end_call_refused"))
                    .put("heard_frames", full.optInt("heard_frames")).put("sent_frames", full.optInt("sent_frames")).put("voice_chunks_played", full.optInt("voice_chunks_played")).put("heard_peak", full.optDouble("heard_peak", 0.0)),
                "transcript_chars" to text.length,
                "transcript_lines" to (if (text.isEmpty()) 0 else text.count { it == '\n' } + 1),
                if (tail > 0 && text.isNotEmpty()) "transcript_last" to text.takeLast(tail) else null,
                "timeline_events" to (timeline?.length() ?: 0),
                if (n > 0 && timeline != null) "timeline_last" to events(timeline, n, detail) else null,
                "counters_total" to (full.opt("totals") ?: JSONObject().put("lines", (pick(full, call, "counters") as? JSONArray)?.length() ?: 0)),
                "page_errors" to (errors?.length() ?: 0),
                "more" to "This is a summary. The whole of it: $HOW",
            ))
            if (out.length <= SUMMARY_MAX) return out
        }
        return write(listOf("state" to state(full, callRunning, phase), "transcript_chars" to text.length, "timeline_events" to (timeline?.length() ?: 0), "more" to HOW))
    }

    // ---- status: one part, in pages -----------------------------------------------------------------------------------

    private fun pages(part: String, items: List<Any>, from: Int): String {
        val start = from.coerceIn(0, items.size)
        var end = start
        var out = ""
        fun make(to: Int) = write(listOf("part" to part, "from" to start, "to" to to, "total" to items.size, "more" to (to < items.size), "next" to (if (to < items.size) to else JSONObject.NULL),
            "items" to JSONArray(items.subList(start, to))))
        out = make(start)
        while (end < items.size) {
            val next = make(end + 1)
            if (next.length > OUT_MAX) break
            out = next; end++
        }
        // One item too long for a page by itself (it cannot be, each is capped where it is written): hand it over cut, and move on.
        if (end == start && start < items.size) return write(listOf("part" to part, "from" to start, "to" to start + 1, "total" to items.size, "more" to (start + 1 < items.size),
            "next" to (if (start + 1 < items.size) start + 1 else JSONObject.NULL), "items" to JSONArray(listOf(clip(items[start].toString(), OUT_MAX - 600)))))
        return out
    }

    private fun textPages(text: String, from: Int): String {
        val start = from.coerceIn(0, text.length)
        var size = 8_500
        while (true) {
            val to = (start + size).coerceAtMost(text.length)
            val out = write(listOf("part" to "transcript", "from" to start, "to" to to, "total_chars" to text.length, "more" to (to < text.length), "next" to (if (to < text.length) to else JSONObject.NULL),
                "text" to text.substring(start, to)))
            if (out.length <= OUT_MAX || size <= 200) return if (out.length <= OUT_MAX) out else write(listOf("part" to "transcript", "from" to start, "to" to start, "total_chars" to text.length, "more" to true, "next" to start, "text" to ""))
            size = size * 85 / 100
        }
    }

    /** One whole part of the call's story, or one page of it starting at [from] (a character for the transcript, an item for the lists). */
    fun part(name: String, from: Int, full: JSONObject, call: JSONObject?): String = when (name.trim().lowercase()) {
        "transcript" -> textPages(words(full, call), from)
        "timeline" -> pages("timeline", list(pick(full, call, "timeline") as? JSONArray), from)
        "counters" -> pages("counters", list(pick(full, call, "counters") as? JSONArray), from)
        "errors", "page_errors" -> pages("errors", list(pick(full, call, "page_errors") as? JSONArray), from)
        "ended" -> ((pick(full, call, "ended") as? JSONObject)?.let { e -> write(listOf("part" to "ended", "ended" to e)).takeIf { it.length <= OUT_MAX } ?: write(listOf("part" to "ended", "ended" to endedShort(e, 300, 6), "cut" to true)) }
            ?: write(listOf("part" to "ended", "ended" to JSONObject.NULL, "why" to "No call voice has run in this browser yet.")))
        "page" -> cap(write(listOf("part" to "page", "page" to (full.opt("page") ?: JSONObject.NULL), "page_log" to JSONArray(list(full.optJSONArray("page_log")).takeLast(15).map { clip(it.toString(), 300) }))))
        else -> "Unknown part '$name'. Parts: ${PARTS.joinToString(", ")}. Leave part out for the summary."
    }

    /** `status`, as the tool answers it: the summary, or the part asked for. Never longer than [OUT_MAX]. */
    fun status(full: JSONObject, call: JSONObject?, callRunning: Boolean, phase: String, part: String = "", from: Int = 0): String =
        cap(if (part.isBlank() || part.trim().lowercase() == "summary") summary(full, call, callRunning, phase) else part(part, from, full, call))

    // ---- the result of place_whatsapp_call ----------------------------------------------------------------------------

    /** The timeline cut to [n] events: the ones that explain the call first, then the newest of the rest, in their own order. */
    private fun keyEvents(all: List<Any>, n: Int, detail: Int): JSONArray {
        if (n <= 0) return JSONArray()
        val idx = all.indices.toList()
        fun key(i: Int) = (all[i] as? JSONObject)?.let { o -> o.optString("event") in KEY_EVENTS || (o.optString("event") == CallDiag.WA_EVENT && o.optString("detail").substringBefore(' ').let { it in CallDiag.FAILURES || it in CallDiag.REMOTE || it == "CallEnding" }) } ?: false
        val keys = idx.filter(::key).takeLast(n)
        val rest = idx.filterNot { it in keys }.takeLast(n - keys.size)
        return JSONArray((keys + rest).sorted().map { i -> (all[i] as? JSONObject)?.let { o -> if (o.optString("detail").length > detail) JSONObject(o.toString()).also { c -> if (detail <= 0) c.remove("detail") else c.put("detail", clip(o.optString("detail"), detail)) } else o } ?: all[i] })
    }

    /**
     * The result of a whole call ([WhatsAppCall.Result.json]) as the tool returns it: the outcome, the transcript and
     * the ended block first and whole when they fit; the timeline, counters and page errors cut to what fits; and
     * `trimmed` saying what was left out and how to fetch it. Never longer than [OUT_MAX].
     */
    fun result(r: JSONObject): String {
        val text = r.optString("transcript")
        val ended = r.optJSONObject("ended")
        val timeline = list(r.optJSONArray("timeline")); val counters = list(r.optJSONArray("counters")); val errors = list(r.optJSONArray("page_errors"))
        val whatsapp = r.opt("whatsapp")?.let { w -> if (w.toString().length <= 1_200) w else (w as? JSONObject)?.optJSONObject("ended") ?: JSONObject.NULL } ?: JSONObject.NULL
        // transcript characters, timeline events, their detail, counter lines, page errors: less and less until it fits.
        for ((tMax, ev, detail, co, er) in listOf(listOf(5_000, 24, 240, 8, 5), listOf(4_000, 14, 140, 4, 3), listOf(3_000, 8, 100, 2, 2), listOf(2_000, 4, 60, 0, 1), listOf(1_000, 0, 0, 0, 0))) {
            val cutCounters = if (counters.size <= co) counters else counters.take(co / 4) + counters.takeLast(co - co / 4)
            val endedOut: Any = ended?.let { if (it.toString().length <= 3_000 && tMax > 1_000) it else endedShort(it, 200, 4) } ?: JSONObject.NULL
            val trimmed = JSONObject()
            if (text.length > tMax) trimmed.put("transcript", "the beginning and the end of ${text.length} characters")
            if (timeline.size > ev) trimmed.put("timeline", "${minOf(ev, timeline.size)} of ${timeline.size} events")
            if (counters.size > co) trimmed.put("counters", "${cutCounters.size} of ${counters.size} lines")
            if (errors.size > er) trimmed.put("page_errors", "${minOf(er, errors.size)} of ${errors.size}")
            if (endedOut !== ended && ended != null) trimmed.put("ended", "shortened")
            if (trimmed.length() > 0) trimmed.put("how", "The whole of each: $HOW")
            val out = write(listOfNotNull(
                "outcome" to r.optString("outcome"), "reason" to clip(r.optString("reason"), 400), "contact" to clip(r.optString("contact"), 80), "chat" to clip(r.optString("chat"), 80),
                "rang_seconds" to r.optLong("rang_seconds"), "duration_seconds" to r.optLong("duration_seconds"), "ended_by" to r.optString("ended_by"), "min_seconds" to r.optInt("min_seconds"),
                "transcript" to middleCut(text, tMax), "transcript_chars" to text.length, "saved_to_chat" to r.optBoolean("saved_to_chat"),
                "ended" to endedOut,
                "calling_offered" to (r.opt("calling_offered") ?: JSONObject.NULL), "test_account" to clip(r.optString("test_account"), 300), "whatsapp" to whatsapp,
                if (trimmed.length() > 0) "trimmed" to trimmed else null,
                "timeline" to keyEvents(timeline, ev, detail), "counters" to JSONArray(cutCounters), "page_errors" to JSONArray(errors.takeLast(er)),
            ))
            if (out.length <= OUT_MAX) return out
        }
        return cap(write(listOf("outcome" to r.optString("outcome"), "reason" to clip(r.optString("reason"), 400), "duration_seconds" to r.optLong("duration_seconds"), "ended_by" to r.optString("ended_by"),
            "transcript" to middleCut(text, 1_000), "transcript_chars" to text.length, "trimmed" to JSONObject().put("everything_else", "left out").put("how", "The whole of each: $HOW"))))
    }
}

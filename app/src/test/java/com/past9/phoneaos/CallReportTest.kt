package com.past9.phoneaos

import com.past9.phoneaos.browser.CallDiag
import com.past9.phoneaos.browser.CallReport
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No call tool answer is ever longer than a tool result can carry. A linked computer cuts a phone tool's result at
 * 12,000 characters: the first real call lost its transcript that way (it came last in a 20,000 character status).
 */
class CallReportTest {
    /** Where a top-level key starts in an answer (each is on its own line). */
    private fun at(out: String, key: String) = out.indexOf("\n \"$key\": ")

    private fun transcript(lines: Int) = (1..lines).joinToString("\n") { i -> (if (i % 2 == 0) "Voice: " else "Them: ") + "line $i says \"something\" about the weekend, ngiyabonga kakhulu, and then a little more to make it long" }

    /** A half-hour call: every list as full as [CallDiag] lets it get, every detail as long as it may be. */
    private fun big(words: String = transcript(700)): JSONObject {
        val timeline = JSONArray(); for (i in 0 until CallDiag.HARD_EVENTS) timeline.put(JSONObject().put("s", i * 8.1).put("from", "whatsapp").put("event", if (i == 3) CallDiag.WA_CONNECTED else if (i == 200) CallDiag.END_REFUSED else CallDiag.WA_EVENT).put("detail", "CallStateChanged $i " + "x".repeat(580)))
        val counters = JSONArray(); for (i in 0 until CallDiag.MAX_SAMPLES) counters.put(JSONObject().put("s", i * 5.0).put("voice_chunks", 12).put("heard_frames", 50).put("frames_to_voice", 50).put("page_frames", 117).put("bytes_sent", 12_345).put("bytes_received", 23_456)
            .put("bytes_sent_total", 1_234_567).put("bytes_received_total", 2_345_678).put("connections", 9).put("ice", "connected").put("mic", "running").put("hidden", i % 2 == 0).put("hidden_seconds", 5.0).put("errors", 3))
        val errors = JSONArray(); for (i in 0 until CallDiag.MAX_ERRORS) errors.put(JSONObject().put("s", i * 1.5).put("error", "voip: [AV] No audio device selected. " + "y".repeat(260)))
        val ended = JSONObject().put("by", CallDiag.AGENT_END_CALL).put("detail", "The voice finished the conversation (end_call) and the agent hung up.").put("seconds_after_answer", 1790.2).put("answered", true)
            .put("whatsapp", JSONObject().put("id", "00A101C4004CF24583E645B2BA1C038A").put("callLogResult", 5).put("result", "Connected")).put("whatsapp_events_read", true)
            .put("voice", JSONObject().put("failed", JSONObject.NULL).put("closed", JSONObject.NULL).put("end_call_asked", true).put("end_call_refused", 1).put("min_seconds", 65))
            .put("microphone", JSONObject().put("context", "running").put("tracks", 3).put("tracks_ended", 0).put("tracks_muted", 0).put("context_resumed", 0))
            .put("page", JSONObject().put("answering", true).put("hidden_now", false).put("was_hidden", true).put("hidden_seconds", 44.0).put("errors", 20))
            .put("signs", JSONArray()).put("notes", JSONArray().put("The page was hidden for about 44 seconds while the call voice ran. Sound kept flowing, so this is not a fault. It is visible again now."))
            .put("events_left_out", 412).put("timed_from", "answer")
        return JSONObject().put("running", false).put("page_ready", true).put("armed", false).put("voice_ready", false).put("end_requested", true).put("failed", JSONObject.NULL)
            .put("min_seconds", 65).put("min_seconds_left", 0).put("end_call_refused", 1).put("heard_frames", 17_900).put("sent_frames", 17_900).put("heard_peak", 0.982).put("voice_chunks_played", 2_210)
            .put("page", JSONObject().put("t", "state").put("armed", false).put("mic", "running").put("hidden", false))
            .put("page_log", JSONArray((1..60).map { "log line $it " + "z".repeat(280) }))
            .put("transcript", words).put("ended", ended).put("timeline", timeline).put("counters", counters).put("page_errors", errors)
            .put("totals", JSONObject().put("lines", 60).put("voice_chunks", 2_210).put("heard_frames", 17_900).put("frames_to_voice", 17_900).put("page_frames", 41_000).put("bytes_sent", 1_234_567).put("bytes_received", 2_345_678).put("page_errors", 20))
    }

    private fun result(full: JSONObject = big()) = JSONObject().put("outcome", "answered").put("reason", "The conversation was finished.").put("contact", "Ayanda").put("chat", "54696509239358@lid")
        .put("rang_seconds", 33).put("duration_seconds", 1790).put("ended_by", "agent").put("min_seconds", 65).put("transcript", full.getString("transcript")).put("saved_to_chat", true)
        .put("calling_offered", true).put("test_account", "Calling was switched on in the page for this call, for the marked test account. A reload undoes it.")
        .put("whatsapp", JSONObject().put("pending", false).put("connected", false).put("ended", JSONObject().put("id", "00A1").put("callLogResult", 5)))
        .put("ended", full.get("ended")).put("timeline", full.get("timeline")).put("counters", full.get("counters")).put("page_errors", full.get("page_errors")).put("at", 1_762_777_000_000)

    @Test fun theOldWholeStatusOfALongCallWouldNotHaveFit() {
        // What 0.15.35 returned: everything at once. This is the fault being fixed, kept here so the test means something.
        assertTrue(big().put("call", result()).toString(2).length > CallReport.LIMIT * 5)
    }

    @Test fun theLimitsLeaveRoomUnderTheCut() {
        assertTrue(CallReport.OUT_MAX <= CallReport.LIMIT - 1_500); assertTrue(CallReport.SUMMARY_MAX < 6_000)
    }

    @Test fun statusIsASmallSummaryWhateverTheCallWasLike() {
        val out = CallReport.status(big(), result(), callRunning = false, phase = "")
        assertTrue("summary is ${out.length} characters", out.length <= CallReport.SUMMARY_MAX)
        val o = JSONObject(out)
        assertEquals("ended", o.getString("state"))
        val call = o.getJSONObject("call"); assertEquals("answered", call.getString("outcome")); assertEquals(1790, call.getInt("duration_seconds")); assertEquals("Ayanda", call.getString("contact"))
        val ended = o.getJSONObject("ended"); assertEquals(CallDiag.AGENT_END_CALL, ended.getString("by")); assertTrue(ended.has("detail")); assertTrue(ended.has("signs")); assertEquals(1, ended.getJSONArray("notes").length())
        assertFalse(ended.getJSONObject("page").getBoolean("hidden_now")); assertTrue(ended.getJSONObject("page").getBoolean("was_hidden"))
        assertEquals(1790.2, o.getDouble("duration_seconds"), 0.0)
        assertEquals(big().getString("transcript").length, o.getInt("transcript_chars")); assertEquals(700, o.getInt("transcript_lines"))
        assertEquals(CallDiag.HARD_EVENTS, o.getInt("timeline_events")); assertTrue(o.getJSONArray("timeline_last").length() in 1..6)
        assertEquals(1_234_567L, o.getJSONObject("counters_total").getLong("bytes_sent")); assertEquals(20, o.getInt("page_errors"))
        assertEquals(65, o.getJSONObject("voice").getInt("min_seconds")); assertEquals(1, o.getJSONObject("voice").getInt("end_call_refused"))
        assertTrue(o.getString("more").contains("part 'transcript'"))
        // The state comes first, the pointer to the parts last.
        assertTrue(at(out, "state") in 0 until at(out, "call")); assertTrue(at(out, "ended") in 1 until at(out, "timeline_last")); assertTrue(at(out, "more") > at(out, "timeline_last"))
    }

    @Test fun whileACallRunsTheSummarySaysItsPhase() {
        val live = big().put("running", true); live.getJSONObject("ended").put("seconds_after_answer", JSONObject.NULL)
        val o = JSONObject(CallReport.status(live, null, callRunning = true, phase = "answered"))
        assertEquals("answered", o.getString("state")); assertTrue(o.getJSONObject("call").getBoolean("running")); assertEquals("answered", o.getJSONObject("call").getString("phase"))
        // A voice started by hand with nothing yet: small, and it says so.
        val idle = JSONObject(CallReport.status(JSONObject().put("running", false).put("page_ready", true).put("transcript", ""), null, false, ""))
        assertEquals("idle", idle.getString("state")); assertEquals(0, idle.getInt("transcript_chars")); assertTrue(idle.isNull("call"))
    }

    @Test fun theWholeTranscriptComesBackInPagesThatEachFit() {
        val full = big(); val words = full.getString("transcript")
        val got = StringBuilder(); var from = 0; var pages = 0
        while (true) {
            val out = CallReport.status(full, null, false, "", "transcript", from)
            assertTrue("page is ${out.length} characters", out.length <= CallReport.OUT_MAX)
            val o = JSONObject(out); pages++
            assertEquals(from, o.getInt("from")); assertEquals(words.length, o.getInt("total_chars"))
            got.append(o.getString("text"))
            assertTrue("'more' and 'next' come before the text, so a cut never hides them", out.indexOf("\"more\"") < out.indexOf("\"text\""))
            if (!o.getBoolean("more")) { assertTrue(o.isNull("next")); break }
            from = o.getInt("next"); assertTrue(pages < 100)
        }
        assertEquals(words, got.toString()); assertTrue("a long transcript needs more than one page", pages > 3)
        // After the call the bridge's own words are gone only when a new call starts: then the last result's are used.
        val later = JSONObject(CallReport.part("transcript", 0, JSONObject().put("transcript", ""), JSONObject().put("transcript", "Them: Hello")))
        assertEquals("Them: Hello", later.getString("text")); assertFalse(later.getBoolean("more"))
    }

    @Test fun theTimelineCountersAndErrorsComeBackWholeInPages() {
        val full = big()
        for ((part, key) in listOf("timeline" to "timeline", "counters" to "counters", "errors" to "page_errors")) {
            val all = full.getJSONArray(key); val got = ArrayList<String>(); var from = 0; var pages = 0
            while (true) {
                val out = CallReport.status(full, null, false, "", part, from)
                assertTrue("$part page is ${out.length} characters", out.length <= CallReport.OUT_MAX)
                val o = JSONObject(out); pages++
                assertEquals(all.length(), o.getInt("total")); assertEquals(part, o.getString("part"))
                val items = o.getJSONArray("items"); assertTrue(items.length() > 0)
                for (i in 0 until items.length()) got += items.getJSONObject(i).toString()
                assertEquals(from + items.length(), o.getInt("to"))
                if (!o.getBoolean("more")) break
                from = o.getInt("next"); assertTrue(pages < 100)
            }
            assertEquals(part, (0 until all.length()).map { all.getJSONObject(it).toString() }, got)
        }
        // A page that starts past the end is empty and says there is no more.
        val past = JSONObject(CallReport.part("timeline", 9_999, full, null)); assertEquals(0, past.getJSONArray("items").length()); assertFalse(past.getBoolean("more"))
    }

    @Test fun theEndedBlockAndThePagePartFitToo() {
        val full = big()
        val ended = CallReport.status(full, null, false, "", "ended"); assertTrue(ended.length <= CallReport.OUT_MAX)
        assertEquals(CallDiag.AGENT_END_CALL, JSONObject(ended).getJSONObject("ended").getString("by")); assertTrue(JSONObject(ended).getJSONObject("ended").has("microphone"))
        val page = CallReport.status(full, null, false, "", "page"); assertTrue(page.length <= CallReport.OUT_MAX); assertEquals(15, JSONObject(page).getJSONArray("page_log").length())
        assertTrue(CallReport.status(full, null, false, "", "nonsense").startsWith("Unknown part"))
        assertTrue(JSONObject(CallReport.part("ended", 0, JSONObject(), null)).isNull("ended"))
    }

    @Test fun theResultOfAWholeCallFitsWithTheWordsAndTheEndFirst() {
        val out = CallReport.result(result())
        assertTrue("result is ${out.length} characters", out.length <= CallReport.OUT_MAX)
        val o = JSONObject(out)
        assertEquals("answered", o.getString("outcome")); assertEquals(65, o.getInt("min_seconds"))
        // The transcript and the ended block come before the lists that grow with the call.
        val t = at(out, "transcript"); val e = at(out, "ended"); val tl = at(out, "timeline"); val c = at(out, "counters")
        assertTrue(t in 1 until e); assertTrue(e < tl); assertTrue(tl < c); assertTrue(at(out, "trimmed") in e until tl)
        // Its beginning and its end are there, and it says how much is missing and how to get it.
        val words = result().getString("transcript")
        assertTrue(o.getString("transcript").startsWith(words.take(200))); assertTrue(o.getString("transcript").endsWith(words.takeLast(200))); assertTrue(o.getString("transcript").contains("characters left out here"))
        assertEquals(words.length, o.getInt("transcript_chars"))
        assertEquals(CallDiag.AGENT_END_CALL, o.getJSONObject("ended").getString("by"))
        val trimmed = o.getJSONObject("trimmed")
        for (k in listOf("transcript", "timeline", "counters", "page_errors", "how")) assertTrue(k, trimmed.has(k))
        assertTrue(trimmed.getString("timeline").endsWith("of ${CallDiag.HARD_EVENTS} events")); assertTrue(trimmed.getString("how").contains("browser_call_voice"))
        // What is kept of the timeline is what explains the call: the answer and the refused end_call are in it.
        val kept = (0 until o.getJSONArray("timeline").length()).map { o.getJSONArray("timeline").getJSONObject(it).getString("event") }
        assertTrue(kept.toString(), CallDiag.WA_CONNECTED in kept && CallDiag.END_REFUSED in kept)
    }

    @Test fun aShortCallIsHandedBackWholeWithNothingTrimmed() {
        val small = JSONObject().put("outcome", "answered").put("reason", "The conversation was finished.").put("contact", "Sam").put("chat", "1@lid").put("rang_seconds", 8).put("duration_seconds", 48)
            .put("ended_by", "agent").put("min_seconds", 0).put("transcript", "Them: Hello\nVoice: Hi Sam").put("saved_to_chat", true).put("calling_offered", true).put("test_account", "")
            .put("whatsapp", JSONObject().put("connected", false))
            .put("ended", JSONObject().put("by", CallDiag.AGENT_END_CALL).put("signs", JSONArray()).put("notes", JSONArray()))
            .put("timeline", JSONArray().put(JSONObject().put("s", 0.0).put("from", "whatsapp").put("event", CallDiag.WA_CONNECTED)).put(JSONObject().put("s", 42.4).put("from", "voice").put("event", CallDiag.END_CALL).put("detail", "done")))
            .put("counters", JSONArray().put(JSONObject().put("s", 5.0).put("bytes_sent", 9_088))).put("page_errors", JSONArray())
        val o = JSONObject(CallReport.result(small))
        assertFalse(o.has("trimmed")); assertEquals("Them: Hello\nVoice: Hi Sam", o.getString("transcript")); assertEquals(2, o.getJSONArray("timeline").length()); assertEquals(1, o.getJSONArray("counters").length())
        assertEquals("done", o.getJSONArray("timeline").getJSONObject(1).getString("detail"))
        // A call that failed before it started has none of the story: still a whole, small answer.
        val failed = JSONObject(CallReport.result(JSONObject().put("outcome", "failed").put("reason", "The call voice is off.").put("whatsapp", JSONObject.NULL).put("calling_offered", JSONObject.NULL)))
        assertEquals("failed", failed.getString("outcome")); assertTrue(failed.isNull("ended")); assertEquals(0, failed.getJSONArray("timeline").length())
    }

    @Test fun anythingElseACallToolSaysIsCutToFitAsWell() {
        // browser_call_voice stop hands back the words it saved: a long call's must not overflow either.
        val said = "Stopped. The call's words were saved to the chat:\n" + transcript(700)
        val out = CallReport.cap(said)
        assertTrue(out.length <= CallReport.OUT_MAX); assertTrue(out.startsWith("Stopped.")); assertTrue(out.contains("part 'transcript'"))
        assertEquals("short answers are left alone", "Stopped.", CallReport.cap("Stopped."))
    }

    @Test fun textThatBloatsWhenWrittenStillFits() {
        // Quotes, backslashes and line breaks double in size when written as JSON: the pages get smaller, never too long.
        val nasty = "\"\\\n".repeat(20_000)
        var from = 0; val got = StringBuilder(); var pages = 0
        while (true) {
            val out = CallReport.part("transcript", from, JSONObject().put("transcript", nasty), null)
            assertTrue(out.length <= CallReport.OUT_MAX)
            val o = JSONObject(out); got.append(o.getString("text")); pages++
            if (!o.getBoolean("more")) break
            assertTrue(o.getInt("next") > from); from = o.getInt("next"); assertTrue(pages < 200)
        }
        assertEquals(nasty, got.toString())
        assertTrue(CallReport.result(result(big(nasty))).length <= CallReport.OUT_MAX)
        assertTrue(CallReport.status(big(nasty), result(big(nasty)), false, "").length <= CallReport.SUMMARY_MAX)
    }
}

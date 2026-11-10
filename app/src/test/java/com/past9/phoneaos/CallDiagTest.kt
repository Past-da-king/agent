package com.past9.phoneaos

import com.past9.phoneaos.browser.CallDiag
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The call's own story: who or what ended it, the timeline, the counter lines and the page's last errors. */
class CallDiagTest {
    private var clock = 100_000L
    private fun diag() = CallDiag { clock }.also { it.reset() }
    private fun verdict(agent: String = "", why: String = "", events: List<String> = emptyList(), whatsapp: JSONObject? = null, voiceFailed: String? = null,
                        pageSilent: Boolean = false, over: Boolean = true, reading: Boolean = true) = CallDiag.verdict(agent, why, events, whatsapp, voiceFailed, pageSilent, over, reading)
    private val normalEnd get() = JSONObject().put("id", "C1").put("callOutcome", "Completed").put("callDuration", 20).put("wasEverConnected", true).put("userEndedCall", false)
        .put("callFailedReason", JSONObject.NULL).put("callLogResult", 5)

    // ---- the verdict ----

    @Test fun whatTheAgentDidItselfComesFirst() {
        // Even when WhatsApp also says the other side ended it: the agent knows what it did.
        assertEquals(CallDiag.AGENT_END_CALL, verdict(CallDiag.AGENT_END_CALL, events = listOf("CallTerminateReceived")).first)
        assertEquals(CallDiag.AGENT_MAX_MINUTES, verdict(CallDiag.AGENT_MAX_MINUTES, "The call reached its limit of 5 minutes.").first)
        assertTrue(verdict(CallDiag.AGENT_MAX_MINUTES, "The call reached its limit of 5 minutes.").second.contains("5 minutes"))
        val v = verdict(CallDiag.AGENT_VOICE_FAILED, voiceFailed = "token expired")
        assertEquals(CallDiag.AGENT_VOICE_FAILED, v.first); assertTrue(v.second, v.second.contains("token expired"))
        assertEquals(CallDiag.AGENT_OTHER, verdict(CallDiag.AGENT_OTHER, "Nobody answered in 60 seconds.").first)
        assertEquals(CallDiag.BY_PAGE, verdict(CallDiag.BY_PAGE).first)
    }

    @Test fun aCallWhatsAppSaysBrokeIsAFailureWithItsReasons() {
        val v = verdict(events = listOf("CallStateChanged", "RxTimeout", "RxTimeout", "CallTerminateReceived"), whatsapp = normalEnd)
        assertEquals(CallDiag.BY_FAILURE, v.first); assertTrue(v.second, v.second.contains("RxTimeout")); assertFalse(v.second.contains("RxTimeout, RxTimeout"))
        val reason = verdict(whatsapp = normalEnd.put("callFailedReason", "relay_binds_failed"))
        assertEquals(CallDiag.BY_FAILURE, reason.first); assertTrue(reason.second.contains("relay_binds_failed"))
        assertEquals(CallDiag.BY_FAILURE, verdict(whatsapp = normalEnd.put("callLogResult", 7)).first)
        for (e in listOf("CallFatal", "TxTimeout", "RelayBindsFailed", "DataChannelConnectionTimeout", "AudioInitError")) assertEquals(e, CallDiag.BY_FAILURE, verdict(events = listOf(e)).first)
    }

    @Test fun aTerminateFromTheOtherSideIsARemoteHangup() {
        val v = verdict(events = listOf("CallStateChanged", "CallTerminateReceived", "CallEnding"), whatsapp = normalEnd)
        assertEquals(CallDiag.BY_REMOTE, v.first); assertTrue(v.second.contains("CallTerminateReceived"))
        assertEquals(CallDiag.BY_REMOTE, verdict(events = listOf("RTCPByeReceived")).first)
    }

    @Test fun anEndPressedInThisBrowserIsNotBlamedOnTheOtherSide() {
        assertEquals(CallDiag.BY_HERE, verdict(events = listOf("CallEnding"), whatsapp = normalEnd.put("userEndedCall", true)).first)
    }

    @Test fun aNormalEndWithNoSignEitherWayIsSaidToBeUnknown() {
        // The 10 Nov calls: Completed, result 5, not ended here, no failure reason. That alone does not say who ended it.
        val read = verdict(events = listOf("CallStateChanged", "CallEnding"), whatsapp = normalEnd)
        assertEquals(CallDiag.BY_UNKNOWN, read.first); assertTrue(read.second.contains("no terminate"))
        val blind = verdict(whatsapp = normalEnd, reading = false)
        assertEquals(CallDiag.BY_UNKNOWN, blind.first); assertTrue(blind.second, blind.second.contains("could not be read"))
    }

    @Test fun aPageThatWentAwayAndACallStillGoingAreToldApart() {
        assertEquals(CallDiag.BY_PAGE, verdict(pageSilent = true, over = false).first)
        // The page went quiet after WhatsApp had already written the end down: the end is what counts.
        assertEquals(CallDiag.BY_REMOTE, verdict(events = listOf("CallTerminateReceived"), whatsapp = normalEnd, pageSilent = true).first)
        assertEquals(CallDiag.NOT_ENDED, verdict(over = false).first)
    }

    @Test fun whatsAppsResultNumbersHaveNames() {
        assertEquals("Connected", CallDiag.resultName(5)); assertEquals("Rejected", CallDiag.resultName(4)); assertEquals("Failed", CallDiag.resultName(7))
        assertEquals("unknown 42", CallDiag.resultName(42))
    }

    // ---- the story ----

    @Test fun nothingIsKeptBeforeACallVoiceStarts() {
        val d = CallDiag { clock }
        d.add("page", "wa_connected"); d.error("x"); d.sample(JSONObject(), 1, 1)
        assertFalse(d.started); assertEquals(0, d.timeline().length()); assertEquals(0, d.counters().length()); assertEquals(0, d.pageErrors().length())
    }

    @Test fun theTimelineCountsSecondsFromTheAnswer() {
        val d = diag()
        d.add("app", "voice_armed")
        clock += 12_000; d.add("whatsapp", CallDiag.WA_CONNECTED)
        // The page stamps its own events: one that arrives late is still put where it happened.
        d.add("whatsapp", CallDiag.WA_EVENT, "CallTerminateReceived", at = clock + 20_300)
        d.add("voice", "first_words_spoken", at = clock + 2_000)
        clock += 20_400; d.add("whatsapp", CallDiag.WA_ENDED, normalEnd.toString())
        val t = d.timeline()
        assertEquals(listOf("voice_armed", "wa_connected", "first_words_spoken", "wa_event", "wa_ended"), (0 until t.length()).map { t.getJSONObject(it).getString("event") })
        assertEquals(-12.0, t.getJSONObject(0).getDouble("s"), 0.0); assertEquals(0.0, t.getJSONObject(1).getDouble("s"), 0.0)
        assertEquals(2.0, t.getJSONObject(2).getDouble("s"), 0.0); assertEquals(20.3, t.getJSONObject(3).getDouble("s"), 0.0)
        assertEquals("CallTerminateReceived", t.getJSONObject(3).getString("detail")); assertFalse(t.getJSONObject(0).has("detail"))
        val e = d.ended(JSONObject().put("wa_events", true).put("mic", "running").put("tracks_out", 3).put("tracks_ended", 0))
        assertEquals(CallDiag.BY_REMOTE, e.getString("by")); assertEquals(20.4, e.getDouble("seconds_after_answer"), 0.0); assertEquals("answer", e.getString("timed_from"))
        assertEquals("Connected", e.getJSONObject("whatsapp").getString("result")); assertEquals("Completed", e.getJSONObject("whatsapp").getString("callOutcome"))
        assertEquals("running", e.getJSONObject("microphone").getString("context")); assertEquals(3, e.getJSONObject("microphone").getInt("tracks"))
        assertTrue(e.getBoolean("whatsapp_events_read")); assertTrue(e.getJSONObject("page").getBoolean("answering"))
        assertTrue(d.answered); assertTrue(d.over)
    }

    @Test fun aCallNobodyAnsweredIsTimedFromTheVoiceBeingArmed() {
        val d = diag(); clock += 7_000; d.add("app", "voice_stopped")
        assertEquals(7.0, d.timeline().getJSONObject(0).getDouble("s"), 0.0)
        val e = d.ended(null)
        assertEquals("voice armed", e.getString("timed_from")); assertTrue(e.isNull("seconds_after_answer")); assertFalse(e.getBoolean("answered")); assertEquals(CallDiag.NOT_ENDED, e.getString("by"))
    }

    @Test fun theVoiceAndTheAgentAreInTheVerdictsDetails() {
        val d = diag(); d.add("whatsapp", CallDiag.WA_CONNECTED)
        d.add("voice", CallDiag.END_CALL, "done"); d.add("voice", CallDiag.VOICE_CLOSED, "code 1001 going away")
        clock += 30_000; d.ended(CallDiag.AGENT_END_CALL, "The conversation was finished.", normalEnd)
        val e = d.ended(null)
        assertEquals(CallDiag.AGENT_END_CALL, e.getString("by")); assertEquals(30.0, e.getDouble("seconds_after_answer"), 0.0)
        assertTrue(e.getJSONObject("voice").getBoolean("end_call_asked")); assertEquals("code 1001 going away", e.getJSONObject("voice").getString("closed"))
        assertEquals(5, e.getJSONObject("whatsapp").getInt("callLogResult"))
        // The caller's word on the end does not replace what the page already said about it.
        val d2 = diag(); d2.add("whatsapp", CallDiag.WA_ENDED, normalEnd.put("callDuration", 99).toString()); d2.ended("", "", normalEnd)
        assertEquals(99, d2.ended(null).getJSONObject("whatsapp").getInt("callDuration"))
    }

    @Test fun counterLinesSayWhatHappenedSinceTheLineBefore() {
        val d = diag(); d.add("whatsapp", CallDiag.WA_CONNECTED)
        fun page(at: Long, played: Int, frames: Int, sent: Long, got: Long) = JSONObject().put("at", at).put("played", played).put("remote_frames", frames)
            .put("bytes_sent", sent).put("bytes_received", got).put("pcs", 9).put("ice", "connected").put("mic", "running").put("hidden", true).put("errors", 0)
        d.sample(page(clock + 5_000, 10, 100, 9_088, 7_390), heard = 48, sent = 40)
        d.sample(page(clock + 10_000, 25, 210, 21_166, 19_286), heard = 98, sent = 90)
        // The busiest connection changed (a smaller total): never a negative line.
        d.sample(page(clock + 15_000, 25, 320, 500, 300), heard = 148, sent = 140)
        val c = d.counters()
        assertEquals(3, c.length())
        val a = c.getJSONObject(0); val b = c.getJSONObject(1); val z = c.getJSONObject(2)
        assertEquals(5.0, a.getDouble("s"), 0.0); assertEquals(10, a.getInt("voice_chunks")); assertEquals(48, a.getInt("heard_frames")); assertEquals(9_088, a.getInt("bytes_sent"))
        assertEquals(10.0, b.getDouble("s"), 0.0); assertEquals(15, b.getInt("voice_chunks")); assertEquals(50, b.getInt("heard_frames")); assertEquals(50, b.getInt("frames_to_voice")); assertEquals(110, b.getInt("page_frames"))
        assertEquals(12_078, b.getInt("bytes_sent")); assertEquals(11_896, b.getInt("bytes_received")); assertEquals(21_166, b.getInt("bytes_sent_total"))
        assertEquals(9, b.getInt("connections")); assertEquals("connected", b.getString("ice")); assertEquals("running", b.getString("mic")); assertTrue(b.getBoolean("hidden"))
        assertEquals(0, z.getInt("voice_chunks")); assertEquals(0, z.getInt("bytes_sent")); assertEquals(0, z.getInt("bytes_received"))
    }

    @Test fun aLongCallKeepsItsStartAndItsEnd() {
        val d = diag(); d.add("whatsapp", CallDiag.WA_CONNECTED)
        for (i in 1..200) d.sample(JSONObject().put("at", clock + i * 5_000L).put("played", i), 0, 0)
        val c = d.counters()
        assertEquals(CallDiag.MAX_SAMPLES, c.length())
        assertEquals(5.0, c.getJSONObject(0).getDouble("s"), 0.0); assertEquals(1000.0, c.getJSONObject(c.length() - 1).getDouble("s"), 0.0)
        assertEquals(CallDiag.KEEP_FIRST * 5.0, c.getJSONObject(CallDiag.KEEP_FIRST - 1).getDouble("s"), 0.0)
        // A flood of chatter never pushes out the events that decide the verdict.
        for (i in 1..500) d.add("whatsapp", CallDiag.WA_EVENT, "NetHealthStatusChangedV2")
        d.add("whatsapp", CallDiag.WA_EVENT, "CallTerminateReceived"); d.add("whatsapp", CallDiag.WA_ENDED, normalEnd.toString())
        val t = d.timeline()
        assertTrue(t.length() <= CallDiag.HARD_EVENTS)
        assertEquals("wa_ended", t.getJSONObject(t.length() - 1).getString("event")); assertEquals("wa_connected", t.getJSONObject(0).getString("event"))
        val e = d.ended(JSONObject().put("wa_events", true))
        assertEquals(CallDiag.BY_REMOTE, e.getString("by")); assertTrue(e.getInt("events_left_out") > 300)
    }

    @Test fun whatLooksWrongOnOurSideIsListedInPlainWords() {
        fun line(sent: Long, got: Long, hidden: Boolean = false) = JSONObject().put("connections", 1).put("bytes_sent", sent).put("bytes_received", got).put("hidden", hidden)
        val healthy = listOf(line(9000, 7000), line(12000, 12000), line(15000, 24000))
        assertTrue(CallDiag.signs(null, null, false, JSONObject().put("mic", "running"), healthy, false).isEmpty())
        val s = CallDiag.signs(null, "code 1011 deadline", true, JSONObject().put("mic", "suspended").put("tracks_ended", 1).put("mic_resumes", 2),
            listOf(line(9000, 7000), line(9000, 0), line(9000, 0, hidden = true)), true)
        for (w in listOf("1011", "loaded again", "stopped answering", "1 microphone track", "suspended", "2 time", "Nothing arrived", "hidden")) assertTrue(w, s.any { it.contains(w) })
        assertFalse(s.any { it.contains("Nothing was sent") })
        // Too few lines to say anything about traffic, and lines after the connections closed do not count.
        assertTrue(CallDiag.signs(null, null, false, null, listOf(line(0, 0), line(0, 0)), false).isEmpty())
        assertTrue(CallDiag.signs(null, null, false, null, healthy + JSONObject().put("connections", 0).put("bytes_received", 0) + JSONObject().put("connections", 0).put("bytes_received", 0), false).isEmpty())
        // And it is in the ended block.
        val d = diag(); d.add("voice", CallDiag.VOICE_CLOSED, "code 1006 gone")
        assertTrue(d.ended(null).getJSONArray("signs").getString(0).contains("1006"))
    }

    @Test fun theLastTwentyPageErrorsAreKept() {
        val d = diag(); d.add("whatsapp", CallDiag.WA_CONNECTED)
        for (i in 1..25) d.error("error $i " + "x".repeat(400), at = clock + i * 1_000L)
        val e = d.pageErrors()
        assertEquals(20, e.length()); assertTrue(e.getJSONObject(0).getString("error").startsWith("error 6 ")); assertEquals(300, e.getJSONObject(0).getString("error").length)
        assertEquals(25.0, e.getJSONObject(19).getDouble("s"), 0.0)
        assertEquals(20, d.ended(null).getJSONObject("page").getInt("errors"))
    }

    @Test fun aNewCallForgetsTheOneBefore() {
        val d = diag(); d.add("whatsapp", CallDiag.WA_CONNECTED); d.add("whatsapp", CallDiag.WA_ENDED, normalEnd.toString()); d.error("x"); d.sample(JSONObject(), 1, 1)
        d.reset()
        assertEquals(0, d.timeline().length()); assertEquals(0, d.counters().length()); assertEquals(0, d.pageErrors().length())
        assertFalse(d.answered); assertFalse(d.over); assertTrue(d.ended(null).isNull("whatsapp"))
        val all = d.json(null)
        for (k in listOf("ended", "timeline", "counters", "page_errors")) assertTrue(k, all.has(k))
    }
}

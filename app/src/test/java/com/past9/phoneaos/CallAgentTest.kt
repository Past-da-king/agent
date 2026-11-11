package com.past9.phoneaos

import com.past9.phoneaos.remote.RemoteIds
import com.past9.phoneaos.remote.RemoteRows
import com.past9.phoneaos.voice.CallController
import com.past9.phoneaos.voice.CallCore
import com.past9.phoneaos.voice.CallState
import com.past9.phoneaos.voice.PlaybackQueue
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 22 Oct 2026: on a linked call the voice said it couldn't delegate, talk to anyone or send anything, and the
 * computer's approvals never showed on the call screen. The voice must hand everything to the agent, say approvals out
 * loud, and take a yes by voice only when the user really said it.
 */
class CallAgentTest {
    private fun approvalRow(id: String) = JSONObject().put("id", id).put("kind", "approval").put("text", "Send the invoice to Shey")
        .put("meta", JSONObject().put("summary", "Send the invoice to Shey").put("details", "To: shey@example.com").put("decision", "").toString())
        .put("createdAt", 1_792_676_929_746L)

    @Test
    fun theVoiceIsToldItCanDoAnythingAndNeverToRefuse() {
        val p = CallController.systemPrompt("Hina", "Ayanda", linked = true)
        assertTrue(p.contains("NEVER say you can't"))
        assertTrue(p.contains("start_work"))
        assertTrue(p.contains("computer"))
        assertTrue(p.contains("Approve?"))
        assertFalse(CallController.systemPrompt("Hina", "Ayanda", linked = false).contains("their computer"))
    }

    @Test
    fun theCallDeclaresTheHandOffTheAnswerAndTheStatusTools() {
        val decls = CallController.callTools(true).getJSONObject(0).getJSONArray("functionDeclarations")
        val names = (0 until decls.length()).map { decls.getJSONObject(it).getString("name") }
        assertEquals(listOf("start_work", "answer_question", "work_status", "play_media", "media_control"), names)
        assertTrue(decls.getJSONObject(0).getString("description").contains("on their computer"))
        assertTrue(decls.getJSONObject(1).getJSONObject("parameters").getJSONObject("properties").has("user_said"))
    }

    @Test
    fun aComputerApprovalIsTheCallsOpenCardAndIsSaidOutLoud() {
        val item = RemoteRows.chat(approvalRow("a1"), RemoteIds())
        val open = CallController.openQuestion(listOf(item))
        assertNotNull(open)
        assertTrue(CallController.isApproval(open!!))
        assertEquals("Send the invoice to Shey", CallController.approvalWhat(open))
        val said = CallController.cardPrompt(open)
        assertTrue(said, said.contains("I need your OK to Send the invoice to Shey. Approve?"))
        // Answered: no longer the open card.
        val answered = item.copy(meta = JSONObject(item.meta).put("answer", "Approve").toString())
        assertEquals(null, CallController.openQuestion(listOf(answered)))
    }

    @Test
    fun linkedTheVoiceKnowsWhoIsOnTheComputerAndCanNameATarget() {
        val decls = CallController.callTools(true).getJSONObject(0).getJSONArray("functionDeclarations")
        val props = decls.getJSONObject(0).getJSONObject("parameters").getJSONObject("properties")
        assertTrue(props.has("target")); assertTrue(props.has("new_chat"))
        val alone = CallController.callTools(false).getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0).getJSONObject("parameters").getJSONObject("properties")
        assertFalse(alone.has("target"))
        assertTrue(CallController.systemPrompt("Hina", "Ayanda", true).contains("I'm on it in the Acme Website project"))
        assertTrue(CallController.systemPrompt("Hina", "Ayanda", true).contains("don't guess and don't send it to main"))
        val targets = org.json.JSONArray()
            .put(JSONObject().put("kind", "main").put("name", "Hina").put("running", false).put("doing", ""))
            .put(JSONObject().put("kind", "project").put("name", "Agent Desktop App").put("running", true).put("doing", "Wiring the RPC")
                .put("chats", org.json.JSONArray().put(JSONObject().put("title", "Phone call routing").put("running", true))))
            .put(JSONObject().put("kind", "helper").put("name", "Desktop dictation fix").put("running", true).put("doing", "Building the installer"))
        val r = CallController.roster(targets)
        assertTrue(r, r.contains("- Your main chat (target main), idle"))
        assertTrue(r, r.contains("- Your project Agent Desktop App, working: Wiring the RPC. Chats: \"Phone call routing\" (working)"))
        assertTrue(r, r.contains("- Your helper \"Desktop dictation fix\", working: Building the installer"))
        assertEquals("", CallController.roster(null))
    }

    @Test
    fun aYesByVoiceOnlyCountsWhenTheUserSaidIt() {
        assertTrue(CallController.saidYes("Yeah, go ahead"))
        assertTrue(CallController.saidYes("yes please send it"))
        assertFalse(CallController.saidYes("no don't send it"))
        assertFalse(CallController.saidYes("wait, what is it?"))
        assertFalse(CallController.saidYes("what's the weather"))
        assertFalse(CallController.saidYes(""))
        assertTrue(CallController.isNo("Decline"))
        assertFalse(CallController.isNo("Approve"))
    }

    // ---------- the call's state machine (9 Nov 2026) ----------

    private class Rig { var released = 0; val core = CallCore { released++ }; val st get() = core.state.value }

    @Test
    fun aCallGoesFromConnectingToLiveAndASecondStartIsIgnored() {
        val r = Rig()
        val g = r.core.begin()!!
        assertTrue(r.st.active); assertEquals("connecting", r.st.phase)
        assertEquals("A second tap while it connects starts nothing", null, r.core.begin())
        r.core.speaking(g, true)
        assertEquals("Nothing is live before the session says so", "connecting", r.st.phase)
        r.core.live(g)
        assertEquals("listening", r.st.phase)
        assertEquals("A second start while live is ignored", null, r.core.begin())
        r.core.thinking(g); assertEquals("thinking", r.st.phase)
        r.core.speaking(g, true); assertEquals("speaking", r.st.phase)
        r.core.speaking(g, false); assertEquals("listening", r.st.phase)
        r.core.reconnecting(g); assertEquals("reconnecting", r.st.phase)
        r.core.live(g); assertEquals("listening", r.st.phase)
        assertEquals(0, r.released)
    }

    @Test
    fun endFromEveryStateReleasesEverythingOnceAndTheNextCallStartsClean() {
        for (phase in listOf("connecting", "listening", "thinking", "speaking", "reconnecting")) {
            val r = Rig()
            val g = r.core.begin()!!
            if (phase != "connecting") r.core.live(g)
            when (phase) { "thinking" -> r.core.thinking(g); "speaking" -> r.core.speaking(g, true); "reconnecting" -> r.core.reconnecting(g) }
            assertEquals(phase, r.st.phase)
            r.core.caption(g, "in", "hello"); r.core.muted(true); r.core.level(g, false, 0.8f)
            r.core.end()
            assertEquals("$phase: released", 1, r.released)
            assertFalse(r.st.active); assertEquals("ended", r.st.phase); assertEquals("", r.st.error)
            assertFalse("$phase: not left muted", r.st.muted); assertEquals(0f, r.st.theirs)
            r.core.end()
            assertEquals("$phase: End twice releases once", 1, r.released)
            // What the dead call's session still sends changes nothing.
            r.core.speaking(g, true); r.core.live(g); r.core.dropped(g, "late")
            assertEquals("ended", r.st.phase); assertEquals(1, r.released)
            val g2 = r.core.begin()!!
            assertTrue(g2 != g)
            assertEquals(CallState(active = true, phase = "connecting"), r.st)
        }
    }

    @Test
    fun aCallThatNeverConnectsTimesOutWithAPlainReason() {
        val r = Rig()
        val g = r.core.begin()!!
        r.core.timedOut(g, CallCore.CONNECTING, CallCore.TIMED_OUT)
        assertFalse(r.st.active); assertEquals("failed", r.st.phase); assertEquals(CallCore.TIMED_OUT, r.st.error)
        assertEquals(1, r.released)
        assertEquals("Couldn't connect", com.past9.phoneaos.ui.screens.callStatusLabel(r.st))
        // The token arriving after the timeout does not bring the dead call back.
        assertFalse(r.core.current(g)); r.core.live(g); assertEquals("failed", r.st.phase)
        // A call that did connect is not touched by its own connecting timer, or by an older call's.
        val g2 = r.core.begin()!!
        r.core.live(g2)
        r.core.timedOut(g2, CallCore.CONNECTING, CallCore.TIMED_OUT); r.core.timedOut(g, CallCore.CONNECTING, CallCore.TIMED_OUT)
        assertTrue(r.st.active); assertEquals("listening", r.st.phase); assertEquals(1, r.released)
        // Reconnecting that never comes back ends the call and says so.
        r.core.reconnecting(g2); r.core.timedOut(g2, CallCore.RECONNECTING, CallCore.LOST)
        assertEquals("ended", r.st.phase); assertEquals(CallCore.LOST, r.st.error); assertEquals(2, r.released)
        assertEquals("Call cut off", com.past9.phoneaos.ui.screens.callStatusLabel(r.st))
    }

    @Test
    fun aRefusedOrDroppedCallSaysWhy() {
        val r = Rig()
        val g = r.core.begin()!!
        r.core.refuse(g, CallCore.NO_MIC, needsMic = true)
        assertEquals("failed", r.st.phase); assertTrue(r.st.needsMic); assertEquals(1, r.released)
        assertEquals("Microphone needed", com.past9.phoneaos.ui.screens.callStatusLabel(r.st))
        val g2 = r.core.begin()!!
        assertFalse("Asking again clears the refusal", r.st.needsMic)
        r.core.dropped(g2, CallCore.plainError("Unable to resolve host \"generativelanguage.googleapis.com\": No address associated with hostname"))
        assertEquals("failed", r.st.phase); assertEquals(CallCore.NO_NETWORK, r.st.error)
        val g3 = r.core.begin()!!
        r.core.live(g3); r.core.dropped(g3, "")
        assertEquals("A live call cut by the network says so", "ended", r.st.phase); assertEquals(CallCore.CUT_OFF, r.st.error)
        assertEquals(3, r.released)
        assertTrue(CallCore.plainError("HTTP 403 API key not valid").contains("Google key was refused"))
        assertEquals("", CallCore.plainError(""))
    }

    @Test
    fun captionsGrowInPlaceAndTellTheTwoSidesApart() {
        val r = Rig()
        val g = r.core.begin()!!
        r.core.live(g)
        listOf("Book", " the early", " one").forEach { r.core.caption(g, "in", it) }
        listOf("Done,", " it is held.").forEach { r.core.caption(g, "out", it) }
        r.core.caption(g, "in", "Thanks")
        assertEquals(listOf("in" to "Book the early one", "out" to "Done, it is held.", "in" to "Thanks"), r.st.lines)
        // Muted: the meter stays still and the state says so whatever else is going on.
        r.core.muted(true); r.core.level(g, true, 0.9f)
        assertEquals(0f, r.st.mine); assertEquals("Muted", com.past9.phoneaos.ui.screens.callStatusLabel(r.st))
        r.core.speaking(g, true)
        assertEquals("Talking, you're muted", com.past9.phoneaos.ui.screens.callStatusLabel(r.st))
        assertEquals("Connecting", com.past9.phoneaos.ui.screens.callStatusLabel(CallState()))
        assertEquals("Voice call · 3 min · 12 messages", CallCore.rowTitle(12, 1_000L, 181_000L))
        assertEquals("Voice call · 1 message", CallCore.rowTitle(1, 5_000L, 5_000L))
    }

    @Test
    fun talkingOverTheAgentFlushesWhatWasQueuedAndStopsTheSpeaker() {
        val written = mutableListOf<ByteArray>(); var flushed = 0; val speaking = mutableListOf<Boolean>()
        val q = PlaybackQueue(object : PlaybackQueue.Sink {
            override fun write(pcm: ByteArray) { written += pcm }
            override fun flush() { flushed++ }
        }, { speaking += it })
        repeat(5) { q.enqueue(ByteArray(320) { 9 }) }
        q.pump(0)
        assertEquals(1, written.size); assertEquals(listOf(true), speaking); assertEquals(4, q.pending)
        q.interrupt()
        assertEquals("The speaker's own buffer is dropped", 1, flushed)
        assertEquals("Nothing queued survives an interrupt", 0, q.pending)
        assertEquals(listOf(true, false), speaking)
        q.pump(0); q.pump(0)
        assertEquals("No stale audio after the interrupt", 1, written.size)
        // The next reply plays, and speaking ends only when it has played out.
        q.enqueue(ByteArray(320)); q.enqueue(ByteArray(320)); q.turnComplete()
        q.pump(0)
        assertEquals(2, written.size); assertEquals(true, speaking.last())
        q.pump(0); q.pump(0)
        assertEquals(3, written.size); assertEquals(listOf(true, false, true, false), speaking)
        q.close(); q.enqueue(ByteArray(320)); q.pump(0)
        assertEquals("A closed call plays nothing", 3, written.size)
    }

    @Test
    fun theSoundButtonGoesRoundEarphonesSpeakerEarpiece() {
        val K = com.past9.phoneaos.voice.AudioRoute.Kind.values().associateBy { it.name }
        val next = { k: String, headset: Boolean, earpiece: Boolean -> com.past9.phoneaos.voice.AudioRoute.nextChoice(K.getValue(k), headset, earpiece) }
        assertEquals(com.past9.phoneaos.voice.AudioRoute.TYPE_BUILTIN_SPEAKER, next("BLUETOOTH", true, true))
        assertEquals(com.past9.phoneaos.voice.AudioRoute.TYPE_BUILTIN_EARPIECE, next("SPEAKER", true, true))
        assertEquals("Back to the earphones", null, next("EARPIECE", true, true))
        assertEquals(com.past9.phoneaos.voice.AudioRoute.TYPE_BUILTIN_SPEAKER, next("EARPIECE", false, true))
        assertEquals("A tablet with no earpiece stays on the speaker", null, next("SPEAKER", false, false))
    }
}

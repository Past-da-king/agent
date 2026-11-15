package com.past9.phoneaos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.assist.AssistMessage
import com.past9.phoneaos.assist.AssistPrefs
import com.past9.phoneaos.assist.FrameDiff
import com.past9.phoneaos.assist.FrameGate
import com.past9.phoneaos.assist.FrameThrottle
import com.past9.phoneaos.assist.LiveAssistCore
import com.past9.phoneaos.assist.ScreenContext
import com.past9.phoneaos.assist.ScreenGuard
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.ui.screens.LiveCallParts
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Live sees the screen: when a frame may go (switches, money apps, protected windows), how often, and the call record it leaves. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveSeesTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val noBlock: (String, String) -> Boolean = { _, _ -> false }
    private val money: (String, String) -> Boolean = { p, l -> ScreenGuard.blocked(p, l) }
    private fun screen(pkg: String = "com.example.notes", shot: String = "/tmp/s.jpg", blocked: Boolean = false, protectedWindow: Boolean = false) =
        ScreenContext(pkg = pkg, label = "Notes", screenshot = shot, blocked = blocked, protectedWindow = protectedWindow)

    // ---- the switches -------------------------------------------------------------------------------

    @Test fun framesNeedBothSwitchesAndOffMeansNone() {
        assertFalse(FrameGate.enabled(readScreen = false, liveSees = true))
        assertFalse(FrameGate.enabled(readScreen = true, liveSees = false))
        assertFalse(FrameGate.enabled(false, false))
        assertTrue(FrameGate.enabled(true, true))
    }

    @Test fun liveSeesCannotBeOnWhileReadingTheScreenIsOff() {
        AssistPrefs.setReadScreen(ctx, false); AssistPrefs.setLiveSees(ctx, true)
        assertFalse(AssistPrefs.liveSees(ctx))
        AssistPrefs.setReadScreen(ctx, true)
        assertTrue("on by default once reading the screen is on", AssistPrefs.liveSees(ctx))
        AssistPrefs.setLiveSees(ctx, false)
        assertFalse(AssistPrefs.liveSees(ctx))
    }

    @Test fun thePhoneGathersAPictureOnlyForTheSheetsSwitchOrForLivesFrames() {
        AssistPrefs.setReadScreen(ctx, true); AssistPrefs.setReadScreenshot(ctx, false); AssistPrefs.setLiveVoice(ctx, false); AssistPrefs.setLiveSees(ctx, true)
        assertFalse("live voice off, picture off", AssistPrefs.screenshotAllowed(ctx))
        AssistPrefs.setLiveVoice(ctx, true)
        assertTrue("live voice on and allowed to see", AssistPrefs.screenshotAllowed(ctx))
        AssistPrefs.setLiveSees(ctx, false)
        assertFalse(AssistPrefs.screenshotAllowed(ctx))
        AssistPrefs.setReadScreen(ctx, false)
        assertFalse("reading off means no picture whatever else is on", AssistPrefs.screenshotAllowed(ctx))
    }

    // ---- what may be sent ---------------------------------------------------------------------------

    @Test fun anOrdinaryScreenIsSent() = assertEquals(FrameGate.Verdict.SEND, FrameGate.verdict(screen(), "com.example.notes", "Notes", noBlock))

    @Test fun bankingAndMoneyAppsAreNeverSent() {
        listOf("za.co.fnb.banking", "com.paypal.android.p2pmobile", "com.samsung.android.spay", "com.coinbase.android", "com.example.mybank", "com.chase.sig.android").forEach { pkg ->
            assertEquals(pkg, FrameGate.Verdict.PRIVATE, FrameGate.verdict(screen(pkg = pkg), pkg, "", money))
        }
    }

    @Test fun anAppTheAssistantAlreadyMarkedBlockedIsPrivate() =
        assertEquals(FrameGate.Verdict.PRIVATE, FrameGate.verdict(screen(blocked = true), "com.x", "", noBlock))

    @Test fun aProtectedWindowIsPrivateAndNothingIsSent() =
        assertEquals(FrameGate.Verdict.PRIVATE, FrameGate.verdict(screen(protectedWindow = true, shot = ""), "com.example.notes", "", noBlock))

    @Test fun noPictureOrNoKnownAppSendsNothing() {
        assertEquals(FrameGate.Verdict.NOTHING, FrameGate.verdict(null, "com.x", "", noBlock))
        assertEquals(FrameGate.Verdict.NOTHING, FrameGate.verdict(screen(shot = ""), "com.x", "", noBlock))
        assertEquals("never guess a screen is safe", FrameGate.Verdict.NOTHING, FrameGate.verdict(screen(), "", "", noBlock))
    }

    @Test fun withTheSheetOpenTheAppBehindItIsTheOneJudged() {
        assertEquals("com.bank.app", FrameGate.appToJudge("com.past9.phoneaos", "com.past9.phoneaos", "com.bank.app"))
        assertEquals("com.example.notes", FrameGate.appToJudge("com.example.notes", "com.past9.phoneaos", "com.bank.app"))
        assertEquals("", FrameGate.appToJudge("", "com.past9.phoneaos", ""))
        // The sheet over a banking app: the picture would show it, so it is private.
        assertEquals(FrameGate.Verdict.PRIVATE, FrameGate.verdict(screen(pkg = "com.past9.phoneaos"), FrameGate.appToJudge("com.past9.phoneaos", "com.past9.phoneaos", "za.co.fnb.banking"), "", money))
    }

    // ---- how often ----------------------------------------------------------------------------------

    @Test fun aboutOneFrameASecondWhileTheScreenChangesAndSlowWhenItHoldsStill() {
        val t = FrameThrottle()
        assertTrue("the first look is at once", t.due(0, false)); t.looked(0, true)
        assertFalse(t.due(1_000, false)); assertTrue(t.due(1_200, false)); t.looked(1_200, true)
        // Still for two looks: slows right down.
        t.looked(2_500, false); t.looked(3_700, false)
        assertFalse(t.due(5_000, false)); assertFalse(t.due(9_000, false)); assertTrue(t.due(9_700, false))
        // It moves again: back to about a second.
        t.looked(9_700, true)
        assertTrue(t.due(10_900, false))
    }

    @Test fun theUsersTurnStartingGetsAFreshLookButNotAHammer() {
        val t = FrameThrottle(); t.looked(0, false); t.looked(1_300, false); t.looked(2_600, false)
        assertTrue("forced overrides the slow pace", t.due(3_100, true))
        assertFalse("not within 400 ms of the last look", t.due(2_800, true))
    }

    @Test fun anUnchangedScreenIsNotResent() {
        val a = IntArray(256) { 100 }
        assertTrue("nothing before it", FrameDiff.differs(null, a))
        assertFalse(FrameDiff.differs(a, IntArray(256) { 102 }))
        assertTrue(FrameDiff.differs(a, IntArray(256) { 140 }))
        assertTrue("a different size is a different screen", FrameDiff.differs(a, IntArray(64) { 100 }))
        assertEquals(0, FrameDiff.luma(0x000000)); assertEquals(255, FrameDiff.luma(0xFFFFFF))
    }

    // ---- what Live is told and given ----------------------------------------------------------------

    @Test fun liveAnswersScreenQuestionsFromTheFramesAndOnlyHandsOverRealWork() {
        val p = LiveAssistCore.systemPrompt("Nova", "Sam", true)
        assertTrue(p.contains("You can SEE their screen"))
        assertTrue(p.contains("answer yourself from the latest picture"))
        assertTrue(p.contains("Do not hand that to the agent"))
        assertTrue(p.contains("If a note says the screen is private"))
        assertTrue(p.contains("hand_to_agent")); assertTrue(p.contains("include_screen"))
        assertFalse("the old rule that sent every screen question to the agent is gone", p.contains("just hand it off, do not ask them to read it out"))
    }

    @Test fun handToAgentTakesAnOptionalIncludeScreen() {
        val decls = LiveAssistCore.tools().getJSONObject(0).getJSONArray("functionDeclarations")
        val t = (0 until decls.length()).map { decls.getJSONObject(it) }.first { it.getString("name") == "hand_to_agent" }
        val props = t.getJSONObject("parameters").getJSONObject("properties")
        assertEquals("BOOLEAN", props.getJSONObject("include_screen").getString("type"))
        assertEquals("only request is required", 1, t.getJSONObject("parameters").getJSONArray("required").length())
    }

    @Test fun theAgentIsToldTheLineIsLivesParaphraseAndGetsTheUsersOwnWords() {
        val msg = LiveAssistCore.message("Update the EOD report", "", "side_key", null, spoken = "can you put today's numbers in the end of day report")
        assertTrue(msg.startsWith("Update the EOD report"))
        assertTrue(msg.contains("Live voice hand-off"))
        assertTrue(msg.contains("not something the user typed"))
        assertTrue(msg.contains("What the user actually said, just before: \"can you put today's numbers in the end of day report\""))
        assertTrue(msg.contains(AssistMessage.MARK))
        val without = LiveAssistCore.message("Do it", "", "side_key", null)
        assertFalse(without.contains("What the user actually said"))
    }

    @Test fun aHandOffCarriesItsOriginAndSpokenWordsToTheComputer() {
        val m = LiveAssistCore.handoffMeta("  Send the invoice ", " send sam the invoice ", true, "live-abc123")
        assertEquals("live_voice", m.getString("origin")); assertEquals("Send the invoice", m.getString("request")); assertEquals("live-abc123", m.getString("session"))
        assertEquals("send sam the invoice", m.getString("spoken")); assertTrue(m.getBoolean("include_screen"))
    }

    // ---- the call record ----------------------------------------------------------------------------

    private fun core(): LiveAssistCore { var now = 1_000L; val c = LiveAssistCore { now.also { now += 100 } }; c.connected(); return c }

    @Test fun aLiveSessionRecordsItselfAsACallWithItsHandOffsInside() {
        val c = core()
        c.caption("in", "can you "); c.caption("in", "email Sam the invoice")
        c.caption("out", "On it."); c.caption("in", "thanks")
        c.handOff("Email Sam the invoice", 5, includeScreen = true)
        val r = c.record(1_000, 9_000)
        assertEquals("live_assistant", r.getString("kind")); assertEquals("live", r.getString("source")); assertEquals(c.sessionId, r.getString("session"))
        assertTrue(c.sessionId.startsWith("live-"))
        assertEquals(8_000L, r.getLong("durationMs"))
        val lines = r.getJSONArray("lines")
        assertEquals(3, lines.length())
        assertEquals("you", lines.getJSONObject(0).getString("who")); assertEquals("can you email Sam the invoice", lines.getJSONObject(0).getString("text"))
        assertEquals("agent", lines.getJSONObject(1).getString("who"))
        val h = r.getJSONArray("handoffs")
        assertEquals(1, h.length())
        assertEquals("Email Sam the invoice", h.getJSONObject(0).getString("request"))
        assertEquals("thanks", h.getJSONObject(0).getString("spoken"))
        assertTrue(h.getJSONObject(0).getBoolean("include_screen"))
    }

    @Test fun theUsersWordsBehindAHandOffAreTheirLastStretchOfSpeech() {
        val c = core()
        assertEquals("", c.spokenNow())
        c.caption("in", "remind me to call Thabo at five")
        c.caption("out", "Sure.")
        assertEquals("remind me to call Thabo at five", c.spokenNow())
    }

    @Test fun anEmptySessionHasNothingToRecord() {
        val r = core().record(0, 0)
        assertEquals(0, r.getJSONArray("lines").length()); assertEquals(0, r.getJSONArray("handoffs").length())
    }

    // ---- how the chat reads it ----------------------------------------------------------------------

    private fun row(id: Long, kind: String, text: String, meta: JSONObject) = ChatItem(id = id, kind = kind, text = text, meta = meta.toString())

    @Test fun callRowsOnThisPhoneFoldTheHandOffsIntoTheCall() {
        val items = listOf(
            row(1, "user", "send sam the invoice", JSONObject().put("call", true).put("live", true)),
            row(2, "agent", "Send Sam the invoice", JSONObject().put("call", true).put("live", true).put("handoff", true).put("spoken", "send sam the invoice")),
            row(3, "agent", "On it.", JSONObject().put("call", true).put("live", true)),
        )
        assertTrue(LiveCallParts.isLive(items))
        val hands = LiveCallParts.handoffs(items)
        assertEquals(1, hands.size); assertEquals("send sam the invoice", hands[0].spoken)
        assertNull(LiveCallParts.handoffOf(items[0]))
        assertEquals("Live voice · 3 min · 3 messages · 1 hand-off", LiveCallParts.title("Voice call · 3 min · 3 messages", true, 1))
        assertEquals("Voice call · 3 min", LiveCallParts.title("Voice call · 3 min", false, 0))
        assertEquals("Handed to your agent: Send Sam the invoice", LiveCallParts.summary("On it.", hands))
        assertEquals("On it.", LiveCallParts.summary("On it.", emptyList()))
    }

    @Test fun aHandOffTheComputerReplicatedBackFoldsIntoTheCallAndIsNeverATypedBubble() {
        val note = "\n\n[Assistant sheet: the user said this to the phone's assistant sheet]\nScreen: not read."
        val live = JSONObject().put("origin", "live_voice").put("spoken", "update my end of day").put("session", "live-1")
        val replicated = row(5, "user", "Update the EOD report" + "\n[Live voice hand-off: wording]" + note, JSONObject().put("via", JSONObject().put("from", "call").put("name", "")).put("live", live))
        assertTrue(LiveCallParts.isLiveRow(replicated))
        assertTrue(LiveCallParts.isLive(listOf(replicated)))
        val h = LiveCallParts.handoffOf(replicated)!!
        assertEquals("Update the EOD report", h.request); assertEquals("update my end of day", h.spoken)
        // A plain message the user typed is not one.
        assertFalse(LiveCallParts.isLiveRow(row(6, "user", "hello", JSONObject())))
        // An agent reply is not a hand-off even in a call.
        assertNull(LiveCallParts.handoffOf(row(7, "agent", "Done.", JSONObject().put("live", live))))
    }

    @Test fun aCallRowFromTheComputerWithItsMetadataIsReadToo() {
        val meta = JSONObject().put("kind", "live_assistant").put("handoffs", JSONArray().put(JSONObject().put("request", "Update the EOD report").put("spoken", "update my end of day").put("include_screen", false)).put(JSONObject().put("request", " ")))
        val hands = LiveCallParts.fromMeta(meta)
        assertEquals(1, hands.size); assertEquals("update my end of day", hands[0].spoken)
        assertTrue("an old call row has none", LiveCallParts.fromMeta(JSONObject()).isEmpty())
        assertEquals("Live voice · 2 hand-offs", LiveCallParts.title("Voice call", true, 2))
    }
}

package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.browser.CallDiag
import com.past9.phoneaos.browser.CallBridgeJs
import com.past9.phoneaos.browser.CallTestAccounts
import com.past9.phoneaos.browser.CallTestAccounts.Gate
import com.past9.phoneaos.browser.CallTestAccounts.Parsed
import com.past9.phoneaos.browser.WhatsAppCall
import com.past9.phoneaos.browser.WhatsAppCall.Chat
import com.past9.phoneaos.browser.WhatsAppCall.Info
import com.past9.phoneaos.browser.WhatsAppCall.State
import com.past9.phoneaos.browser.WhatsAppCallJs
import com.past9.phoneaos.data.SettingsStore
import com.past9.phoneaos.remote.DeviceTools
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A WhatsApp call placed in one step (place_whatsapp_call), with the page and the voice both played by the test,
 * and the rule for switching calling on: only for a number the user marked as a test account. The numbers here are
 * made up (the 555 01xx range).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WhatsAppCallTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun store() = SettingsStore(ctx)
    @Before fun clean() { store().setExtra("call_test_numbers", null); store().setExtra("call_test_on", null) }

    private val test = "15550100"
    private val main = "15550199"

    // ---- B: the test-account switch ----

    @Test fun aNumberIsKeptAsDigitsWithItsCountryCode() {
        fun ok(s: String) = (CallTestAccounts.normalise(s) as? Parsed.Ok)?.number
        assertEquals("15550100", ok("+1 555 0100")); assertEquals("15550100", ok("1 (555) 01-00")); assertEquals("15550100", ok("001 555 0100"))
        assertEquals("442079460958", ok(" +44 20 7946 0958 "))
        for (bad in listOf("", "   ", "0555 0100", "555", "+1 555 0100 ext 2", "abc", "+1234567890123456", "+0 555 0100 22"))
            assertTrue(bad, CallTestAccounts.normalise(bad) is Parsed.Bad)
        assertTrue((CallTestAccounts.normalise("0555 010 0100") as Parsed.Bad).why.contains("country code"))
    }

    @Test fun theSwitchIsOffOnAFreshInstallAndHoldsNoNumber() {
        assertFalse(store().callTestOn()); assertEquals(emptyList<String>(), store().callTestNumbers())
        assertEquals(Gate.SWITCH_OFF, CallTestAccounts.gate(store().callTestOn(), store().callTestNumbers(), "$test@c.us"))
    }

    @Test fun theSwitchCannotBeTurnedOnWithoutANumber() {
        assertFalse(store().setCallTestOn(true)); assertFalse(store().callTestOn())
        // Even a stored "on" does nothing while no number is marked.
        store().setExtra("call_test_on", "true"); assertFalse(SettingsStore(ctx).callTestOn())
    }

    @Test fun aMarkedNumberIsStoredAndRemovingTheLastTurnsTheSwitchOff() {
        assertNull(store().addCallTestNumber("+1 555 0100"))
        assertTrue(store().addCallTestNumber("+1 555 0100")!!.contains("already"))
        assertTrue(store().addCallTestNumber("0555 0100")!!.contains("country code"))
        assertEquals(listOf(test), SettingsStore(ctx).callTestNumbers())
        assertFalse("adding a number does not turn the switch on", store().callTestOn())
        assertTrue(store().setCallTestOn(true)); assertTrue(SettingsStore(ctx).callTestOn())
        store().removeCallTestNumber("+$test")
        assertFalse(SettingsStore(ctx).callTestOn()); assertEquals(emptyList<String>(), store().callTestNumbers())
        // Marking a number again later does not bring the old "on" back.
        store().addCallTestNumber("+1 555 0100"); assertFalse(store().callTestOn())
    }

    @Test fun callingIsOnlyForcedForTheExactNumberTheUserMarked() {
        assertEquals(Gate.ALLOWED, CallTestAccounts.gate(true, listOf(test), "$test@c.us"))
        assertEquals(Gate.ALLOWED, CallTestAccounts.gate(true, listOf(test), "$test:14@c.us"))
        assertEquals(Gate.ALLOWED, CallTestAccounts.gate(true, listOf(main, test), test))
        assertEquals(Gate.SWITCH_OFF, CallTestAccounts.gate(false, listOf(test), "$test@c.us"))
        assertEquals(Gate.NO_NUMBER, CallTestAccounts.gate(true, emptyList(), "$test@c.us"))
        assertEquals(Gate.OTHER_ACCOUNT, CallTestAccounts.gate(true, listOf(test), "$main@c.us"))
        // A longer or shorter number is another number.
        assertEquals(Gate.OTHER_ACCOUNT, CallTestAccounts.gate(true, listOf(test), "1${test}@c.us"))
        assertEquals(Gate.OTHER_ACCOUNT, CallTestAccounts.gate(true, listOf(test), test.dropLast(1)))
        for (unknown in listOf(null, "", "@c.us", "null")) assertEquals(Gate.NOT_SIGNED_IN, CallTestAccounts.gate(true, listOf(test), unknown))
        for (g in Gate.values()) assertTrue(CallTestAccounts.why(g).isNotBlank())
    }

    @Test fun theWarningSaysWhatCanHappenAndTheSourceHoldsNoNumber() {
        val w = CallTestAccounts.WARNING
        assertTrue(w.contains("banned")); assertTrue(w.contains("test number only")); assertTrue(w.contains("never your main number"))
        assertFalse(w.contains("—"))
        // The page script that forces calling checks the signed-in number itself, takes the number as an argument,
        // finds WhatsApp's setting by its name, and is not part of what is installed in the page.
        val js = WhatsAppCallJs.ENABLE_CALLING
        assertTrue(js.contains("mine !== String(args.number)")); assertTrue(js.contains("['enable_web_calling'][0]")); assertTrue(js.contains("getMaybeMePnUser"))
        assertFalse(Regex("[0-9]{5,}").containsMatchIn(js))
        assertFalse(CallBridgeJs.HOOKS.contains("enable_web_calling")); assertFalse(CallBridgeJs.HOOKS.contains("WAWebABProps"))
        for (other in listOf(WhatsAppCallJs.INFO, WhatsAppCallJs.OPEN_CHAT, WhatsAppCallJs.STATE, WhatsAppCallJs.HANG_UP, WhatsAppCallJs.PRESS_CALL, WhatsAppCallJs.HAS_BUTTON))
            assertFalse(other.contains("enable_web_calling"))
        // The call button is pressed from page script, not by a tap.
        assertTrue(WhatsAppCallJs.PRESS_CALL.contains("b.click()"))
    }

    @Test fun theToolIsLentToALinkedComputerWithTheBrowser() {
        assertEquals("browser", DeviceTools.groupOf("place_whatsapp_call"))
    }

    // ---- D: the call, against a made-up page ----

    /** WhatsApp Web as the call sees it. The script says what the page shows at each second after Voice call is pressed. */
    private class FakePage(
        var info: Info? = Info(on = true, loaded = true, hooks = true, me = "15550100", calling = true, unsupported = null),
        var button: Boolean = true,
        /** seconds since the press -> the call's state; the last entry holds from then on */
        val script: Map<Int, State> = emptyMap(),
        val clock: Clock,
    ) : WhatsAppCall.Page {
        val log = ArrayList<String>()
        var pressedAt = -1L
        var hungUpAt = -1L
        var enableWorks = true
        var buttonAfterEnable = true
        var hangUpWorks = true
        var hooksAfterOpen = true
        var left = false
        override suspend fun info(): Info? = info
        override suspend fun open() { log += "open"; info = info?.copy(on = true, loaded = true, hooks = hooksAfterOpen) }
        override suspend fun enableCalling(number: String): Chat { log += "enable:$number"; if (enableWorks) { info = info?.copy(calling = true); button = buttonAfterEnable }; return Chat(enableWorks, why = if (enableWorks) "" else "no such setting") }
        override suspend fun openChat(chat: String, reopen: Boolean): Chat { log += "chat:$chat" + if (reopen) ":reopen" else ""; return if (chat == "Nobody") Chat(false, why = "No chat matches.") else Chat(true, "123@lid", "Sam Jones") }
        override suspend fun hasCallButton() = button
        override suspend fun pressCall(): Boolean { log += "press"; pressedAt = clock.t; return true }
        override suspend fun state(): State? {
            if (left) return null
            if (pressedAt < 0) return State(false, false, null, "old", false)
            if (hungUpAt >= 0 && hangUpWorks) return State(false, false, null, "new", false)
            val sec = ((clock.t - pressedAt) / 1000).toInt()
            return script.entries.filter { it.key <= sec }.maxByOrNull { it.key }?.value ?: State(false, false, null, "old", false)
        }
        override suspend fun hangUp(): Boolean { log += "hangup"; if (hungUpAt < 0) hungUpAt = clock.t; return hangUpWorks }
        override suspend fun leave() { log += "leave"; left = true }
    }

    private class Clock { var t = 1_000_000L }

    private class FakeVoice(override var on: Boolean = true) : WhatsAppCall.Voice {
        var started: Pair<String, String>? = null
        var startError: String? = null
        var stops = 0
        val nudges = ArrayList<String>()
        override var endRequested = false
        override var failed: String? = null
        override var turns = 0
        var said = "Them: Hello\nVoice: Hi Sam"
        var min = -1; var answeredCalls = 0
        override fun answered() { answeredCalls++ }
        override suspend fun start(peer: String, goal: String, minSeconds: Int): String? { if (startError != null) return startError; started = peer to goal; min = minSeconds; return null }
        override fun nudge(text: String) { nudges += text }
        override fun words() = if (started != null) said else ""
        override fun stop() { stops++ }
        var endedBy: String? = null; var endedWhy = ""; var endedWhatsApp: JSONObject? = null; var endedAfterStop = false
        var story: JSONObject? = JSONObject().put("ended", JSONObject().put("by", "remote_hangup").put("detail", "WhatsApp reported that the other side ended the call (CallTerminateReceived)."))
            .put("timeline", org.json.JSONArray().put(JSONObject().put("s", 0).put("event", "wa_connected"))).put("counters", org.json.JSONArray()).put("page_errors", org.json.JSONArray())
        override fun ended(by: String, why: String, whatsapp: JSONObject?) { endedBy = by; endedWhy = why; endedWhatsApp = whatsapp; endedAfterStop = stops > 0 }
        override fun diagnostics(): JSONObject? = story
    }

    private val ringing = State(true, false, "call1", "old", true)
    private val talking = State(false, true, "call1", "old", true)
    private val over = State(false, false, null, "call1", false, JSONObject().put("ended", JSONObject().put("id", "call1").put("callOutcome", "Completed").put("callDuration", 21).put("wasEverConnected", true)))
    private val missed = State(false, false, null, "call1", false, JSONObject().put("ended", JSONObject().put("id", "call1").put("callOutcome", "Rejected").put("wasEverConnected", false)))

    private class Rig(val page: FakePage, val voice: FakeVoice, val clock: Clock, testOn: Boolean = false, marked: List<String> = emptyList(), onTick: (Long) -> Unit = {}) {
        val call = WhatsAppCall(page, voice, test = { testOn to marked }, now = { clock.t }, pause = { ms -> clock.t += ms; onTick(clock.t) })
        fun run(chat: String = "Sam Jones", goal: String = "confirm Saturday lunch", max: Int = 5, contact: String = "", min: Int = 0) = runBlocking { call.run(chat, goal, max, contact, min) }
    }

    private fun rig(script: Map<Int, State> = emptyMap(), testOn: Boolean = false, marked: List<String> = emptyList(), voice: FakeVoice = FakeVoice(), onTick: (Rig, Long) -> Unit = { _, _ -> }): Rig {
        val clock = Clock()
        lateinit var r: Rig
        r = Rig(FakePage(script = script, clock = clock), voice, clock, testOn, marked) { t -> onTick(r, t) }
        return r
    }

    @Test fun anAnsweredCallIsHeldUntilTheOtherSideHangsUpAndComesBackWithItsWords() {
        val r = rig(mapOf(0 to ringing, 8 to talking, 30 to over))
        val out = r.run()
        assertEquals(WhatsAppCall.ANSWERED, out.outcome)
        assertEquals("Sam Jones", out.contact); assertEquals("123@lid", out.chat)
        assertEquals(8, out.rangSeconds); assertEquals(22, out.durationSeconds)
        assertEquals(WhatsAppCall.OTHER_SIDE, out.endedBy)
        assertEquals("Them: Hello\nVoice: Hi Sam", out.transcript)
        // The voice was armed with who is being called and why BEFORE the button was pressed, and stopped once after.
        assertEquals("Sam Jones" to "confirm Saturday lunch", r.voice.started); assertEquals(1, r.voice.stops)
        assertEquals(listOf("chat:Sam Jones", "press"), r.page.log)
        val j = out.json()
        for (k in listOf("outcome", "reason", "contact", "chat", "rang_seconds", "duration_seconds", "ended_by", "transcript", "saved_to_chat", "calling_offered", "test_account", "whatsapp")) assertTrue(k, j.has(k))
        assertTrue(j.getBoolean("saved_to_chat")); assertEquals("Completed", j.getJSONObject("whatsapp").getJSONObject("ended").getString("callOutcome"))
        assertEquals("done", r.call.phase)
    }

    @Test fun theNameToUseCanBeGivenWhenTheChatIsCalledSomethingElse() {
        val r = rig(mapOf(0 to ringing, 3 to talking, 9 to over))
        assertEquals("Samuel", r.run(contact = " Samuel ").contact)
        assertEquals("Samuel", r.voice.started!!.first)
    }

    @Test fun whenTheVoiceSaysTheConversationIsDoneTheAgentHangsUpAfterTheGoodbye() {
        val r = rig(mapOf(0 to ringing, 5 to talking)) { rig, t -> if (rig.page.pressedAt > 0 && t - rig.page.pressedAt >= 20_000) rig.voice.endRequested = true }
        val out = r.run()
        assertEquals(WhatsAppCall.ANSWERED, out.outcome); assertEquals("agent", out.endedBy)
        assertTrue(r.page.log.contains("hangup")); assertFalse(r.page.log.contains("leave"))
        // 15 s of talk, then the goodbye is given time to be heard.
        assertEquals(15 + WhatsAppCall.GOODBYE_MS / 1000, out.durationSeconds)
    }

    @Test fun aCallThatRunsToItsLimitIsHungUp() {
        val r = rig(mapOf(0 to ringing, 5 to talking))
        val out = r.run(max = 2)
        assertEquals(WhatsAppCall.ANSWERED, out.outcome); assertEquals("max_minutes", out.endedBy)
        assertEquals(120, out.durationSeconds); assertTrue(out.reason.contains("2 minutes"))
        assertTrue(r.page.log.contains("hangup")); assertEquals(1, r.voice.stops)
    }

    @Test fun theLimitIsFiveMinutesUnlessAskedAndNeverMoreThanThirty() {
        assertEquals(5, WhatsAppCall.DEFAULT_MINUTES)
        val r = rig(mapOf(0 to ringing, 1 to talking)); val out = r.run(max = 500)
        assertEquals(WhatsAppCall.MAX_MINUTES * 60L, out.durationSeconds)
        val r2 = rig(mapOf(0 to ringing, 1 to talking)); assertEquals(60, r2.run(max = 0).durationSeconds)
    }

    @Test fun aCallNobodyAnswersEndsAsNoAnswerWithoutWaitingForever() {
        // WhatsApp gives up by itself and says so.
        val r = rig(mapOf(0 to ringing, 31 to missed))
        val out = r.run()
        assertEquals(WhatsAppCall.NO_ANSWER, out.outcome); assertEquals(31, out.rangSeconds); assertEquals(0, out.durationSeconds)
        assertFalse(r.page.log.contains("hangup")); assertEquals(1, r.voice.stops)
        // WhatsApp keeps ringing: the agent hangs up after a minute.
        val r2 = rig(mapOf(0 to ringing))
        val out2 = r2.run()
        assertEquals(WhatsAppCall.NO_ANSWER, out2.outcome); assertEquals("agent", out2.endedBy); assertEquals(WhatsAppCall.RING_MS / 1000, out2.rangSeconds)
        assertTrue(r2.page.log.contains("hangup")); assertTrue(out2.reason.contains("Nobody answered"))
    }

    @Test fun aChatWithNoCallButtonPlacesNoCallAndSaysWhyAndThatTheTestSwitchIsOff() {
        val r = rig(); r.page.button = false; r.page.info = r.page.info!!.copy(calling = false)
        val out = r.run()
        assertEquals(WhatsAppCall.FAILED, out.outcome)
        assertTrue(out.reason, out.reason.contains("does not offer calling")); assertTrue(out.reason.contains("is off"))
        assertEquals(false, out.callingOffered); assertTrue(out.testAccount.contains("is off"))
        assertFalse(r.page.log.any { it.startsWith("enable") }); assertFalse(r.page.log.contains("press")); assertNull(r.voice.started); assertEquals(0, r.voice.stops)
    }

    @Test fun callingIsSwitchedOnOnlyWhenTheSignedInNumberIsAMarkedTestAccount() {
        // The switch is on and the signed-in number is marked: switched on in the page, the chat reopened, the call placed.
        val a = rig(mapOf(0 to ringing, 2 to talking, 6 to over), testOn = true, marked = listOf(test)); a.page.button = false; a.page.info = a.page.info!!.copy(calling = false)
        val outA = a.run()
        assertEquals(WhatsAppCall.ANSWERED, outA.outcome)
        assertEquals(listOf("enable:$test", "chat:Sam Jones:reopen", "press"), a.page.log); assertTrue(outA.testAccount.contains("switched on"))
        // The switch is on but another account is signed in: nothing is forced.
        val b = rig(testOn = true, marked = listOf(main)); b.page.button = false; b.page.info = b.page.info!!.copy(calling = false)
        val outB = b.run()
        assertEquals(WhatsAppCall.FAILED, outB.outcome); assertFalse(b.page.log.any { it.startsWith("enable") }); assertTrue(outB.reason.contains("not one the user marked"))
        // The number is marked but the switch is off: nothing is forced.
        val c = rig(testOn = false, marked = listOf(test)); c.page.button = false; c.page.info = c.page.info!!.copy(calling = false)
        assertEquals(WhatsAppCall.FAILED, c.run().outcome); assertFalse(c.page.log.any { it.startsWith("enable") })
        // WhatsApp already offers calling to the account: nothing is forced even for a marked number.
        val d = rig(mapOf(0 to ringing, 2 to talking, 6 to over), testOn = true, marked = listOf(test))
        assertEquals(WhatsAppCall.ANSWERED, d.run().outcome); assertFalse(d.page.log.any { it.startsWith("enable") })
        // WhatsApp Web does not say who is signed in: nothing is forced.
        val e = rig(testOn = true, marked = listOf(test)); e.page.button = false; e.page.info = e.page.info!!.copy(calling = false, me = null)
        assertEquals(WhatsAppCall.FAILED, e.run().outcome); assertFalse(e.page.log.any { it.startsWith("enable") })
    }

    @Test fun whenForcingDoesNotBringTheButtonTheCallIsNotPlaced() {
        val r = rig(testOn = true, marked = listOf(test)); r.page.button = false; r.page.buttonAfterEnable = false; r.page.info = r.page.info!!.copy(calling = false)
        val out = r.run()
        assertEquals(WhatsAppCall.FAILED, out.outcome); assertTrue(out.reason.contains("still has no voice call button")); assertFalse(r.page.log.contains("press"))
        val r2 = rig(testOn = true, marked = listOf(test)); r2.page.enableWorks = false; r2.page.info = r2.page.info!!.copy(calling = false)
        assertTrue(r2.run().reason.contains("no such setting"))
    }

    @Test fun theVoiceSwitchOffPlacesNoCall() {
        val r = rig(voice = FakeVoice(on = false))
        val out = r.run()
        assertEquals(WhatsAppCall.FAILED, out.outcome); assertTrue(out.reason.contains("Let the agent speak in WhatsApp calls")); assertTrue(r.page.log.isEmpty())
    }

    @Test fun aPageWithoutTheCallVoiceIsLoadedAgainOnceSoTheSwitchWorksWithoutWaiting() {
        val r = rig(mapOf(0 to ringing, 2 to talking, 6 to over)); r.page.info = r.page.info!!.copy(hooks = false)
        assertEquals(WhatsAppCall.ANSWERED, r.run().outcome)
        assertEquals(listOf("open", "chat:Sam Jones", "press"), r.page.log)
        // Still not there after loading again: said plainly, no call.
        val r2 = rig(); r2.page.info = r2.page.info!!.copy(hooks = false); r2.page.hooksAfterOpen = false
        val out = r2.run()
        assertEquals(WhatsAppCall.FAILED, out.outcome); assertTrue(out.reason.contains("not in the WhatsApp Web page")); assertEquals(1, r2.page.log.count { it == "open" })
    }

    @Test fun whatsAppWebIsOpenedWhenTheBrowserIsSomewhereElseAndASignedOutPageStopsIt() {
        val r = rig(mapOf(0 to ringing, 2 to talking, 6 to over)); r.page.info = r.page.info!!.copy(on = false, loaded = false)
        assertEquals(WhatsAppCall.ANSWERED, r.run().outcome); assertEquals("open", r.page.log.first())
        val clock = Clock()
        val page = object : WhatsAppCall.Page by FakePage(clock = clock) { override suspend fun info() = Info(true, false, false, null, false, null) }
        val out = runBlocking { WhatsAppCall(page, FakeVoice(), { false to emptyList() }, { clock.t }, { clock.t += it }).run("Sam", "hello") }
        assertEquals(WhatsAppCall.FAILED, out.outcome); assertTrue(out.reason.contains("not signed in"))
    }

    @Test fun aBrowserThatCannotCallAnUnknownChatAndAVoiceThatWillNotStartAllStopBeforeTheCall() {
        val a = rig(); a.page.info = a.page.info!!.copy(unsupported = "missing_shared_array_buffer")
        assertTrue(a.run().reason.contains("missing_shared_array_buffer")); assertTrue(a.page.log.isEmpty())
        val b = rig(); val outB = b.run(chat = "Nobody")
        assertEquals(WhatsAppCall.FAILED, outB.outcome); assertEquals("No chat matches.", outB.reason); assertNull(b.voice.started)
        val c = rig(voice = FakeVoice().also { it.startError = "The voice could not start: no key." })
        val outC = c.run()
        assertEquals("The voice could not start: no key.", outC.reason); assertFalse(c.page.log.contains("press"))
        assertEquals(WhatsAppCall.FAILED, rig().run(chat = " ").outcome)
    }

    @Test fun aCallThatNeverStartsIsReportedWithWhatWhatsAppShows() {
        val idle = State(false, false, null, "old", false, JSONObject().put("dialog", "Allow microphone"))
        val r = rig(mapOf(0 to idle))
        val out = r.run()
        assertEquals(WhatsAppCall.FAILED, out.outcome); assertTrue(out.reason, out.reason.contains("did not start")); assertTrue(out.reason.contains("Allow microphone"))
        assertEquals(1, r.voice.stops)
    }

    @Test fun aVoiceThatDiesDuringTheCallEndsTheCall() {
        val r = rig(mapOf(0 to ringing, 2 to talking)) { rig, t -> if (rig.page.pressedAt > 0 && t - rig.page.pressedAt >= 10_000) rig.voice.failed = "connection failed" }
        val out = r.run()
        assertEquals(WhatsAppCall.ANSWERED, out.outcome); assertTrue(out.reason.contains("connection failed")); assertTrue(r.page.log.contains("hangup"))
    }

    @Test fun aHangUpThatDoesNotTakeLeavesThePageSoNoCallIsLeftRunning() {
        val r = rig(mapOf(0 to ringing, 2 to talking)); r.page.hangUpWorks = false
        val out = r.run(max = 1)
        assertEquals("max_minutes", out.endedBy)
        assertEquals(2, r.page.log.count { it == "hangup" }); assertEquals("leave", r.page.log.last())
    }

    @Test fun aSilentAnsweredCallGetsOneNudgeToGreetAndATalkingOneGetsNone() {
        val r = rig(mapOf(0 to ringing, 2 to talking, 20 to over))
        r.run(); assertEquals(1, r.voice.nudges.size); assertTrue(r.voice.nudges[0].contains("Greet"))
        val r2 = rig(mapOf(0 to ringing, 2 to talking, 20 to over), voice = FakeVoice().also { it.turns = 1 })
        r2.run(); assertTrue(r2.voice.nudges.isEmpty())
    }

    @Test fun aConnectionThatBlipsIsNotTakenForTheEndButALostOneIs() {
        val gone = State(false, false, "call1", "old", false)
        val r = rig(mapOf(0 to ringing, 2 to talking, 10 to gone, 12 to talking, 30 to over))
        assertEquals(28, r.run().durationSeconds)
        val r2 = rig(mapOf(0 to ringing, 2 to talking, 10 to gone))
        val out = r2.run()
        assertEquals(WhatsAppCall.ANSWERED, out.outcome); assertEquals(WhatsAppCall.OTHER_SIDE, out.endedBy); assertEquals((8 + WhatsAppCall.LOST_POLLS - 1).toLong(), out.durationSeconds)
    }

    @Test fun aCallWhatsAppIsReconnectingIsGivenTimeBeforeTheAgentEndsIt() {
        // Not connected, but WhatsApp still shows the call (its End call button is there): it is reconnecting.
        val reconnecting = State(false, false, "call1", "old", true)
        val r = rig(mapOf(0 to ringing, 2 to talking, 10 to reconnecting, 30 to talking, 50 to over))
        val out = r.run()
        assertEquals(48, out.durationSeconds); assertEquals(WhatsAppCall.OTHER_SIDE, out.endedBy); assertFalse(r.page.log.contains("hangup"))
        // It never comes back: the agent ends it, and says why.
        val r2 = rig(mapOf(0 to ringing, 2 to talking, 10 to reconnecting))
        val out2 = r2.run()
        assertEquals("agent", out2.endedBy); assertTrue(out2.reason, out2.reason.contains("not connected for 30 seconds")); assertTrue(r2.page.log.contains("hangup"))
        assertEquals((8 + WhatsAppCall.RECONNECT_POLLS - 1).toLong(), out2.durationSeconds)
        assertEquals(CallDiag.AGENT_OTHER, r2.voice.endedBy)
        assertTrue(WhatsAppCall.RECONNECT_POLLS > WhatsAppCall.LOST_POLLS)
    }

    @Test fun theCallSaysWhoEndedItAndHandsBackItsStory() {
        // The other side: the agent claims nothing, WhatsApp's own record of the end is passed on, and the story's verdict is the reason.
        val a = rig(mapOf(0 to ringing, 8 to talking, 30 to over)); val outA = a.run()
        assertEquals("", a.voice.endedBy); assertEquals("Completed", a.voice.endedWhatsApp!!.getString("callOutcome")); assertTrue(a.voice.endedAfterStop)
        assertTrue(outA.reason, outA.reason.contains("CallTerminateReceived"))
        val j = outA.json()
        for (k in listOf("ended", "timeline", "counters", "page_errors")) assertTrue(k, j.has(k))
        assertEquals("remote_hangup", j.getJSONObject("ended").getString("by")); assertEquals(1, j.getJSONArray("timeline").length())
        // The voice finished the conversation.
        val b = rig(mapOf(0 to ringing, 5 to talking)) { rig, t -> if (rig.page.pressedAt > 0 && t - rig.page.pressedAt >= 20_000) rig.voice.endRequested = true }
        b.run(); assertEquals(CallDiag.AGENT_END_CALL, b.voice.endedBy)
        // The limit.
        val c = rig(mapOf(0 to ringing, 5 to talking)); c.run(max = 1)
        assertEquals(CallDiag.AGENT_MAX_MINUTES, c.voice.endedBy); assertTrue(c.voice.endedWhy.contains("1 minutes"))
        // The voice broke.
        val d = rig(mapOf(0 to ringing, 5 to talking)) { rig, t -> if (rig.page.pressedAt > 0 && t - rig.page.pressedAt >= 20_000) rig.voice.failed = "token expired" }
        d.run(); assertEquals(CallDiag.AGENT_VOICE_FAILED, d.voice.endedBy); assertTrue(d.voice.endedWhy.contains("token expired"))
        // Nobody answered: the agent hung up, but not because the conversation was done.
        val e = rig(mapOf(0 to ringing)); e.run(); assertEquals(CallDiag.AGENT_OTHER, e.voice.endedBy)
        // A voice that keeps no story: the result is as before.
        val f = rig(mapOf(0 to ringing, 8 to talking, 30 to over)); f.voice.story = null
        val outF = f.run(); assertFalse(outF.json().has("ended")); assertTrue(outF.reason.contains("other side"))
    }

    @Test fun nothingButTheLimitTheAgentWasGivenEndsALongQuietCall() {
        // Half an hour connected, not a word said, the voice alive: only max_minutes ends it.
        val r = rig(mapOf(0 to ringing, 3 to talking))
        val out = r.run(max = 30)
        assertEquals("max_minutes", out.endedBy); assertEquals(30 * 60L, out.durationSeconds)
        for (limit in listOf(WhatsAppCall.START_MS, WhatsAppCall.RING_MS)) assertTrue(limit <= 60_000L)
    }

    @Test fun aPageThatStopsAnsweringEndsTheCallInsteadOfHanging() {
        val clock = Clock(); val inner = FakePage(script = mapOf(0 to ringing, 2 to talking), clock = clock)
        val page = object : WhatsAppCall.Page by inner { override suspend fun state(): State? = if (inner.pressedAt > 0 && clock.t - inner.pressedAt > 6_000) null else inner.state() }
        val voice = FakeVoice()
        val out = runBlocking { WhatsAppCall(page, voice, { false to emptyList() }, { clock.t }, { clock.t += it }).run("Sam Jones", "hello") }
        assertEquals(WhatsAppCall.ANSWERED, out.outcome); assertEquals("page", out.endedBy); assertEquals(1, voice.stops)
    }

    @Test fun whatsAppsOwnWordThatTheCallConnectedWinsWhenThePollingMissedIt() {
        val r = rig(mapOf(0 to ringing, 4 to over))
        val out = r.run()
        assertEquals(WhatsAppCall.ANSWERED, out.outcome)
    }

    // ---- what the page scripts hand back ----

    @Test fun thePagesAnswersAreRead() {
        val info = WhatsAppCall.parseInfo(WhatsAppCall.value("""{"ok":true,"v":{"on":true,"loaded":true,"hooks":true,"me":"15550100","calling":false,"unsupported":null}}"""))!!
        assertEquals(Info(true, true, true, "15550100", false, null), info)
        assertNull(WhatsAppCall.value("""{"ok":false,"e":"Timed out"}""")); assertNull(WhatsAppCall.value("timeout")); assertNull(WhatsAppCall.parseInfo(null))
        val s = WhatsAppCall.parseState(JSONObject("""{"pending":true,"connected":false,"active":{"id":"A1"},"ended":null,"end_button":true}"""))!!
        assertTrue(s.pending); assertEquals("A1", s.activeId); assertNull(s.endedId); assertFalse(s.quiet)
        assertTrue(WhatsAppCall.parseState(JSONObject("""{"pending":false,"connected":false,"end_button":false}"""))!!.quiet)
        assertFalse(WhatsAppCall.parseChat(null).ok); assertEquals("Sam", WhatsAppCall.parseChat(JSONObject("""{"ok":true,"id":"1@c.us","name":"Sam"}""")).name)
    }

    // ---- min_seconds (0.15.36) ----

    @Test fun theMinimumTimeIsHandedToTheVoiceAndTheAnswerIsToldToIt() {
        val r = rig(mapOf(0 to ringing, 8 to talking, 30 to over))
        val out = r.run(min = 65)
        assertEquals(65, r.voice.min); assertEquals(1, r.voice.answeredCalls)
        assertEquals(65, out.minSeconds); assertEquals(65, out.json().getInt("min_seconds"))
        // The other side hung up at 22 s, well before the minimum: the call ends at once all the same.
        assertEquals(WhatsAppCall.ANSWERED, out.outcome); assertEquals(WhatsAppCall.OTHER_SIDE, out.endedBy); assertEquals(22, out.durationSeconds)
    }

    @Test fun maxMinutesWinsOverALongerMinimum() {
        val r = rig(mapOf(0 to ringing, 5 to talking))
        val out = r.run(max = 1, min = 600)
        assertEquals(60, r.voice.min)
        assertEquals("max_minutes", out.endedBy); assertEquals(60, out.durationSeconds)
    }

    @Test fun withNoMinimumNothingChanges() {
        val r = rig(mapOf(0 to ringing, 8 to talking, 30 to over))
        r.run()
        assertEquals(0, r.voice.min)
    }
}

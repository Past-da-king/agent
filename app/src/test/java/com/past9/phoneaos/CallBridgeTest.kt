package com.past9.phoneaos

import com.past9.phoneaos.browser.CallBridge
import com.past9.phoneaos.browser.CallBridgeJs
import com.past9.phoneaos.browser.CallBridgeProtocol
import com.past9.phoneaos.browser.CallBridgeProtocol.FromPage
import com.past9.phoneaos.voice.ExternalAudio
import com.past9.phoneaos.voice.LiveAuth
import com.past9.phoneaos.voice.LiveLink
import com.past9.phoneaos.voice.LiveSession
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The agent's voice in a WhatsApp Web call: the words the page and the app exchange, and the wiring between the
 * page's sound and the Live voice session, with the session and the page both played by the test.
 */
class CallBridgeTest {
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun pageAudio(bytes: ByteArray, rate: Int = 16000) = JSONObject().put("t", "pcm").put("rate", rate).put("d", b64(bytes)).toString()

    // ---- the words ----

    @Test fun thePagesMessagesAreRead() {
        assertTrue(CallBridgeProtocol.parse("""{"t":"hello"}""") is FromPage.Hello)
        val a = CallBridgeProtocol.parse(pageAudio(byteArrayOf(1, 2, 3, 4), 48000)) as FromPage.Audio
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), a.pcm); assertEquals(48000, a.rate)
        assertEquals("armed", (CallBridgeProtocol.parse("""{"t":"log","m":"armed"}""") as FromPage.Log).text)
        assertEquals(2, (CallBridgeProtocol.parse("""{"t":"state","taps":2}""") as FromPage.State).json.optInt("taps"))
    }

    @Test fun anythingElseFromThePageIsIgnored() {
        for (raw in listOf(null, "", "hello", "[1,2]", """{"t":"eval","js":"alert(1)"}""", """{"t":"pcm","rate":16000,"d":"***"}""",
            """{"t":"pcm","rate":16000,"d":""}""", """{"t":"pcm","rate":5,"d":"AAAA"}""", """{"t":"pcm","rate":900000,"d":"AAAA"}""",
            "x".repeat(CallBridgeProtocol.MAX_CHARS + 1)))
            assertTrue("$raw".take(40), CallBridgeProtocol.parse(raw) is FromPage.Bad)
    }

    @Test fun aStrayByteFromThePageIsDropped() {
        assertEquals(2, (CallBridgeProtocol.parse(pageAudio(byteArrayOf(1, 2, 3))) as FromPage.Audio).pcm.size)
    }

    @Test fun theAppsMessagesAreWhatThePageExpects() {
        assertEquals(true, JSONObject(CallBridgeProtocol.arm(true)).let { it.getString("t") == "arm" && it.getBoolean("on") })
        assertFalse(JSONObject(CallBridgeProtocol.arm(false)).getBoolean("on"))
        val p = JSONObject(CallBridgeProtocol.play(byteArrayOf(9, 8, 7, 6)))
        assertEquals("play", p.getString("t")); assertEquals(24000, p.getInt("rate"))
        assertArrayEquals(byteArrayOf(9, 8, 7, 6), Base64.getDecoder().decode(p.getString("d")))
        assertEquals("flush", JSONObject(CallBridgeProtocol.flush()).getString("t"))
        assertEquals("status", JSONObject(CallBridgeProtocol.status()).getString("t"))
    }

    @Test fun theVoiceHasOneToolAndItEndsTheCall() {
        val decl = CallBridgeProtocol.tools().getJSONObject(0).getJSONArray("functionDeclarations")
        assertEquals(1, decl.length()); assertEquals("end_call", decl.getJSONObject(0).getString("name"))
    }

    @Test fun theVoiceIsToldWhoItIsAndWhy() {
        val s = CallBridgeProtocol.system("Hina", "Ayanda", "Asanda", "confirm Saturday lunch")
        for (w in listOf("Hina", "Ayanda", "Asanda", "confirm Saturday lunch", "end_call")) assertTrue(w, s.contains(w))
        // Nothing is assumed when the names are not known.
        assertFalse(CallBridgeProtocol.system("", "", "", "").contains("  "))
    }

    @Test fun theVoiceIsToldWhoWillAnswerAndToGreetThemByNameFirst() {
        val s = CallBridgeProtocol.system("Hina", "Ayanda", "Sam Jones", "confirm Saturday lunch.")
        assertTrue(s, s.contains("The person who answers will be Sam Jones")); assertTrue(s.contains("Greet them by name first")); assertTrue(s.contains("\"Hi Sam\""))
        assertTrue(s.contains("you are Hina, calling for Ayanda")); assertTrue(s.contains("Why you are calling: confirm Saturday lunch. "))
        assertTrue(s.contains("polite goodbye")); assertTrue(s.contains("end_call")); assertTrue(s.contains("Ai assistant")); assertFalse(s.contains("AI"))
        assertFalse(s.contains("\u2014")); assertFalse(s.contains("  "))
    }

    @Test fun whenThePersonCalledIsTheUserTheVoiceGreetsThemAndDoesNotCallOnTheirBehalf() {
        for (peer in listOf("Ayanda", "ayanda ", "Ayanda Phaketsi")) {
            val s = CallBridgeProtocol.system("Hina", "Ayanda", peer, "a test call")
            assertTrue(s, s.contains("The person who answers is Ayanda")); assertTrue(s.contains("\"Hi Ayanda, it's Hina\""))
            assertTrue(s.contains("do not say you are calling for anyone")); assertFalse(s.contains("calling for Ayanda"))
        }
        assertTrue(CallBridgeProtocol.sameName("Sam", "sam jones")); assertTrue(CallBridgeProtocol.sameName("Sam Jones", "Sam"))
        assertFalse(CallBridgeProtocol.sameName("Sam Jones", "Sam Smith")); assertFalse(CallBridgeProtocol.sameName("", "Sam")); assertFalse(CallBridgeProtocol.sameName("Sam", " "))
        // Nobody named: it says hello first and still says who it is.
        val unknown = CallBridgeProtocol.system("Hina", "Ayanda", "", "a test call")
        assertTrue(unknown.contains("You do not know who will answer")); assertTrue(unknown.contains("you are Hina, calling for Ayanda"))
    }

    @Test fun theCallIsStartedWithTheContactAndTheUsersName() {
        val r = Rig(); r.pageSays("""{"t":"hello"}"""); runBlocking { r.bridge.start("Sam Jones", "confirm lunch") }
        val system = r.opened!![3]
        assertTrue(system.contains("Sam Jones")); assertTrue(system.contains("Ayanda")); assertTrue(system.contains("Hina")); assertTrue(system.contains("confirm lunch"))
    }

    @Test fun theWordsSoFarAndANudgeAreThereWhileACallRuns() {
        val r = Rig(); assertFalse(r.bridge.nudge("x")); assertEquals(0, r.bridge.turns); assertEquals("", r.bridge.words())
        r.pageSays("""{"t":"hello"}"""); r.start()
        assertTrue(r.bridge.nudge("The call is connected.")); assertEquals(listOf("The call is connected."), r.link.said)
        r.events(LiveSession.Event.Transcript("in", "Hello?")); r.events(LiveSession.Event.Transcript("out", "Hi Sam"))
        assertEquals(2, r.bridge.turns); assertEquals("Them: Hello?\nVoice: Hi Sam", r.bridge.words())
    }

    // ---- the page script ----

    @Test fun thePageScriptListsOneNamedMicrophoneGrantsItAndMakesItMono() {
        val js = CallBridgeJs.HOOKS
        // Nothing in it runs unless the app gave this page the listener, which it only does with the switch on.
        assertTrue(js.indexOf("typeof hinaCall === 'undefined') return") in 0 until js.indexOf("enumerateDevices"))
        assertTrue(js.contains("md.enumerateDevices = async function"))
        assertTrue(js.contains("kind: 'audioinput', deviceId: MIC_ID, label: '${CallBridgeJs.MIC_LABEL}', groupId: '${CallBridgeJs.MIC_GROUP}'"))
        assertTrue(js.contains("const MIC_ID = '${CallBridgeJs.MIC_ID}'"))
        // Speakers are handed back as the browser gives them: only microphones are replaced.
        assertTrue(js.contains("d.kind !== 'audioinput'")); assertFalse(js.contains("setSinkId")); assertFalse(js.contains("kind: 'audiooutput'"))
        // Only the microphone permission is answered; anything else goes to the browser.
        assertTrue(js.contains("d.name === 'microphone'")); assertTrue(js.contains("st.state = 'granted'")); assertTrue(js.contains("return origQuery(d)"))
        assertTrue(js.contains("new EventTarget()"))
        for (other in listOf("'camera'", "'geolocation'", "'notifications'")) assertFalse(other, js.contains(other))
        // Mono, 48 kHz.
        assertTrue(js.contains("new AudioContext({ sampleRate: 48000 })")); assertTrue(js.contains("H.micDest.channelCount = 1")); assertTrue(js.contains("channelCountMode = 'explicit'"))
    }

    @Test fun thePageScriptStartsDisarmedAndForcesNothing() {
        val js = CallBridgeJs.HOOKS
        assertTrue(js.contains("inject: false")); assertTrue(js.contains("forward: false"))
        // It only runs where the app put the listener, in the top page.
        assertTrue(js.contains("typeof hinaCall === 'undefined'")); assertTrue(js.contains("window.top !== window"))
        // No WhatsApp feature is switched on from here: a call only works if WhatsApp offers it to the account.
        for (banned in listOf("WAWebABProps", "enable_web_calling", "setGetABPropConfigValueImpl", "require(")) assertFalse(banned, js.contains(banned))
        // Kotlin must not have eaten anything: a raw string would read a dollar sign as a template.
        assertFalse(js.contains("null") && js.contains("\$"))
        assertEquals(CallBridgeProtocol.OBJECT, "hinaCall")
        assertEquals("https://web.whatsapp.com", CallBridgeProtocol.ORIGIN)
    }

    // ---- the wiring ----

    private class FakeLink : LiveLink {
        var started = false; var stopped = 0; var out: ExternalAudio? = null; var ready = true
        val sent = ArrayList<ByteArray>(); val results = ArrayList<Triple<String, String, JSONObject>>(); val said = ArrayList<String>()
        override fun start() { started = true }
        override fun openExternalAudio(out: ExternalAudio) { this.out = out }
        override fun sendAudio(pcm: ByteArray): Boolean { if (!ready) return false; sent += pcm; return true }
        override fun announce(text: String) { said += text }
        override fun sendToolResult(id: String, name: String, result: JSONObject) { results += Triple(id, name, result) }
        override fun stop() { stopped++ }
    }

    private class Rig(token: LiveAuth.Token = LiveAuth.Token.Ok("wss://generativelanguage.googleapis.com/x?access_token=t", "live-model", "Kore"), var pageListening: Boolean = true) {
        val toPage = ArrayList<JSONObject>()
        val link = FakeLink()
        var opened: List<String>? = null
        var tools: JSONArray? = null
        lateinit var events: (LiveSession.Event) -> Unit
        var saved: Triple<Long, Long, List<Triple<String, String, Long>>>? = null
        var saves = 0; var alive = 0; var clock = 1_000L; var authCalls = 0
        val bridge = CallBridge(
            toPage = { m -> if (pageListening) { toPage += JSONObject(m); true } else false },
            auth = { authCalls++; token },
            open = { url, model, voice, system, t, onEvent -> opened = listOf(url, model, voice, system); tools = t; events = onEvent; link },
            save = { a, b, l -> saves++; saved = Triple(a, b, l) },
            names = { "Hina" to "Ayanda" },
            keepAlive = { alive++ },
            now = { clock },
        )
        fun pageSays(raw: String) = bridge.onPageMessage(raw)
        fun start() = runBlocking { bridge.start("Asanda", "confirm lunch") }
        fun types() = toPage.map { it.getString("t") }
    }

    @Test fun nothingStartsBeforeThePageHasTheBridge() {
        val r = Rig()
        assertTrue(r.start().contains("has not loaded"))
        assertEquals(0, r.authCalls); assertNull(r.opened); assertFalse(r.bridge.running); assertTrue(r.toPage.isEmpty())
    }

    @Test fun startArmsThePageAndOpensTheVoiceWithoutThePhonesAudio() {
        val r = Rig(); r.pageSays("""{"t":"hello"}""")
        val said = r.start()
        assertTrue(said, said.contains("armed"))
        assertEquals(listOf("arm"), r.types()); assertTrue(r.toPage[0].getBoolean("on"))
        assertEquals("wss://generativelanguage.googleapis.com/x?access_token=t", r.opened!![0]); assertEquals("live-model", r.opened!![1]); assertEquals("Kore", r.opened!![2])
        assertTrue(r.opened!![3].contains("Asanda") && r.opened!![3].contains("confirm lunch") && r.opened!![3].contains("Hina"))
        assertEquals("end_call", r.tools!!.getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0).getString("name"))
        assertTrue(r.link.started); assertTrue(r.link.out != null); assertTrue(r.bridge.running); assertTrue(r.bridge.armed)
    }

    @Test fun aSecondStartChangesNothing() {
        val r = Rig(); r.pageSays("""{"t":"hello"}"""); r.start()
        assertTrue(r.start().contains("already")); assertEquals(1, r.authCalls)
    }

    @Test fun noKeyOrTokenIsSaidInWordsAndNothingIsArmed() {
        val r = Rig(token = LiveAuth.Token.No(LiveAuth.NEEDS_KEY, true)); r.pageSays("""{"t":"hello"}""")
        assertTrue(r.start().contains(LiveAuth.NEEDS_KEY))
        assertFalse(r.bridge.armed); assertFalse(r.bridge.running); assertTrue(r.toPage.isEmpty())
    }

    @Test fun whatTheOtherPersonSaysReachesTheVoiceAsHundredMillisecondFramesAtSixteen() {
        val r = Rig(); r.pageSays("""{"t":"hello"}"""); r.start()
        // The page sends 48 kHz sound in pieces of 2048 samples (its audio callback): 0.3 s in all.
        val all = ByteArray(14_400 * 2) { (it % 100).toByte() }
        var at = 0
        while (at < all.size) { val end = minOf(all.size, at + 4096); r.pageSays(pageAudio(all.copyOfRange(at, end), 48000)); at = end }
        // 0.3 s is 4800 samples at 16 kHz: each 4096-byte piece becomes 682 samples (one is lost per piece to rounding), so two whole frames have gone.
        assertEquals(2, r.link.sent.size)
        assertTrue(r.link.sent.all { it.size == 3200 })
        assertEquals(2, r.bridge.sentFrames); assertEquals(2, r.bridge.heardFrames)
        assertTrue(r.alive > 1)
    }

    @Test fun sixteenKilohertzFromThePagePassesThroughUnchangedAndInOrder() {
        val r = Rig(); r.pageSays("""{"t":"hello"}"""); r.start()
        val all = ByteArray(6400) { (it % 127).toByte() }
        r.pageSays(pageAudio(all.copyOfRange(0, 1000))); r.pageSays(pageAudio(all.copyOfRange(1000, 5000))); r.pageSays(pageAudio(all.copyOfRange(5000, 6400)))
        assertEquals(2, r.link.sent.size)
        assertArrayEquals(all, r.link.sent[0] + r.link.sent[1])
    }

    @Test fun soundFromThePageIsDroppedUnlessACallVoiceIsRunning() {
        val r = Rig(); r.pageSays("""{"t":"hello"}""")
        r.pageSays(pageAudio(ByteArray(6400)))
        assertEquals(0, r.bridge.heardFrames); assertEquals(0, r.alive)
        r.start(); r.bridge.stop()
        r.pageSays(pageAudio(ByteArray(6400)))
        assertEquals(0, r.link.sent.size)
    }

    @Test fun soundTheVoiceIsNotReadyForIsCountedAsHeardNotSent() {
        val r = Rig(); r.pageSays("""{"t":"hello"}"""); r.start(); r.link.ready = false
        r.pageSays(pageAudio(ByteArray(3200)))
        assertEquals(1, r.bridge.heardFrames); assertEquals(0, r.bridge.sentFrames)
    }

    @Test fun theVoiceGoesToThePageAtTwentyFour() {
        val r = Rig(); r.pageSays("""{"t":"hello"}"""); r.start()
        r.link.out!!.play(byteArrayOf(1, 2, 3, 4, 5, 6))
        val m = r.toPage.last()
        assertEquals("play", m.getString("t")); assertEquals(24000, m.getInt("rate"))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), Base64.getDecoder().decode(m.getString("d")))
        assertEquals(1, r.bridge.playedChunks)
    }

    @Test fun talkingOverTheVoiceEmptiesThePagesQueue() {
        val r = Rig(); r.pageSays("""{"t":"hello"}"""); r.start()
        r.link.out!!.flush()
        assertEquals("flush", r.types().last())
    }

    @Test fun theWordsAreKeptPerTurnAndSavedOnceWhenItStops() {
        val r = Rig(); r.pageSays("""{"t":"hello"}"""); r.start()
        r.events(LiveSession.Event.SetupComplete); assertTrue(r.bridge.ready)
        r.clock = 2_000; r.events(LiveSession.Event.Transcript("out", "Hello, this is Hina ")); r.events(LiveSession.Event.Transcript("out", "calling for Ayanda."))
        r.clock = 5_000; r.events(LiveSession.Event.Transcript("in", "Oh hi."))
        r.clock = 7_000; r.events(LiveSession.Event.Transcript("out", "Is Saturday still fine?"))
        assertTrue(r.bridge.status().getString("transcript").contains("Them: Oh hi."))
        r.clock = 9_000
        val said = r.bridge.stop()
        assertTrue(said, said.contains("Voice: Hello, this is Hina calling for Ayanda."))
        assertEquals(1, r.saves); assertEquals(1_000L, r.saved!!.first); assertEquals(9_000L, r.saved!!.second)
        assertEquals(listOf(Triple("out", "Hello, this is Hina calling for Ayanda.", 2_000L), Triple("in", "Oh hi.", 5_000L), Triple("out", "Is Saturday still fine?", 7_000L)), r.saved!!.third)
        // The record the computer is sent is the same shape a phone call sends.
        val rec = LiveAuth.record(r.saved!!.first, r.saved!!.second, r.saved!!.third)
        assertEquals(8_000L, rec.getLong("durationMs")); assertEquals("you", rec.getJSONArray("lines").getJSONObject(1).getString("who"))
        // The page is disarmed and its queue emptied; the session is let go; a second stop does nothing more.
        assertEquals(listOf("flush", "arm"), r.types().takeLast(2)); assertFalse(r.toPage.last().getBoolean("on"))
        assertEquals(1, r.link.stopped); assertFalse(r.bridge.running); assertFalse(r.bridge.armed)
        assertEquals("No call voice is running.", r.bridge.stop()); assertEquals(1, r.saves); assertEquals(1, r.link.stopped)
    }

    @Test fun aCallWhereNothingWasSaidSavesNothing() {
        val r = Rig(); r.pageSays("""{"t":"hello"}"""); r.start()
        assertTrue(r.bridge.stop().contains("Nothing was said")); assertEquals(0, r.saves)
    }

    @Test fun endCallIsAnsweredAndShownButHangingUpIsNotDoneBehindTheAgentsBack() {
        val r = Rig(); r.pageSays("""{"t":"hello"}"""); r.start()
        r.events(LiveSession.Event.ToolCall("c1", "end_call", JSONObject().put("reason", "done")))
        assertEquals("c1", r.link.results.single().first); assertTrue(r.link.results.single().third.getBoolean("ok"))
        assertTrue(r.bridge.endRequested); assertTrue(r.bridge.status().getBoolean("end_requested")); assertTrue(r.bridge.running)
    }

    @Test fun aVoiceThatFailsOrClosesDisarmsThePageAndKeepsTheWords() {
        val r = Rig(); r.pageSays("""{"t":"hello"}"""); r.start()
        r.events(LiveSession.Event.Transcript("in", "Hello?"))
        r.events(LiveSession.Event.Failed("connection failed"))
        assertFalse(r.bridge.running); assertFalse(r.bridge.armed); assertEquals("connection failed", r.bridge.failed)
        assertEquals("arm", r.types().last()); assertFalse(r.toPage.last().getBoolean("on")); assertEquals(1, r.saves)
        val r2 = Rig(); r2.pageSays("""{"t":"hello"}"""); r2.start(); r2.events(LiveSession.Event.Closed("closed"))
        assertFalse(r2.bridge.running); assertFalse(r2.bridge.armed)
    }

    @Test fun aReloadedPageIsArmedAgainWhileTheCallVoiceRuns() {
        val r = Rig(); r.pageSays("""{"t":"hello"}"""); r.start()
        r.toPage.clear(); r.pageSays("""{"t":"hello"}""")
        assertEquals(listOf("arm"), r.types()); assertTrue(r.toPage[0].getBoolean("on"))
        // Not while nothing runs: a page that loads stays untouched.
        r.bridge.stop(); r.toPage.clear(); r.pageSays("""{"t":"hello"}""")
        assertTrue(r.toPage.isEmpty())
    }

    @Test fun aPageThatIsNotListeningStopsTheStart() {
        val r = Rig(); r.pageSays("""{"t":"hello"}"""); r.pageListening = false
        assertTrue(r.start().contains("not listening"))
        assertFalse(r.bridge.running); assertFalse(r.bridge.armed); assertEquals(1, r.link.stopped); assertFalse(r.link.started)
    }

    @Test fun statusShowsWhatThePageReports() {
        val r = Rig(); r.pageSays("""{"t":"hello"}"""); r.start()
        r.pageSays("""{"t":"state","armed":true,"gum":1,"taps":1}"""); r.pageSays("""{"t":"log","m":"speaker tap at 48000 Hz"}""")
        val s = r.bridge.status()
        assertEquals("status", r.types().last())
        assertEquals(1, s.getJSONObject("page").getInt("gum")); assertEquals("speaker tap at 48000 Hz", s.getJSONArray("page_log").getString(0))
        assertTrue(s.getBoolean("armed")); assertTrue(s.getBoolean("page_ready"))
    }

    // ---- what goes on the wire to the voice ----

    @Test fun aFrameOnTheWireIsSixteenKilohertzPcmInBase64() {
        val frame = ByteArray(3200) { (it % 50).toByte() }
        val audio = JSONObject(LiveSession.audioMessage(frame, frame.size)).getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
        assertArrayEquals(frame, Base64.getDecoder().decode(audio.getString("data")))
    }
}

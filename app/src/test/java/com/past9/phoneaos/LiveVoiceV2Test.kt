package com.past9.phoneaos

import com.past9.phoneaos.assist.LiveAssistCore
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.voice.ExternalAudio
import com.past9.phoneaos.voice.LiveSession
import com.past9.phoneaos.voice.LiveTuning
import com.past9.phoneaos.voice.SpokenResult
import com.past9.phoneaos.voice.TurnHold
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Live voice v2: patient turn-taking (server VAD tuned, optional wait-for-me), compression and resumption, names for the
 * transcriber, answers read as written, and nothing sent to the model while he is mid-sentence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveVoiceV2Test {
    private lateinit var server: MockWebServer
    private val wire = LinkedBlockingQueue<JSONObject>()

    @Before fun up() { server = MockWebServer(); server.start() }
    @After fun down() { runCatching { server.shutdown() } }

    private fun url() = server.url("/live").toString().replaceFirst("http", "ws")

    /** A fake Live server that records every message it receives, in order, and answers setup. */
    private fun recording(refuseFirstSetupWith: Int? = null) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val m = JSONObject(text)
            wire.add(m)
            if (m.has("setup")) {
                if (refuseFirstSetupWith != null) { webSocket.close(refuseFirstSetupWith, "Invalid value at setup"); return }
                webSocket.send("""{"setupComplete":{}}""")
            }
        }
    })

    private fun session(tuning: LiveTuning = LiveTuning(), dictation: Boolean = false) =
        LiveSession(url(), "gemini-test", "Kore", "be brief", tools = null, dictation = dictation, tuning = tuning) { }

    private fun next(): JSONObject = wire.poll(6, TimeUnit.SECONDS)!!

    // ---- turn-taking -------------------------------------------------------------------------------

    @Test fun theDefaultSetupIsPatient() {
        server.enqueue(recording())
        val s = session(); s.start()
        val vad = next().getJSONObject("setup").getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection")
        assertEquals("END_SENSITIVITY_LOW", vad.getString("endOfSpeechSensitivity"))
        assertEquals("START_SENSITIVITY_LOW", vad.getString("startOfSpeechSensitivity"))
        assertEquals(1400, vad.getInt("silenceDurationMs"))
        assertEquals(200, vad.getInt("prefixPaddingMs"))
        assertFalse(vad.has("disabled"))
        s.stop()
    }

    @Test fun thePresetsChangeTheWaitAndNothingElseIsHardcoded() {
        val quick = LiveTuning(LiveTuning.Patience.QUICK).realtimeInputConfig().getJSONObject("automaticActivityDetection")
        assertEquals("END_SENSITIVITY_HIGH", quick.getString("endOfSpeechSensitivity")); assertEquals(800, quick.getInt("silenceDurationMs"))
        val very = LiveTuning(LiveTuning.Patience.VERY).realtimeInputConfig().getJSONObject("automaticActivityDetection")
        assertEquals("END_SENSITIVITY_LOW", very.getString("endOfSpeechSensitivity")); assertEquals(2400, very.getInt("silenceDurationMs"))
        assertEquals(LiveTuning.Patience.PATIENT, LiveTuning.Patience.of("nonsense"))
        assertEquals(LiveTuning.Patience.VERY, LiveTuning.Patience.of("very"))
    }

    @Test fun waitForMeTurnsTheServerDetectorOffAndSendsNoSensitivity() {
        val vad = LiveTuning(waitForMe = true).realtimeInputConfig().getJSONObject("automaticActivityDetection")
        assertTrue(vad.getBoolean("disabled")); assertFalse(vad.has("endOfSpeechSensitivity")); assertFalse(vad.has("silenceDurationMs"))
    }

    @Test fun waitForMeMarksTheTurnOnTheWireInOrderAndSendsNoSoundOutsideIt() {
        server.enqueue(recording())
        val s = session(LiveTuning(waitForMe = true))
        val sink = object : ExternalAudio { override fun play(pcm: ByteArray) {}; override fun flush() {} }
        s.openExternalAudio(sink); s.start()
        val setup = next().getJSONObject("setup")
        assertTrue(setup.getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection").getBoolean("disabled"))
        Thread.sleep(300)
        val pcm = ByteArray(3200) { if (it % 2 == 0) 100 else 40 }
        assertFalse("not his turn yet: nothing is sent", s.sendAudio(pcm))
        s.beginTurn(); assertTrue(s.talking)
        assertTrue(s.sendAudio(pcm))
        s.endTurn(); assertFalse(s.talking)
        assertFalse("after I'm done: nothing is sent", s.sendAudio(pcm))
        val kinds = ArrayList<String>()
        repeat(3) { kinds += next().getJSONObject("realtimeInput").keys().next() }
        assertEquals(listOf("activityStart", "audio", "activityEnd"), kinds)
        s.stop()
    }

    @Test fun dictationKeepsTheServerDefaultsAndItsPinnedLanguage() {
        server.enqueue(recording())
        val s = session(LiveTuning(autoLanguage = true), dictation = true); s.start()
        val setup = next().getJSONObject("setup")
        assertEquals("dictation uses the server's own turn-taking", 0, setup.getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection").length())
        assertEquals("en-US", setup.getJSONObject("generationConfig").getJSONObject("speechConfig").getString("languageCode"))
        s.stop()
    }

    @Test fun aRefusedTurnTakingFieldFallsBackToServerDefaultsAndTheCallStillStarts() {
        server.enqueue(recording(refuseFirstSetupWith = 1007))
        server.enqueue(recording())
        val s = session(); s.start()
        val first = next().getJSONObject("setup")
        assertTrue(first.getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection").has("silenceDurationMs"))
        val second = wire.poll(8, TimeUnit.SECONDS)!!.getJSONObject("setup")
        assertEquals("the retry carries no tuned fields", 0, second.getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection").length())
        assertTrue("but still asks for resumption", second.has("sessionResumption"))
        s.stop()
    }

    // ---- language, vocabulary, compression -------------------------------------------------------

    @Test fun languageIsPinnedByDefaultAndFollowsTheSpeakerWhenAsked() {
        assertEquals("en-US", LiveTuning.languageToPin(false, ""))
        assertEquals("fr-FR", LiveTuning.languageToPin(false, "fr-FR"))
        assertNull(LiveTuning.languageToPin(true, "en-US"))
        server.enqueue(recording())
        val s = session(LiveTuning(autoLanguage = true)); s.start()
        assertFalse(next().getJSONObject("setup").getJSONObject("generationConfig").getJSONObject("speechConfig").has("languageCode"))
        s.stop()
    }

    @Test fun theTranscriberGetsTheNamesItWillHearCleanedAndCapped() {
        val names = listOf(" Ayanda ", "Ayanda", "x", "Strat Edge") + (1..100).map { "Project $it" }
        val v = LiveTuning(vocabulary = names).inputTranscription().getJSONArray("customVocabulary")
        assertEquals(LiveTuning.MAX_VOCAB, v.length())
        assertEquals("Ayanda", v.getString(0)); assertEquals("Strat Edge", v.getString(1))
        assertFalse((0 until v.length()).any { v.getString(it) == "x" })
        assertFalse(LiveTuning().inputTranscription().has("customVocabulary"))
    }

    @Test fun theLongConversationSetupCarriesSlidingWindowCompressionAndResumption() {
        server.enqueue(recording())
        val s = session(LiveTuning(compressionTriggerTokens = 90_000)); s.start()
        val setup = next().getJSONObject("setup")
        val c = setup.getJSONObject("contextWindowCompression")
        assertTrue(c.has("slidingWindow")); assertEquals("90000", c.getString("triggerTokens"))
        assertTrue(setup.has("sessionResumption"))
        assertTrue(setup.has("outputAudioTranscription")); assertTrue(setup.has("inputAudioTranscription"))
        s.stop()
    }

    // ---- nothing is sent while he is mid-sentence ------------------------------------------------------

    @Test fun anAnswerIsHeldWhileHeSpeaksAndSentWhenHeIsQuiet() {
        val h = TurnHold(quietMs = 1_000, maxHoldMs = 5_000)
        assertFalse("nobody speaking: goes now", h.offer("a", true, 10_000))
        h.voice(10_100)
        assertTrue(h.offer("b", true, 10_200)); assertTrue(h.offer("c", false, 10_300))
        assertTrue("still speaking", h.due(10_900).isEmpty())
        val out = h.due(11_200)
        assertEquals(listOf("b", "c"), out.map { it.text }); assertEquals(listOf(true, false), out.map { it.turnComplete })
        assertEquals(0, h.waiting)
    }

    @Test fun aHeldMessageNeverWaitsForeverAndTheQueueIsBounded() {
        val h = TurnHold(quietMs = 1_000, maxHoldMs = 3_000, limit = 3)
        h.voice(0)
        (1..5).forEach { h.offer("m$it", true, 100L * it) }
        assertEquals(3, h.waiting)
        h.voice(3_400)
        assertEquals("the oldest wait too long: out even while he talks", listOf("m3", "m4", "m5"), h.due(3_500).map { it.text })
    }

    @Test fun liveHoldsAnAnnouncementWhileHisVoiceIsComingInAndSendsItOnceHeStops() {
        server.enqueue(recording())
        val s = session()
        val sink = object : ExternalAudio { override fun play(pcm: ByteArray) {}; override fun flush() {} }
        s.openExternalAudio(sink); s.start()
        next() // setup
        Thread.sleep(300)
        val loud = ByteArray(3200) { if (it % 2 == 0) 0 else 60 } // clearly speech-level
        assertTrue(s.sendAudio(loud))
        s.announce("[The agent finished] answer")
        val msgs = ArrayList<JSONObject>()
        // the audio itself is on the wire at once; the announcement is not
        msgs += next()
        assertNull("held while he is speaking", wire.poll(600, TimeUnit.MILLISECONDS))
        val late = wire.poll(5, TimeUnit.SECONDS)
        assertNotNull("sent after he has been quiet", late)
        assertTrue(late!!.has("clientContent"))
        assertTrue(late.getJSONObject("clientContent").getBoolean("turnComplete"))
        s.stop()
    }

    // ---- answers are read as written -----------------------------------------------------------------

    private val longAnswer = (1..40).joinToString(" ") { "Item $it is done and checked, and the result was filed under the March folder." }

    @Test fun aFinishedAnswerIsFramedWithTheSpeakAsWrittenMarkerAndIsNotCutAt1500Characters() {
        assertTrue(longAnswer.length > 1500 && longAnswer.length < SpokenResult.MAX_CHARS)
        val framed = SpokenResult.frame(longAnswer)
        assertTrue(framed.startsWith(SpokenResult.HEAD)); assertTrue(framed.trimEnd().endsWith(SpokenResult.END))
        assertTrue("the whole answer is there", framed.contains("Item 40 is done"))
    }

    @Test fun aVeryLongAnswerIsCutAtASentenceAndSaysTheRestIsInTheChat() {
        val huge = (1..400).joinToString(" ") { "Sentence number $it ends here." }
        val framed = SpokenResult.frame(huge)
        assertTrue(framed.contains(SpokenResult.REST))
        assertTrue(framed.length < SpokenResult.MAX_CHARS + 200)
        assertTrue("cut at a full stop", framed.substringBefore(SpokenResult.REST).trimEnd().endsWith("."))
    }

    @Test fun markdownIsStrippedSoItCanBeReadAloud() {
        val md = "## Summary\n- **Sam** replied: see [the doc](https://x.example/a) now\n| a | b |\n|---|---|\n| 1 | 2 |\n`code`"
        val s = SpokenResult.speakable(md)
        assertFalse(s.contains("**") || s.contains("##") || s.contains("|") || s.contains("`") || s.contains("https://"))
        assertTrue(s.contains("Sam replied: see the doc now")); assertTrue(s.contains("1, 2"))
    }

    @Test fun theAgentsFinishedAnswerReachesLiveWithTheMarkerAndNothingTellsItToSummarise() {
        val c = LiveAssistCore { 1_000L }; c.connected()
        c.handOff("find the invoices", 5)
        val told = c.rows(listOf(
            ChatItem(id = 5, kind = "user", text = "x", meta = "{}"),
            ChatItem(id = 6, kind = "agent", text = longAnswer, meta = "{}")), false)
        assertEquals(1, told.size)
        assertTrue(told[0].startsWith(SpokenResult.HEAD))
        assertTrue("every detail", told[0].contains("Item 40 is done"))
        assertFalse(told[0].contains("do not read it out word for word"))
    }

    @Test fun theInstructionTellsLiveToReadMarkedAnswersAsWrittenAndToWaitForHim() {
        val p = LiveAssistCore.systemPrompt("Nova", "Sam", linked = true)
        assertTrue(p.contains(SpokenResult.HEAD)); assertTrue(p.contains(SpokenResult.END))
        assertTrue(p.contains("Never summarise a summary")); assertTrue(p.contains("Do NOT shorten it"))
        assertFalse(p.contains("say a short version"))
        assertTrue(p.contains("a pause is not the end of their turn"))
    }

    @Test fun theHandOffTellsTheAgentItsReplyIsReadAloudSoItWritesTheSpokenVersion() {
        val note = LiveAssistCore.liveNote("find the invoices")
        assertTrue(note.contains(SpokenResult.AGENT_NOTE))
        assertTrue(note.contains("read aloud word for word"))
        assertTrue("still marked as a Live hand-off", note.contains("Live voice hand-off"))
    }
}

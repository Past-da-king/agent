package com.past9.phoneaos

import com.past9.phoneaos.assist.AssistMessage
import com.past9.phoneaos.assist.LiveAssistCore
import com.past9.phoneaos.assist.LivePhase
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.voice.PlaybackQueue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live assistant's brain: what it is told, its one tool, the caption, how a hand-off comes back, and when it ends. */
class LiveAssistTest {
    private fun row(id: Long, kind: String, text: String, meta: JSONObject = JSONObject()) = ChatItem(id = id, kind = kind, text = text, meta = meta.toString())
    private var now = 1_000L
    private fun core() = LiveAssistCore { now }

    @Test fun liveHandsEverythingToTheAgentExceptMusicAndVideoWhichRunOnThePhone() {
        val decls = LiveAssistCore.tools().getJSONObject(0).getJSONArray("functionDeclarations")
        assertEquals(listOf("hand_to_agent", "answer_ask", "play_media", "media_control"), (0 until decls.length()).map { decls.getJSONObject(it).getString("name") })
        assertTrue(decls.getJSONObject(2).getJSONObject("parameters").getJSONObject("properties").has("query"))
        assertTrue(decls.getJSONObject(3).getJSONObject("parameters").getJSONObject("properties").has("action"))
        val t = decls.getJSONObject(0)
        assertEquals("hand_to_agent", t.getString("name"))
        val props = t.getJSONObject("parameters").getJSONObject("properties")
        assertTrue(props.has("request")); assertTrue(props.has("context"))
        assertEquals("request", t.getJSONObject("parameters").getJSONArray("required").getString(0))
    }

    @Test fun theInstructionsUseTheAgentsNameFromSettingsAndNothingElse() {
        val p = LiveAssistCore.systemPrompt("Nova", "Sam", linked = true)
        assertTrue(p.contains("You are Nova, Sam's personal agent"))
        assertTrue(p.contains("on their computer"))
        assertTrue(LiveAssistCore.systemPrompt("your agent", "", linked = false).contains("the user's personal agent"))
        assertTrue(LiveAssistCore.systemPrompt("your agent", "", linked = false).contains("on this phone"))
        assertTrue(p.contains("hand_to_agent"))
        // Quick questions are Live's own; it never refuses; it never approves for the user.
        assertTrue(p.contains("Answer quick questions yourself"))
        assertTrue(p.contains("NEVER say you can't"))
        assertTrue(p.contains("Never approve for them"))
    }

    @Test fun theHandOffGoesToTheAgentWithTheSheetNoteAndTheRequest() {
        val msg = LiveAssistCore.message("Send Sam a note that I am late", "He is at the station", AssistSource, null)
        assertTrue(msg.startsWith("Send Sam a note that I am late"))
        assertTrue(msg.contains("(From the voice conversation: He is at the station)"))
        assertTrue(msg.contains(AssistMessage.MARK))
        assertTrue(msg.contains("Screen: not read"))
    }

    private val AssistSource = "side_key"

    @Test fun captionGrowsInPlaceAndAChangeOfSpeakerStartsANewLine() {
        val c = core(); c.connected()
        c.caption("in", "what is the"); c.caption("in", " capital of France")
        assertEquals("what is the capital of France", c.current.you)
        c.caption("out", "Paris."); c.caption("out", " It is on the Seine.")
        assertEquals("Paris. It is on the Seine.", c.current.agent)
        c.caption("in", "thanks")
        assertEquals("thanks", c.current.you)
        assertEquals("Paris. It is on the Seine.", c.current.agent)
        assertEquals(LivePhase.LISTENING, c.current.phase)
    }

    @Test fun theCaptionKeepsOnlyTheEndOfALongSentence() {
        val long = "word ".repeat(80)
        val t = LiveAssistCore.tail(long, 160)
        assertTrue(t.length <= 162); assertTrue(t.startsWith("…"))
        assertEquals("short", LiveAssistCore.tail("short"))
    }

    @Test fun thePhaseFollowsConnectingListeningSpeaking() {
        val c = core()
        assertEquals(LivePhase.CONNECTING, c.current.phase)
        c.connected(); assertEquals(LivePhase.LISTENING, c.current.phase)
        c.speaking(true); assertEquals(LivePhase.SPEAKING, c.current.phase)
        c.speaking(false); assertEquals(LivePhase.LISTENING, c.current.phase)
        c.failed("no key"); assertEquals(LivePhase.ERROR, c.current.phase); assertEquals("no key", c.current.problem)
    }

    @Test fun anAnswerComesBackToLiveToBeSpokenOnceAndTheJobIsClosed() {
        val c = core(); c.connected()
        assertEquals("Send it", c.handOff("Send it", 5))
        assertTrue(c.handOffOpen); assertEquals(LivePhase.WORKING, c.current.phase)
        // Working, nothing yet: nothing to say.
        assertTrue(c.rows(listOf(row(5, "user", "x")), true).isEmpty())
        val told = c.rows(listOf(row(5, "user", "x"), row(6, "agent", "Done. Sam has it.")), false)
        assertEquals(1, told.size)
        assertTrue(told[0].contains("Done. Sam has it."))
        assertTrue(told[0].contains("short")); assertFalse(c.handOffOpen)
        assertEquals(LivePhase.LISTENING, c.current.phase)
        // The same row again says nothing more.
        assertTrue(c.rows(listOf(row(6, "agent", "Done. Sam has it.")), false).isEmpty())
    }

    @Test fun anOldReplyBeforeTheHandOffIsNotReadBack() {
        val c = core(); c.connected()
        c.handOff("Check my mail", 10)
        assertTrue(c.rows(listOf(row(9, "agent", "An old answer")), true).isEmpty())
        assertTrue(c.handOffOpen)
    }

    @Test fun anApprovalIsPutToTheUserAndTheCardStaysOnTheSheet() {
        val c = core(); c.connected(); c.handOff("Email the invoice", 1)
        val card = row(8, "question", "APPROVAL|Send the invoice to Sam|Invoice 12 attached", JSONObject().put("options", JSONArray(listOf("Approve", "Decline"))))
        val told = c.rows(listOf(row(1, "user", "x"), card), false)
        assertEquals(1, told.size)
        assertTrue(told[0].contains("OK")); assertTrue(told[0].contains("Do not approve it yourself"))
        assertEquals(LivePhase.NEEDS_YOU, c.current.phase)
        assertEquals(8L, c.current.card?.id)
        // The same card is not announced twice.
        assertTrue(c.rows(listOf(row(1, "user", "x"), card), false).isEmpty())
        // Once answered the card goes and the turn carries on.
        val answered = card.copy(meta = JSONObject(card.meta).put("answer", "Approve").toString())
        assertTrue(c.rows(listOf(row(1, "user", "x"), answered), true).isEmpty())
        assertNull(c.current.card)
        assertTrue(c.handOffOpen)
    }

    @Test fun aQuestionIsAskedAloudWithItsOptions() {
        val c = core(); c.connected(); c.handOff("Book a table", 1)
        val q = row(4, "question", "Which restaurant?", JSONObject().put("options", JSONArray(listOf("Luigi", "Sushi Bar"))))
        val told = c.rows(listOf(q), false)
        assertEquals(1, told.size)
        assertTrue(told[0].contains("Which restaurant?")); assertTrue(told[0].contains("Luigi, Sushi Bar"))
        assertEquals(LivePhase.NEEDS_YOU, c.current.phase)
    }

    @Test fun aFailureIsToldInOneLine() {
        val c = core(); c.connected(); c.handOff("Do it", 1)
        val told = c.rows(listOf(row(2, "notice", "The model is overloaded", JSONObject().put("error", true))), false)
        assertEquals(1, told.size); assertTrue(told[0].contains("could not do it"))
        assertFalse(c.handOffOpen)
    }

    @Test fun aFailedSendClosesTheHandOff() {
        val c = core(); c.connected(); c.handOff("Do it", 1)
        c.handOffFailed(); assertFalse(c.handOffOpen); assertEquals(LivePhase.LISTENING, c.current.phase)
    }

    @Test fun goodbyeEndsOnlyAfterTheFarewellHasBeenSaid() {
        val c = core(); c.connected()
        c.caption("in", "thanks, that's all")
        assertFalse(c.speaking(true))
        assertTrue(c.ending)
        assertTrue(c.speaking(false))
    }

    @Test fun aLongRequestThatContainsThoseWordsDoesNotEnd() {
        val c = core(); c.connected()
        c.caption("in", "tell Sam that's all I have and ask him to call me back please")
        assertFalse(c.speaking(true)); assertFalse(c.ending)
        assertFalse(c.speaking(false))
    }

    @Test fun endPhrases() {
        listOf("thanks, that's all", "That's all.", "that is all thank you", "OK thanks that's it", "goodbye", "bye bye", "we're done", "stop listening", "cheers that's all for now").forEach { assertTrue(it, LiveAssistCore.isEndPhrase(it)) }
        listOf("thanks", "what is the time", "that's all wrong, redo it", "send it to Sam and that's all the news I have today", "", "stop the music").forEach { assertFalse(it, LiveAssistCore.isEndPhrase(it)) }
    }

    @Test fun itEndsByItselfWhenQuietButNotWhileWorkOrACardIsOut() {
        val c = core(); c.connected()
        assertFalse(c.idle())
        now += LiveAssistCore.IDLE_MS - 1; assertFalse(c.idle())
        now += 2; assertTrue(c.idle())
        // Work out with the agent: not idle however long.
        val d = core(); d.connected(); d.handOff("Do it", 1)
        now += LiveAssistCore.IDLE_MS * 3; assertFalse(d.idle())
        // Speaking resets the clock.
        val e = core(); e.connected(); now += LiveAssistCore.IDLE_MS - 5; e.speaking(true); e.speaking(false)
        now += 100; assertFalse(e.idle())
    }

    @Test fun interruptingClearsWhatIsQueuedAndGoesQuiet() {
        val played = ArrayList<Int>(); var flushed = 0; val states = ArrayList<Boolean>()
        val q = PlaybackQueue(object : PlaybackQueue.Sink {
            override fun write(pcm: ByteArray) { played += pcm.size }
            override fun flush() { flushed++ }
        }, { states += it })
        q.enqueue(ByteArray(10)); q.enqueue(ByteArray(20))
        assertEquals(2, q.pending)
        q.interrupt()
        assertEquals(0, q.pending); assertEquals(1, flushed)
        q.pump(10)
        assertTrue(played.isEmpty())
    }
}

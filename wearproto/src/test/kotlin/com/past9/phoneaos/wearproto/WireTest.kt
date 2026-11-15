package com.past9.phoneaos.wearproto

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest {
    @Test fun askRoundTrips() {
        val a = Ask("a1", "what is on today", Via.SPEECH)
        assertEquals(a, Wire.parseAsk(Wire.ask(a)))
    }

    @Test fun anUnknownViaBecomesKeyboardAndAMissingIdIsRefused() {
        val raw = """{"id":"x","text":"hi","via":"carrier-pigeon"}""".toByteArray()
        assertEquals(Via.KEYBOARD, Wire.parseAsk(raw)?.via)
        assertNull(Wire.parseAsk("""{"text":"hi"}""".toByteArray()))
        assertNull(Wire.parseAsk("not json".toByteArray()))
        assertNull(Wire.parseAsk(null))
    }

    @Test fun answerAckAndHeardRoundTrip() {
        assertEquals(Answer(42, "Approve"), Wire.parseAnswer(Wire.answer(Answer(42, "Approve"))))
        assertNull(Wire.parseAnswer("""{"card":1}""".toByteArray()))
        assertNull(Wire.parseAnswer("""{"option":"Approve"}""".toByteArray()))
        assertEquals(Ack("a1", false, "no"), Wire.parseAck(Wire.ack(Ack("a1", false, "no"))))
        assertEquals(Heard("h", true, "hello"), Wire.parseHeard(Wire.heard(Heard("h", true, "hello"))))
    }

    @Test fun stateRoundTripsWithEverythingFilledIn() {
        val s = WatchState(
            agent = "Hina", link = "pc", working = true,
            turn = TurnView(Phase.ACTING, "send it", "Reading mail", "Partly done", 9, ""),
            pending = listOf(Pending(7, Pending.KIND_APPROVAL, "Send the invoice", "To the client", Pending.APPROVAL_OPTIONS, "Your agent"),
                Pending(8, Pending.KIND_QUESTION, "Which one?", "", listOf("First", "Second"), "")),
            recent = listOf(Exchange(3, "hello", "hi there", 1000L)),
        )
        assertEquals(s, Wire.parseState(Wire.state(s)))
    }

    @Test fun aStateWithNoTurnComesBackWithNoTurn() {
        val back = Wire.parseState(Wire.state(WatchState()))
        assertNull(back?.turn)
        assertEquals(WatchState(), back)
    }

    @Test fun anApprovalOnlyAcceptsApproveAndDecline() {
        val p = Pending(1, Pending.KIND_APPROVAL, "x", "", Pending.APPROVAL_OPTIONS, "")
        assertTrue(p.accepts("Approve")); assertTrue(p.accepts("Decline"))
        assertFalse(p.accepts("approve")); assertFalse(p.accepts("Delete everything"))
    }

    @Test fun clipKeepsShortTextAndEndsLongTextWithAnEllipsis() {
        assertEquals("abc", Limits.clip("abc", 5))
        val c = Limits.clip("abcdefghij", 5)
        assertEquals(5, c.length); assertTrue(c.endsWith("…"))
    }
}

class WatchRouterTest {
    private class Sink : RelaySink {
        val asks = ArrayList<Pair<String, String>>(); val answers = ArrayList<Pair<Long, String>>()
        var stops = 0; var speaks = 0; var hellos = 0
        var askResult = RelayResult(true); var answerResult = RelayResult(true)
        override suspend fun ask(text: String, via: String): RelayResult { asks += text to via; return askResult }
        override suspend fun answer(card: Long, option: String): RelayResult { answers += card to option; return answerResult }
        override suspend fun stop(): RelayResult { stops++; return RelayResult(true) }
        override suspend fun speakOnPhone(): RelayResult { speaks++; return RelayResult(true) }
        override suspend fun hello() { hellos++ }
    }

    @Test fun anAskGoesToTheAgentAndIsAcked() = runTest {
        val s = Sink(); val r = WatchRouter(s)
        val reply = r.route(WearPaths.ASK, Wire.ask(Ask("a1", "  what is next  ", Via.QUICK)))!!
        assertEquals(WearPaths.ACK, reply.path)
        assertEquals(Ack("a1", true), Wire.parseAck(reply.data))
        assertEquals(listOf("what is next" to Via.QUICK), s.asks)
    }

    @Test fun theSameAskTwiceIsSentOnce() = runTest {
        val s = Sink(); val r = WatchRouter(s)
        val msg = Wire.ask(Ask("dup", "hello"))
        r.route(WearPaths.ASK, msg); val again = r.route(WearPaths.ASK, msg)!!
        assertEquals(1, s.asks.size)
        assertTrue(Wire.parseAck(again.data)!!.ok)
    }

    @Test fun aFailedAskMayBeTriedAgainWithTheSameId() = runTest {
        val s = Sink(); val r = WatchRouter(s)
        s.askResult = RelayResult(false, "Your computer is not reachable.")
        val first = Wire.parseAck(r.route(WearPaths.ASK, Wire.ask(Ask("x", "hi")))!!.data)!!
        assertFalse(first.ok); assertEquals("Your computer is not reachable.", first.reason)
        s.askResult = RelayResult(true)
        assertTrue(Wire.parseAck(r.route(WearPaths.ASK, Wire.ask(Ask("x", "hi")))!!.data)!!.ok)
        assertEquals(2, s.asks.size)
    }

    @Test fun emptyOrUnreadableAsksNeverReachTheAgent() = runTest {
        val s = Sink(); val r = WatchRouter(s)
        assertFalse(Wire.parseAck(r.route(WearPaths.ASK, Wire.ask(Ask("e", "   ")))!!.data)!!.ok)
        assertFalse(Wire.parseAck(r.route(WearPaths.ASK, "junk".toByteArray())!!.data)!!.ok)
        assertTrue(s.asks.isEmpty())
    }

    @Test fun aVeryLongAskIsCut() = runTest {
        val s = Sink(); val r = WatchRouter(s)
        r.route(WearPaths.ASK, Wire.ask(Ask("l", "x".repeat(5000))))
        assertEquals(Limits.ASK_MAX, s.asks.single().first.length)
    }

    @Test fun anAnswerIsPassedOnAndItsRefusalIsReported() = runTest {
        val s = Sink(); val r = WatchRouter(s)
        assertEquals(Ack("card:5", true), Wire.parseAck(r.route(WearPaths.ANSWER, Wire.answer(Answer(5, "Approve")))!!.data))
        s.answerResult = RelayResult(false, "That is not waiting any more.")
        val no = Wire.parseAck(r.route(WearPaths.ANSWER, Wire.answer(Answer(6, "Approve")))!!.data)!!
        assertFalse(no.ok); assertEquals("card:6", no.id)
        assertEquals(listOf(5L to "Approve", 6L to "Approve"), s.answers)
    }

    @Test fun stopSpeakAndHelloAreRoutedAndAnUnknownPathIsIgnored() = runTest {
        val s = Sink(); val r = WatchRouter(s)
        r.route(WearPaths.STOP, null); r.route(WearPaths.SPEAK_ON_PHONE, null)
        assertNull(r.route(WearPaths.HELLO, null))
        assertNull(r.route("/somewhere/else", null))
        assertEquals(Triple(1, 1, 1), Triple(s.stops, s.speaks, s.hellos))
    }
}

package com.past9.phoneaos

import com.past9.phoneaos.assist.AssistMessage
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.wear.WatchStateBuilder
import com.past9.phoneaos.wearproto.Pending
import com.past9.phoneaos.wearproto.Phase
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

/** What the watch is shown, built from the phone's own chat rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchStateBuilderTest {
    private fun row(id: Long, kind: String, text: String, meta: JSONObject = JSONObject()) = ChatItem(id, kind, text, meta.toString(), id * 1000)
    private fun approval(id: Long, answer: String = "") = row(id, "approval", "send the invoice", JSONObject().put("summary", "send the invoice").put("details", "to the client").put("by", "Your agent").put("answer", answer))
    private fun question(id: Long, answer: String = "") = row(id, "question", "Which one?", JSONObject().put("options", JSONArray(listOf("First", "Second"))).put("answer", answer))

    @Test fun anApprovalThatWaitsReachesTheWatchWithExactlyApproveAndDecline() {
        val p = WatchStateBuilder.pending(listOf(approval(5)))
        assertEquals(1, p.size)
        assertTrue(p[0].isApproval); assertEquals(Pending.APPROVAL_OPTIONS, p[0].options)
        assertEquals("Send the invoice", p[0].title)
    }

    @Test fun aQuestionCarriesItsOwnAnswers() {
        val p = WatchStateBuilder.pending(listOf(question(6)))
        assertEquals(listOf("First", "Second"), p.single().options); assertFalse(p.single().isApproval)
    }

    @Test fun cardsThatAreAnsweredAreNotSent() {
        assertTrue(WatchStateBuilder.pending(listOf(approval(5, "Approve"), question(6, "First"))).isEmpty())
    }

    @Test fun theWatchCanOnlyAnswerAWaitingCardWithAnAnswerItOffers() {
        val rows = listOf(approval(5), question(6), approval(7, "Decline"))
        assertTrue(WatchStateBuilder.checkAnswer(rows, 5, "Approve").ok)
        assertTrue(WatchStateBuilder.checkAnswer(rows, 5, "Decline").ok)
        assertFalse(WatchStateBuilder.checkAnswer(rows, 5, "Delete everything").ok)
        assertFalse(WatchStateBuilder.checkAnswer(rows, 5, "approve").ok)
        assertTrue(WatchStateBuilder.checkAnswer(rows, 6, "Second").ok)
        assertFalse(WatchStateBuilder.checkAnswer(rows, 6, "Approve").ok)
        assertFalse(WatchStateBuilder.checkAnswer(rows, 7, "Approve").ok)
        assertFalse(WatchStateBuilder.checkAnswer(rows, 99, "Approve").ok)
    }

    @Test fun recentPairsWhatHeSaidWithTheAnswerNewestFirstAndDropsTheAgentsNote() {
        val note = AssistMessage.MARK + ": the user typed this on their watch]"
        val rows = listOf(row(1, "user", "first" + note), row(2, "agent", "one"), row(3, "activity", "tool"), row(4, "user", "second"), row(5, "agent", "two"))
        val r = WatchStateBuilder.recent(rows)
        assertEquals(listOf("second", "first"), r.map { it.you }); assertEquals(listOf("two", "one"), r.map { it.reply })
    }

    @Test fun aMessageFromAnotherAgentIsNotShownAsHis() {
        val from = JSONObject().put("via", JSONObject().put("from", "main").put("name", "Nova"))
        val rows = listOf(row(1, "user", "from a helper", from), row(2, "agent", "reply"))
        assertTrue(WatchStateBuilder.recent(rows).isEmpty())
    }

    @Test fun noTurnUntilTheWatchHasAsked() {
        assertNull(WatchStateBuilder.turn(listOf(row(1, "agent", "old")), false, null))
    }

    @Test fun theTurnFollowsTheSameRowsAsTheAssistSheet() {
        val before = listOf(row(1, "user", "old"), row(2, "agent", "old answer"))
        val follow = WatchStateBuilder.Follow(2, "what is next")
        assertEquals(Phase.THINKING, WatchStateBuilder.turn(before, true, follow)!!.phase)
        val answered = before + listOf(row(3, "user", "what is next"), row(4, "agent", "A call at 10:00."))
        val t = WatchStateBuilder.turn(answered, false, follow)!!
        assertEquals(Phase.ANSWERING, t.phase); assertEquals("A call at 10:00.", t.answer); assertEquals("what is next", t.heard)
        // The old answer is not this turn's.
        assertFalse(WatchStateBuilder.turn(before, false, follow)!!.answer == "old answer")
    }

    @Test fun aCardThatWaitsComesBeforeEverythingElse() {
        val rows = listOf(row(1, "user", "x"), approval(2))
        val t = WatchStateBuilder.turn(rows, true, WatchStateBuilder.Follow(0, "x"))!!
        assertEquals(Phase.APPROVAL, t.phase)
    }

    @Test fun composeAddsTheNoteTheChatAlreadyHidesAndCleanUserTakesItOff() {
        val c = WatchStateBuilder.compose("  hello  ", "speech")
        assertTrue(c.startsWith("hello" + AssistMessage.MARK)); assertTrue(c.contains("said"))
        assertEquals("hello", WatchStateBuilder.cleanUser(c))
        assertTrue(WatchStateBuilder.compose("hi", "keyboard").contains("typed"))
    }

    @Test fun buildFillsEverythingTheWatchDraws() {
        val s = WatchStateBuilder.build(listOf(approval(2)), true, null, "Nova", "pc")
        assertEquals("Nova", s.agent); assertEquals("pc", s.link); assertTrue(s.working); assertEquals(1, s.pending.size)
    }
}

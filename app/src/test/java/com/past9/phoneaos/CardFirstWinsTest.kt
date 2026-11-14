package com.past9.phoneaos

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.ui.LocalSheetPreview
import com.past9.phoneaos.ui.screens.Approval
import com.past9.phoneaos.ui.screens.ApprovalCard
import com.past9.phoneaos.ui.screens.CardConflict
import com.past9.phoneaos.ui.theme.AppTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A card is decided once on the computer: a second, different answer is refused with a plain sentence the card shows. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CardFirstWinsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun readsAnApprovalThatWasAlreadyDecided() {
        val c = CardConflict.parse(RuntimeException("already decided: approve by Pixel 9"))!!
        assertEquals("Already decided: approve by Pixel 9.", c.text)
        assertEquals("Approve", c.answer)
        assertEquals("Decline", CardConflict.parse(RuntimeException("already decided: decline by desktop"))!!.answer)
    }

    @Test fun readsAQuestionThatWasAlreadyAnswered() {
        val c = CardConflict.parse(RuntimeException("Already answered: Tuesday by desktop"))!!
        assertEquals("Already answered: Tuesday by desktop.", c.text)
        assertEquals("Tuesday", c.answer)
    }

    @Test fun findsItInsideTheRpcWrapper() {
        val c = CardConflict.parse(RuntimeException("Error invoking remote method 'agent:chat.decide': Error: already decided: approve by Pixel 9"))
        assertEquals("Already decided: approve by Pixel 9.", c?.text)
    }

    @Test fun otherErrorsAreNotConflicts() {
        assertNull(CardConflict.parse(RuntimeException("Not connected to a computer right now.")))
        assertNull(CardConflict.parse(RuntimeException("Timed out")))
        assertNull(CardConflict.parse(RuntimeException("not already decided at all")))
        assertNull(CardConflict.parse(RuntimeException(null as String?)))
    }

    @Test fun aConflictTurnsTheCardIntoTheWinningDecisionWithNoFailure() {
        val meta = JSONObject().put("answer", "Decline").put(Approval.FAILED_KEY, true)
        assertTrue(CardConflict.applyTo(meta, RuntimeException("already decided: approve by Pixel 9")))
        assertEquals("Approve", meta.getString("answer"))
        assertFalse(meta.has(Approval.FAILED_KEY))
        assertEquals("Already decided: approve by Pixel 9.", meta.getString(Approval.NOTE_KEY))
    }

    @Test fun anOrdinaryFailureLeavesTheMetaAlone() {
        val meta = JSONObject().put("answer", "Approve")
        assertFalse(CardConflict.applyTo(meta, RuntimeException("Not connected")))
        assertEquals("Approve", meta.getString("answer"))
        assertFalse(meta.has(Approval.NOTE_KEY))
    }

    @Test fun aConflictWithNoReadableWinnerFreesTheCardAndKeepsTheNote() {
        val meta = JSONObject().put("answer", "Decline")
        assertTrue(CardConflict.applyTo(meta, RuntimeException("already answered")))
        assertFalse(meta.has("answer"))
        assertEquals("Already answered.", meta.getString(Approval.NOTE_KEY))
    }

    @Test fun theCardCarriesTheNote() {
        val a = Approval.of(ChatItem(id = 1, kind = "approval", text = "x", meta = JSONObject().put("summary", "Send it").put("answer", "Approve").put(Approval.NOTE_KEY, "Already decided: approve by Pixel 9.").toString()))
        assertEquals("Already decided: approve by Pixel 9.", a.note)
        assertFalse(a.failed)
    }

    @Test fun theNoteIsShownOnTheDecidedCard() {
        val item = ChatItem(id = 1, kind = "approval", text = "x", meta = JSONObject().put("summary", "Send the email").put("answer", "Approve").put(Approval.NOTE_KEY, "Already decided: approve by Pixel 9.").toString())
        rule.setContent { AppTheme(dark = false) { CompositionLocalProvider(LocalSheetPreview provides true) { ApprovalCard(item) { _, _ -> } } } }
        rule.onNodeWithText("Already decided: approve by Pixel 9.").assertIsDisplayed()
    }

    @Test fun theNoteIsShownOnAPendingCardInsteadOfTheFailureLine() {
        val item = ChatItem(id = 1, kind = "approval", text = "x", meta = JSONObject().put("summary", "Send the email").put(Approval.NOTE_KEY, "Already answered.").toString())
        rule.setContent { AppTheme(dark = false) { CompositionLocalProvider(LocalSheetPreview provides true) { ApprovalCard(item) { _, _ -> } } } }
        rule.onNodeWithText("Already answered.").assertIsDisplayed()
    }
}

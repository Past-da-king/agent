package com.past9.phoneaos

import com.past9.phoneaos.ui.ReplyDrafts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The reply box under an answer card keeps what he typed and the photo he picked when the card leaves the list
 * and comes back (keyboard, scrolling, the photo picker, the project chat and back). Real phone 29 Oct 2026: the
 * photo vanished and the box closed.
 */
@RunWith(RobolectricTestRunner::class)
class ReplyDraftsTest {
    @Test fun theSameCardGetsTheSameBoxBack() {
        val a = ReplyDrafts.box("card-1")
        a.draft = "test reply"; a.attachments.add("/data/pic.jpg")
        ReplyDrafts.setOpen("card-1", true)
        // The list item is thrown away and made again: it asks for its box by the card's id.
        val again = ReplyDrafts.box("card-1")
        assertSame(a, again)
        assertEquals("test reply", again.draft)
        assertEquals(listOf("/data/pic.jpg"), again.attachments.toList())
        assertTrue(ReplyDrafts.isOpen("card-1"))
        ReplyDrafts.sent("card-1")
    }

    @Test fun sentEmptiesAndClosesIt() {
        val a = ReplyDrafts.box("card-2"); a.draft = "x"; ReplyDrafts.setOpen("card-2", true)
        ReplyDrafts.sent("card-2")
        assertFalse(ReplyDrafts.isOpen("card-2"))
        val b = ReplyDrafts.box("card-2")
        assertNotSame(a, b)
        assertEquals("", b.draft)
    }

    @Test fun cardsDoNotShare() {
        ReplyDrafts.box("card-3").draft = "three"
        assertEquals("", ReplyDrafts.box("card-4").draft)
        ReplyDrafts.sent("card-3"); ReplyDrafts.sent("card-4")
    }
}

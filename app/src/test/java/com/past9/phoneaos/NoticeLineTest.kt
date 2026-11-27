package com.past9.phoneaos

import com.past9.phoneaos.remote.DiskFull
import com.past9.phoneaos.remote.NoticeLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 27 Nov 2026: a computer whose disk was full refused message after message, and every refusal wrote another line
 * saying the same thing. One line with a count now stands in for all of them.
 */
class NoticeLineTest {
    private var clock = 1_000L
    private val line = NoticeLine(now = { clock })

    @Test fun theFirstTimeIsANewLineWithNoCount() {
        assertNull(line.lineFor("Couldn't send that to your computer: database or disk is full."))
    }

    @Test fun theSameWordsAgainFoldIntoTheLineAlreadyThere() {
        line.lineFor("Couldn't send that to your computer: database or disk is full. Your words are still in the message box.")
        clock += 5_000
        assertEquals("Couldn't send that to your computer: database or disk is full. Your words are still in the message box. (×2)", line.lineFor("Couldn't send that to your computer: database or disk is full. Your words are still in the message box."))
        clock += 5_000
        assertEquals("Couldn't send that to your computer: database or disk is full. Your words are still in the message box. (×3)", line.lineFor("Couldn't send that to your computer: database or disk is full. Your words are still in the message box."))
    }

    @Test fun differentWordsGetTheirOwnLine() {
        line.lineFor("first")
        assertNull(line.lineFor("second"))
        assertEquals("second (×2)", line.lineFor("second"))
    }

    @Test fun theSameWordsLongAfterwardsAreANewLineAgain() {
        line.lineFor("disk is full")
        clock += NoticeLine.REPEAT_MS + 1
        assertNull(line.lineFor("disk is full"))
    }

    @Test fun aForgottenLineStartsFresh() {
        line.lineFor("disk is full")
        line.reset()
        assertNull(line.lineFor("disk is full"))
    }

    @Test fun onlyAFullDiskCountsAsAFullDisk() {
        assertTrue(DiskFull.isIn("ENOSPC: no space left on device, write"))
        assertTrue(DiskFull.isIn("database or disk is full"))
        assertTrue(DiskFull.isIn("SQLITE_FULL: database or disk is full"))
        assertTrue(DiskFull.isIn("Couldn't close Mthashana on your computer: database or disk is full"))
        assertTrue(DiskFull.isIn("There is not enough space on the disk."))
        // These are the computer answering no, and must not move his message to another computer.
        assertFalse(DiskFull.isIn("A bad send id."))
        assertFalse(DiskFull.isIn("That row cannot be set."))
        assertFalse(DiskFull.isIn("None of your computers answered for 10 minutes"))
        assertFalse(DiskFull.isIn(null))
        assertFalse(DiskFull.isIn(""))
    }
}

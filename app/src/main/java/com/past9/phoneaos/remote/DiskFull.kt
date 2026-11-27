package com.past9.phoneaos.remote

/**
 * A brain that says its disk is full.
 *
 * 27 Nov 2026: the computer's drive filled up and it answered every message with ENOSPC or SQLite's "database or disk
 * is full". The phone read that as the computer refusing the message ("the computer said no: that is an answer"), kept
 * his words in the box and showed the same error again and again, while the impetix server was healthy and idle.
 *
 * A full disk is the computer failing, not the message being wrong: it cannot store a message however many times it is
 * asked, so the send is carried to the next brain in the order instead of stopping at this one.
 */
internal object DiskFull {
    /**
     * What a full drive looks like from here: the phone's own upload, the computer's write, SQLite's words, Windows'
     * words. Nothing else counts: a bad send id or a refused row is an answer, and must not move the message elsewhere.
     */
    private val signs = listOf(
        "enospc",
        "sqlite_full",
        "database or disk is full",
        "no space left on device",
        "disk is full",
        "not enough space on the disk",
        "insufficient storage",
    )

    fun isIn(message: String?): Boolean {
        val text = message?.lowercase() ?: return false
        return signs.any { text.contains(it) }
    }
}

package com.past9.phoneaos.system

/**
 * Whether the chat itself is in front of the user right now.
 *
 * Issue #15 (5 Nov 2026). A reply notification is only worth a buzz when the chat is NOT being watched, and buzzing
 * the phone for a message the user is reading is noise, so the one decision left to make is answered here from two
 * facts the app already has: is the app resumed (a real person looking at it, not the lock screen or another app), and
 * is the screen on top the chat. MainActivity keeps [resumed] true between onResume and onPause; the navigation graph
 * keeps [route] at the screen showing ("chat", "home", "settings" and so on).
 *
 * Read from whichever thread is posting the notification, so both are volatile.
 */
object ChatOnScreen {
    /** A person is looking at the app. */
    @Volatile var resumed = false
    /** The screen on top, as the navigation graph names it. */
    @Volatile var route: String? = null

    /** The assist sheet is up over another app: it shows the reply itself. */
    @Volatile var assistShowing = false

    /** The chat is open in front of the user, so a reply that lands now is already being read. */
    fun showing(): Boolean = (resumed && route == "chat") || assistShowing
}

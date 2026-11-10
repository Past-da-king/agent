package com.past9.phoneaos

import com.past9.phoneaos.screen.ScreenLanding
import com.past9.phoneaos.screen.ScreenLanding.Decision
import com.past9.phoneaos.screen.ScreenLanding.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenLandingTest {
    private val chrome = Task(1, 0, "com.android.chrome", true)
    private val gmailRecent = Task(2, 0, "com.google.android.gm", false)
    private val gm = "com.google.android.gm"

    @Test fun refusesTheAppThePersonHasInFront() {
        assertEquals(Decision.Refuse(gm), ScreenLanding.before(listOf(Task(2, 0, gm, true), chrome.copy(visible = false)), gm))
        assertTrue(ScreenLanding.before(listOf(chrome, gmailRecent), gm) is Decision.Leave)
        // The same app on the hidden display is ours to use.
        assertTrue(ScreenLanding.before(listOf(chrome, Task(2, 7, gm, true)), gm) is Decision.Leave)
    }

    @Test fun leavesALaunchThatLandedOnTheHiddenDisplay() {
        assertTrue(ScreenLanding.after(listOf(chrome), listOf(chrome, Task(9, 7, gm, true)), gm) is Decision.Leave)
    }

    @Test fun movesAndRestoresWhenTheAppCameUpOnTheirScreen() {
        val after = listOf(Task(2, 0, gm, true), chrome.copy(visible = false))
        assertEquals(Decision.Fix(listOf(2), 1), ScreenLanding.after(listOf(chrome, gmailRecent), after, gm))
    }

    @Test fun movesAStrayThatStayedBehindWithoutTouchingTheFront() {
        // Still only in their recents: it goes to the hidden display, and what they have in front is not touched.
        assertEquals(Decision.Fix(listOf(2), null), ScreenLanding.after(listOf(chrome, gmailRecent), listOf(chrome, gmailRecent), gm))
    }

    @Test fun neverMovesWhatThePersonOpenedThemselves() {
        // They switched to another app while ours was starting: that is theirs.
        val maps = Task(5, 0, "com.google.android.apps.maps", true)
        assertTrue(ScreenLanding.after(listOf(chrome), listOf(maps, chrome.copy(visible = false), Task(9, 7, gm, true)), gm) is Decision.Leave)
    }
}

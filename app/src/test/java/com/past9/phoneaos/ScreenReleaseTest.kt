package com.past9.phoneaos

import com.past9.phoneaos.screen.ScreenRelease
import com.past9.phoneaos.screen.ScreenRelease.Row
import com.past9.phoneaos.screen.ScreenRelease.Step
import com.past9.phoneaos.screen.helper.Flags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenReleaseTest {
    private val mine = Row(10, 7, "com.example.player")
    private val mine2 = Row(11, 7, "com.example.notes")
    private val theirs = Row(1, 0, "com.example.front")
    private val other = Row(5, 3, "com.example.cast")

    @Test fun everyTaskIsRemovedBeforeTheDisplayIsReleased() {
        val plan = ScreenRelease.plan(listOf(theirs, mine, other, mine2), 7, emptySet())
        assertEquals(listOf(Step.Remove(10), Step.Remove(11), Step.ReleaseDisplay), plan)
        assertEquals(Step.ReleaseDisplay, plan.last())
    }

    @Test fun nothingIsMovedToThePersonsScreenUnlessKept() {
        val plan = ScreenRelease.plan(listOf(mine, mine2), 7, emptySet())
        assertTrue(plan.none { it is Step.Keep })
        val kept = ScreenRelease.plan(listOf(mine, mine2), 7, setOf("com.example.notes"))
        assertEquals(listOf(Step.Remove(10), Step.Keep(11), Step.ReleaseDisplay), kept)
    }

    @Test fun neverTouchesThePersonsOwnDisplayOrAnotherOne() {
        val plan = ScreenRelease.plan(listOf(theirs, other), 7, emptySet())
        assertEquals(listOf<Step>(Step.ReleaseDisplay), plan)
        assertTrue(ScreenRelease.plan(listOf(theirs), 0, emptySet()).isEmpty())
    }

    @Test fun orphansOfAnEarlierRunAreFoundAndOnlyOurs() {
        val rows = listOf(theirs, mine, other, mine2)
        // Display 7 is ours and is not the current one; 3 is somebody else's; 0 is the person's.
        assertEquals(listOf(10, 11), ScreenRelease.orphans(rows, setOf(7), null))
        assertEquals(listOf(10, 11), ScreenRelease.orphans(rows, setOf(7), 9))
        assertTrue(ScreenRelease.orphans(rows, setOf(7), 7).isEmpty())
        assertTrue(ScreenRelease.orphans(rows, emptySet(), null).isEmpty())
    }

    @Test fun cleansUpOnlyAfterTheAppHasBeenGoneForTheWholeGraceTime() {
        val w = ScreenRelease.ClientWatch(6_000)
        assertFalse(w.due(1_000, true))          // nobody ever connected
        w.came(); w.came()
        w.went(10_000)
        assertFalse(w.due(99_000, true))         // one connection is still open
        w.went(20_000)
        assertFalse(w.due(25_999, true))         // gone, but inside the grace time
        assertTrue(w.due(26_000, true))
        assertFalse(w.due(26_000, false))        // nothing to clean
    }

    @Test fun anAppThatComesBackInTimeKeepsItsDisplay() {
        val w = ScreenRelease.ClientWatch(6_000)
        w.came(); w.went(1_000)
        w.came()
        assertFalse(w.due(60_000, true))
        w.went(61_000)
        assertFalse(w.due(62_000, true))
        w.reset()
        assertFalse(w.due(900_000, true))
    }

    @Test fun onlyATakeOverReasonMayMoveAnythingToThePersonsScreen() {
        assertTrue(ScreenRelease.mayMoveToMain("take_over"))
        assertTrue(ScreenRelease.mayMoveToMain("bring_to_screen"))
        assertTrue(ScreenRelease.mayMoveToMain("keep_app"))
        assertFalse(ScreenRelease.mayMoveToMain(""))
        assertFalse(ScreenRelease.mayMoveToMain("close"))
        assertFalse(ScreenRelease.mayMoveToMain("app_gone"))
    }

    @Test fun everyDisplayFlagSetTriesDestroyContentFirstAndKeepsAFallback() {
        for (sdk in listOf(31, 33, 34, 36)) for (locked in listOf(false, true)) {
            val l = Flags.ladder(sdk, locked)
            val withIt = l.filter { it and Flags.DESTROY_CONTENT_ON_REMOVAL != 0 }
            val without = l.filter { it and Flags.DESTROY_CONTENT_ON_REMOVAL == 0 }
            assertTrue(withIt.isNotEmpty() && without.isNotEmpty())
            assertEquals(l.take(withIt.size), withIt)
            // The same sets without the flag are the fallback.
            assertEquals(withIt.map { it and Flags.DESTROY_CONTENT_ON_REMOVAL.inv() }, without)
        }
    }
}
package com.past9.phoneaos

import com.past9.phoneaos.triggers.ReminderPlan
import com.past9.phoneaos.triggers.ReminderPlan.Due
import org.junit.Assert.*
import org.junit.Test

class ReminderPlanTest {
    private val now = 1_000_000_000_000L
    private val h = 3_600_000L

    @Test fun armsFutureDueTimesOnce() {
        val p = ReminderPlan.plan(listOf(Due(1, "Call Lee", now + h), Due(2, "Pay rent", now + 2 * h)), emptyMap(), emptySet(), now)
        assertEquals(mapOf(1L to now + h, 2L to now + 2 * h), p.arm)
        val again = ReminderPlan.plan(listOf(Due(1, "Call Lee", now + h)), p.arm, emptySet(), now)
        assertTrue("already armed: nothing to do", again.arm.isEmpty())
        assertEquals("task 2 done or deleted: its alarm is cancelled", setOf(2L), again.cancel)
    }
    @Test fun aMovedDueTimeIsArmedAgain() {
        val p = ReminderPlan.plan(listOf(Due(1, "Call Lee", now + 3 * h)), mapOf(1L to now + h), emptySet(), now)
        assertEquals(mapOf(1L to now + 3 * h), p.arm)
    }
    @Test fun afterARebootEverythingIsArmedAgain() {
        val armed = mapOf(1L to now + h)
        assertTrue(ReminderPlan.plan(listOf(Due(1, "x", now + h)), armed, emptySet(), now).arm.isEmpty())
        assertEquals(armed, ReminderPlan.plan(listOf(Due(1, "x", now + h)), armed, emptySet(), now, force = true).arm)
    }
    @Test fun aMissedReminderIsSaidOnceAndOnlyIfRecent() {
        val tasks = listOf(Due(1, "Check in for flight", now - h), Due(2, "Old one", now - 30 * h))
        val p = ReminderPlan.plan(tasks, emptyMap(), emptySet(), now)
        assertEquals(listOf(1L), p.missed.map { it.id })
        assertTrue(ReminderPlan.plan(tasks, emptyMap(), setOf(ReminderPlan.key(1, now - h)), now).missed.isEmpty())
    }
}

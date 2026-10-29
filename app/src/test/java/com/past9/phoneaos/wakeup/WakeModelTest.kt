package com.past9.phoneaos.wakeup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's copy of the wake-up rules (desktop shared/wakeups.ts) and the snapshot it reads, on a fake clock. */
class WakeModelTest {
    private val t0 = 1_793_000_000_000L
    private fun wake(id: String = "w1", severity: String = "urgent", ringAt: Long = t0, targets: List<String> = emptyList(), seen: Set<String> = emptySet(), cancelled: Boolean = false) =
        Wake(id, severity, "Standup", "Join it", "https://x.test/j", "meeting", ringAt, cancelled, targets, seen, "routine")
    private fun snap(vararg w: Wake) = WakeSnapshot(w.toList(), 5, "", "pc")

    @Test fun ringsFromItsTimeAndNeverStopsByItself() {
        val s = snap(wake(ringAt = t0 + 60_000, targets = listOf("phone")))
        assertTrue(WakeRules.ringing(s, "phone", t0, false, emptySet()).isEmpty())
        assertEquals(1, WakeRules.ringing(s, "phone", t0 + 60_000, false, emptySet()).size)
        assertEquals(1, WakeRules.ringing(s, "phone", t0 + 9 * 3_600_000, false, emptySet()).size)
    }

    @Test fun answeredHereStopsAtOnce() {
        val s = snap(wake(severity = "critical"))
        assertTrue(WakeRules.ringing(s, "phone", t0 + 1, false, setOf("w1")).isEmpty())
    }

    @Test fun urgentJoinsAfter60sAndHeadsUpNever() {
        val u = snap(wake(targets = listOf("pc"), seen = setOf("pc")))
        assertTrue(WakeRules.ringing(u, "phone", t0 + 59_000, false, emptySet()).isEmpty())
        assertEquals(1, WakeRules.ringing(u, "phone", t0 + 60_000, false, emptySet()).size)
        val h = snap(wake(severity = "heads_up", targets = listOf("pc"), seen = setOf("pc")))
        assertTrue(WakeRules.ringing(h, "phone", t0 + 600_000, false, emptySet()).isEmpty())
    }

    @Test fun aDeviceThatNeverGotItMakesEveryoneRingAfter30s() {
        val s = snap(wake(severity = "heads_up", targets = listOf("pc")))
        assertTrue(WakeRules.ringing(s, "phone", t0 + 29_000, false, emptySet()).isEmpty())
        assertEquals(1, WakeRules.ringing(s, "phone", t0 + 30_000, false, emptySet()).size)
    }

    @Test fun headsUpIsSilentInQuietHoursCriticalIsNot() {
        val h = WakeRules.ringing(snap(wake(severity = "heads_up")), "phone", t0, true, emptySet()).single()
        assertEquals(false, h.sound)
        val c = WakeRules.ringing(snap(wake(severity = "critical")), "phone", t0, true, emptySet()).single()
        assertEquals(true, c.sound)
        assertTrue(WakeRules.inQuiet("22:00-07:00", 23 * 60))
        assertTrue(!WakeRules.inQuiet("22:00-07:00", 12 * 60))
    }

    @Test fun mostSevereFirst() {
        val s = snap(wake("a", "heads_up"), wake("b", "critical"), wake("c", "urgent"))
        assertEquals(listOf("b", "c", "a"), WakeRules.ringing(s, "phone", t0, false, emptySet()).map { it.wake.id })
    }

    @Test fun nextChangeIsTheBookingThenTheEscalation() {
        val s = snap(wake(ringAt = t0 + 10_000, targets = listOf("pc")))
        assertEquals(t0 + 10_000, WakeRules.nextChange(s, t0, emptySet()))
        assertEquals(t0 + 10_000 + 30_000, WakeRules.nextChange(s, t0 + 11_000, emptySet()))
        assertNull(WakeRules.nextChange(s, t0 + 11_000, setOf("w1")))
    }

    @Test fun snapshotMovesTimesOntoThePhoneClockAndKeepsCancelled() {
        val wire = JSONObject().put("now", 1_000_000L).put("from", "pc").put("snoozeMinutes", 10).put("quietHours", "22:00-07:00")
            .put("wakes", JSONArray().put(JSONObject().put("id", "w9").put("severity", "critical").put("title", "Cancelled: Board").put("whatToDo", "Answer")
                .put("ringAt", 1_060_000L).put("cancelled", true).put("targets", JSONArray().put("phone")).put("seen", JSONObject().put("pc", 5)).put("link", "")))
        val s = WakeSnapshot.fromWire(wire, 2_000_000L)!!
        assertEquals(2_060_000L, s.wakes.single().ringAt)
        assertEquals(setOf("pc"), s.wakes.single().seen)
        assertEquals("Cancelled: Board", WakeRules.shownTitle(s.wakes.single()))
        assertEquals(10, s.snoozeMinutes)
        val back = WakeSnapshot.fromLocal(s.toJson().toString())
        assertEquals(s, back)
    }

    @Test fun coloursAreTheAppTokens() {
        assertEquals(0xFFB3261EL, WakeRules.colour("critical"))
        assertEquals(0xFF8A5100L, WakeRules.colour("urgent"))
        assertEquals(0xFF1E6B3AL, WakeRules.colour("heads_up"))
    }
}

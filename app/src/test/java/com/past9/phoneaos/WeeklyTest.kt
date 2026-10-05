package com.past9.phoneaos

import com.past9.phoneaos.data.TriggerRow
import com.past9.phoneaos.triggers.Routines
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class WeeklyTest {
    private val mon = ZonedDateTime.of(2026, 10, 5, 19, 0, 0, 0, ZoneId.of("Africa/Johannesburg")) // Monday 19:00

    @Test fun parses() {
        assertEquals(Triple(setOf(7), 18, 0), Routines.parseWeekly("SUN 18:00"))
        assertEquals(Triple(setOf(1, 3, 5), 7, 30), Routines.parseWeekly("mon,wed,fri 07:30"))
        assertNull(Routines.parseWeekly("18:00"))
        assertNull(Routines.parseWeekly("XYZ 18:00"))
    }
    @Test fun nextSunday() {
        val ms = Routines.nextRunIn(TriggerRow(name = "Plan", kind = "weekly", spec = "SUN 18:00", prompt = ""), mon)!!
        assertEquals(mon.plusDays(6).withHour(18), mon.plusNanos(ms * 1_000_000))
    }
    @Test fun laterToday() {
        val ms = Routines.nextRunIn(TriggerRow(name = "x", kind = "weekly", spec = "MON 20:00", prompt = ""), mon)!!
        assertEquals(3_600_000L, ms)
    }
    @Test fun labels() {
        assertEquals("Every Sunday at 18:00", Routines.weeklyLabel("SUN 18:00"))
        assertEquals("Every weekday at 07:30", Routines.weeklyLabel("MON,TUE,WED,THU,FRI 07:30"))
    }
}

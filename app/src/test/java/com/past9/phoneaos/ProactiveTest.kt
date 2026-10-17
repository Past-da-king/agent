package com.past9.phoneaos

import com.past9.phoneaos.triggers.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The proactive layer's rules: no reason, no wake; cheap checks first; nearby wakes merged; quiet hours. */
class ProactiveTest {
    private val zone = ZoneId.of("Africa/Johannesburg")
    private fun at(h: Int, m: Int = 0, day: Int = 19) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone)
    private fun ms(z: ZonedDateTime) = z.toInstant().toEpochMilli()
    private val quiet = QuietHours(LocalTime.of(23, 0), LocalTime.of(7, 0))

    /** Fakes for everything the engine touches. */
    private class Rig(var quiet: QuietHours = QuietHours(LocalTime.of(23, 0), LocalTime.of(7, 0))) {
        val wakes = linkedMapOf<Long, Wake>(); var nextId = 1L
        val probeResults = mutableMapOf<String, Probe>()   // by reason
        val agentPrompts = mutableListOf<String>()
        val told = mutableListOf<String>()
        var armed: Long? = -1
        val store = object : WakeStore {
            override suspend fun all() = wakes.values.toList()
            override suspend fun save(w: Wake): Long { val id = if (w.id > 0) w.id else nextId++; wakes[id] = w.copy(id = id); return id }
            override suspend fun delete(id: Long) { wakes.remove(id) }
            val doneKeys = mutableSetOf<String>()
            override suspend fun done() = doneKeys.toSet()
            override suspend fun markDone(key: String) { doneKeys += key }
        }
        val engine = WakeEngine(store,
            object : WakeProbes { override suspend fun check(w: Wake, now: Long) = probeResults[w.reason] ?: Probe.Found("") },
            object : WakeAgent { override suspend fun run(title: String, prompt: String): String { agentPrompts += prompt; return "QUIET" } },
            object : WakeNotifier { override fun tell(title: String, body: String) { told += title } },
            object : WakeAlarm { override fun arm(at: Long?) { armed = at } }) { quiet }
    }
    private fun wake(reason: String, t: ZonedDateTime, check: Check = Check(Check.NOTIFICATION, "Gmail", listOf("Brightwater")), urgent: Boolean = false, retry: Int = 0, max: Int = 1) =
        Wake(reason = reason, job = "Do the thing for $reason", at = ms(t), check = check, urgent = urgent, retryMinutes = retry, maxTries = max)

    // ---- quiet hours ----
    @Test fun quietHoursWrapMidnight() {
        assertTrue(quiet.isQuiet(LocalTime.of(23, 30))); assertTrue(quiet.isQuiet(LocalTime.of(6, 59)))
        assertFalse(quiet.isQuiet(LocalTime.of(7, 0))); assertFalse(quiet.isQuiet(LocalTime.of(22, 59)))
        assertEquals(at(7, 0, 20), quiet.nextLoud(at(23, 30)))
        assertEquals(at(12), quiet.nextLoud(at(12)))
        assertFalse(QuietHours.parse("off")!!.isQuiet(LocalTime.of(3, 0)))
        assertEquals(QuietHours(LocalTime.of(0, 0), LocalTime.of(7, 0)), QuietHours.parse("00:00-07:00"))
        assertNull(QuietHours.parse("late"))
    }

    // ---- no reason, no wake ----
    @Test fun noReasonNoWake() = runBlocking {
        val r = Rig()
        assertNotNull(r.engine.schedule(wake("", at(10)), ms(at(9))).second)
        assertNotNull(r.engine.schedule(wake("Brightwater reply", at(10)).copy(job = ""), ms(at(9))).second)
        assertNotNull(r.engine.schedule(wake("Too late", at(8)), ms(at(9))).second)
        assertTrue(r.wakes.isEmpty())
    }
    @Test fun nothingScheduledMeansNoAlarmAndNoAi() = runBlocking {
        val r = Rig()
        assertNull(r.engine.rearm(at(9)))
        assertNull(r.armed)
        val rep = r.engine.fire(at(9))
        assertEquals(0, rep.agentCalls); assertTrue(r.agentPrompts.isEmpty()); assertNull(rep.nextAt)
    }

    // ---- cheap checks first ----
    @Test fun checkThatFindsNothingNeverCallsTheAi() = runBlocking {
        val r = Rig()
        r.engine.schedule(wake("Brightwater reply", at(9), retry = 60, max = 3), ms(at(8)))
        r.probeResults["Brightwater reply"] = Probe.Nothing
        val first = r.engine.fire(at(9))
        assertEquals(0, first.agentCalls); assertEquals(1, first.retried)
        assertEquals(ms(at(10)), r.wakes.values.single().at)            // looks again in 60 min
        r.engine.fire(at(10))
        assertEquals(ms(at(12)), r.wakes.values.single().at)            // then 120 min later (backs off)
        r.engine.fire(at(12))
        assertTrue("gives up after max tries", r.wakes.isEmpty())
        assertTrue(r.agentPrompts.isEmpty())
        assertNull(r.armed)
    }
    @Test fun goneReasonIsDroppedWithoutAi() = runBlocking {
        val r = Rig()
        r.engine.schedule(wake("Chase CV", at(9), Check(Check.TASK_OPEN, taskId = 4)), ms(at(8)))
        r.probeResults["Chase CV"] = Probe.Gone
        val rep = r.engine.fire(at(9))
        assertEquals(0, rep.agentCalls); assertEquals(1, rep.dropped); assertTrue(r.wakes.isEmpty())
    }
    @Test fun foundWakesTheAiOnceWithReasonAndFinding() = runBlocking {
        val r = Rig()
        r.engine.schedule(wake("Brightwater reply", at(9)), ms(at(8)))
        r.probeResults["Brightwater reply"] = Probe.Found("Gmail: Lee Morgan: Re: Brightwater")
        val rep = r.engine.fire(at(9))
        assertEquals(1, rep.agentCalls)
        val p = r.agentPrompts.single()
        assertTrue(p.contains("Reason: Brightwater reply")); assertTrue(p.contains("Found: Gmail: Lee Morgan")); assertTrue(p.contains("QUIET"))
        assertTrue(r.wakes.isEmpty())
    }
    @Test fun headsUpIsCodeOnly() = runBlocking {
        val r = Rig()
        r.engine.schedule(Wake(reason = "Dana interview starts soon", job = "", at = ms(at(14, 30)), check = Check(Check.HEADS_UP), urgent = true, since = ms(at(15))), ms(at(9)))
        r.probeResults["Dana interview starts soon"] = Probe.Tell("Dana interview", "Starts in 30 min")
        val rep = r.engine.fire(at(14, 30))
        assertEquals(0, rep.agentCalls); assertEquals(listOf("Dana interview"), r.told)
    }

    // ---- nearby wakes merged ----
    @Test fun nearbyWakesShareOneAlarmAndOneAiRun() = runBlocking {
        val r = Rig()
        r.engine.schedule(wake("A", at(10, 0)), ms(at(8)))
        r.engine.schedule(wake("B", at(10, 6)), ms(at(8)))
        r.engine.schedule(wake("C", at(10, 30)), ms(at(8)))
        assertEquals("slides to the last one within 10 min", ms(at(10, 6)), r.engine.rearm(at(8)))
        assertEquals(listOf(2, 1), WakePlan.clusters(r.wakes.values.toList(), r.quiet, zone).map { it.size })
        val rep = r.engine.fire(at(10, 6))
        assertEquals(2, rep.ran.size); assertEquals(1, rep.agentCalls)
        assertTrue(r.agentPrompts.single().contains("Reason: A") && r.agentPrompts.single().contains("Reason: B"))
        assertEquals(listOf("C"), r.wakes.values.map { it.reason })
        assertEquals(ms(at(10, 30)), r.armed)
    }
    @Test fun anUrgentWakeIsNotDelayedToMerge() = runBlocking {
        val r = Rig()
        r.engine.schedule(wake("Urgent", at(10, 0), urgent = true), ms(at(8)))
        r.engine.schedule(wake("Later", at(10, 8)), ms(at(8)))
        assertEquals(ms(at(10, 0)), r.engine.rearm(at(8)))
    }
    @Test fun aWakeDueSoonIsPulledIntoTheCurrentRun() = runBlocking {
        val r = Rig()
        r.engine.schedule(wake("Now", at(10, 0)), ms(at(8)))
        r.engine.schedule(wake("Soon", at(10, 9)), ms(at(8)))
        assertEquals(2, r.engine.fire(at(10, 0)).ran.size)
        assertEquals(1, r.agentPrompts.size)
    }

    // ---- quiet hours ----
    @Test fun quietHoursHoldBackAllButUrgent() = runBlocking {
        val r = Rig()
        r.engine.schedule(wake("Night thing", at(23, 30)), ms(at(20)))
        assertEquals("moved to the end of quiet hours", ms(at(7, 0, 20)), r.engine.rearm(at(20)))
        assertTrue(r.engine.fire(at(23, 30)).ran.isEmpty())
        assertTrue(r.agentPrompts.isEmpty())
        r.engine.schedule(wake("Flight check-in closes", at(23, 40), urgent = true), ms(at(20)))
        assertEquals(ms(at(23, 40)), r.engine.rearm(at(23, 30)))
        assertEquals(listOf("Flight check-in closes"), r.engine.fire(at(23, 40)).ran.map { it.reason })
        assertEquals(1, r.wakes.size)
        assertEquals(1, r.engine.fire(at(7, 0, 20)).ran.size)
    }

    // ---- the calendar ----
    private val meeting = EventInfo(7, "Northwind interview with Dana", ms(at(15)), ms(at(16)), attendees = 3)
    @Test fun aMeetingGetsAHeadsUpAndAnAfterMeetingCheck() {
        val (add, stale) = WakePlan.calendarWakes(listOf(meeting), emptyList(), ms(at(9)))
        assertTrue(stale.isEmpty())
        val prep = add.single { it.key.startsWith("prep:") }; val after = add.single { it.key.startsWith("after:") }
        assertEquals(ms(at(14, 30)), prep.at); assertEquals(Check.HEADS_UP, prep.check.type)
        assertEquals(ms(at(16, 5)), after.at); assertEquals(Check.TRANSCRIPT, after.check.type)
        assertTrue(after.check.words.containsAll(listOf("Northwind", "Dana")))
        assertTrue(after.job.contains("task_add") && after.job.contains("wake_schedule"))
    }
    @Test fun notMeetingsGetNothing() {
        val e = listOf(meeting.copy(id = 1, allDay = true), meeting.copy(id = 2, declined = true), meeting.copy(id = 3, attendees = 1, online = false))
        assertTrue(WakePlan.calendarWakes(e, emptyList(), ms(at(9))).first.isEmpty())
        assertEquals(2, WakePlan.calendarWakes(listOf(meeting.copy(attendees = 1, online = true)), emptyList(), ms(at(9))).first.size)
    }
    @Test fun calendarSyncIsIdempotentAndFollowsCancellations() = runBlocking {
        val r = Rig()
        r.engine.syncCalendar(listOf(meeting), ms(at(9)))
        r.engine.syncCalendar(listOf(meeting), ms(at(9, 5)))
        assertEquals(2, r.wakes.size)
        r.engine.syncCalendar(emptyList(), ms(at(10)))   // cancelled
        assertTrue(r.wakes.isEmpty())
    }
    @Test fun afterMeetingWaitsForTheTranscriptWithoutAi() = runBlocking {
        val r = Rig()
        r.engine.syncCalendar(listOf(meeting), ms(at(9)))
        r.probeResults["Northwind interview with Dana ended"] = Probe.Nothing
        r.probeResults["Northwind interview with Dana starts soon"] = Probe.Tell("Northwind interview with Dana", "Starts in 30 min")
        r.engine.fire(at(14, 30))
        r.engine.fire(at(16, 5))
        assertTrue("no transcript yet: no AI", r.agentPrompts.isEmpty())
        assertEquals(ms(at(16, 25)), r.wakes.values.single().at)
        r.probeResults["Northwind interview with Dana ended"] = Probe.Found("Fathom: Your meeting notes are ready")
        r.engine.fire(at(16, 25))
        assertEquals(1, r.agentPrompts.size)
        assertTrue(r.agentPrompts.single().contains("action items"))
        assertTrue(r.wakes.isEmpty())
        r.engine.syncCalendar(listOf(meeting), ms(at(16, 30)))
        assertTrue("a handled meeting is not picked up again", r.wakes.isEmpty())
    }

    @Test fun quietReplyIsRecognised() {
        assertTrue(WakePlan.isQuiet("QUIET")); assertTrue(WakePlan.isQuiet(" quiet. ")); assertFalse(WakePlan.isQuiet("Quiet hours start at 23:00"))
    }

    @Test fun anUrgentWakeInsideTheWindowIsNotHeldForALaterOne() = runBlocking {
        val r = Rig()
        r.engine.schedule(wake("Plain", at(10, 0)), ms(at(8)))
        r.engine.schedule(wake("Urgent", at(10, 5), urgent = true), ms(at(8)))
        r.engine.schedule(wake("Plain 2", at(10, 9)), ms(at(8)))
        assertEquals("the group must not slide past the urgent one", ms(at(10, 0)), r.engine.rearm(at(8)))
    }

    @Test fun aFailedAiRunKeepsTheWakeAndAsksAgainLater() = runBlocking {
        val r = Rig()
        val failing = WakeEngine(r.store, object : WakeProbes { override suspend fun check(w: Wake, now: Long) = Probe.Found("Gmail: reply") },
            object : WakeAgent { override suspend fun run(title: String, prompt: String): String = error("no network") },
            object : WakeNotifier { override fun tell(title: String, body: String) {} }, object : WakeAlarm { override fun arm(at: Long?) { r.armed = at } }) { r.quiet }
        failing.schedule(wake("Brightwater reply", at(9)), ms(at(8)))
        val rep = failing.fire(at(9))
        assertEquals(1, rep.agentCalls)
        val kept = r.wakes.values.single()
        assertEquals("asked again in 15 minutes", ms(at(9, 15)), kept.at)
        assertEquals(ms(at(9, 15)), r.armed)
        failing.fire(at(9, 15)); failing.fire(at(9, 30)); failing.fire(at(9, 45))
        assertTrue("given up after three more tries, not retried forever", r.wakes.isEmpty())
    }

    @Test fun followUpsGrowWithUse() {
        assertEquals(3, WakePlan.followUpBudget(0))
        assertEquals(3, WakePlan.followUpBudget(9))
        assertEquals(4, WakePlan.followUpBudget(10))
        assertEquals(8, WakePlan.followUpBudget(50))
        assertEquals("capped so a busy week does not flood the user", WakePlan.MAX_FOLLOW_UPS, WakePlan.followUpBudget(100_000))
        assertTrue(WakePlan.followUpBudget(200) > WakePlan.followUpBudget(20))
    }

    @Test fun thereIsNoFixedHeartbeat() = runBlocking {
        // With nothing scheduled the engine arms nothing and never calls the AI, however often it is asked.
        val r = Rig()
        repeat(5) { r.engine.fire(at(9 + it)) }
        assertNull(r.armed); assertTrue(r.agentPrompts.isEmpty())
    }
}

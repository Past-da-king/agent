package com.past9.phoneaos.wearproto.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Synthetic sensor traces at 50 Hz (20 ms), shaped like the real movements. No watch is needed to run them. */
class GestureTest {
    private val dt = 20L

    /** Gyro trace: zeros except [f] added on top, for [ms]. */
    private fun gyro(ms: Long, f: (Long) -> Triple<Float, Float, Float>): List<Sample> =
        (0..ms step dt).map { t -> f(t).let { Sample(t, it.first, it.second, it.third) } }

    private fun accel(ms: Long, f: (Long) -> Triple<Float, Float, Float>): List<Sample> =
        (0..ms step dt).map { t -> f(t).let { Sample(t, it.first, it.second, it.third) } }

    private fun shifted(trace: List<Sample>, by: Long) = trace.map { it.copy(tMs = it.tMs + by) }

    private fun runFlick(trace: List<Sample>): Int { val d = FlickDetector(); return trace.count { d.onGyro(it) } }

    // ---- flick ---------------------------------------------------------------------------------------------------

    @Test fun aQuickTwistOutAndBackIsAFlick() {
        // One full sine of 360 ms, 6 rad/s: out, then back.
        val t = gyro(800) { ms -> if (ms < 360) Triple((6f * sin(2 * PI * ms / 360)).toFloat(), 0.3f, 0.2f) else Triple(0f, 0f, 0f) }
        assertEquals(1, runFlick(t))
    }

    @Test fun theSameFlickTheOtherWayRoundAndOnTheOtherWristStillCounts() {
        val t = gyro(800) { ms -> if (ms < 360) Triple((-6f * sin(2 * PI * ms / 360)).toFloat(), 0f, 0f) else Triple(0f, 0f, 0f) }
        assertEquals(1, runFlick(t))
    }

    @Test fun aSlowTurnOfTheWristIsNotAFlick() {
        val t = gyro(2_000) { ms -> Triple((1.6f * sin(2 * PI * ms / 1500)).toFloat(), 0f, 0f) }
        assertEquals(0, runFlick(t))
    }

    @Test fun aMediumSpeedTwistOutAndBackIsNotAFlickEitherItMustBeQuick() {
        // 3 rad/s: past the way-back threshold but far below a deliberate flick.
        val t = gyro(800) { ms -> if (ms < 700) Triple((3f * sin(2 * PI * ms / 700)).toFloat(), 0f, 0f) else Triple(0f, 0f, 0f) }
        assertEquals(0, runFlick(t))
    }

    @Test fun aTwistWithoutTheWayBackIsNotAFlick() {
        val t = gyro(1_500) { ms -> if (ms in 100..300) Triple(6f, 0f, 0f) else Triple(0f, 0f, 0f) }
        assertEquals(0, runFlick(t))
    }

    @Test fun aMovementAboutTheWrongAxisIsNotAFlick() {
        // Big, quick, out and back, but about y (the hand nodding), not the forearm.
        val t = gyro(800) { ms -> if (ms < 360) Triple(1f, (6f * sin(2 * PI * ms / 360)).toFloat(), 0f) else Triple(0f, 0f, 0f) }
        assertEquals(0, runFlick(t))
    }

    @Test fun oneFlickIsNotCountedTwiceAndALaterOneIsCounted() {
        val one = gyro(400) { ms -> if (ms < 360) Triple((6f * sin(2 * PI * ms / 360)).toFloat(), 0f, 0f) else Triple(0f, 0f, 0f) }
        val d = FlickDetector()
        val n = (one + shifted(one, 400) + shifted(one, 2_000)).count { d.onGyro(it) }
        // The second sine starts 400 ms after the first: inside the quiet time after the first flick, so it is not a flick of its own.
        assertEquals(2, n)
    }

    // ---- shake ---------------------------------------------------------------------------------------------------

    private fun runShake(trace: List<Sample>): Int { val d = ShakeDetector(); return trace.count { d.onAccel(it) } }

    @Test fun aHardShakeIsDetected() {
        val t = accel(1_500) { ms -> if (ms in 200..1_000) Triple((20f * sin(2 * PI * 5 * ms / 1000)).toFloat(), 0f, 9.81f) else Triple(0f, 0f, 9.81f) }
        assertTrue(runShake(t) >= 1)
    }

    @Test fun walkingAndArmSwingAreNotAShake() {
        val t = accel(8_000) { ms -> Triple((4f * sin(2 * PI * 2 * ms / 1000)).toFloat(), 0f, 9.81f + (3f * sin(2 * PI * 2 * ms / 1000 + 1)).toFloat()) }
        assertEquals(0, runShake(t))
    }

    @Test fun oneBumpIsNotAShake() {
        val t = accel(1_000) { ms -> if (ms in 300..340) Triple(25f, 0f, 9.81f) else Triple(0f, 0f, 9.81f) }
        assertEquals(0, runShake(t))
    }

    @Test fun aWatchLyingStillNeverShakes() {
        assertEquals(0, runShake(accel(30_000) { Triple(0.1f, 0f, 9.81f) }))
    }

    // ---- double tap ----------------------------------------------------------------------------------------------

    /** A tap: a jolt then a ring-down, then nothing. Offsets are z deltas on 9.81. */
    private fun tapAt(at: Long): (Long) -> Float = { ms ->
        when (ms - at) { 0L -> 25f; 20L -> -8f; 40L -> 1.5f; else -> 0f }
    }

    private fun taps(vararg at: Long, total: Long = 2_000): List<Sample> {
        val fs = at.map(::tapAt)
        return accel(total) { ms -> Triple(0f, 0f, 9.81f + fs.sumOf { it(ms).toDouble() }.toFloat()) }
    }

    private fun runTap(trace: List<Sample>): Int { val d = DoubleTapDetector(); return trace.count { d.onAccel(it) } }

    @Test fun twoQuickKnocksAreADoubleTap() { assertEquals(1, runTap(taps(500, 780))) }

    @Test fun oneKnockIsNot() { assertEquals(0, runTap(taps(500))) }

    @Test fun twoKnocksFarApartAreNot() { assertEquals(0, runTap(taps(300, 1_200))) }

    @Test fun twoKnocksTooCloseAreNot() { assertEquals(0, runTap(taps(500, 560))) }

    @Test fun knocksInTheMiddleOfOtherMovementAreNotATap() {
        // The watch is being moved about (jolts of about 8 on every sample, above the quiet level) and two sharp spikes land in it.
        val t = accel(2_000) { ms ->
            val base = if (ms % 40L == 0L) 4f else -4f
            val spike = if (ms == 600L || ms == 880L) 25f else 0f
            Triple(base, 0f, 9.81f + spike)
        }
        assertEquals(0, runTap(t))
    }

    @Test fun knocksThatAreFollowedByMoreMovementAreNotATap() {
        // Each knock is followed 80 ms later by another jolt: something is still moving, so it was not a knock out of stillness.
        val t = accel(2_000) { ms ->
            val z = when (ms) { 500L -> 25f; 580L -> 14f; 780L -> 25f; 860L -> 14f; else -> 0f }
            Triple(0f, 0f, 9.81f + z)
        }
        assertEquals(0, runTap(t))
    }

    @Test fun jostlingWhileWalkingIsNotATap() {
        val t = accel(3_000) { ms -> Triple((9f * sin(2 * PI * 4 * ms / 1000)).toFloat(), (7f * sin(2 * PI * 3 * ms / 1000)).toFloat(), 9.81f) }
        assertEquals(0, runTap(t))
    }

    @Test fun aShakeIsNotADoubleTap() {
        val t = accel(1_500) { ms -> Triple((20f * sin(2 * PI * 5 * ms / 1000)).toFloat(), 0f, 9.81f) }
        assertEquals(0, runTap(t))
    }

    // ---- raise ---------------------------------------------------------------------------------------------------

    private fun runRaise(trace: List<Sample>): Int { val d = RaiseDetector(); return trace.count { d.onAccel(it) } }

    /** Hanging at the side (gravity along x), then brought up in [riseMs] and held, then still. */
    private fun raiseTrace(riseMs: Long, holdMs: Long): List<Sample> = accel(1_000 + riseMs + holdMs) { ms ->
        when {
            ms < 1_000 -> Triple(9.5f, 0.3f, 1.0f)
            ms < 1_000 + riseMs -> {
                val p = (ms - 1_000).toFloat() / riseMs
                // A pure rotation keeps the length 1 g; the hand adds a little acceleration on the way.
                Triple(9.5f * (1 - p), 0.3f, 1f + 8.7f * p)
            }
            else -> Triple(0.4f, 0.3f, 9.78f)
        }
    }

    @Test fun liftingTheWatchToLookAtItAndHoldingItIsARaise() { assertEquals(1, runRaise(raiseTrace(600, 1_200))) }

    @Test fun aSlowTiltIsNotARaise() { assertEquals(0, runRaise(raiseTrace(5_000, 1_500))) }

    @Test fun aWatchThatWasAlreadyFacingUpIsNotARaise() { assertEquals(0, runRaise(accel(5_000) { Triple(0.4f, 0.3f, 9.78f) })) }

    @Test fun aRaiseThatIsNotHeldStillIsNotARaise() { assertEquals(0, runRaise(raiseTrace(600, 150))) }

    // ---- the engine -----------------------------------------------------------------------------------------------

    private fun flickTrace(at: Long) = shifted(gyro(400) { ms -> if (ms < 360) Triple((6f * sin(2 * PI * ms / 360)).toFloat(), 0f, 0f) else Triple(0f, 0f, 0f) }, at)

    @Test fun aGestureThatIsNotSwitchedOnIsNeverReported() {
        val e = GestureEngine(setOf(Gesture.SHAKE))
        assertTrue(flickTrace(0).flatMap { e.onGyro(it) }.isEmpty())
    }

    @Test fun aSingleFlickIsReportedImmediatelyWhenDoubleFlickIsOff() {
        val e = GestureEngine(setOf(Gesture.FLICK))
        assertEquals(listOf(Gesture.FLICK), flickTrace(0).flatMap { e.onGyro(it) })
    }

    @Test fun withDoubleFlickOnASingleFlickWaitsAndThenReportsAsAFlick() {
        val e = GestureEngine(setOf(Gesture.FLICK, Gesture.DOUBLE_FLICK))
        val got = ArrayList<Gesture>()
        flickTrace(0).forEach { got += e.onGyro(it) }
        assertTrue("held back while the second might come", got.isEmpty())
        got += e.poll(5_000)
        assertEquals(listOf(Gesture.FLICK), got)
    }

    @Test fun twoFlicksAreOneDoubleFlickAndNotThreeEvents() {
        val e = GestureEngine(setOf(Gesture.FLICK, Gesture.DOUBLE_FLICK))
        val got = ArrayList<Gesture>()
        (flickTrace(0) + flickTrace(900)).forEach { got += e.onGyro(it) }
        got += e.poll(10_000)
        assertEquals(listOf(Gesture.DOUBLE_FLICK), got)
    }

    @Test fun oneMovementDoesNotFireTwoGestures() {
        // A hard shake is also a run of sharp jolts: only the shake is reported, not a tap on top of it.
        val e = GestureEngine(setOf(Gesture.SHAKE, Gesture.DOUBLE_TAP))
        val t = accel(1_500) { ms -> if (ms in 200..1_000) Triple((20f * sin(2 * PI * 5 * ms / 1000)).toFloat(), 0f, 9.81f) else Triple(0f, 0f, 9.81f) }
        assertEquals(listOf(Gesture.SHAKE), t.flatMap { e.onAccel(it) })
    }

    // ---- the map, the router and the sensors -----------------------------------------------------------------------

    @Test fun everyGestureStartsAsNothing() {
        val m = GestureMap()
        Gesture.entries.forEach { assertEquals(GestureAction.NONE, m.actionFor(it)) }
        assertTrue(m.enabled().isEmpty())
    }

    @Test fun theMapSurvivesBeingSavedAndAnUnknownGestureOrActionIsDropped() {
        val m = GestureMap(mapOf(Gesture.FLICK to GestureAction.TALK, Gesture.SHAKE to GestureAction.CALL), alwaysOn = true)
        assertEquals(m, GestureMap.fromJson(m.toJson()))
        val odd = GestureMap.fromJson("""{"map":{"flick":"teleport","wave":"talk","shake":"approve"}}""")
        assertEquals(GestureAction.NONE, odd.actionFor(Gesture.FLICK))
        assertEquals(GestureAction.APPROVE, odd.actionFor(Gesture.SHAKE))
        assertEquals(GestureMap(), GestureMap.fromJson("not json"))
        assertEquals(GestureMap(), GestureMap.fromJson(null))
    }

    @Test fun talkStartsTheMicOnlyWhenNothingElseIsGoingOn() {
        val r = GestureRouter(); val m = GestureMap().with(Gesture.FLICK, GestureAction.TALK)
        assertEquals(GestureEffect.StartTalk, r.decide(Gesture.FLICK, m, GestureContext(), 0))
        assertEquals(GestureEffect.Ignore, r.decide(Gesture.FLICK, m, GestureContext(callLive = true), 0))
        assertEquals(GestureEffect.Ignore, r.decide(Gesture.FLICK, m, GestureContext(ringing = true), 0))
    }

    @Test fun callStartsACallAndEndsTheOneThatIsOn() {
        val r = GestureRouter(); val m = GestureMap().with(Gesture.DOUBLE_TAP, GestureAction.CALL)
        assertEquals(GestureEffect.StartCall, r.decide(Gesture.DOUBLE_TAP, m, GestureContext(), 0))
        assertEquals(GestureEffect.EndCall, r.decide(Gesture.DOUBLE_TAP, m, GestureContext(callLive = true), 0))
        assertEquals(GestureEffect.Ignore, r.decide(Gesture.DOUBLE_TAP, m, GestureContext(ringing = true), 0))
    }

    @Test fun dismissStopsAnyRingAndLeavesAnApprovalWithoutAnsweringIt() {
        val r = GestureRouter(); val m = GestureMap().with(Gesture.SHAKE, GestureAction.DISMISS)
        assertEquals(GestureEffect.DismissRing, r.decide(Gesture.SHAKE, m, GestureContext(ringing = true), 0))
        assertEquals(GestureEffect.LeaveScreen, r.decide(Gesture.SHAKE, m, GestureContext(approvalOnScreen = true), 0))
        assertEquals(GestureEffect.Ignore, r.decide(Gesture.SHAKE, m, GestureContext(), 0))
    }

    @Test fun anApprovalByGestureNeedsTheSameGestureTwiceAndTheApprovalOnTheScreen() {
        val r = GestureRouter(); val m = GestureMap().with(Gesture.FLICK, GestureAction.APPROVE)
        // Not looking at an approval: never.
        assertEquals(GestureEffect.Ignore, r.decide(Gesture.FLICK, m, GestureContext(), 0))
        val on = GestureContext(approvalOnScreen = true)
        assertEquals(GestureEffect.ArmApprove, r.decide(Gesture.FLICK, m, on, 1_000))
        assertEquals(GestureEffect.Approve, r.decide(Gesture.FLICK, m, on, 2_500))
        // Used up: the next one arms again, it does not approve the next card.
        assertEquals(GestureEffect.ArmApprove, r.decide(Gesture.FLICK, m, on, 2_600))
    }

    @Test fun theSecondGestureComesTooLateSoItOnlyArmsAgain() {
        val r = GestureRouter(); val m = GestureMap().with(Gesture.FLICK, GestureAction.APPROVE); val on = GestureContext(approvalOnScreen = true)
        assertEquals(GestureEffect.ArmApprove, r.decide(Gesture.FLICK, m, on, 0))
        assertEquals(GestureEffect.ArmApprove, r.decide(Gesture.FLICK, m, on, 5_000))
    }

    @Test fun anotherGestureBetweenTheTwoDisarmsTheApproval() {
        val r = GestureRouter(); val m = GestureMap().with(Gesture.FLICK, GestureAction.APPROVE).with(Gesture.SHAKE, GestureAction.DISMISS); val on = GestureContext(approvalOnScreen = true)
        assertEquals(GestureEffect.ArmApprove, r.decide(Gesture.FLICK, m, on, 0))
        r.decide(Gesture.SHAKE, m, on, 500)
        assertEquals(GestureEffect.ArmApprove, r.decide(Gesture.FLICK, m, on, 1_000))
    }

    @Test fun noGestureMeansNoSensorsAtAll() {
        assertTrue(SensorPlan.requests(emptySet(), SensorPlan.Mode.FOREGROUND).isEmpty())
        assertTrue(SensorPlan.requests(emptySet(), SensorPlan.Mode.ALWAYS_ON).isEmpty())
    }

    @Test fun onlyTheSensorsTheMappedGesturesNeedAreAskedFor() {
        assertEquals(listOf("gyro"), SensorPlan.requests(setOf(Gesture.FLICK), SensorPlan.Mode.FOREGROUND).map { it.sensor })
        assertEquals(listOf("accel"), SensorPlan.requests(setOf(Gesture.SHAKE, Gesture.RAISE), SensorPlan.Mode.FOREGROUND).map { it.sensor })
        assertEquals(setOf("gyro", "accel"), SensorPlan.requests(setOf(Gesture.FLICK, Gesture.SHAKE), SensorPlan.Mode.FOREGROUND).map { it.sensor }.toSet())
    }

    @Test fun aTapGetsTheFastRateAndTheOthersTheSlowOne() {
        assertEquals(20_000, SensorPlan.requests(setOf(Gesture.DOUBLE_TAP), SensorPlan.Mode.FOREGROUND).single().periodUs)
        assertEquals(40_000, SensorPlan.requests(setOf(Gesture.SHAKE), SensorPlan.Mode.FOREGROUND).single().periodUs)
    }

    @Test fun alwaysOnBatchesWhatCanWaitButNeverATap() {
        assertEquals(500_000, SensorPlan.requests(setOf(Gesture.FLICK), SensorPlan.Mode.ALWAYS_ON).single().maxLatencyUs)
        assertEquals(500_000, SensorPlan.requests(setOf(Gesture.SHAKE), SensorPlan.Mode.ALWAYS_ON).single().maxLatencyUs)
        assertEquals(0, SensorPlan.requests(setOf(Gesture.DOUBLE_TAP), SensorPlan.Mode.ALWAYS_ON).single().maxLatencyUs)
        assertEquals(0, SensorPlan.requests(setOf(Gesture.FLICK), SensorPlan.Mode.FOREGROUND).single().maxLatencyUs)
    }

    @Test fun sensorsRunOnlyWhileTheAppIsUpUnlessAlwaysOnIsOnAndTheScreenIsAwake() {
        val on = GestureMap().with(Gesture.FLICK, GestureAction.TALK)
        assertTrue(SensorPlan.active(on, appForeground = true, ongoingActivity = false, interactive = true))
        assertTrue(SensorPlan.active(on, appForeground = false, ongoingActivity = true, interactive = false))
        assertFalse(SensorPlan.active(on, appForeground = false, ongoingActivity = false, interactive = true))
        val always = on.copy(alwaysOn = true)
        assertTrue(SensorPlan.active(always, false, false, interactive = true))
        assertFalse(SensorPlan.active(always, false, false, interactive = false))
        assertFalse(SensorPlan.active(GestureMap(alwaysOn = true), true, true, true))
    }

    @Test fun theCapabilityTableFollowsTheWatchesSensorsAndOs() {
        val full = DeviceProfile(api = 36, hasAccel = true, hasGyro = true, oem = "samsung")
        assertEquals(Availability.WORKS, GestureCapabilities.availability(Gesture.FLICK, full))
        assertEquals(Availability.NOT_ON_THIS_WATCH, GestureCapabilities.availability(Gesture.FLICK, full.copy(hasGyro = false)))
        assertEquals(Availability.NOT_ON_THIS_WATCH, GestureCapabilities.availability(Gesture.SHAKE, full.copy(hasAccel = false)))
        assertEquals(Availability.NOT_ON_THIS_WATCH, GestureCapabilities.systemGestures(full))
        assertEquals(Availability.SYSTEM_ONLY, GestureCapabilities.systemGestures(full.copy(api = 37)))
    }
}

package com.past9.phoneaos.wearproto.gesture

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Watch gestures that an app can detect for itself, from the accelerometer and the gyroscope (docs/wear.md, "Gestures").
 * No Android here: samples go in, gestures come out, so every detector is tested on recorded and synthetic traces.
 *
 * The system's own gestures (double pinch, wrist turn on Wear OS 7 through Compose's oneHandedGesture, Samsung's Gestures) are
 * not detected here: the system detects them and the app receives a callback. This file is for what the platform leaves to the
 * app: flick, double flick, shake, double tap and raise.
 *
 * Axes are the watch's: x toward 3 o'clock, y toward 12, z out of the glass. Worn on a wrist the forearm runs along x, so a
 * twist of the wrist is a rotation about x (gyro x); the detectors use magnitude and out-and-back shape, never one sign, so a left
 * wrist, a right wrist and a flipped crown all work. Units: accelerometer m/s2, gyroscope rad/s, time in ms.
 */
data class Sample(val tMs: Long, val x: Float, val y: Float, val z: Float)

enum class Gesture(val key: String, val sensor: String) {
    FLICK("flick", "gyro"), DOUBLE_FLICK("double_flick", "gyro"),
    SHAKE("shake", "accel"), DOUBLE_TAP("double_tap", "accel"), RAISE("raise", "accel");

    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } }
}

/** Out and back: a quick twist of the wrist about the forearm one way, then back. Slow turns and shakes do not count. */
class FlickDetector(private val high: Float = 4.0f, private val back: Float = 2.5f, private val windowMs: Long = 500, private val refractoryMs: Long = 700) {
    private var first = 0 // sign of the first peak, 0 = waiting
    private var firstAt = 0L
    private var quietUntil = 0L

    fun onGyro(s: Sample): Boolean {
        if (s.tMs < quietUntil) return false
        val gx = s.x; val other = max(abs(s.y), abs(s.z))
        if (first == 0) {
            if (abs(gx) >= high && abs(gx) >= 2f * other) { first = if (gx > 0) 1 else -1; firstAt = s.tMs }
            return false
        }
        if (s.tMs - firstAt > windowMs) { first = 0; return onGyro(s) }
        val sign = if (gx > 0) 1 else -1
        if (sign == -first && abs(gx) >= back && abs(gx) >= 1.5f * other) { first = 0; quietUntil = s.tMs + refractoryMs; return true }
        return false
    }
    fun reset() { first = 0; quietUntil = 0 }
}

/** At least [swings] hard reversals of the arm within [windowMs]. Walking and ordinary arm swing stay far below [threshold]. */
class ShakeDetector(private val threshold: Float = 10f, private val swings: Int = 4, private val windowMs: Long = 900, private val refractoryMs: Long = 1_200) {
    private var lx = 0f; private var ly = 0f; private var lz = 0f; private var init = false
    private val hits = ArrayDeque<Long>()
    private var lastSign = 0
    private var quietUntil = 0L

    fun onAccel(s: Sample): Boolean {
        if (!init) { lx = s.x; ly = s.y; lz = s.z; init = true; return false }
        // The slow part is gravity and posture; what is left is movement.
        lx += 0.1f * (s.x - lx); ly += 0.1f * (s.y - ly); lz += 0.1f * (s.z - lz)
        if (s.tMs < quietUntil) return false
        val hx = s.x - lx; val hy = s.y - ly; val hz = s.z - lz
        val ax = abs(hx); val ay = abs(hy); val az = abs(hz)
        val (m, v) = when { ax >= ay && ax >= az -> ax to hx; ay >= az -> ay to hy; else -> az to hz }
        while (hits.isNotEmpty() && s.tMs - hits.first() > windowMs) hits.removeFirst()
        if (m >= threshold) {
            val sign = if (v > 0) 1 else -1
            if (sign != lastSign) { hits.addLast(s.tMs); lastSign = sign }
            if (hits.size >= swings) { hits.clear(); lastSign = 0; quietUntil = s.tMs + refractoryMs; return true }
        }
        return false
    }
    fun reset() { init = false; hits.clear(); lastSign = 0; quietUntil = 0 }
}

/**
 * Two sharp knocks on the watch body or on the back of the hand it sits on: a sudden jolt out of stillness that dies away at once
 * (a shake or a knock-about keeps moving, a tap does not), twice within [minGapMs]..[maxGapMs].
 */
class DoubleTapDetector(
    private val tapJolt: Float = 18f, private val quietJolt: Float = 6f, private val afterJolt: Float = 10f,
    private val minGapMs: Long = 120, private val maxGapMs: Long = 500, private val refractoryMs: Long = 900,
) {
    private class Jolt(val t: Long, val j: Float)
    private val recent = ArrayDeque<Jolt>()
    private var px = 0f; private var py = 0f; private var pz = 0f; private var havePrev = false
    private var pendingAt = -1L; private var pendingBad = false
    private var firstTap = -1L
    private var quietUntil = 0L

    fun onAccel(s: Sample): Boolean {
        if (!havePrev) { px = s.x; py = s.y; pz = s.z; havePrev = true; return false }
        val dx = s.x - px; val dy = s.y - py; val dz = s.z - pz
        px = s.x; py = s.y; pz = s.z
        val j = sqrt(dx * dx + dy * dy + dz * dz)
        recent.addLast(Jolt(s.tMs, j)); while (recent.isNotEmpty() && s.tMs - recent.first().t > 400) recent.removeFirst()
        if (s.tMs < quietUntil) return false

        // A tap waiting for its quiet after: any more movement 60..150 ms on spoils it (a shake, a knock-about).
        if (pendingAt >= 0) {
            val since = s.tMs - pendingAt
            if (since in 60..150 && j >= afterJolt) pendingBad = true
            if (since > 150) {
                val ok = !pendingBad
                val at = pendingAt; pendingAt = -1; pendingBad = false
                if (ok) {
                    if (firstTap >= 0 && at - firstTap in minGapMs..maxGapMs) { firstTap = -1; quietUntil = s.tMs + refractoryMs; return true }
                    firstTap = at
                }
            }
        }
        if (firstTap >= 0 && s.tMs - firstTap > maxGapMs + 200) firstTap = -1

        if (pendingAt < 0 && j >= tapJolt) {
            // Still before it: nothing else moving in the 60..15 ms ahead of the jolt.
            val calm = recent.none { it.t in (s.tMs - 60)..(s.tMs - 15) && it.j >= quietJolt }
            if (calm) { pendingAt = s.tMs; pendingBad = false }
        }
        return false
    }
    fun reset() { recent.clear(); havePrev = false; pendingAt = -1; firstTap = -1; quietUntil = 0 }
}

/**
 * The watch is brought up from hanging at the side to being looked at, and held still: the glass turns from edge-on to facing up
 * within [riseMs], then stays steady for [holdMs]. (This is the movement the system uses to wake the screen; as a gesture it
 * starts the mic when the app is open.) Slow tilts, walking and reading the watch where it already is do not count.
 */
class RaiseDetector(
    private val downZ: Float = 3.5f, private val upZ: Float = 6.0f, private val riseMs: Long = 1_200, private val holdMs: Long = 500,
    private val steady: Float = 1.6f, private val refractoryMs: Long = 3_000,
) {
    private var lastDownAt = -1L
    private var upSince = -1L
    private var quietUntil = 0L
    private var stillSince = -1L

    fun onAccel(s: Sample): Boolean {
        if (s.tMs < quietUntil) return false
        val mag = sqrt(s.x * s.x + s.y * s.y + s.z * s.z)
        val moving = abs(mag - 9.81f) > steady
        if (s.z < downZ) { lastDownAt = s.tMs; upSince = -1; stillSince = -1; return false }
        if (s.z >= upZ) {
            if (upSince < 0) upSince = s.tMs
            if (moving) stillSince = -1 else if (stillSince < 0) stillSince = s.tMs
            val raisedRecently = lastDownAt >= 0 && upSince - lastDownAt <= riseMs
            if (raisedRecently && stillSince >= 0 && s.tMs - stillSince >= holdMs) {
                lastDownAt = -1; upSince = -1; stillSince = -1; quietUntil = s.tMs + refractoryMs
                return true
            }
        } else { upSince = -1; stillSince = -1 }
        return false
    }
    fun reset() { lastDownAt = -1; upSince = -1; stillSince = -1; quietUntil = 0 }
}

/**
 * Runs the detectors for the gestures that are switched on and hands back what happened. A single flick waits [doubleWindowMs]
 * when double flick is on too, so one flick is never reported as both. After any gesture the others are quiet for a moment, so one
 * movement cannot fire two.
 */
class GestureEngine(private val enabled: Set<Gesture>, private val doubleWindowMs: Long = 1_100, private val settleMs: Long = 500) {
    private val flick = FlickDetector(); private val shake = ShakeDetector(); private val tap = DoubleTapDetector(); private val raise = RaiseDetector()
    private var pendingFlickAt = -1L
    private var lastFlickAt = -1L
    private var settledUntil = 0L

    private fun out(g: Gesture, t: Long, into: MutableList<Gesture>) { if (g in enabled && t >= settledUntil) { into += g; settledUntil = t + settleMs } }

    fun onGyro(s: Sample): List<Gesture> {
        val r = ArrayList<Gesture>()
        flushFlick(s.tMs, r)
        if ((Gesture.FLICK in enabled || Gesture.DOUBLE_FLICK in enabled) && flick.onGyro(s)) {
            if (Gesture.DOUBLE_FLICK in enabled && lastFlickAt >= 0 && s.tMs - lastFlickAt <= doubleWindowMs) {
                pendingFlickAt = -1; lastFlickAt = -1; out(Gesture.DOUBLE_FLICK, s.tMs, r)
            } else if (Gesture.DOUBLE_FLICK in enabled) { lastFlickAt = s.tMs; pendingFlickAt = s.tMs }
            else out(Gesture.FLICK, s.tMs, r)
        }
        return r
    }

    fun onAccel(s: Sample): List<Gesture> {
        val r = ArrayList<Gesture>()
        flushFlick(s.tMs, r)
        if (Gesture.SHAKE in enabled && shake.onAccel(s)) out(Gesture.SHAKE, s.tMs, r)
        if (Gesture.DOUBLE_TAP in enabled && tap.onAccel(s)) out(Gesture.DOUBLE_TAP, s.tMs, r)
        if (Gesture.RAISE in enabled && raise.onAccel(s)) out(Gesture.RAISE, s.tMs, r)
        return r
    }

    /** A first flick whose second never came is a single flick, once the window has passed. */
    private fun flushFlick(now: Long, into: MutableList<Gesture>) {
        if (pendingFlickAt >= 0 && now - pendingFlickAt > doubleWindowMs) {
            pendingFlickAt = -1; lastFlickAt = -1
            if (Gesture.FLICK in enabled) out(Gesture.FLICK, now, into)
        }
    }

    /** Call with the time now when no sample came (the screen went off), so a waiting flick still resolves. */
    fun poll(now: Long): List<Gesture> = ArrayList<Gesture>().also { flushFlick(now, it) }

    fun reset() { flick.reset(); shake.reset(); tap.reset(); raise.reset(); pendingFlickAt = -1; lastFlickAt = -1; settledUntil = 0 }
}

/** What a gesture does. */
enum class GestureAction(val key: String) {
    NONE("none"), TALK("talk"), APPROVE("approve"), DISMISS("dismiss"), CALL("call");
    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: NONE }
}

/** What he chose for each gesture. Every gesture starts as NONE: nothing on his wrist moves until he says so. */
data class GestureMap(val map: Map<Gesture, GestureAction> = emptyMap(), val alwaysOn: Boolean = false) {
    fun actionFor(g: Gesture): GestureAction = map[g] ?: GestureAction.NONE
    /** Gestures that need a sensor running: the ones he mapped to something. */
    fun enabled(): Set<Gesture> = map.filterValues { it != GestureAction.NONE }.keys
    fun with(g: Gesture, a: GestureAction) = copy(map = map + (g to a))

    fun toJson(): String = JSONObject().put("alwaysOn", alwaysOn).put("map", JSONObject().also { o -> map.forEach { (g, a) -> o.put(g.key, a.key) } }).toString()

    companion object {
        fun fromJson(text: String?): GestureMap {
            val o = runCatching { JSONObject(text ?: return GestureMap()) }.getOrNull() ?: return GestureMap()
            val m = o.optJSONObject("map")
            val map = LinkedHashMap<Gesture, GestureAction>()
            m?.keys()?.forEach { k -> Gesture.of(k)?.let { g -> map[g] = GestureAction.of(m.optString(k)) } }
            return GestureMap(map, o.optBoolean("alwaysOn"))
        }
        /** A starting point he can accept in one tap: flick to talk, double flick to dismiss a ring. */
        fun recommended() = GestureMap(mapOf(Gesture.FLICK to GestureAction.TALK, Gesture.DOUBLE_FLICK to GestureAction.DISMISS))
    }
}

/** Where the watch is when a gesture arrives. */
data class GestureContext(val callLive: Boolean = false, val ringing: Boolean = false, val approvalOnScreen: Boolean = false)

sealed interface GestureEffect {
    data object Ignore : GestureEffect
    data object StartTalk : GestureEffect
    data object StartCall : GestureEffect
    data object EndCall : GestureEffect
    /** Stop the ring that is on (a wake is snoozed, an approval is put aside); the phone and the other devices follow. */
    data object DismissRing : GestureEffect
    data object LeaveScreen : GestureEffect
    /** First of two: the screen says "Again to approve". */
    data object ArmApprove : GestureEffect
    data object Approve : GestureEffect
}

/**
 * Turns a gesture into what the watch does. Two rules keep a stray movement from doing harm: an approval by gesture needs the
 * same gesture twice within [armMs] and only while that approval is on the screen (so a gesture can never approve something he has
 * not seen), and while something rings only a dismiss is honoured.
 */
class GestureRouter(private val armMs: Long = 3_000) {
    private var armedUntil = 0L

    @Synchronized fun decide(g: Gesture, map: GestureMap, ctx: GestureContext, now: Long): GestureEffect {
        val action = map.actionFor(g)
        if (action != GestureAction.APPROVE) armedUntil = 0L
        return when (action) {
            GestureAction.NONE -> GestureEffect.Ignore
            GestureAction.TALK -> if (ctx.ringing || ctx.callLive) GestureEffect.Ignore else GestureEffect.StartTalk
            GestureAction.CALL -> if (ctx.ringing) GestureEffect.Ignore else if (ctx.callLive) GestureEffect.EndCall else GestureEffect.StartCall
            GestureAction.DISMISS -> when { ctx.ringing -> GestureEffect.DismissRing; ctx.approvalOnScreen -> GestureEffect.LeaveScreen; else -> GestureEffect.Ignore }
            GestureAction.APPROVE -> when {
                !ctx.approvalOnScreen || ctx.ringing -> { armedUntil = 0L; GestureEffect.Ignore }
                armedUntil > 0L && now <= armedUntil -> { armedUntil = 0L; GestureEffect.Approve }
                else -> { armedUntil = now + armMs; GestureEffect.ArmApprove }
            }
        }
    }
}

/** How the sensors are asked for, so a gesture costs battery only while it can be used. */
data class SensorRequest(val sensor: String, val periodUs: Int, val maxLatencyUs: Int)

object SensorPlan {
    enum class Mode { FOREGROUND, ALWAYS_ON }

    /** No gesture switched on, no sensor: nothing is registered at all. */
    fun requests(enabled: Set<Gesture>, mode: Mode): List<SensorRequest> {
        val out = ArrayList<SensorRequest>()
        val gyro = enabled.any { it.sensor == "gyro" }
        val accel = enabled.filter { it.sensor == "accel" }
        // Gyro: 40 Hz is enough to see a 300 ms flick. Always-on lets the sensor hub batch half a second (the CPU sleeps between).
        if (gyro) out += SensorRequest("gyro", 25_000, if (mode == Mode.ALWAYS_ON) 500_000 else 0)
        if (accel.isNotEmpty()) {
            // A tap is a few milliseconds long and needs 50 Hz; shake and raise are fine at 25 Hz.
            val period = if (Gesture.DOUBLE_TAP in accel) 20_000 else 40_000
            // A tap must be seen within a moment of landing; the other two can wait a batch.
            val latency = if (mode == Mode.ALWAYS_ON && Gesture.DOUBLE_TAP !in accel) 500_000 else 0
            out += SensorRequest("accel", period, latency)
        }
        return out
    }

    /** Whether the sensors should be registered right now. Always-on stops with the screen: the sensor hub keeps the battery. */
    fun active(map: GestureMap, appForeground: Boolean, ongoingActivity: Boolean, interactive: Boolean): Boolean {
        if (map.enabled().isEmpty()) return false
        if (appForeground || ongoingActivity) return true
        return map.alwaysOn && interactive
    }
}

/** What a watch can do, to show him only the gestures that can work here. */
data class DeviceProfile(val api: Int, val hasAccel: Boolean, val hasGyro: Boolean, val oem: String = "")

enum class Availability { WORKS, NEEDS_SENSOR, SYSTEM_ONLY, NOT_ON_THIS_WATCH }

object GestureCapabilities {
    /** Wear OS 7 (API 37) one-handed gestures framework, seen by apps through Compose's Modifier.oneHandedGesture. */
    const val ONE_HANDED_API = 37

    fun availability(g: Gesture, d: DeviceProfile): Availability = when (g.sensor) {
        "gyro" -> if (d.hasGyro) Availability.WORKS else Availability.NOT_ON_THIS_WATCH
        else -> if (d.hasAccel) Availability.WORKS else Availability.NOT_ON_THIS_WATCH
    }

    /** The system's own double pinch and wrist turn reach an app only on Wear OS 7 and up, and only on a watch whose maker supports them. */
    fun systemGestures(d: DeviceProfile): Availability = if (d.api >= ONE_HANDED_API) Availability.SYSTEM_ONLY else Availability.NOT_ON_THIS_WATCH
}

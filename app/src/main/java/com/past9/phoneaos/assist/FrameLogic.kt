package com.past9.phoneaos.assist

/** Whether Live can see the screen right now, as the sheet and the bubble say it. */
enum class Seeing {
    /** Nothing is being sent (screen reading is off, or Live cannot see yet). */
    OFF,
    /** Frames of the screen are going to Live. */
    SEEING,
    /** A banking or money app, or a protected window, is on screen: nothing is sent and Live is told it is private. */
    PRIVATE,
}

/** The rules for when a frame of the screen may go to Live. Plain logic, no Android, so every rule is tested. */
object FrameGate {
    enum class Verdict {
        /** Send this frame. */
        SEND,
        /** The screen is private (a banking or money app, or a protected window): send nothing, and say so. */
        PRIVATE,
        /** Nothing usable came back (no picture, or the app could not be told): send nothing. */
        NOTHING,
    }

    /** Frames exist only with "Let the assistant read the screen" on AND "Live sees your screen" on. Off means none, ever. */
    fun enabled(readScreen: Boolean, liveSees: Boolean): Boolean = readScreen && liveSees

    /**
     * Which app to judge. While the sheet is open the assistant's own window is on top, so what the phone calls "the
     * top app" is this app; the app behind it (the last other app seen) is the one that matters.
     */
    fun appToJudge(topPkg: String, ownPkg: String, lastOther: String): String = if (topPkg.isBlank() || topPkg == ownPkg) lastOther else topPkg

    fun verdict(ctx: ScreenContext?, app: String, label: String, blockedBy: (String, String) -> Boolean): Verdict = when {
        ctx == null -> Verdict.NOTHING
        ctx.blocked -> Verdict.PRIVATE
        // Not knowing which app it is, nothing goes: never guess a screen is safe.
        app.isBlank() -> Verdict.NOTHING
        blockedBy(app, label) -> Verdict.PRIVATE
        ctx.protectedWindow -> Verdict.PRIVATE
        ctx.screenshot.isBlank() -> Verdict.NOTHING
        else -> Verdict.SEND
    }
}

/**
 * When to look at the screen. About one frame a second while it changes; once it has held still for two looks it slows
 * to one every [idleMs]; a user's turn starting (forced) gets a fresh look at once, but never more often than [forcedMinMs].
 */
class FrameThrottle(private val activeMs: Long = 1_200, private val idleMs: Long = 6_000, private val forcedMinMs: Long = 400) {
    private var last = -1L
    private var still = 0

    fun due(now: Long, forced: Boolean): Boolean {
        if (last < 0) return true
        val gap = now - last
        if (forced) return gap >= forcedMinMs
        return gap >= if (still >= 2) idleMs else activeMs
    }

    /** A look was taken at [now]; [changed] says the screen differed from the last one sent. */
    fun looked(now: Long, changed: Boolean) { last = now; still = if (changed) 0 else still + 1 }
}

/** A cheap "did the screen change": a small grid of brightness values, compared by their average difference. */
object FrameDiff {
    const val SIDE = 16
    const val THRESHOLD = 4

    fun luma(argb: Int): Int = ((argb shr 16 and 0xff) * 299 + (argb shr 8 and 0xff) * 587 + (argb and 0xff) * 114) / 1000

    /** True when there is no earlier grid, or the two differ by more than [threshold] on average (0..255). */
    fun differs(old: IntArray?, new: IntArray, threshold: Int = THRESHOLD): Boolean {
        if (old == null || old.size != new.size || new.isEmpty()) return true
        var sum = 0L
        for (i in new.indices) sum += kotlin.math.abs(old[i] - new[i])
        return sum / new.size > threshold
    }
}

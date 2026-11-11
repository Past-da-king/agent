package com.past9.phoneaos.assist

/**
 * Holds one prepared value (a one-use live token minted ahead of time) for a short while. [take] hands it out once and
 * only while it is still fresh; an old one is dropped, because a token is good for a short time and one start.
 */
class FreshOnce<T>(private val ttlMs: Long, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private var value: T? = null
    private var at = 0L

    /** True when a value is held and still fresh (so a second warm-up is not needed). */
    @Synchronized fun fresh(): Boolean = value != null && clock() - at < ttlMs

    @Synchronized fun put(v: T) { value = v; at = clock() }

    /** The value if it is fresh, and it is gone after this; null otherwise. */
    @Synchronized fun take(): T? {
        val v = value; value = null
        return if (v != null && clock() - at < ttlMs) v else null
    }
}

/** The floating bubble's geometry, free of Android so it can be tested. */
object BubbleMath {
    /** Whether the bubble's centre is inside the circular dismiss target. */
    fun overTarget(cx: Float, cy: Float, tx: Float, ty: Float, radius: Float): Boolean {
        val dx = cx - tx; val dy = cy - ty
        return dx * dx + dy * dy <= radius * radius
    }

    /** Keeps the bubble fully on screen. */
    fun clamp(v: Int, size: Int, screen: Int): Int = v.coerceIn(0, (screen - size).coerceAtLeast(0))
}

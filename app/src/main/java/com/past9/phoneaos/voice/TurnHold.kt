package com.past9.phoneaos.voice

/**
 * Keeps the voice from being sent something while the person is still talking.
 *
 * Before: a finished agent answer, a screen note or a context update went to the model the moment it was ready, even in
 * the middle of his sentence. A client turn with turnComplete true makes the model answer at once, so Live "jumped in"
 * on top of him. Now anything that arrives while he is speaking (or has been quiet for less than [quietMs]) waits, in
 * order, and goes out when he has finished, or after [maxHoldMs] so nothing waits forever.
 */
class TurnHold(private val quietMs: Long = 1_800L, private val maxHoldMs: Long = 20_000L, private val limit: Int = 8) {
    class Held(val text: String, val turnComplete: Boolean, val at: Long)

    private val lock = Any()
    private var lastVoice = Long.MIN_VALUE / 2
    private val queue = ArrayList<Held>()

    /** His voice was heard at [now]. */
    fun voice(now: Long) { synchronized(lock) { lastVoice = now } }

    /** Whether he is speaking (or only just stopped) at [now]. */
    fun speaking(now: Long): Boolean = synchronized(lock) { now - lastVoice < quietMs }

    /** True when the message was kept for later; false when it may be sent now. */
    fun offer(text: String, turnComplete: Boolean, now: Long): Boolean = synchronized(lock) {
        if (queue.isEmpty() && now - lastVoice >= quietMs) return false
        queue += Held(text, turnComplete, now)
        while (queue.size > limit) queue.removeAt(0)
        true
    }

    /** What is ready to go at [now]: everything when he has been quiet long enough, or when the oldest has waited too long. */
    fun due(now: Long): List<Held> = synchronized(lock) {
        if (queue.isEmpty()) return emptyList()
        if (now - lastVoice < quietMs && now - queue.first().at < maxHoldMs) return emptyList()
        val out = queue.toList(); queue.clear(); out
    }

    val waiting: Int get() = synchronized(lock) { queue.size }
}

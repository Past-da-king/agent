package com.past9.phoneaos.voice

/**
 * Keeps the voice from being sent something while the person is talking, or while the model is mid-answer.
 *
 * Before: a finished agent answer, a screen note or a context update went to the model the moment it was ready, even in
 * the middle of his sentence. A client turn with turnComplete true makes the model answer at once, so Live "jumped in"
 * on top of him. Google also documents that a clientContent message "will interrupt any current model generation"
 * (https://aistudio.google.com/api-reference/live), and a live check on gemini-3.8-live showed a quiet note sent in the
 * middle of the model's speech derailing it. Now anything that arrives while he is speaking (or has been quiet for less
 * than [quietMs]), or while the model is speaking, waits, in order, and goes out when both are quiet, or after
 * [maxHoldMs] so nothing waits forever.
 */
class TurnHold(private val quietMs: Long = 1_800L, private val maxHoldMs: Long = 90_000L, private val limit: Int = 8) {
    class Held(val text: String, val turnComplete: Boolean, val at: Long)

    private val lock = Any()
    private var lastVoice = Long.MIN_VALUE / 2
    private var modelSince = -1L
    private val queue = ArrayList<Held>()

    /** His voice was heard at [now]. */
    fun voice(now: Long) { synchronized(lock) { lastVoice = now } }

    /** The model started ([on] true) or finished or was cut off ([on] false) speaking at [now]. */
    fun model(on: Boolean, now: Long) { synchronized(lock) { if (on) { if (modelSince < 0) modelSince = now } else modelSince = -1L } }

    /** Whether he is speaking (or only just stopped), or the model is mid-answer, at [now]. */
    fun busy(now: Long): Boolean = synchronized(lock) { now - lastVoice < quietMs || modelSince >= 0 }

    /** True when the message was kept for later; false when it may be sent now. */
    fun offer(text: String, turnComplete: Boolean, now: Long): Boolean = synchronized(lock) {
        if (queue.isEmpty() && !(now - lastVoice < quietMs || modelSince >= 0)) return false
        queue += Held(text, turnComplete, now)
        while (queue.size > limit) queue.removeAt(0)
        true
    }

    /** What is ready to go at [now]: everything once both are quiet, or when the oldest has waited too long. */
    fun due(now: Long): List<Held> = synchronized(lock) {
        if (queue.isEmpty()) return emptyList()
        val busy = now - lastVoice < quietMs || modelSince >= 0
        if (busy && now - queue.first().at < maxHoldMs) return emptyList()
        val out = queue.toList(); queue.clear(); out
    }

    val waiting: Int get() = synchronized(lock) { queue.size }
}

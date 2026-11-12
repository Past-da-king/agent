package com.past9.phoneaos.wear

/**
 * Is this watch's microphone usable? A Galaxy Watch with a dead mic still reports one and records without error: the
 * take is digital silence. So the answer is read from what was recorded, kept, and used to put the fallbacks first
 * (Speak on phone, keyboard, quick replies). Pure, so it is tested without a watch.
 */
object MicPolicy {
    enum class Verdict { OK, NO_HARDWARE, NO_PERMISSION, SILENT, FAILED }

    const val WINDOW_MS = 100
    /** Samples this quiet in every window of a long enough take: nothing reached the converter at all. */
    const val DEAD_PEAK = 3
    const val MIN_TAKE_MS = 1_500
    /** A window louder than this is speech. */
    const val SPEECH_PEAK = 700
    const val QUIET_END_MS = 1_300
    const val GIVE_UP_MS = 7_000
    const val MAX_TAKE_MS = 30_000
    /** After this long a "mic is dead" note is tried again on its own (the user may have had it repaired). */
    const val RETRY_AFTER_MS = 24L * 60 * 60 * 1000

    fun preflight(hasMicFeature: Boolean, hasInput: Boolean, granted: Boolean): Verdict = when {
        !hasMicFeature || !hasInput -> Verdict.NO_HARDWARE
        !granted -> Verdict.NO_PERMISSION
        else -> Verdict.OK
    }

    /** True when a take that ran long enough carried no signal in any window. */
    fun silentTake(peaks: List<Int>): Boolean = peaks.size * WINDOW_MS >= MIN_TAKE_MS && peaks.all { it <= DEAD_PEAK }

    fun heardSpeech(peaks: List<Int>): Boolean = peaks.any { it >= SPEECH_PEAK }

    /** Whether to stop recording now: speech was heard and then it went quiet, nothing was said for long, or the cap. */
    fun shouldStop(peaks: List<Int>): Boolean {
        val ms = peaks.size * WINDOW_MS
        if (ms >= MAX_TAKE_MS) return true
        val last = peaks.indexOfLast { it >= SPEECH_PEAK }
        if (last >= 0) return (peaks.size - 1 - last) * WINDOW_MS >= QUIET_END_MS
        return ms >= GIVE_UP_MS
    }

    /** What to do with a finished take: send it, or say why not (and note a dead mic). */
    fun judge(peaks: List<Int>): Verdict = when {
        silentTake(peaks) -> Verdict.SILENT
        else -> Verdict.OK
    }

    /** Should the talk screen put the fallbacks first? Once a dead mic was seen, until a day has passed or he tries it again. */
    fun preferFallback(deadAtMs: Long, nowMs: Long): Boolean = deadAtMs > 0 && nowMs - deadAtMs < RETRY_AFTER_MS

    /** Loudness 0..1 of one window, for the listening ring. */
    fun level(peak: Int): Float = (peak / 12_000f).coerceIn(0f, 1f)
}

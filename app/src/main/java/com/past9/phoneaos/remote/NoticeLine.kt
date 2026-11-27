package com.past9.phoneaos.remote

/**
 * The chat's notice line, with repeats folded in.
 *
 * 27 Nov 2026: a computer whose disk was full refused message after message, and each refusal wrote a new line saying
 * the same thing, so the chat became a wall of identical errors. The same words again inside [REPEAT_MS] now fold into
 * the line already there, with a count, and one line is all he sees.
 */
internal class NoticeLine(
    private val repeatMs: Long = REPEAT_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var text: String? = null
    private var at = 0L
    private var count = 0

    /**
     * This line's text: null when it is a new line (add it), or the whole line to show when the same words came again
     * (replace the line already in the chat with it, count and all).
     */
    fun lineFor(incoming: String): String? {
        val time = now()
        if (text == incoming && count > 0 && time - at < repeatMs) {
            count += 1
            at = time
            return shown(incoming, count)
        }
        text = incoming
        at = time
        count = 1
        return null
    }

    /** Forget the line in the chat (it was deleted, or something else was written there). */
    fun reset() {
        text = null
        at = 0
        count = 0
    }

    companion object {
        /** The same words within ten minutes are the same trouble, not ten troubles. */
        const val REPEAT_MS = 10 * 60_000L

        /** "Couldn't send that to your computer" on its own the first time, then " (×2)", " (×3)". */
        fun shown(base: String, count: Int): String = if (count <= 1) base else base + " (×" + count + ")"
    }
}

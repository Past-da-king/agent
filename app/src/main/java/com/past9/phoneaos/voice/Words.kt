package com.past9.phoneaos.voice

/**
 * Words he said only ever go forward (30 Oct 2026, "my whole thing just disappeared", "if I keep quiet the thing will
 * disappear"): when a stretch ended in the quiet, its text was REPLACED by whatever came last: a short final result, a
 * partial that started over, or an empty write-down from the saved audio. Pure, so it is tested with no phone.
 */
object Words {
    private fun list(s: String) = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    private fun norm(w: String) = w.lowercase().trim { !it.isLetterOrDigit() }

    fun join(a: String, b: String): String = when {
        a.isBlank() -> b.trim()
        b.isBlank() -> a.trim()
        else -> a.trimEnd() + " " + b.trimStart()
    }

    /**
     * Whether [next] would lose words he can already see in [now]. A recogniser correcting itself changes a word or two
     * and keeps the length; losing more than 40% of something at least four words long is not a correction.
     */
    fun less(now: String, next: String): Boolean {
        val a = list(now)
        if (a.isEmpty()) return false
        val b = list(next)
        if (b.isEmpty()) return true
        if (a.size < 4) return false
        return b.size < a.size * 0.6
    }

    /** A partial that is much shorter and does not begin like the last one: the recogniser started a new utterance. */
    fun startedOver(now: String, next: String): Boolean {
        if (!less(now, next)) return false
        val a = list(now).map(::norm); val b = list(next).map(::norm)
        val same = a.zip(b).takeWhile { (x, y) -> x == y }.size
        return same < maxOf(1, b.size / 2)
    }
}

/**
 * Writes one dictation into a message box. The box may hold more than the dictation: what was there when it started,
 * and anything he typed since. Only ever adds: an update that would leave less than the last one is ignored, and once
 * he has changed the box himself (typed, or sent it) only the words that arrive after that go in, after what he left
 * there, so a message he already sent never comes back.
 */
class BoxWriter(start: String, private val log: (String) -> Unit = {}) {
    private var base = start
    /** The dictated words already accounted for in [base] (he changed the box after they went in). */
    private var consumed = ""
    private var lastDictated = ""
    private var written = start

    /** The box's new text for [dictated], the whole dictation so far; null: leave the box as it is. */
    fun next(box: String, dictated: String): String? {
        if (box != written) { base = box; consumed = lastDictated; written = box }
        val tail = after(dictated, consumed)
        val text = Words.join(base, tail)
        if (text == written) { lastDictated = dictated; return null }
        // Nothing dictated any more, after there was: never back to how the box started.
        if (tail.isBlank()) { log("ignored an empty result"); return null }
        if (Words.less(written, text)) { log("ignored a shorter result: ${written.length} to ${text.length} chars"); return null }
        lastDictated = dictated
        written = text
        return text
    }

    private fun after(whole: String, done: String): String {
        if (done.isBlank()) return whole
        if (whole.startsWith(done)) return whole.substring(done.length).trimStart()
        // The words before it were corrected meanwhile: count words, not characters.
        val n = done.trim().split(Regex("\\s+")).size
        return whole.trim().split(Regex("\\s+")).drop(n).joinToString(" ")
    }
}

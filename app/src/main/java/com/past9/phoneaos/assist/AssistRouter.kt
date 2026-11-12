package com.past9.phoneaos.assist

/** Where the user's words go. */
sealed class Routed {
    /** Nothing waiting fits: the words go to the agent as they always did. */
    object None : Routed()
    /** The words answer [ask] clearly: send [answer] to whoever is waiting. */
    data class Answer(val ask: CtxAsk, val answer: String) : Routed()
    /** Two or more waiting questions could take it: the user picks (a slide-up sheet), the words are kept in [answer]. */
    data class Which(val answer: String, val candidates: List<CtxAsk>) : Routed()
    /** The words cannot be sent as an answer, and why. */
    data class Refused(val reason: String) : Routed()
}

/**
 * Decides whether what the user said answers something an agent waits on, without asking which agent when it is
 * clear. Free of Android. Approvals are never answered by words: they are held to approve on the card, as everywhere
 * in the app, so only questions are candidates.
 */
object AssistRouter {
    /** What a question asking for a code or secret says about itself. */
    private val CODE_ASK = Regex("\\b(code|pin|otp|passcode|pairing|pair|token|verification|one[- ]time|6[- ]digit|4[- ]digit)\\b", RegexOption.IGNORE_CASE)
    private val ANSWER_IS = Regex("\\b(?:the answer is|my answer is|the answer's|answer is)[: ,]+(.+)$", RegexOption.IGNORE_CASE)
    private val ANSWER_LEAD = Regex("^(?:ok(?:ay)?[, ]+)?(?:answer|reply|say)[: ,]+(.+)$", RegexOption.IGNORE_CASE)
    /** Dates, times and amounts that are not codes: 25th, 3pm, 10am, 5km, 2nd. */
    private val NOT_CODE = Regex("^\\d+(st|nd|rd|th|am|pm|km|kg|mb|gb|ms|min|hr|h|m|s)$", RegexOption.IGNORE_CASE)
    private val SPOKEN_DIGITS = mapOf("zero" to "0", "oh" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9")

    /** For the typed sheet and Live's own fallback: read the words and decide. */
    fun fromWords(words: String, asks: List<CtxAsk>): Routed {
        val open = asks.filter { !it.approval }
        val w = words.trim()
        if (open.isEmpty() || w.isEmpty()) return Routed.None
        val lower = w.lowercase()

        // 1. One of a question's own options, said on its own or in a few words ("the second one", "yes" when it has Yes).
        if (w.split(Regex("\\s+")).size <= 6) {
            val byOption = open.mapNotNull { k -> pickOption(k, lower)?.let { k to it } }
            if (byOption.size == 1) return Routed.Answer(byOption[0].first, byOption[0].second)
            if (byOption.size > 1) return Routed.Which(w, byOption.map { it.first })
        }
        // 2. A code or a pin: it fits the one question that asks for one.
        val code = extractCode(w)
        if (code != null) {
            val askers = open.filter { it.options.isEmpty() && CODE_ASK.containsMatchIn(it.text + " " + it.why) }
            if (askers.size == 1) return Routed.Answer(askers[0], code)
            if (askers.size > 1) return Routed.Which(code, askers)
        }
        // 3. He says it is the answer ("tell Pengen the answer is blue"): the free-text questions, narrowed by a name he used.
        val rest = (ANSWER_IS.find(w) ?: ANSWER_LEAD.find(w))?.groupValues?.get(1)?.trim()?.trimEnd('.')
        if (!rest.isNullOrEmpty()) {
            val free = open.filter { it.options.isEmpty() }
            val named = free.filter { it.by.isNotBlank() && lower.contains(it.by.lowercase()) }
            val pool = if (named.isNotEmpty()) named else free
            if (pool.size == 1) return Routed.Answer(pool[0], rest)
            if (pool.size > 1) return Routed.Which(rest, pool)
        }
        return Routed.None
    }

    /**
     * Live called answer_ask. [ref] names the question when the voice is sure; blank when it is not. [answer] is the
     * answer; [userSaid] is what he really said (a code must be in it, so the voice cannot make one up).
     */
    fun forTool(ref: String, answer: String, userSaid: String, asks: List<CtxAsk>): Routed {
        val a = answer.trim()
        if (a.isEmpty()) return Routed.Refused("There is no answer to send.")
        val target = asks.firstOrNull { it.ref.equals(ref.trim(), true) }
        if (target != null) {
            if (target.approval) return Routed.Refused("That is an approval: the user holds the approve button on the screen. Tell them so; never approve for them.")
            return grounded(target, a, userSaid) ?: Routed.Answer(target, finalAnswer(target, a))
        }
        val open = asks.filter { !it.approval }
        if (open.isEmpty()) return Routed.Refused(if (asks.isEmpty()) "Nothing is waiting on the user right now." else "Only an approval is waiting, and the user holds the approve button on the screen for that.")
        if (open.size == 1) return grounded(open[0], a, userSaid) ?: Routed.Answer(open[0], finalAnswer(open[0], a))
        // Several wait and the voice did not say which: use the same reading as the sheet, else the user picks.
        when (val r = fromWords(userSaid.ifBlank { a }, open)) {
            is Routed.Answer -> return grounded(r.ask, a, userSaid) ?: Routed.Answer(r.ask, finalAnswer(r.ask, a.ifBlank { r.answer }))
            is Routed.Which -> return Routed.Which(a, r.candidates)
            else -> {}
        }
        return Routed.Which(a, open)
    }

    /**
     * A code or a number must be in what the user actually said, so the voice can never send one it made up. Null when it is.
     * Questions with options and plain words are not checked: the voice may tidy those.
     */
    private fun grounded(ask: CtxAsk, answer: String, userSaid: String): Routed? {
        if (ask.options.isNotEmpty()) return null
        val code = extractCode(answer) ?: return null
        if (answer.trim().replace("-", "").replace(" ", "").equals(code, true) && extractCode(userSaid) != code) {
            return Routed.Refused("That code is not in what the user said. Ask them to say it again, then answer with exactly what they said.")
        }
        return null
    }

    /** The answer as the question takes it: one of its options when the words are one, else the words. */
    fun finalAnswer(ask: CtxAsk, answer: String): String {
        val a = answer.trim()
        val picked = ask.options.firstOrNull { it.equals(a, true) } ?: pickOption(ask, a.lowercase())
        return picked ?: a
    }

    private fun pickOption(k: CtxAsk, lower: String): String? {
        if (k.options.isEmpty()) return null
        val clean = lower.trim().trim('.', '!', ',')
        k.options.firstOrNull { it.equals(clean, true) }?.let { return it }
        val hits = k.options.filter { Regex("(^|\\W)" + Regex.escape(it.lowercase()) + "(\\W|$)").containsMatchIn(clean) }
        return hits.singleOrNull()
    }

    /**
     * A code in his words: digits (also spoken, "four eight two nine"), or one token that mixes letters and digits.
     * Null when the words carry no code. The code is returned without spaces or dashes.
     */
    fun extractCode(words: String): String? {
        val tokens = words.lowercase().replace(Regex("[,.!?]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        // One token like 482917, 48-29-17 or a8f3k2.
        tokens.firstOrNull { t -> val c = t.replace("-", ""); c.length in 4..12 && c.any { it.isDigit() } && c.all { it.isLetterOrDigit() } && !NOT_CODE.matches(c) }?.let { return it.replace("-", "") }
        // Single digits or digit words in a row ("4 8 2 9 1 7", "four eight two nine one seven").
        val digits = StringBuilder(); var best = ""
        for (t in tokens) {
            val d = if (t.length <= 4 && t.all { it.isDigit() }) t else SPOKEN_DIGITS[t]
            if (d != null) digits.append(d) else { if (digits.length > best.length) best = digits.toString(); digits.clear() }
        }
        if (digits.length > best.length) best = digits.toString()
        return best.takeIf { it.length in 4..12 }
    }
}

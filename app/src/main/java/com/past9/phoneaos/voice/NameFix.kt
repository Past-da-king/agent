package com.past9.phoneaos.voice

/**
 * Puts a name the recogniser heard as something that sounds like it back to the name's spelling (19 Nov 2026), for the
 * LIVE PREVIEW only ("started edge" / "that edge" for a name he says all day). It works from the names list the app
 * holds (no one name is written in): a multi-word name matches a run of words whose consonant skeletons are close to
 * the name's own, with at least one word exact. A sound-alike is only changed beside a company-like word (or when all
 * but one word of the name is exact), never inside talk about the mistake itself ("it said started edge instead of
 * Strat Edge"), and never in a sentence that already holds the correct name. The whole-take text is the model's and is
 * not touched by it.
 */
object NameFix {
    /** Words that say a business name is being talked about. */
    private val COMPANY = setOf("solutions", "intelligence", "id", "cms", "team", "letterhead", "platform", "tender", "tenders", "website", "company", "project", "projects", "portal", "bid", "documents", "staff", "pack", "branding", "logo")
    /** Words that mean he is talking about the spelling or the mistake itself. */
    private val META = setOf("said", "say", "says", "saying", "instead", "spelled", "spelt", "spell", "spelling", "called", "supposed", "wrote", "write", "writes", "typed", "wrong", "mistake", "confuses", "confused", "misheard", "heard")

    private class Tok(val text: String, val start: Int, val end: Int) { val low = text.lowercase() }

    private fun split(n: String) = n.split(Regex("[^A-Za-z0-9']+")).filter { it.isNotEmpty() }

    fun respell(text: String, names: List<String>): String {
        if (text.isBlank() || names.isEmpty()) return text
        val toks = Regex("[A-Za-z0-9']+").findAll(text).map { Tok(it.value, it.range.first, it.range.last + 1) }.toList()
        if (toks.size < 2) return text
        val multi = names.map { it.trim() }.filter { split(it).size >= 2 }.distinctBy { it.lowercase() }.sortedByDescending { split(it).size }
        val after = multi.flatMap { split(it).drop(1) }.map { it.lowercase() }.toSet()
        val context = COMPANY + after
        val sentences = Regex("[^.!?]+").findAll(text).map { it.range.first to it.range.last + 1 }.toList()
        val used = BooleanArray(toks.size)
        val edits = ArrayList<Triple<Int, Int, String>>()
        for (name in multi) {
            val parts = split(name)
            val k = parts.size
            var i = 0
            while (i + k <= toks.size) {
                if ((i until i + k).any { used[it] }) { i++; continue }
                val window = toks.subList(i, i + k)
                val exact = window.indices.count { window[it].low == parts[it].lowercase() }
                if (exact == k) {
                    if (window.indices.any { window[it].text != parts[it] }) edits.add(Triple(window[0].start, window[k - 1].end, name))
                    for (j in i until i + k) used[j] = true
                    i += k; continue
                }
                if (exact >= 1 && window.indices.all { like(window[it].low, parts[it].lowercase()) } && (exact >= 2 || nearCompany(toks, i, k, context)) && !meta(toks, i, k) && !sentenceHasName(text, sentences, window[0].start, name)) {
                    edits.add(Triple(window[0].start, window[k - 1].end, name))
                    for (j in i until i + k) used[j] = true
                    i += k; continue
                }
                i++
            }
        }
        if (edits.isEmpty()) return text
        val sb = StringBuilder(text)
        for ((s, e, r) in edits.sortedByDescending { it.first }) sb.replace(s, e, r)
        return sb.toString()
    }

    private fun nearCompany(toks: List<Tok>, i: Int, k: Int, context: Set<String>): Boolean {
        val after = (i + k until minOf(toks.size, i + k + 2)).any { toks[it].low in context }
        val before = i > 0 && toks[i - 1].low in context
        return after || before
    }

    private fun meta(toks: List<Tok>, i: Int, k: Int): Boolean =
        (maxOf(0, i - 4) until i).any { toks[it].low in META } || (i + k until minOf(toks.size, i + k + 3)).any { toks[it].low in META }

    private fun sentenceHasName(text: String, sentences: List<Pair<Int, Int>>, at: Int, name: String): Boolean {
        val s = sentences.firstOrNull { at >= it.first && at < it.second } ?: return false
        return text.substring(s.first, s.second).contains(name, ignoreCase = true)
    }

    /** Whether two words sound alike: the same, or close once the vowels are set aside. A two-letter word only matches the start of a longer one. */
    internal fun like(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length <= 2 || b.length <= 2) return (a.length == 2 && b.length > 2 && b.startsWith(a)) || (b.length == 2 && a.length > 2 && a.startsWith(b))
        val x = skeleton(a); val y = skeleton(b)
        return distance(x, y) <= (if (y.length >= 4) 2 else 1)
    }

    internal fun skeleton(w: String): String {
        val sb = StringBuilder()
        for ((i, c) in w.withIndex()) {
            if (i > 0 && c in "aeiouy") continue
            if (sb.isNotEmpty() && sb.last() == c) continue
            sb.append(c)
        }
        return sb.toString()
    }

    private fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return d[a.length][b.length]
    }
}

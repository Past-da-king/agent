package com.past9.phoneaos.voice

import com.past9.phoneaos.remote.MentionProject

/**
 * The names told to the speech model with a take, and used to bias the live caption (19 Nov 2026): what this phone
 * already holds, nothing written in for a person. Order matters (the first names are the ones the model leans on):
 * 1. a list he keeps himself ("always spell these", Settings extra [ALWAYS_KEY], one name per line or comma);
 * 2. stems the projects share in data (two or more names starting with the same two or more words give that stem, so
 *    a company name that heads several projects comes first);
 * 3. the computer's project names and other names for them, projects sharing a first word with more projects first,
 *    then the most recently used project;
 * 4. his own and the agent's name. The linked computer adds the names in its own memory on top.
 * Same limits as the request: 2 to 60 characters, no repeats, at most 100.
 */
object DictationNames {
    const val MAX = 100
    const val ALWAYS_KEY = "dictation_always_spell"

    private fun words(n: String) = n.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun build(userName: String, agentName: String, projects: List<MentionProject>, always: List<String> = emptyList()): List<String> {
        val out = ArrayList<String>()
        out.addAll(always)
        val all = projects.flatMap { listOf(it.name) + it.aliases }.map { it.trim() }.filter { it.isNotEmpty() }
        val stems = LinkedHashMap<String, Int>()
        for (n in all) { val w = words(n); for (len in 2 until w.size) stems.merge(w.take(len).joinToString(" "), 1, Int::plus) }
        val stemNames = all.flatMap { n -> val w = words(n); (2 until w.size).map { w.take(it).joinToString(" ") } }
            .filter { (stems[it] ?: 0) >= 2 }.distinctBy { it.lowercase() }
        out.addAll(stemNames)
        val firstWord = projects.groupingBy { words(it.name).firstOrNull()?.lowercase().orEmpty() }.eachCount()
        val ranked = projects.sortedWith(compareByDescending<MentionProject> { firstWord[words(it.name).firstOrNull()?.lowercase().orEmpty()] ?: 0 }.thenByDescending { it.railAt })
        for (p in ranked) { out.add(p.name); out.addAll(p.aliases) }
        userName.trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
        agentName.trim().takeIf { it.isNotEmpty() && it != "Your agent" }?.let { out.add(it) }
        return out.map { it.trim() }.filter { it.length in 2..60 }.distinctBy { it.lowercase() }.take(MAX)
    }

    /** The list he keeps himself: one name per line or separated by commas. */
    fun alwaysOf(raw: String?): List<String> = raw.orEmpty().split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }
}

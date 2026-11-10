package com.past9.phoneaos.screen

/**
 * Apps the background screen never opens, shows or touches: banking, wallets, payments, cards, crypto, investing.
 *
 * The built-in rules come from assets/screen-blocklist.txt and cannot be switched off by anyone. The person can add
 * more apps; nothing can take one away, and the agent has no way to change the list. The helper (the part that owns
 * the hidden display) enforces it itself, below every tool: it refuses to start or move a blocked app, and closes one
 * at once if it lands on the hidden display some other way.
 *
 * Plain logic, no Android: the same matcher runs in the app, in the helper and in the unit tests.
 */
class ScreenBlocklist(builtIn: String, added: Collection<String> = emptyList()) {
    private val exact = HashSet<String>()
    private val patterns = ArrayList<Pair<String, Regex>>()
    private val words = ArrayList<String>()
    private val edges = ArrayList<String>()
    private val labels = ArrayList<Pair<String, Regex>>()
    private val screens = ArrayList<String>()
    /** Lines that could not be read, for the test that checks the file. */
    val problems = ArrayList<String>()
    var rules = 0; private set

    init {
        builtIn.lines().forEach { add(it, strict = true) }
        // What the person added: packages or patterns only. A line of theirs can never loosen anything.
        added.forEach { add(it.substringBefore('|'), strict = false) }
    }

    private fun glob(p: String) = Regex(p.split('*').joinToString(".*") { Regex.escape(it) }, RegexOption.IGNORE_CASE)

    private fun add(raw: String, strict: Boolean) {
        val l = raw.trim()
        if (l.isEmpty() || l.startsWith("#")) return
        val key = l.substringBefore(':', "").lowercase(); val value = l.substringAfter(':').trim().lowercase()
        when {
            strict && key == "word" && value.length >= 3 -> words += value
            strict && key == "edge" && value.length >= 3 -> edges += value
            strict && key == "label" && value.length >= 2 -> labels += value to Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(value) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            strict && key == "screen" && value.length >= 3 -> screens += value
            ':' in l -> { if (strict) problems += l; return }
            !Regex("[A-Za-z0-9_.*]+").matches(l) || l.replace("*", "").replace(".", "").length < 3 -> { if (strict) problems += l; return }
            '*' in l -> patterns += l to glob(l)
            else -> exact += l.lowercase()
        }
        rules++
    }

    /** Why [pkg] is blocked (the rule that caught it), or null when it is not. [label] is the app's name on the phone, when known. */
    fun blocked(pkg: String, label: String = ""): String? {
        val p = pkg.trim().lowercase(); if (p.isEmpty()) return null
        if (p in exact) return p
        patterns.firstOrNull { it.second.matches(p) }?.let { return it.first }
        val parts = p.split('.', '_')
        words.firstOrNull { w -> parts.any { it.contains(w) } }?.let { return "word:$it" }
        edges.firstOrNull { w -> parts.any { it == w || (it.length > w.length && (it.startsWith(w) || it.endsWith(w))) } }?.let { return "edge:$it" }
        if (label.isNotBlank()) labels.firstOrNull { it.second.containsMatchIn(label) }?.let { return "label:${it.first}" }
        return null
    }

    /** A screen the app hides from being seen AND that talks about money: treated as a money app's screen. */
    fun moneyScreen(texts: Collection<String>): String? {
        val all = texts.joinToString(" \n ").lowercase()
        return screens.firstOrNull { all.contains(it) }
    }

    companion object {
        const val ASSET = "screen-blocklist.txt"
        const val REASON = "Not allowed: banking and money apps are never used on the background screen. This cannot be switched off. Do not try another way (no link, no other app that opens it); tell the user they need to do this one themselves."
        fun reason(app: String) = "\"$app\" is a banking or money app. " + REASON
    }
}

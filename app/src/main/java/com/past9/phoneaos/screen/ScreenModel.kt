package com.past9.phoneaos.screen

import org.json.JSONObject

/**
 * One thing on the hidden display that can be read or pressed. [id] is its number in the list the agent was shown;
 * it is only good until the next look.
 */
data class ScreenElement(
    val id: Int, val cls: String, val text: String, val desc: String, val hint: String, val res: String,
    val left: Int, val top: Int, val right: Int, val bottom: Int, val flags: String, val window: Int,
) {
    val clickable get() = 'c' in flags || 'l' in flags
    val editable get() = 'e' in flags
    val scrollable get() = 's' in flags
    val focused get() = 'f' in flags
    val checked get() = 'K' in flags
    val selected get() = 'x' in flags
    val disabled get() = 'd' in flags
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    val area get() = (right - left).toLong() * (bottom - top)
    /** What a person would call it: its words, else its spoken label, else its hint. */
    val label get() = text.ifBlank { desc.ifBlank { hint } }
    fun contains(x: Int, y: Int) = x in left until right && y in top until bottom

    /** "button", "field", "text", "switch"... from the widget class. */
    val kind: String get() = when {
        editable -> "field"
        cls.contains("Switch") || cls.contains("CheckBox") || cls.contains("RadioButton") || cls.contains("ToggleButton") -> "switch"
        cls.contains("Button") -> "button"
        cls.contains("Image") -> if (clickable) "button" else "image"
        scrollable -> "list"
        clickable -> "button"
        else -> "text"
    }
}

/** Reading the helper's answer and saying it in few words. Plain logic, no Android. */
object ScreenTree {
    fun parse(reply: JSONObject): List<ScreenElement> {
        val a = reply.optJSONArray("nodes") ?: return emptyList()
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i); val b = o.optJSONArray("b")
            ScreenElement(o.optInt("i", i), o.optString("c"), o.optString("t"), o.optString("d"), o.optString("h"), o.optString("r"),
                b?.optInt(0) ?: 0, b?.optInt(1) ?: 0, b?.optInt(2) ?: 0, b?.optInt(3) ?: 0, o.optString("f"), o.optInt("w"))
        }
    }

    /** One line per element: `[12] button "Send" at 640,1480`. Read-only words that sit inside a pressable row are kept: they say what the row is. */
    fun line(e: ScreenElement): String = buildString {
        append('[').append(e.id).append("] ").append(e.kind)
        val words = e.label
        if (words.isNotBlank()) append(" \"").append(words.replace('\n', ' ').take(90)).append('"')
        else if (e.res.isNotBlank()) append(" (").append(e.res).append(')')
        if (e.editable && e.text.isBlank() && e.hint.isNotBlank()) append(" empty")
        val marks = listOfNotNull("checked".takeIf { e.checked }, "selected".takeIf { e.selected }, "focused".takeIf { e.focused && e.editable }, "off".takeIf { e.disabled })
        if (marks.isNotEmpty()) append(" (").append(marks.joinToString(", ")).append(')')
        append(" at ").append(e.centerX).append(',').append(e.centerY)
    }

    /**
     * The list for the agent. [filter] keeps only elements whose words contain it. Long screens are cut at [maxChars]
     * with a line that says so, so a busy screen never floods the conversation.
     */
    fun describe(elements: List<ScreenElement>, filter: String = "", maxChars: Int = 7000): String {
        val f = filter.trim()
        val keep = elements.filter { e ->
            if (f.isNotEmpty()) return@filter e.label.contains(f, true) || e.res.contains(f, true)
            // A layout box with no words and nothing to press says nothing.
            e.label.isNotBlank() || e.clickable || e.editable
        }
        if (keep.isEmpty()) return if (f.isNotEmpty()) "Nothing on the screen matches \"$f\"." else "The screen has nothing to read or press right now."
        val sb = StringBuilder(); var shown = 0
        for (e in keep) {
            val l = line(e)
            if (sb.length + l.length > maxChars) break
            sb.append(l).append('\n'); shown++
        }
        if (shown < keep.size) sb.append("(${keep.size - shown} more not shown. Scroll, or look again with a filter.)\n")
        return sb.toString().trimEnd()
    }

    /** The element the agent means by these words: an exact label first, then one that contains them; pressable ones win. */
    fun find(elements: List<ScreenElement>, words: String): ScreenElement? {
        val w = words.trim(); if (w.isEmpty()) return null
        val exact = elements.filter { it.label.equals(w, true) || it.text.equals(w, true) || it.desc.equals(w, true) }
        val pool = exact.ifEmpty { elements.filter { it.label.contains(w, true) || it.desc.contains(w, true) } }
        return pool.sortedWith(compareByDescending<ScreenElement> { it.clickable || it.editable }.thenBy { it.area }).firstOrNull()
    }

    /** What is under a point: the smallest element that holds it, a pressable one when there is one. */
    fun at(elements: List<ScreenElement>, x: Int, y: Int): ScreenElement? {
        val hit = elements.filter { it.contains(x, y) }
        return hit.filter { it.clickable || it.editable }.minByOrNull { it.area } ?: hit.minByOrNull { it.area }
    }

    /** A row is often pressable while its words sit in a child. The words inside [e], for the approval card and the gate. */
    fun wordsInside(elements: List<ScreenElement>, e: ScreenElement): String =
        (listOf(e.label) + elements.filter { it !== e && it.left >= e.left && it.right <= e.right && it.top >= e.top && it.bottom <= e.bottom }.map { it.label })
            .filter { it.isNotBlank() }.distinct().joinToString(" ").take(160)
}

/**
 * The safety rules for the hidden display, enforced in code rather than asked of the model.
 *
 * Per app the person chooses: Allow (the agent may press anything), Ask me (the default: it asks before anything that
 * sends, pays, deletes or otherwise goes out in their name) or Never (the agent may not open the app at all).
 */
object ScreenRules {
    enum class Rule { ALLOW, ASK, NEVER }

    /** The rules as typed in Settings: one per line, "package | allow", "package | ask" or "package | never". */
    fun parse(text: String?): Map<String, Rule> = text.orEmpty().lines().mapNotNull { raw ->
        val l = raw.trim(); if (l.isEmpty() || l.startsWith("#")) return@mapNotNull null
        val pkg = l.substringBefore('|').trim(); val r = l.substringAfter('|', "ask").trim().lowercase()
        if (pkg.isEmpty()) null else pkg to when (r) { "allow", "allowed" -> Rule.ALLOW; "never", "block", "blocked" -> Rule.NEVER; else -> Rule.ASK }
    }.toMap()

    fun ruleFor(pkg: String, rules: Map<String, Rule>): Rule = rules[pkg] ?: rules["*"] ?: Rule.ASK

    fun line(pkg: String, rule: Rule) = "$pkg | ${rule.name.lowercase()}"

    /** Words on a button that mean pressing it reaches beyond the screen. The person can add their own (other languages). */
    val WORDS = listOf("send", "post", "publish", "reply", "comment", "share", "forward", "tweet", "pay", "buy", "purchase", "order", "checkout", "check out",
        "donate", "transfer", "withdraw", "deposit", "book", "reserve", "confirm", "submit", "delete", "remove", "erase", "discard", "block", "report",
        "uninstall", "call", "dial", "accept", "decline", "subscribe", "unsubscribe", "sign out", "log out", "place order", "apply", "invite")

    fun extraWords(text: String?): List<String> = text.orEmpty().split(',', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    private fun pieces(s: String): String = s.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace(Regex("[_\\-./:]+"), " ").lowercase()

    /** The word that makes pressing this a change, or null when it is only moving around. Whole words only: "Sender" is not "send". */
    fun riskyWord(words: String, res: String, extra: List<String> = emptyList()): String? {
        val hay = " " + pieces(words).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim() + " | " + pieces(res).trim() + " "
        return (WORDS + extra).firstOrNull { w -> Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(w) + "(?![\\p{L}\\p{N}])").containsMatchIn(hay) }
    }

    sealed interface Gate {
        data object Go : Gate
        /** Ask first: [what] is the card's title, [details] exactly what will happen. */
        data class Ask(val what: String, val details: String) : Gate
        data class Refuse(val reason: String) : Gate
    }

    const val NEVER_REASON = "Not allowed: the user set this app so the agent never uses it on the background screen. Don't try another way; tell them, and they can change it in Settings > Background screen."

    /**
     * Before a press. [target] is what will be pressed (null: a bare spot with no element under it), [inside] the
     * words inside it, [typed] what sits in the screen's text fields right now (that is what a Send would send).
     */
    fun beforePress(appLabel: String, rule: Rule, target: ScreenElement?, inside: String, typed: List<String>, extra: List<String> = emptyList(), submit: Boolean = false): Gate {
        if (rule == Rule.NEVER) return Gate.Refuse(NEVER_REASON)
        if (rule == Rule.ALLOW) return Gate.Go
        val word = when {
            submit -> "send"
            target == null -> null
            target.editable -> return Gate.Go
            else -> riskyWord(inside.ifBlank { target.label }, target.res, extra)
        }
        val blind = target == null && !submit
        if (word == null && !blind) return Gate.Go
        val name = when {
            submit -> "Submit what was typed"
            blind -> "Press a spot with no label"
            else -> "Press \"${inside.ifBlank { target!!.label }.ifBlank { target!!.res }.take(60)}\""
        }
        val details = buildString {
            append("On the background screen, in ").append(appLabel).append('.')
            if (typed.isNotEmpty()) append("\nTyped: ").append(typed.joinToString(" / ") { "\"" + it.take(300) + "\"" })
            if (blind) append("\nThe agent chose a point on the picture, so the app cannot say what it does.")
        }
        return Gate.Ask("$name in $appLabel", details)
    }
}

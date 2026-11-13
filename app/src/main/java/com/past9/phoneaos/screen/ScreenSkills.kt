package com.past9.phoneaos.screen

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turning a run on the background screen into something that can be done again: the steps, each with a way to find
 * its element on another day (its id in the app, its words, its kind, its place among its like), and the parts that
 * change from one time to the next as named inputs. Plain logic, no Android.
 *
 * A learned step: {"do": tap|long|type|swipe|key|wait|open, "sel": {...}, "text": "...{input}...", "extra": {...}, "ms": how long to wait for its element}
 */
object ScreenSkills {
    /** Kinds of step in a run that are part of doing the job. Looking, asking and notes are not. */
    val DOING = setOf("open", "share", "tap", "long", "type", "swipe", "key", "wait")
    const val DEFAULT_WAIT_MS = 6_000L

    /** How to find [e] again: by what it is, not by where it happened to be. */
    fun selector(e: ScreenElement, all: List<ScreenElement>, width: Int, height: Int): JSONObject {
        val like = all.filter { it.res == e.res && it.kind == e.kind && it.cls == e.cls }
        return JSONObject().put("res", e.res).put("text", e.text.take(120)).put("desc", e.desc.take(120)).put("hint", e.hint.take(80)).put("kind", e.kind).put("cls", e.cls)
            .put("nth", like.indexOf(e).coerceAtLeast(0))
            // Many apps put a button's words in a child element and leave the button itself bare: those words are how it is known.
            .put("inside", if (e.label.isBlank()) ScreenTree.wordsInside(all, e).take(120) else "")
            // Where it was, as parts of the screen: only for the last resort, when nothing else finds it.
            .put("fx", if (width > 0) e.centerX.toDouble() / width else 0.5).put("fy", if (height > 0) e.centerY.toDouble() / height else 0.5)
    }

    /** `{name}` in [template] becomes that input's value. */
    fun fill(template: String, args: JSONObject): String =
        Regex("\\{([a-zA-Z][a-zA-Z0-9_]*)\\}").replace(template) { m -> if (args.has(m.groupValues[1])) args.opt(m.groupValues[1]).toString() else m.value }

    /** The inputs a template still needs. */
    fun inputsIn(template: String): List<String> = Regex("\\{([a-zA-Z][a-zA-Z0-9_]*)\\}").findAll(template).map { it.groupValues[1] }.toList()

    /** Put `{name}` where [value] has one of this run's changing parts. Longest first, so "Mom and Dad" wins over "Mom". */
    fun templatize(value: String, params: Map<String, String>): String {
        var out = value
        for ((name, literal) in params.entries.sortedByDescending { it.value.length }) if (literal.isNotBlank()) out = out.replace(literal, "{$name}", ignoreCase = true)
        return out
    }

    /**
     * The element a selector means, on the screen as it is now. Its words must match when it had words; without
     * words (an icon, an empty field) its id in the app must match, or failing that its kind and place.
     */
    fun find(sel: JSONObject, all: List<ScreenElement>, args: JSONObject = JSONObject()): ScreenElement? {
        val res = sel.optString("res"); val text = fill(sel.optString("text"), args); val desc = fill(sel.optString("desc"), args)
        val hint = sel.optString("hint"); val kind = sel.optString("kind"); val nth = sel.optInt("nth", 0)
        val inside = fill(sel.optString("inside"), args)
        // A text field's words are what was typed last time, not what it is: find fields by id, hint and place.
        val field = kind == "field"
        fun wordsMatch(e: ScreenElement): Int = when {
            field -> 0
            inside.isNotBlank() && e.label.isBlank() && ScreenTree.wordsInside(all, e).equals(inside, true) -> 4
            text.isNotBlank() && (e.text.equals(text, true) || e.label.equals(text, true)) -> 4
            desc.isNotBlank() && e.desc.equals(desc, true) -> 4
            text.isNotBlank() && e.label.contains(text, true) -> 2
            desc.isNotBlank() && e.desc.contains(desc, true) -> 2
            else -> 0
        }
        val hasWords = !field && (text.isNotBlank() || desc.isNotBlank() || inside.isNotBlank())
        val scored = all.mapNotNull { e ->
            val w = wordsMatch(e)
            val r = if (res.isNotBlank() && e.res == res) 4 else 0
            val h = if (field && hint.isNotBlank() && (e.hint.equals(hint, true) || e.desc.equals(hint, true))) 3 else 0
            val k = if (e.kind == kind) 1 else 0
            if (hasWords && w == 0) return@mapNotNull null
            if (!hasWords && r == 0 && h == 0 && (k == 0 || res.isNotBlank() && all.any { it.res == res })) return@mapNotNull null
            e to (w + r + h + k)
        }
        // No exact or partial match: the closest words on the screen, when one is clearly it (a file or a contact named a little differently).
        if (scored.isEmpty()) return if (hasWords) ScreenFuzzy.best(all, text.ifBlank { desc }, 0.8) else null
        val best = scored.maxOf { it.second }
        val top = scored.filter { it.second == best }.map { it.first }
        // Several alike (rows of a list, a row of icons): the same place among them as when it was learned.
        return if (top.size == 1 || hasWords) top.first() else top.getOrNull(nth) ?: top.first()
    }

    /**
     * The steps of a learned tool, from what was done in a run. [params]: input name to the value it had in this run;
     * wherever that value was typed or pressed, the step takes the input instead. Returns the steps and what could not be kept.
     */
    fun fromRun(run: List<RunStep>, params: Map<String, String>): Pair<JSONArray, List<String>> {
        val out = JSONArray(); val notes = ArrayList<String>()
        var secrets = 0
        for (s in run) {
            if (s.kind !in DOING) continue
            // A wait with no words was only "let it settle": on a replay each step waits for its own element instead.
            if (s.kind == "wait" && s.text.isBlank() && (s.extra?.optInt("stable") ?: 0) <= 0) continue
            // Opening the app again mid-run starts it over: only what came after the last open is the way in.
            if (s.kind == "open" || s.kind == "share") { while (out.length() > 0) out.remove(0) }
            val o = JSONObject().put("do", s.kind)
            s.sel?.let { sel ->
                val c = JSONObject(sel.toString())
                c.put("text", templatize(c.optString("text"), params)); c.put("desc", templatize(c.optString("desc"), params)); c.put("inside", templatize(c.optString("inside"), params))
                // A press on "whichever row matched this time" is found again by its id, kind and place among its like, never by the words that row had.
                if (s.extra?.optBoolean("generic") == true) { c.put("text", ""); c.put("desc", ""); c.put("inside", ""); c.put("hint", "") }
                o.put("sel", c)
            }
            if (s.secret) { secrets++; o.put("text", "{secret$secrets}"); notes += "A hidden text was typed: it is the input secret$secrets and is never stored." }
            else if (s.text.isNotBlank()) o.put("text", templatize(s.text, params))
            s.extra?.let { o.put("extra", JSONObject(it.toString()).apply { for (k in listOf("link", "files", "text")) if (has(k)) put(k, templatize(optString(k), params)) }) }
            o.put("ms", DEFAULT_WAIT_MS)
            out.put(o)
        }
        return out to notes
    }

    /** Every input the steps take, in the order they are first needed. */
    fun inputs(steps: JSONArray): List<String> = (0 until steps.length()).flatMap { i ->
        val s = steps.getJSONObject(i)
        inputsIn(s.optString("text")) + inputsIn(s.optJSONObject("sel")?.optString("text").orEmpty()) + inputsIn(s.optJSONObject("sel")?.optString("desc").orEmpty()) +
            listOf("link", "files", "text").flatMap { k -> inputsIn(s.optJSONObject("extra")?.optString(k).orEmpty()) }
    }.distinct()

    /** The package the steps open, from their open step. */
    fun say(step: JSONObject): String {
        val sel = step.optJSONObject("sel"); val words = sel?.let { it.optString("text").ifBlank { it.optString("desc").ifBlank { it.optString("inside") }.ifBlank { it.optString("hint").ifBlank { it.optString("res") } } } }.orEmpty()
        return when (step.optString("do")) {
            "open" -> "Open the app" + step.optJSONObject("extra")?.optString("link")?.takeIf { it.isNotBlank() }?.let { " at $it" }.orEmpty()
            "share" -> "Hand the app ${step.optJSONObject("extra")?.optString("files").orEmpty().ifBlank { "nothing" }}" + step.optJSONObject("extra")?.optString("text")?.takeIf { it.isNotBlank() }?.let { " with the words \"$it\"" }.orEmpty()
            "read" -> "Read \"${step.optString("name")}\" off the screen and hand it back"
            "check" -> "Make sure \"${step.optString("text")}\" is on the screen at the end"
            "tap" -> "Press \"$words\""
            "long" -> "Press and hold \"$words\""
            "type" -> "Type \"${step.optString("text")}\"" + (if (words.isNotBlank()) " in \"$words\"" else "") + if (step.optJSONObject("extra")?.optBoolean("submit") == true) " and submit" else ""
            "swipe" -> "Swipe ${step.optJSONObject("extra")?.optString("dir").orEmpty().ifBlank { "across" }}"
            "key" -> "Press the ${step.optJSONObject("extra")?.optString("key")} key"
            "wait" -> "Wait until \"${step.optString("text")}\" ${if (step.optJSONObject("extra")?.optBoolean("gone") == true) "is gone" else "shows"}"
            else -> step.optString("do")
        }
    }

    /** How to read a value off the final screen again: where it sits (its id in the app, or the words just before it), never what it said that day. */
    fun readStep(name: String, value: String, all: List<ScreenElement>, params: Map<String, String>): JSONObject? {
        if (value.isBlank()) return null
        val shown = all.filter { !it.editable && it.label.isNotBlank() }
        val e = shown.firstOrNull { it.label.equals(value, true) } ?: shown.filter { it.label.contains(value, true) }.minByOrNull { it.label.length } ?: return null
        val at = e.label.indexOf(value, ignoreCase = true).coerceAtLeast(0)
        val like = shown.filter { it.res == e.res && it.cls == e.cls }
        return JSONObject().put("do", "read").put("name", name).put("res", e.res).put("cls", e.cls).put("nth", like.indexOf(e).coerceAtLeast(0))
            .put("after", templatize(shown.getOrNull(shown.indexOf(e) - 1)?.label?.take(80).orEmpty(), params))
            .put("pre", e.label.take(at)).put("post", e.label.substring((at + value.length).coerceAtMost(e.label.length)))
    }

    fun readValue(step: JSONObject, all: List<ScreenElement>, args: JSONObject = JSONObject()): String {
        val res = step.optString("res"); val cls = step.optString("cls"); val after = fill(step.optString("after"), args)
        val shown = all.filter { !it.editable && it.label.isNotBlank() }
        val e = (if (res.isNotBlank()) shown.filter { it.res == res && it.cls == cls }.let { it.getOrNull(step.optInt("nth")) ?: it.firstOrNull() } else null)
            ?: (if (after.isNotBlank()) shown.indexOfFirst { it.label.equals(after, true) }.takeIf { it >= 0 }?.let { shown.getOrNull(it + 1) } else null)
            ?: return ""
        var v = e.label
        val pre = step.optString("pre"); val post = step.optString("post")
        if (pre.isNotBlank() && v.startsWith(pre, true)) v = v.substring(pre.length)
        if (post.isNotBlank() && v.endsWith(post, true)) v = v.substring(0, v.length - post.length)
        return v.trim()
    }

    /** What an input may be. Null when [value] is fine for [kind], else what is wrong with it. */
    fun badInput(kind: String, value: String): String? = when {
        kind == "number" && value.trim().toDoubleOrNull() == null -> "must be a number, got \"${value.take(30)}\""
        kind == "date" && runCatching { java.time.LocalDate.parse(value.trim().take(10)) }.isFailure -> "must be a date like 2026-11-15, got \"${value.take(30)}\""
        kind.startsWith("choice:") && kind.substringAfter(':').split('|').none { it.trim().equals(value.trim(), true) } -> "must be one of ${kind.substringAfter(':').split('|').joinToString { it.trim() }}"
        else -> null
    }

    fun inputHelp(kind: String): String = when {
        kind == "contact" -> "A person or chat by name, as the app shows it. The closest name is used; if two are equally close the job stops instead of guessing. "
        kind == "place" -> "A place name or an address. "
        kind == "file" -> "The full path of a file on the phone. "
        kind == "number" -> "A number. "
        kind == "date" -> "A date, 2026-11-15 style. "
        kind.startsWith("choice:") -> "One of: ${kind.substringAfter(':').split('|').joinToString { it.trim() }}. "
        else -> ""
    }

    fun describe(steps: JSONArray): String = (0 until steps.length()).joinToString("\n") { "${it + 1}. ${say(steps.getJSONObject(it))}" }

    /**
     * After a replay that worked: drop the steps that turned out not to be needed, and give each step a wait that
     * fits how long its element really took to show (three times that, at least a second and a half).
     * Returns the new steps, or null when nothing changed enough to keep.
     */
    fun refine(steps: JSONArray, stepMs: List<Long>, skipped: Set<Int>): JSONArray? {
        val out = JSONArray(); var changed = skipped.isNotEmpty()
        for (i in 0 until steps.length()) {
            if (i in skipped) continue
            val s = JSONObject(steps.getJSONObject(i).toString())
            stepMs.getOrNull(i)?.let { took ->
                val fit = (took * 3 + 500).coerceIn(1_500, 15_000)
                // Only ever a real change: timings wobble, and every version kept should mean something.
                if (kotlin.math.abs(fit - s.optLong("ms", DEFAULT_WAIT_MS)) > 1_500) { s.put("ms", fit); changed = true }
            }
            out.put(s)
        }
        return if (changed) out else null
    }
}

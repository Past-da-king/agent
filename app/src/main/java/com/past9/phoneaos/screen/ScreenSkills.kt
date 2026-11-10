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
    val DOING = setOf("open", "tap", "long", "type", "swipe", "key", "wait")
    const val DEFAULT_WAIT_MS = 6_000L

    /** How to find [e] again: by what it is, not by where it happened to be. */
    fun selector(e: ScreenElement, all: List<ScreenElement>, width: Int, height: Int): JSONObject {
        val like = all.filter { it.res == e.res && it.kind == e.kind && it.cls == e.cls }
        return JSONObject().put("res", e.res).put("text", e.text.take(120)).put("desc", e.desc.take(120)).put("hint", e.hint.take(80)).put("kind", e.kind).put("cls", e.cls)
            .put("nth", like.indexOf(e).coerceAtLeast(0))
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
        // A text field's words are what was typed last time, not what it is: find fields by id, hint and place.
        val field = kind == "field"
        fun wordsMatch(e: ScreenElement): Int = when {
            field -> 0
            text.isNotBlank() && (e.text.equals(text, true) || e.label.equals(text, true)) -> 4
            desc.isNotBlank() && e.desc.equals(desc, true) -> 4
            text.isNotBlank() && e.label.contains(text, true) -> 2
            desc.isNotBlank() && e.desc.contains(desc, true) -> 2
            else -> 0
        }
        val hasWords = !field && (text.isNotBlank() || desc.isNotBlank())
        val scored = all.mapNotNull { e ->
            val w = wordsMatch(e)
            val r = if (res.isNotBlank() && e.res == res) 4 else 0
            val h = if (field && hint.isNotBlank() && (e.hint.equals(hint, true) || e.desc.equals(hint, true))) 3 else 0
            val k = if (e.kind == kind) 1 else 0
            if (hasWords && w == 0) return@mapNotNull null
            if (!hasWords && r == 0 && h == 0 && (k == 0 || res.isNotBlank() && all.any { it.res == res })) return@mapNotNull null
            e to (w + r + h + k)
        }
        if (scored.isEmpty()) return null
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
            if (s.kind == "wait" && s.text.isBlank()) continue
            // Opening the app again mid-run starts it over: only what came after the last open is the way in.
            if (s.kind == "open") { while (out.length() > 0) out.remove(0) }
            val o = JSONObject().put("do", s.kind)
            s.sel?.let { sel ->
                val c = JSONObject(sel.toString())
                c.put("text", templatize(c.optString("text"), params)); c.put("desc", templatize(c.optString("desc"), params))
                o.put("sel", c)
            }
            if (s.secret) { secrets++; o.put("text", "{secret$secrets}"); notes += "A hidden text was typed: it is the input secret$secrets and is never stored." }
            else if (s.text.isNotBlank()) o.put("text", templatize(s.text, params))
            s.extra?.let { o.put("extra", JSONObject(it.toString()).apply { if (has("link")) put("link", templatize(optString("link"), params)) }) }
            o.put("ms", DEFAULT_WAIT_MS)
            out.put(o)
        }
        return out to notes
    }

    /** Every input the steps take, in the order they are first needed. */
    fun inputs(steps: JSONArray): List<String> = (0 until steps.length()).flatMap { i ->
        val s = steps.getJSONObject(i)
        inputsIn(s.optString("text")) + inputsIn(s.optJSONObject("sel")?.optString("text").orEmpty()) + inputsIn(s.optJSONObject("sel")?.optString("desc").orEmpty()) +
            inputsIn(s.optJSONObject("extra")?.optString("link").orEmpty())
    }.distinct()

    /** The package the steps open, from their open step. */
    fun say(step: JSONObject): String {
        val sel = step.optJSONObject("sel"); val words = sel?.let { it.optString("text").ifBlank { it.optString("desc").ifBlank { it.optString("hint").ifBlank { it.optString("res") } } } }.orEmpty()
        return when (step.optString("do")) {
            "open" -> "Open the app" + step.optJSONObject("extra")?.optString("link")?.takeIf { it.isNotBlank() }?.let { " at $it" }.orEmpty()
            "tap" -> "Press \"$words\""
            "long" -> "Press and hold \"$words\""
            "type" -> "Type \"${step.optString("text")}\"" + (if (words.isNotBlank()) " in \"$words\"" else "") + if (step.optJSONObject("extra")?.optBoolean("submit") == true) " and submit" else ""
            "swipe" -> "Swipe ${step.optJSONObject("extra")?.optString("dir").orEmpty().ifBlank { "across" }}"
            "key" -> "Press the ${step.optJSONObject("extra")?.optString("key")} key"
            "wait" -> "Wait until \"${step.optString("text")}\" ${if (step.optJSONObject("extra")?.optBoolean("gone") == true) "is gone" else "shows"}"
            else -> step.optString("do")
        }
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

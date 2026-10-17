package com.past9.phoneaos.ui.openui

/**
 * OpenUI Lang 1.0 (https://github.com/thesysdev/openui/tree/main/spec), the compact line language a model writes to
 * describe a UI: `root = Card([header, chart])`, one statement per line, names usable before they are defined.
 *
 * Parses literals, arrays, objects, component calls, references, `$state` and `@` actions, closes open
 * brackets and strings so a half-streamed program still renders, and drops only a broken statement. Operators,
 * queries, mutations and forms are not run yet.
 */
object OpenUiLang {
    sealed interface V
    data class Str(val v: String) : V
    data class Num(val v: Double) : V
    data class Bool(val v: Boolean) : V
    data object Null : V
    data class Arr(val items: List<V>) : V
    data class Obj(val fields: Map<String, V>) : V
    data class Call(val name: String, val args: List<V>) : V
    data class Ref(val name: String) : V
    data class Fn(val name: String, val args: List<V>) : V

    data class Statement(val name: String, val value: V)
    data class Program(val statements: Map<String, Statement>, val order: List<String>, val errors: List<String>, val incomplete: Boolean)

    /** A rendered component: its type and props by name. */
    data class Node(val type: String, val props: Map<String, Any?>)
    /** What a button or list item does when tapped. */
    sealed interface Action { data class ToAssistant(val text: String, val context: Any?) : Action; data class OpenUrl(val url: String) : Action }

    /** The program inside a ```openui-lang fence (or bare). Comments (`//`) are dropped. */
    fun parse(src: String): Program {
        val text = src.lines().filterNot { it.trim().startsWith("```") }.joinToString("\n")
        val p = Parser(text); val stmts = LinkedHashMap<String, Statement>(); val errors = mutableListOf<String>()
        while (true) {
            p.skipBlank()
            if (p.eof()) break
            val start = p.pos
            try {
                val name = p.ident() ?: throw ParseError("expected a name")
                p.ws(); if (!p.eat('=')) throw ParseError("expected =")
                val v = p.expr()
                p.ws(); if (!p.eof() && p.peek() != '\n') throw ParseError("trailing text")
                stmts.remove(name); stmts[name] = Statement(name, v)   // a later binding wins
            } catch (e: ParseError) {
                errors += "line ${text.substring(0, start).count { it == '\n' } + 1}: ${e.message}"
                p.pos = start; p.skipLine()
            }
        }
        return Program(stmts, stmts.keys.toList(), errors, p.incomplete)
    }

    private class ParseError(msg: String) : Exception(msg)

    private class Parser(val s: String) {
        var pos = 0
        var incomplete = false
        fun eof() = pos >= s.length
        fun peek() = s[pos]
        fun ws() { while (!eof() && (s[pos] == ' ' || s[pos] == '\t' || s[pos] == '\r')) pos++; comment() }
        private fun comment() { if (pos + 1 < s.length && s[pos] == '/' && s[pos + 1] == '/') while (!eof() && s[pos] != '\n') pos++ }
        /** Inside brackets a newline is just whitespace. */
        fun wsn() { while (!eof() && s[pos].isWhitespace()) pos++; comment(); if (!eof() && s[pos].isWhitespace()) wsn() }
        fun skipBlank() { while (true) { wsn(); if (eof() || !(s[pos] == '/' && pos + 1 < s.length && s[pos + 1] == '/')) return } }
        fun skipLine() { var depth = 0; var inStr = false
            while (!eof()) { val c = s[pos]
                if (inStr) { if (c == '\\') pos++ else if (c == '"') inStr = false }
                else when (c) { '"' -> inStr = true; '(', '[', '{' -> depth++; ')', ']', '}' -> depth--; '\n' -> if (depth <= 0) { pos++; return } }
                pos++ } }
        fun eat(c: Char): Boolean { if (!eof() && s[pos] == c) { pos++; return true }; return false }
        fun ident(): String? {
            val st = pos
            if (!eof() && (s[pos] == '$' || s[pos] == '@')) pos++
            while (!eof() && (s[pos].isLetterOrDigit() || s[pos] == '_')) pos++
            return if (pos > st && !(pos == st + 1 && (s[st] == '$' || s[st] == '@'))) s.substring(st, pos) else { pos = st; null }
        }
        fun expr(): V {
            wsn()
            if (eof()) { incomplete = true; return Null }   // the stream stopped right here
            val c = s[pos]
            return when {
                c == '"' -> Str(string())
                c == '[' -> { pos++; Arr(list(']')) }
                c == '{' -> { pos++; obj() }
                c == '-' || c.isDigit() -> num()
                c == '(' -> { pos++; val v = expr(); wsn(); if (!eat(')')) { if (eof()) incomplete = true else throw ParseError("expected )") }; v }
                else -> {
                    val id = ident() ?: throw ParseError("unexpected '${c}'")
                    when (id) { "true" -> return Bool(true); "false" -> return Bool(false); "null" -> return Null }
                    ws()
                    if (!eof() && s[pos] == '(') { pos++; val args = list(')'); if (id.startsWith("@")) Fn(id.drop(1), args) else Call(id, args) }
                    else if (eof() && id.first().isUpperCase()) { incomplete = true; Ref(id) }   // `Head` may become `Header(...)`: treat as pending
                    else Ref(id)
                }
            }
        }
        fun list(close: Char): List<V> {
            val out = mutableListOf<V>()
            while (true) {
                wsn()
                if (eof()) { incomplete = true; return out }
                if (eat(close)) return out
                out += expr()
                wsn(); eat(',')
            }
        }
        fun obj(): Obj {
            val out = LinkedHashMap<String, V>()
            while (true) {
                wsn()
                if (eof()) { incomplete = true; return Obj(out) }
                if (eat('}')) return Obj(out)
                val key = if (s[pos] == '"') string() else ident() ?: throw ParseError("expected a key")
                wsn(); if (!eat(':')) { if (eof()) { incomplete = true; return Obj(out) }; throw ParseError("expected :") }
                out[key] = expr(); wsn(); eat(',')
            }
        }
        fun string(): String {
            pos++; val b = StringBuilder()
            while (true) {
                if (eof()) { incomplete = true; return b.toString() }   // closes itself while streaming
                val c = s[pos++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> { if (eof()) { incomplete = true; return b.toString() }
                        when (val e = s[pos++]) { 'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r')
                            'u' -> { val h = s.substring(pos, minOf(pos + 4, s.length)); if (h.length == 4) { b.append(h.toInt(16).toChar()); pos += 4 } else { incomplete = true; pos = s.length } }
                            else -> b.append(e) } }
                    else -> b.append(c)
                }
            }
        }
        fun num(): V {
            val st = pos; if (s[pos] == '-') pos++
            while (!eof() && (s[pos].isDigit() || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E' || ((s[pos] == '+' || s[pos] == '-') && (s[pos - 1] == 'e' || s[pos - 1] == 'E')))) pos++
            return s.substring(st, pos).toDoubleOrNull()?.let { Num(it) } ?: throw ParseError("bad number")
        }
    }

    /** Prop names by position, from OpenUI's chat library (react-ui 0.18). Unknown components are dropped. */
    val LIBRARY: Map<String, List<String>> = mapOf(
        // From OpenUI's playground library: several Cards, one per item ("row" swipes, "column" stacks).
        "Stack" to listOf("children", "direction", "gap", "align", "justify", "wrap"),
        "Card" to listOf("children", "sources"), "CardHeader" to listOf("title", "subtitle"),
        "TextContent" to listOf("text", "size"), "MarkDownRenderer" to listOf("textMarkdown", "variant"),
        "Callout" to listOf("variant", "title", "description", "visible"), "Separator" to listOf("orientation", "decorative"),
        "Table" to listOf("columns"), "Col" to listOf("label", "data", "type"), "Series" to listOf("category", "values"),
        "BarChart" to listOf("labels", "series", "variant", "xLabel", "yLabel", "height"),
        "LineChart" to listOf("labels", "series", "variant", "xLabel", "yLabel", "height"),
        "PieChart" to listOf("labels", "values", "variant", "appearance"),
        "Buttons" to listOf("buttons", "direction"), "Button" to listOf("label", "action", "variant", "type", "size"),
        "ListBlock" to listOf("items", "variant", "size"), "ListItem" to listOf("title", "subtitle", "image", "actionLabel", "action"),
        "FollowUpBlock" to listOf("items"), "FollowUpItem" to listOf("text"),
        "Steps" to listOf("items"), "StepsItem" to listOf("title", "details"),
        "TagBlock" to listOf("tags", "size"), "Tag" to listOf("text", "icon", "size", "variant"),
        "ImageBlock" to listOf("src", "alt"), "Image" to listOf("alt", "src"),
        "InlineHeader" to listOf("heading", "description"),
        "EntityList" to listOf("rows", "size", "header", "footer"),
        "TextCallout" to listOf("variant", "title", "description"),
        // Lucide icons (Tag's optional icon): accepted, not drawn yet.
        "Icon" to listOf("name", "category"),
        // Ours, not OpenUI's: one thing for sale, for a row of them to swipe through (research, shopping).
        "Product" to listOf("image", "name", "price", "rating", "reviews", "seller", "was", "tag", "actions"),
    )
    /** The library's root is Stack (one Card per item); a lone Card is accepted too. */
    val ROOTS = setOf("Stack", "Card")

    /** Where a tap happened: the card it is on and the item inside it (a section heading or a list row). */
    data class Scope(val card: String? = null, val item: String? = null)

    /**
     * What a tap on @ToAssistant sends into the chat as the user's message: the instruction, then one line saying which
     * card and item it is about plus the model's context, so the agent acts on that specific one.
     */
    fun messageFor(a: Action.ToAssistant, scope: Scope): String {
        val parts = mutableListOf<String>()
        fun add(s: String?) { val v = s?.trim().orEmpty(); if (v.isNotEmpty() && parts.none { it.equals(v, true) } && !a.text.trim().equals(v, true)) parts += v }
        add(scope.item); add(scope.card)
        when (val c = a.context) {
            is Map<*, *> -> c.forEach { (k, v) -> val s = value(v); if (s.isNotBlank()) add(if (k == "item" || k == "name" || k == "title") s else "$k: $s") }
            null -> {}
            else -> add(value(c))
        }
        return if (parts.isEmpty()) a.text.trim() else a.text.trim() + "\n\u21b3 " + parts.joinToString(" \u00b7 ")
    }

    private fun value(v: Any?): String = when (v) {
        null -> ""
        is Double -> if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        is Map<*, *> -> v.entries.joinToString(", ") { "${it.key}: ${value(it.value)}" }
        is List<*> -> v.joinToString(", ") { value(it) }
        else -> v.toString()
    }

    private val FENCE = Regex("```openui[^\\n]*\\n([\\s\\S]*?)(```|\\z)")

    /**
     * The message for places that can't draw cards (a notification, the Home preview): each openui-lang block becomes
     * the titles of what it shows, e.g. "R7,200 / month, 62 m2 · R6,800 / month, 38 m2".
     */
    fun plainText(md: String): String = FENCE.replace(md) { m ->
        val titles = mutableListOf<String>()
        fun walk(v: Any?) { when (v) {
            is Node -> { when (v.type) { "CardHeader" -> v.props["title"]; "InlineHeader" -> v.props["heading"]; "ListItem", "StepsItem" -> v.props["title"]; else -> null }
                ?.let { (it as? String)?.takeIf { s -> s.isNotBlank() }?.let { s -> titles += s } }
                v.props.values.forEach { walk(it) } }
            is List<*> -> v.forEach { walk(it) }
        } }
        walk(render(parse(m.groupValues[1])).root)
        titles.distinct().take(4).joinToString(" \u00b7 ")
    }.replace(Regex("\\n{3,}"), "\n\n").trim()

    data class Rendered(val root: Node?, val unresolved: Set<String>, val errors: List<String>)

    /** The tree from the entry: `root`, else the first statement that calls the root component. */
    fun render(p: Program): Rendered {
        val unresolved = linkedSetOf<String>(); val errors = p.errors.toMutableList()
        val entry = p.statements["root"] ?: p.order.map { p.statements.getValue(it) }.firstOrNull { (it.value as? Call)?.name in ROOTS }
            ?: return Rendered(null, unresolved, errors + "no-root")
        fun eval(v: V, seen: Set<String>): Any? = when (v) {
            is Str -> v.v; is Num -> v.v; is Bool -> v.v; Null -> null
            is Arr -> v.items.mapNotNull { eval(it, seen) }   // a pending reference just isn't there yet
            is Obj -> v.fields.mapValues { eval(it.value, seen) }
            is Ref -> if (v.name in seen) null.also { errors += "cycle at ${v.name}" }
                else p.statements[v.name]?.let { eval(it.value, seen + v.name) } ?: null.also { unresolved += v.name }
            is Fn -> when (v.name) {
                "ToAssistant" -> Action.ToAssistant(eval(v.args.getOrElse(0) { Null }, seen) as? String ?: "", v.args.getOrNull(1)?.let { eval(it, seen) })
                "OpenUrl" -> (eval(v.args.getOrElse(0) { Null }, seen) as? String)?.let { Action.OpenUrl(it) }
                else -> null.also { errors += "unsupported @${v.name}" }
            }
            is Call -> if (v.name == "Action") (eval(v.args.getOrElse(0) { Null }, seen) as? List<*>)?.filterIsInstance<Action>()
                else LIBRARY[v.name]?.let { names -> Node(v.name, names.zip(v.args).associate { (n, a) -> n to eval(a, seen) }.filterValues { it != null }) }
                    ?: null.also { errors += "unknown-component ${v.name}" }
        }
        return Rendered(eval(entry.value, setOf(entry.name)) as? Node, unresolved, errors)
    }
}

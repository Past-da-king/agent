package com.past9.phoneaos.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Surface
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Just enough markdown for chat: headings, bullets, numbered lists, tables, **bold**, *italic*, `code`, links. */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge, color: Color = MaterialTheme.colorScheme.onSurface,
             onWikiLink: ((String) -> Unit)? = null, knownPages: Set<String> = emptySet()) {
    val parts = remember(text) { MdTable.split(text) }
    if (parts.size == 1 && parts[0] is MdPart.Prose) return Prose((parts[0] as MdPart.Prose).text, modifier, style, color, onWikiLink, knownPages)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { p ->
            when (p) {
                is MdPart.Prose -> Prose(p.text, Modifier, style, color, onWikiLink, knownPages)
                is MdPart.Table -> TableView(p.table, style, color, onWikiLink, knownPages)
            }
        }
    }
}

@Composable
private fun Prose(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge, color: Color = MaterialTheme.colorScheme.onSurface,
             onWikiLink: ((String) -> Unit)? = null, knownPages: Set<String> = emptySet()) {
    val link = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val known = remember(knownPages) { knownPages.map { it.lowercase() }.toSet() }
    fun inline(s: String, l: Color, bg: Color) = inline(s, l, bg, onWikiLink, known, muted)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        text.trim().split("\n").fold(mutableListOf<String>()) { acc, line ->
            val pureImage = line.isNotBlank() && splitImages(line).first.isEmpty()
            if (pureImage && acc.isNotEmpty() && acc.last().isNotEmpty() && splitImages(acc.last()).first.isEmpty()) acc[acc.lastIndex] = acc.last() + " " + line.trim()
            else if (line.isBlank()) acc += "" else if (acc.isNotEmpty() && acc.last().isNotEmpty() && !isBlockStart(line) && !isBlockStart(acc.last())) acc[acc.lastIndex] = acc.last() + " " + line.trim() else acc += line
            acc
        }.filter { it.isNotEmpty() }.forEach { raw ->
            val (line, images) = splitImages(raw)
            val t = line.trimStart()
            when {
                t.isEmpty() -> {}
                t.startsWith("#") -> Text(inline(t.trimStart('#').trim(), link, codeBg), style = MaterialTheme.typography.titleMedium, color = color)
                Regex("^[-*•] ").containsMatchIn(t) -> Row {
                    Text("•", style = style, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(18.dp).padding(start = 4.dp))
                    Text(inline(t.drop(2), link, codeBg), style = style, color = color)
                }
                Regex("^\\d+[.)] ").containsMatchIn(t) -> Row {
                    val n = t.substringBefore(' ')
                    Text(n, style = style.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(26.dp))
                    Text(inline(t.substringAfter(' '), link, codeBg), style = style, color = color)
                }
                else -> Text(inline(t, link, codeBg), style = style, color = color)
            }
            if (images.isNotEmpty()) ImageStrip(images, Modifier.padding(vertical = 4.dp))
        }
    }
}

private fun isBlockStart(l: String) = l.trimStart().let { it.startsWith("#") || Regex("^([-*•]|\\d+[.)]) ").containsMatchIn(it) }

private val token = Regex("""\[\[([^\]|]+)(?:\|([^\]]*))?]]|\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)\]\((https?://[^)\s]+)\)|(?<![*\w])\*(?!\s)(.+?)(?<!\s)\*(?!\w)|(https?://[^\s)]+)""")

fun inline(s: String, linkColor: Color, codeBg: Color, onWiki: ((String) -> Unit)? = null, known: Set<String> = emptySet(), muted: Color = linkColor): AnnotatedString = buildAnnotatedString {
    var i = 0
    val linkStyle = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    token.findAll(s).forEach { m ->
        append(s.substring(i, m.range.first))
        val g0 = m.groupValues
        // Shift so the original group numbers below stay as they were before wiki links existed.
        val g = listOf(g0[0]) + g0.drop(3)
        when {
            g0[1].isNotEmpty() -> {
                val target = g0[1].trim(); val label = g0[2].ifBlank { target }
                val exists = target.lowercase() in known
                if (onWiki == null) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(label) }
                else withLink(LinkAnnotation.Clickable("wiki:$target", TextLinkStyles(SpanStyle(color = if (exists) linkColor else muted, fontWeight = FontWeight.SemiBold,
                    textDecoration = if (exists) null else TextDecoration.Underline))) { onWiki(target) }) { append(label) }
            }
            g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(g[1]) }
            g[2].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(" ${g[2]} ") }
            g[3].isNotEmpty() -> withLink(LinkAnnotation.Url(g[4], linkStyle)) { append(g[3]) }
            g[5].isNotEmpty() -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(g[5]) }
            g[6].isNotEmpty() -> withLink(LinkAnnotation.Url(g[6], linkStyle)) { append(g[6].removePrefix("https://").removePrefix("www.").let { if (it.length > 40) it.take(38) + "…" else it }) }
        }
        i = m.range.last + 1
    }
    append(s.substring(i))
}

/** A piece of a message: ordinary markdown, or a table. */
sealed interface MdPart {
    data class Prose(val text: String) : MdPart
    data class Table(val table: MdTable) : MdPart
}

/** A GitHub-style markdown table: header, column alignments, rows (padded to the header's width). */
data class MdTable(val header: List<String>, val align: List<Char>, val rows: List<List<String>>) {
    /** Numbers line up on the right unless the table says otherwise. */
    fun numeric(col: Int): Boolean {
        val cells = rows.map { it.getOrElse(col) { "" }.replace("*", "").trim() }.filter { it.isNotEmpty() }
        return cells.isNotEmpty() && cells.all { numberish.matches(it) }
    }

    companion object {
        private val sep = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")
        private val numberish = Regex("""^[~≈+\-]?\s?(R|\$|€|£|USD|ZAR)?\s?[\d][\d,. ]*(%|k|m|bn|x)?$""", RegexOption.IGNORE_CASE)

        /** Cells of one row: outer pipes dropped, `\|` kept as a literal pipe. */
        fun cells(line: String): List<String> {
            var t = line.trim()
            if (t.startsWith("|")) t = t.drop(1)
            if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
            val out = mutableListOf<String>(); val cur = StringBuilder(); var i = 0
            while (i < t.length) {
                val c = t[i]
                if (c == '\\' && i + 1 < t.length && t[i + 1] == '|') { cur.append('|'); i += 2; continue }
                if (c == '|') { out += cur.toString().trim(); cur.clear() } else cur.append(c)
                i++
            }
            out += cur.toString().trim()
            return out
        }

        /** Splits a message into prose and tables. A table is a row with pipes followed by a --- separator row. */
        fun split(text: String): List<MdPart> {
            val lines = text.trim().split("\n")
            val out = mutableListOf<MdPart>(); val prose = mutableListOf<String>()
            fun flush() { if (prose.joinToString("").isNotBlank()) out += MdPart.Prose(prose.joinToString("\n")); prose.clear() }
            var i = 0
            while (i < lines.size) {
                val l = lines[i]
                if (l.contains('|') && i + 1 < lines.size && sep.matches(lines[i + 1]) && lines[i + 1].contains('-')) {
                    val header = cells(l)
                    val align = cells(lines[i + 1]).map { c -> val t = c.trim(); when { t.startsWith(":") && t.endsWith(":") -> 'c'; t.endsWith(":") -> 'r'; else -> 'l' } }
                    var j = i + 2; val rows = mutableListOf<List<String>>()
                    while (j < lines.size && lines[j].contains('|') && lines[j].isNotBlank()) {
                        val r = cells(lines[j]); rows += (r + List((header.size - r.size).coerceAtLeast(0)) { "" }).take(header.size); j++
                    }
                    flush(); out += MdPart.Table(MdTable(header, (align + List(header.size) { 'l' }).take(header.size), rows)); i = j
                } else { prose += l; i++ }
            }
            flush()
            return out.ifEmpty { listOf(MdPart.Prose(text)) }
        }
    }
}

/** A table in a rounded card: bold header, soft zebra rows, numbers on the right, sideways scroll when it's wider than the phone. */
@Composable
private fun TableView(t: MdTable, style: TextStyle, color: Color, onWikiLink: ((String) -> Unit)?, knownPages: Set<String>) {
    val cs = MaterialTheme.colorScheme
    val known = remember(knownPages) { knownPages.map { it.lowercase() }.toSet() }
    val cell = MaterialTheme.typography.bodyMedium
    // Column widths from content: short columns stay tight, long ones wrap at a sensible width.
    val widths = remember(t) {
        t.header.indices.map { c ->
            val longest = (listOf(t.header[c]) + t.rows.map { it.getOrElse(c) { "" } }).maxOf { it.replace("**", "").length }
            (longest * 7.5f + 28f).coerceIn(64f, 220f).dp
        }
    }
    val alignOf = { c: Int -> when (t.align.getOrElse(c) { 'l' }) { 'r' -> TextAlign.End; 'c' -> TextAlign.Center; else -> if (t.numeric(c)) TextAlign.End else TextAlign.Start } }
    Surface(shape = MaterialTheme.shapes.large, color = cs.surfaceContainerLow, border = BorderStroke(1.dp, cs.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row(Modifier.background(cs.surfaceContainerHigh)) {
                t.header.forEachIndexed { c, h ->
                    Text(inline(h, cs.primary, cs.surfaceContainerHighest, onWikiLink, known, cs.onSurfaceVariant), style = cell.copy(fontWeight = FontWeight.SemiBold), color = color,
                        textAlign = alignOf(c), modifier = Modifier.width(widths[c]).padding(horizontal = 12.dp, vertical = 10.dp))
                }
            }
            t.rows.forEachIndexed { r, row ->
                Row(Modifier.background(if (r % 2 == 1) cs.surfaceContainer else Color.Transparent)) {
                    row.forEachIndexed { c, v ->
                        Text(inline(v, cs.primary, cs.surfaceContainerHighest, onWikiLink, known, cs.onSurfaceVariant), style = cell, color = color,
                            textAlign = alignOf(c), modifier = Modifier.width(widths[c]).padding(horizontal = 12.dp, vertical = 9.dp))
                    }
                }
            }
        }
    }
}

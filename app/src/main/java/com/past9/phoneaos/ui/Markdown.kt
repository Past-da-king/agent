package com.past9.phoneaos.ui

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

/** Just enough markdown for chat: headings, bullets, numbered lists, **bold**, *italic*, `code`, links. */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge, color: Color = MaterialTheme.colorScheme.onSurface,
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

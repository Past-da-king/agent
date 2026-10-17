@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.past9.phoneaos.ui.openui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.ui.openui.OpenUiLang.Action
import com.past9.phoneaos.ui.openui.OpenUiLang.Node

/** What a tap on a generated button sends back to the agent (the chat sets it; elsewhere taps only open links). */
val LocalOpenUiSend = compositionLocalOf<(String) -> Unit> { {} }

/** Which card and item the composable being drawn sits in, so a tap can say what it is about. */
private val LocalOpenUiScope = compositionLocalOf { OpenUiLang.Scope() }

/** Tests hand in already-loaded pictures by URL; the app loads them with Coil. */
val LocalOpenUiImages = compositionLocalOf<(String) -> androidx.compose.ui.graphics.ImageBitmap?> { { null } }

/**
 * An OpenUI Lang block drawn natively in Compose, in the app's own theme (accent, dark mode, type), with no WebView.
 * Re-parsed on every change, so a streaming answer builds up line by line.
 */
@Composable
fun OpenUiBlock(source: String, modifier: Modifier = Modifier) {
    val rendered = remember(source) { OpenUiLang.render(OpenUiLang.parse(source)) }
    val root = rendered.root ?: return
    Box(modifier.fillMaxWidth()) { Comp(root) }
}

private fun Node.str(k: String) = props[k] as? String
private fun Node.list(k: String) = (props[k] as? List<*>).orEmpty()
private fun Node.nodes(k: String) = list(k).filterIsInstance<Node>()

@Composable
private fun Comp(n: Node) {
    val cs = MaterialTheme.colorScheme
    when (n.type) {
        "Stack" -> StackComp(n)
        "Card" -> Surface(shape = RoundedCornerShape(24.dp), color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // The card's name is its header; inside a card with sections, each section heading names the item below it.
                val kids = n.nodes("children")
                val title = kids.firstOrNull { it.type == "CardHeader" }?.str("title") ?: kids.firstOrNull { it.type == "InlineHeader" }?.str("heading")
                var section: String? = null
                kids.forEach { k ->
                    if (k.type == "InlineHeader" && kids.firstOrNull { it.type == "CardHeader" } != null) section = k.str("heading")
                    androidx.compose.runtime.CompositionLocalProvider(LocalOpenUiScope provides OpenUiLang.Scope(card = title, item = section)) { Comp(k) }
                }
                val sources = n.list("sources").filterIsInstance<Map<*, *>>()
                if (sources.isNotEmpty()) Sources(sources)
            }
        }
        "CardHeader" -> Column {
            n.str("title")?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
            n.str("subtitle")?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
        }
        "TextContent" -> n.str("text")?.let { t ->
            val style = when (n.str("size")) { "small" -> MaterialTheme.typography.bodyMedium; "large" -> MaterialTheme.typography.bodyLarge
                "small-heavy" -> MaterialTheme.typography.titleSmall; "large-heavy" -> MaterialTheme.typography.titleMedium; else -> MaterialTheme.typography.bodyLarge }
            com.past9.phoneaos.ui.Markdown(t, style = style)
        }
        "MarkDownRenderer" -> n.str("textMarkdown")?.let { com.past9.phoneaos.ui.Markdown(it) }
        "Callout" -> {
            val (bg, fg) = when (n.str("variant")) {
                "warning" -> cs.tertiaryContainer to cs.onTertiaryContainer; "error" -> cs.errorContainer to cs.onErrorContainer
                "success" -> cs.secondaryContainer to cs.onSecondaryContainer; "info" -> cs.primaryContainer to cs.onPrimaryContainer
                else -> cs.surfaceContainerHigh to cs.onSurface }
            Surface(shape = RoundedCornerShape(16.dp), color = bg, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    n.str("title")?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = fg) }
                    n.str("description")?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = fg) }
                }
            }
        }
        "Separator" -> HorizontalDivider(color = cs.outlineVariant)
        "ImageBlock" -> n.str("src")?.let { Picture(it, n.str("alt"), Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(16.dp))) }
        "Image" -> n.str("src")?.let { Picture(it, n.str("alt"), Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(16.dp))) }
        "InlineHeader" -> Column {
            n.str("heading")?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            n.str("description")?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
        }
        "EntityList" -> EntityRows(n)
        "TextCallout" -> Comp(Node("Callout", n.props + ("variant" to (n.str("variant")?.replace("danger", "error") ?: "neutral"))))
        "Table" -> TableComp(n.nodes("columns"))
        "BarChart" -> Chart(n, bars = true)
        "LineChart" -> Chart(n, bars = false)
        "PieChart" -> Pie(n)
        "Buttons" -> { val bs = n.nodes("buttons")
            if (n.str("direction") == "column") Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { bs.forEach { Comp(it) } }
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { bs.forEach { Comp(it) } } }
        "Button" -> { val run = runner(n.props["action"]); val label = n.str("label") ?: ""
            when (n.str("variant")) {
                "secondary" -> FilledTonalButton(onClick = run) { Text(label) }
                "tertiary" -> TextButton(onClick = run) { Text(label) }
                else -> Button(onClick = run) { Text(label) }
            } }
        "ListBlock" -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            n.nodes("items").forEachIndexed { i, it -> ListRow(i + 1, it, numbered = n.str("variant") != "image") }
        }
        "Steps" -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { n.nodes("items").forEachIndexed { i, s -> ListRow(i + 1, Node("ListItem", mapOf("title" to s.str("title"), "subtitle" to s.str("details"))), true) } }
        "FollowUpBlock" -> { val send = LocalOpenUiSend.current
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { n.nodes("items").forEach { f -> val t = f.str("text") ?: ""
                Surface(onClick = { send(t) }, shape = RoundedCornerShape(50), color = cs.surfaceContainerHigh) {
                    Text("$t  →", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, color = cs.primary) } } } }
        "TagBlock" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            n.list("tags").filterIsInstance<String>().forEach { TagChip(it, null) } }
        "Tag" -> TagChip(n.str("text") ?: "", n.str("variant"))
        else -> {}
    }
}

/** A photo from a URL, cropped to fill; a soft block while it loads or if it can't. */
@Composable
private fun Picture(url: String, alt: String?, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val local = LocalOpenUiImages.current(url)
    if (local != null) androidx.compose.foundation.Image(local, alt, modifier, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    else coil.compose.SubcomposeAsyncImage(model = url, contentDescription = alt, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = modifier,
        loading = { Box(Modifier.fillMaxSize().background(cs.surfaceContainerHigh)) }, error = { Box(Modifier.fillMaxSize().background(cs.surfaceContainerHigh)) })
}

/** Label on the left, value on the right (numbers in tabular figures), with an optional header and total row. */
@Composable
private fun EntityRows(n: Node) {
    val cs = MaterialTheme.colorScheme
    @Composable fun row(m: Map<*, *>, strong: Boolean) {
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
            Text(m["left"]?.toString() ?: "", Modifier.weight(1f), style = if (strong) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                color = if (strong) cs.onSurface else cs.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            // The value gets at most 60% of the row, so a long one wraps instead of squeezing the label to a letter a line.
            Text(m["right"]?.let { fmt(it) } ?: "", Modifier.weight(1.5f, fill = false), style = (if (strong) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
                .copy(fontFeatureSettings = if (m["rightVariant"] == "number") "tnum" else null), textAlign = TextAlign.End)
        }
    }
    Column {
        (n.props["header"] as? Map<*, *>)?.let { row(it, true); HorizontalDivider(color = cs.outlineVariant) }
        n.list("rows").filterIsInstance<Map<*, *>>().forEachIndexed { i, r -> if (i > 0) HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.4f)); row(r, false) }
        (n.props["footer"] as? Map<*, *>)?.let { HorizontalDivider(color = cs.outlineVariant); row(it, true) }
    }
}

@Composable
private fun runner(action: Any?, item: String? = null): () -> Unit {
    val send = LocalOpenUiSend.current; val uri = LocalUriHandler.current
    val scope = LocalOpenUiScope.current.let { if (item != null) it.copy(item = item) else it }
    val list = when (action) { is Action -> listOf(action); is List<*> -> action.filterIsInstance<Action>(); else -> emptyList() }
    return { list.forEach { a -> when (a) { is Action.ToAssistant -> send(OpenUiLang.messageFor(a, scope)); is Action.OpenUrl -> runCatching { uri.openUri(a.url) } } } }
}

/** Several cards, one per item: side by side to swipe through ("row"), or one under another. */
@Composable
private fun StackComp(n: Node) {
    val kids = n.nodes("children")
    if (kids.isEmpty()) return
    if (n.str("direction") != "row" || kids.size == 1) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { kids.forEach { Comp(it) } }
    else BoxWithConstraints(Modifier.fillMaxWidth()) {
        val w = (maxWidth * 0.86f).coerceAtMost(380.dp)
        val state = androidx.compose.foundation.lazy.rememberLazyListState()
        androidx.compose.foundation.lazy.LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(10.dp),
            flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(state)) {
            items(kids.size) { i -> Box(Modifier.width(w)) { Comp(kids[i]) } }
        }
    }
}

@Composable
private fun ListRow(i: Int, it: Node, numbered: Boolean) {
    val cs = MaterialTheme.colorScheme; val run = runner(it.props["action"], item = it.str("title"))
    val clickable = it.props["action"] != null
    Surface(onClick = run, enabled = clickable, color = Color.Transparent, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
            val img = (it.props["image"] as? Map<*, *>)?.get("src") as? String
            if (img != null) Picture(img, null, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
            else if (numbered) Box(Modifier.size(26.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                Text("$i", style = MaterialTheme.typography.labelMedium, color = cs.onPrimaryContainer) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(it.str("title") ?: "", style = MaterialTheme.typography.titleSmall)
                it.str("subtitle")?.let { s -> Text(s, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
            }
            it.str("actionLabel")?.let { l -> Text(l, style = MaterialTheme.typography.labelLarge, color = cs.primary, modifier = Modifier.padding(start = 8.dp, top = 2.dp)) }
        }
    }
}

@Composable
private fun TagChip(text: String, variant: String?) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (variant) { "success" -> cs.secondaryContainer to cs.onSecondaryContainer; "warning" -> cs.tertiaryContainer to cs.onTertiaryContainer
        "danger" -> cs.errorContainer to cs.onErrorContainer; "info" -> cs.primaryContainer to cs.onPrimaryContainer; else -> cs.surfaceContainerHigh to cs.onSurfaceVariant }
    Surface(shape = RoundedCornerShape(50), color = bg) { Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = fg) }
}

private fun fmt(v: Any?): String = when (v) {
    is Double -> if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) String.format("%,d", v.toLong()).replace(',', ' ') else String.format("%,.2f", v).replace(',', ' ')
    null -> ""; else -> v.toString()
}

/** Column-oriented, as OpenUI defines it: each Col carries its own data. Numbers on the right; sideways scroll when wide. */
@Composable
private fun TableComp(cols: List<Node>) {
    if (cols.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val data = cols.map { it.list("data") }; val rows = data.maxOf { it.size }
    val numeric = data.map { d -> d.isNotEmpty() && d.all { it is Double } }
    Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints { val minW = maxWidth
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            cols.forEachIndexed { c, col ->
                Column(Modifier.width(IntrinsicSize.Max).widthIn(min = minW / cols.size)) {
                    val align = if (numeric[c]) Alignment.End else Alignment.Start
                    Box(Modifier.fillMaxWidth().background(cs.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = if (numeric[c]) Alignment.CenterEnd else Alignment.CenterStart) {
                        Text(col.str("label") ?: "", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) }
                    for (r in 0 until rows) Box(Modifier.fillMaxWidth().background(if (r % 2 == 1) cs.surfaceContainerHigh.copy(alpha = 0.45f) else Color.Transparent)
                        .padding(horizontal = 14.dp, vertical = 9.dp), contentAlignment = if (numeric[c]) Alignment.CenterEnd else Alignment.CenterStart) {
                        Text(fmt(data[c].getOrNull(r)), style = MaterialTheme.typography.bodyMedium, textAlign = if (align == Alignment.End) TextAlign.End else TextAlign.Start) }
                }
            }
        } }
    }
}

@Composable
private fun palette(): List<Color> { val cs = MaterialTheme.colorScheme; return listOf(cs.primary, cs.tertiary, cs.secondary, cs.error, cs.inversePrimary) }

@Composable
private fun Legend(names: List<String>, colors: List<Color>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        names.forEachIndexed { i, s -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(colors[i % colors.size])); Spacer(Modifier.width(6.dp))
            Text(s, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
}

/** Bars (grouped) or lines over shared labels, scaled to the largest value, with a light grid. */
@Composable
private fun Chart(n: Node, bars: Boolean) {
    val labels = n.list("labels").map { fmt(it) }
    val series = n.nodes("series").map { (it.str("category") ?: "") to it.list("values").map { v -> (v as? Double) ?: 0.0 } }
    if (labels.isEmpty() || series.isEmpty()) return
    val colors = palette(); val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val max = series.flatMap { it.second }.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(((n.props["height"] as? Double) ?: 180.0).coerceIn(120.0, 320.0).dp)) {
            val h = size.height; val w = size.width
            for (g in 0..3) { val y = h - h * g / 3f; drawLine(grid, Offset(0f, y), Offset(w, y), 1.dp.toPx()) }
            val slot = w / labels.size
            if (bars) {
                val bw = (slot * 0.7f) / series.size
                series.forEachIndexed { s, (_, vs) -> labels.indices.forEach { i ->
                    val v = vs.getOrElse(i) { 0.0 }; val bh = (v / max * (h - 4)).toFloat()
                    drawRoundRect(colors[s % colors.size], Offset(slot * i + slot * 0.15f + bw * s, h - bh), Size(bw - 3.dp.toPx(), bh), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
                } }
            } else series.forEachIndexed { s, (_, vs) ->
                val path = Path(); labels.indices.forEach { i -> val x = slot * i + slot / 2; val y = h - (vs.getOrElse(i) { 0.0 } / max * (h - 6)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y); drawCircle(colors[s % colors.size], 4.dp.toPx(), Offset(x, y)) }
                drawPath(path, colors[s % colors.size], style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Row(Modifier.fillMaxWidth()) { labels.forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1) } }
        if (series.size > 1 || series[0].first.isNotBlank()) Legend(series.map { it.first }, colors)
    }
}

@Composable
private fun Pie(n: Node) {
    val labels = n.list("labels").map { fmt(it) }; val values = n.list("values").map { (it as? Double) ?: 0.0 }
    val total = values.sum().takeIf { it > 0 } ?: return
    val colors = palette(); val donut = n.str("variant") != "pie"
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Canvas(Modifier.size(132.dp)) {
            var start = -90f
            values.forEachIndexed { i, v -> val sweep = (v / total * 360).toFloat()
                if (donut) drawArc(colors[i % colors.size], start, sweep - 2f, false, style = Stroke(22.dp.toPx()), topLeft = Offset(11.dp.toPx(), 11.dp.toPx()), size = Size(size.width - 22.dp.toPx(), size.height - 22.dp.toPx()))
                else drawArc(colors[i % colors.size], start, sweep, true); start += sweep }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEachIndexed { i, l -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(colors[i % colors.size])); Spacer(Modifier.width(8.dp))
                Text("$l  ${Math.round(values.getOrElse(i) { 0.0 } / total * 100)}%", style = MaterialTheme.typography.bodyMedium) } }
        }
    }
}

@Composable
private fun Sources(sources: List<Map<*, *>>) {
    val uri = LocalUriHandler.current; val cs = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        sources.forEachIndexed { i, s -> val url = s["url"] as? String
            Surface(onClick = { url?.let { runCatching { uri.openUri(it) } } }, shape = RoundedCornerShape(50), color = cs.surfaceContainerHigh) {
                Text("${i + 1}  ${s["sourceName"] ?: s["title"] ?: ""}", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant) } }
    }
}

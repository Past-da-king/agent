@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.past9.phoneaos.ui.openui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CallMade
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CallReceived
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.StarHalf
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.past9.phoneaos.ui.openui.OpenUiLang.Action
import com.past9.phoneaos.ui.openui.OpenUiLang.Node

/** What a tap on a generated button sends back to the agent (the chat sets it; elsewhere taps only open links). */
val LocalOpenUiSend = compositionLocalOf<(String) -> Unit> { {} }

/**
 * What a tap on a generated button sends: quietly, to the agent in the background, with no message from the user in the chat.
 * Null where the screen has no quiet path; a tap then falls back to [LocalOpenUiSend].
 */
val LocalOpenUiAct = compositionLocalOf<((String) -> Unit)?> { null }

/**
 * Set where a button is an answer rather than a message (a question the agent is waiting on): a tap hands the button's
 * own words to this and nothing is sent to the chat.
 */
val LocalOpenUiPick = compositionLocalOf<((String) -> Unit)?> { null }

/** Which card and item the composable being drawn sits in, so a tap can say what it is about. */
private val LocalOpenUiScope = compositionLocalOf { OpenUiLang.Scope() }

/** Words from across the card (its labels and buttons), so the header can pick an icon that fits what the card is about. */
private val LocalCardHint = compositionLocalOf { "" }

/** The first facts list in the card: the only one that may open with a big money figure. */
private val LocalFirstFacts = compositionLocalOf<Node?> { null }

/** The amount the card's header already shows big, so a fact row with the same amount is not said twice. */
private val LocalHeroAmount = compositionLocalOf<String?> { null }

/** Tests hand in already-loaded pictures by URL; the app loads them with Coil. */
val LocalOpenUiImages = compositionLocalOf<(String) -> androidx.compose.ui.graphics.ImageBitmap?> { { null } }

/**
 * An OpenUI Lang block drawn natively in Compose, in the app's own theme (accent, dark mode, type), with no WebView.
 * Re-parsed on every change, so a streaming answer builds up line by line.
 *
 * The look: one tonal step per layer (card, then tiles inside it), a cookie-shaped icon chip that echoes the agent's
 * own shape, one big figure per card when there is an amount, and full-width pill buttons that squash when pressed.
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
        "Card" -> CardComp(n)
        "Product" -> ProductComp(n)
        "CardHeader" -> CardHeaderComp(n)
        "TextContent" -> n.str("text")?.let { t ->
            val style = when (n.str("size")) { "small" -> MaterialTheme.typography.bodyMedium; "large" -> MaterialTheme.typography.bodyLarge
                "small-heavy" -> MaterialTheme.typography.titleSmall; "large-heavy" -> MaterialTheme.typography.titleMedium; else -> MaterialTheme.typography.bodyLarge }
            com.past9.phoneaos.ui.Markdown(t, style = style)
        }
        "MarkDownRenderer" -> n.str("textMarkdown")?.let { com.past9.phoneaos.ui.Markdown(it) }
        "Callout" -> CalloutComp(n)
        "Separator" -> HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f))
        "ImageBlock", "Image" -> n.str("src")?.let { Picture(it, n.str("alt"), Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(20.dp))) }
        "InlineHeader" -> Column {
            n.str("heading")?.let { Text(it, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)) }
            n.str("description")?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
        }
        "Icon" -> IconChip(OpenUiIcons.forTextOr(n.str("name")), 36.dp)
        "EntityList" -> EntityRows(n)
        "TextCallout" -> Comp(Node("Callout", n.props + ("variant" to (n.str("variant")?.replace("danger", "error") ?: "neutral"))))
        "Table" -> TableComp(n.nodes("columns"))
        "BarChart" -> Chart(n, bars = true)
        "LineChart" -> Chart(n, bars = false)
        "PieChart" -> Pie(n)
        "Buttons" -> ButtonsComp(n)
        "Button" -> ActionButton(n, Modifier.fillMaxWidth())
        "ListBlock" -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            n.nodes("items").forEachIndexed { i, it -> ListRow(i + 1, it, numbered = n.str("variant") != "image") }
        }
        "Steps" -> Column {
            val items = n.nodes("items")
            items.forEachIndexed { i, s -> ListRow(i + 1, Node("ListItem", mapOf("title" to s.str("title"), "subtitle" to s.str("details"))), true, connectBelow = i < items.size - 1) }
        }
        "FollowUpBlock" -> { val send = LocalOpenUiSend.current
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { n.nodes("items").forEach { f -> val t = f.str("text") ?: ""
                Surface(onClick = { send(t) }, shape = RoundedCornerShape(50), color = cs.surfaceContainerHighest) {
                    Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(16.dp), tint = cs.primary)
                    } } } } }
        "TagBlock" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            n.list("tags").filterIsInstance<String>().forEach { TagChip(it, null, null) } }
        "Tag" -> TagChip(n.str("text") ?: "", n.str("variant"), n.str("icon"))
        else -> {}
    }
}

// ---- Card, header, hero figure ----

/** A leading amount like "R7,200" in a title, split from the words after it ("a month"). */
private val HERO = Regex("^([R$€£]\\s?\\d{1,3}(?:[ ,]\\d{3})*(?:[.,]\\d{1,2})?|[R$€£]\\s?\\d+(?:[.,]\\d{1,2})?)(.*)$")
private fun heroSplit(title: String?): Pair<String, String>? = title?.let { HERO.find(it.trim())?.let { m -> m.groupValues[1] to m.groupValues[2].trim() } }
private fun digits(v: Any?) = v?.toString().orEmpty().filter { it.isDigit() }

@Composable
private fun CardComp(n: Node) {
    val cs = MaterialTheme.colorScheme
    val kids = n.nodes("children")
    val header = kids.firstOrNull { it.type == "CardHeader" }
    // The card's name is its header; inside a card with sections, each section heading names the item below it.
    val title = header?.str("title") ?: kids.firstOrNull { it.type == "InlineHeader" }?.str("heading")
    val lead = kids.firstOrNull()?.takeIf { it.type == "ImageBlock" || it.type == "Image" }
    val firstFacts = kids.firstOrNull { it.type == "EntityList" }
    val hint = buildString {
        kids.forEach { k -> when (k.type) {
            "EntityList" -> k.list("rows").filterIsInstance<Map<*, *>>().forEach { append(it["left"]).append(' ') }
            "Buttons" -> k.nodes("buttons").forEach { append(it.str("label")).append(' ') }
            "ListBlock", "Steps" -> k.nodes("items").forEach { append(it.str("title")).append(' ') } } }
    }
    Surface(shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column {
            // A leading photo runs edge to edge; the card's own rounded corners clip it.
            lead?.str("src")?.let { Picture(it, lead.str("alt"), Modifier.fillMaxWidth().aspectRatio(16f / 10f)) }
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                var section: String? = null
                androidx.compose.runtime.CompositionLocalProvider(LocalHeroAmount provides heroSplit(header?.str("title"))?.first, LocalCardHint provides hint, LocalFirstFacts provides firstFacts) {
                    kids.forEach { k ->
                        if (k === lead) return@forEach
                        if (k.type == "InlineHeader" && header != null) section = k.str("heading")
                        androidx.compose.runtime.CompositionLocalProvider(LocalOpenUiScope provides OpenUiLang.Scope(card = title, item = section)) { Comp(k) }
                    }
                }
                val sources = n.list("sources").filterIsInstance<Map<*, *>>()
                if (sources.isNotEmpty()) Sources(sources)
            }
        }
    }
}

@Composable
private fun CardHeaderComp(n: Node) {
    val cs = MaterialTheme.colorScheme
    val title = n.str("title").orEmpty(); val sub = n.str("subtitle")
    val hero = heroSplit(title)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconChip(OpenUiIcons.forText("$title ${sub.orEmpty()}") ?: (if (hero != null) OpenUiIcons.forText("pay") else OpenUiIcons.forText(LocalCardHint.current)) ?: Icons.Rounded.Insights, 48.dp)
        Column(Modifier.weight(1f)) {
            if (hero != null) Text(buildAnnotatedString {
                withStyle(SpanStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, fontFeatureSettings = "tnum")) { append(hero.first) }
                if (hero.second.isNotEmpty()) withStyle(SpanStyle(fontSize = 16.sp, color = cs.onSurfaceVariant)) { append("  ${hero.second}") }
            }, lineHeight = 40.sp)
            else Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold))
            sub?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
        }
    }
}

/** The app's cookie shape (the same one as the agent) holding an icon: the one decorative motif, used for every leading icon. */
@Composable
private fun IconChip(icon: ImageVector, size: Dp, container: Color = MaterialTheme.colorScheme.primaryContainer,
                     content: Color = MaterialTheme.colorScheme.onPrimaryContainer, cookie: Boolean = true) {
    Box(Modifier.size(size).clip(if (cookie) MaterialShapes.Cookie9Sided.toShape() else CircleShape).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.5f), tint = content)
    }
}

@Composable
private fun CalloutComp(n: Node) {
    val cs = MaterialTheme.colorScheme
    val v = n.str("variant")
    val (chip, onChip) = when (v) { "warning" -> cs.tertiaryContainer to cs.onTertiaryContainer; "error" -> cs.errorContainer to cs.onErrorContainer
        "success" -> cs.secondaryContainer to cs.onSecondaryContainer; else -> cs.primaryContainer to cs.onPrimaryContainer }
    val icon = OpenUiIcons.forText(when (v) { "warning" -> "warning"; "error" -> "error"; "success" -> "success"; else -> "info" }) ?: Icons.Rounded.Insights
    Surface(shape = RoundedCornerShape(20.dp), color = cs.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconChip(icon, 36.dp, chip, onChip)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                n.str("title")?.let { Text(it, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)) }
                n.str("description")?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
            }
        }
    }
}

// ---- Money and signs ----

@Composable private fun goodColor() = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF7FD6A0) else Color(0xFF17703F)

/** Money out in the error colour, money in in green; anything else keeps [base]. */
@Composable
private fun signTint(v: String, base: Color): Color {
    val t = v.trim()
    if (t.none { it.isDigit() }) return base
    return when { t.startsWith("-") || t.startsWith("−") -> MaterialTheme.colorScheme.error; t.startsWith("+") -> goodColor(); else -> base }
}

private val GENERIC_HEADS = setOf("item", "items", "label", "name", "detail", "details", "date", "amount", "value", "field", "description", "key", "property", "when")

/** Facts: short label/value pairs become tiles in two columns; long or many ones stay as a grouped list. */
@Composable
private fun EntityRows(n: Node) {
    val cs = MaterialTheme.colorScheme
    val hero = LocalHeroAmount.current
    val all = n.list("rows").filterIsInstance<Map<*, *>>()
    val rows = all.filter { hero == null || digits(it["right"]) != digits(hero) }
    val head = (n.props["header"] as? Map<*, *>)
    val foot = (n.props["footer"] as? Map<*, *>)
    val short = rows.size in 2..8 && rows.all { fmt(it["right"]).length <= 22 && it["left"]?.toString().orEmpty().length <= 20 }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // A header like "Item / Amount" says nothing; a real one is kept as a caption.
        head?.let { h -> val l = h["left"]?.toString().orEmpty()
            if (l.isNotBlank() && l.lowercase() !in GENERIC_HEADS) Text(l, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant) }
        if (short) FactTiles(rows, firstFacts = n === LocalFirstFacts.current) else FactList(rows)
        foot?.let { f -> Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            Text(f["left"]?.toString().orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(fmt(f["right"]), style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"), color = signTint(fmt(f["right"]), cs.onSurface))
        } }
    }
}

@Composable
private fun FactTiles(rows: List<Map<*, *>>, firstFacts: Boolean) {
    val cs = MaterialTheme.colorScheme
    // With no amount in the header, a first row that is money is the card's figure: full width, drawn big.
    val moneyFirst = firstFacts && LocalHeroAmount.current == null && rows.size <= 4 && fmt(rows[0]["right"]).let { HERO.containsMatchIn(it.trim().trimStart('-', '+')) }
    val lines = mutableListOf<List<Map<*, *>>>(); var i = 0
    if (moneyFirst) { lines += listOf(rows[0]); i = 1 }
    while (i < rows.size) { lines += rows.subList(i, minOf(i + 2, rows.size)); i += 2 }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        lines.forEachIndexed { li, line ->
            val big = moneyFirst && li == 0
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                line.forEach { r -> val label = r["left"]?.toString().orEmpty(); val value = fmt(r["right"])
                    Surface(shape = RoundedCornerShape(20.dp), color = cs.surfaceContainerHighest, modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OpenUiIcons.forText(label)?.let { Icon(it, null, Modifier.size(16.dp), tint = cs.primary) }
                                Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text(value, style = (if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium)
                                .copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = signTint(value, cs.onSurface))
                        }
                    } }
            }
        }
    }
}

@Composable
private fun FactList(rows: List<Map<*, *>>) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = cs.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        Column {
            rows.forEachIndexed { i, r ->
                val left = r["left"]?.toString().orEmpty(); val value = fmt(r["right"])
                if (i > 0) HorizontalDivider(Modifier.padding(start = 62.dp), color = cs.outlineVariant.copy(alpha = 0.45f))
                val out = value.trim().startsWith("-"); val into = value.trim().startsWith("+")
                val icon = OpenUiIcons.forText(left) ?: if (out) Icons.Rounded.CallMade else if (into) Icons.Rounded.CallReceived else null
                val long = value.length > 30
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = if (long) Alignment.Top else Alignment.CenterVertically) {
                    if (icon != null) IconChip(icon, 36.dp, cs.surfaceContainerHigh, cs.onSurfaceVariant, cookie = false)
                    else Box(Modifier.size(36.dp).clip(CircleShape).background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                        Text(left.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant) }
                    Spacer(Modifier.width(12.dp))
                    // A long value (an email body, an address) goes under its label at full width; a short one sits on the right.
                    if (long) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(left, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                    } else {
                        Text(left, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                        Spacer(Modifier.width(12.dp))
                        Text(value, Modifier.weight(0.9f, fill = false), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                            color = signTint(value, cs.onSurface), textAlign = TextAlign.End)
                    }
                }
            }
        }
    }
}

// ---- Product: photo, name, price, stars, buttons ----

/** Amber on dark, a deeper amber on light so the stars stay visible against a pale card. */
@Composable private fun starGold() = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFFF2A900) else Color(0xFFC77700)

/** Whole stars, a half and the empty ones, for a rating out of 5. */
private fun starKinds(rating: Double): List<Int> = (1..5).map { i -> if (rating >= i - 0.25) 2 else if (rating >= i - 0.75) 1 else 0 }

@Composable
private fun Stars(rating: Double, reviews: Double?) {
    val cs = MaterialTheme.colorScheme
    val said = "Rated ${fmtRating(rating)} out of 5" + (reviews?.let { ", ${count(it)} reviews" } ?: "")
    Row(Modifier.semantics { contentDescription = said }, verticalAlignment = Alignment.CenterVertically) {
        starKinds(rating).forEach { k -> Icon(when (k) { 2 -> Icons.Rounded.Star; 1 -> Icons.Rounded.StarHalf; else -> Icons.Rounded.StarBorder }, null, Modifier.size(16.dp), tint = if (k == 0) cs.outline else starGold()) }
        Spacer(Modifier.width(6.dp))
        Text(fmtRating(rating), style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
        reviews?.let { Text(" (${count(it)})", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant) }
    }
}

private fun count(v: Double) = String.format(java.util.Locale.US, "%,d", v.toLong())

private fun fmtRating(r: Double) = String.format(java.util.Locale.US, "%.1f", r.coerceIn(0.0, 5.0))

@Composable
private fun ProductComp(n: Node) {
    val cs = MaterialTheme.colorScheme
    val name = n.str("name").orEmpty()
    val rating = (n.props["rating"] as? Double)?.takeIf { it > 0 }
    val reviews = (n.props["reviews"] as? Double)?.takeIf { it > 0 }
    val price = n.str("price"); val was = n.str("was")?.takeIf { it != price }
    Surface(shape = RoundedCornerShape(24.dp), color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column {
            Box {
                val img = n.str("image")
                if (img != null) Picture(img, name, Modifier.fillMaxWidth().aspectRatio(1f))
                else Box(Modifier.fillMaxWidth().aspectRatio(1f).background(cs.surfaceContainerHighest), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.ShoppingBag, null, Modifier.size(40.dp), tint = cs.onSurfaceVariant) }
                n.str("tag")?.takeIf { it.isNotBlank() }?.let { t ->
                    Surface(shape = RoundedCornerShape(50), color = cs.primaryContainer, contentColor = cs.onPrimaryContainer, modifier = Modifier.padding(10.dp).align(Alignment.TopStart)) {
                        Text(t, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                n.str("seller")?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                // Two lines always, so every card in the row has its price and buttons at the same height.
                Text(name, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
                if (rating != null) Stars(rating, reviews)
                if (price != null) Row(verticalAlignment = Alignment.Bottom) {
                    Text(price, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"), color = cs.onSurface, maxLines = 1)
                    was?.let { Text(it, Modifier.padding(start = 8.dp, bottom = 2.dp), style = MaterialTheme.typography.bodyMedium.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough), color = cs.onSurfaceVariant, maxLines = 1) }
                }
                (n.props["actions"] as? Node)?.let { a ->
                    Spacer(Modifier.height(4.dp))
                    // A tap names this product, so "Add to cart" is about this one.
                    androidx.compose.runtime.CompositionLocalProvider(LocalOpenUiScope provides OpenUiLang.Scope(card = name)) { Comp(a) }
                }
            }
        }
    }
}

// ---- Buttons: big pills that squash when pressed ----

@Composable
private fun ButtonsComp(n: Node) {
    val bs = n.nodes("buttons")
    if (bs.isEmpty()) return
    // Two choices sit side by side (Approve / Decline); more, or a column asked for, stack at full width.
    val short = bs.size == 2 && bs.all { (it.str("label") ?: "").length <= 10 }
    if (n.str("direction") == "column" || !short && bs.size > 1) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { bs.forEach { ActionButton(it, Modifier.fillMaxWidth()) } }
    else if (bs.size == 1) ActionButton(bs[0], Modifier.fillMaxWidth())
    else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { bs.forEach { ActionButton(it, Modifier.weight(1f)) } }
}

@Composable
private fun ActionButton(n: Node, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val run = runner(n.props["action"]); val label = n.str("label") ?: ""
    val picking = LocalOpenUiPick.current != null
    val tellsAgent = !picking && n.props["action"].let { a -> a is Action.ToAssistant || (a is List<*> && a.any { it is Action.ToAssistant }) }
    // The tap leaves no message in the chat, so the button itself says it went.
    var sent by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // The shape springs from a pill to a softer rectangle under the thumb and back: the part that makes it feel alive.
    val corner by animateDpAsState(if (pressed) 14.dp else 28.dp, spring(dampingRatio = 0.6f, stiffness = 800f), label = "corner")
    // A "Details" / "Open" button only navigates: tonal, so a column of cards doesn't shout the same primary button four times.
    val quiet = n.str("variant") == null && OpenUiIcons.navigates(label)
    val (bg0, fg0) = when (if (quiet) "secondary" else n.str("variant")) { "secondary" -> cs.secondaryContainer to cs.onSecondaryContainer; "tertiary" -> Color.Transparent to cs.primary; else -> cs.primary to cs.onPrimary }
    val (bg, fg) = if (sent) cs.surfaceContainerHighest to cs.onSurfaceVariant else bg0 to fg0
    Surface(onClick = { run(); if (tellsAgent) sent = true }, enabled = !sent, shape = RoundedCornerShape(corner), color = bg, contentColor = fg, interactionSource = interaction, modifier = modifier.heightIn(min = 52.dp)) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            (if (sent) androidx.compose.material.icons.Icons.Rounded.Check else OpenUiIcons.forAction(label))?.let { Icon(it, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
            Text(if (sent) "Sent" else label, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A photo from a URL or a file on this phone (a path starting with /), cropped to fill; a soft block while it loads or if it can't. */
@Composable
private fun Picture(url: String, alt: String?, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val local = LocalOpenUiImages.current(url)
    if (local != null) androidx.compose.foundation.Image(local, alt, modifier, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    else coil.compose.SubcomposeAsyncImage(model = if (url.startsWith("/")) java.io.File(url) else url, contentDescription = alt, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = modifier,
        loading = { Box(Modifier.fillMaxSize().background(cs.surfaceContainerHighest)) }, error = { Box(Modifier.fillMaxSize().background(cs.surfaceContainerHighest)) })
}

@Composable
private fun runner(action: Any?, item: String? = null): () -> Unit {
    val send = LocalOpenUiAct.current ?: LocalOpenUiSend.current; val pick = LocalOpenUiPick.current; val uri = LocalUriHandler.current
    val scope = LocalOpenUiScope.current.let { if (item != null) it.copy(item = item) else it }
    val list = when (action) { is Action -> listOf(action); is List<*> -> action.filterIsInstance<Action>(); else -> emptyList() }
    return { list.forEach { a -> when (a) { is Action.ToAssistant -> if (pick != null) pick(a.text) else send(OpenUiLang.messageFor(a, scope)); is Action.OpenUrl -> runCatching { uri.openUri(a.url) } } } }
}

/** Several cards, one per item: side by side to swipe through ("row"), or one under another. */
@Composable
private fun StackComp(n: Node) {
    val kids = n.nodes("children")
    if (kids.isEmpty()) return
    if (n.str("direction") != "row" || kids.size == 1) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { kids.forEach { Comp(it) } }
    else BoxWithConstraints(Modifier.fillMaxWidth()) {
        val products = kids.all { it.type == "Product" }
        val w = if (products) (maxWidth * 0.6f).coerceIn(168.dp, 250.dp) else (maxWidth * 0.86f).coerceAtMost(380.dp)
        val state = androidx.compose.foundation.lazy.rememberLazyListState()
        androidx.compose.foundation.lazy.LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(10.dp),
            flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(state)) {
            items(kids.size) { i -> Box(Modifier.width(w)) { Comp(kids[i]) } }
        }
    }
}

@Composable
private fun ListRow(i: Int, it: Node, numbered: Boolean, connectBelow: Boolean = false) {
    val cs = MaterialTheme.colorScheme; val run = runner(it.props["action"], item = it.str("title"))
    val clickable = it.props["action"] != null
    val title = it.str("title") ?: ""
    Surface(onClick = run, enabled = clickable, color = Color.Transparent, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(IntrinsicSize.Min), verticalAlignment = Alignment.Top) {
            val img = (it.props["image"] as? Map<*, *>)?.get("src") as? String
            val chipIcon = OpenUiIcons.forText(title)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (img != null) Picture(img, null, Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)))
                else if (numbered) Box(Modifier.size(36.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                    Text("$i", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = cs.onPrimaryContainer) }
                else if (chipIcon != null) IconChip(chipIcon, 36.dp, cs.surfaceContainerHighest, cs.primary, cookie = false)
                else Spacer(Modifier.width(0.dp))
                // Steps are joined by a thin line, so the order reads as a path.
                if (connectBelow) Box(Modifier.padding(top = 4.dp).width(2.dp).weight(1f).background(cs.outlineVariant.copy(alpha = 0.6f)))
            }
            if (img != null || numbered || chipIcon != null) Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).padding(bottom = if (connectBelow) 12.dp else 0.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                it.str("subtitle")?.let { s -> Text(s, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
            }
            it.str("actionLabel")?.let { l -> Text(l, style = MaterialTheme.typography.labelLarge, color = cs.primary, modifier = Modifier.padding(start = 8.dp, top = 2.dp)) }
        }
    }
}

@Composable
private fun TagChip(text: String, variant: String?, iconName: String?) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (variant) { "success" -> cs.secondaryContainer to cs.onSecondaryContainer; "warning" -> cs.tertiaryContainer to cs.onTertiaryContainer
        "danger" -> cs.errorContainer to cs.onErrorContainer; "info" -> cs.primaryContainer to cs.onPrimaryContainer; else -> cs.surfaceContainerHighest to cs.onSurfaceVariant }
    val icon = (if (iconName != null) OpenUiIcons.forText(iconName) else null) ?: OpenUiIcons.forText(text)
    Surface(shape = RoundedCornerShape(50), color = bg) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.let { Icon(it, null, Modifier.size(14.dp), tint = fg); Spacer(Modifier.width(6.dp)) }
            Text(text, style = MaterialTheme.typography.labelMedium, color = fg)
        }
    }
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
    Surface(shape = RoundedCornerShape(20.dp), color = cs.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints { val minW = maxWidth
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            cols.forEachIndexed { c, col ->
                Column(Modifier.width(IntrinsicSize.Max).widthIn(min = minW / cols.size)) {
                    val align = if (numeric[c]) Alignment.End else Alignment.Start
                    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), contentAlignment = if (numeric[c]) Alignment.CenterEnd else Alignment.CenterStart) {
                        Text(col.str("label") ?: "", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant) }
                    HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f))
                    for (r in 0 until rows) {
                        if (r > 0) HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.3f))
                        val cell = fmt(data[c].getOrNull(r))
                        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), contentAlignment = if (numeric[c]) Alignment.CenterEnd else Alignment.CenterStart) {
                            Text(cell, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = if (numeric[c]) "tnum" else null), color = signTint(cell, cs.onSurface),
                                textAlign = if (align == Alignment.End) TextAlign.End else TextAlign.Start) }
                    }
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
            Surface(onClick = { url?.let { runCatching { uri.openUri(it) } } }, shape = RoundedCornerShape(50), color = cs.surfaceContainerHighest) {
                Text("${i + 1}  ${s["sourceName"] ?: s["title"] ?: ""}", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant) } }
    }
}

package com.past9.phoneaos.ui

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.past9.phoneaos.cards.Card
import com.past9.phoneaos.cards.CardBridge
import com.past9.phoneaos.cards.CardKit
import com.past9.phoneaos.cards.CardWeb
import com.past9.phoneaos.ui.theme.Eyebrow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The kit's icon names, drawn natively on tiles and headers. */
fun kitIcon(name: String): ImageVector = when (name) {
    "mail" -> Icons.Rounded.Mail; "send" -> Icons.AutoMirrored.Rounded.Send; "reply" -> Icons.AutoMirrored.Rounded.Reply; "tag" -> Icons.Rounded.Sell
    "cart" -> Icons.Rounded.ShoppingCart; "calendar" -> Icons.Rounded.CalendarMonth; "clock" -> Icons.Rounded.Schedule; "money" -> Icons.Rounded.Payments
    "briefcase" -> Icons.Rounded.Work; "users" -> Icons.Rounded.Groups; "user" -> Icons.Rounded.Person; "building" -> Icons.Rounded.Business
    "target" -> Icons.Rounded.TrackChanges; "list" -> Icons.AutoMirrored.Rounded.List; "star" -> Icons.Rounded.Star; "bolt" -> Icons.Rounded.Bolt
    "doc" -> Icons.Rounded.Description; "search" -> Icons.Rounded.Search; "sun" -> Icons.Rounded.WbSunny; "cloud" -> Icons.Rounded.Cloud
    "trend_up" -> Icons.AutoMirrored.Rounded.TrendingUp; "trend_down" -> Icons.AutoMirrored.Rounded.TrendingDown; "pin" -> Icons.Rounded.Place
    "phone" -> Icons.Rounded.Call; "link" -> Icons.Rounded.Link; "alert" -> Icons.Rounded.Warning; "info" -> Icons.Rounded.Info
    else -> Icons.Rounded.Insights
}

@Composable
private fun IconTile(card: Card, size: androidx.compose.ui.unit.Dp = 44.dp) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 3)).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
        Icon(kitIcon(card.icon), null, Modifier.size(size * 0.5f), tint = cs.onSecondaryContainer)
    }
}

/** Home: the user's pinned cards, a row of tiles. Each answers "what is it, the one number, how fresh". */
@Composable
fun CardTiles(cards: List<Card>, onOpen: (Card) -> Unit, now: Long = System.currentTimeMillis()) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 0.dp)) {
        items(cards, key = { it.id }) { c -> CardTile(c, onOpen, now) }
    }
}

@Composable
fun CardTile(c: Card, onOpen: (Card) -> Unit, now: Long = System.currentTimeMillis()) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = { onOpen(c) }, shape = MaterialTheme.shapes.large, color = cs.surfaceContainerLow, modifier = Modifier.width(184.dp).heightIn(min = 150.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(c, 36.dp)
                Spacer(Modifier.weight(1f))
                if (c.error.isNotBlank()) Icon(Icons.Rounded.ErrorOutline, "Didn't refresh", Modifier.size(18.dp), tint = cs.error)
            }
            Spacer(Modifier.height(6.dp))
            Text(c.title, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(c.headline.ifBlank { "Open" }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Updated ${relativeTime(c.updatedAt, now)}", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
    }
}

/** A card in the chat: a calm row that opens the full card. */
@Composable
fun CardChatRow(c: Card?, title: String, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = onOpen, enabled = c != null, shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (c != null) IconTile(c) else Box(Modifier.size(44.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("CARD", style = Eyebrow, color = cs.primary)
                Text(c?.title ?: title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(c?.headline?.ifBlank { null } ?: if (c == null) "This card was deleted" else "Tap to open", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (c != null) Icon(Icons.Rounded.ChevronRight, "Open ${c.title}", tint = cs.onSurfaceVariant)
        }
    }
}

/** What the card sheet can do in the app. */
data class CardSheetActions(
    val onRefresh: (Card) -> Unit = {},
    val onPin: (Card, Boolean) -> Unit = { _, _ -> },
    val onAsk: (Card, String) -> Unit = { _, _ -> },
    val onOpenLink: (String) -> Unit = {},
    /** A search box in the card: run its script with this input, return {ok, data or error}. */
    val onRun: suspend (Card, String) -> Pair<Boolean, String> = { _, _ -> false to "{}" },
)

/**
 * The full card: a native header (title, source, how fresh, refresh, pin) over the agent's HTML, rendered
 * with the app's live colours so the two read as one screen.
 */
@Composable
fun CardSheet(c: Card, dark: Boolean, accent: String, actions: CardSheetActions, onDismiss: () -> Unit, refreshing: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp)
    val latest by rememberUpdatedState(c)
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(c, 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(c.source.ifBlank { null }, "updated ${relativeTime(c.updatedAt)}").joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (c.script.isNotBlank()) {
                    if (refreshing) LoadingIndicator(Modifier.size(40.dp))
                    else IconButton(onClick = { actions.onRefresh(c) }) { Icon(Icons.Rounded.Refresh, "Refresh now") }
                }
                IconButton(onClick = { actions.onPin(c, !c.pinned) }) {
                    Icon(if (c.pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin, if (c.pinned) "Take off Home" else "Put on Home",
                        tint = if (c.pinned) cs.primary else cs.onSurfaceVariant)
                }
            }
            if (c.error.isNotBlank()) Surface(shape = MaterialTheme.shapes.medium, color = cs.errorContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text("The last refresh didn't work, so this may be out of date. Ask your agent to fix it.", Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall, color = cs.onErrorContainer)
            }
            if (LocalSheetPreview.current) {
                // Screenshot tests can't run a WebView: show where it sits.
                Box(Modifier.fillMaxWidth().weight(1f).padding(20.dp).clip(MaterialTheme.shapes.large).background(cs.surfaceContainer))
            } else {
                var view by remember { mutableStateOf<WebView?>(null) }
                AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { ctx ->
                    WebView(ctx).also { wv ->
                        val bridge = CardBridge(
                            data = { latest.data },
                            onRun = { id, input -> wv.post { scope.launchRun(wv, id) { actions.onRun(latest, input) } } },
                            onOpen = { url -> wv.post { actions.onOpenLink(url) } },
                            onAsk = { text -> wv.post { actions.onAsk(latest, text) } },
                        )
                        CardWeb.configure(wv, bridge) { url -> actions.onOpenLink(url) }
                        view = wv
                    }
                }, onRelease = { it.destroy() })
                // The page follows the HTML and the theme; fresh data is pushed into the open page.
                LaunchedEffect(view, c.html, dark, accent) { view?.let { CardWeb.load(it, CardKit.page(context, c.html, dark, accent)) } }
                var first by remember { mutableStateOf(true) }
                LaunchedEffect(view, c.data) { if (first) { first = false; return@LaunchedEffect }; view?.evaluateJavascript("window.__cardUpdate && window.__cardUpdate(${JSONObject.quote(c.data)})", null) }
            }
        }
    }
    if (LocalSheetPreview.current) Surface(color = cs.surfaceContainerLow, shape = shape, modifier = Modifier.fillMaxSize().padding(top = 48.dp)) {
        Column { Box(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 22.dp).size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(cs.onSurfaceVariant.copy(alpha = 0.4f))); body() }
    } else ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = cs.surfaceContainerLow, shape = shape,
        dragHandle = { BottomSheetDefaults.DragHandle() }) { body() }
}

private fun kotlinx.coroutines.CoroutineScope.launchRun(wv: WebView, id: String, block: suspend () -> Pair<Boolean, String>) {
    launch {
        val (ok, json) = runCatching { block() }.getOrElse { false to JSONObject().put("error", it.message ?: "failed").toString() }
        wv.evaluateJavascript("window.__cardResolve(${JSONObject.quote(id)}, $ok, ${JSONObject.quote(json)})", null)
    }
}

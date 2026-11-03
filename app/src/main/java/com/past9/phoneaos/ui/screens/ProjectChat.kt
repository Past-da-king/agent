package com.past9.phoneaos.ui.screens

import androidx.compose.material.icons.rounded.Image

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.past9.phoneaos.remote.RemoteHub
import com.past9.phoneaos.remote.RemoteRows
import com.past9.phoneaos.remote.RemoteSession
import com.past9.phoneaos.ui.Markdown
import com.past9.phoneaos.ui.ProjectIconTile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * One of the computer's project chats, opened from a "Sent to ..." line (29 Oct 2026, @-mentions). It reads that chat's
 * newest messages by index (data.where), follows it live from the events the link already carries (its rows changing,
 * its run starting and ending), and sends what he types into it. Nothing is kept on the phone: it is a window.
 */
@Composable
fun ProjectChatScreen(chat: String, name: String, onBack: () -> Unit,
                      /** The computer's cards; only this project's show here (the main thread shows them all). */
                      live: com.past9.phoneaos.remote.LiveCardsUi? = null) {
    val session = RemoteHub.session.collectAsStateWithLifecycle().value
    val project = session?.mentions?.collectAsStateWithLifecycle()?.value?.firstOrNull { it.name == name }
    // System back goes to the main thread too, the same as the arrow.
    androidx.activity.compose.BackHandler { onBack() }
    val mine = remember(live, chat, project?.id, name) { live?.scoped(chat, project?.id.orEmpty(), name) }
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val box = remember(chat) { com.past9.phoneaos.ui.ComposerBoxes.box("chat:$chat") }
    val appScope = com.past9.phoneaos.App.graph(androidx.compose.ui.platform.LocalContext.current).scope
    val tools = com.past9.phoneaos.ui.LocalComposerTools.current
    var more by remember { mutableStateOf(false) }
    var loadingOlder by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()

    // Mobile-data rules: the newest PAGE messages, older ones a page at a time as he scrolls up (data.where before).
    suspend fun page(s: RemoteSession, before: Long?): List<JSONObject> {
        val a = s.call("data.where", listOf("chat_item", com.past9.phoneaos.remote.Mentions.pageWhere(chat, PAGE, before)), 30_000) as? org.json.JSONArray ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { RemoteRows.chatOf(it) == chat }
    }
    suspend fun load(s: RemoteSession) {
        runCatching { page(s, null) to s.chatRunning(chat) }
            .onSuccess { (r, w) -> if (!loaded) more = r.size >= PAGE; rows = com.past9.phoneaos.remote.Mentions.merge(rows, r); working = w; problem = ""; loaded = true }
            .onFailure { problem = "Couldn't read this chat from your computer: ${it.message}"; loaded = true }
    }
    suspend fun loadOlder(s: RemoteSession) {
        if (loadingOlder || !more) return
        loadingOlder = true
        val oldest = rows.minOfOrNull { it.optLong("createdAt") } ?: 0L
        runCatching { page(s, oldest) }
            .onSuccess { r -> more = r.size >= PAGE; rows = com.past9.phoneaos.remote.Mentions.merge(rows, r); android.util.Log.i("Mentions", "older page: ${r.size} rows") }
            .onFailure { problem = "Couldn't load earlier messages: ${it.message}" }
        loadingOlder = false
    }
    // Scrolled up to the top: the page above.
    val atTop by remember { derivedStateOf { list.firstVisibleItemIndex == 0 && list.layoutInfo.totalItemsCount > 0 } }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(atTop, more, session, settled) { if (atTop && more && loaded && settled) session?.let { loadOlder(it) } }
    LaunchedEffect(session, chat) {
        val s = session ?: return@LaunchedEffect
        load(s)
        // Follow it: a row of this chat changed, or its run started or ended. A burst of changes is read once.
        s.events.filter { (event, payload) -> concerns(event, payload, chat) }.collectLatest { delay(if (s.metered) 900 else 400); load(s) }
    }
    // Follow the newest message (not when a page above arrives).
    val newestKey = rows.lastOrNull()?.let { it.optString("id") + it.optString("text").length }
    LaunchedEffect(newestKey) { if (rows.isNotEmpty()) { list.scrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1); delay(300); settled = true } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to the chat") }
                if (project != null) ProjectIconTile(project.name, project.glyph, project.color, project.image, 32.dp, working = working)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (working) "Working on it" else "Project chat on your computer", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        bottomBar = {
            // THE message box (the main chat's): photos, documents, camera, mic, '@' another project. Its sends carry
            // their pictures (sendTo uploads each first; before 0.15.11 this box was text only and sent none).
            val acts = com.past9.phoneaos.ui.composerActionsFor(box, tools, working, appScope, send = { text, pics, files ->
                val s = session ?: throw java.io.IOException("Not linked to a computer.")
                s.sendTo(chat, text, pics, files)
                scope.launch { load(s) }
            }, stop = { session?.let { s -> scope.launch { runCatching { s.call("chat.stop", listOf(chat), 15_000) } } } },
                onFailed = { problem = "Couldn't send that: $it. Your words are still in the box." })
            MessageComposer(box.draft, { box.draft = it }, working, acts, box.attachments, box.docs.keys.toList(), tools?.listening(box) == true, placeholder = "Message $name", dictationOwner = box, sending = box.sending,
                failed = box.failed, onRetry = box.retry)
        },
    ) { pad ->
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (more) item(key = "older") { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TextButton(onClick = { session?.let { s -> scope.launch { loadOlder(s) } } }, enabled = !loadingOlder) { Text(if (loadingOlder) "Loading..." else "Earlier messages") } } }
            if (problem.isNotEmpty()) item(key = "problem") { Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (!loaded) item(key = "loading") { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() } }
            else if (rows.isEmpty() && problem.isEmpty()) item(key = "empty") { Text("Nothing in this chat yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(rows, key = { it.optString("id") }) { row ->
                when (row.optString("kind")) {
                    "user" -> {
                        val via = runCatching { JSONObject(row.optString("meta").ifBlank { "{}" }).optJSONObject("via") }.getOrNull()
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                            if (via != null) Text("From ${via.optString("name").ifBlank { "another chat" }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Surface(shape = RoundedCornerShape(com.past9.phoneaos.ui.theme.Radius.md), color = if (via == null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.widthIn(max = 320.dp)) {
                                Text(row.optString("text"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                            }
                            // The pictures he sent with it (paths on the computer, fetched here once; on mobile data on a tap).
                            RowPictures(row, session)
                        }
                    }
                    "agent" -> Markdown(row.optString("text"), Modifier.fillMaxWidth().padding(end = 12.dp))
                    else -> Text(row.optString("text"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                }
            }
            if (mine != null && mine.cards.isNotEmpty()) item(key = "live-cards") { com.past9.phoneaos.remote.LiveCardList(mine) }
            if (working) item(key = "working") {
                Row(verticalAlignment = Alignment.CenterVertically) { LoadingIndicator(Modifier.size(28.dp)); Spacer(Modifier.width(8.dp)); Text("Working…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** Messages read at a time on the phone (mobile-data rules). */
internal const val PAGE = 20

/** Whether a link event is about this chat: one of its rows, or its run starting or ending. */
internal fun concerns(event: String, payload: Any?, chat: String): Boolean {
    val p = payload as? JSONObject ?: return false
    return when (event) {
        RemoteSession.EVT_CHAT -> p.optString("chat") == chat && p.optString("type") in setOf("run_started", "run_finished", "chat_changed")
        RemoteSession.EVT_DATA -> p.optString("entity") == "chat_item" && (p.optJSONObject("row")?.let { RemoteRows.chatOf(it) == chat } ?: false)
        else -> false
    }
}

/**
 * A project chat message's pictures (its attachments and images on the computer), drawn under the bubble. On Wi-Fi they
 * come at once; on mobile data a tap brings them (the same rule as the main chat).
 */
@Composable
private fun RowPictures(row: JSONObject, session: RemoteSession?) {
    val meta = remember(row.optString("id"), row.optString("meta")) { runCatching { JSONObject(row.optString("meta").ifBlank { "{}" }) }.getOrNull() ?: JSONObject() }
    val count = remember(meta) { RemoteRows.mediaPaths(meta).size }
    if (count == 0 || session == null) return
    var local by remember(meta) { mutableStateOf<List<String>>(emptyList()) }
    var asked by remember(meta) { mutableStateOf(false) }
    LaunchedEffect(meta, asked) { local = runCatching { session.picturesOf(meta, force = asked).values.toList() }.getOrDefault(emptyList()) }
    Spacer(Modifier.height(6.dp))
    if (local.isNotEmpty()) com.past9.phoneaos.ui.ImageStrip(local.map { com.past9.phoneaos.ui.ChatImage(java.io.File(it).toURI().toString(), "Photo you sent") }, Modifier.widthIn(max = 320.dp))
    else Surface(onClick = { asked = true }, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(androidx.compose.material.icons.Icons.Rounded.Image, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(if (count == 1) "1 photo · tap to load" else "$count photos · tap to load", style = MaterialTheme.typography.labelLarge)
        }
    }
}

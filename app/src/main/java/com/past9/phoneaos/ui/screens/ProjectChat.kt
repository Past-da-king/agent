package com.past9.phoneaos.ui.screens

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
fun ProjectChatScreen(chat: String, name: String, onBack: () -> Unit) {
    val session = RemoteHub.session.collectAsStateWithLifecycle().value
    val project = session?.mentions?.collectAsStateWithLifecycle()?.value?.firstOrNull { it.name == name }
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()

    suspend fun load(s: RemoteSession) {
        runCatching { s.chatRows(chat) to s.chatRunning(chat) }
            .onSuccess { (r, w) -> rows = r; working = w; problem = ""; loaded = true }
            .onFailure { problem = "Couldn't read this chat from your computer: ${it.message}"; loaded = true }
    }
    LaunchedEffect(session, chat) {
        val s = session ?: return@LaunchedEffect
        load(s)
        // Follow it: a row of this chat changed, or its run started or ended. A burst of changes is read once.
        s.events.filter { (event, payload) -> concerns(event, payload, chat) }.collectLatest { delay(if (s.metered) 900 else 400); load(s) }
    }
    LaunchedEffect(rows.size, rows.lastOrNull()?.optString("text")?.length) { if (rows.isNotEmpty()) list.animateScrollToItem(rows.size - 1) }

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
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
                    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                        Box(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                            if (draft.isEmpty()) Text("Message $name", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            BasicTextField(draft, { draft = it }, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), maxLines = 6, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    val canSend = draft.isNotBlank() && session != null
                    FilledIconButton(onClick = {
                        val s = session ?: return@FilledIconButton
                        val text = draft.trim(); if (text.isEmpty()) return@FilledIconButton
                        draft = ""
                        scope.launch { runCatching { s.sendTo(chat, text) }.onFailure { problem = "Couldn't send that: ${it.message}"; draft = text }; load(s) }
                    }, enabled = canSend, modifier = Modifier.size(56.dp), shape = CircleShape) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Send to $name")
                    }
                }
            }
        },
    ) { pad ->
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (problem.isNotEmpty()) item(key = "problem") { Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (!loaded) item(key = "loading") { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() } }
            else if (rows.isEmpty() && problem.isEmpty()) item(key = "empty") { Text("Nothing in this chat yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(rows, key = { it.optString("id") }) { row ->
                when (row.optString("kind")) {
                    "user" -> {
                        val via = runCatching { JSONObject(row.optString("meta").ifBlank { "{}" }).optJSONObject("via") }.getOrNull()
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                            if (via != null) Text("From ${via.optString("name").ifBlank { "another chat" }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Surface(shape = RoundedCornerShape(20.dp), color = if (via == null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.widthIn(max = 320.dp)) {
                                Text(row.optString("text"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                            }
                        }
                    }
                    "agent" -> Markdown(row.optString("text"), Modifier.fillMaxWidth().padding(end = 12.dp))
                    else -> Text(row.optString("text"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                }
            }
            if (working) item(key = "working") {
                Row(verticalAlignment = Alignment.CenterVertically) { LoadingIndicator(Modifier.size(28.dp)); Spacer(Modifier.width(8.dp)); Text("Working…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** Whether a link event is about this chat: one of its rows, or its run starting or ending. */
internal fun concerns(event: String, payload: Any?, chat: String): Boolean {
    val p = payload as? JSONObject ?: return false
    return when (event) {
        RemoteSession.EVT_CHAT -> p.optString("chat") == chat && p.optString("type") in setOf("run_started", "run_finished", "chat_changed")
        RemoteSession.EVT_DATA -> p.optString("entity") == "chat_item" && (p.optJSONObject("row")?.let { RemoteRows.chatOf(it) == chat } ?: false)
        else -> false
    }
}

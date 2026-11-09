package com.past9.phoneaos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.past9.phoneaos.remote.RemoteHub
import com.past9.phoneaos.remote.RunningFeed
import com.past9.phoneaos.remote.RunningList
import com.past9.phoneaos.remote.SwitchRow

/**
 * What "New project" in the switcher does. The app provides it once near its root; while nobody does, the row is
 * left out rather than shown dead.
 */
val LocalNewProject = compositionLocalOf<(() -> Unit)?> { null }

/** The line under a name in the switcher: what was last said there and when, from the newest message of that chat. */
fun switcherLine(text: String, at: Long, now: Long = System.currentTimeMillis()): String {
    val said = text.lineSequence().map { it.trim().trimStart('#', '-', '*', '>', ' ') }.firstOrNull { it.isNotEmpty() }.orEmpty().take(90)
    val time = if (at > 0) relativeTime(at, now) else ""
    return listOf(time, said).filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * The project switcher: a slide-up sheet with the main chat first, then his projects (icon, name, how much is
 * running there, the latest chat's last line), the one he is in marked. A tap opens that project's newest chat.
 * "New project" sits at the bottom, in reach of the thumb. It works with no projects, one, or a long list that scrolls.
 */
@Composable
fun ProjectSwitcherSheet(rows: List<SwitchRow>, lines: Map<String, String>, onPick: (SwitchRow) -> Unit, onNewProject: (() -> Unit)?, onDismiss: () -> Unit) {
    AgentSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                SheetHead(Icons.Rounded.SwapHoriz, "Switch", "Chats and projects")
                Spacer(Modifier.height(16.dp))
            }
            LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows, key = { it.key }) { r -> SwitcherRow(r, lines[r.key].orEmpty()) { onPick(r) } }
                if (rows.size <= 1) item(key = "none") {
                    Text("No projects yet. A project keeps its own chats, files and memory.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                }
            }
            if (onNewProject != null) {
                val cs = MaterialTheme.colorScheme
                Surface(onClick = onNewProject, shape = MaterialTheme.shapes.large, color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 20.dp).heightIn(min = 64.dp)) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null, Modifier.size(24.dp)) }
                        Spacer(Modifier.width(14.dp))
                        Text("New project", style = MaterialTheme.typography.titleMedium)
                    }
                }
            } else Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SwitcherRow(r: SwitchRow, line: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sub = line.ifBlank { if (r.project == null) "Your agent's own chat" else if (r.chat.isBlank()) "No chat yet" else "" }
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = if (r.current) cs.primaryContainer else cs.surfaceContainerHigh,
        contentColor = if (r.current) cs.onPrimaryContainer else cs.onSurface,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).semantics { contentDescription = r.name + (if (r.current) ", you are here" else "") + (if (r.running > 0) ", ${r.running} running" else "") }) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val p = r.project
            if (p == null) Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { AgentAvatar(working = r.running > 0, size = 34.dp) }
            else ProjectIconTile(p.name, p.glyph, p.color, p.image, 36.dp, working = r.running > 0)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (r.current) cs.onPrimaryContainer.copy(alpha = 0.8f) else cs.onSurfaceVariant)
            }
            if (r.running > 0) {
                Spacer(Modifier.width(10.dp))
                StatusPill("${r.running} running", if (r.current) cs.surface else cs.primaryContainer, if (r.current) cs.onSurface else cs.onPrimaryContainer)
            }
            if (r.current) { Spacer(Modifier.width(8.dp)); Icon(Icons.Rounded.Check, "You are here", Modifier.size(22.dp)) }
        }
    }
}

/**
 * The switcher on a chat's title. [current] is the chat he is in ("main" or a project chat). It reads the linked
 * computer's projects itself and follows them live; each project's last line is read once while the sheet is open.
 * [onOpenChat] gets "main" for the main chat, else the project's newest chat and its name.
 */
@Composable
fun ProjectSwitcher(current: String, onOpenChat: (chat: String, name: String) -> Unit, onDismiss: () -> Unit, onNewProject: (() -> Unit)? = LocalNewProject.current) {
    RunningFeed.Follow()
    val session = RemoteHub.session.collectAsStateWithLifecycle().value
    val cards = session?.cards?.collectAsStateWithLifecycle()?.value.orEmpty()
    val projects = RunningFeed.projects
    val states = RunningFeed.states
    val rows = remember(projects, cards, current, states) { RunningList.switcher(projects, cards, current, states) }
    val lines = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(session, rows.map { it.chat }) {
        val s = session ?: return@LaunchedEffect
        for (r in rows) {
            if (r.chat.isBlank() || r.chat == RunningList.MAIN || lines.containsKey(r.key)) continue
            val last = runCatching { s.chatRows(r.chat, 1).lastOrNull() }.getOrNull() ?: continue
            lines[r.key] = switcherLine(last.optString("text"), last.optLong("createdAt"))
        }
    }
    ProjectSwitcherSheet(rows, lines, onPick = { r ->
        when {
            r.current -> onDismiss()
            r.chat.isBlank() -> AppNotice.say("${r.name} has no chat yet. Start one on your computer.")
            else -> { onDismiss(); onOpenChat(r.chat, r.name) }
        }
    }, onNewProject = onNewProject?.let { f -> { onDismiss(); f() } }, onDismiss = onDismiss)
}

/** A chat title that opens the switcher: wrap the title's own content. With no computer linked it does [otherwise]. */
@Composable
fun SwitcherTitle(current: String, onOpenChat: (chat: String, name: String) -> Unit, modifier: Modifier = Modifier, otherwise: (() -> Unit)? = null,
                  content: @Composable () -> Unit) {
    val linked = RemoteHub.session.collectAsStateWithLifecycle().value != null
    var open by remember { mutableStateOf(false) }
    val tap: (() -> Unit)? = if (linked) ({ open = true }) else otherwise
    Surface(onClick = { tap?.invoke() }, enabled = tap != null, shape = MaterialTheme.shapes.medium, color = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface, modifier = modifier) { content() }
    if (open) ProjectSwitcher(current, onOpenChat, onDismiss = { open = false })
}

package com.past9.phoneaos.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.agent.AgentStatus
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.data.GoalRow
import com.past9.phoneaos.data.TaskRow
import com.past9.phoneaos.data.TriggerRow
import com.past9.phoneaos.triggers.Routines
import com.past9.phoneaos.ui.AgentAvatar
import com.past9.phoneaos.ui.SectionHeader
import com.past9.phoneaos.ui.StatusPill
import com.past9.phoneaos.ui.theme.Eyebrow
import com.past9.phoneaos.ui.theme.LocalExtra
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class HomeState(
    val agentName: String = "Your agent",
    val userName: String = "",
    val status: AgentStatus = AgentStatus(),
    val items: List<ChatItem> = emptyList(),
    val goals: List<GoalRow> = emptyList(),
    val tasks: List<TaskRow> = emptyList(),
    val routines: List<TriggerRow> = emptyList(),
    val notifications24h: Int = 0,
    val notifApps: Int = 0,
    val browserLive: Boolean = false,
    val now: Long = System.currentTimeMillis(),
)

data class HomeActions(
    val onProfile: () -> Unit = {},
    val onChat: () -> Unit = {},
    val onAnswer: (Long, String) -> Unit = { _, _ -> },
    val onToggleTask: (TaskRow) -> Unit = {},
    val onTasks: () -> Unit = {},
    val onGoal: (GoalRow) -> Unit = {},
    val onRoutines: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onBrowser: () -> Unit = {},
    val onNotifications: () -> Unit = {},
    /** Live voice call with the agent; null when live calls are off. */
    val onCall: (() -> Unit)? = null,
)

/** What the agent is doing right now, in one human line. */
fun nowLine(s: HomeState): String {
    val pending = s.items.count { it.kind == "question" && !JSONObject(it.meta).has("answer") }
    return when {
        pending > 0 -> "Waiting for you on ${if (pending == 1) "one thing" else "$pending things"}"
        s.status.working -> s.status.label + (if (s.status.helpers > 0) " with ${s.status.helpers} helper${if (s.status.helpers > 1) "s" else ""}" else "") + "…"
        else -> "Free. Ask me anything."
    }
}

private fun dayKey(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

@Composable
fun HomeScreen(s: HomeState, actions: HomeActions, bottomPadding: Dp = 120.dp) {
    val cs = MaterialTheme.colorScheme
    val today = dayKey(s.now)
    val pending = s.items.filter { it.kind == "question" && !JSONObject(it.meta).has("answer") }
    val myTodos = s.tasks.filter { it.owner == "user" && it.status != "done" }.sortedWith(compareBy({ it.dueAt ?: Long.MAX_VALUE }, { it.createdAt }))
    val blockedMine = s.tasks.filter { it.status == "blocked" && it.blocker.isNotBlank() && it.owner == "agent" }
    val openGoals = s.goals.filter { it.status == "open" }
    val lastAgent = s.items.lastOrNull { it.kind == "agent" }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM")).uppercase(), style = Eyebrow, color = cs.primary)
                    Text(greeting(s.userName), style = MaterialTheme.typography.headlineMedium)
                }
                IconButton(onClick = actions.onSettings) { Icon(Icons.Rounded.Settings, "Settings", tint = cs.onSurfaceVariant) }
            }
        }
        // The agent, right now. Tap for its profile.
        item {
            Surface(onClick = actions.onProfile, shape = MaterialTheme.shapes.extraLarge, color = cs.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AgentAvatar(working = s.status.working, needsYou = pending.isNotEmpty(), size = 76.dp)
                        Spacer(Modifier.width(18.dp))
                        Column(Modifier.weight(1f)) {
                            Text("RIGHT NOW", style = Eyebrow, color = cs.onPrimaryContainer.copy(alpha = 0.75f))
                            Text(s.agentName, style = MaterialTheme.typography.titleLarge, color = cs.onPrimaryContainer)
                            AnimatedContent(nowLine(s), transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "now") {
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer)
                            }
                        }
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Open profile", tint = cs.onPrimaryContainer)
                    }
                    val focus = openGoals.firstOrNull { g -> s.tasks.any { it.goalId == g.id && it.status == "doing" } } ?: openGoals.firstOrNull()
                    if (focus != null) {
                        val gt = s.tasks.filter { it.goalId == focus.id }; val done = gt.count { it.status == "done" }
                        Spacer(Modifier.height(16.dp))
                        Text("WORKING ON", style = Eyebrow, color = cs.onPrimaryContainer.copy(alpha = 0.75f))
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(focus.title, style = MaterialTheme.typography.titleMedium, color = cs.onPrimaryContainer, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("$done/${gt.size}", style = MaterialTheme.typography.labelLarge, color = cs.onPrimaryContainer)
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearWavyProgressIndicator(progress = { if (gt.isEmpty()) 0f else done / gt.size.toFloat() }, modifier = Modifier.fillMaxWidth(), color = cs.onPrimaryContainer, trackColor = cs.onPrimaryContainer.copy(alpha = 0.2f))
                    }
                    if (actions.onCall != null) {
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = actions.onCall, shapes = ButtonDefaults.shapes(), colors = ButtonDefaults.buttonColors(containerColor = cs.onPrimaryContainer, contentColor = cs.primaryContainer)) {
                            Icon(Icons.Rounded.Call, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Call ${s.agentName.takeIf { it != "Your agent" } ?: "your agent"}")
                        }
                    }
                    if (s.browserLive) {
                        Spacer(Modifier.height(14.dp))
                        FilledTonalButton(onClick = actions.onBrowser, shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.Language, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Watch it browse") }
                    }
                }
            }
        }
        if (pending.isNotEmpty() || blockedMine.isNotEmpty()) {
            item { SectionHeader("Needs you", "Your agent can't go on without these", Modifier.padding(start = 4.dp, top = 6.dp)) }
            items(pending, key = { "q${it.id}" }) { q -> NeedsYouCard(q, actions) }
            items(blockedMine, key = { "b${it.id}" }) { t ->
                Surface(onClick = actions.onChat, shape = MaterialTheme.shapes.large, color = cs.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("BLOCKED", style = Eyebrow, color = cs.onTertiaryContainer)
                        Text(t.title, style = MaterialTheme.typography.titleSmall, color = cs.onTertiaryContainer)
                        Text("Needs: ${t.blocker}", style = MaterialTheme.typography.bodyMedium, color = cs.onTertiaryContainer)
                    }
                }
            }
        }
        item { SectionHeader("Your to-dos", if (myTodos.isEmpty()) "Nothing on your list" else "${myTodos.size} open, soonest first", Modifier.padding(start = 4.dp, top = 6.dp)) {
            TextButton(onClick = actions.onTasks) { Text("All") }
        } }
        item {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    if (myTodos.isEmpty()) Text("Say \"remind me to…\" or \"add … to my list\" and it lands here.", Modifier.padding(18.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    myTodos.take(5).forEach { t -> TodoLine(t, today, actions.onToggleTask) }
                    if (myTodos.size > 5) TextButton(onClick = actions.onTasks, modifier = Modifier.padding(start = 8.dp)) { Text("${myTodos.size - 5} more") }
                }
            }
        }
        if (openGoals.isNotEmpty()) {
            item { SectionHeader("Goals", "What your agent is working through", Modifier.padding(start = 4.dp, top = 6.dp)) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(end = 16.dp)) {
                    items(openGoals, key = { it.id }) { g -> GoalChipCard(g, s.tasks.filter { it.goalId == g.id }) { actions.onGoal(g) } }
                }
            }
        }
        val coming = comingUp(s)
        if (coming.isNotEmpty()) {
            item { SectionHeader("Coming up", "Routines and deadlines in the next few days", Modifier.padding(start = 4.dp, top = 6.dp)) }
            item {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 6.dp)) { coming.take(6).forEach { (icon, title, line, urgent) -> ComingLine(icon, title, line, urgent, actions.onRoutines) } }
                }
            }
        }
        if (s.notifApps > 0) item {
            Surface(onClick = actions.onNotifications, shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.NotificationsActive, null, tint = cs.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${s.notifications24h} notifications today", style = MaterialTheme.typography.titleSmall)
                        Text("From ${s.notifApps} app${if (s.notifApps == 1) "" else "s"} you let your agent read", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
        val latest = s.items.lastOrNull { it.kind == "agent" || it.kind == "voice" }
        if (latest != null) {
            item { SectionHeader("Latest", "The last thing your agent told you", Modifier.padding(start = 4.dp, top = 6.dp)) }
            item {
                Surface(onClick = actions.onChat, shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.Top) {
                        AgentAvatar(working = false, size = 36.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text((if (latest.kind == "voice") "VOICE NOTE · " else "") + com.past9.phoneaos.ui.relativeTime(latest.createdAt).uppercase(), style = Eyebrow, color = cs.primary)
                            Spacer(Modifier.height(4.dp))
                            // The real message, rendered (bold, lists, links), cut to a few lines; no raw markdown.
                            Box(Modifier.heightIn(max = 112.dp)) {
                                com.past9.phoneaos.ui.Markdown(com.past9.phoneaos.voice.Tts.stripEmoji(latest.text).replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "").lines().filter { it.isNotBlank() }.take(4).joinToString("\n"),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("Continue in chat", style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        }
                    }
                }
            }
        }
    }
}

private fun greeting(name: String): String {
    val h = ZonedDateTime.now().hour
    val part = when (h) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Hey" }
    return if (name.isBlank()) "$part." else "$part, $name."
}

@Composable
private fun NeedsYouCard(q: ChatItem, actions: HomeActions) {
    val cs = MaterialTheme.colorScheme
    val approval = q.text.startsWith("APPROVAL|")
    val parts = q.text.split("|", limit = 3)
    val opts = JSONObject(q.meta).optJSONArray("options")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
    com.past9.phoneaos.tools.ConnectRequest.parse(q.text)?.let { req ->
        Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppLogo(req.name, req.logo, 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("CONNECT AN APP", style = Eyebrow, color = cs.onTertiaryContainer)
                        Text(req.summary, style = MaterialTheme.typography.titleMedium, color = cs.onTertiaryContainer, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { actions.onAnswer(q.id, "Decline") }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Decline", color = cs.onTertiaryContainer) }
                    Button(onClick = { actions.onAnswer(q.id, "Connect") }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1.4f).heightIn(min = 48.dp)) { Text("Connect") }
                }
            }
        }
        return
    }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(if (approval) "APPROVE?" else "QUESTION", style = Eyebrow, color = cs.onTertiaryContainer)
            Spacer(Modifier.height(4.dp))
            Text(if (approval) parts.getOrElse(1) { "" } else q.text, style = MaterialTheme.typography.titleMedium, color = cs.onTertiaryContainer, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            if (approval) Button(onClick = actions.onChat, modifier = Modifier.fillMaxWidth().height(48.dp), shapes = ButtonDefaults.shapes()) { Text("Review and approve") }
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                opts.forEachIndexed { i, o ->
                    if (i == 0) Button(onClick = { actions.onAnswer(q.id, o) }, shapes = ButtonDefaults.shapes(), modifier = Modifier.heightIn(min = 44.dp)) { Text(o) }
                    else OutlinedButton(onClick = { actions.onAnswer(q.id, o) }, shapes = ButtonDefaults.shapes(), modifier = Modifier.heightIn(min = 44.dp)) { Text(o, color = cs.onTertiaryContainer) }
                }
            }
        }
    }
}

@Composable
fun TodoLine(t: TaskRow, today: LocalDate, onToggle: (TaskRow) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val due = t.dueAt?.let { dayKey(it) }
    val (label, color) = when {
        due == null -> null to cs.onSurfaceVariant
        due.isBefore(today) -> "Overdue · ${dueLabel(t.dueAt)}" to cs.error
        due == today -> "Today${dueLabel(t.dueAt).substringAfter(",", "").let { if (it.isBlank()) "" else ",$it" }}" to cs.tertiary
        due == today.plusDays(1) -> "Tomorrow" to cs.onSurfaceVariant
        else -> dueLabel(t.dueAt) to cs.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth().clickable { onToggle(t) }.heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = t.status == "done", onCheckedChange = { onToggle(t) })
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (label != null) Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        }
        if (t.status == "blocked") StatusPill("Blocked", cs.tertiaryContainer, cs.onTertiaryContainer, Modifier.padding(end = 8.dp))
    }
}

@Composable
fun GoalChipCard(g: GoalRow, tasks: List<TaskRow>, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val done = tasks.count { it.status == "done" }
    val blocked = tasks.any { it.status == "blocked" }
    val p = if (tasks.isEmpty()) 0f else done / tasks.size.toFloat()
    Surface(onClick = onClick, shape = MaterialTheme.shapes.extraLarge, color = if (blocked) cs.tertiaryContainer else cs.surfaceContainerLow, modifier = Modifier.width(220.dp).height(150.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center) {
                    CircularWavyProgressIndicator(progress = { p }, modifier = Modifier.size(44.dp))
                    Text("${(p * 100).toInt()}", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.weight(1f))
                if (blocked) StatusPill("Blocked", cs.tertiary, cs.onTertiary) else Text("$done/${tasks.size}", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            Text(g.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (blocked) cs.onTertiaryContainer else cs.onSurface)
        }
    }
}

private data class Coming(val icon: ImageVector, val title: String, val line: String, val urgent: Boolean)

private fun comingUp(s: HomeState): List<Coming> {
    val zone = ZoneId.systemDefault()
    val now = java.time.Instant.ofEpochMilli(s.now).atZone(zone)
    val out = mutableListOf<Pair<Long, Coming>>()
    s.routines.filter { it.enabled }.forEach { r ->
        val next = Routines.nextRunIn(r, now)
        if (r.kind == "email" || r.kind == "notification") out += Long.MAX_VALUE - 1 to Coming(Icons.Rounded.Bolt, r.name, if (r.kind == "email") "When a matching email arrives" else "When a matching notification arrives", false)
        else if (next != null) out += next to Coming(Icons.Rounded.Schedule, r.name, whenLabel(r) + " · " + inLabel(next), false)
    }
    s.tasks.filter { it.status != "done" && it.dueAt != null && it.dueAt - s.now < 3 * 86_400_000L }.forEach { t ->
        val ms = t.dueAt!! - s.now
        out += ms to Coming(Icons.Rounded.Event, t.title, if (ms < 0) "Overdue" else "Due " + dueLabel(t.dueAt), ms < 86_400_000L)
    }
    return out.sortedBy { it.first }.map { it.second }
}

@Composable
private fun ComingLine(icon: ImageVector, title: String, line: String, urgent: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(if (urgent) cs.tertiaryContainer else cs.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = if (urgent) cs.onTertiaryContainer else cs.onSecondaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = MaterialTheme.typography.bodySmall, color = if (urgent) cs.tertiary else cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The floating bar: where you are, and one big button to talk to your agent. */
@Composable
fun HomeBar(current: String, onGo: (String) -> Unit, onTalk: () -> Unit, modifier: Modifier = Modifier) {
    val tabs = listOf(Triple("home", Icons.Rounded.Home, "Home"), Triple("tasks", Icons.Rounded.TaskAlt, "Tasks"), Triple("memory", Icons.Rounded.Hub, "Memory"),
        Triple("routines", Icons.Rounded.Schedule, "Routines"), Triple("connections", Icons.Rounded.Apps, "Apps"))
    HorizontalFloatingToolbar(
        expanded = true,
        floatingActionButton = { FloatingToolbarDefaults.VibrantFloatingActionButton(onClick = onTalk, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) { Icon(Icons.AutoMirrored.Rounded.Chat, "Talk to your agent") } },
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
        modifier = modifier.navigationBarsPadding().padding(bottom = 16.dp),
    ) {
        tabs.forEach { (route, icon, label) ->
            val on = route == current
            ToggleButton(checked = on, onCheckedChange = { onGo(route) }, colors = ToggleButtonDefaults.colors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                checkedContainerColor = MaterialTheme.colorScheme.onPrimaryContainer, checkedContentColor = MaterialTheme.colorScheme.primaryContainer)) {
                // Five places + the talk button: icons only, so the bar fits a phone without clipping.
                Icon(icon, label)
            }
        }
    }
}

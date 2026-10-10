package com.past9.phoneaos.ui.screens

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.past9.phoneaos.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.data.GoalRow
import com.past9.phoneaos.data.MemoryRow
import com.past9.phoneaos.data.TaskRow
import com.past9.phoneaos.ui.AgentAvatar
import com.past9.phoneaos.ui.HelperCard
import com.past9.phoneaos.ui.HelperSheet
import com.past9.phoneaos.ui.HelperView
import com.past9.phoneaos.ui.SectionHeader
import com.past9.phoneaos.ui.StatusPill
import com.past9.phoneaos.ui.theme.Eyebrow
import com.past9.phoneaos.ui.theme.LocalExtra

data class ProfileActions(
    val onBack: () -> Unit = {},
    val onChat: () -> Unit = {},
    val onMemory: () -> Unit = {},
    val onRoutines: () -> Unit = {},
    val onStyle: () -> Unit = {},
)

/**
 * The agent's profile: what it is doing right now, what it thinks about you, and every goal it
 * is working on with the steps under each, blocked ones first.
 */
@Composable
fun ProfileScreen(home: HomeState, memories: List<MemoryRow>, actions: ProfileActions) {
    val cs = MaterialTheme.colorScheme
    val goals = home.goals.filter { it.status == "open" }
        .sortedByDescending { g -> home.tasks.any { it.goalId == g.id && it.status == "blocked" } }
    val achieved = home.goals.count { it.status == "achieved" }
    val helpers = remember(home.items) { HelperView.from(home.items) }
    var openHelper by remember { mutableStateOf<Long?>(null) }
    val stop = LocalStopHelper.current
    helpers.firstOrNull { it.id == openHelper && it.working }?.let { h -> HelperSheet(h, stop, onDismiss = { openHelper = null }, now = home.now) }
    val about = (memories.filter { it.pinned } + memories.filter { !it.pinned }.sortedByDescending { it.updatedAt }).distinctBy { it.id }.take(6)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Box(Modifier.fillMaxWidth().background(cs.primaryContainer, MaterialTheme.shapes.extraLarge.copy(topStart = androidx.compose.foundation.shape.CornerSize(0.dp), topEnd = androidx.compose.foundation.shape.CornerSize(0.dp)))) {
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.profile_back), tint = cs.onPrimaryContainer) }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = actions.onStyle) { Icon(Icons.Rounded.Palette, stringResource(R.string.profile_change_look), tint = cs.onPrimaryContainer) }
                    }
                    AgentAvatar(working = home.status.working, needsYou = home.items.any { it.kind == "question" && !org.json.JSONObject(it.meta).has("answer") }, size = 128.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(if (home.agentName == "Your agent") stringResource(R.string.profile_your_agent) else home.agentName, style = MaterialTheme.typography.headlineMedium, color = cs.onPrimaryContainer)
                    Spacer(Modifier.height(4.dp))
                    Text(nowLine(home), style = MaterialTheme.typography.bodyLarge, color = cs.onPrimaryContainer, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Stat("${goals.size}", pluralStringResource(R.plurals.profile_stat_goals, goals.size)); home.tasks.count { it.owner == "agent" && it.status == "done" }.let { n -> Stat("$n", pluralStringResource(R.plurals.profile_stat_steps, n)) }; Stat("${memories.size}", pluralStringResource(R.plurals.profile_stat_memories, memories.size)); home.routines.count { it.enabled }.let { n -> Stat("$n", pluralStringResource(R.plurals.profile_stat_routines, n)) }
                    }
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = actions.onChat, shapes = ButtonDefaults.shapes(), colors = ButtonDefaults.buttonColors(containerColor = cs.onPrimaryContainer, contentColor = cs.primaryContainer)) {
                        Text(if (home.agentName == "Your agent") stringResource(R.string.profile_talk_to_your_agent) else stringResource(R.string.profile_talk_to, home.agentName))
                        Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null)
                    }
                }
            }
        }
        item {
            val running = helpers.filter { it.working }
            SectionHeader(stringResource(R.string.profile_helpers), if (running.isEmpty()) stringResource(R.string.profile_helpers_none) else stringResource(R.string.profile_helpers_running, running.size),
                Modifier.padding(horizontal = 20.dp))
        }
        items(helpers.filter { it.working }, key = { "h${it.id}" }) { h -> HelperCard(h, onClick = { openHelper = h.id }, modifier = Modifier.padding(horizontal = 16.dp), now = home.now) }
        item { SectionHeader(stringResource(R.string.profile_about), stringResource(R.string.profile_about_sub), Modifier.padding(horizontal = 20.dp)) { TextButton(onClick = actions.onMemory) { Text(stringResource(R.string.profile_all)) } } }
        item {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    if (about.isEmpty()) Text(stringResource(R.string.profile_about_empty), Modifier.padding(18.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    about.forEach { m ->
                        Row(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
                            Icon(if (m.pinned) Icons.Rounded.PushPin else Icons.Rounded.Psychology, null, Modifier.size(18.dp).padding(top = 2.dp), tint = cs.primary)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(m.title, style = MaterialTheme.typography.titleSmall)
                                Text(summaryOf(m.body), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        item { SectionHeader(stringResource(R.string.profile_goals), if (goals.isEmpty()) (if (achieved > 0) stringResource(R.string.profile_goals_none_achieved, achieved) else stringResource(R.string.profile_goals_none)) else (if (achieved > 0) stringResource(R.string.profile_goals_open_achieved, goals.size, achieved) else stringResource(R.string.profile_goals_open, goals.size)), Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) }
        if (goals.isEmpty()) item {
            Text(stringResource(R.string.profile_goals_empty), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
        items(goals, key = { it.id }) { g -> GoalDetail(g, home.tasks.filter { it.goalId == g.id }) }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = cs.surface.copy(alpha = 0.55f)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
            Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun GoalDetail(g: GoalRow, tasks: List<TaskRow>) {
    val cs = MaterialTheme.colorScheme
    val done = tasks.count { it.status == "done" }
    val blocked = tasks.filter { it.status == "blocked" }
    val p = if (tasks.isEmpty()) 0f else done / tasks.size.toFloat()
    Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(vertical = 16.dp)) {
            Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (blocked.isNotEmpty()) stringResource(R.string.profile_goal_blocked, done, tasks.size) else stringResource(R.string.profile_goal_done, done, tasks.size), style = Eyebrow, color = if (blocked.isNotEmpty()) cs.tertiary else cs.primary)
                    Text(g.title, style = MaterialTheme.typography.titleLarge)
                    if (g.why.isNotBlank()) Text(g.why, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
                Box(contentAlignment = Alignment.Center) {
                    CircularWavyProgressIndicator(progress = { p }, modifier = Modifier.size(52.dp))
                    Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                }
            }
            blocked.forEach { b ->
                Surface(shape = MaterialTheme.shapes.large, color = cs.tertiaryContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(stringResource(R.string.profile_stuck_on, b.title.uppercase()), style = Eyebrow, color = cs.onTertiaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(b.blocker.ifBlank { stringResource(R.string.profile_waiting) }, style = MaterialTheme.typography.bodyMedium, color = cs.onTertiaryContainer)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            tasks.sortedBy { when (it.status) { "doing" -> 0; "blocked" -> 1; "todo" -> 2; else -> 3 } }.forEach { t -> StepLine(t) }
        }
    }
}

@Composable
private fun StepLine(t: TaskRow) {
    val cs = MaterialTheme.colorScheme
    val extra = LocalExtra.current
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        when (t.status) {
            "done" -> Icon(Icons.Rounded.CheckCircle, stringResource(R.string.profile_done), Modifier.size(22.dp), tint = extra.success)
            "doing" -> LoadingIndicator(Modifier.size(24.dp))
            "blocked" -> Icon(Icons.Rounded.PauseCircle, stringResource(R.string.profile_blocked), Modifier.size(22.dp), tint = cs.tertiary)
            else -> Box(Modifier.size(20.dp).clip(CircleShape).background(cs.outlineVariant))
        }
        Spacer(Modifier.width(14.dp))
        Text(t.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            textDecoration = if (t.status == "done") TextDecoration.LineThrough else null, color = if (t.status == "done") cs.onSurfaceVariant else cs.onSurface)
        if (t.owner == "user") StatusPill(stringResource(R.string.profile_you), cs.primaryContainer, cs.onPrimaryContainer)
    }
}

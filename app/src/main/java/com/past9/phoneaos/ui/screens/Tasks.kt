package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.data.GoalRow
import com.past9.phoneaos.data.TaskRow
import com.past9.phoneaos.ui.AppCard
import com.past9.phoneaos.ui.EmptyState
import com.past9.phoneaos.ui.SectionHeader
import com.past9.phoneaos.ui.StatusPill
import com.past9.phoneaos.ui.SubScreen
import com.past9.phoneaos.ui.theme.Eyebrow
import com.past9.phoneaos.ui.theme.LocalExtra
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.past9.phoneaos.R

data class TaskActions(
    val onBack: () -> Unit = {},
    val onToggle: (TaskRow) -> Unit = {},
    /** title, due time (null = no date) */
    val onAdd: (String, Long?) -> Unit = { _, _ -> },
    val onDelete: (TaskRow) -> Unit = {},
    val onAsk: (String) -> Unit = {},
)

fun dueLabel(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).let {
    if (it.hour == 9 && it.minute == 0) it.format(DateTimeFormatter.ofPattern("EEE d MMM")) else it.format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm"))
}

@Composable
fun TasksScreen(goals: List<GoalRow>, tasks: List<TaskRow>, actions: TaskActions, startTab: Int = 0, bottomPadding: androidx.compose.ui.unit.Dp = 120.dp) {
    var adding by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(startTab) }
    val mine = tasks.filter { it.owner == "user" }
    val agents = tasks.filter { it.owner == "agent" }
    val open = mine.count { it.status != "done" }
    SubScreen(stringResource(R.string.tasks_title), if (tab == 0) (if (open == 0) stringResource(R.string.tasks_clear) else stringResource(R.string.tasks_open_count, open)) else goals.count { it.status == "open" }.let { pluralStringResource(R.plurals.tasks_goals_count, it, it) }, actions.onBack,
        fab = { if (tab == 0) ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text(stringResource(R.string.tasks_add_todo)) }, modifier = Modifier.padding(bottom = bottomPadding - 40.dp)) }) { pad ->
        Column(Modifier.padding(pad)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                listOf(stringResource(R.string.tasks_tab_mine), stringResource(R.string.tasks_tab_agent)).forEachIndexed { i, label ->
                    ToggleButton(tab == i, { tab = i }, modifier = Modifier.weight(1f).height(48.dp),
                        shapes = if (i == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes()) { Text(label) }
                }
            }
            if (tab == 0) {
                if (mine.isEmpty()) { EmptyState(stringResource(R.string.tasks_clear), stringResource(R.string.tasks_empty_body)); return@Column }
                val today = java.time.LocalDate.now()
                LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val todo = mine.filter { it.status != "done" }.sortedWith(compareBy({ it.dueAt ?: Long.MAX_VALUE }, { it.createdAt }))
                    if (todo.isNotEmpty()) item { AppCard(padding = PaddingValues(vertical = 6.dp)) { todo.forEach { TodoLine(it, today, actions.onToggle) } } }
                    val done = mine.filter { it.status == "done" }
                    if (done.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.tasks_done), stringResource(R.string.tasks_done_sub), Modifier.padding(top = 8.dp, start = 4.dp)) }
                        item { AppCard(padding = PaddingValues(vertical = 6.dp), container = MaterialTheme.colorScheme.surfaceContainerLowest) { done.take(30).forEach { TaskLine(it, actions) } } }
                    }
                }
            } else {
                val openGoals = goals.filter { it.status == "open" }
                if (openGoals.isEmpty() && agents.isEmpty()) { EmptyState(stringResource(R.string.tasks_none), stringResource(R.string.tasks_none_body)); return@Column }
                LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(openGoals.sortedByDescending { g -> tasks.any { it.goalId == g.id && it.status == "blocked" } }, key = { "g${it.id}" }) { g -> GoalCard(g, tasks.filter { it.goalId == g.id }, actions) }
                    val loose = agents.filter { t -> t.goalId == null || openGoals.none { it.id == t.goalId } }.filter { it.status != "done" }
                    if (loose.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.tasks_other), stringResource(R.string.tasks_other_sub), Modifier.padding(top = 8.dp, start = 4.dp)) }
                        item { AppCard(padding = PaddingValues(vertical = 6.dp)) { loose.forEach { TaskLine(it, actions) } } }
                    }
                    val achieved = goals.filter { it.status == "achieved" }
                    if (achieved.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.tasks_achieved), stringResource(R.string.tasks_achieved_sub), Modifier.padding(top = 12.dp, start = 4.dp)) }
                        items(achieved, key = { "a${it.id}" }) { g -> Text("✓  ${g.title}", style = MaterialTheme.typography.bodyLarge, color = LocalExtra.current.success, modifier = Modifier.padding(horizontal = 8.dp)) }
                    }
                }
            }
        }
    }
    if (adding) AddTaskSheet({ adding = false }, onAsk = { actions.onAsk(it); adding = false }) { t, due -> actions.onAdd(t, due); adding = false }
}

@Composable
private fun GoalCard(g: GoalRow, tasks: List<TaskRow>, actions: TaskActions) {
    val done = tasks.count { it.status == "done" }
    val progress = if (tasks.isEmpty()) 0f else done / tasks.size.toFloat()
    AppCard(padding = PaddingValues(top = 18.dp, bottom = 8.dp)) {
        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.tasks_goal_eyebrow, done, tasks.size), style = Eyebrow, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                Text(g.title, style = MaterialTheme.typography.titleLarge)
                if (g.why.isNotBlank()) Text(g.why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Box(contentAlignment = Alignment.Center) {
                CircularWavyProgressIndicator(progress = { progress }, modifier = Modifier.size(52.dp))
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(8.dp))
        tasks.sortedBy { it.status == "done" }.forEach { TaskLine(it, actions) }
    }
}

@Composable
fun TaskLine(t: TaskRow, actions: TaskActions) {
    val done = t.status == "done"
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 6.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = done, onCheckedChange = { actions.onToggle(t) })
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textDecoration = if (done) TextDecoration.LineThrough else null, color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            val sub = listOfNotNull(t.blocker.takeIf { t.status == "blocked" && it.isNotBlank() }?.let { stringResource(R.string.tasks_needs, it) }, t.dueAt?.let { dueLabel(it) }, t.notes.takeIf { it.isNotBlank() }, stringResource(R.string.tasks_you).takeIf { t.owner == "user" && t.goalId != null }).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when (t.status) {
            "doing" -> StatusPill(stringResource(R.string.tasks_doing), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
            "blocked" -> StatusPill(stringResource(R.string.tasks_blocked), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        }
        if (t.status == "blocked" && t.blocker.isNotBlank()) Unit
        if (!done && t.owner == "user") IconButton(onClick = { actions.onAsk("Help me with task #${t.id}: ${t.title}") }) { Icon(Icons.Rounded.AutoAwesome, stringResource(R.string.tasks_ask_agent), tint = MaterialTheme.colorScheme.primary) }
    }
}

/** When a to-do is due, in plain choices. Times are local: 18:00 today, 09:00 otherwise. */
internal fun dueChoice(id: String, now: java.time.ZonedDateTime = java.time.ZonedDateTime.now()): Long? {
    val d = now.toLocalDate()
    val at = when (id) {
        "today" -> d.atTime(18, 0).let { if (it.isBefore(now.toLocalDateTime())) now.toLocalDateTime().plusHours(2).withMinute(0) else it }
        "tomorrow" -> d.plusDays(1).atTime(9, 0)
        "weekend" -> d.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.SATURDAY)).atTime(9, 0)
        "nextweek" -> d.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)).atTime(9, 0)
        else -> return null
    }
    return at.atZone(now.zone).toInstant().toEpochMilli()
}

@Composable
internal fun AddTaskSheet(onDismiss: () -> Unit, onAsk: (String) -> Unit, onAdd: (String, Long?) -> Unit) {
    var text by remember { mutableStateOf("") }
    var due by remember { mutableStateOf("none") }
    val dueAt = dueChoice(due)
    com.past9.phoneaos.ui.AppSheet(Icons.Rounded.TaskAlt, stringResource(R.string.tasks_your_list), stringResource(R.string.tasks_new_todo), onDismiss,
        primary = if (dueAt == null) stringResource(R.string.tasks_add_to_list) else stringResource(R.string.tasks_add_for, dueLabel(dueAt)), primaryEnabled = text.isNotBlank(), onPrimary = { onAdd(text.trim(), dueAt) },
        shape = MaterialShapes.Cookie7Sided,
        secondary = {
            TextButton(onClick = { onAsk("Please take care of this for me: ${text.trim()}") }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.tasks_agent_instead))
            }
        }) {
        com.past9.phoneaos.ui.SheetField(text, { text = it }, stringResource(R.string.tasks_what), stringResource(R.string.tasks_what_hint), big = true, minLines = 2)
        Spacer(Modifier.height(20.dp))
        com.past9.phoneaos.ui.ChoiceChips(listOf(
            Triple("none", stringResource(R.string.tasks_no_date), Icons.Rounded.AllInclusive), Triple("today", stringResource(R.string.tasks_today), Icons.Rounded.WbTwilight),
            Triple("tomorrow", stringResource(R.string.tasks_tomorrow), Icons.Rounded.WbSunny), Triple("weekend", stringResource(R.string.tasks_weekend), Icons.Rounded.Weekend),
            Triple("nextweek", stringResource(R.string.tasks_next_week), Icons.Rounded.DateRange),
        ), due, { due = it }, label = stringResource(R.string.tasks_when))
    }
}

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

data class TaskActions(
    val onBack: () -> Unit = {},
    val onToggle: (TaskRow) -> Unit = {},
    val onAdd: (String) -> Unit = {},
    val onDelete: (TaskRow) -> Unit = {},
    val onAsk: (String) -> Unit = {},
)

fun dueLabel(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).let {
    if (it.hour == 9 && it.minute == 0) it.format(DateTimeFormatter.ofPattern("EEE d MMM")) else it.format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm"))
}

@Composable
fun TasksScreen(goals: List<GoalRow>, tasks: List<TaskRow>, actions: TaskActions) {
    var adding by remember { mutableStateOf(false) }
    val open = tasks.count { it.status != "done" }
    SubScreen("Tasks", if (open == 0) "Nothing open" else "$open open", actions.onBack,
        fab = { ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Add task") }) }) { pad ->
        if (goals.isEmpty() && tasks.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize()) { EmptyState("No tasks yet", "Ask your agent to plan something, or add a task yourself. Goals and their tasks show up here.") }
            return@SubScreen
        }
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val openGoals = goals.filter { it.status == "open" }
            if (openGoals.isNotEmpty()) item { SectionHeader("Goals", "Bigger things your agent is working through, task by task", Modifier.padding(top = 8.dp, start = 4.dp)) }
            items(openGoals, key = { "g${it.id}" }) { g -> GoalCard(g, tasks.filter { it.goalId == g.id }, actions) }
            val loose = tasks.filter { t -> t.goalId == null || goals.none { it.id == t.goalId } }
            val looseOpen = loose.filter { it.status != "done" }
            if (looseOpen.isNotEmpty()) {
                item { SectionHeader("To do", "Single tasks, soonest first", Modifier.padding(top = 12.dp, start = 4.dp)) }
                item { AppCard(padding = PaddingValues(vertical = 6.dp)) { looseOpen.sortedBy { it.dueAt ?: Long.MAX_VALUE }.forEach { TaskLine(it, actions) } } }
            }
            val done = tasks.filter { it.status == "done" && (it.goalId == null || openGoals.none { g -> g.id == it.goalId }) }
            if (done.isNotEmpty()) {
                item { SectionHeader("Done", "Finished recently", Modifier.padding(top = 12.dp, start = 4.dp)) }
                item { AppCard(padding = PaddingValues(vertical = 6.dp), container = MaterialTheme.colorScheme.surfaceContainerLowest) { done.take(30).forEach { TaskLine(it, actions) } } }
            }
            val achieved = goals.filter { it.status == "achieved" }
            if (achieved.isNotEmpty()) {
                item { SectionHeader("Achieved", "Goals where every task got done", Modifier.padding(top = 12.dp, start = 4.dp)) }
                items(achieved, key = { "a${it.id}" }) { g -> Text("✓  ${g.title}", style = MaterialTheme.typography.bodyLarge, color = LocalExtra.current.success, modifier = Modifier.padding(horizontal = 8.dp)) }
            }
        }
    }
    if (adding) AddTaskSheet({ adding = false }) { actions.onAdd(it); adding = false }
}

@Composable
private fun GoalCard(g: GoalRow, tasks: List<TaskRow>, actions: TaskActions) {
    val done = tasks.count { it.status == "done" }
    val progress = if (tasks.isEmpty()) 0f else done / tasks.size.toFloat()
    AppCard(padding = PaddingValues(top = 18.dp, bottom = 8.dp)) {
        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("GOAL · $done/${tasks.size} DONE", style = Eyebrow, color = MaterialTheme.colorScheme.primary)
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
            val sub = listOfNotNull(t.dueAt?.let { dueLabel(it) }, t.notes.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when (t.status) {
            "doing" -> StatusPill("Doing", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
            "blocked" -> StatusPill("Blocked", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        }
        if (!done) IconButton(onClick = { actions.onAsk("Help me with task #${t.id}: ${t.title}") }) { Icon(Icons.Rounded.AutoAwesome, "Ask your agent to do this", tint = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun AddTaskSheet(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("New task", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(text, { text = it }, label = { Text("What needs doing?") }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { onAdd(text) }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth().height(56.dp), shapes = ButtonDefaults.shapes()) { Text("Add") }
        }
    }
}

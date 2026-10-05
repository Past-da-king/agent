package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.data.TriggerRow
import com.past9.phoneaos.tools.parseWhen
import com.past9.phoneaos.triggers.Routines
import com.past9.phoneaos.ui.AppCard
import com.past9.phoneaos.ui.EmptyState
import com.past9.phoneaos.ui.SubScreen
import com.past9.phoneaos.ui.relativeTime
import com.past9.phoneaos.ui.theme.Eyebrow

data class RoutineActions(
    val onBack: () -> Unit = {},
    val onToggle: (TriggerRow) -> Unit = {},
    val onRunNow: (TriggerRow) -> Unit = {},
    val onDelete: (TriggerRow) -> Unit = {},
    val onSave: (TriggerRow) -> Unit = {},
)

fun whenLabel(t: TriggerRow): String = when (t.kind) {
    "at" -> parseWhen(t.spec)?.let { "Once, " + dueLabel(it) } ?: "Once"
    "daily" -> "Every day at ${t.spec}"
    "interval" -> (t.spec.toIntOrNull() ?: 60).let { if (it % 60 == 0) "Every ${it / 60} h" else "Every $it min" }
    "email" -> "When a new email matches: ${t.spec}"
    "notification" -> t.spec.split("|").let { "When ${it[0]} notifies" + (it.getOrNull(1)?.takeIf { w -> w.isNotBlank() }?.let { w -> " about \"$w\"" } ?: "") }
    else -> t.kind
}

fun inLabel(ms: Long): String = when {
    ms < 60_000 -> "in under a minute"; ms < 3_600_000 -> "in ${ms / 60_000} min"
    ms < 86_400_000 -> "in ${ms / 3_600_000} h ${(ms % 3_600_000) / 60_000} min"; else -> "in ${ms / 86_400_000} d"
}

private fun kindIcon(kind: String): ImageVector = when (kind) { "at" -> Icons.Rounded.Event; "daily" -> Icons.Rounded.WbSunny; "interval" -> Icons.Rounded.Autorenew; "notification" -> Icons.Rounded.NotificationsActive; else -> Icons.Rounded.Mail }

@Composable
fun RoutinesScreen(routines: List<TriggerRow>, actions: RoutineActions, now: Long = System.currentTimeMillis(), bottomPadding: androidx.compose.ui.unit.Dp = 120.dp) {
    var editing by remember { mutableStateOf<TriggerRow?>(null) }
    SubScreen("Routines", "Things your agent does on its own", actions.onBack,
        fab = { ExtendedFloatingActionButton(onClick = { editing = TriggerRow(name = "", kind = "daily", spec = "07:00", prompt = "") }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("New routine") }, modifier = Modifier.padding(bottom = (bottomPadding - 40.dp).coerceAtLeast(0.dp))) }) { pad ->
        if (routines.isEmpty()) { Box(Modifier.padding(pad)) { EmptyState("No routines yet", "Say \"every morning at 7, brief me on my day\" or \"when my bank emails, tell me what changed\". Or add one here.") }; return@SubScreen }
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding + 60.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(routines, key = { it.id }) { r -> RoutineCard(r, actions, now) { editing = r } }
        }
    }
    editing?.let { r -> RoutineEditor(r, { editing = null }) { actions.onSave(it); editing = null } }
}

@Composable
private fun RoutineCard(r: TriggerRow, actions: RoutineActions, now: Long, onEdit: () -> Unit) {
    val next = if (r.enabled) Routines.nextRunIn(r, java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())) else null
    AppCard(onClick = onEdit, container = if (r.enabled) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerLowest) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(MaterialTheme.shapes.medium).background(if (r.enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                Icon(kindIcon(r.kind), null, tint = if (r.enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(when { !r.enabled -> "PAUSED"; r.kind == "email" -> "WATCHING EVERY 15 MIN"; r.kind == "notification" -> "LISTENING"; next != null -> "NEXT RUN ${inLabel(next).uppercase()}"; else -> "FINISHED" },
                    style = Eyebrow, color = if (r.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(r.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(whenLabel(r), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Switch(r.enabled, { actions.onToggle(r) })
        }
        Spacer(Modifier.height(10.dp))
        Text(r.prompt, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (r.lastRunAt != null) {
            Spacer(Modifier.height(10.dp))
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("LAST RUN · ${relativeTime(r.lastRunAt).uppercase()}", style = Eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(r.lastResult.ifBlank { "No result" }, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            TextButton(onClick = { actions.onRunNow(r) }) { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Run now") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { actions.onDelete(r) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun RoutineEditor(r: TriggerRow, onDismiss: () -> Unit, onSave: (TriggerRow) -> Unit) {
    var name by remember { mutableStateOf(r.name) }
    var prompt by remember { mutableStateOf(r.prompt) }
    var kind by remember { mutableStateOf(r.kind) }
    var spec by remember { mutableStateOf(r.spec) }
    val valid = name.isNotBlank() && prompt.isNotBlank() && when (kind) {
        "daily" -> Regex("^\\d{1,2}:\\d{2}$").matches(spec.trim()); "interval" -> (spec.trim().toIntOrNull() ?: 0) >= 15
        "at" -> (parseWhen(spec) ?: 0) > System.currentTimeMillis(); else -> spec.isNotBlank()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(if (r.id == 0L) "New routine" else "Edit routine", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(prompt, { prompt = it }, label = { Text("What should your agent do?") }, minLines = 3, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
            Spacer(Modifier.height(16.dp))
            Text("WHEN", style = Eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            val kinds = listOf("daily" to "Daily", "at" to "Once", "interval" to "Repeat", "email" to "Email")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                kinds.forEachIndexed { i, (k, label) ->
                    ToggleButton(kind == k, { kind = k; spec = when (k) { "daily" -> "07:00"; "interval" -> "60"; "at" -> java.time.LocalDate.now().plusDays(1).toString() + "T09:00"; else -> "" } },
                        modifier = Modifier.weight(1f), shapes = when (i) { 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes(); kinds.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes(); else -> ButtonGroupDefaults.connectedMiddleButtonShapes() }) {
                        Text(label, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(spec, { spec = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                label = { Text(when (kind) { "daily" -> "Time (HH:mm)"; "at" -> "Date and time (2026-10-06T07:30)"; "interval" -> "Every how many minutes (15 or more)"; else -> "Gmail search, e.g. from:bank@capitec.co.za" }) },
                supportingText = if (kind == "email") ({ Text("Needs Gmail connected in Connections.") }) else null)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { onSave(r.copy(name = name.trim(), prompt = prompt.trim(), kind = kind, spec = spec.trim(), cursor = if (kind != r.kind || spec != r.spec) "" else r.cursor, enabled = true)) },
                enabled = valid, modifier = Modifier.fillMaxWidth().height(56.dp), shapes = ButtonDefaults.shapes()) { Text("Save routine") }
        }
    }
}

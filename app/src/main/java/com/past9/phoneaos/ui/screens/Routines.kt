package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
    "weekly" -> Routines.weeklyLabel(t.spec)
    "nightly" -> "Every night while charging, from ${t.spec}"
    "watch" -> (t.spec.toIntOrNull() ?: 1440).let { m -> "Checks " + when { m >= 1440 && m % 1440 == 0 -> if (m == 1440) "daily" else "every ${m / 1440} days"; m % 60 == 0 -> "every ${m / 60} h"; else -> "every $m min" } + ", wakes your agent only when it fires" }
    "interval" -> (t.spec.toIntOrNull() ?: 60).let { if (it % 60 == 0) "Every ${it / 60} h" else "Every $it min" }
    "email" -> "When a new email matches: ${t.spec}"
    "notification" -> t.spec.split("|").let { "When ${it[0]} notifies" + (it.getOrNull(1)?.takeIf { w -> w.isNotBlank() }?.let { w -> " about \"$w\"" } ?: "") }
    else -> t.kind
}

fun inLabel(ms: Long): String = when {
    ms < 60_000 -> "in under a minute"; ms < 3_600_000 -> "in ${ms / 60_000} min"
    ms < 86_400_000 -> "in ${ms / 3_600_000} h ${(ms % 3_600_000) / 60_000} min"; else -> "in ${ms / 86_400_000} d"
}

private fun kindIcon(kind: String): ImageVector = when (kind) { "at" -> Icons.Rounded.Event; "daily" -> Icons.Rounded.WbSunny; "weekly" -> Icons.Rounded.EventRepeat; "nightly" -> Icons.Rounded.Bedtime; "watch" -> Icons.Rounded.TrackChanges; "interval" -> Icons.Rounded.Autorenew; "notification" -> Icons.Rounded.NotificationsActive; else -> Icons.Rounded.Mail }

private val routineShapes = listOf(MaterialShapes.Cookie6Sided, MaterialShapes.Clover4Leaf, MaterialShapes.Sunny, MaterialShapes.Pill, MaterialShapes.Cookie9Sided)

@Composable
fun RoutinesScreen(routines: List<TriggerRow>, actions: RoutineActions, now: Long = System.currentTimeMillis(), bottomPadding: androidx.compose.ui.unit.Dp = 120.dp) {
    var editing by remember { mutableStateOf<TriggerRow?>(null) }
    val zNow = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
    val clock = setOf("daily", "weekly", "at", "interval", "nightly")
    val running = routines.filter { it.enabled }
    val next = running.filter { it.kind in clock }.mapNotNull { r -> Routines.nextRunIn(r, zNow)?.let { r to it } }.minByOrNull { it.second }
    SubScreen("Routines", if (running.isEmpty()) "Things your agent does on its own" else "${running.size} running on their own", actions.onBack,
        fab = { ExtendedFloatingActionButton(onClick = { editing = TriggerRow(name = "", kind = "daily", spec = "07:00", prompt = "") }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("New routine") }, modifier = Modifier.padding(bottom = (bottomPadding - 40.dp).coerceAtLeast(0.dp))) }) { pad ->
        if (routines.isEmpty()) { Box(Modifier.padding(pad)) { EmptyState("No routines yet", "Say \"every morning at 7, brief me on my day\" or \"when my bank emails, tell me what changed\". Or add one here.", shape = MaterialShapes.Sunny) }; return@SubScreen }
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPadding + 72.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            next?.let { (r, ms) -> item(key = "next") { NextUp(r, ms, zNow, { actions.onRunNow(r) }) { editing = r } } }
            val groups = listOf(
                Triple("On a clock", "Runs at the times you set", routines.filter { it.enabled && it.kind in clock && it.id != next?.first?.id }),
                Triple("Watching", "Waits for an email or a notification", routines.filter { it.enabled && it.kind !in clock }),
                Triple("Paused", "Switched off, kept for later", routines.filter { !it.enabled }),
            )
            groups.forEach { (title, line, list) ->
                if (list.isNotEmpty()) {
                    item(key = "h-$title") { com.past9.phoneaos.ui.SectionHeader(title, line, Modifier.padding(start = 4.dp, top = 14.dp, bottom = 2.dp)) }
                    items(list, key = { it.id }) { r -> RoutineRow(r, actions, zNow) { editing = r } }
                }
            }
        }
    }
    editing?.let { r -> RoutineEditor(r, { editing = null }, { actions.onSave(it); editing = null },
        onRunNow = if (r.id != 0L) ({ actions.onRunNow(r); editing = null }) else null,
        onDelete = if (r.id != 0L) ({ actions.onDelete(r); editing = null }) else null) }
}

private fun clockLabel(ms: Long, now: java.time.ZonedDateTime): Pair<String, String> {
    val at = now.plusNanos(ms * 1_000_000)
    val time = at.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    val day = when (at.toLocalDate()) { now.toLocalDate() -> "Today"; now.toLocalDate().plusDays(1) -> "Tomorrow"; else -> at.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")) }
    return time to "$day, ${inLabel(ms)}"
}

/** The one loud thing on the page: what your agent does next, and when. */
@Composable
private fun NextUp(r: TriggerRow, ms: Long, now: java.time.ZonedDateTime, onRun: () -> Unit, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val (time, day) = clockLabel(ms, now)
    Surface(onClick = onOpen, shape = MaterialTheme.shapes.extraLarge, color = cs.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NEXT UP", style = Eyebrow, color = cs.onPrimaryContainer.copy(alpha = 0.8f), modifier = Modifier.weight(1f))
                Icon(kindIcon(r.kind), null, tint = cs.onPrimaryContainer, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(time, style = MaterialTheme.typography.displayMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = cs.onPrimaryContainer)
            Text(day, style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer.copy(alpha = 0.8f))
            Spacer(Modifier.height(16.dp))
            Text(r.name, style = MaterialTheme.typography.titleLarge, color = cs.onPrimaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(r.prompt, style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRun, shapes = ButtonDefaults.shapes(), colors = ButtonDefaults.buttonColors(containerColor = cs.primary, contentColor = cs.onPrimary)) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Run it now")
            }
        }
    }
}

@Composable
private fun RoutineRow(r: TriggerRow, actions: RoutineActions, now: java.time.ZonedDateTime, onEdit: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val next = if (r.enabled) Routines.nextRunIn(r, now) else null
    val shape = remember(r.id) { val p = routineShapes[(r.id % routineShapes.size).toInt()].normalized(); com.past9.phoneaos.ui.MorphShape(androidx.graphics.shapes.Morph(p, p), 0f) }
    val status = when {
        !r.enabled -> "Paused"
        r.kind == "email" -> "Checks every 15 min"
        r.kind == "notification" -> "Listening"
        r.kind == "watch" -> "On watch"
        next != null -> clockLabel(next, now).let { (t, d) -> "${d.substringBefore(",")} at $t" }
        else -> "Finished"
    }
    Surface(onClick = onEdit, shape = MaterialTheme.shapes.large, color = if (r.enabled) cs.surfaceContainerLow else cs.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(shape).background(if (r.enabled) cs.secondaryContainer else cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                    Icon(kindIcon(r.kind), null, tint = if (r.enabled) cs.onSecondaryContainer else cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = MaterialTheme.typography.titleMedium, color = if (r.enabled) cs.onSurface else cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(whenLabel(r), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Switch(r.enabled, { actions.onToggle(r) })
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.padding(start = 62.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(status, style = MaterialTheme.typography.labelMedium, color = if (r.enabled) cs.primary else cs.onSurfaceVariant)
                if (r.lastRunAt != null) {
                    Text("  ·  ", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    Text("Last: " + r.lastResult.ifBlank { relativeTime(r.lastRunAt) }.lineSequence().first(), style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
            }
        }
    }
}

private data class Template(val name: String, val kind: String, val spec: String, val prompt: String, val icon: ImageVector)
private val templates = listOf(
    Template("Morning brief", "daily", "07:00", "Brief me on today: my calendar, what's due, and news that matters to me. Keep it short.", Icons.Rounded.WbSunny),
    Template("Sunday plan", "weekly", "SUN 18:00", "Look at my open tasks and the week ahead and give me a simple plan for the week.", Icons.Rounded.EventNote),
    Template("Bank watch", "email", "from:alerts@mybank.co.za", "Tell me what changed and flag anything over R 1,000.", Icons.Rounded.AccountBalance),
    Template("Price drop", "interval", "180", "Check the price of the thing I'm watching and tell me if it drops below my target.", Icons.Rounded.TrendingDown),
)

@Composable
internal fun RoutineEditor(r: TriggerRow, onDismiss: () -> Unit, onSave: (TriggerRow) -> Unit, onRunNow: (() -> Unit)?, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(r.name) }
    var prompt by remember { mutableStateOf(r.prompt) }
    var kind by remember { mutableStateOf(r.kind) }
    var spec by remember { mutableStateOf(r.spec) }
    val valid = name.isNotBlank() && prompt.isNotBlank() && when (kind) {
        "daily", "nightly" -> Regex("^\\d{1,2}:\\d{2}$").matches(spec.trim()); "weekly" -> Routines.parseWeekly(spec) != null; "interval", "watch" -> (spec.trim().toIntOrNull() ?: 0) >= 15
        "at" -> (parseWhen(spec) ?: 0) > System.currentTimeMillis(); else -> spec.isNotBlank()
    }
    com.past9.phoneaos.ui.AppSheet(kindIcon(kind), if (r.id == 0L) "Runs on its own" else whenLabel(r.copy(kind = kind, spec = spec)), if (r.id == 0L) "New routine" else "Edit routine", onDismiss,
        primary = if (r.id == 0L) "Start this routine" else "Save routine", primaryEnabled = valid, shape = MaterialShapes.Sunny,
        onPrimary = { onSave(r.copy(name = name.trim(), prompt = prompt.trim(), kind = kind, spec = spec.trim(), cursor = if (kind != r.kind || spec != r.spec) "" else r.cursor, enabled = true)) },
        secondary = if (onRunNow == null && onDelete == null) null else ({
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                if (onRunNow != null) TextButton(onClick = onRunNow, modifier = Modifier.weight(1f).height(52.dp)) { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Run now") }
                if (onDelete != null) TextButton(onClick = onDelete, modifier = Modifier.weight(1f).height(52.dp)) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        })) {
        if (r.id == 0L) {
            Text("START FROM", style = Eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                templates.forEach { t ->
                    AssistChip(onClick = { name = t.name; prompt = t.prompt; kind = t.kind; spec = t.spec }, label = { Text(t.name) },
                        leadingIcon = { Icon(t.icon, null, Modifier.size(18.dp)) }, shape = RoundedCornerShape(50), modifier = Modifier.height(40.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        com.past9.phoneaos.ui.SheetField(name, { name = it }, "Name", "Morning brief", big = true, singleLine = true)
        Spacer(Modifier.height(16.dp))
        com.past9.phoneaos.ui.SheetField(prompt, { prompt = it }, "What your agent does", "Brief me on my calendar and anything due today", minLines = 3)
        Spacer(Modifier.height(20.dp))
        Text("WHEN", style = Eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        val kinds = buildList {
            if (r.kind == "nightly") add(Triple("nightly", "Overnight", "While the phone charges"))
            if (r.kind == "watch") add(Triple("watch", "Watcher", "A script checks, no agent needed"))
            add(Triple("daily", "Every day", "At a time you pick")); add(Triple("weekly", "Weekly", "On the days you pick"))
            add(Triple("at", "Once", "On one date and time"))
            add(Triple("interval", "Repeat", "Every few minutes or hours")); add(Triple("email", "On email", "When a matching email lands"))
        }
        kinds.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (k, t, l) ->
                    com.past9.phoneaos.ui.ChoiceCard(kindIcon(k), t, l, kind == k, {
                        if (kind != k) { kind = k; spec = when (k) { "daily" -> "07:00"; "weekly" -> "SUN 18:00"; "nightly" -> "02:00"; "interval" -> "60"; "at" -> java.time.LocalDate.now().plusDays(1).toString() + "T09:00"; else -> "" } }
                    }, Modifier.weight(1f))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        when (kind) {
            "daily", "nightly" -> com.past9.phoneaos.ui.ChoiceChips(listOf("06:00", "07:00", "08:00", "12:00", "18:00", "21:00").let { if (spec in it) it else it + spec }.map { Triple(it, it, null) }, spec, { spec = it }, label = "Time")
            "weekly" -> {
                val parsed = Routines.parseWeekly(spec)
                val days = parsed?.first ?: setOf(7)
                val time = parsed?.let { "%02d:%02d".format(it.second, it.third) } ?: "18:00"
                val codes = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
                fun put(d: Set<Int>, t: String) { spec = d.sorted().joinToString(",") { codes[it - 1] } + " " + t }
                Text("DAYS", style = Eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, l ->
                        val on = (i + 1) in days
                        Surface(onClick = { val n = if (on) days - (i + 1) else days + (i + 1); if (n.isNotEmpty()) put(n, time) }, shape = androidx.compose.foundation.shape.CircleShape,
                            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.weight(1f).aspectRatio(1f)) {
                            Box(contentAlignment = Alignment.Center) { Text(l, style = MaterialTheme.typography.titleSmall, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                com.past9.phoneaos.ui.ChoiceChips(listOf("07:00", "08:00", "12:00", "17:00", "18:00", "20:00").let { if (time in it) it else it + time }.map { Triple(it, it, null) }, time, { put(days, it) }, label = "Time")
            }
            "interval", "watch" -> com.past9.phoneaos.ui.ChoiceChips(listOf("15" to "15 min", "60" to "Every hour", "180" to "3 hours", "360" to "6 hours", "720" to "12 hours", "1440" to "Daily").let { l -> if (l.any { it.first == spec }) l else l + (spec to "$spec min") }.map { Triple(it.first, it.second, null) }, spec, { spec = it }, label = "How often")
            "at" -> com.past9.phoneaos.ui.SheetField(spec, { spec = it }, "Date and time", "2026-10-06T07:30", singleLine = true)
            else -> {
                com.past9.phoneaos.ui.SheetField(spec, { spec = it }, "Gmail search", "from:bank@mybank.co.za", singleLine = true)
                Text("Needs Gmail connected in Connections.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
            }
        }
    }
}

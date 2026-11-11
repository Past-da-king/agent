package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.R
import com.past9.phoneaos.people.GiftIdea
import com.past9.phoneaos.people.ImportantDate
import com.past9.phoneaos.people.Person
import com.past9.phoneaos.people.PersonLog
import com.past9.phoneaos.people.addDateLabel
import com.past9.phoneaos.people.nextDate
import com.past9.phoneaos.people.parsePersonDate
import com.past9.phoneaos.people.spokeLine
import com.past9.phoneaos.ui.AppCard
import com.past9.phoneaos.ui.AppSheet
import com.past9.phoneaos.ui.ChoiceChips
import com.past9.phoneaos.ui.ConfirmSheet
import com.past9.phoneaos.ui.SectionHeader
import com.past9.phoneaos.ui.SheetField
import com.past9.phoneaos.ui.StatusPill
import com.past9.phoneaos.ui.SubScreen
import com.past9.phoneaos.ui.theme.Eyebrow
import java.time.LocalDate
import java.time.ZoneId

data class PersonActions(
    val onBack: () -> Unit = {},
    /** kind (call, message, note), what was said */
    val onLog: (String, String) -> Unit = { _, _ -> },
    /** label, comes round every year, date as typed, days before */
    val onAddDate: (String, Boolean, String, Int) -> Unit = { _, _, _, _ -> },
    val onAddGift: (String, String) -> Unit = { _, _ -> },
    val onRemoveDate: (String) -> Unit = {},
    val onRemoveGift: (String) -> Unit = {},
    val onDelete: () -> Unit = {},
    /** Ask the agent about this person. */
    val onAsk: (String) -> Unit = {},
)

/** One person: log a call or a note in one tap, their dates, the present ideas, and everything you have logged. */
@Composable
fun PersonScreen(person: Person, logs: List<PersonLog>, actions: PersonActions, bottomPadding: Dp = 120.dp, now: Long = System.currentTimeMillis()) {
    val cs = MaterialTheme.colorScheme
    var logging by remember { mutableStateOf<String?>(null) }
    var addingDate by remember { mutableStateOf(false) }
    var addingGift by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val today = java.time.Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
    SubScreen(person.name, spokeLine(person.lastLogAt, now), actions.onBack, actions = {
        IconButton(onClick = { actions.onAsk("Tell me what I should remember about ${person.name}.") }) { Icon(Icons.Rounded.AutoAwesome, stringResource(R.string.person_ask)) }
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = com.past9.phoneaos.ui.sidePad(16.dp), end = com.past9.phoneaos.ui.sidePad(16.dp), top = 8.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(52.dp).clip(CircleShape).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
                            Text(person.name.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = cs.onSecondaryContainer)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            if (person.note.isNotBlank()) Text(person.note, style = MaterialTheme.typography.bodyMedium)
                            else Text(stringResource(R.string.person_no_note), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LogButton(stringResource(R.string.person_call), Icons.Rounded.Call, Modifier.weight(1f)) { logging = "call" }
                        LogButton(stringResource(R.string.person_message), Icons.AutoMirrored.Rounded.Chat, Modifier.weight(1f)) { logging = "message" }
                        LogButton(stringResource(R.string.person_note), Icons.Rounded.EditNote, Modifier.weight(1f)) { logging = "note" }
                    }
                }
            }
            item { SectionRow(stringResource(R.string.person_dates), stringResource(R.string.person_add_date), if (person.dates.isEmpty()) stringResource(R.string.person_dates_empty) else null) { addingDate = true } }
            if (person.dates.isNotEmpty()) item {
                AppCard(padding = PaddingValues(vertical = 6.dp)) {
                    person.dates.sortedBy { it.occurrenceOnOrAfter(today.toLocalDate()) }.forEach { d ->
                        val day = d.occurrenceOnOrAfter(today.toLocalDate())
                        val inDays = java.time.temporal.ChronoUnit.DAYS.between(today.toLocalDate(), day)
                        DateLine(d, day, inDays, onRemove = { actions.onRemoveDate(d.key) })
                    }
                }
            }
            item { SectionRow(stringResource(R.string.person_gifts), stringResource(R.string.person_add_gift), if (person.gifts.isEmpty()) stringResource(R.string.person_gifts_empty) else null) { addingGift = true } }
            if (person.gifts.isNotEmpty()) item {
                AppCard(padding = PaddingValues(vertical = 6.dp)) {
                    person.gifts.forEach { g -> GiftLine(g, onRemove = { actions.onRemoveGift(g.key) }) }
                }
            }
            item {
                SectionHeader(stringResource(R.string.person_timeline), if (logs.isEmpty()) stringResource(R.string.person_timeline_empty) else null, Modifier.padding(start = 4.dp, top = 6.dp))
            }
            if (logs.isNotEmpty()) item {
                AppCard(padding = PaddingValues(vertical = 6.dp)) {
                    logs.forEach { l -> LogLine(l) }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { removing = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.person_remove), color = cs.error) }
            }
        }
    }
    logging?.let { kind -> LogSheet(kind, person.name, onDismiss = { logging = null }) { text -> logging = null; actions.onLog(kind, text) } }
    if (addingDate) AddDateSheet(person.name, onDismiss = { addingDate = false }) { label, yearly, date, days -> addingDate = false; actions.onAddDate(label, yearly, date, days) }
    if (addingGift) AddGiftSheet(person.name, onDismiss = { addingGift = false }) { idea, occasion -> addingGift = false; actions.onAddGift(idea, occasion) }
    if (removing) ConfirmSheet(Icons.Rounded.PersonRemove, stringResource(R.string.people_title), stringResource(R.string.person_remove_title, person.name),
        stringResource(R.string.person_remove_line), stringResource(R.string.person_remove), onConfirm = { removing = false; actions.onDelete() },
        onDismiss = { removing = false }, cancel = stringResource(R.string.person_cancel), destructive = true)
}

/** A section line with its own add button, level with the eyebrow rather than floating above it. */
@Composable
private fun SectionRow(eyebrow: String, addLabel: String, line: String?, onAdd: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(eyebrow.uppercase(), style = Eyebrow, color = cs.primary)
            if (line != null) Text(line, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        IconButton(onClick = onAdd) { Icon(Icons.Rounded.Add, addLabel, Modifier.size(20.dp), tint = cs.primary) }
    }
}

@Composable
private fun LogButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.height(56.dp), shapes = ButtonDefaults.shapes(), contentPadding = PaddingValues(horizontal = 8.dp)) {
        Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DateLine(d: ImportantDate, day: LocalDate, inDays: Long, onRemove: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (d.label.contains("birth", true)) Icons.Rounded.Cake else Icons.Rounded.Event, null, Modifier.size(20.dp), tint = cs.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(d.label, style = MaterialTheme.typography.titleSmall)
            Text("${d.short()} · ${stringResource(R.string.person_reminded_before, d.daysBefore)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
        }
        StatusPill(if (inDays == 0L) stringResource(R.string.person_today) else stringResource(R.string.person_in_days, inDays),
            if (inDays <= 7) cs.primaryContainer else cs.surfaceContainerHighest,
            if (inDays <= 7) cs.onPrimaryContainer else cs.onSurfaceVariant)
        IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, stringResource(R.string.person_remove_date), Modifier.size(18.dp), tint = cs.onSurfaceVariant) }
    }
}

@Composable
private fun GiftLine(g: GiftIdea, onRemove: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.CardGiftcard, null, Modifier.size(20.dp), tint = cs.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(g.text, style = MaterialTheme.typography.bodyLarge)
            if (g.occasion.isNotBlank()) Text(g.occasion, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, stringResource(R.string.person_remove_gift), Modifier.size(18.dp), tint = cs.onSurfaceVariant) }
    }
}

@Composable
private fun LogLine(l: PersonLog) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(when (l.kind) { "call" -> Icons.Rounded.Call; "message" -> Icons.AutoMirrored.Rounded.Chat; "met" -> Icons.Rounded.Groups; "note" -> Icons.Rounded.EditNote; else -> Icons.Rounded.Schedule },
            null, Modifier.size(18.dp).padding(top = 2.dp), tint = cs.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${PersonLog.label(l.kind)} · ${addDateLabel(l.at)}", style = Eyebrow, color = cs.onSurfaceVariant)
            if (l.text.isNotBlank()) Text(l.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Log a call or a message: a few words now, or just the fact of it. */
@Composable
internal fun LogSheet(kind: String, name: String, onDismiss: () -> Unit, onLog: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val title = when (kind) { "call" -> stringResource(R.string.person_log_call, name); "message" -> stringResource(R.string.person_log_message, name); else -> stringResource(R.string.person_log_note, name) }
    AppSheet(if (kind == "call") Icons.Rounded.Call else Icons.Rounded.EditNote, name, title, onDismiss,
        primary = stringResource(R.string.person_log_confirm), primaryEnabled = true, onPrimary = { onLog(text.trim()) },
        shape = MaterialShapes.Cookie7Sided) {
        SheetField(text, { text = it }, stringResource(R.string.person_log_label), stringResource(R.string.person_log_hint), minLines = 2)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.person_log_line), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A birthday, an anniversary or any date, with how many days ahead to be reminded. */
@Composable
internal fun AddDateSheet(name: String, onDismiss: () -> Unit, onAdd: (String, Boolean, String, Int) -> Unit) {
    var kind by remember { mutableStateOf("birthday") }
    var custom by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var days by remember { mutableIntStateOf(3) }
    val parsed = parsePersonDate(date)
    val label = when (kind) {
        "birthday" -> stringResource(R.string.person_date_birthday)
        "anniversary" -> stringResource(R.string.person_date_anniversary)
        else -> custom.ifBlank { stringResource(R.string.person_date_other) }
    }
    AppSheet(Icons.Rounded.Cake, name, stringResource(R.string.person_add_date), onDismiss,
        primary = stringResource(R.string.person_date_confirm), primaryEnabled = parsed != null,
        onPrimary = { onAdd(label, kind != "other", date.trim(), days) }, shape = MaterialShapes.Cookie7Sided) {
        ChoiceChips(listOf(
            Triple("birthday", stringResource(R.string.person_date_birthday), Icons.Rounded.Cake),
            Triple("anniversary", stringResource(R.string.person_date_anniversary), Icons.Rounded.Favorite),
            Triple("other", stringResource(R.string.person_date_other), Icons.Rounded.Event),
        ), kind, { kind = it }, label = stringResource(R.string.person_date_what))
        if (kind == "other") {
            Spacer(Modifier.height(12.dp))
            SheetField(custom, { custom = it }, stringResource(R.string.person_date_name), stringResource(R.string.person_date_name_hint), singleLine = true)
        }
        Spacer(Modifier.height(18.dp))
        SheetField(date, { date = it }, stringResource(R.string.person_date_when), stringResource(R.string.person_date_hint), singleLine = true)
        if (date.isNotBlank() && parsed == null) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.person_date_bad), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(18.dp))
        ChoiceChips(listOf(
            Triple("0", stringResource(R.string.person_days_0), null),
            Triple("1", stringResource(R.string.person_days_1), null),
            Triple("3", stringResource(R.string.person_days_3), null),
            Triple("7", stringResource(R.string.person_days_7), null),
            Triple("14", stringResource(R.string.person_days_14), null),
        ), days.toString(), { days = it.toIntOrNull() ?: 3 }, label = stringResource(R.string.person_days_before))
    }
}

@Composable
internal fun AddGiftSheet(name: String, onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var idea by remember { mutableStateOf("") }
    var occasion by remember { mutableStateOf("") }
    AppSheet(Icons.Rounded.CardGiftcard, name, stringResource(R.string.person_add_gift), onDismiss,
        primary = stringResource(R.string.person_gift_confirm), primaryEnabled = idea.isNotBlank(), onPrimary = { onAdd(idea.trim(), occasion.trim()) },
        shape = MaterialShapes.Cookie7Sided) {
        SheetField(idea, { idea = it }, stringResource(R.string.person_gift_label), stringResource(R.string.person_gift_hint), minLines = 2)
        Spacer(Modifier.height(18.dp))
        SheetField(occasion, { occasion = it }, stringResource(R.string.person_gift_occasion), stringResource(R.string.person_gift_occasion_hint), singleLine = true)
    }
}

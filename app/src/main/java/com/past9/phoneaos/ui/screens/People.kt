package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.R
import com.past9.phoneaos.people.Person
import com.past9.phoneaos.people.QUIET_DAYS
import com.past9.phoneaos.people.daysQuiet
import com.past9.phoneaos.people.nextDate
import com.past9.phoneaos.people.spokeLine
import com.past9.phoneaos.ui.AppCard
import com.past9.phoneaos.ui.AppSheet
import com.past9.phoneaos.ui.EmptyState
import com.past9.phoneaos.ui.SectionHeader
import com.past9.phoneaos.ui.SheetField
import com.past9.phoneaos.ui.StatusPill
import com.past9.phoneaos.ui.SubScreen
import com.past9.phoneaos.ui.theme.Eyebrow

data class PeopleActions(
    val onBack: () -> Unit = {},
    val onOpen: (Person) -> Unit = {},
    /** name, note */
    val onAdd: (String, String) -> Unit = { _, _ -> },
    /** Import the phone's contacts. The caller asks for the permission at this moment, never before. */
    val onImport: () -> Unit = {},
    val onAsk: (String) -> Unit = {},
)

/** Everyone on the list, the ones going quiet first. */
@Composable
fun PeopleScreen(people: List<Person>, actions: PeopleActions, bottomPadding: Dp = 120.dp, now: Long = System.currentTimeMillis()) {
    var adding by remember { mutableStateOf(false) }
    val quiet = people.filter { daysQuiet(it.lastLogAt, now) >= QUIET_DAYS }.sortedByDescending { daysQuiet(it.lastLogAt, now) }
    val rest = people.filter { it !in quiet }.sortedBy { it.name.lowercase() }
    val subtitle = when {
        people.isEmpty() -> stringResource(R.string.people_sub_empty)
        quiet.isEmpty() -> stringResource(R.string.people_sub_all, people.size)
        else -> stringResource(R.string.people_sub_quiet, people.size, quiet.size)
    }
    SubScreen(stringResource(R.string.people_title), subtitle, actions.onBack,
        actions = {
            IconButton(onClick = actions.onImport) { Icon(Icons.Rounded.Contacts, stringResource(R.string.people_import)) }
        },
        fab = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Rounded.PersonAdd, null) },
                text = { Text(stringResource(R.string.people_add)) }, modifier = Modifier.padding(bottom = bottomPadding - 40.dp))
        }) { pad ->
        if (people.isEmpty()) {
            Column(Modifier.padding(pad).fillMaxWidth()) {
                EmptyState(stringResource(R.string.people_empty_title), stringResource(R.string.people_empty_line))
                TextButton(onClick = actions.onImport, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.people_import)) }
            }
            return@SubScreen
        }
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = com.past9.phoneaos.ui.sidePad(16.dp), end = com.past9.phoneaos.ui.sidePad(16.dp), top = 8.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (quiet.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.people_quiet_section), stringResource(R.string.people_quiet_sub), Modifier.padding(start = 4.dp)) }
                item { AppCard(padding = PaddingValues(vertical = 6.dp)) { quiet.forEach { PersonLine(it, now, actions) } } }
            }
            if (rest.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.people_all_section), stringResource(R.string.people_all_sub, rest.size), Modifier.padding(start = 4.dp, top = 6.dp)) }
                item { AppCard(padding = PaddingValues(vertical = 6.dp)) { rest.forEach { PersonLine(it, now, actions) } } }
            }
        }
    }
    if (adding) AddPersonSheet(onDismiss = { adding = false }, onImport = { adding = false; actions.onImport() }) { name, note ->
        adding = false; actions.onAdd(name, note)
    }
}

@Composable
fun PersonLine(p: Person, now: Long, actions: PeopleActions) {
    val cs = MaterialTheme.colorScheme
    val quiet = daysQuiet(p.lastLogAt, now) >= QUIET_DAYS
    Surface(onClick = { actions.onOpen(p) }, color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
                Text(p.name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = cs.onSecondaryContainer)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val next = nextDate(p, java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()))
                // A quiet row keeps the pill rather than a date that would push it off the line; their page has the date.
                val line = buildList {
                    add(spokeLine(p.lastLogAt, now))
                    if (!quiet && next != null && next.inDays <= 60) add(if (next.inDays <= 14) "${next.date.label} in ${next.inDays} days" else "${next.date.label} ${next.date.short()}")
                }.joinToString(" · ")
                Text(line, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (quiet) StatusPill(stringResource(R.string.people_quiet_pill), cs.tertiaryContainer, cs.onTertiaryContainer)
        }
    }
}

@Composable
internal fun AddPersonSheet(onDismiss: () -> Unit, onImport: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AppSheet(Icons.Rounded.PersonAdd, stringResource(R.string.people_title), stringResource(R.string.people_add), onDismiss,
        primary = stringResource(R.string.people_add_confirm), primaryEnabled = name.isNotBlank(), onPrimary = { onAdd(name.trim(), note.trim()) },
        shape = MaterialShapes.Cookie7Sided,
        secondary = {
            TextButton(onClick = onImport, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Rounded.Contacts, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.people_import))
            }
        }) {
        SheetField(name, { name = it }, stringResource(R.string.people_name_label), stringResource(R.string.people_name_hint), singleLine = true,
            keyboard = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words))
        Spacer(Modifier.height(20.dp))
        SheetField(note, { note = it }, stringResource(R.string.people_note_label), stringResource(R.string.people_note_hint), big = false, minLines = 2)
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.people_import_line), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

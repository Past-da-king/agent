package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.data.MemoryRow
import com.past9.phoneaos.tools.Recall
import com.past9.phoneaos.ui.AppCard
import com.past9.phoneaos.ui.EmptyState
import com.past9.phoneaos.ui.SubScreen
import com.past9.phoneaos.ui.relativeTime
import com.past9.phoneaos.ui.theme.Eyebrow

data class MemoryActions(
    val onBack: () -> Unit = {},
    val onSave: (MemoryRow) -> Unit = {},
    val onDelete: (MemoryRow) -> Unit = {},
)

@Composable
fun MemoryScreen(memories: List<MemoryRow>, actions: MemoryActions) {
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<MemoryRow?>(null) }
    val kinds = remember(memories) { memories.map { it.kind }.distinct().sorted() }
    val shown = remember(memories, query, kind) {
        val k = memories.filter { kind == null || it.kind == kind }
        if (query.isBlank()) k else Recall.rank(query, k, 200)
    }
    SubScreen("Memory", "${memories.size} things your agent knows about you", actions.onBack,
        fab = { ExtendedFloatingActionButton(onClick = { editing = MemoryRow(id = 0, title = "", body = "", source = "user") }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Add") }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Your agent saves what matters as you talk, and reads it before it answers. Everything is here: fix it, pin it, or delete it.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
            }
            item {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search memory") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge)
            }
            if (kinds.size > 1) item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(kind == null, { kind = null }, label = { Text("All") }) }
                    items(kinds) { k -> FilterChip(kind == k, { kind = if (kind == k) null else k }, label = { Text(k.replaceFirstChar { it.uppercase() }) }) }
                }
            }
            if (memories.isEmpty()) item { EmptyState("Nothing remembered yet", "Tell your agent about yourself, the people in your life and how you like things done. It keeps what matters here.") }
            else if (shown.isEmpty()) item { EmptyState("No match", "Nothing in memory matches \"$query\".") }
            items(shown, key = { it.id }) { m -> MemoryCard(m) { editing = m } }
        }
    }
    editing?.let { m -> MemoryEditor(m, { editing = null }, { actions.onSave(it); editing = null }, { actions.onDelete(m); editing = null }) }
}

@Composable
private fun MemoryCard(m: MemoryRow, onClick: () -> Unit) {
    AppCard(onClick = onClick, padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.kind.uppercase() + " · " + (if (m.source == "user") "ADDED BY YOU" else "SAVED BY YOUR AGENT") + " · " + relativeTime(m.updatedAt).uppercase(),
                style = Eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (m.pinned) Icon(Icons.Rounded.PushPin, "Pinned", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(6.dp))
        Text(m.title, style = MaterialTheme.typography.titleMedium)
        Text(m.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MemoryEditor(m: MemoryRow, onDismiss: () -> Unit, onSave: (MemoryRow) -> Unit, onDelete: () -> Unit) {
    var title by remember { mutableStateOf(m.title) }
    var body by remember { mutableStateOf(m.body) }
    var pinned by remember { mutableStateOf(m.pinned) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(if (m.id == 0L) "New memory" else "Memory", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(body, { body = it }, label = { Text("What to remember") }, minLines = 3, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Pin it", style = MaterialTheme.typography.titleSmall)
                    Text("Always in your agent's mind, not just when relevant", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(pinned, { pinned = it })
            }
            Spacer(Modifier.height(20.dp))
            Button(onClick = { onSave(m.copy(title = title.trim(), body = body.trim(), pinned = pinned, updatedAt = System.currentTimeMillis())) }, enabled = title.isNotBlank() && body.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(56.dp), shapes = ButtonDefaults.shapes()) { Text("Save") }
            if (m.id != 0L) TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Forget this", color = MaterialTheme.colorScheme.error) }
        }
    }
}

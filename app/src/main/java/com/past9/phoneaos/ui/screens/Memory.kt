package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.data.MemoryRow
import com.past9.phoneaos.tools.Recall
import com.past9.phoneaos.tools.linksIn
import com.past9.phoneaos.ui.EmptyState
import com.past9.phoneaos.ui.Markdown
import com.past9.phoneaos.ui.SectionHeader
import com.past9.phoneaos.ui.SubScreen
import com.past9.phoneaos.ui.relativeTime
import com.past9.phoneaos.ui.theme.Eyebrow

data class MemoryActions(
    val onBack: () -> Unit = {},
    val onOpen: (MemoryRow) -> Unit = {},
    val onSave: (MemoryRow) -> Unit = {},
    val onDelete: (MemoryRow) -> Unit = {},
)

fun pageIcon(kind: String): ImageVector = when (kind) {
    "person" -> Icons.Rounded.Person; "company" -> Icons.Rounded.Business; "place" -> Icons.Rounded.Place; "project" -> Icons.Rounded.Flag
    "preference" -> Icons.Rounded.Favorite; "event" -> Icons.Rounded.Event; "how-to" -> Icons.Rounded.Lightbulb; else -> Icons.Rounded.Psychology
}

/** First readable line of a page, without markdown or link brackets. */
fun summaryOf(body: String) = body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }.orEmpty()
    .replace(Regex("""\[\[([^\]|]+)(?:\|([^\]]*))?]]""")) { it.groupValues[2].ifBlank { it.groupValues[1] } }.replace(Regex("[*_`]"), "").removePrefix("- ")

@Composable
fun MemoryScreen(memories: List<MemoryRow>, actions: MemoryActions, bottomPadding: androidx.compose.ui.unit.Dp = 120.dp) {
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val kinds = remember(memories) { memories.map { it.kind }.distinct().sorted() }
    val shown = remember(memories, query, kind) {
        val k = memories.filter { kind == null || it.kind == kind }
        if (query.isBlank()) k else Recall.rank(query, k, 200)
    }
    SubScreen("Memory", "${memories.size} pages your agent keeps about your world", actions.onBack,
        fab = { ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("New page") }, modifier = Modifier.padding(bottom = (bottomPadding - 40.dp).coerceAtLeast(0.dp))) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding + 60.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search people, places, anything") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge)
            }
            if (kinds.size > 1) item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(kind == null, { kind = null }, label = { Text("All") }, shape = RoundedCornerShape(50)) }
                    items(kinds) { k -> FilterChip(kind == k, { kind = if (kind == k) null else k }, label = { Text(k.replaceFirstChar { it.uppercase() }) }, leadingIcon = { Icon(pageIcon(k), null, Modifier.size(16.dp)) }, shape = RoundedCornerShape(50)) }
                }
            }
            if (memories.isEmpty()) item { EmptyState("Nothing remembered yet", "As you talk, your agent writes a page for each person, place and thing that matters, linked together like a wiki.") }
            else if (shown.isEmpty()) item { EmptyState("No match", "Nothing in memory matches \"$query\".") }
            val pinned = shown.filter { it.pinned }; val rest = shown.filter { !it.pinned }
            if (pinned.isNotEmpty() && query.isBlank()) {
                item { SectionHeader("Pinned", "Always on your agent's mind", Modifier.padding(start = 4.dp, top = 6.dp)) }
                items(pinned, key = { "p${it.id}" }) { m -> PageRow(m, memories) { actions.onOpen(m) } }
                item { SectionHeader("Everything else", "Newest first", Modifier.padding(start = 4.dp, top = 10.dp)) }
                items(rest, key = { it.id }) { m -> PageRow(m, memories) { actions.onOpen(m) } }
            } else items(shown, key = { it.id }) { m -> PageRow(m, memories) { actions.onOpen(m) } }
        }
    }
    if (adding) PageEditor(MemoryRow(id = 0, title = "", body = "", source = "user"), { adding = false }, onSave = { actions.onSave(it); adding = false })
}

@Composable
private fun PageRow(m: MemoryRow, all: List<MemoryRow>, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val links = remember(m.body) { linksIn(m.body).size }
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(MaterialTheme.shapes.medium).background(if (m.pinned) cs.primaryContainer else cs.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(pageIcon(m.kind), null, tint = if (m.pinned) cs.onPrimaryContainer else cs.onSecondaryContainer)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(m.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(summaryOf(m.body), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(m.kind.uppercase(), if (links > 0) "$links LINK${if (links > 1) "S" else ""}" else null, relativeTime(m.updatedAt).uppercase()).joinToString(" · "),
                    style = Eyebrow, color = cs.outline, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** A memory page, full screen, like a wiki article. Links open the linked page. */
@Composable
fun WikiPageScreen(page: MemoryRow?, all: List<MemoryRow>, onBack: () -> Unit, onOpenTitle: (String) -> Unit, onSave: (MemoryRow) -> Unit, onDelete: (MemoryRow) -> Unit, onAsk: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf(false) }
    if (page == null) { SubScreen("Not found", null, onBack) { pad -> Box(Modifier.padding(pad)) { EmptyState("This page is gone", "It may have been deleted.") } }; return }
    val known = remember(all) { all.map { it.title }.toSet() }
    val backlinks = remember(all, page) { all.filter { o -> o.id != page.id && linksIn(o.body).any { it.equals(page.title, true) } } }
    SubScreen(page.title, page.kind.replaceFirstChar { it.uppercase() } + " · updated " + relativeTime(page.updatedAt), onBack, actions = {
        IconButton(onClick = { onSave(page.copy(pinned = !page.pinned)) }) { Icon(if (page.pinned) Icons.Rounded.PushPin else Icons.Rounded.PushPin, if (page.pinned) "Unpin" else "Pin", tint = if (page.pinned) cs.primary else cs.onSurfaceVariant) }
        IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, "Edit page") }
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 48.dp)) {
            if (page.topics.isNotBlank()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                    page.topics.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { t ->
                        Surface(shape = RoundedCornerShape(50), color = cs.secondaryContainer) { Text("#$t", Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = cs.onSecondaryContainer) }
                    }
                }
            }
            Markdown(page.body, onWikiLink = onOpenTitle, knownPages = known)
            if (backlinks.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                SectionHeader("Linked from", "Pages that mention ${page.title}")
                Spacer(Modifier.height(8.dp))
                backlinks.forEach { b ->
                    Surface(onClick = { onOpenTitle(b.title) }, shape = MaterialTheme.shapes.large, color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(pageIcon(b.kind), null, tint = cs.primary); Spacer(Modifier.width(12.dp))
                            Column { Text(b.title, style = MaterialTheme.typography.titleSmall); Text(summaryOf(b.body), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = { onAsk("Tell me what you know about ${page.title}, and fix anything on its memory page that's out of date.") }, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Ask your agent about this")
            }
        }
    }
    if (editing) PageEditor(page, { editing = false }, { onSave(it); editing = false }, { onDelete(page); editing = false })
}

@Composable
internal fun PageEditor(m: MemoryRow, onDismiss: () -> Unit, onSave: (MemoryRow) -> Unit, onDelete: (() -> Unit)? = null) {
    var title by remember { mutableStateOf(m.title) }
    var body by remember { mutableStateOf(m.body) }
    var tags by remember { mutableStateOf(m.topics) }
    var kind by remember { mutableStateOf(m.kind) }
    com.past9.phoneaos.ui.AppSheet(pageIcon(kind), if (m.id == 0L) "Your agent's memory" else "Editing a page", if (m.id == 0L) "New page" else m.title.ifBlank { "Page" }, onDismiss,
        primary = if (m.id == 0L) "Save to memory" else "Save changes", primaryEnabled = title.isNotBlank() && body.isNotBlank(),
        onPrimary = { onSave(m.copy(title = title.trim(), body = body.trim(), topics = tags.trim(), kind = kind, updatedAt = System.currentTimeMillis())) },
        shape = MaterialShapes.Flower,
        secondary = onDelete?.let { del -> { TextButton(onClick = del, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Forget this page", color = MaterialTheme.colorScheme.error) } } }) {
        com.past9.phoneaos.ui.ChoiceChips(listOf("person" to "Person", "place" to "Place", "company" to "Company", "project" to "Project", "preference" to "Preference", "event" to "Event", "how-to" to "How-to", "fact" to "Fact")
            .map { (id, l) -> Triple(id, l, pageIcon(id)) }, kind, { kind = it }, label = "What is it")
        Spacer(Modifier.height(20.dp))
        com.past9.phoneaos.ui.SheetField(title, { title = it }, "Title", "Lerato, Durban trip, Gym...", big = true, singleLine = true)
        Spacer(Modifier.height(16.dp))
        com.past9.phoneaos.ui.SheetField(body, { body = it }, "The page", "What your agent should know. Link other pages with [[Name]].", minLines = 6)
        Spacer(Modifier.height(16.dp))
        com.past9.phoneaos.ui.SheetField(tags, { tags = it }, "Tags", "family, work, health", singleLine = true)
    }
}

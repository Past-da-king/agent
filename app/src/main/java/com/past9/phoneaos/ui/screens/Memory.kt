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
import android.content.Context
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.past9.phoneaos.R

data class MemoryActions(
    val onBack: () -> Unit = {},
    val onOpen: (MemoryRow) -> Unit = {},
    val onSave: (MemoryRow) -> Unit = {},
    val onDelete: (MemoryRow) -> Unit = {},
    /** Write every page into this zip (the file the user just named). Calls back with what happened. */
    val onExport: (android.net.Uri, (TransferNote) -> Unit) -> Unit = { _, _ -> },
    /** Read pages from the zip, JSON or markdown file the user picked. Calls back with what happened. */
    val onImport: (android.net.Uri, (TransferNote) -> Unit) -> Unit = { _, _ -> },
)

/** What an import or export did, shown as a card in the Memory list (never a pop-up). */
data class TransferNote(val headline: String, val sub: String, val rows: List<Pair<String, Int>> = emptyList(), val unsorted: Int = 0, val footnote: String? = null, val ok: Boolean = true) { companion object }

// Built outside composition (MainActivity, tests): with a Context the text follows the app language, without one it stays English.
private fun Context?.memStr(id: Int, en: String, vararg a: Any): String = this?.getString(id, *a) ?: en
private fun Context?.memQty(id: Int, n: Int, en: String, vararg a: Any): String = this?.resources?.getQuantityString(id, n, *a) ?: en

fun TransferNote.Companion.imported(s: com.past9.phoneaos.data.MemoryTransfer.Summary, ctx: Context? = null): TransferNote {
    val r = s.result
    if (s.pages == 0) return TransferNote(ctx.memStr(R.string.memory_import_nothing, "Nothing to import"), ctx.memStr(R.string.memory_import_nothing_body, "No memory pages were found in that file.") + if (s.ignored > 0) " " + ctx.memQty(R.plurals.memory_import_ignored, s.ignored, "${s.ignored} file${if (s.ignored == 1) "" else "s"} couldn't be read.", s.ignored) else "", ok = false)
    val unsortedLine = if (r.unsorted > 0) " " + ctx.memQty(R.plurals.memory_import_unsorted, r.unsorted, "${r.unsorted} came in unsorted: tap Show unsorted to classify them.", r.unsorted) else ""
    val headline = if (r.added == s.pages) ctx.memQty(R.plurals.memory_import_added, s.pages, "${s.pages} page${if (s.pages == 1) "" else "s"} added", s.pages)
        else ctx.memQty(R.plurals.memory_import_imported, s.pages, "${s.pages} page${if (s.pages == 1) "" else "s"} imported", s.pages)
    return TransferNote(headline, ctx.memStr(R.string.memory_import_sub, "Your agent can search them now.") + unsortedLine,
        listOf(ctx.memStr(R.string.memory_row_new, "New") to r.added, ctx.memStr(R.string.memory_row_updated, "Updated") to r.updated, ctx.memStr(R.string.memory_row_skipped, "Already there") to r.skipped,
            ctx.memStr(R.string.memory_row_unsorted, "Unsorted") to r.unsorted, ctx.memStr(R.string.memory_row_not_pinned, "Not pinned") to r.pinnedDropped).filterIndexed { i, (_, n) -> n > 0 || i == 0 }, r.unsorted,
        if (r.pinnedDropped > 0) ctx.memStr(R.string.memory_import_pinned_dropped, "Only ${com.past9.phoneaos.data.MemoryTransfer.MAX_PINNED} pages can be pinned, so ${r.pinnedDropped} came in unpinned.", com.past9.phoneaos.data.MemoryTransfer.MAX_PINNED, r.pinnedDropped) else null)
}
fun TransferNote.Companion.exported(n: Int, ctx: Context? = null) = TransferNote(ctx.memQty(R.plurals.memory_exported, n, "$n page${if (n == 1) "" else "s"} saved", n), ctx.memStr(R.string.memory_exported_sub, "One zip file. Import memories brings every page back."))
fun TransferNote.Companion.failed(what: String, why: String?, ctx: Context? = null) = TransferNote(ctx.memStr(R.string.memory_failed, "That didn't work"), "$what${why?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}", ok = false)

fun pageIcon(kind: String): ImageVector = when (kind) {
    "person" -> Icons.Rounded.Person; "company" -> Icons.Rounded.Business; "place" -> Icons.Rounded.Place; "project" -> Icons.Rounded.Flag
    "preference" -> Icons.Rounded.Favorite; "event" -> Icons.Rounded.Event; "how-to" -> Icons.Rounded.Lightbulb; else -> Icons.Rounded.Psychology
}

/** Display name of a page kind; unknown kinds are shown as written. */
@Composable
private fun kindLabel(k: String): String = when (k) {
    "person" -> stringResource(R.string.memory_kind_person); "place" -> stringResource(R.string.memory_kind_place); "company" -> stringResource(R.string.memory_kind_company)
    "project" -> stringResource(R.string.memory_kind_project); "preference" -> stringResource(R.string.memory_kind_preference); "event" -> stringResource(R.string.memory_kind_event)
    "how-to" -> stringResource(R.string.memory_kind_howto); "fact" -> stringResource(R.string.memory_kind_fact)
    else -> k.replaceFirstChar { it.uppercase() }
}

/** First readable line of a page, without markdown or link brackets. */
fun summaryOf(body: String) = body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }.orEmpty()
    .replace(Regex("""\[\[([^\]|]+)(?:\|([^\]]*))?]]""")) { it.groupValues[2].ifBlank { it.groupValues[1] } }.replace(Regex("[*_`]"), "").removePrefix("- ")

@Composable
fun MemoryScreen(memories: List<MemoryRow>, actions: MemoryActions, bottomPadding: androidx.compose.ui.unit.Dp = 120.dp, initialNote: TransferNote? = null) {
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var unsortedOnly by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf(initialNote) }
    var busy by remember { mutableStateOf<String?>(null) }
    val kinds = remember(memories) { memories.map { it.kind }.distinct().sorted() }
    val unsorted = remember(memories) { memories.count { isUnsorted(it) } }
    val shown = remember(memories, query, kind, unsortedOnly) {
        val k = memories.filter { (kind == null || it.kind == kind) && (!unsortedOnly || isUnsorted(it)) }
        if (query.isBlank()) k else Recall.rank(query, k, 200)
    }
    // Back up or bring back memory as one zip: the system file picker chooses where it goes and what comes in.
    val savingLabel = stringResource(R.string.memory_busy_saving); val importingLabel = stringResource(R.string.memory_busy_importing)
    val exportTo = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) { busy = savingLabel; note = null; actions.onExport(uri) { busy = null; note = it } } }
    val importFrom = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { busy = importingLabel; note = null; actions.onImport(uri) { busy = null; note = it } } }
    val pickFile = { importFrom.launch(arrayOf("application/zip", "application/json", "text/*", "application/octet-stream", "application/x-zip-compressed")) }
    val saveFile = { exportTo.launch("agent-memories-${java.time.LocalDate.now()}.zip") }
    SubScreen(stringResource(R.string.memory_title), pluralStringResource(R.plurals.memory_sub, memories.size, memories.size), actions.onBack,
        fab = { ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text(stringResource(R.string.memory_new_page)) }, modifier = Modifier.padding(bottom = (bottomPadding - 40.dp).coerceAtLeast(0.dp))) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = com.past9.phoneaos.ui.sidePad(16.dp), end = com.past9.phoneaos.ui.sidePad(16.dp), bottom = bottomPadding + 60.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                OutlinedTextField(query, { query = it }, placeholder = { Text(stringResource(R.string.memory_search)) }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge)
            }
            // What an import or export just did, or that it is working: a card in the list, never a pop-up.
            busy?.let { b -> item(key = "transfer-busy") { TransferBusy(b) } }
            note?.let { n -> item(key = "transfer-note") { TransferCard(n) { pick -> if (pick == "unsorted") unsortedOnly = true; note = null } } }
            // With nothing in memory yet (a fresh install, or after a wipe) bringing it back is the first thing to offer.
            if (memories.isEmpty() && busy == null && note == null) item(key = "transfer-first") { TransferSection(pickFile, saveFile, canExport = false, modifier = Modifier.padding(top = 12.dp)) }
            if (kinds.size > 1 || unsorted > 0) item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(kind == null && !unsortedOnly, { kind = null; unsortedOnly = false }, label = { Text(stringResource(R.string.memory_all)) }, shape = RoundedCornerShape(50)) }
                    if (unsorted > 0) item { FilterChip(unsortedOnly, { unsortedOnly = !unsortedOnly }, label = { Text(stringResource(R.string.memory_unsorted_chip, unsorted)) }, leadingIcon = { Icon(Icons.Rounded.Inbox, null, Modifier.size(16.dp)) }, shape = RoundedCornerShape(50)) }
                    items(kinds) { k -> FilterChip(kind == k, { kind = if (kind == k) null else k }, label = { Text(kindLabel(k)) }, leadingIcon = { Icon(pageIcon(k), null, Modifier.size(16.dp)) }, shape = RoundedCornerShape(50)) }
                }
            }
            if (memories.isEmpty()) item { EmptyState(stringResource(R.string.memory_empty), stringResource(R.string.memory_empty_body)) }
            else if (shown.isEmpty()) item { EmptyState(stringResource(R.string.memory_no_match), stringResource(R.string.memory_no_match_body, query)) }
            val pinned = shown.filter { it.pinned }; val rest = shown.filter { !it.pinned }
            if (pinned.isNotEmpty() && query.isBlank()) {
                item { SectionHeader(stringResource(R.string.memory_pinned), stringResource(R.string.memory_pinned_sub), Modifier.padding(start = 4.dp, top = 6.dp)) }
                items(pinned, key = { "p${it.id}" }) { m -> PageRow(m, memories) { actions.onOpen(m) } }
                item { SectionHeader(stringResource(R.string.memory_rest), stringResource(R.string.memory_rest_sub), Modifier.padding(start = 4.dp, top = 10.dp)) }
                items(rest, key = { it.id }) { m -> PageRow(m, memories) { actions.onOpen(m) } }
            } else items(shown, key = { it.id }) { m -> PageRow(m, memories) { actions.onOpen(m) } }
            if (memories.isNotEmpty() && busy == null) item(key = "transfer-last") { TransferSection(pickFile, saveFile, canExport = true, modifier = Modifier.padding(top = 18.dp)) }
        }
    }
    if (adding) PageEditor(MemoryRow(id = 0, title = "", body = "", source = "user"), { adding = false }, onSave = { actions.onSave(it); adding = false })
}

/** Back up or bring back memory: one zip out, one zip (or JSON or markdown from another tool) in. */
@Composable
private fun TransferSection(onImport: () -> Unit, onExport: () -> Unit, canExport: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.memory_transfer_title), if (canExport) stringResource(R.string.memory_transfer_sub_export) else stringResource(R.string.memory_transfer_sub_import), Modifier.padding(start = 4.dp, bottom = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onImport, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Icon(Icons.Rounded.FileUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.memory_import)) }
            if (canExport) FilledTonalButton(onClick = onExport, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Icon(Icons.Rounded.FileDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.memory_export)) }
        }
    }
}

@Composable
private fun TransferBusy(what: String) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularWavyProgressIndicator(modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(16.dp))
            Column { Text(what, style = MaterialTheme.typography.titleMedium); Text(stringResource(R.string.memory_busy_body), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
        }
    }
}

/** The result of an import or export: what happened, the numbers as tiles, and the next step. Drawn like the app's other cards. */
@Composable
internal fun TransferCard(n: TransferNote, onPick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(if (n.ok) cs.primaryContainer else cs.errorContainer), contentAlignment = Alignment.Center) {
                    Icon(if (n.ok) Icons.Rounded.Check else Icons.Rounded.ErrorOutline, null, Modifier.size(26.dp), tint = if (n.ok) cs.onPrimaryContainer else cs.onErrorContainer)
                }
                Column(Modifier.weight(1f)) {
                    Text(n.headline, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                    Text(n.sub, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
            if (n.rows.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                n.rows.forEach { (label, count) ->
                    Surface(shape = RoundedCornerShape(18.dp), color = cs.surfaceContainerHighest) {
                        Column(Modifier.widthIn(min = 92.dp).padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(count.toString(), style = MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontFeatureSettings = "tnum"), color = cs.onSurface)
                            Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
            n.footnote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (n.unsorted > 0) Button(onClick = { onPick("unsorted") }, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Rounded.Inbox, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.memory_show_unsorted)) }
                if (n.unsorted > 0) FilledTonalButton(onClick = { onPick("done") }, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Rounded.Check, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.memory_done)) }
                else Button(onClick = { onPick("done") }, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Rounded.Check, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.memory_done)) }
            }
        }
    }
}

/** Came in from a file that didn't say what it is: waiting to be looked at. Saving the page by hand clears it. */
fun isUnsorted(m: MemoryRow) = m.topics.split(',').any { it.trim().equals(com.past9.phoneaos.data.MemoryTransfer.UNSORTED, true) }

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
                Text(listOfNotNull(kindLabel(m.kind).uppercase(), if (links > 0) pluralStringResource(R.plurals.memory_links, links, links) else null, relativeTime(m.updatedAt).uppercase()).joinToString(" · "),
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
    if (page == null) { SubScreen(stringResource(R.string.memory_not_found), null, onBack) { pad -> Box(Modifier.padding(pad)) { EmptyState(stringResource(R.string.memory_gone), stringResource(R.string.memory_gone_body)) } }; return }
    val known = remember(all) { all.map { it.title }.toSet() }
    val backlinks = remember(all, page) { all.filter { o -> o.id != page.id && linksIn(o.body).any { it.equals(page.title, true) } } }
    SubScreen(page.title, stringResource(R.string.memory_updated, kindLabel(page.kind), relativeTime(page.updatedAt)), onBack, actions = {
        IconButton(onClick = { onSave(page.copy(pinned = !page.pinned)) }) { Icon(if (page.pinned) Icons.Rounded.PushPin else Icons.Rounded.PushPin, if (page.pinned) stringResource(R.string.memory_unpin) else stringResource(R.string.memory_pin), tint = if (page.pinned) cs.primary else cs.onSurfaceVariant) }
        IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, stringResource(R.string.memory_edit_page)) }
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = com.past9.phoneaos.ui.sidePad(20.dp)).padding(bottom = 48.dp)) {
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
                SectionHeader(stringResource(R.string.memory_linked_from), stringResource(R.string.memory_linked_from_sub, page.title))
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
                Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.memory_ask))
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
    com.past9.phoneaos.ui.AppSheet(pageIcon(kind), if (m.id == 0L) stringResource(R.string.memory_agent_memory) else stringResource(R.string.memory_editing), if (m.id == 0L) stringResource(R.string.memory_new_page) else m.title.ifBlank { stringResource(R.string.memory_page) }, onDismiss,
        primary = if (m.id == 0L) stringResource(R.string.memory_save_to) else stringResource(R.string.memory_save_changes), primaryEnabled = title.isNotBlank() && body.isNotBlank(),
        onPrimary = { onSave(m.copy(title = title.trim(), body = body.trim(), topics = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() && !it.equals(com.past9.phoneaos.data.MemoryTransfer.UNSORTED, true) }.joinToString(","), kind = kind, updatedAt = System.currentTimeMillis())) },
        shape = MaterialShapes.Flower,
        secondary = onDelete?.let { del -> { TextButton(onClick = del, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.memory_forget), color = MaterialTheme.colorScheme.error) } } }) {
        com.past9.phoneaos.ui.ChoiceChips(listOf("person", "place", "company", "project", "preference", "event", "how-to", "fact")
            .map { id -> Triple(id, kindLabel(id), pageIcon(id)) }, kind, { kind = it }, label = stringResource(R.string.memory_what_is))
        Spacer(Modifier.height(20.dp))
        com.past9.phoneaos.ui.SheetField(title, { title = it }, stringResource(R.string.memory_title_field), stringResource(R.string.memory_title_hint), big = true, singleLine = true)
        Spacer(Modifier.height(16.dp))
        com.past9.phoneaos.ui.SheetField(body, { body = it }, stringResource(R.string.memory_body), stringResource(R.string.memory_body_hint), minLines = 6)
        Spacer(Modifier.height(16.dp))
        com.past9.phoneaos.ui.SheetField(tags, { tags = it }, stringResource(R.string.memory_tags), stringResource(R.string.memory_tags_hint), singleLine = true)
    }
}


/**
 * Memory on a landscape tablet: the list on the left, the open page on the right (the pinned page, else the newest,
 * until one is picked). No frame around either pane: a hairline divides them.
 */
@Composable
fun MemoryTwoPane(memories: List<MemoryRow>, openId: Long?, onPick: (Long?) -> Unit, actions: MemoryActions, bottomPadding: androidx.compose.ui.unit.Dp,
                  onOpenTitle: (String) -> Unit, onAsk: (String) -> Unit) {
    val listWidth = 400.dp
    val shown = memories.firstOrNull { it.id == openId } ?: memories.firstOrNull { it.pinned } ?: memories.maxByOrNull { it.updatedAt }
    Row(Modifier.fillMaxSize()) {
        androidx.compose.runtime.CompositionLocalProvider(com.past9.phoneaos.ui.LocalPaneWidth provides listWidth) {
            Box(Modifier.width(listWidth).fillMaxHeight()) { MemoryScreen(memories, actions, bottomPadding) }
        }
        androidx.compose.material3.VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        val rest = com.past9.phoneaos.ui.windowWidthDp() - listWidth
        androidx.compose.runtime.CompositionLocalProvider(com.past9.phoneaos.ui.LocalPaneWidth provides rest) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (shown == null) EmptyState(stringResource(R.string.memory_no_pages), stringResource(R.string.memory_no_pages_body), Modifier.align(Alignment.Center))
                else WikiPageScreen(shown, memories, onBack = actions.onBack, onOpenTitle = onOpenTitle, onSave = actions.onSave,
                    onDelete = { m -> actions.onDelete(m); onPick(null) }, onAsk = onAsk)
            }
        }
    }
}

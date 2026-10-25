package com.past9.phoneaos.ui.screens

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.past9.phoneaos.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.agent.ModelInfo
import com.past9.phoneaos.data.HelperModel
import com.past9.phoneaos.data.PinnedAgent
import com.past9.phoneaos.ui.LocalSheetPreview
import com.past9.phoneaos.ui.theme.Eyebrow

private const val MAX_HELPERS = 6

/**
 * Who the agent hands work to: pick several models and tag what each is good for. The main agent reads
 * these tags to send each job to the right helper, and writes a fuller brief for smaller models.
 */
@Composable
fun HelperRosterSheet(models: List<ModelInfo>?, error: String?, initial: List<HelperModel>, onSave: (List<HelperModel>) -> Unit, onDismiss: () -> Unit,
                      /** Helpers the agent kept (pinned): shown quietly, nothing to manage. */
                      kept: List<PinnedAgent> = emptyList()) {
    val cs = MaterialTheme.colorScheme
    // Picked models in the order they were picked: the first is the default helper.
    val picked = remember { mutableStateListOf<HelperModel>().apply { addAll(initial) } }
    var q by remember { mutableStateOf("") }
    val shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp)
    // Models already on the roster stay listed even if the provider's list no longer has them.
    val all = remember(models, initial) {
        val known = models.orEmpty()
        known + initial.filter { h -> known.none { it.id == h.id } }.map { ModelInfo(it.id, it.name, false, "") }
    }
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(stringResource(R.string.helpers_eyebrow), style = Eyebrow, color = cs.primary)
                Text(stringResource(R.string.helpers_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.helpers_body, MAX_HELPERS),
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                if (all.size > 8) OutlinedTextField(q, { q = it }, placeholder = { Text(stringResource(R.string.helpers_search)) }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge)
            }
            when {
                error != null -> Text(error, Modifier.padding(24.dp), color = cs.error)
                models == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                all.isEmpty() -> Text(stringResource(R.string.helpers_no_models), Modifier.padding(24.dp), color = cs.onSurfaceVariant)
                else -> LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 520.dp), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
                    // Picked ones first, so the roster reads top to bottom.
                    val shown = all.filter { q.isBlank() || it.name.contains(q, true) || it.id.contains(q, true) }
                        .sortedBy { m -> picked.indexOfFirst { it.id == m.id }.let { if (it < 0) Int.MAX_VALUE else it } }
                    items(shown, key = { it.id.ifBlank { "default" } }) { m ->
                        val index = picked.indexOfFirst { it.id == m.id }
                        RosterRow(m, picked.getOrNull(index), index, full = picked.size >= MAX_HELPERS,
                            onToggle = { if (index >= 0) picked.removeAt(index) else if (picked.size < MAX_HELPERS) picked += HelperModel(m.id, m.name) },
                            onTags = { tags -> if (index >= 0) picked[index] = picked[index].copy(tags = tags) })
                    }
                    if (kept.isNotEmpty() && q.isBlank()) {
                        item(key = "kept-header") {
                            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 4.dp)) {
                                Text(stringResource(R.string.helpers_kept_eyebrow), style = Eyebrow, color = cs.onSurfaceVariant)
                                Spacer(Modifier.height(2.dp))
                                Text(stringResource(R.string.helpers_kept_sub), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                        }
                        items(kept.sortedByDescending { it.lastUsedAt }, key = { "kept-" + it.id }) { a -> KeptRow(a) }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 20.dp)) {
                Button(onClick = { onSave(picked.toList()); onDismiss() }, modifier = Modifier.fillMaxWidth().height(56.dp), shapes = ButtonDefaults.shapes()) {
                    Text(if (picked.isEmpty()) stringResource(R.string.helpers_use_agent_only) else pluralStringResource(R.plurals.helpers_save, picked.size, picked.size), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
    if (LocalSheetPreview.current) Surface(color = cs.surfaceContainerLow, shape = shape, modifier = Modifier.fillMaxSize().padding(top = 48.dp)) {
        Column { Box(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 22.dp).size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(cs.onSurfaceVariant.copy(alpha = 0.4f))); body() }
    } else ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = cs.surfaceContainerLow, shape = shape) { body() }
}

/** One kept helper: its name and what it's good for. Read-only on purpose. */
@Composable
private fun KeptRow(a: PinnedAgent) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PushPin, null, Modifier.size(16.dp), tint = cs.onSecondaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(a.goodFor.joinToString(", ").ifBlank { a.summary }, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(pluralStringResource(R.plurals.helpers_kept_jobs, a.jobs.size, a.jobs.size), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun RosterRow(m: ModelInfo, picked: HelperModel?, index: Int, full: Boolean, onToggle: () -> Unit, onTags: (List<String>) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val on = picked != null
    Surface(shape = MaterialTheme.shapes.extraLarge, color = if (on) cs.surfaceContainerHigh else Color.Transparent, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).animateContentSize()) {
        Column {
            Surface(onClick = onToggle, enabled = on || !full, color = Color.Transparent, shape = MaterialTheme.shapes.extraLarge) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    // The pick order, or an empty ring.
                    Box(Modifier.size(28.dp).clip(CircleShape).background(if (on) cs.primary else cs.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                        if (on) Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = cs.onPrimary)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (!on && full) cs.onSurfaceVariant else cs.onSurface)
                        val sub = listOfNotNull(if (index == 0) stringResource(R.string.helpers_default) else null, m.note.takeIf { it.isNotBlank() }, m.id.takeIf { it.isNotBlank() && it != m.name }).joinToString(" · ")
                        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (on) Icon(Icons.Rounded.Check, stringResource(R.string.helpers_picked), tint = cs.primary)
                }
            }
            AnimatedVisibility(on) {
                Column(Modifier.padding(start = 54.dp, end = 12.dp, bottom = 14.dp)) {
                    Text(stringResource(R.string.helpers_good_for), style = Eyebrow, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    val tags = picked?.tags.orEmpty()
                    var adding by remember { mutableStateOf(false) }
                    var custom by remember { mutableStateOf("") }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        (HelperModel.PresetTags + tags.filter { it !in HelperModel.PresetTags }).forEach { t ->
                            val sel = t in tags
                            FilterChip(sel, onClick = { onTags(if (sel) tags - t else tags + t) }, label = { Text(t) },
                                leadingIcon = if (sel) ({ Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }) else null)
                        }
                        AssistChip(onClick = { adding = true }, label = { Text(stringResource(R.string.helpers_your_own)) }, leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)) })
                    }
                    if (adding) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(custom, { custom = it.take(24) }, placeholder = { Text(stringResource(R.string.helpers_custom_hint)) }, singleLine = true,
                            modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.large)
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(onClick = { custom.trim().takeIf { it.isNotEmpty() && it !in tags }?.let { onTags(tags + it) }; custom = ""; adding = false }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.helpers_add)) }
                    }
                }
            }
        }
    }
}

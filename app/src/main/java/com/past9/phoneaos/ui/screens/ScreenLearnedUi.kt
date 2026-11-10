package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.connections.LearnedApp
import com.past9.phoneaos.connections.LearnedTool
import com.past9.phoneaos.screen.ScreenLearned
import com.past9.phoneaos.screen.ScreenSkills
import com.past9.phoneaos.ui.AppSheet
import com.past9.phoneaos.ui.SheetField

/** What the person can change about a learned phone app: its name, and each tool (rename, describe, undo, delete). */
class LearnedEdit(val rename: (id: String, name: String) -> Unit = { _, _ -> }, val replaceTool: (id: String, tool: String, with: LearnedTool?) -> Unit = { _, _, _ -> })

private fun secs(ms: Long) = "%.1f s".format(ms / 1000.0)

/** How a learned phone tool has been going, in one line. */
fun learnedToolLine(t: LearnedTool): String {
    val s = t.stats
    return listOfNotNull("${t.steps.length()} steps", if (s.optInt("runs") > 0) "run ${s.optInt("runs")} times" else "not run yet",
        s.optLong("lastMs").takeIf { it > 0 }?.let { "last took ${secs(it)}" }, s.optLong("liveMs").takeIf { it > 0 }?.let { "by hand it took ${secs(it)}" },
        s.optInt("fails").takeIf { it > 0 }?.let { "stopped $it times" }, if (s.optString("by") == "you") "shown by you" else null).joinToString(" · ")
}

/**
 * Inside a learned app's tile in Connections, for an app on the phone: each tool with how it has been going, and
 * slide-up sheets to rename the app and to open a tool (its steps, a new name, undo its last change, delete it).
 */
@Composable
fun ScreenLearnedDetails(a: LearnedApp, edit: LearnedEdit) {
    val cs = MaterialTheme.colorScheme
    var renaming by remember(a.id) { mutableStateOf(false) }
    var opened by remember(a.id) { mutableStateOf<String?>(null) }
    Text("Done in the real app on the hidden screen. A step that sends, pays or deletes still asks you every time.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 10.dp)) {
        a.tools.forEach { t ->
            Surface(onClick = { opened = t.name }, shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name.replace('_', ' '), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(learnedToolLine(t), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Rounded.ChevronRight, "Open", tint = cs.onSurfaceVariant)
                }
            }
        }
    }
    TextButton(onClick = { renaming = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Rename") }
    if (renaming) {
        var name by remember { mutableStateOf(a.name) }
        AppSheet(Icons.Rounded.Edit, "Learned app", "Rename", { renaming = false }, primary = "Save", primaryEnabled = name.isNotBlank(), onPrimary = { edit.rename(a.id, name); renaming = false }) {
            SheetField(name, { name = it }, "Name", a.name, singleLine = true, keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
        }
    }
    a.tools.firstOrNull { it.name == opened }?.let { t -> LearnedToolSheet(a, t, edit) { opened = null } }
}

@Composable
fun LearnedToolSheet(a: LearnedApp, t: LearnedTool, edit: LearnedEdit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var name by remember(t.name) { mutableStateOf(t.name.replace('_', ' ')) }
    var what by remember(t.name) { mutableStateOf(t.description) }
    var sure by remember { mutableStateOf(false) }
    val slug = LearnedApp.slug(name)
    AppSheet(Icons.Rounded.AutoAwesome, a.name, t.name.replace('_', ' '), onDismiss, primary = "Save", primaryEnabled = slug.length >= 2 && what.isNotBlank() && (slug == t.name || a.tools.none { it.name == slug }),
        onPrimary = { edit.replaceTool(a.id, t.name, t.copy(name = slug, description = what.trim())); onDismiss() }) {
        Text(learnedToolLine(t), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        SheetField(name, { name = it }, "Name", "", singleLine = true, keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None))
        Spacer(Modifier.height(12.dp))
        SheetField(what, { what = it }, "What it does", "", minLines = 2, keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
        Spacer(Modifier.height(16.dp))
        Text("THE STEPS", style = com.past9.phoneaos.ui.theme.Eyebrow, color = cs.primary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Text(ScreenSkills.describe(t.steps), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { ScreenLearned.undone(t)?.let { edit.replaceTool(a.id, t.name, it) }; onDismiss() }, enabled = t.versions.length() > 0, shapes = ButtonDefaults.shapes(),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (t.versions.length() > 0) "Undo last change" else "No earlier version") }
            if (!sure) OutlinedButton(onClick = { sure = true }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Delete", color = cs.error) }
            else Button(onClick = { edit.replaceTool(a.id, t.name, null); onDismiss() }, shapes = ButtonDefaults.shapes(), colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Yes, delete") }
        }
    }
}

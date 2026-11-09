package com.past9.phoneaos.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.past9.phoneaos.remote.ProjectDraft
import com.past9.phoneaos.remote.ProjectsApi
import com.past9.phoneaos.remote.RemoteHub
import com.past9.phoneaos.ui.theme.Eyebrow
import com.past9.phoneaos.ui.theme.accentOf
import kotlinx.coroutines.launch

/** The link's projects calls, or null when this phone is not using a computer. */
@Composable
fun rememberProjectsApi(): ProjectsApi? {
    val session = RemoteHub.session.collectAsStateWithLifecycle().value
    return remember(session) { session?.let { s -> ProjectsApi { method, args -> s.call(method, args) } } }
}

/**
 * Make a project on the linked computer, as a slide-up sheet (9 Nov 2026): name, what it is for, the profile it works
 * in, its icon and colour, and the folder on the computer if he knows it. Not linked, it says so instead of a form.
 */
@Composable
fun NewProjectSheet(onDismiss: () -> Unit, onCreated: (projectId: String) -> Unit) =
    NewProjectSheet(onDismiss, onCreated, rememberProjectsApi())

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewProjectSheet(
    onDismiss: () -> Unit, onCreated: (projectId: String) -> Unit, api: ProjectsApi?,
    /** What the form starts with, and the profiles it offers before the computer answers (screenshots and tests). */
    initial: ProjectDraft = ProjectDraft(), initialProfiles: List<Pair<String, String>> = listOf("" to "General"), initialError: String = "",
) {
    val cs = MaterialTheme.colorScheme
    if (api == null) {
        AgentSheet(onDismiss) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 20.dp).navigationBarsPadding()) {
                SheetHead(Icons.Rounded.Computer, "New project", "Projects need a linked computer",
                    "A project lives on your computer, with its own chats and files. Link a computer in Settings, then make one here.")
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(56.dp), shapes = ButtonDefaults.shapes()) { Text("Close", style = MaterialTheme.typography.titleSmall) }
            }
        }
        return
    }
    var draft by remember { mutableStateOf(initial) }
    var emoji by remember { mutableStateOf(initial.glyph.takeIf { it.isNotEmpty() && it !in ProjectsApi.GLYPHS }.orEmpty()) }
    var profiles by remember { mutableStateOf(initialProfiles) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(initialError) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(api) { runCatching { api.profiles() }.onSuccess { profiles = it } }

    AppSheet(Icons.Rounded.CreateNewFolder, "New project", "What is it?", onDismiss,
        primary = if (busy) "Creating" else "Create project", primaryEnabled = !busy && ProjectsApi.canCreate(draft),
        onPrimary = {
            busy = true; error = ""
            scope.launch {
                runCatching { api.create(draft) }
                    .onSuccess { busy = false; onCreated(it.id) }
                    .onFailure { busy = false; error = "Couldn't make the project: ${it.message?.trim()?.trimEnd('.')?.ifBlank { null } ?: "your computer did not answer"}. Nothing you typed is lost." }
            }
        },
        secondary = if (busy) ({ LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp)) }) else null,
    ) {
        if (error.isNotBlank()) {
            Surface(shape = MaterialTheme.shapes.large, color = cs.errorContainer, contentColor = cs.onErrorContainer, modifier = Modifier.fillMaxWidth()) {
                Text(error, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProjectIconTile(ProjectsApi.cleanName(draft.name).ifEmpty { "P" }, ProjectsApi.cleanGlyph(draft.glyph), draft.color, "", 56.dp)
            Spacer(Modifier.width(12.dp))
            AppTextField(draft.name, { draft = draft.copy(name = it.take(ProjectsApi.NAME_MAX)) }, Modifier.weight(1f), enabled = !busy, singleLine = true,
                label = { Text("Name") }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next))
        }
        Spacer(Modifier.height(12.dp))
        AppTextField(draft.description, { draft = draft.copy(description = it.take(ProjectsApi.DESCRIPTION_MAX)) }, Modifier.fillMaxWidth(), enabled = !busy, minLines = 2, maxLines = 5,
            label = { Text("What it is for (optional)") }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            supportingText = if (draft.description.length > ProjectsApi.DESCRIPTION_MAX - 100) ({ Text("${draft.description.length} of ${ProjectsApi.DESCRIPTION_MAX}") }) else null)

        FormLabel("Profile", "Which accounts its agent may use")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            profiles.forEach { (id, name) ->
                val on = draft.profile == id
                Surface(onClick = { if (!busy) draft = draft.copy(profile = id) }, shape = RoundedCornerShape(50), color = if (on) cs.primaryContainer else cs.surfaceContainerHigh,
                    contentColor = if (on) cs.onPrimaryContainer else cs.onSurface, modifier = Modifier.heightIn(min = 48.dp).semantics { selected = on }) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (on) { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
                        Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
        }

        FormLabel("Colour")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ProjectsApi.COLORS.forEach { c ->
                val on = draft.color == c
                val accent = accentOf(c)
                Surface(onClick = { if (!busy) draft = draft.copy(color = c) }, shape = CircleShape, color = androidx.compose.ui.graphics.Color.Transparent,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = accent.label; selected = on; role = Role.RadioButton }) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(accent.lP).then(if (on) Modifier.border(BorderStroke(3.dp, cs.onSurface), CircleShape) else Modifier), contentAlignment = Alignment.Center) {
                            if (on) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = accent.lOnP)
                        }
                    }
                }
            }
        }

        FormLabel("Icon")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ProjectsApi.GLYPHS.forEach { gl ->
                val on = emoji.isEmpty() && draft.glyph == gl
                Surface(onClick = { if (!busy) { emoji = ""; draft = draft.copy(glyph = gl) } }, shape = MaterialTheme.shapes.medium,
                    color = if (on) cs.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = gl; selected = on; role = Role.RadioButton }) {
                    Box(contentAlignment = Alignment.Center) { ProjectIconTile("", gl, if (on) draft.color else "graphite", "", 34.dp) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        AppTextField(emoji, { typed ->
            val one = typed.trim().let { t -> if (t.isEmpty()) "" else ProjectsApi.cleanGlyph(t).takeIf { it != "folder" }.orEmpty() }
            emoji = one
            draft = draft.copy(glyph = one.ifEmpty { draft.glyph.takeIf { it in ProjectsApi.GLYPHS } ?: "folder" })
        }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text("Or one emoji (optional)") })

        FormLabel("Folder on your computer", "Optional. Type the full path, or leave it empty and set it on the computer later.")
        AppTextField(draft.path, { draft = draft.copy(path = it) }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text("Folder path") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done))
    }
}

@Composable
private fun FormLabel(title: String, line: String? = null) {
    val cs = MaterialTheme.colorScheme
    Spacer(Modifier.height(20.dp))
    Text(title.uppercase(), style = Eyebrow, color = cs.primary)
    if (line != null) Text(line, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
}

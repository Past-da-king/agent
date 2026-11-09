package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.past9.phoneaos.remote.ProjectRow
import com.past9.phoneaos.remote.ProjectsApi
import com.past9.phoneaos.remote.RemoteHub
import com.past9.phoneaos.remote.RemoteSession
import com.past9.phoneaos.ui.ChoiceSheet
import com.past9.phoneaos.ui.NewProjectSheet
import com.past9.phoneaos.ui.ProjectIconTile
import com.past9.phoneaos.ui.SectionHeader
import com.past9.phoneaos.ui.SheetChoice
import com.past9.phoneaos.ui.theme.accentOf
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The computer's projects as the profile shows them. [api] is null when this phone is not using a computer. */
data class ProfileProjects(val api: ProjectsApi? = null, val rows: List<ProjectRow> = emptyList(), val loaded: Boolean = false, val reload: () -> Unit = {}) {
    val linked get() = api != null
}

/** How many projects the profile lists before "Show all". */
private const val PROJECTS_SHOWN = 5

/** The linked computer's projects, read once and again whenever the computer says a project changed (data event). */
@Composable
fun rememberProfileProjects(): ProfileProjects {
    val session = RemoteHub.session.collectAsStateWithLifecycle().value
    val api = remember(session) { session?.let { s -> ProjectsApi { method, args -> s.call(method, args) } } }
    var rows by remember(session) { mutableStateOf<List<ProjectRow>>(emptyList()) }
    var loaded by remember(session) { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(api, tick) { if (api != null) runCatching { api.list() }.onSuccess { rows = it; loaded = true } }
    LaunchedEffect(session) {
        session?.events?.collect { (event, payload) -> if (event == RemoteSession.EVT_DATA && (payload as? JSONObject)?.let { it.optString("entity") == "project" || it.optBoolean("reset") } == true) tick++ }
    }
    return ProfileProjects(api, rows, loaded, reload = { tick++ })
}

/** Open a new chat in [project] (an empty one is reused), or say why not. */
private suspend fun openNewChat(projects: ProfileProjects, project: ProjectRow, actions: ProfileActions): String {
    val api = projects.api ?: return "Not linked to a computer right now."
    return runCatching { api.startChat(project) }.fold(
        { ref -> projects.reload(); actions.onProjectChat(ref.chat, project.name); "" },
        { "Couldn't start a chat in ${project.name}: ${it.message?.trim()?.trimEnd('.')?.ifBlank { null } ?: "your computer did not answer"}." })
}

/** The profile's Projects section: each project with its icon, name and last activity; a tap opens its latest chat. */
@Composable
fun ProjectsSection(projects: ProfileProjects, actions: ProfileActions, now: Long = System.currentTimeMillis()) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var all by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf("") }
    if (adding) NewProjectSheet(onDismiss = { adding = false }, onCreated = { id ->
        adding = false; projects.reload()
        // A new project opens on its first chat, ready to type in.
        scope.launch { projects.api?.let { api -> runCatching { api.list().firstOrNull { it.id == id } }.getOrNull()?.let { p -> problem = openNewChat(projects, p, actions) } } }
    }, api = projects.api)
    val rows = projects.rows
    Column(Modifier.fillMaxWidth()) {
        SectionHeader("Projects", when {
            !projects.linked -> "Projects live on a linked computer."
            !projects.loaded -> "Reading them from your computer"
            rows.isEmpty() -> "None yet. A project has its own chats, files and agent."
            else -> "${rows.size} on your computer. Tap one to open its chat."
        }, Modifier.padding(horizontal = 20.dp)) {
            TextButton(onClick = { adding = true }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("New project") }
        }
        if (problem.isNotBlank()) Text(problem, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodyMedium, color = cs.error)
        val openProject: (ProjectRow) -> Unit = { p ->
            problem = ""
            if (p.latestChat.isNotBlank()) actions.onProjectChat(p.latestChat, p.name) else scope.launch { problem = openNewChat(projects, p, actions) }
        }
        // Folded (the default): one row of the first few, side by side, and "All" for the rest. Never a long list by itself.
        if (rows.isNotEmpty() && !all) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Top) {
                rows.take(PROJECTS_SHOWN).forEach { p ->
                    Surface(onClick = { openProject(p) }, shape = MaterialTheme.shapes.large, color = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.width(76.dp)) {
                        Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            ProjectIconTile(p.name, p.glyph, p.color, p.image, 44.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(if (p.isArchive) p.name.ifBlank { "Earlier chats" } else p.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
                        }
                    }
                }
                if (rows.size > PROJECTS_SHOWN) TextButton(onClick = { all = true }, modifier = Modifier.heightIn(min = 60.dp)) { Text("All ${rows.size}") }
            }
        }
        if (rows.isNotEmpty() && all) {
            Spacer(Modifier.height(10.dp))
            Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    rows.forEach { p ->
                        Surface(onClick = { openProject(p) }, color = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                ProjectIconTile(p.name, p.glyph, p.color, p.image, 40.dp)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(if (p.isArchive) p.name.ifBlank { "Earlier chats" } else p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(when { p.chats.isEmpty() -> "No chats yet"; else -> ProjectsApi.ago(p.activeAt, now).ifBlank { if (p.chats.size == 1) "1 chat" else "${p.chats.size} chats" } },
                                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(Icons.Rounded.ChevronRight, null, tint = cs.onSurfaceVariant)
                            }
                        }
                    }
                    TextButton(onClick = { all = false }, modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp)) { Text("Show fewer") }
                }
            }
        }
    }
}

/** "New chat" from the profile: the main chat, or any project, as a slide-up choice. */
@Composable
fun NewChatChoice(projects: ProfileProjects, actions: ProfileActions, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf("") }
    val choices = listOf(SheetChoice("", "Main chat", "Start fresh. What was said so far is saved to memory.", icon = Icons.Rounded.Forum)) +
        projects.rows.filter { !it.isArchive }.map { p -> SheetChoice(p.id, p.name, "A new chat in this project", mark = accentOf(p.color).lP) }
    ChoiceSheet(Icons.Rounded.AddComment, "New chat", "Where do you want to start?", choices, onPick = { c ->
        val p = projects.rows.firstOrNull { it.id == c.id && c.id.isNotEmpty() }
        if (p == null) { onDismiss(); actions.onNewMainChat() }
        else scope.launch { problem = openNewChat(projects, p, actions); if (problem.isEmpty()) onDismiss() }
    }, onDismiss = onDismiss, line = problem.ifBlank { if (projects.linked) null else "Link a computer to start chats in projects." })
}

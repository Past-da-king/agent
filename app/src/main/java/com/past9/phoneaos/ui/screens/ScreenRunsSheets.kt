package com.past9.phoneaos.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.connections.LearnedApp
import com.past9.phoneaos.screen.InstalledApps
import com.past9.phoneaos.screen.RunInfo
import com.past9.phoneaos.screen.RunStep
import com.past9.phoneaos.screen.ScreenLearned
import com.past9.phoneaos.screen.ScreenUi
import com.past9.phoneaos.screen.Screens
import com.past9.phoneaos.ui.AgentSheet
import com.past9.phoneaos.ui.AppCard
import com.past9.phoneaos.ui.AppSheet
import com.past9.phoneaos.ui.ChoiceSheet
import com.past9.phoneaos.ui.SheetChoice
import com.past9.phoneaos.ui.SheetField
import com.past9.phoneaos.ui.SheetHead
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun size(bytes: Long) = if (bytes >= 1_000_000) "%.1f MB".format(bytes / 1_000_000.0) else "${(bytes / 1000).coerceAtLeast(1)} KB"
private fun whenText(at: Long) = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(at))

/**
 * Settings > Background screen > Recordings and learned apps: every run on the hidden screen, newest first, to watch
 * or delete; where the learned phone apps are; and the way to show the agent a job by hand. A slide-up sheet.
 */
@Composable
fun ScreenRunsSheet(runs: List<RunInfo>, learned: List<LearnedApp>, recordOn: Boolean, onOpen: (String) -> Unit, onDeleteAll: () -> Unit, onRecord: (Boolean) -> Unit,
                    onShow: () -> Unit, onLearned: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var confirm by remember { mutableStateOf(false) }
    AgentSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            SheetHead(Icons.Rounded.Movie, "Background screen", "Recordings and learned apps",
                "Everything your agent does on the hidden screen is kept here as a recording you can watch, on this phone only. A job that worked can be saved as a learned app, so next time it is one quick step.")
            Spacer(Modifier.height(20.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Hub, null, tint = cs.primary); Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Learned phone apps", style = MaterialTheme.typography.titleSmall)
                        Text(if (learned.isEmpty()) "None yet. They show with your other learned apps, in Connections."
                            else learned.joinToString(", ") { "${it.name} (${it.tools.size})" } + ". Open, rename or remove them in Connections.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.TouchApp, null, tint = cs.primary); Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Show it a job yourself", style = MaterialTheme.typography.titleSmall)
                        Text("Do it once by hand on the hidden screen. Your agent learns the steps and can repeat them.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onShow) { Text("Show", style = MaterialTheme.typography.labelLarge) }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FiberManualRecord, null, tint = cs.primary); Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Keep recordings", style = MaterialTheme.typography.titleSmall)
                        Text("Passwords and anything typed as private are painted over. Old ones are cleared by themselves.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(recordOn, onRecord)
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("RECORDINGS", style = com.past9.phoneaos.ui.theme.Eyebrow, color = cs.primary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            if (runs.isEmpty()) Text("Nothing yet. When your agent uses an app in the background, the run shows up here.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp))
            else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    runs.forEach { r ->
                        Surface(onClick = { onOpen(r.id) }, shape = MaterialTheme.shapes.large, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.PlayCircle, null, tint = cs.primary); Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(r.app.ifBlank { r.pkg }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(whenText(r.startedAt) + " · " + r.steps + (if (r.steps == 1) " step" else " steps") + " · " + size(r.bytes) + (if (r.owner == "you") " · shown by you" else ""),
                                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (!confirm) TextButton(onClick = { confirm = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete all recordings", color = cs.error) }
                else Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { confirm = false }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Keep") }
                    Button(onClick = { confirm = false; onDeleteAll() }, shapes = ButtonDefaults.shapes(), colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Delete all") }
                }
            }
        }
    }
}

/** Watch one run again: its pictures in order, with what was done written under each. */
@Composable
fun ScreenRunPlayerSheet(info: RunInfo, steps: List<RunStep>, picture: (Int) -> ImageBitmap?, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shown = remember(steps) { steps.filter { it.frame >= 0 } }
    var at by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(playing, shown.size) {
        while (playing && shown.isNotEmpty()) { delay(1100); if (at < shown.size - 1) at++ else playing = false }
    }
    val step = shown.getOrNull(at)
    val img = remember(step?.frame) { step?.let { picture(it.frame) } }
    AgentSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 20.dp).navigationBarsPadding()) {
            SheetHead(Icons.Rounded.PlayCircle, "Recording", info.app.ifBlank { info.pkg }, whenText(info.startedAt) + " · kept on this phone only")
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().weight(1f, fill = false), contentAlignment = Alignment.Center) {
                Box(Modifier.heightIn(max = 420.dp).aspectRatio(0.45f).clip(MaterialTheme.shapes.large).background(cs.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    if (img != null) Image(img, "What the hidden screen showed", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else Text(if (shown.isEmpty()) "This run has no pictures" else "Picture not kept", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            Surface(color = cs.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(if (shown.isEmpty()) "No steps" else "Step ${at + 1} of ${shown.size}" + if (step?.by == "you") " · by you" else "", style = com.past9.phoneaos.ui.theme.Eyebrow, color = cs.primary)
                    Text(step?.say ?: steps.lastOrNull()?.say.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = cs.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (shown.size > 1) Slider(at.toFloat(), { at = it.toInt().coerceIn(0, shown.size - 1); playing = false }, valueRange = 0f..(shown.size - 1).toFloat(), steps = (shown.size - 2).coerceAtLeast(0))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { playing = false; if (at > 0) at-- }, enabled = at > 0, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Icon(Icons.Rounded.SkipPrevious, "Step back") }
                Button(onClick = { if (at >= shown.size - 1) at = 0; playing = !playing }, enabled = shown.size > 1, modifier = Modifier.weight(1.4f).height(56.dp), shapes = ButtonDefaults.shapes()) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(if (playing) "Pause" else "Play", style = MaterialTheme.typography.titleMedium)
                }
                OutlinedButton(onClick = { playing = false; if (at < shown.size - 1) at++ }, enabled = at < shown.size - 1, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Icon(Icons.Rounded.SkipNext, "Step on") }
            }
            TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete this recording", color = cs.error) }
        }
    }
}

/** Apps the background screen never uses: the built-in money apps on this phone (fixed), and the ones the person added. */
@Composable
fun ScreenBlockedSheet(builtInHere: List<String>, added: List<Pair<String, String>>, onAdd: () -> Unit, onRemove: (String) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AppSheet(Icons.Rounded.Shield, "Background screen", "Blocked apps", onDismiss, primary = "Block another app", primaryEnabled = true, onPrimary = onAdd) {
        Text("Banking, wallet, payment, card, crypto and investing apps are never opened, shown or touched on the hidden screen. That is built in and cannot be switched off, by you or by your agent. You can add any other app.",
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text("ALWAYS BLOCKED ON THIS PHONE", style = com.past9.phoneaos.ui.theme.Eyebrow, color = cs.primary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Surface(color = cs.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, null, tint = cs.onSurfaceVariant); Spacer(Modifier.width(16.dp))
                Text(if (builtInHere.isEmpty()) "No banking or money app was found on this phone. Any you install later is blocked too." else builtInHere.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("ALSO BLOCKED BY YOU", style = com.past9.phoneaos.ui.theme.Eyebrow, color = cs.primary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        if (added.isEmpty()) Text("None added.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            added.forEach { (pkg, label) ->
                Surface(color = cs.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(label.ifBlank { pkg }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(pkg, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { onRemove(pkg) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Unblock") }
                    }
                }
            }
        }
    }
}

/** Save what was just done (by the agent, or by hand) as a learned app tool: a name, what it does, and which typed words change each time. */
@Composable
fun ScreenSaveSkillSheet(app: String, steps: List<String>, typed: List<String>, error: String, onSave: (name: String, description: String, inputs: Map<String, String>) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var name by remember { mutableStateOf("") }
    var what by remember { mutableStateOf("") }
    val on = remember { mutableStateMapOf<Int, Boolean>().apply { typed.indices.forEach { put(it, true) } } }
    val slug = LearnedApp.slug(name)
    AppSheet(Icons.Rounded.AutoAwesome, "Learned app", "Save this for $app", onDismiss, primary = "Save", primaryEnabled = slug.length >= 2 && what.isNotBlank(),
        onPrimary = { onSave(slug, what.trim(), typed.withIndex().filter { on[it.index] == true }.associate { (i, t) -> (if (typed.size == 1) "text" else "text${i + 1}") to t }) }) {
        Text("Your agent will be able to do these ${steps.size} steps again by itself, also from your computer. A step that sends, pays or deletes still asks you every time.",
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        SheetField(name, { name = it }, "What to call it", "search, send a message, add a note", singleLine = true, keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None))
        Spacer(Modifier.height(12.dp))
        SheetField(what, { what = it }, "What it does", "One line your agent reads to know when to use it", minLines = 2, keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
        if (typed.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("WHAT CHANGES EACH TIME", style = com.past9.phoneaos.ui.theme.Eyebrow, color = cs.primary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                typed.forEachIndexed { i, t ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("“" + t.take(60) + "”", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (on[i] == true) "Your agent fills this in each time" else "Always these exact words", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        Switch(on[i] == true, { on[i] = it })
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("THE STEPS", style = com.past9.phoneaos.ui.theme.Eyebrow, color = cs.primary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Text(steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodyMedium, color = cs.error, modifier = Modifier.padding(top = 12.dp))
    }
}

/** Type into the hidden screen while you have taken it over. */
@Composable
fun ScreenTypeSheet(onType: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf(false) }
    AppSheet(Icons.Rounded.Keyboard, "Background screen", "Type into the app", onDismiss, primary = "Type it", primaryEnabled = text.isNotEmpty(), onPrimary = { onType(text, secret) }) {
        SheetField(text, { text = it }, "Goes into the field you pressed", "", minLines = 2, keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Private", style = MaterialTheme.typography.titleSmall)
                Text("A password or a PIN: painted over in the recording and never kept.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(secret, { secret = it })
        }
    }
}

// ---- wiring to the phone ----

@Composable
internal fun RunsHost(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val g = remember { com.past9.phoneaos.App.graph(context) }
    val runs = remember { Screens.runs(context) }
    val session = remember { Screens.session(context) }
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(runs.list()) }
    var open by remember { mutableStateOf<String?>(null) }
    var record by remember { mutableStateOf(g.settings.extra(Screens.RECORD) != "0") }
    var choose by remember { mutableStateOf(false) }
    val learned by g.runtime.learned.apps.collectAsState()
    val playing = open?.let { id -> list.firstOrNull { it.id == id } }
    when {
        playing != null -> ScreenRunPlayerSheet(playing, remember(playing.id) { runs.steps(playing.id) },
            picture = { n -> runs.picture(playing.id, n)?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() } },
            onDelete = { runs.delete(playing.id); list = runs.list(); open = null }, onDismiss = { open = null })
        choose -> {
            val block = remember { Screens.config(context).blocklist }
            val apps = remember { InstalledApps(context).everything().filter { block.blocked(it.pkg, it.label) == null }.sortedBy { it.label.lowercase() } }
            ChoiceSheet(Icons.Rounded.TouchApp, "Show it a job", "Which app?", apps.map { SheetChoice(it.pkg, it.label, it.pkg) }, onPick = { c ->
                choose = false
                scope.launch {
                    val why = session.userShow(c.id)
                    if (why == null) ScreenUi.show.value = ScreenUi.PEEK else com.past9.phoneaos.ui.AppNotice.say(why.substringBefore('.').take(90))
                }
            }, onDismiss = { choose = false }, line = "It opens on the hidden screen with the picture in your hands. Do the job, then save it.")
        }
        else -> ScreenRunsSheet(list, learned.filter { it.isScreen }, record, onOpen = { open = it }, onDeleteAll = { runs.deleteAll(); list = runs.list() },
            onRecord = { on -> record = on; g.settings.setExtra(Screens.RECORD, if (on) "1" else "0") }, onShow = { choose = true },
            onLearned = {}, onDismiss = onDismiss)
    }
}

@Composable
internal fun BlockedHost(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var choose by remember { mutableStateOf(false) }
    val installed = remember { InstalledApps(context).everything() }
    val builtIn = remember { com.past9.phoneaos.screen.ScreenBlocklist(Screens.builtInBlocklist(context)) }
    val added = remember(tick) { Screens.blockAdded(context).map { it.substringBefore('|').trim() to it.substringAfter('|', "").trim() } }
    if (choose) ChoiceSheet(Icons.Rounded.Shield, "Blocked apps", "Block which app?",
        installed.filter { a -> builtIn.blocked(a.pkg, a.label) == null && added.none { it.first == a.pkg } }.sortedBy { it.label.lowercase() }.map { SheetChoice(it.pkg, it.label, it.pkg) },
        onPick = { c -> Screens.blockAdd(context, c.id, c.title); tick++; choose = false }, onDismiss = { choose = false }, line = "Your agent will not open, see or touch it on the hidden screen.")
    else ScreenBlockedSheet(installed.filter { builtIn.blocked(it.pkg, it.label) != null }.map { it.label }.distinct().sorted(), added, onAdd = { choose = true },
        onRemove = { Screens.blockRemoveAdded(context, it); tick++ }, onDismiss = onDismiss)
}

/** The person saving what is on the run so far. Their own yes is the tap on Save. */
@Composable
internal fun SaveSkillHost(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val g = remember { com.past9.phoneaos.App.graph(context) }
    val session = remember { Screens.session(context) }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf("") }
    val run = remember { session.runSteps }
    val doing = remember(run) { run.filter { it.kind in com.past9.phoneaos.screen.ScreenSkills.DOING } }
    val typed = remember(doing) { doing.filter { it.kind == "type" && !it.secret && it.text.isNotBlank() }.map { it.text }.distinct() }
    ScreenSaveSkillSheet(session.state.collectAsState().value.app, doing.map { it.say }, typed, error, onSave = { name, what, inputs ->
        scope.launch {
            val out = ScreenLearned.save(session, g.runtime.learned, InstalledApps(context), Screens.config(context).blocklist, YesContext, name, what, inputs, readOnly = false, by = "you")
            if (out.startsWith("Saved")) { com.past9.phoneaos.ui.AppNotice.say("Saved as a learned app"); onDismiss() } else error = out
        }
    }, onDismiss = onDismiss)
}

/** The person is the one tapping: nothing to ask them twice. */
internal object YesContext : com.past9.phoneaos.agent.ToolContext {
    override val agentLabel = "you"
    override suspend fun activity(text: String, meta: org.json.JSONObject): Long = 0
    override suspend fun updateActivity(id: Long, text: String, meta: org.json.JSONObject) {}
    override suspend fun ask(question: String, options: List<String>): String? = "Approve"
    override suspend fun notify(title: String, body: String) {}
}

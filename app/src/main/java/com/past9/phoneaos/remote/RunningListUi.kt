package com.past9.phoneaos.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.past9.phoneaos.R
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.past9.phoneaos.ui.AgentAvatar
import com.past9.phoneaos.ui.HelperCard
import com.past9.phoneaos.ui.HelperSheet
import com.past9.phoneaos.ui.ProjectIconTile
import com.past9.phoneaos.ui.StatusPill
import com.past9.phoneaos.ui.theme.Eyebrow
import com.past9.phoneaos.ui.theme.Radius
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Whether the running list is folded: ONE choice for the whole app, kept on the device, so a tap on any screen folds
 * it on every screen (the main chat, a project chat's header, Home).
 */
class RunningFold(private val prefs: SharedPreferences?) {
    var choice by mutableStateOf(prefs?.let { p -> if (p.contains(KEY)) p.getBoolean(KEY, false) else p.getString(AgentsFold.KEY, "") == AgentsFold.CLOSED } ?: false)
        private set
    /** A look at what just finished while nothing runs. Not kept. */
    var peek by mutableStateOf(false)
        private set
    /** The groups he folded (a project's id, "main", "other"), kept on the device like the fold itself. */
    var groups by mutableStateOf<Set<String>>(prefs?.getStringSet(GROUPS, null)?.toSet() ?: emptySet())
        private set
    fun toggleGroup(key: String) {
        groups = if (key in groups) groups - key else groups + key
        prefs?.edit()?.putStringSet(GROUPS, groups)?.apply()
    }
    fun collapsed(n: RunCounts) = RunningList.collapsed(choice, n, peek)
    fun set(collapsed: Boolean) {
        choice = collapsed
        prefs?.edit()?.putBoolean(KEY, collapsed)?.apply()
    }
    /** One tap: fold or open. With nothing running it only peeks, so his choice for running work is not lost. */
    fun toggle(n: RunCounts) { if (n.running == 0) peek = !peek else { peek = false; set(!choice) } }
    companion object {
        const val KEY = "running_collapsed"
        const val GROUPS = "running_groups_folded"
        @Volatile private var shared: RunningFold? = null
        fun shared(ctx: Context): RunningFold = shared ?: synchronized(this) {
            shared ?: RunningFold(ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)).also { shared = it }
        }
    }
}

@Composable
fun rememberRunningFold(): RunningFold {
    val ctx = LocalContext.current
    return remember { RunningFold.shared(ctx) }
}

/**
 * The computer's projects and which chats run or wait, for the running list and the switcher. Read once when a screen
 * that shows them opens, then kept current from the link's own events (a project row changed, a run started or
 * ended). Never polled.
 */
object RunningFeed {
    var projects by mutableStateOf<List<ProjectInfo>>(emptyList())
        private set
    var states by mutableStateOf<Map<String, ChatState>>(emptyMap())
        private set
    @Volatile private var staleProjects = true
    @Volatile private var staleStates = true

    private suspend fun read(s: RemoteSession) {
        if (staleProjects) runCatching { s.call("data.list", listOf("project"), 30_000) as? JSONArray }.getOrNull()?.let { projects = RunningList.projects(it); staleProjects = false }
        if (staleStates) runCatching { s.call("chat.states", listOf(), 15_000) as? JSONArray }.getOrNull()?.let { states = RunningList.states(it); staleStates = false }
    }

    /** What an event makes stale: 1 the projects, 2 the chat states, 0 nothing. */
    fun touches(event: String, payload: Any?): Int {
        val p = payload as? JSONObject
        return when (event) {
            RemoteSession.EVT_DATA -> if (p?.optString("entity") == "project") 1 else 0
            RemoteSession.EVT_CHAT -> if (p?.optString("type") in setOf("run_started", "run_finished", "chat_changed")) 2 else 0
            else -> 0
        }
    }

    @Composable
    fun Follow() {
        val s = RemoteHub.session.collectAsStateWithLifecycle().value
        LaunchedEffect(s) {
            if (s == null) { projects = emptyList(); states = emptyMap(); staleProjects = true; staleStates = true; return@LaunchedEffect }
            staleProjects = true; staleStates = true
            read(s)
            s.events.collectLatest { (event, payload) ->
                when (touches(event, payload)) { 1 -> staleProjects = true; 2 -> staleStates = true; else -> return@collectLatest }
                // A burst of changes is read once.
                delay(if (s.metered) 900 else 350)
                read(s)
            }
        }
    }
}

/** The header every running block has: what is going on in one line, and one tap to fold or open. */
@Composable
fun RunningHeader(n: RunCounts, collapsed: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, opensUp: Boolean = false,
                  container: Color = MaterialTheme.colorScheme.surfaceContainerHigh, content: Color = MaterialTheme.colorScheme.onSurface) {
    val cs = MaterialTheme.colorScheme
    val line = RunningList.header(n)
    val desc = stringResource(if (collapsed) R.string.link_running_show_list else R.string.link_running_hide_list, line)
    Surface(onClick = onToggle, shape = RoundedCornerShape(Radius.md), color = container, contentColor = content,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = desc }) {
        Row(Modifier.padding(start = 14.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.link_running), style = Eyebrow, color = if (n.needsYou > 0) cs.tertiary else cs.primary)
                Text(line, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val up = if (opensUp) collapsed else !collapsed
            Icon(if (up) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null, Modifier.size(22.dp), tint = content.copy(alpha = 0.7f))
        }
    }
}

/** A group's own line: the project (or the main chat) and what is going on there. One tap folds or opens the group. */
@Composable
private fun GroupHead(g: RunGroup, folded: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val n = g.counts
    val line = if (n.running == 0 && n.needsYou == 0) "" else RunningList.header(n)
    val desc = stringResource(if (folded) R.string.link_group_show else R.string.link_group_hide, g.name + (if (line.isBlank()) "" else ", $line"))
    Surface(onClick = onToggle, shape = RoundedCornerShape(Radius.sm), color = Color.Transparent, contentColor = cs.onSurface,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = desc }) {
        Row(Modifier.padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (g.isMain || g.key == RunningList.OTHER) Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { AgentAvatar(working = false, size = 20.dp) }
            else {
                val icon = g.cards.firstOrNull()
                ProjectIconTile(g.name, g.project?.glyph ?: icon?.ownerGlyph.orEmpty(), g.project?.color ?: icon?.ownerColor ?: "iris", g.project?.image ?: icon?.ownerImage.orEmpty(), 22.dp)
            }
            Spacer(Modifier.width(8.dp))
            Text(g.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (line.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(line, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1)
            }
            Icon(if (folded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp, null, Modifier.padding(start = 4.dp).size(18.dp), tint = cs.onSurfaceVariant)
        }
    }
}

/** The line under a parent that holds the helpers it started: closed until he taps it, and not kept. */
@Composable
private fun UnderRow(n: Int, open: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = onToggle, shape = RoundedCornerShape(Radius.sm), color = Color.Transparent, contentColor = cs.onSurfaceVariant, modifier = Modifier.padding(start = 22.dp)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(RunningList.under(n), style = MaterialTheme.typography.labelLarge)
            Icon(if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null, Modifier.padding(start = 4.dp).size(18.dp))
        }
    }
}

/**
 * The running list: ONE list of all running work across all projects, grouped by project with [current] (a project's
 * id or one of its chats, or "main") first. The header folds it with one tap and the choice holds on every screen. A
 * card that needs him stays in view when folded. [only] draws it just when folded (true) or just when open (false),
 * for a screen that puts the two in different places. Nothing to show draws nothing.
 */
@Composable
fun RunningBlock(ui: LiveCardsUi, modifier: Modifier = Modifier, current: String = RunningList.MAIN, only: Boolean? = null, maxHeight: Dp? = null,
                 opensUp: Boolean = false, onToggled: (collapsed: Boolean) -> Unit = {},
                 projects: List<ProjectInfo>? = null, states: Map<String, ChatState>? = null, fold: RunningFold = rememberRunningFold()) {
    if (projects == null || states == null) RunningFeed.Follow()
    if (ui.cards.isEmpty()) return
    val ps = projects ?: RunningFeed.projects
    val st = states ?: RunningFeed.states
    val counts = remember(ui.cards, st) { RunningList.counts(ui.cards, st) }
    val collapsed = fold.collapsed(counts)
    if (only != null && only != collapsed) return
    val groups = remember(ui.cards, ps, st, current) { RunningList.groups(ui.cards, ps, current, st) }
    var openRow by rememberSaveable { mutableStateOf<String?>(null) }
    var showFinished by rememberSaveable { mutableStateOf(false) }
    // The "for 4 min" labels move on their own.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val cs = MaterialTheme.colorScheme
    // With nothing running the open list is a look at what just finished.
    val finished = showFinished || counts.running == 0
    // Which parents show the helpers under them: closed by default, and not kept.
    var openUnder by remember { mutableStateOf(emptySet<String>()) }
    @Composable fun card(c: LiveCard, sub: Boolean) {
        key(c.row) {
            Column(if (sub) Modifier.padding(start = 22.dp) else Modifier) {
                // Whoever needs him says so, through any fold.
                if (RunningList.needsYou(c, st)) StatusPill(stringResource(R.string.home_needs_you), cs.tertiaryContainer, cs.onTertiaryContainer, Modifier.padding(start = 4.dp, bottom = 4.dp))
                LiveSwipeCard(c, onSwiped = { ui.dismiss(c) }) {
                    HelperCard(ui.view(c), onClick = {
                        when (val t = tapOf(c)) {
                            is CardTap.Chat -> ui.openChat(t.chat, t.name)
                            is CardTap.Sheet -> openRow = t.row
                        }
                    }, compact = true, iconSize = if (sub) 28.dp else 32.dp, now = now)
                }
            }
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RunningHeader(counts, collapsed, onToggle = { fold.toggle(counts); onToggled(fold.collapsed(counts)) }, opensUp = opensUp)
        val body = if (maxHeight != null) Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()) else Modifier
        if (collapsed) {
            val needing = RunningList.shown(groups, true, st)
            if (needing.isNotEmpty()) Column(body.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) { needing.forEach { card(it, false) } }
        } else Column(body.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            groups.forEach { g ->
                if (RunningList.shown(g, false, st, finished).isNotEmpty()) {
                    val gFolded = g.key in fold.groups
                    GroupHead(g, gFolded) { fold.toggleGroup(g.key) }
                    if (gFolded) RunningList.shown(g, true, st).forEach { card(it, false) }
                    else g.clusters.forEach { cl ->
                        if (finished || RunningList.active(cl, st)) {
                            val head = cl.first()
                            val subs = cl.drop(1)
                            card(head, false)
                            if (subs.isNotEmpty()) {
                                val open = head.row in openUnder
                                UnderRow(subs.size, open) { openUnder = if (open) openUnder - head.row else openUnder + head.row }
                                subs.forEach { if (open || RunningList.needsYou(it, st)) card(it, true) }
                            }
                        }
                    }
                }
            }
            val more = RunningList.finishedCount(groups, st)
            if (more > 0 && counts.running > 0) TextButton(onClick = { showFinished = !showFinished }, modifier = Modifier.padding(start = 4.dp)) {
                Text(if (showFinished) stringResource(R.string.link_hide_finished) else stringResource(R.string.link_more_finished, more), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    val open = ui.cards.firstOrNull { it.row == openRow }
    if (openRow != null && open == null) openRow = null
    if (open != null) HelperSheet(ui.view(open), onStop = { ui.stop(open) }, onDismiss = { openRow = null }, now = now,
        onGoToChat = { openRow = null; ui.openChat(open.homeChat, if (open.homeChat == "main") "" else open.ownerName.ifBlank { open.label }) })
}

/**
 * The same list as a few lines inside Home's main agent card: the header (the same fold), then when open each
 * project with how many are running there, and always who needs him. It reads the linked computer itself, so Home
 * only places it; with no computer linked or nothing going on it draws nothing.
 */
@Composable
fun HomeRunning(modifier: Modifier = Modifier, cards: List<LiveCard>? = null, projects: List<ProjectInfo>? = null, states: Map<String, ChatState>? = null,
                fold: RunningFold = rememberRunningFold()) {
    val session = RemoteHub.session.collectAsStateWithLifecycle().value
    val list = cards ?: session?.cards?.collectAsStateWithLifecycle()?.value ?: return
    if (projects == null || states == null) RunningFeed.Follow()
    val ps = projects ?: RunningFeed.projects
    val st = states ?: RunningFeed.states
    val counts = remember(list, st) { RunningList.counts(list, st) }
    if (counts.running == 0 && counts.needsYou == 0) return
    val groups = remember(list, ps, st) { RunningList.groups(list, ps, RunningList.MAIN, st) }
    val collapsed = fold.collapsed(counts)
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RunningHeader(counts, collapsed, onToggle = { fold.toggle(counts) }, container = cs.onPrimaryContainer.copy(alpha = 0.08f), content = cs.onPrimaryContainer)
        if (!collapsed) groups.filter { it.working > 0 }.forEach { g ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(g.name, style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.link_working_count, g.working), style = MaterialTheme.typography.labelMedium, color = cs.onPrimaryContainer.copy(alpha = 0.75f), maxLines = 1)
            }
        }
        RunningList.shown(groups, true, st).forEach { c ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c.ownerName.ifBlank { c.label }, style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_needs_you), style = MaterialTheme.typography.labelMedium, color = cs.onPrimaryContainer, maxLines = 1)
            }
        }
    }
}

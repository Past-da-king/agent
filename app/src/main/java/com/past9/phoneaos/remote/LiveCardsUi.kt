package com.past9.phoneaos.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.ui.AgentAvatar
import com.past9.phoneaos.ui.HelperCard
import com.past9.phoneaos.ui.HelperSheet
import com.past9.phoneaos.ui.HelperView
import com.past9.phoneaos.ui.ProjectTile
import com.past9.phoneaos.ui.theme.Eyebrow
import kotlinx.coroutines.delay

/** The cards in groups: a card at depth 0 with the depth-1 cards right after it (the brain's order is kept). */
fun groupsOf(cards: List<LiveCard>): List<List<LiveCard>> {
    val out = ArrayList<MutableList<LiveCard>>()
    for (c in cards) if (c.depth == 1 && out.isNotEmpty()) out.last() += c else out += mutableListOf(c)
    return out
}

/** One place agents are working in, for the pill: a project (its first working card stands for its icon) or the main chat. */
data class PillGroup(val key: String, val card: LiveCard, val count: Int) {
    val isProject get() = key.startsWith("p:")
}

/**
 * Where the working agents are, in the brain's order: one entry per project with agents working in it, and one for
 * the main chat and its helpers, each with how many are working. Finished cards do not count.
 */
fun pillGroups(cards: List<LiveCard>): List<PillGroup> {
    val by = LinkedHashMap<String, MutableList<LiveCard>>()
    for (c in cards) if (c.working) by.getOrPut(if (c.ownerKind == "project" && c.ownerName.isNotBlank()) "p:" + c.ownerName else "main") { mutableListOf() } += c
    return by.map { (k, l) -> PillGroup(k, l.first(), l.size) }
}

/**
 * Whether the "On the computer" list is folded into the pill. His own choice wins once he has made one (kept across
 * restarts); until then it folds once more than two agents are working. Nothing working: never folded (no pill).
 */
fun foldedFor(choice: String, working: Int): Boolean = working > 0 && when (choice) {
    AgentsFold.CLOSED -> true
    AgentsFold.OPEN -> false
    else -> working > 2
}

/** The fold choice, kept in the app's settings so it survives closing the app. */
class AgentsFold(private val prefs: SharedPreferences?) {
    var choice by mutableStateOf(prefs?.getString(KEY, "") ?: "")
        private set
    fun folded(working: Int) = foldedFor(choice, working)
    fun set(open: Boolean) {
        choice = if (open) OPEN else CLOSED
        prefs?.edit()?.putString(KEY, choice)?.apply()
    }
    companion object {
        const val KEY = "agents_fold"
        const val OPEN = "open"
        const val CLOSED = "closed"
    }
}

@Composable
fun rememberAgentsFold(): AgentsFold {
    val ctx = LocalContext.current
    return remember { AgentsFold(ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)) }
}

/** What the chat needs to draw the computer's helper cards: the cards, and how to draw and stop one. */
class LiveCardsUi(val cards: List<LiveCard>, val view: (LiveCard) -> HelperView, val stop: (LiveCard) -> Unit)

/**
 * The folded list: a small pill just above the message box with each place agents are working in (the project's
 * icon, or the agent's own for the main chat) and how many are working there. A tap opens the full list again.
 */
@Composable
fun AgentsPill(ui: LiveCardsUi, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val groups = remember(ui.cards) { pillGroups(ui.cards) }
    if (groups.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val total = groups.sumOf { it.count }
    Surface(onClick = onOpen, shape = RoundedCornerShape(50), color = cs.surfaceContainerHigh,
        modifier = modifier.semantics { contentDescription = "$total working on the computer. Show them" }) {
        Row(Modifier.padding(start = 6.dp, end = 8.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            groups.forEach { g ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (g.isProject) ProjectTile(ui.view(g.card), 24.dp, spinner = false)
                    else Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { AgentAvatar(working = false, size = 22.dp) }
                    Spacer(Modifier.width(5.dp))
                    Text("${g.count}", style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
                }
            }
            Icon(Icons.Rounded.KeyboardArrowUp, null, Modifier.size(20.dp), tint = cs.onSurfaceVariant)
        }
    }
}

/**
 * The computer's helpers and project agents, at the foot of the chat while they work: small cards, a project agent's
 * card first with its helpers indented under it (the same order as the desktop side panel). Each says what it is
 * doing now; once finished, what it did. A tap opens the full card, which keeps following it live. While agents
 * work, [onCollapse] folds the list into the pill above the message box.
 */
@Composable
fun LiveCardList(ui: LiveCardsUi, modifier: Modifier = Modifier, onCollapse: (() -> Unit)? = null) {
    if (ui.cards.isEmpty()) return
    var openRow by rememberSaveable { mutableStateOf<String?>(null) }
    // The "for 4 min" labels move on their own.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val working = ui.cards.count { it.working }
    val cs = MaterialTheme.colorScheme
    // A card with the ones indented under it is one group. Groups still working always show; of the finished ones the
    // newest shows its done line and the rest fold away, so the chat never fills with old cards.
    val groups = remember(ui.cards) { groupsOf(ui.cards) }
    val (live, finished) = groups.partition { g -> g.any { it.working } }
    val newestDone = finished.maxByOrNull { g -> g.maxOf { it.endedAt } }
    val folded = finished.filter { it !== newestDone }
    var showAll by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val canCollapse = onCollapse != null && working > 0
        val head = Modifier.fillMaxWidth()
        Surface(onClick = { onCollapse?.invoke() }, enabled = canCollapse, shape = RoundedCornerShape(12.dp), color = cs.surface, modifier = head) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("ON THE COMPUTER", style = Eyebrow, color = cs.primary)
                Spacer(Modifier.weight(1f))
                Text(if (working > 0) "$working working" else "Just finished", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                if (canCollapse) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.KeyboardArrowDown, "Collapse", Modifier.size(20.dp), tint = cs.onSurfaceVariant)
                }
            }
        }
        val shown = live + listOfNotNull(newestDone) + if (showAll) folded else emptyList()
        shown.flatten().forEach { c ->
            val sub = c.depth == 1
            HelperCard(ui.view(c), onClick = { openRow = c.row }, compact = true, iconSize = if (sub) 28.dp else 32.dp, now = now,
                modifier = if (sub) Modifier.padding(start = 22.dp) else Modifier)
        }
        if (folded.isNotEmpty()) {
            val n = folded.sumOf { it.size }
            androidx.compose.material3.TextButton(onClick = { showAll = !showAll }, modifier = Modifier.padding(start = 4.dp)) {
                Text(if (showAll) "Hide finished" else "$n more finished in the last 20 min", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    val open = ui.cards.firstOrNull { it.row == openRow }
    if (openRow != null && open == null) openRow = null
    if (open != null) HelperSheet(ui.view(open), onStop = { ui.stop(open) }, onDismiss = { openRow = null }, now = now)
}

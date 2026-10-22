package com.past9.phoneaos.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.ui.HelperCard
import com.past9.phoneaos.ui.HelperSheet
import com.past9.phoneaos.ui.HelperView
import com.past9.phoneaos.ui.theme.Eyebrow
import kotlinx.coroutines.delay

/** The cards in groups: a card at depth 0 with the depth-1 cards right after it (the brain's order is kept). */
fun groupsOf(cards: List<LiveCard>): List<List<LiveCard>> {
    val out = ArrayList<MutableList<LiveCard>>()
    for (c in cards) if (c.depth == 1 && out.isNotEmpty()) out.last() += c else out += mutableListOf(c)
    return out
}

/** What the chat needs to draw the computer's helper cards: the cards, and how to draw and stop one. */
class LiveCardsUi(val cards: List<LiveCard>, val view: (LiveCard) -> HelperView, val stop: (LiveCard) -> Unit)

/**
 * The computer's helpers and project agents, at the foot of the chat while they work: small cards, a project agent's
 * card first with its helpers indented under it (the same order as the desktop side panel). Each says what it is
 * doing now; once finished, what it did. A tap opens the full card, which keeps following it live.
 */
@Composable
fun LiveCardList(ui: LiveCardsUi, modifier: Modifier = Modifier) {
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
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ON THE COMPUTER", style = Eyebrow, color = cs.primary)
            Spacer(Modifier.weight(1f))
            Text(if (working > 0) "$working working" else "Just finished", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
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

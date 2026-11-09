package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.data.TaskRow
import com.past9.phoneaos.ui.ChoiceSheet
import com.past9.phoneaos.ui.SheetChoice
import com.past9.phoneaos.ui.theme.Eyebrow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.time.ZonedDateTime

/**
 * One thing on Home that waits for the person: a card that asks (approval, question, connect an app), a step the
 * agent is blocked on, or one of his own to-dos. Always derived from the live rows, never kept: when the row is
 * answered, done or gone, the need is gone too.
 * [key] names the card (kind letter plus the row id); [sig] names this need for it, so a card he cleared comes back
 * when the same row needs him again for something new (a new blocker, a task opened again).
 */
data class Need(
    val key: String,
    /** approval | question | connect | blocked | todo */
    val kind: String,
    val title: String,
    val sig: String = "open",
    val itemId: Long? = null,
    val taskId: Long? = null,
    /** The project chat the card was asked in ("" for the main chat). */
    val chat: String = "",
    /** The helper that asks ("" when the agent itself asks). */
    val helper: String = "",
) {
    val isTask get() = taskId != null
}

/** Where a tap on a need goes. */
sealed interface NeedTarget {
    /** The chat, scrolled to that card (approvals, questions and helper cards are answered there). */
    data class ChatAt(val itemId: Long) : NeedTarget
    /** The project chat a card was asked in. */
    data class ProjectChat(val chat: String) : NeedTarget
    /** That task, opened on the Tasks screen (the agent's tab for its own steps). */
    data class Task(val taskId: Long, val agentTab: Boolean) : NeedTarget
    /** Nothing to open any more; [line] says so plainly. */
    data class Gone(val line: String) : NeedTarget
}

/** What he cleared (card key to the need's sig) and what he snoozed (card key to when it comes back). */
data class NeedHidden(val dismissed: Map<String, String> = emptyMap(), val snoozed: Map<String, Long> = emptyMap()) {
    fun hides(n: Need, now: Long) = dismissed[n.key] == n.sig || (snoozed[n.key] ?: 0L) > now
}

object Needs {
    const val HANDLED = "This was already handled"
    const val MISSING = "This is no longer there"

    private fun meta(i: ChatItem) = runCatching { JSONObject(i.meta) }.getOrDefault(JSONObject())
    private fun waiting(i: ChatItem) = i.kind == "question" && !meta(i).has("answer")

    /** The cards and blocked steps that need him, from the live rows, oldest ask first. */
    fun needs(items: List<ChatItem>, tasks: List<TaskRow>): List<Need> =
        items.filter { waiting(it) }.map { q ->
            val m = meta(q)
            val connect = com.past9.phoneaos.tools.ConnectRequest.parse(q.text)
            val kind = when { connect != null -> "connect"; q.text.startsWith("APPROVAL|") -> "approval"; else -> "question" }
            val title = when (kind) { "connect" -> connect!!.summary; "approval" -> q.text.split("|", limit = 3).getOrElse(1) { "" }; else -> q.text }
            Need("q${q.id}", kind, title.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim(), itemId = q.id,
                chat = m.optString("chat").takeIf { it != "main" }.orEmpty(), helper = m.optString("helper"))
        } + tasks.filter { it.status == "blocked" && it.blocker.isNotBlank() && it.owner == "agent" }.map { t -> Need("b${t.id}", "blocked", t.title, sig = t.blocker, taskId = t.id) }

    /** His open to-dos, soonest first. */
    fun todos(tasks: List<TaskRow>): List<Need> = tasks.filter { it.owner == "user" && it.status != "done" }
        .sortedWith(compareBy({ it.dueAt ?: Long.MAX_VALUE }, { it.createdAt })).map { t -> Need("t${t.id}", "todo", t.title, taskId = t.id) }

    fun visible(all: List<Need>, hidden: NeedHidden, now: Long) = all.filterNot { hidden.hides(it, now) }

    /** Where a tap goes, checked against the rows as they are now: a row that is answered, done or deleted gives [NeedTarget.Gone]. */
    fun target(n: Need, items: List<ChatItem>, tasks: List<TaskRow>): NeedTarget {
        if (n.isTask) {
            val t = tasks.firstOrNull { it.id == n.taskId } ?: return NeedTarget.Gone(MISSING)
            if (t.status == "done" || (n.kind == "blocked" && t.status != "blocked")) return NeedTarget.Gone(HANDLED)
            return NeedTarget.Task(t.id, agentTab = t.owner == "agent")
        }
        val q = items.firstOrNull { it.id == n.itemId } ?: return NeedTarget.Gone(MISSING)
        return if (waiting(q)) NeedTarget.ChatAt(q.id) else NeedTarget.Gone(HANDLED)
    }

    /** The project chat a card came from, when it came from one. */
    fun projectTarget(n: Need): NeedTarget.ProjectChat? = n.chat.takeIf { it.isNotBlank() }?.let { NeedTarget.ProjectChat(it) }

    /** When a snoozed card comes back; null when that choice makes no sense now (this evening, after 18:00). */
    fun snoozeUntil(choice: String, now: ZonedDateTime = ZonedDateTime.now()): Long? = when (choice) {
        "hour" -> now.plusHours(1)
        "evening" -> now.toLocalDate().atTime(18, 0).atZone(now.zone).takeIf { it.isAfter(now) }
        "morning" -> now.toLocalDate().plusDays(1).atTime(8, 0).atZone(now.zone)
        else -> null
    }?.toInstant()?.toEpochMilli()

    fun kindLabel(n: Need) = when (n.kind) {
        "approval" -> "Approval"; "connect" -> "Connect an app"; "blocked" -> "Blocked step"; "todo" -> "Your to-do"
        else -> if (n.helper.isNotBlank()) "A helper asks" else "Question"
    }

    /** The rows of the card's sheet: open it, tick it off (tasks only), put it off, or clear it. */
    fun choices(n: Need, now: ZonedDateTime = ZonedDateTime.now()): List<SheetChoice> = listOfNotNull(
        SheetChoice("open", "Open", when { n.kind == "todo" -> "See it on your list"; n.kind == "blocked" -> "See the step and what it needs"; else -> "Go to it in the chat" }, Icons.AutoMirrored.Rounded.OpenInNew),
        SheetChoice("project", "Open the project chat", "Where this was asked", Icons.Rounded.Forum).takeIf { projectTarget(n) != null },
        SheetChoice("done", "Done", "Tick it off", Icons.Rounded.CheckCircle).takeIf { n.isTask },
        SheetChoice("hour", "Snooze for 1 hour", "Hide it until then", Icons.Rounded.Snooze),
        SheetChoice("evening", "Snooze until this evening", "Back at 18:00", Icons.Rounded.NightsStay).takeIf { snoozeUntil("evening", now) != null },
        SheetChoice("morning", "Snooze until tomorrow morning", "Back at 08:00", Icons.Rounded.WbSunny),
        SheetChoice("drop", "Not needed", if (n.isTask) "Clear it from Home without ticking it off" else "Clear it from Home without answering", Icons.Rounded.Close),
    )
}

/**
 * What he cleared and snoozed on Home, kept on the phone so it survives a restart and a fresh sync never brings a
 * cleared card back. [load] and [save] are the storage (a preferences string on the phone).
 */
class NeedStore(load: () -> String? = { null }, private val save: (String) -> Unit = {}) {
    val hidden = MutableStateFlow(parse(load()))

    fun dismiss(n: Need) = put(hidden.value.copy(dismissed = hidden.value.dismissed + (n.key to n.sig)))
    fun snooze(n: Need, until: Long) = put(hidden.value.copy(snoozed = hidden.value.snoozed + (n.key to until)))

    /**
     * Forget what no longer applies: a snooze that ran out, and a cleared card whose row is closed (a task ticked off,
     * a card answered), so the same row shows again if it is opened again. A row that is simply not loaded yet keeps
     * its entry: an empty list at start-up must never bring cleared cards back.
     */
    fun prune(items: List<ChatItem>, tasks: List<TaskRow>, now: Long) {
        val h = hidden.value
        fun closed(key: String): Boolean {
            val id = key.drop(1).toLongOrNull() ?: return true
            return when (key.first()) {
                'q' -> items.firstOrNull { it.id == id }?.let { runCatching { JSONObject(it.meta).has("answer") }.getOrDefault(false) } ?: false
                't' -> tasks.firstOrNull { it.id == id }?.let { it.status == "done" } ?: false
                'b' -> tasks.firstOrNull { it.id == id }?.let { it.status != "blocked" } ?: false
                else -> true
            }
        }
        val next = NeedHidden(h.dismissed.filterKeys { !closed(it) }, h.snoozed.filter { (k, until) -> until > now && !closed(k) })
        if (next != h) put(next)
    }

    private fun put(h: NeedHidden) { hidden.value = h; save(JSONObject().put("dismissed", JSONObject(h.dismissed as Map<*, *>)).put("snoozed", JSONObject(h.snoozed as Map<*, *>)).toString()) }

    companion object {
        fun parse(s: String?): NeedHidden = runCatching {
            val o = JSONObject(s ?: "{}")
            val d = o.optJSONObject("dismissed") ?: JSONObject(); val z = o.optJSONObject("snoozed") ?: JSONObject()
            NeedHidden(d.keys().asSequence().associateWith { d.optString(it) }, z.keys().asSequence().associateWith { z.optLong(it) })
        }.getOrDefault(NeedHidden())

        fun of(context: android.content.Context): NeedStore {
            val p = context.getSharedPreferences("home_needs", android.content.Context.MODE_PRIVATE)
            return NeedStore({ p.getString("hidden", null) }, { p.edit().putString("hidden", it).apply() })
        }
    }
}

/** A tap on Home asks the chat to scroll to one card; the chat takes it once it has drawn. */
object ChatFocus { val id = MutableStateFlow<Long?>(null) }

/** A tap on Home asks the Tasks screen to open one task; the screen takes it when it opens. */
object TaskFocus {
    @Volatile var target: NeedTarget.Task? = null
    fun take(): NeedTarget.Task? = target.also { target = null }
}

/** A Home card that swipes away either way, with what the swipe does behind it. */
@Composable
fun NeedSwipe(label: String, onCleared: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    val cs = MaterialTheme.colorScheme
    SwipeToDismissBox(
        state = state,
        modifier = modifier.semantics { contentDescription = "$label. Swipe to clear it, hold for more" },
        onDismiss = { onCleared() },
        backgroundContent = {
            val toStart = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            // Only while it moves: a card with rounder corners must not show this behind its corners at rest.
            if (state.dismissDirection != SwipeToDismissBoxValue.Settled) Surface(shape = MaterialTheme.shapes.large, color = cs.surfaceContainerHighest, modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (toStart) Arrangement.End else Arrangement.Start) {
                    Icon(Icons.Rounded.Close, null, Modifier.size(20.dp), tint = cs.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text("Clear", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                }
            }
        },
    ) { content() }
}

/** In place of a card whose target is gone: what happened, and a way to clear it. Never an empty screen. */
@Composable
fun GoneNeedCard(title: String, line: String, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.TaskAlt, null, Modifier.size(22.dp), tint = cs.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(line, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Dismiss") }
        }
    }
}

/** The agent's step that waits on him, with a way to the card's sheet. */
@Composable
fun BlockedNeedCard(t: TaskRow, onMore: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = cs.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("BLOCKED", style = Eyebrow, color = cs.onTertiaryContainer)
                Text(t.title, style = MaterialTheme.typography.titleSmall, color = cs.onTertiaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Needs: ${t.blocker}", style = MaterialTheme.typography.bodyMedium, color = cs.onTertiaryContainer, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, "More", tint = cs.onTertiaryContainer) }
        }
    }
}

/** One quiet line when nothing waits for him. */
@Composable
fun NothingNeedsYou(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text("Nothing needs you right now.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    }
}

/** The slide-up sheet of one Home card: open it, tick it off, snooze it, or clear it. */
@Composable
fun NeedSheet(n: Need, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ChoiceSheet(if (n.isTask) Icons.Rounded.TaskAlt else Icons.Rounded.NotificationsActive, Needs.kindLabel(n), n.title.let { if (it.length > 60) it.take(57).trimEnd() + "..." else it },
        Needs.choices(n), onPick = { onPick(it.id) }, onDismiss = onDismiss)
}

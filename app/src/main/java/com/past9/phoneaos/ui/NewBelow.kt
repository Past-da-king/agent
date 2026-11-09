package com.past9.phoneaos.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.data.ChatItem
import org.json.JSONObject

/**
 * What counts as a NEW MESSAGE below where he is reading (the pill over the chat and the "new messages" divider).
 *
 * The computer sends no count. The phone gets rows, and the SAME row arrives again on every write (a tool step going
 * from running to done, a helper card changing state, an approval being decided). So the number is: distinct rows that
 * were not in the chat when he left the bottom, and that are messages. Tool steps, helper cards, questions, approvals,
 * notices, dividers, calls and branches are never messages. The same rule serves the main chat and project chats.
 */
object NewBelow {
    /** The message kinds. The computer says voice_note; the phone stores the same row as voice. */
    val MESSAGE_KINDS = setOf("user", "agent", "report", "voice_note", "voice", "media")

    /** Is this row a message a person would count? */
    fun isMessageRow(kind: String, text: String, meta: String): Boolean {
        if (kind !in MESSAGE_KINDS) return false
        val m = runCatching { JSONObject(meta.ifBlank { "{}" }) }.getOrNull() ?: JSONObject()
        // A helper's own chatter lives inside its card.
        if (m.has("helper")) return false
        // An agent row with nothing to read yet (it is still being written, or it only carried tool calls).
        if (kind == "agent" && text.isBlank() && !m.has("visual")) return false
        return true
    }

    fun isMessageRow(item: ChatItem): Boolean = isMessageRow(item.kind, item.text, item.meta)

    /** A row as the computer sends it (a project chat reads these as they are). */
    fun isMessageRow(row: JSONObject): Boolean = isMessageRow(row.optString("kind"), row.optString("text"), row.optString("meta"))

    /**
     * How many new messages sit below him: distinct ids not in [seen] (the ids in the chat when he left the bottom)
     * that are message rows. Rows above the first one he had seen are older history loaded later, not new.
     */
    fun <T, K> countNew(rows: List<T>, seen: Set<K>, id: (T) -> K, isMessage: (T) -> Boolean): Int {
        val firstSeen = if (seen.isEmpty()) -1 else rows.indexOfFirst { id(it) in seen }
        val start = if (seen.isEmpty()) 0 else if (firstSeen < 0) 0 else firstSeen
        val fresh = HashSet<K>()
        for (i in start until rows.size) { val r = rows[i]; val k = id(r); if (k !in seen && isMessage(r)) fresh += k }
        return fresh.size
    }

    fun countNewMessages(items: List<ChatItem>, seenIds: Set<Long>): Int = countNew(items, seenIds, { it.id }, { isMessageRow(it) })

    /** The same count over a project chat's rows (the computer's own ids). */
    fun countNewRows(rows: List<JSONObject>, seenIds: Set<String>): Int = countNew(rows, seenIds, { it.optString("id") }, { isMessageRow(it) })

    /** Messages from anyone but him after [readMark], for the divider a chat opens at. */
    fun unreadAfter(items: List<ChatItem>, readMark: Long): List<ChatItem> =
        items.filter { it.id > readMark && it.kind != "user" && isMessageRow(it) }.distinctBy { it.id }

    /** What the pill says. */
    fun label(count: Int): String = when {
        count <= 0 -> "Jump to latest"
        count == 1 -> "1 new message"
        else -> "$count new messages"
    }
}

/** The pill over a chat while he reads older messages: how many new ones are below, or a quiet way down. */
@Composable
fun NewBelowPill(visible: Boolean, count: Int, onClick: () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
        ExtendedFloatingActionButton(
            onClick = onClick,
            icon = { Icon(Icons.Rounded.KeyboardArrowDown, null) },
            text = { Text(NewBelow.label(count)) },
            modifier = Modifier.padding(bottom = 12.dp),
            // New messages speak up; a plain way down stays quiet.
            containerColor = if (count > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (count > 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

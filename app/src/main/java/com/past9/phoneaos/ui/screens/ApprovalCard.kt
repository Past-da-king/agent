package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.R
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.ui.AgentSheet
import com.past9.phoneaos.ui.AppCard
import com.past9.phoneaos.ui.Markdown
import com.past9.phoneaos.ui.SheetHead
import com.past9.phoneaos.ui.theme.Eyebrow
import com.past9.phoneaos.ui.theme.LocalExtra
import org.json.JSONObject

/**
 * An approval as a person reads it: what will happen ([summary]), on what ([target]), the whole content ([details],
 * never trimmed), who asked, and what was decided. One shape for both paths: the computer's approval rows
 * (meta summary and details, decided through chat.decide) and the phone's own agent ("APPROVAL|what|details").
 */
data class Approval(
    val id: Long,
    val summary: String,
    val details: String,
    val target: String,
    val askedBy: String,
    val why: String,
    /** "" while it waits, else the answer given ("Approve", "Decline", or the words of another answer). */
    val answer: String,
    /** The last decision did not reach the computer: the buttons stay and the card says so. */
    val failed: Boolean,
) {
    val pending get() = answer.isEmpty()
    val approved get() = answer == "Approve"
    val isCommand get() = details.startsWith("$ ")
    val decided get() = when { approved -> "Approved"; answer == "Decline" -> "Declined"; else -> "Answered" }

    companion object {
        const val FAILED = "That did not go through. Try again."
        /** Meta key the session sets when a decision could not be delivered. */
        const val FAILED_KEY = "decideFailed"

        fun isApproval(item: ChatItem) = item.kind == "approval" || item.text.startsWith("APPROVAL|")

        fun of(item: ChatItem): Approval {
            val m = runCatching { JSONObject(item.meta) }.getOrNull() ?: JSONObject()
            val parts = if (item.text.startsWith("APPROVAL|")) item.text.split("|", limit = 3) else listOf("", item.text, "")
            // The computer's own words win: the text form swaps a '|' in the summary so it can be split.
            val summary = m.optString("summary").ifBlank { parts.getOrElse(1) { "" } }.trim().replaceFirstChar { it.uppercase() }
            val details = m.optString("details").ifBlank { parts.getOrElse(2) { "" } }
            val by = m.optString("by").ifBlank { m.optString("whyBy") }
            val chat = m.optString("chat")
            val askedBy = when {
                by.isNotBlank() -> by
                chat.isNotBlank() && chat != "main" -> "A project chat"
                else -> "Your agent"
            }
            // A helper's card carries a stock reason; only a reason somebody wrote is worth a line.
            val why = m.optString("why").takeIf { it.isNotBlank() && it != "a helper is waiting on this" }.orEmpty()
            // What it acts on, when the row says: a named target, else where it runs (the computer's scope).
            val target = listOf("target", "account", "app", "device").firstNotNullOfOrNull { k -> m.optString(k).takeIf { it.isNotBlank() } }
                ?: when (val scope = m.optString("scope")) { "" -> ""; "computer" -> "your computer"; "phone" -> "this phone"; else -> scope }
            return Approval(item.id, summary, details, target, askedBy, why, m.optString("answer"), m.optBoolean(FAILED_KEY))
        }
    }
}

/**
 * Nothing goes out in the user's name without this card. It says what is being approved (the action, the target and
 * the start of the content) and a tap opens [ApprovalSheet], where ALL of it can be read before Approve or Decline.
 * Once decided it folds to one line that says what was decided; a tap still opens the details.
 */
@Composable
fun ApprovalCard(item: ChatItem, onAnswer: (Long, String) -> Unit) {
    val a = remember(item.id, item.text, item.meta) { Approval.of(item) }
    var open by remember(item.id) { mutableStateOf(false) }
    var sent by remember(item.id) { mutableStateOf(false) }
    // His decision landed: the sheet has done its job. If it did not go through, the card says so and keeps its buttons.
    LaunchedEffect(a.answer, a.failed) { if (sent && !a.pending) { open = false; sent = false } else if (a.failed) sent = false }
    val cs = MaterialTheme.colorScheme
    if (!a.pending) {
        Surface(onClick = { open = true }, shape = MaterialTheme.shapes.medium, color = cs.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 48.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(com.past9.phoneaos.ui.theme.Radius.sm)).background(if (a.approved) LocalExtra.current.successContainer else cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                Icon(if (a.approved) Icons.Rounded.Check else Icons.Rounded.Close, null, Modifier.size(16.dp), tint = if (a.approved) LocalExtra.current.success else cs.onSurfaceVariant)
            }
            Spacer(Modifier.width(10.dp))
            Text(a.summary, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(decidedLabel(a), style = MaterialTheme.typography.labelMedium, color = if (a.approved) LocalExtra.current.success else cs.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
            Icon(com.past9.phoneaos.ui.ChevronEnd, stringResource(R.string.chat_show_what_was, decidedLabel(a).lowercase()), tint = cs.onSurfaceVariant)
        }
        }
    } else AppCard(container = cs.tertiaryContainer, onClick = { open = true }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (a.isCommand) Icons.Rounded.Terminal else Icons.Rounded.Shield, null, tint = cs.onTertiaryContainer, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.chat_approve_before), style = Eyebrow, color = cs.onTertiaryContainer)
        }
        Spacer(Modifier.height(8.dp))
        Text(a.summary, style = MaterialTheme.typography.titleLarge, color = cs.onTertiaryContainer)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.chat_asked_by, shown(a.askedBy)) + if (a.target.isNotBlank()) stringResource(R.string.chat_on_target, shown(a.target)) else "", style = MaterialTheme.typography.bodySmall, color = cs.onTertiaryContainer.copy(alpha = 0.8f))
        if (a.details.isNotBlank()) { Spacer(Modifier.height(12.dp)); ApprovalBody(a.details, full = false) }
        if (a.failed) { Spacer(Modifier.height(10.dp)); Text(stringResource(R.string.chat_approval_failed), style = MaterialTheme.typography.bodyMedium, color = cs.error) }
        Spacer(Modifier.height(14.dp))
        Button(onClick = { open = true }, modifier = Modifier.fillMaxWidth().height(52.dp), shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.chat_review), style = MaterialTheme.typography.titleMedium)
        }
    }
    if (open) ApprovalSheet(a, onDecide = { sent = true; onAnswer(item.id, it) }, onDismiss = { open = false })
}

/** The sheet for a chat row, for screens that draw their own card (Home, a call). */
@Composable
fun ApprovalSheet(item: ChatItem, onAnswer: (Long, String) -> Unit, onDismiss: () -> Unit) {
    val a = remember(item.id, item.text, item.meta) { Approval.of(item) }
    var sent by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(a.answer) { if (sent && !a.pending) onDismiss() }
    ApprovalSheet(a, onDecide = { sent = true; onAnswer(item.id, it) }, onDismiss = onDismiss)
}

/**
 * The whole approval, slid up from the bottom: the action as the title, who asked, the target, and every word of the
 * content scrolling inside. Approve and Decline are pinned under it, so they never scroll away. After a decision it
 * shows the same content with what was decided.
 */
@Composable
fun ApprovalSheet(a: Approval, onDecide: (String) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AgentSheet(onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                SheetHead(if (a.isCommand) Icons.Rounded.Terminal else Icons.Rounded.Shield, if (a.pending) stringResource(R.string.chat_approve_before_title) else decidedLabel(a), a.summary)
                Spacer(Modifier.height(20.dp))
                Fact(stringResource(R.string.chat_fact_asked_by), shown(a.askedBy))
                if (a.why.isNotBlank()) Fact(stringResource(R.string.chat_fact_why), a.why)
                if (a.target.isNotBlank()) Fact(stringResource(R.string.chat_fact_on), shown(a.target))
                if (a.details.isNotBlank()) {
                    Text(if (a.isCommand) stringResource(R.string.chat_command) else stringResource(R.string.chat_what_it_will_do), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    ApprovalBody(a.details, full = true)
                }
                Spacer(Modifier.height(16.dp))
            }
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 20.dp).navigationBarsPadding()) {
                if (a.pending) {
                    if (a.failed) { Text(stringResource(R.string.chat_approval_failed), style = MaterialTheme.typography.bodyMedium, color = cs.error); Spacer(Modifier.height(10.dp)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { onDecide("Decline") }, modifier = Modifier.weight(1f).height(56.dp), shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(R.string.chat_decline), style = MaterialTheme.typography.titleMedium)
                        }
                        Button(onClick = { onDecide("Approve") }, modifier = Modifier.weight(1.4f).height(56.dp), shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(R.string.chat_approve), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                } else Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (a.approved) Icons.Rounded.Check else Icons.Rounded.Close, null, Modifier.size(20.dp), tint = if (a.approved) LocalExtra.current.success else cs.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(when { a.approved -> stringResource(R.string.chat_you_approved); a.answer == "Decline" -> stringResource(R.string.chat_you_declined); else -> stringResource(R.string.chat_you_answered_x, a.answer) },
                        style = MaterialTheme.typography.titleMedium, color = if (a.approved) LocalExtra.current.success else cs.onSurfaceVariant)
                }
            }
        }
    }
}

/** [Approval.decided] in the phone's language; the English stays in the model for its tests. */
@Composable
private fun decidedLabel(a: Approval) = when { a.approved -> stringResource(R.string.chat_approved); a.answer == "Decline" -> stringResource(R.string.chat_declined); else -> stringResource(R.string.chat_answered) }

/** The stock words [Approval.of] fills in, in the phone's language; names from the row are shown as they are. */
@Composable
private fun shown(s: String) = when (s) {
    "Your agent" -> stringResource(R.string.chat_your_agent); "A project chat" -> stringResource(R.string.chat_a_project_chat)
    "your computer" -> stringResource(R.string.chat_your_computer); "this phone" -> stringResource(R.string.chat_this_phone); else -> s
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(84.dp).padding(top = 2.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

/**
 * What an approval will do: a command in monospace on the terminal surface, an app action's fields as labelled lines
 * (never raw JSON), or words as markdown. [full] draws every line (the sheet scrolls it); the card shows the start.
 */
@Composable
fun ApprovalBody(details: String, full: Boolean) {
    val cs = MaterialTheme.colorScheme
    val json = remember(details) { runCatching { JSONObject(details) }.getOrNull() }
    val lines = if (full) Int.MAX_VALUE else 6
    when {
        details.startsWith("$ ") -> Surface(shape = MaterialTheme.shapes.medium, color = com.past9.phoneaos.ui.theme.Media.terminal, modifier = Modifier.fillMaxWidth()) {
            Text(details, Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = com.past9.phoneaos.ui.theme.Media.onTerminal, maxLines = lines, overflow = TextOverflow.Ellipsis)
        }
        json != null -> Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
            val fields = readableFields(json)
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                (if (full) fields else fields.take(4)).forEach { (k, v) ->
                    if (k.startsWith("#")) Text(k.removePrefix("#"), style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                    else Column {
                        Text(k, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        Text(v, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, maxLines = if (full) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!full && fields.size > 4) Text(stringResource(R.string.chat_more_in_review, fields.size - 4), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }
        }
        else -> Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
            if (full) Markdown(details, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
            else Text(remember(details) { plainStart(details) }, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, maxLines = lines, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The start of markdown as plain words for the card (no stars, hashes or backticks, no empty lines); the sheet draws it properly. */
internal fun plainStart(details: String): String =
    details.lineSequence().map { it.trim().replace(Regex("^#{1,6}\\s+"), "").replace("**", "").replace("__", "").replace("`", "") }.filter { it.isNotBlank() }.take(12).joinToString("\n")

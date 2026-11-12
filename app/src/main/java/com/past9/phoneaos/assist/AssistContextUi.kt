package com.past9.phoneaos.assist

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.R
import com.past9.phoneaos.ui.ChoiceSheet
import com.past9.phoneaos.ui.SheetChoice
import com.past9.phoneaos.ui.theme.Radius

/**
 * The "what's going on" strip: one quiet line under the assistant's name saying what waits on him and who is at work, so
 * he never has to say it again. A tap opens it to a few lines. Draws nothing when nothing is going on.
 */
@Composable
fun ContextStrip(ctx: AssistContext?, agentName: String, modifier: Modifier = Modifier) {
    if (ctx == null || (ctx.asks.isEmpty() && ctx.agents.isEmpty())) return
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val needs = ctx.asks.isNotEmpty()
    val fallback = stringResource(R.string.svc_your_agent)
    val first = ctx.asks.lastOrNull()
    val line = when {
        first != null && ctx.asks.size == 1 -> first.by.ifBlank { agentName.ifBlank { fallback } } + ": " + first.text
        needs -> stringResource(R.string.assist_ctx_waiting, ctx.asks.size)
        else -> stringResource(R.string.assist_ctx_working, ctx.working)
    }
    Surface(onClick = { open = !open }, shape = androidx.compose.foundation.shape.RoundedCornerShape(Radius.md), color = if (needs) cs.tertiaryContainer else cs.surfaceContainerHigh,
        contentColor = if (needs) cs.onTertiaryContainer else cs.onSurfaceVariant, modifier = modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (needs) Icons.Rounded.HelpOutline else Icons.Rounded.Forum, null, Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(line, style = MaterialTheme.typography.bodyMedium, maxLines = if (open) 4 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            if (open) {
                Spacer(Modifier.size(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (ctx.asks.size > 1) ctx.asks.takeLast(3).forEach { k ->
                        Text(k.by.ifBlank { agentName.ifBlank { fallback } } + ": " + k.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    ctx.agents.take(4).forEach { a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).background(if (a.state == CtxAgent.WAITING) cs.tertiary else if (a.state == CtxAgent.WORKING) cs.primary else cs.outline, CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text(a.name + if (a.doing.isNotBlank()) ": ${a.doing}" else "", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Two or more waiting questions could take what he said: pick one. A slide-up sheet, never a centred dialog. His words
 * stay in the sheet's line so he sees what is being sent.
 */
@Composable
fun AskChoiceSheet(choice: AskChoice, agentName: String, onPick: (CtxAsk) -> Unit, onDismiss: () -> Unit) {
    val fallback = stringResource(R.string.svc_your_agent)
    val choices = choice.asks.map { k -> SheetChoice(k.id.toString(), k.by.ifBlank { agentName.ifBlank { fallback } }, k.text) }
    ChoiceSheet(Icons.Rounded.Check, stringResource(R.string.chat_needs_you_title), stringResource(R.string.assist_ctx_which), choices,
        onPick = { c -> choice.asks.firstOrNull { it.id.toString() == c.id }?.let(onPick) }, onDismiss = onDismiss,
        line = stringResource(R.string.assist_ctx_your_words, choice.answer.take(120)))
}

/** The words he said and the questions that could take them, while the choice sheet is up (Live voice and the typed sheet share it). */
data class AskChoice(val answer: String, val asks: List<CtxAsk>)

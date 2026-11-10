package com.past9.phoneaos.assist

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.ui.AgentAvatar
import com.past9.phoneaos.ui.AppTextField
import com.past9.phoneaos.ui.Markdown
import com.past9.phoneaos.ui.MorphIconButton
import com.past9.phoneaos.ui.screens.Approval
import com.past9.phoneaos.ui.screens.ApprovalBody
import com.past9.phoneaos.ui.screens.ApprovalSheet
import com.past9.phoneaos.ui.screens.HoldToApprove
import com.past9.phoneaos.ui.screens.QuestionCard
import com.past9.phoneaos.ui.theme.Eyebrow
import com.past9.phoneaos.ui.theme.Radius
import kotlinx.coroutines.delay

/** What the sheet's buttons do. */
data class AssistActions(
    /** The mic button: listen (again). */
    val onMic: () -> Unit = {},
    /** He is done speaking: send what was heard now. */
    val onDoneSpeaking: () -> Unit = {},
    /** Stop the agent's work. */
    val onStopAgent: () -> Unit = {},
    val onKeyboard: () -> Unit = {},
    val onSendTyped: (String) -> Unit = {},
    val onOpenApp: () -> Unit = {},
    val onAnswer: (Long, String) -> Unit = { _, _ -> },
    val onUnlock: () -> Unit = {},
)

/** What the sheet says it knows about the screen: a line and whether anything is being read. */
data class ScreenLine(val text: String, val reading: Boolean)

object AssistWords {
    /** The line under the agent's name. */
    fun caption(v: AssistView): String = when (v.phase) {
        AssistPhase.LISTENING -> "Listening"
        AssistPhase.TYPING -> "Type it instead"
        AssistPhase.LOCKED -> "Unlock to carry on"
        AssistPhase.SENDING -> "Sending"
        AssistPhase.THINKING -> "Thinking"
        AssistPhase.ACTING -> v.label.ifBlank { "Working" }
        AssistPhase.ANSWERING -> "Answered"
        AssistPhase.APPROVAL -> "Needs your yes"
        AssistPhase.ASKING -> "Needs you"
        AssistPhase.ERROR -> "That did not work"
    }

    /** One line on what the assistant can see of the screen, or null when there is nothing to say. */
    fun screenLine(screen: ScreenContext?, readScreenOn: Boolean): ScreenLine? = when {
        screen == null -> if (readScreenOn) null else ScreenLine("Not reading the screen", false)
        screen.blocked -> ScreenLine(ScreenGuard.SAYS, false)
        screen.hasAnything -> ScreenLine("Reading the screen" + (if (screen.label.isNotBlank()) ": ${screen.label}" else "") + (if (screen.screenshot.isNotBlank()) ", with a picture" else ""), true)
        else -> ScreenLine("This screen can't be read", false)
    }
}

/**
 * The assist sheet itself: the agent's face and what it is doing, his words as he speaks, the answer or the card that
 * needs him, and three buttons (keyboard, mic or stop, the full app). It only draws [view]; the activity owns the state.
 */
@Composable
fun AssistSheetBody(view: AssistView, agentName: String, screen: ScreenLine?, actions: AssistActions, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val busy = view.phase == AssistPhase.SENDING || view.phase == AssistPhase.THINKING || view.phase == AssistPhase.ACTING
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 10.dp, bottom = 14.dp)) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(CircleShape).background(cs.outlineVariant))
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgentAvatar(working = busy || view.phase == AssistPhase.LISTENING, size = 44.dp, needsYou = view.phase == AssistPhase.APPROVAL || view.phase == AssistPhase.ASKING)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(agentName, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(AssistWords.caption(view), style = MaterialTheme.typography.bodySmall, color = if (view.phase == AssistPhase.ERROR) cs.error else cs.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (view.phase == AssistPhase.LISTENING) ListeningBars()
        }
        Spacer(Modifier.height(14.dp))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            when (view.phase) {
                AssistPhase.LISTENING -> Text(view.heard.ifBlank { "Go ahead, I'm listening" }, style = MaterialTheme.typography.headlineSmall,
                    color = if (view.heard.isBlank()) cs.onSurfaceVariant else cs.onSurface, modifier = Modifier.heightIn(min = 64.dp))
                AssistPhase.TYPING -> TypeBox(view, agentName, actions.onSendTyped)
                AssistPhase.LOCKED -> {
                    Heard(view.heard)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant); Spacer(Modifier.width(8.dp))
                        Text("Unlock your phone and it goes.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = actions.onUnlock, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Unlock", style = MaterialTheme.typography.titleMedium) }
                }
                AssistPhase.SENDING, AssistPhase.THINKING, AssistPhase.ACTING -> {
                    Heard(view.heard)
                    if (view.answer.isNotBlank()) { Spacer(Modifier.height(10.dp)); Markdown(com.past9.phoneaos.voice.Tts.stripEmoji(view.answer), style = MaterialTheme.typography.bodyLarge) }
                    Spacer(Modifier.height(12.dp))
                    Surface(shape = RoundedCornerShape(Radius.md), color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(AssistWords.caption(view), style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                        }
                    }
                }
                AssistPhase.ANSWERING -> {
                    Heard(view.heard)
                    Spacer(Modifier.height(10.dp))
                    Markdown(com.past9.phoneaos.voice.Tts.stripEmoji(view.answer), style = MaterialTheme.typography.bodyLarge)
                }
                AssistPhase.APPROVAL -> { Heard(view.heard); Spacer(Modifier.height(10.dp)); view.card?.let { ApprovalInSheet(it, actions.onAnswer) } }
                AssistPhase.ASKING -> { Heard(view.heard); Spacer(Modifier.height(10.dp)); view.card?.let { QuestionCard(it, actions.onAnswer, onBrowser = actions.onOpenApp) } }
                AssistPhase.ERROR -> {
                    Heard(view.heard)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(20.dp), tint = cs.error); Spacer(Modifier.width(8.dp))
                        Text(view.problem.ifBlank { "Something went wrong. Try again." }, style = MaterialTheme.typography.bodyLarge, color = cs.error)
                    }
                }
            }
        }
        if (screen != null && (view.phase == AssistPhase.LISTENING || view.phase == AssistPhase.TYPING)) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (screen.reading) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(screen.text, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            MorphIconButton(onClick = actions.onKeyboard, size = 52.dp, enabled = view.phase != AssistPhase.SENDING && view.phase != AssistPhase.LOCKED) {
                Icon(Icons.Rounded.Keyboard, "Type instead", tint = cs.onSurface)
            }
            val (icon, label, tap) = when {
                view.phase == AssistPhase.LISTENING -> Triple(Icons.Rounded.Stop, "Done speaking", actions.onDoneSpeaking)
                busy -> Triple(Icons.Rounded.Stop, "Stop", actions.onStopAgent)
                else -> Triple(Icons.Rounded.Mic, "Speak", actions.onMic)
            }
            FilledIconButton(onClick = tap, modifier = Modifier.size(68.dp).semantics { contentDescription = label }, enabled = view.phase != AssistPhase.SENDING && view.phase != AssistPhase.LOCKED,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (view.phase == AssistPhase.LISTENING) cs.primary else if (busy) cs.errorContainer else cs.primaryContainer,
                    contentColor = if (view.phase == AssistPhase.LISTENING) cs.onPrimary else if (busy) cs.onErrorContainer else cs.onPrimaryContainer)) {
                Icon(icon, null, Modifier.size(30.dp))
            }
            MorphIconButton(onClick = actions.onOpenApp, size = 52.dp) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open the app", tint = cs.onSurface) }
        }
    }
}

/** His words once they are sent: small, in his own bubble colour, like the chat. */
@Composable
private fun Heard(words: String) {
    if (words.isBlank()) return
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(color = cs.primaryContainer, shape = RoundedCornerShape(Radius.md)) {
            Text(words, Modifier.padding(horizontal = 14.dp, vertical = 9.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TypeBox(view: AssistView, agentName: String, onSend: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var text by remember(view.heard) { mutableStateOf(view.heard) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { focus.requestFocus() } }
    if (view.problem.isNotBlank()) { Text(view.problem, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant); Spacer(Modifier.height(10.dp)) }
    AppTextField(text, { text = it }, Modifier.fillMaxWidth().focusRequester(focus), placeholder = { Text("Ask or tell $agentName") }, minLines = 1, maxLines = 4,
        shape = RoundedCornerShape(Radius.md), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) onSend(text) }),
        trailingIcon = { IconButton(onClick = { if (text.isNotBlank()) onSend(text) }, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Rounded.Send, "Send", tint = if (text.isNotBlank()) cs.primary else cs.onSurfaceVariant) } })
}

/** A few bars that move while the microphone is open. */
@Composable
private fun ListeningBars() {
    val cs = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "listening")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "p")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(24.dp)) {
        repeat(5) { i ->
            val h = 6f + 16f * kotlin.math.abs(kotlin.math.sin((p + i * 0.19f) * Math.PI)).toFloat()
            Box(Modifier.width(3.dp).height(h.dp).clip(CircleShape).background(cs.primary))
        }
    }
}

/**
 * The app's approval, inside the sheet: what will happen, on what, the start of the content, and hold to approve.
 * The same row and the same answer as the card in the chat; "Read all of it" opens the full approval sheet.
 */
@Composable
fun ApprovalInSheet(item: ChatItem, onAnswer: (Long, String) -> Unit) {
    val a = remember(item.id, item.text, item.meta) { Approval.of(item) }
    val cs = MaterialTheme.colorScheme
    var open by remember(item.id) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(Radius.md), color = cs.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Shield, null, tint = cs.onTertiaryContainer, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("APPROVE BEFORE I DO THIS", style = Eyebrow, color = cs.onTertiaryContainer)
            }
            Spacer(Modifier.height(8.dp))
            Text(a.summary, style = MaterialTheme.typography.titleMedium, color = cs.onTertiaryContainer)
            Text("Asked by ${a.askedBy}" + if (a.target.isNotBlank()) " · On ${a.target}" else "", style = MaterialTheme.typography.bodySmall, color = cs.onTertiaryContainer.copy(alpha = 0.8f))
            if (a.details.isNotBlank()) { Spacer(Modifier.height(10.dp)); ApprovalBody(a.details, full = false) }
            if (a.failed) { Spacer(Modifier.height(8.dp)); Text(Approval.FAILED, style = MaterialTheme.typography.bodyMedium, color = cs.error) }
            Spacer(Modifier.height(12.dp))
            HoldToApprove { onAnswer(item.id, "Approve") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { onAnswer(item.id, "Decline") }) { Text("Decline", style = MaterialTheme.typography.labelLarge, color = cs.onTertiaryContainer) }
                if (a.details.isNotBlank()) TextButton(onClick = { open = true }) { Text("Read all of it", style = MaterialTheme.typography.labelLarge, color = cs.onTertiaryContainer) }
            }
        }
    }
    if (open) ApprovalSheet(item, onAnswer = onAnswer, onDismiss = { open = false })
}

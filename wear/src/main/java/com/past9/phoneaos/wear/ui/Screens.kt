package com.past9.phoneaos.wear.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.past9.phoneaos.wear.R
import com.past9.phoneaos.wearproto.Exchange
import com.past9.phoneaos.wearproto.Pending
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A scrolling watch screen: the crown and the bezel scroll it, the edge button sits at the bottom. */
@Composable
fun WatchList(
    modifier: Modifier = Modifier,
    edgeButton: (@Composable BoxScope.() -> Unit)? = null,
    content: TransformingLazyColumnScope.() -> Unit,
) {
    val state = rememberTransformingLazyColumnState()
    if (edgeButton != null) {
        ScreenScaffold(scrollState = state, modifier = modifier, edgeButton = edgeButton) { padding: PaddingValues ->
            TransformingLazyColumn(state = state, contentPadding = padding, modifier = Modifier.fillMaxSize(), content = content)
        }
    } else {
        ScreenScaffold(scrollState = state, modifier = modifier) { padding: PaddingValues ->
            TransformingLazyColumn(state = state, contentPadding = padding, modifier = Modifier.fillMaxSize(), content = content)
        }
    }
}

@Composable
fun BtnIcon(icon: ImageVector) = Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))

@Composable
private fun EdgeLabel(icon: ImageVector?, text: String) {
    if (icon != null) { BtnIcon(icon); Spacer(Modifier.size(6.dp)) }
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * The talk screen. One screen, no scrolling needed for the main thing: the agent's name, what needs him, and the big
 * button to talk to it. The watch mic leads; when this watch has none (or it failed) the big button is Speak on phone
 * instead and a line says why. Under that: keyboard and the other voice way, then (scrolling) quick replies and Recent.
 */
@Composable
fun TalkScreen(
    agent: String, link: String, waiting: Int, micUsable: Boolean, quick: List<String>, hasRecent: Boolean, notice: String?,
    onMic: () -> Unit, onPhone: () -> Unit, onType: () -> Unit, onQuick: (String) -> Unit, onWaiting: () -> Unit, onRecent: () -> Unit, onTryMic: () -> Unit,
) {
    val problem = notice ?: when (link) { "offline" -> stringResource(R.string.computer_offline); "none" -> stringResource(R.string.phone_unreachable); else -> null }
    val cs = MaterialTheme.colorScheme
    val heroHeight = LocalConfiguration.current.screenHeightDp.dp * 0.78f
    val mainLabel = stringResource(if (micUsable) R.string.talk else R.string.speak_on_phone)
    WatchList {
        item {
            Column(Modifier.fillMaxWidth().height(heroHeight), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
                Text(agent.ifBlank { stringResource(R.string.agent_default) }, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
                when {
                    waiting > 0 -> FilledTonalButton(
                        onClick = onWaiting, modifier = Modifier.padding(horizontal = 20.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer),
                        icon = { BtnIcon(WIcons.Shield) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        label = { Text(if (waiting == 1) stringResource(R.string.tile_one_waits) else stringResource(R.string.tile_many_wait, waiting), maxLines = 1) },
                    )
                    problem != null -> Text(problem, style = MaterialTheme.typography.bodySmall, color = cs.error, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(horizontal = 24.dp))
                    !micUsable -> Text(stringResource(R.string.mic_unavailable), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(horizontal = 24.dp))
                    else -> Spacer(Modifier.height(1.dp))
                }
                // The primary action is always the one that works: the watch mic, or the phone's when this watch's is gone.
                FilledIconButton(onClick = if (micUsable) onMic else onPhone, modifier = Modifier.size(68.dp)) {
                    Icon(if (micUsable) WIcons.Mic else WIcons.Phone, contentDescription = mainLabel, modifier = Modifier.size(32.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = onType, modifier = Modifier.size(44.dp)) { Icon(WIcons.Keyboard, contentDescription = stringResource(R.string.type_it), modifier = Modifier.size(22.dp)) }
                    if (micUsable) FilledTonalIconButton(onClick = onPhone, modifier = Modifier.size(44.dp)) { Icon(WIcons.Phone, contentDescription = stringResource(R.string.speak_on_phone), modifier = Modifier.size(22.dp)) }
                    else FilledTonalIconButton(onClick = onTryMic, modifier = Modifier.size(44.dp)) { Icon(WIcons.Mic, contentDescription = stringResource(R.string.try_mic), modifier = Modifier.size(22.dp)) }
                }
            }
        }
        quick.forEach { q -> item { FilledTonalButton(onClick = { onQuick(q) }, modifier = Modifier.fillMaxWidth(), label = { Text(q, maxLines = 2) }) } }
        if (hasRecent) item { FilledTonalButton(onClick = onRecent, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.recent)) }) }
    }
}

/** The mic is open (or the take is on its way to the phone). */
@Composable
fun ListeningScreen(level: Float, sending: Boolean, onStop: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    // A screen that does not scroll has no edge button to hang on the scroll state: Stop is a plain button under the ring.
    ScreenScaffold {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            // The ring grows with the voice; it is a plain circle, so it costs the battery nothing.
            val d = 52.dp + (36 * level).dp
            Spacer(Modifier.height(18.dp))
            Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(d).clip(CircleShape).background(if (sending) cs.surfaceContainerHigh else cs.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(WIcons.Mic, contentDescription = null, tint = cs.onPrimaryContainer, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(if (sending) R.string.sending else R.string.listening), style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            if (!sending) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onStop, label = { Text(stringResource(R.string.stop)) })
            }
        }
    }
}

/** What the answer screen draws. */
data class AnswerUi(val you: String, val status: String, val answer: String, val working: Boolean, val needsYou: Boolean, val problem: String)

@Composable
fun AnswerScreen(ui: AnswerUi, onReply: () -> Unit, onStop: () -> Unit, onReview: () -> Unit, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    WatchList(edgeButton = {
        when {
            ui.problem.isNotBlank() -> EdgeButton(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
            ui.needsYou -> EdgeButton(onClick = onReview, colors = ButtonDefaults.buttonColors(containerColor = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer)) { Text(stringResource(R.string.needs_you)) }
            ui.working -> EdgeButton(onClick = onStop) { Text(stringResource(R.string.stop)) }
            else -> EdgeButton(onClick = onReply) { EdgeLabel(WIcons.Mic, stringResource(R.string.reply)) }
        }
    }) {
        if (ui.you.isNotBlank()) item { Text(ui.you, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) }
        if (ui.status.isNotBlank()) item { Text(ui.status, style = MaterialTheme.typography.labelMedium, color = if (ui.needsYou) cs.tertiary else cs.primary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) }
        if (ui.problem.isNotBlank()) item { Text(ui.problem, style = MaterialTheme.typography.bodyMedium, color = cs.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) }
        if (ui.answer.isNotBlank()) item { Text(ui.answer, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) }
    }
}

/**
 * A card that needs him. An approval is only ever answered by holding Approve (a tap cannot do it) or pressing Decline.
 * A question shows its own answers; one with none is answered on the phone.
 */
@Composable
fun ApprovalScreen(p: Pending, position: String, onAnswer: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    WatchList {
        item { Text(stringResource(R.string.needs_you) + position, style = MaterialTheme.typography.labelMedium, color = cs.tertiary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) }
        item { Text(p.title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) }
        if (p.askedBy.isNotBlank()) item { Text(p.askedBy, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) }
        if (p.details.isNotBlank()) item { Text(p.details, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) }
        if (p.isApproval) {
            item { HoldToApprove(stringResource(R.string.approve_hold), stringResource(R.string.approving)) { onAnswer("Approve") } }
            item { FilledTonalButton(onClick = { onAnswer("Decline") }, modifier = Modifier.fillMaxWidth(), icon = { BtnIcon(WIcons.Close) }, label = { Text(stringResource(R.string.decline)) }) }
        } else if (p.options.isEmpty()) {
            item { Text(stringResource(R.string.answer_on_phone), style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) }
        } else p.options.forEach { o -> item { FilledTonalButton(onClick = { onAnswer(o) }, modifier = Modifier.fillMaxWidth(), label = { Text(o, maxLines = 2) }) } }
    }
}

/** Hold for 0.9 s. Let go early and nothing happens. */
@Composable
fun HoldToApprove(label: String, holding: String, onApproved: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var fired by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth().height(56.dp).clip(CircleShape).background(cs.primaryContainer)
            .drawBehind { drawRect(cs.primary.copy(alpha = 0.55f), size = Size(size.width * progress.value, size.height)) }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        progress.animateTo(1f, tween(900, easing = LinearEasing))
                        if (!fired) { fired = true; haptic.performHapticFeedback(HapticFeedbackType.LongPress); onApproved() }
                    }
                    tryAwaitRelease()
                    if (progress.value < 1f) { job.cancel(); scope.launch { progress.snapTo(0f) } }
                })
            }
            .semantics(mergeDescendants = true) { role = Role.Button; onLongClick(label) { if (!fired) { fired = true; onApproved() }; true } },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (progress.value > 0f) holding else label, style = MaterialTheme.typography.labelMedium, color = cs.onPrimaryContainer, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 18.dp))
    }
}

/** A full-screen confirmation (never a pop-up): shown a moment after an answer, then it goes back by itself. */
@Composable
fun DoneScreen(text: String, positive: Boolean, onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { delay(1_400); onDone() }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(if (positive) cs.primaryContainer else cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(if (positive) WIcons.Check else WIcons.Close, contentDescription = null, tint = if (positive) cs.onPrimaryContainer else cs.onSurfaceVariant, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
    }
}

@Composable
fun RecentScreen(items: List<Exchange>, onOpen: (Exchange) -> Unit) {
    val cs = MaterialTheme.colorScheme
    WatchList {
        item { ListHeader { Text(stringResource(R.string.recent)) } }
        if (items.isEmpty()) item { Text(stringResource(R.string.no_recent), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) }
        items.forEach { e ->
            item {
                FilledTonalButton(onClick = { onOpen(e) }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(e.you, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text(e.reply, maxLines = 2, overflow = TextOverflow.Ellipsis) })
            }
        }
    }
}

@Composable
fun ExchangeScreen(e: Exchange, onReply: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    WatchList(edgeButton = { EdgeButton(onClick = onReply) { EdgeLabel(WIcons.Mic, stringResource(R.string.reply)) } }) {
        item { Text(e.you, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) }
        item { Text(e.reply, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) }
    }
}

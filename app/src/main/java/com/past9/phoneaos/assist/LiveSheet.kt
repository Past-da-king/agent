package com.past9.phoneaos.assist

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.ui.AgentAvatar
import com.past9.phoneaos.ui.MorphIconButton
import com.past9.phoneaos.ui.screens.QuestionCard
import com.past9.phoneaos.ui.theme.Radius

/** What the live sheet's buttons do. */
data class LiveActions(
    val onEnd: () -> Unit = {},
    val onMute: (Boolean) -> Unit = {},
    val onOpenApp: () -> Unit = {},
    val onAnswer: (Long, String) -> Unit = { _, _ -> },
    /** Shrink the sheet to a floating bubble; the session keeps going. */
    val onMinimise: () -> Unit = {},
)

object LiveWords {
    fun caption(v: LiveView): String = when (v.phase) {
        LivePhase.CONNECTING -> "Connecting"
        LivePhase.LISTENING -> if (v.muted) "Muted" else "Listening"
        LivePhase.SPEAKING -> "Speaking"
        LivePhase.WORKING -> "Working on it"
        LivePhase.NEEDS_YOU -> "Needs you"
        LivePhase.ERROR -> "Live voice stopped"
    }
}

/**
 * The assist sheet while Live voice is on: the agent's face and a small LIVE indicator, a short live caption of the
 * last thing each side said, the app's own approval or question card when the agent needs him, and Mute, End and Open.
 * It only draws [view]; the activity owns the session.
 */
@Composable
fun LiveSheetBody(view: LiveView, agentName: String, screen: ScreenLine?, actions: LiveActions, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val speaking = view.phase == LivePhase.SPEAKING
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 10.dp, bottom = 14.dp)) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(CircleShape).background(cs.outlineVariant))
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgentAvatar(working = speaking || view.phase == LivePhase.WORKING || view.phase == LivePhase.LISTENING && !view.muted, size = 44.dp, needsYou = view.phase == LivePhase.NEEDS_YOU)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(agentName, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(LiveWords.caption(view), style = MaterialTheme.typography.bodySmall, color = if (view.phase == LivePhase.ERROR) cs.error else cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (view.phase != LivePhase.ERROR) LiveBadge(view.phase != LivePhase.CONNECTING)
            if (view.phase != LivePhase.ERROR) {
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = actions.onMinimise, modifier = Modifier.size(44.dp).semantics { contentDescription = "Minimise" }) {
                    Icon(Icons.Rounded.CloseFullscreen, null, tint = cs.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).heightIn(min = 72.dp)) {
            if (view.phase == LivePhase.ERROR) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(20.dp), tint = cs.error); Spacer(Modifier.width(8.dp))
                    Text(view.problem.ifBlank { "Live voice stopped. Try again." }, style = MaterialTheme.typography.bodyLarge, color = cs.error)
                }
            } else {
                if (view.you.isNotBlank()) YouSaid(LiveAssistCore.tail(view.you, 140))
                if (view.agent.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(LiveAssistCore.tail(view.agent), style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 4, overflow = TextOverflow.Ellipsis)
                } else if (view.you.isBlank()) {
                    Text(if (view.phase == LivePhase.CONNECTING) "Getting ready" else "Go ahead, just talk", style = MaterialTheme.typography.headlineSmall, color = cs.onSurfaceVariant)
                }
                view.card?.let { card ->
                    Spacer(Modifier.height(12.dp))
                    if (com.past9.phoneaos.ui.screens.Approval.isApproval(card)) ApprovalInSheet(card, actions.onAnswer) else QuestionCard(card, actions.onAnswer, onBrowser = actions.onOpenApp)
                }
                if (view.phase == LivePhase.WORKING) {
                    Spacer(Modifier.height(12.dp))
                    Surface(shape = RoundedCornerShape(Radius.md), color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(view.label.ifBlank { "Working on it" }, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                        }
                    }
                }
            }
        }
        if (view.seeing != Seeing.OFF && view.phase != LivePhase.ERROR) {
            // Live is looking at the screen (or cannot, because it is private): said plainly, so nothing is hidden.
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (view.seeing == Seeing.SEEING) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, null, Modifier.size(16.dp), tint = if (view.seeing == Seeing.SEEING) cs.primary else cs.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(if (view.seeing == Seeing.SEEING) "Live can see your screen" else "Private screen: Live can't see it", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else if (screen != null && view.phase != LivePhase.ERROR) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (screen.reading) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(screen.text, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            MorphIconButton(onClick = { actions.onMute(!view.muted) }, size = 52.dp, enabled = view.phase != LivePhase.ERROR && view.phase != LivePhase.CONNECTING) {
                Icon(if (view.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (view.muted) "Unmute" else "Mute", tint = if (view.muted) cs.error else cs.onSurface)
            }
            FilledIconButton(onClick = actions.onEnd, modifier = Modifier.size(68.dp).semantics { contentDescription = "End" },
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = cs.errorContainer, contentColor = cs.onErrorContainer)) {
                Icon(Icons.Rounded.CallEnd, null, Modifier.size(30.dp))
            }
            MorphIconButton(onClick = actions.onOpenApp, size = 52.dp) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open the app", tint = cs.onSurface) }
        }
    }
}

@Composable
private fun YouSaid(words: String) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(color = cs.primaryContainer, shape = RoundedCornerShape(Radius.md)) {
            Text(words, Modifier.padding(horizontal = 14.dp, vertical = 9.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The small indicator that a live session is open: a pulsing dot and the word LIVE. */
@Composable
fun LiveBadge(on: Boolean) {
    val cs = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "live")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Surface(shape = CircleShape, color = cs.errorContainer, modifier = Modifier.semantics { contentDescription = if (on) "Live session open" else "Connecting" }) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).alpha(if (on) a else 0.5f).clip(CircleShape).background(cs.error))
            Spacer(Modifier.width(6.dp))
            Text("LIVE", style = MaterialTheme.typography.labelSmall, color = cs.onErrorContainer)
        }
    }
}

package com.past9.phoneaos.assist

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.ui.AgentAvatar
import com.past9.phoneaos.ui.AppSheet

/** False when the phone's "remove animations" setting is on (animator duration scale 0): the face then holds still. */
@Composable
fun rememberAnimate(vararg keys: Any?): Boolean {
    val context = LocalContext.current
    return remember(*keys) { runCatching { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }.getOrDefault(true) }
}

/**
 * The agent's own face on the live bubble: the mascot the user picked (the same [AgentAvatar] the rest of the app
 * draws, read from the theme), moving a little with what the voice is doing.
 *  - listening: a slow, gentle breath;
 *  - speaking: the mouth opens and closes with the loudness of the voice and the face pulses with it;
 *  - working on a hand-off, connecting or muted: calm, barely moving (muted also dims);
 *  - needing him: the attention colour the app uses for that.
 * With [animate] false (reduce motion) it holds still; while speaking the mouth stays half open.
 */
@Composable
fun LiveFace(view: LiveView, modifier: Modifier = Modifier, size: Dp = 40.dp, animate: Boolean = rememberAnimate(view.phase)) {
    val cs = MaterialTheme.colorScheme
    val needs = view.phase == LivePhase.NEEDS_YOU
    val speaking = view.phase == LivePhase.SPEAKING
    val calm = view.muted || view.phase == LivePhase.WORKING || view.phase == LivePhase.CONNECTING
    val breath = if (animate) {
        val t = rememberInfiniteTransition(label = "face")
        val b by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (calm) 3400 else 2300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
        b
    } else 0f
    val level by animateFloatAsState(if (animate && speaking) view.level else 0f, tween(90), label = "level")
    val scale = when {
        !animate -> 1f
        speaking -> 1f + 0.02f * breath + 0.09f * level
        calm -> 1f + 0.012f * breath
        else -> 1f + 0.05f * breath
    }
    val mouthOpen = when { !speaking -> 0f; animate -> 0.05f + 0.95f * level; else -> 0.5f }
    val ink = if (needs) cs.onTertiary else cs.onPrimary
    Box(modifier.size(size).scale(scale).alpha(if (view.muted) 0.55f else 1f), contentAlignment = Alignment.Center) {
        AgentAvatar(working = false, size = size, needsYou = needs)
        if (speaking) {
            Box(Modifier.offset(y = size * 0.2f).size(width = size * 0.22f, height = size * (0.03f + 0.17f * mouthOpen)).clip(RoundedCornerShape(50)).background(ink))
        }
    }
}

/**
 * The floating bubble the live sheet shrinks to: the agent's face, the LIVE dot with what it is doing, a mute toggle and
 * a close button. Tapping the body opens the sheet again and dragging it moves it (the overlay window handles both).
 */
@Composable
fun LiveBubbleContent(view: LiveView, onMute: (Boolean) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier, animate: Boolean = rememberAnimate(view.phase)) {
    val cs = MaterialTheme.colorScheme
    val needs = view.phase == LivePhase.NEEDS_YOU
    val pulse = if (animate) {
        val t = rememberInfiniteTransition(label = "bubble")
        val p by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot")
        p
    } else 1f
    Surface(
        modifier.height(56.dp).shadow(6.dp, CircleShape).semantics { contentDescription = "Live voice. Tap to open" },
        shape = CircleShape, color = if (needs) cs.tertiaryContainer else cs.surfaceContainerHighest, contentColor = if (needs) cs.onTertiaryContainer else cs.onSurface,
    ) {
        Row(Modifier.padding(start = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            LiveFace(view, size = 42.dp, animate = animate)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.widthIn(max = 104.dp), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).alpha(if (view.phase == LivePhase.CONNECTING) 0.5f else pulse).clip(CircleShape).background(cs.error))
                    Spacer(Modifier.width(5.dp))
                    Text("LIVE", style = MaterialTheme.typography.labelSmall)
                }
                Text(LiveWords.caption(view), style = MaterialTheme.typography.labelLarge, color = if (needs) cs.onTertiaryContainer else cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { onMute(!view.muted) }, modifier = Modifier.size(40.dp).semantics { contentDescription = if (view.muted) "Unmute" else "Mute" }) {
                Icon(if (view.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, null, tint = if (view.muted) cs.error else cs.onSurface)
            }
            IconButton(onClick = onClose, modifier = Modifier.size(40.dp).semantics { contentDescription = "End the live session" }) {
                Icon(Icons.Rounded.Close, null, tint = cs.onSurfaceVariant)
            }
        }
    }
}

/** The circle that appears at the bottom while the bubble is dragged: let go on it to end the session. */
@Composable
fun LiveDismissTarget(over: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val s by animateFloatAsState(if (over) 1.2f else 1f, label = "target")
    Box(modifier.size(96.dp).scale(s), contentAlignment = Alignment.Center) {
        Surface(Modifier.size(64.dp), shape = CircleShape, color = if (over) cs.error else cs.errorContainer, contentColor = if (over) cs.onError else cs.onErrorContainer, shadowElevation = 4.dp) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Close, "End the live session", Modifier.size(28.dp)) }
        }
    }
}

/** Asked once, as a slide-up sheet, before the first minimise: floating over other apps needs that permission. */
@Composable
fun OverlayPermissionSheet(agentName: String, onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AppSheet(Icons.Rounded.PictureInPictureAlt, "Minimise", "Let $agentName float over other apps", onDismiss = onDismiss, primary = "Open Settings", primaryEnabled = true, onPrimary = onOpenSettings,
        secondary = { TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Not now") } }) {
        Text("Minimising turns the live sheet into a small bubble that stays on top, so you can talk to $agentName while you move between apps. Android needs you to allow that once.",
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text("In the next screen, switch on Allow display over other apps. Then come back and tap Minimise again.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    }
}

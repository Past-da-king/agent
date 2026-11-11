package com.past9.phoneaos.assist

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.ui.AppSheet

/**
 * The floating bubble the live sheet shrinks to: a pill with the LIVE dot, what it is doing in two words, a mute
 * toggle and a close button. Tapping the body opens the sheet again (the overlay window handles that tap and the drag).
 */
@Composable
fun LiveBubbleContent(view: LiveView, onMute: (Boolean) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val needs = view.phase == LivePhase.NEEDS_YOU
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "bubble")
    val pulse by t.animateFloat(0.35f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(800), androidx.compose.animation.core.RepeatMode.Reverse), label = "p")
    Surface(
        modifier.height(52.dp).shadow(6.dp, CircleShape).semantics { contentDescription = "Live voice. Tap to open" },
        shape = CircleShape, color = if (needs) cs.tertiaryContainer else cs.surfaceContainerHighest, contentColor = if (needs) cs.onTertiaryContainer else cs.onSurface,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).alpha(if (view.phase == LivePhase.CONNECTING) 0.5f else pulse).clip(CircleShape).background(cs.error))
            Spacer(Modifier.width(8.dp))
            Text("LIVE", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            Text(LiveWords.caption(view), style = MaterialTheme.typography.labelLarge, color = if (needs) cs.onTertiaryContainer else cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp))
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

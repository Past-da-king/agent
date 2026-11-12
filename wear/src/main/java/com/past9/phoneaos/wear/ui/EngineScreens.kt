package com.past9.phoneaos.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureAction
import androidx.wear.compose.material3.onehandedgesture.OneHandedGesturePriority
import androidx.wear.compose.material3.onehandedgesture.oneHandedGesture
import androidx.wear.compose.material3.onehandedgesture.rememberOneHandedGestureConfiguration
import com.past9.phoneaos.wear.R
import com.past9.phoneaos.wearproto.AgentStatus
import com.past9.phoneaos.wearproto.AppEntry
import com.past9.phoneaos.wearproto.ApprovalRingMode
import com.past9.phoneaos.wearproto.CallView
import com.past9.phoneaos.wearproto.HomeGuide
import com.past9.phoneaos.wearproto.RingItem
import com.past9.phoneaos.wearproto.RingKind
import com.past9.phoneaos.wearproto.Side
import com.past9.phoneaos.wearproto.WatchCallCore
import com.past9.phoneaos.wearproto.gesture.Availability
import com.past9.phoneaos.wearproto.gesture.DeviceProfile
import com.past9.phoneaos.wearproto.gesture.Gesture
import com.past9.phoneaos.wearproto.gesture.GestureAction
import com.past9.phoneaos.wearproto.gesture.GestureCapabilities
import com.past9.phoneaos.wearproto.gesture.GestureMap

/*
 * Minimal, working screens for the call, the ring, the gestures and the home screen. They are built from the watch's own tokens and
 * list scaffold (WatchList), with no pop-up dialogs, so the design pass can restyle them without touching what they do.
 */

private val wide = Modifier.fillMaxWidth()
private val inset = Modifier.fillMaxWidth().padding(horizontal = 14.dp)

/**
 * The platform's double pinch (Wear OS 7, Pixel Watch 3 and newer, `oneHandedGesture`) as this control's primary action. On every other
 * watch the API does nothing, so the control stays an ordinary button (a gesture-only action is never hidden, per Google's guidance).
 */
@Composable
fun Modifier.primaryGesture(label: String, onGesture: () -> Unit): Modifier {
    val config = rememberOneHandedGestureConfiguration(action = OneHandedGestureAction.Primary, priority = OneHandedGesturePriority.Clickable)
    return this.oneHandedGesture(gestureConfiguration = config, onGestureLabel = label, onGesture = onGesture)
}

/**
 * What rings: a wake-up or an approval. A wake is answered here (I'm on it, Snooze); an approval is reviewed. Double pinch snoozes a
 * ringing wake (Google's own example of the primary action); wrist turn is the system's back, which the ring screen treats as silence.
 */
@Composable
fun RingScreen(item: RingItem, onAck: () -> Unit, onSnooze: () -> Unit, onReview: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tint = when { item.kind == RingKind.APPROVAL -> cs.tertiary; item.severity == "critical" -> cs.error; else -> cs.tertiary }
    val snoozeButton = wide.primaryGesture(stringResource(R.string.ring_snooze), onSnooze)
    WatchList {
        item { Text(stringResource(if (item.kind == RingKind.APPROVAL) R.string.needs_you else R.string.ring_wake), style = MaterialTheme.typography.labelMedium, color = tint, textAlign = TextAlign.Center, modifier = inset) }
        item { Text(item.title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, modifier = inset) }
        if (item.whatToDo.isNotBlank()) item { Text(item.whatToDo, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = inset) }
        if (item.kind == RingKind.WAKE) {
            item { FilledTonalButton(onClick = onAck, modifier = wide, label = { Text(stringResource(R.string.ring_on_it)) }) }
            item { FilledTonalButton(onClick = onSnooze, modifier = snoozeButton, label = { Text(stringResource(R.string.ring_snooze)) }) }
        } else {
            item { FilledTonalButton(onClick = onReview, modifier = wide, label = { Text(stringResource(R.string.ring_review)) }) }
            item { FilledTonalButton(onClick = onSnooze, modifier = wide, label = { Text(stringResource(R.string.ring_dismiss)) }) }
        }
    }
}

@Composable
private fun phaseText(s: WatchCallCore.State): String = when {
    s.phase == WatchCallCore.STARTING -> stringResource(R.string.call_connecting)
    s.phase == WatchCallCore.LIVE && s.degraded -> stringResource(R.string.call_reconnecting)
    s.phase == WatchCallCore.LIVE -> when (s.sub) {
        CallView.THINKING -> stringResource(R.string.call_thinking)
        CallView.SPEAKING -> stringResource(R.string.call_speaking)
        else -> stringResource(R.string.call_listening)
    }
    s.phase == WatchCallCore.ENDED -> stringResource(R.string.call_ended)
    s.phase == WatchCallCore.FAILED -> stringResource(R.string.call_failed)
    else -> ""
}

/**
 * The call: its state, one caption line, where the sound is, Mute and End. Once it is over the reason is shown with Call again and
 * Use the phone's sound.
 */
@Composable
fun CallScreen(s: WatchCallCore.State, agent: String, onMute: () -> Unit, onEnd: () -> Unit, onAgain: () -> Unit, onPhoneAudio: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val over = s.phase == WatchCallCore.ENDED || s.phase == WatchCallCore.FAILED || s.phase == WatchCallCore.IDLE
    WatchList {
        item { Text(agent.ifBlank { stringResource(R.string.agent_default) }, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, textAlign = TextAlign.Center, modifier = inset) }
        item { Text(phaseText(s), style = MaterialTheme.typography.labelMedium, color = if (s.phase == WatchCallCore.FAILED || s.degraded) cs.error else cs.primary, textAlign = TextAlign.Center, modifier = inset) }
        if (s.error.isNotBlank()) item { Text(s.error, style = MaterialTheme.typography.bodySmall, color = cs.error, textAlign = TextAlign.Center, modifier = inset) }
        if (s.line.isNotBlank() && !over) item { Text(s.line, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = inset) }
        s.plan?.let { p -> if (!over) item { Text(stringResource(R.string.call_sound, soundWord(p.mic), soundWord(p.spk)), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = inset) } }
        if (!over) {
            item { FilledTonalButton(onClick = onMute, modifier = wide.primaryGesture(stringResource(R.string.call_mute), onMute), label = { Text(stringResource(if (s.muted) R.string.call_unmute else R.string.call_mute)) }) }
            item { FilledTonalButton(onClick = onEnd, modifier = wide, colors = ButtonDefaults.filledTonalButtonColors(containerColor = cs.errorContainer, contentColor = cs.onErrorContainer), label = { Text(stringResource(R.string.call_end)) }) }
        } else {
            item { FilledTonalButton(onClick = onAgain, modifier = wide, label = { Text(stringResource(R.string.call_again)) }) }
            item { FilledTonalButton(onClick = onPhoneAudio, modifier = wide, label = { Text(stringResource(R.string.call_use_phone)) }) }
        }
    }
}

@Composable
private fun soundWord(side: String) = stringResource(if (side == Side.WATCH) R.string.sound_watch else R.string.sound_phone)

private fun next(a: GestureAction): GestureAction = when (a) {
    GestureAction.NONE -> GestureAction.TALK; GestureAction.TALK -> GestureAction.DISMISS; GestureAction.DISMISS -> GestureAction.CALL
    GestureAction.CALL -> GestureAction.APPROVE; GestureAction.APPROVE -> GestureAction.NONE
}

@Composable
private fun actionName(a: GestureAction) = stringResource(when (a) {
    GestureAction.NONE -> R.string.act_none; GestureAction.TALK -> R.string.act_talk; GestureAction.DISMISS -> R.string.act_dismiss
    GestureAction.CALL -> R.string.act_call; GestureAction.APPROVE -> R.string.act_approve
})

@Composable
private fun gestureName(g: Gesture) = stringResource(when (g) {
    Gesture.FLICK -> R.string.g_flick; Gesture.DOUBLE_FLICK -> R.string.g_double_flick; Gesture.SHAKE -> R.string.g_shake
    Gesture.DOUBLE_TAP -> R.string.g_double_tap; Gesture.RAISE -> R.string.g_raise
})

@Composable
private fun ringModeName(m: String) = stringResource(when (m) { ApprovalRingMode.OFF -> R.string.mode_off; ApprovalRingMode.BUZZ -> R.string.mode_buzz; else -> R.string.mode_full })

/**
 * Gestures and rings: each gesture he can make is mapped to what it does (tap a row to change it). A gesture this watch cannot detect is
 * shown as unavailable, never offered. Nothing is on until he sets it; Recommended is one tap.
 */
@Composable
fun GesturesScreen(
    map: GestureMap, profile: DeviceProfile, approvalMode: String,
    onMap: (GestureMap) -> Unit, onApprovalMode: (String) -> Unit, onNotificationAccess: () -> Unit, onHomeScreen: () -> Unit, onUnpair: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    WatchList {
        item { ListHeader { Text(stringResource(R.string.gestures_title)) } }
        Gesture.entries.forEach { g ->
            val ok = GestureCapabilities.availability(g, profile) == Availability.WORKS
            item {
                FilledTonalButton(
                    onClick = { if (ok) onMap(map.with(g, next(map.actionFor(g)))) }, enabled = ok, modifier = wide,
                    label = { Text(gestureName(g), maxLines = 1) },
                    secondaryLabel = { Text(if (ok) actionName(map.actionFor(g)) else stringResource(R.string.g_unavailable), maxLines = 1) },
                )
            }
        }
        item { FilledTonalButton(onClick = { onMap(GestureMap.recommended().copy(alwaysOn = map.alwaysOn)) }, modifier = wide, label = { Text(stringResource(R.string.g_recommended)) }) }
        item {
            FilledTonalButton(onClick = { onMap(map.copy(alwaysOn = !map.alwaysOn)) }, modifier = wide,
                label = { Text(stringResource(R.string.g_always_on)) }, secondaryLabel = { Text(stringResource(if (map.alwaysOn) R.string.on else R.string.off)) })
        }
        item { Text(stringResource(R.string.g_battery_note), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = inset) }
        item {
            val system = GestureCapabilities.systemGestures(profile)
            Text(stringResource(if (system == Availability.SYSTEM_ONLY) R.string.g_system_yes else R.string.g_system_no), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = inset)
        }
        item { ListHeader { Text(stringResource(R.string.rings_title)) } }
        item {
            val nextMode = when (approvalMode) { ApprovalRingMode.FULL -> ApprovalRingMode.BUZZ; ApprovalRingMode.BUZZ -> ApprovalRingMode.OFF; else -> ApprovalRingMode.FULL }
            FilledTonalButton(onClick = { onApprovalMode(nextMode) }, modifier = wide, label = { Text(stringResource(R.string.rings_approvals)) }, secondaryLabel = { Text(ringModeName(approvalMode)) })
        }
        item { FilledTonalButton(onClick = onNotificationAccess, modifier = wide, label = { Text(stringResource(R.string.rings_dnd)) }) }
        item { FilledTonalButton(onClick = onHomeScreen, modifier = wide, label = { Text(stringResource(R.string.home_settings)) }) }
        if (onUnpair != null) item { FilledTonalButton(onClick = onUnpair, modifier = wide, label = { Text(stringResource(R.string.pair_unpair)) }) }
    }
}

@Composable
private fun statusWord(s: AgentStatus) = stringResource(when (s) {
    AgentStatus.IDLE -> R.string.face_idle; AgentStatus.WORKING -> R.string.face_working; AgentStatus.NEEDS_YOU -> R.string.face_needs
    AgentStatus.IN_CALL -> R.string.face_call; AgentStatus.OFFLINE -> R.string.face_offline
})

/** The agent as the home screen: the agent first (what it is doing, Talk), then the apps; recents are what was opened from here. */
@Composable
fun HomeScreen(
    agent: String, status: AgentStatus, waiting: Int, apps: List<AppEntry>, recents: List<AppEntry>, resetKey: Int,
    onTalk: () -> Unit, onOpenAgent: () -> Unit, onApp: (AppEntry) -> Unit, onSettings: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    @Suppress("UNUSED_VARIABLE") val key = resetKey
    WatchList {
        item { Text(agent.ifBlank { stringResource(R.string.agent_default) }, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, textAlign = TextAlign.Center, modifier = inset) }
        item { Text(if (waiting > 0) stringResource(R.string.tile_many_wait, waiting) else statusWord(status), style = MaterialTheme.typography.labelMedium, color = if (waiting > 0) cs.tertiary else cs.primary, textAlign = TextAlign.Center, modifier = inset) }
        item { FilledTonalButton(onClick = onTalk, modifier = wide, label = { Text(stringResource(R.string.talk)) }) }
        item { FilledTonalButton(onClick = onOpenAgent, modifier = wide, label = { Text(stringResource(R.string.home_open_agent)) }) }
        if (recents.isNotEmpty()) {
            item { ListHeader { Text(stringResource(R.string.home_recent)) } }
            recents.forEach { a -> item { FilledTonalButton(onClick = { onApp(a) }, modifier = wide, label = { Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }) } }
        }
        item { ListHeader { Text(stringResource(R.string.home_apps)) } }
        apps.forEach { a -> item { FilledTonalButton(onClick = { onApp(a) }, modifier = wide, label = { Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }) } }
        item { FilledTonalButton(onClick = onSettings, modifier = wide, label = { Text(stringResource(R.string.home_settings)) }) }
    }
}

/**
 * Using the agent as the home screen is his choice. This says where it stands, turns it on, and always shows the way back. On a watch
 * whose system has no home picker it says how to choose, and that switching this off is enough to go back.
 */
@Composable
fun HomeSettingsScreen(state: HomeGuide.State, hasPicker: Boolean, note: String, onTurnOn: () -> Unit, onTurnOff: () -> Unit, onOpenSystem: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    WatchList {
        item { ListHeader { Text(stringResource(R.string.home_settings)) } }
        item {
            Text(stringResource(when (state) {
                HomeGuide.State.OFF -> R.string.home_state_off
                HomeGuide.State.ON_THIS_APP_ASKED -> R.string.home_state_asked
                HomeGuide.State.HELD_BY_THIS_APP -> R.string.home_state_mine
                HomeGuide.State.HELD_BY_OTHER -> R.string.home_state_other
            }), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = inset)
        }
        if (state == HomeGuide.State.OFF || state == HomeGuide.State.HELD_BY_OTHER || state == HomeGuide.State.ON_THIS_APP_ASKED)
            item { FilledTonalButton(onClick = onTurnOn, modifier = wide, label = { Text(stringResource(R.string.home_use)) }) }
        if (onOpenSystem != null) item { FilledTonalButton(onClick = onOpenSystem, modifier = wide, label = { Text(stringResource(R.string.home_open_system)) }) }
        if (state != HomeGuide.State.OFF) item { FilledTonalButton(onClick = onTurnOff, modifier = wide, label = { Text(stringResource(R.string.home_back)) }) }
        if (!hasPicker) item { Text(stringResource(R.string.home_no_picker), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = inset) }
        if (note.isNotBlank()) item { Text(note, style = MaterialTheme.typography.bodySmall, color = cs.tertiary, modifier = inset) }
    }
}

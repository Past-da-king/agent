package com.past9.phoneaos.wear.ui

import android.content.ComponentName
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureAction
import androidx.wear.compose.material3.onehandedgesture.OneHandedGesturePriority
import androidx.wear.compose.material3.onehandedgesture.oneHandedGesture
import androidx.wear.compose.material3.onehandedgesture.rememberOneHandedGestureConfiguration
import com.past9.phoneaos.wear.R
import com.past9.phoneaos.wear.ui.ex.CrownColumn
import com.past9.phoneaos.wear.ui.ex.EdgeFades
import com.past9.phoneaos.wear.ui.ex.ExEdge
import com.past9.phoneaos.wear.ui.ex.ExPill
import com.past9.phoneaos.wear.ui.ex.ExShapeBtn
import com.past9.phoneaos.wear.ui.ex.ExSheet
import com.past9.phoneaos.wear.ui.ex.ExStage
import com.past9.phoneaos.wear.ui.ex.Geo
import com.past9.phoneaos.wear.ui.ex.Ic
import com.past9.phoneaos.wear.ui.ex.IconBox
import com.past9.phoneaos.wear.ui.ex.LiveBlob
import com.past9.phoneaos.wear.ui.ex.LiveOrb
import com.past9.phoneaos.wear.ui.ex.LiveWavy
import com.past9.phoneaos.wear.ui.ex.LocalLook
import com.past9.phoneaos.wear.ui.ex.Mood
import com.past9.phoneaos.wear.ui.ex.Orb
import com.past9.phoneaos.wear.ui.ex.SheetTitle
import com.past9.phoneaos.wear.ui.ex.TopLabel
import com.past9.phoneaos.wear.ui.ex.Txt
import com.past9.phoneaos.wear.ui.ex.gestureGlyph
import com.past9.phoneaos.wear.ui.ex.icon
import com.past9.phoneaos.wear.ui.ex.orb
import com.past9.phoneaos.wear.ui.ex.rememberClock
import com.past9.phoneaos.wear.ui.ex.rememberT
import com.past9.phoneaos.wear.ui.ex.shapeBlob
import com.past9.phoneaos.wear.ui.ex.voice
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The call, the ring, the gestures, the home screen and the settings, in Material 3 Expressive (the same kit as Screens.kt). No pop-up
 * dialogs: choices rise as sheets. What each screen does is the engine's (WatchCall, WatchRing, WatchGestures, HomeLauncher); this
 * file only draws it.
 */

private val err = Color(0xFFF2B8B5)

/**
 * The platform's double pinch (Wear OS 7, Pixel Watch 3 and newer, `oneHandedGesture`) as this control's primary action. On every other
 * watch the API does nothing, so the control stays an ordinary button (a gesture-only action is never hidden, per Google's guidance).
 */
@Composable
fun Modifier.primaryGesture(label: String, onGesture: () -> Unit): Modifier {
    val config = rememberOneHandedGestureConfiguration(action = OneHandedGestureAction.Primary, priority = OneHandedGesturePriority.Clickable)
    return this.oneHandedGesture(gestureConfiguration = config, onGestureLabel = label, onGesture = onGesture)
}

/** A two-part bar rising from the bottom bezel: a quiet segment on the left, the main one on the right. */
@Composable
private fun BoxScope.TwoBar(g: Geo, left: String, onLeft: () -> Unit, right: String, onRight: () -> Unit, rightFill: Color, rightText: Color, leftFill: Color, leftText: Color, leftModifier: Modifier = Modifier) {
    val bh = g.dp(46f); val bw = g.d * 0.7f
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
    Row(Modifier.align(Alignment.BottomCenter).offset(0.dp, g.dp(2f)).size(bw, bh).clip(shape)) {
        Box(Modifier.weight(0.42f).fillMaxSize().background(leftFill).then(leftModifier).clickable(onClick = onLeft), contentAlignment = Alignment.TopCenter) { Box(Modifier.padding(top = g.dp(12f))) { Txt(left, 11.5f, leftText, FontWeight.Bold, maxLines = 1) } }
        Box(Modifier.weight(0.58f).fillMaxSize().background(rightFill).clickable(onClick = onRight), contentAlignment = Alignment.TopCenter) { Box(Modifier.padding(top = g.dp(11f))) { Txt(right, 12.5f, rightText, FontWeight.Bold, maxLines = 1) } }
    }
}

// ───────── Ring ─────────

/**
 * What rings: a wake-up or an approval. A wake is answered here (I'm on it, Snooze); an approval is reviewed. Double pinch snoozes a
 * ringing wake (Google's own example of the primary action); wrist turn is the system's back, which the ring screen treats as silence.
 */
@Composable
fun RingScreen(item: RingItem, onAck: () -> Unit, onSnooze: () -> Unit, onReview: () -> Unit) {
    val pal = LocalLook.current.pal
    val critical = item.kind != RingKind.APPROVAL && item.severity == "critical"
    val bg = if (critical) Color(0xFF410E0B) else Color(0xFF2B1700)
    val accent = if (critical) Color(0xFFF2B8B5) else pal.amber
    ExStage(bg) { g ->
        val W = g.w; val H = g.h; val cx = W / 2; val cy = H * 0.27f
        val t = rememberT()
        // a ripple of rings from the agent, and a small shake: the agent is ringing
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(cx.toPx(), cy.toPx())
            for (k in 0 until 3) {
                val ph = (t * 0.55f + k / 3f) % 1f
                drawCircle(accent.copy(alpha = (1f - ph) * 0.32f), g.d.toPx() * (0.15f + 0.55f * ph), c, style = androidx.compose.ui.graphics.drawscope.Stroke(2.2f * density * (1f - ph * 0.6f)))
            }
            shapeBlob(MaterialShapes.Cookie12Sided, c, g.d.toPx() * 0.4f, if (critical) Color(0xFF8C1D18) else pal.amberC, t * 8f)
        }
        val shake = sin(t * 38f) * 2.4f * (if (sin(t * 3.1f) > 0.1f) 1f else 0f)
        Box(Modifier.fillMaxSize().graphicsLayerRotate(shake)) { Orb(cx, cy, g.d * 0.115f, Mood.Needs, 0f, 0f, LocalLook.current.kind, 0f) }
        TopLabel(stringResource(if (item.kind == RingKind.APPROVAL) R.string.needs_you else R.string.ring_wake).uppercase(), accent, 10f, FontWeight.Bold, 2f)
        val half = g.chord(H * 0.62f, 14.dp)
        Box(Modifier.offset(cx - half, H * 0.455f).width(half * 2), contentAlignment = Alignment.Center) { Txt(item.title, 17f, Color.White, FontWeight.Black, -0.3f, lh = 19f, maxLines = 2) }
        if (item.whatToDo.isNotBlank()) Box(Modifier.offset(cx - half, H * 0.455f + g.dp(44f)).width(half * 2), contentAlignment = Alignment.Center) { Txt(item.whatToDo, 11f, Color.White.copy(alpha = 0.7f), lh = 13f, maxLines = 2) }
        if (item.kind == RingKind.WAKE) TwoBar(g, stringResource(R.string.ring_snooze), onSnooze, stringResource(R.string.ring_on_it), onAck, accent, Color(0xFF2B1700), Color.White.copy(alpha = 0.14f), Color.White, Modifier.primaryGesture(stringResource(R.string.ring_snooze), onSnooze))
        else TwoBar(g, stringResource(R.string.ring_dismiss), onSnooze, stringResource(R.string.ring_review), onReview, accent, Color(0xFF2B1700), Color.White.copy(alpha = 0.14f), Color.White)
    }
}

private fun Modifier.graphicsLayerRotate(deg: Float): Modifier = this.then(Modifier.graphicsLayer { rotationZ = deg })

// ───────── Call ─────────

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

@Composable
private fun soundWord(side: String) = stringResource(if (side == Side.WATCH) R.string.sound_watch else R.string.sound_phone)

/**
 * The call: the agent reacts to who is speaking, a timer, where the sound is (tap it for the route sheet), Mute and End. Once it is
 * over the reason is shown with Call again and Use the phone's sound.
 */
@Composable
fun CallScreen(s: WatchCallCore.State, agent: String, onMute: () -> Unit, onEnd: () -> Unit, onAgain: () -> Unit, onPhoneAudio: () -> Unit, previewSheet: Boolean = false, previewSecs: Int = -1) {
    val look = LocalLook.current; val pal = look.pal
    val over = s.phase == WatchCallCore.ENDED || s.phase == WatchCallCore.FAILED || s.phase == WatchCallCore.IDLE
    var sheet by remember { mutableStateOf(previewSheet) }
    val started = remember(s.id) { android.os.SystemClock.elapsedRealtime() }
    var secs by remember { mutableStateOf(if (previewSecs >= 0) previewSecs else 0) }
    LaunchedEffect(s.id, over) { while (!over && previewSecs < 0) { secs = ((android.os.SystemClock.elapsedRealtime() - started) / 1000).toInt(); kotlinx.coroutines.delay(1000) } }
    ExStage { g ->
        val W = g.w; val H = g.h; val cx = W / 2; val cy = H * 0.31f
        val t = if (!over) rememberT() else 0f
        val speaking = s.sub == CallView.SPEAKING && s.phase == WatchCallCore.LIVE
        val level = if (over || s.muted) 0f else if (speaking) voice(t, 2f) * 0.85f else if (s.sub == CallView.THINKING) 0f else 0.25f + 0.2f * sin(t * 2f)
        val mood = when { over -> Mood.Asleep; s.muted -> Mood.Muted; s.sub == CallView.THINKING -> Mood.Thinking; else -> Mood.Call }
        Canvas(Modifier.fillMaxSize()) {
            shapeBlob(MaterialShapes.Cookie12Sided, Offset(cx.toPx(), cy.toPx()), g.d.toPx() * 0.47f, if (over) pal.surfHi else pal.container, t * 5f)
        }
        if (!over) LiveWavy(cx, cy, g.d * 0.18f, g.d * (0.004f + 0.014f * level), 13, pal.primary, 3.dp * g.k)
        TopLabel(((agent.ifBlank { stringResource(R.string.agent_default) }) + "  ·  " + phaseText(s)).uppercase(), if (s.degraded || s.phase == WatchCallCore.FAILED) err else pal.onContainer, 9.5f, FontWeight.Bold, 1.6f)
        Orb(cx, cy, g.d * 0.125f, mood, t, level, look.kind, 0f)
        if (!over) {
            Box(Modifier.offset(cx - g.dp(80f), H * 0.535f).width(g.dp(160f)), contentAlignment = Alignment.Center) { Txt("%02d:%02d".format(secs / 60, secs % 60), 30f, Color.White, FontWeight.Black, -0.5f) }
            val route = s.plan?.let { stringResource(if (it.spk == Side.WATCH) R.string.call_route_watch else R.string.call_route_phone) }.orEmpty()
            if (route.isNotBlank()) Box(Modifier.offset(cx - g.dp(66f), H * 0.69f).size(g.dp(132f), g.dp(26f)).clip(RoundedCornerShape(50)).background(pal.secC).clickable { sheet = true }, contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) { IconBox(Ic.volume, g.dp(13f), pal.onSecC); Spacer(Modifier.width(g.dp(4f))); Txt(route, 11f, pal.onSecC, FontWeight.SemiBold, maxLines = 1) }
            }
            // bottom group: mute | end | route
            val bh = g.dp(46f); val bw = g.d * 0.72f
            val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
            Row(Modifier.align(Alignment.BottomCenter).offset(0.dp, g.dp(2f)).size(bw, bh).clip(shape).background(Color(0xFF1E1D24)).then(Modifier)) {
                Box(Modifier.weight(0.27f).fillMaxSize().then(Modifier.primaryGesture(stringResource(R.string.call_mute), onMute)).clickable(onClick = onMute), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.padding(top = g.dp(12f))) { IconBox(Ic.mic, g.dp(19f), if (s.muted) pal.amber else pal.onSecC) }
                    if (s.muted) Canvas(Modifier.fillMaxSize()) { drawLine(pal.amber, Offset(size.width * 0.3f, 13.dp.toPx() * g.k), Offset(size.width * 0.7f, 31.dp.toPx() * g.k), 2.2f * density, androidx.compose.ui.graphics.StrokeCap.Round) }
                }
                Box(Modifier.weight(0.46f).fillMaxSize().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(V2endRed).clickable(onClick = onEnd), contentAlignment = Alignment.TopCenter) { Box(Modifier.padding(top = g.dp(10f))) { IconBox(Ic.call, g.dp(22f), Color.White, rot = 135f) } }
                Box(Modifier.weight(0.27f).fillMaxSize().clickable { sheet = true }, contentAlignment = Alignment.TopCenter) { Box(Modifier.padding(top = g.dp(12f))) { IconBox(Ic.volume, g.dp(19f), pal.onSecC) } }
            }
            ExSheet(sheet, { sheet = false }, 0.66f) { gg ->
                SheetTitle(stringResource(R.string.call_sound_title))
                Column(Modifier.align(Alignment.TopCenter).padding(top = gg.dp(46f)).padding(horizontal = gg.w * 0.1f), verticalArrangement = Arrangement.spacedBy(gg.dp(6f))) {
                    val onWatch = s.plan?.spk == Side.WATCH
                    ExPill(stringResource(R.string.call_route_watch), { sheet = false }, icon = Ic.volume, container = if (onWatch) pal.container else pal.secC, content = if (onWatch) pal.onContainer else pal.onSecC, trailing = if (onWatch) ({ IconBox(Ic.check, gg.dp(16f), pal.onContainer) }) else null)
                    ExPill(stringResource(R.string.call_route_phone), { sheet = false; onPhoneAudio() }, icon = Ic.call, container = if (!onWatch) pal.container else pal.secC, content = if (!onWatch) pal.onContainer else pal.onSecC, trailing = if (!onWatch) ({ IconBox(Ic.check, gg.dp(16f), pal.onContainer) }) else null)
                }
            }
        } else {
            val half = g.chord(H * 0.6f, 14.dp)
            Box(Modifier.offset(cx - half, H * 0.565f).width(half * 2), contentAlignment = Alignment.Center) { Txt(s.error.ifBlank { phaseText(s) }, 14f, if (s.phase == WatchCallCore.FAILED) err else Color.White, FontWeight.Bold, lh = 16f, maxLines = 2) }
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = g.dp(16f)).padding(horizontal = W * 0.17f), verticalArrangement = Arrangement.spacedBy(g.dp(4f))) {
                ExPill(stringResource(R.string.call_again), onAgain, container = pal.primary, content = pal.onPrimary, icon = Ic.call, height = 34f)
                ExPill(stringResource(R.string.call_use_phone_short), onPhoneAudio, height = 34f)
            }
        }
    }
}

private val V2endRed = Color(0xFFE5534B)

// ───────── Gestures and rings ─────────

private fun glyphKind(g: Gesture) = when (g) {
    Gesture.FLICK, Gesture.DOUBLE_FLICK -> com.past9.phoneaos.wear.ui.ex.GlyphKind.Flick
    Gesture.DOUBLE_TAP -> com.past9.phoneaos.wear.ui.ex.GlyphKind.Pinch
    Gesture.SHAKE -> com.past9.phoneaos.wear.ui.ex.GlyphKind.Shake
    Gesture.RAISE -> com.past9.phoneaos.wear.ui.ex.GlyphKind.Raise
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
 * Gestures and rings: each gesture he can make is mapped to what it does (tap a row, a sheet rises with the choices). A gesture this
 * watch cannot detect is shown as "Not on this watch", never offered. Nothing is on until he sets it; Recommended is one tap.
 */
@Composable
fun GesturesScreen(
    map: GestureMap, profile: DeviceProfile, approvalMode: String,
    onMap: (GestureMap) -> Unit, onApprovalMode: (String) -> Unit, onNotificationAccess: () -> Unit, onHomeScreen: () -> Unit,
    previewPick: Gesture? = null, previewMode: Boolean = false,
) {
    val pal = LocalLook.current.pal
    var picking by remember { mutableStateOf<Gesture?>(previewPick) }
    var pickingMode by remember { mutableStateOf(previewMode) }
    ExStage { g ->
        val W = g.w; val H = g.h
        CrownColumn(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(H * 0.2f))
            Column(Modifier.padding(horizontal = W * 0.09f).padding(bottom = g.dp(40f)), verticalArrangement = Arrangement.spacedBy(g.dp(6f))) {
                Gesture.entries.forEach { gest ->
                    val ok = GestureCapabilities.availability(gest, profile) == Availability.WORKS
                    ExPill(
                        gestureName(gest), if (ok) ({ picking = gest }) else null, enabled = ok, height = 54f,
                        secondary = if (ok) actionName(map.actionFor(gest)) else stringResource(R.string.g_unavailable),
                        trailing = { Canvas(Modifier.size(g.dp(30f))) { gestureGlyph(glyphKind(gest), Offset(size.width / 2, size.height / 2), size.width, if (ok) pal.primary else pal.dimText, 0.4f) } },
                    )
                }
                ExPill(stringResource(R.string.g_recommended), { onMap(GestureMap.recommended().copy(alwaysOn = map.alwaysOn)) }, container = pal.container, content = pal.onContainer)
                ExPill(stringResource(R.string.g_always_on), { onMap(map.copy(alwaysOn = !map.alwaysOn)) }, secondary = stringResource(if (map.alwaysOn) R.string.on else R.string.off), height = 52f)
                Txt(stringResource(R.string.g_battery_note), 10.5f, pal.dimText, align = TextAlign.Start, lh = 13f)
                val system = GestureCapabilities.systemGestures(profile)
                Txt(stringResource(if (system == Availability.SYSTEM_ONLY) R.string.g_system_yes else R.string.g_system_no), 10.5f, pal.dimText, align = TextAlign.Start, lh = 13f)
                Spacer(Modifier.height(g.dp(4f)))
                Txt(stringResource(R.string.rings_title).uppercase(), 10.5f, pal.primary, FontWeight.Bold, 2f, TextAlign.Start)
                ExPill(stringResource(R.string.rings_approvals), { pickingMode = true }, secondary = ringModeName(approvalMode), height = 52f)
                ExPill(stringResource(R.string.rings_dnd), onNotificationAccess)
                ExPill(stringResource(R.string.home_settings), onHomeScreen, icon = Ic.apps)
            }
        }
        EdgeFades(0.19f, 0.14f)
        TopLabel(stringResource(R.string.gestures_title).uppercase(), pal.primary, 10.5f, FontWeight.Bold, 2.2f)
        ExSheet(picking != null, { picking = null }, 0.8f) { gg ->
            val gest = picking
            if (gest != null) {
                SheetTitle(gestureName(gest))
                Column(Modifier.align(Alignment.TopCenter).padding(top = gg.dp(44f)).padding(horizontal = gg.w * 0.1f), verticalArrangement = Arrangement.spacedBy(gg.dp(5f))) {
                    GestureAction.entries.forEach { a ->
                        val sel = map.actionFor(gest) == a
                        ExPill(actionName(a), { onMap(map.with(gest, a)); picking = null }, container = if (sel) pal.container else pal.secC, content = if (sel) pal.onContainer else pal.onSecC, height = 36f,
                            trailing = { Box(Modifier.size(gg.dp(15f)).clip(RoundedCornerShape(50)).background(if (sel) pal.primary else Color.Transparent).let { if (sel) it else it.background(Color.White.copy(alpha = 0.12f)) }) })
                    }
                }
            }
        }
        ExSheet(pickingMode, { pickingMode = false }, 0.62f) { gg ->
            SheetTitle(stringResource(R.string.rings_approvals))
            Column(Modifier.align(Alignment.TopCenter).padding(top = gg.dp(44f)).padding(horizontal = gg.w * 0.1f), verticalArrangement = Arrangement.spacedBy(gg.dp(5f))) {
                listOf(ApprovalRingMode.BUZZ, ApprovalRingMode.FULL, ApprovalRingMode.OFF).forEach { m ->
                    val sel = approvalMode == m
                    ExPill(ringModeName(m), { onApprovalMode(m); pickingMode = false }, container = if (sel) pal.container else pal.secC, content = if (sel) pal.onContainer else pal.onSecC, height = 38f)
                }
            }
        }
    }
}

// ───────── Home screen (the launcher) ─────────

@Composable
private fun statusWord(s: AgentStatus) = stringResource(when (s) {
    AgentStatus.IDLE -> R.string.face_idle; AgentStatus.WORKING -> R.string.face_working; AgentStatus.NEEDS_YOU -> R.string.face_needs
    AgentStatus.IN_CALL -> R.string.face_call; AgentStatus.OFFLINE -> R.string.face_offline
})

@Composable
private fun rememberAppIcons(apps: List<AppEntry>): Map<String, ImageBitmap?> {
    val ctx = LocalContext.current
    return remember(apps) {
        apps.associate { a ->
            a.pkg to runCatching { ctx.packageManager.getActivityIcon(ComponentName(a.pkg, a.activity)).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
}

/**
 * The agent as the home screen: the agent first (what it is doing, Talk), the clock big; swipe up for the apps, a honeycomb with the
 * agent in the middle; recents are what was opened from here.
 */
@Composable
fun HomeScreen(
    agent: String, status: AgentStatus, waiting: Int, apps: List<AppEntry>, recents: List<AppEntry>, resetKey: Int,
    onTalk: () -> Unit, onOpenAgent: () -> Unit, onApp: (AppEntry) -> Unit, onSettings: () -> Unit,
    previewGrid: Boolean = false, previewRecents: Boolean = false,
) {
    val look = LocalLook.current; val pal = look.pal
    var grid by remember(resetKey) { mutableStateOf(previewGrid) }
    var recentSheet by remember { mutableStateOf(previewRecents) }
    val clock = rememberClock()
    val date = remember { java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")).uppercase() }
    val needs = waiting > 0
    val icons = rememberAppIcons(apps + recents)
    ExStage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        if (!grid) {
            var drag by remember { mutableStateOf(0f) }
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures(onDragEnd = { if (drag < -60f) grid = true; drag = 0f }, onDragCancel = { drag = 0f }) { _, d -> drag += d.y }
            }) {
                val cy = H * 0.57f
                LiveBlob(MaterialShapes.Cookie12Sided, cx, cy, g.d * 0.46f, if (needs) pal.amberC else pal.container)
                LiveOrb(cx, cy, g.d * 0.14f, if (needs) Mood.Needs else Mood.Idle)
                Box(Modifier.offset(cx - g.d * 0.23f, cy - g.d * 0.23f).size(g.d * 0.46f).clip(RoundedCornerShape(50)).clickable(onClick = onOpenAgent))
                Box(Modifier.offset(cx - g.dp(100f), H * 0.045f).width(g.dp(200f)), contentAlignment = Alignment.Center) { Txt(clock, 52f, Color.White, FontWeight.Black, -1.5f, maxLines = 1) }
                Box(Modifier.offset(cx - g.dp(90f), H * 0.275f).width(g.dp(180f)), contentAlignment = Alignment.Center) { Txt(if (needs) stringResource(R.string.tile_many_wait, waiting).uppercase() else date + "  ·  " + statusWord(status).uppercase(), 9.5f, if (needs) pal.amber else pal.primary, FontWeight.Bold, 1.6f, maxLines = 1) }
                ExShapeBtn(cx - g.d * 0.31f, H * 0.60f, g.dp(36f), MaterialShapes.Cookie6Sided, pal.secC, Ic.history, pal.onSecC, onClick = { recentSheet = true })
                ExShapeBtn(cx + g.d * 0.31f, H * 0.60f, g.dp(36f), MaterialShapes.Cookie6Sided, pal.secC, Ic.apps, pal.onSecC, onClick = { grid = true })
                ExEdge(g, stringResource(R.string.talk), if (needs) pal.amber else pal.primary, if (needs) pal.onAmber else pal.onPrimary, Ic.mic, onClick = onTalk)
            }
            ExSheet(recentSheet, { recentSheet = false }, 0.78f) { gg ->
                SheetTitle(stringResource(R.string.home_recent))
                CrownColumn(Modifier.fillMaxSize().padding(top = gg.dp(40f))) {
                    Column(Modifier.padding(horizontal = gg.w * 0.1f).padding(bottom = gg.dp(30f)), verticalArrangement = Arrangement.spacedBy(gg.dp(5f))) {
                        if (recents.isEmpty()) Txt(stringResource(R.string.home_no_recent), 12f, pal.dimText, lh = 15f)
                        recents.forEach { a -> ExPill(a.label, { recentSheet = false; onApp(a) }, height = 40f, trailing = { icons[a.pkg]?.let { bmp -> Canvas(Modifier.size(gg.dp(24f))) { drawImage(bmp, dstSize = IntSize(size.width.toInt(), size.height.toInt())) } } }) }
                    }
                }
            }
        } else {
            AppGrid(g, apps, icons, onApp = onApp, onBack = { grid = false }, onSettings = onSettings, onOpenAgent = onOpenAgent)
        }
    }
}

/** The honeycomb: the agent in the middle, the apps around it, smaller toward the edge (a fish-eye); drag or turn the crown to move it. */
@Composable
private fun BoxScope.AppGrid(g: Geo, apps: List<AppEntry>, icons: Map<String, ImageBitmap?>, onApp: (AppEntry) -> Unit, onBack: () -> Unit, onSettings: () -> Unit, onOpenAgent: () -> Unit) {
    val look = LocalLook.current; val pal = look.pal
    val others = apps.drop(1)
    val step = g.d.value * 0.245f; val cell = g.d.value * 0.2f
    // hex spiral: ring k has 6k cells
    val spots = remember(others.size) {
        val out = ArrayList<Pair<Float, Float>>(); var k = 1
        val dirs = listOf(1 to 0, 0 to 1, -1 to 1, -1 to 0, 0 to -1, 1 to -1)
        while (out.size < others.size) {
            var q = 0; var r = 0 // k steps in direction 4 from the origin, then walk the ring
            repeat(k) { q += dirs[4].first; r += dirs[4].second }
            for (side in 0 until 6) repeat(k) {
                out += (step * (q + r / 2f)) to (step * r * 0.8660254f)
                q += dirs[side].first; r += dirs[side].second
            }
            k++
        }
        out.take(others.size)
    }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val reach = remember(spots) { (spots.maxOfOrNull { sqrt(it.first * it.first + it.second * it.second) } ?: 0f) }
    fun clampPan(o: Offset): Offset { val len = sqrt(o.x * o.x + o.y * o.y); val lim = reach + step * 0.4f; return if (len > lim) Offset(o.x / len * lim, o.y / len * lim) else o }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val dens = androidx.compose.ui.platform.LocalDensity.current.density
    val maxDist = g.d.value * 0.56f
    fun scaleAt(dist: Float) = (1.1f - 0.62f * (dist / maxDist)).coerceIn(0.2f, 1.1f)
    var focusIdx by remember { mutableStateOf(-1) }
    Box(
        Modifier.fillMaxSize()
            .onRotaryScrollEvent { pan = clampPan(Offset(pan.x, pan.y - it.verticalScrollPixels / dens)); true }
            .focusRequester(focus).focusTarget()
            .pointerInput(spots) {
                detectDragGestures { _, d -> pan = clampPan(pan + d / dens) }
            }
            .pointerInput(spots, pan) {
                detectTapGestures { o ->
                    val c = Offset(size.width / 2f, size.height / 2f); val p = Offset((o.x - c.x) / dens, (o.y - c.y) / dens)
                    // the agent's own cell, then the apps
                    run {
                        val d0 = sqrt((pan.x - p.x) * (pan.x - p.x) + (pan.y - p.y) * (pan.y - p.y))
                        val dist = sqrt(pan.x * pan.x + pan.y * pan.y)
                        if (d0 < cell * scaleAt(dist) / 2f) { onOpenAgent(); return@detectTapGestures }
                    }
                    var best = -1; var bd = Float.MAX_VALUE
                    spots.forEachIndexed { i, s ->
                        val px = pan.x + s.first; val py = pan.y + s.second
                        val dist = sqrt(px * px + py * py)
                        val dd = sqrt((px - p.x) * (px - p.x) + (py - p.y) * (py - p.y))
                        if (dd < cell * scaleAt(dist) / 2f && dd < bd) { best = i; bd = dd }
                    }
                    if (best >= 0) onApp(others[best])
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            // nearest-to-centre decides the label
            var nearest = -1; var nd = Float.MAX_VALUE
            spots.forEachIndexed { i, s -> val px = pan.x + s.first; val py = pan.y + s.second; val dd = px * px + py * py; if (dd < nd) { nd = dd; nearest = i } }
            val agentD = pan.x * pan.x + pan.y * pan.y
            if (agentD < nd) nearest = -1
            if (nearest != focusIdx) focusIdx = nearest
            fun draw(px: Float, py: Float, isAgent: Boolean, idx: Int) {
                val dist = sqrt(px * px + py * py); if (dist > maxDist + cell) return
                val sc = scaleAt(dist); val sz = cell * sc * density
                val ctr = Offset(c.x + px * density, c.y + py * density)
                val alpha = ((1f - (dist / maxDist - 0.65f) * 2.4f)).coerceIn(0f, 1f); if (alpha <= 0.02f) return
                if (isAgent) {
                    shapeBlob(MaterialShapes.Cookie12Sided, ctr, sz * 1.1f, pal.container.copy(alpha = alpha))
                    orb(ctr, sz * 0.36f, Mood.Idle, 0f, 0f, look.kind, 0f, density)
                } else {
                    val tone = if (idx % 3 == 0) pal.container else if (idx % 3 == 1) pal.secC else pal.surfHi
                    shapeBlob(MaterialShapes.Cookie9Sided, ctr, sz, tone.copy(alpha = alpha))
                    val bmp = icons[others[idx].pkg]
                    if (bmp != null) {
                        clipPath(com.past9.phoneaos.wear.ui.ex.polyPath(MaterialShapes.Cookie9Sided, ctr.x, ctr.y, sz * 0.8f)) { drawImage(bmp, dstOffset = IntOffset((ctr.x - sz * 0.4f).toInt(), (ctr.y - sz * 0.4f).toInt()), dstSize = IntSize((sz * 0.8f).toInt(), (sz * 0.8f).toInt()), alpha = alpha) }
                    } else icon(Ic.apps, ctr, sz * 0.4f, pal.onSecC.copy(alpha = alpha))
                }
            }
            for (i in spots.indices.reversed()) draw(pan.x + spots[i].first, pan.y + spots[i].second, false, i)
            draw(pan.x, pan.y, true, -1)
        }
        val label = if (focusIdx < 0) look.let { stringResource(R.string.agent_default) } else others.getOrNull(focusIdx)?.label.orEmpty()
        EdgeFades(0.0001f, 0.16f)
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = g.dp(14f)).width(g.d * 0.6f), contentAlignment = Alignment.Center) { Txt(label, 13f, Color.White, FontWeight.Bold, maxLines = 1) }
        ExShapeBtn(g.w / 2 - g.d * 0.0f, g.dp(18f), g.dp(28f), MaterialShapes.Cookie6Sided, pal.secC, Ic.back, pal.onSecC, rot = 90f, onClick = onBack)
        ExShapeBtn(g.w / 2 + g.d * 0.26f, g.h - g.dp(30f), g.dp(26f), MaterialShapes.Cookie6Sided, pal.secC, Ic.chevron, pal.onSecC, onClick = onSettings)
    }
}

// ───────── Home screen settings: choose and switch back ─────────

/**
 * Using the agent as the home screen is his choice. Two cards say where it stands; choosing opens a sheet that says what will happen,
 * and the way back is always one tap. On a watch whose system has no home picker it says how to choose.
 */
@Composable
fun HomeSettingsScreen(state: HomeGuide.State, hasPicker: Boolean, note: String, onTurnOn: () -> Unit, onTurnOff: () -> Unit, onOpenSystem: (() -> Unit)?, previewSheet: Int = 0) {
    val look = LocalLook.current; val pal = look.pal
    var choose by remember { mutableStateOf(previewSheet == 1) }
    var back by remember { mutableStateOf(previewSheet == 2) }
    val agentHome = state == HomeGuide.State.HELD_BY_THIS_APP
    val asked = state == HomeGuide.State.ON_THIS_APP_ASKED
    ExStage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        CrownColumn(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(H * 0.2f))
            Column(Modifier.padding(horizontal = W * 0.09f).padding(bottom = g.dp(30f)), verticalArrangement = Arrangement.spacedBy(g.dp(6f))) {
                ExPill(stringResource(R.string.home_card_agent), { if (!agentHome) choose = true }, secondary = stringResource(if (agentHome) R.string.home_state_mine else if (asked) R.string.home_state_asked else R.string.home_card_agent_sub), height = 66f,
                    container = if (agentHome) pal.container else pal.secC, content = if (agentHome) pal.onContainer else pal.onSecC,
                    trailing = { if (agentHome) IconBox(Ic.check, g.dp(18f), pal.onContainer) })
                ExPill(stringResource(R.string.home_card_standard), { if (agentHome || asked) back = true }, secondary = stringResource(R.string.home_card_standard_sub), height = 58f,
                    container = if (!agentHome && !asked) pal.container else pal.secC, content = if (!agentHome && !asked) pal.onContainer else pal.onSecC,
                    trailing = { if (!agentHome && !asked) IconBox(Ic.check, g.dp(18f), pal.onContainer) })
                if (state == HomeGuide.State.HELD_BY_OTHER) Txt(stringResource(R.string.home_state_other), 10.5f, pal.dimText, align = TextAlign.Start, lh = 13f)
                if (onOpenSystem != null) ExPill(stringResource(R.string.home_open_system), onOpenSystem, height = 40f)
                if (!hasPicker) Txt(stringResource(R.string.home_no_picker), 10.5f, pal.dimText, align = TextAlign.Start, lh = 13f)
                if (note.isNotBlank()) Txt(note, 10.5f, pal.amber, align = TextAlign.Start, lh = 13f)
                Txt(stringResource(R.string.home_optional), 10f, pal.dimText, lh = 12f)
            }
        }
        EdgeFades(0.19f, 0.1f)
        TopLabel(stringResource(R.string.home_settings).uppercase(), pal.primary, 10.5f, FontWeight.Bold, 2.2f)
        ExSheet(choose, { choose = false }, 0.86f) { gg ->
            Box(Modifier.align(Alignment.TopCenter).padding(top = gg.dp(30f)).padding(horizontal = gg.w * 0.1f)) { Txt(stringResource(R.string.home_choose_title), 14.5f, Color.White, FontWeight.Bold, lh = 17f, maxLines = 2) }
            Box(Modifier.align(Alignment.TopCenter).padding(top = gg.dp(68f)).padding(horizontal = gg.w * 0.12f)) { Txt(stringResource(R.string.home_choose_body), 10.5f, pal.dimText, lh = 13.5f, maxLines = 4) }
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = gg.dp(12f)).padding(horizontal = gg.w * 0.14f), verticalArrangement = Arrangement.spacedBy(gg.dp(4f))) {
                ExPill(stringResource(R.string.home_use_short), { choose = false; onTurnOn() }, container = pal.primary, content = pal.onPrimary, height = 38f)
                ExPill(stringResource(R.string.not_now), { choose = false }, container = Color.Transparent, content = pal.dimText, height = 32f)
            }
        }
        ExSheet(back, { back = false }, 0.86f) { gg ->
            Box(Modifier.align(Alignment.TopCenter).padding(top = gg.dp(30f)).padding(horizontal = gg.w * 0.1f)) { Txt(stringResource(R.string.home_back_title), 14.5f, Color.White, FontWeight.Bold, lh = 17f, maxLines = 2) }
            Box(Modifier.align(Alignment.TopCenter).padding(top = gg.dp(68f)).padding(horizontal = gg.w * 0.12f)) { Txt(stringResource(R.string.home_back_body), 10.5f, pal.dimText, lh = 13.5f, maxLines = 4) }
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = gg.dp(12f)).padding(horizontal = gg.w * 0.14f), verticalArrangement = Arrangement.spacedBy(gg.dp(4f))) {
                ExPill(stringResource(R.string.home_back_short), { back = false; onTurnOff() }, container = pal.primary, content = pal.onPrimary, height = 38f)
                ExPill(stringResource(R.string.home_keep), { back = false }, container = Color.Transparent, content = pal.dimText, height = 32f)
            }
        }
    }
}

// ───────── Settings ─────────

/** The watch's settings in one place: gestures and rings, the watch home, pairing. */
@Composable
fun WatchSettingsScreen(agent: String, onGestures: () -> Unit, onHome: () -> Unit, onPair: (() -> Unit)? = null) {
    val pal = LocalLook.current.pal
    ExStage { g ->
        val W = g.w; val H = g.h
        CrownColumn(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(H * 0.2f))
            Column(Modifier.padding(horizontal = W * 0.09f).padding(bottom = g.dp(30f)), verticalArrangement = Arrangement.spacedBy(g.dp(6f))) {
                ExPill(stringResource(R.string.gestures_title), onGestures, secondary = stringResource(R.string.settings_gestures_sub), icon = Ic.history, height = 56f)
                ExPill(stringResource(R.string.home_settings), onHome, secondary = stringResource(R.string.settings_home_sub), icon = Ic.apps, height = 56f)
                if (onPair != null) ExPill(stringResource(R.string.pair_title), onPair, secondary = agent.ifBlank { stringResource(R.string.agent_default) }, icon = Ic.call, height = 56f)
            }
        }
        EdgeFades(0.19f, 0.1f)
        TopLabel(stringResource(R.string.settings_title).uppercase(), pal.primary, 10.5f, FontWeight.Bold, 2.2f)
    }
}

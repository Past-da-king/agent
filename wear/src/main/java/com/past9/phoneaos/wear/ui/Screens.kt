package com.past9.phoneaos.wear.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.wear.R
import com.past9.phoneaos.wear.ui.ex.CrownColumn
import com.past9.phoneaos.wear.ui.ex.EdgeFades
import com.past9.phoneaos.wear.ui.ex.ExEdge
import com.past9.phoneaos.wear.ui.ex.ExPill
import com.past9.phoneaos.wear.ui.ex.ExShapeBtn
import com.past9.phoneaos.wear.ui.ex.ExStage
import com.past9.phoneaos.wear.ui.ex.Ic
import com.past9.phoneaos.wear.ui.ex.IconBox
import com.past9.phoneaos.wear.ui.ex.LiveBlob
import com.past9.phoneaos.wear.ui.ex.LiveOrb
import com.past9.phoneaos.wear.ui.ex.LiveWavy
import com.past9.phoneaos.wear.ui.ex.LocalGeo
import com.past9.phoneaos.wear.ui.ex.LocalLook
import com.past9.phoneaos.wear.ui.ex.Mood
import com.past9.phoneaos.wear.ui.ex.TopLabel
import com.past9.phoneaos.wear.ui.ex.Txt
import com.past9.phoneaos.wear.ui.ex.TxtA
import com.past9.phoneaos.wear.ui.ex.rememberClock
import com.past9.phoneaos.wear.ui.ex.shapeBlob
import com.past9.phoneaos.wear.ui.ex.icon
import com.past9.phoneaos.wear.ui.ex.smooth
import com.past9.phoneaos.wearproto.Exchange
import com.past9.phoneaos.wearproto.Pending
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin

/*
 * The watch screens in Material 3 Expressive, in the Agent's own tokens (docs/wear.md, "Look"). The agent on screen is the phone's own
 * mascot (the one the user picked); the colours come from one seed, the accent the phone syncs. Each screen keeps the signature the
 * v1 screens had, so the navigation and the engine's screens call them unchanged.
 */

// ───────── Talk (home) ─────────

/**
 * The talk screen. The agent in the middle on a tonal shape, tap it (or the big edge button) to talk. Call and Type sit either side;
 * scrolling down (the crown) reaches the quick replies, Speak on phone, Recent and Settings. The watch mic leads; when this watch
 * has none the edge button is Speak on phone and a line says why.
 */
@Composable
fun TalkScreen(
    agent: String, link: String, waiting: Int, micUsable: Boolean, quick: List<String>, hasRecent: Boolean, notice: String?,
    onMic: () -> Unit, onPhone: () -> Unit, onType: () -> Unit, onQuick: (String) -> Unit, onWaiting: () -> Unit, onRecent: () -> Unit, onTryMic: () -> Unit,
    /** Call the agent (a live voice call, docs/wear.md); and the screen for gestures, rings and the home screen. Null hides the row. */
    onCall: (() -> Unit)? = null, onMore: (() -> Unit)? = null,
) {
    val problem = notice ?: when (link) { "offline" -> stringResource(R.string.computer_offline); "none" -> stringResource(R.string.phone_unreachable); else -> null }
    val look = LocalLook.current; val pal = look.pal
    val clock = rememberClock()
    val needs = waiting > 0
    val mainLabel = stringResource(if (micUsable) R.string.talk else R.string.speak_on_phone)
    val scroll = rememberScrollState()
    ExStage { g ->
        val W = g.w; val H = g.h; val cx = W / 2; val cy = H * 0.385f
        CrownColumn(Modifier.fillMaxSize(), scroll) {
            Box(Modifier.fillMaxWidth().height(H)) {
                LiveBlob(MaterialShapes.Cookie12Sided, cx, cy, g.d * 0.5f, if (needs) pal.amberC else pal.container)
                LiveOrb(cx, cy, g.d * 0.155f, if (needs) Mood.Needs else Mood.Idle)
                // tap the agent to talk
                Box(Modifier.offset(cx - g.d * 0.25f, cy - g.d * 0.25f).size(g.d * 0.5f).clip(RoundedCornerShape(50)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = if (micUsable) onMic else onPhone))
                if (needs) Box(Modifier.offset(cx - g.dp(62f), H * 0.15f).size(g.dp(124f), g.dp(28f)).clip(RoundedCornerShape(50)).background(pal.amber).clickable(onClick = onWaiting), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBox(Ic.shield, g.dp(14f), pal.onAmber); Spacer(Modifier.width(g.dp(4f)))
                        Txt(if (waiting == 1) stringResource(R.string.tile_one_waits) else stringResource(R.string.tile_many_wait, waiting), 12f, pal.onAmber, FontWeight.Bold, maxLines = 1)
                    }
                }
                if (problem != null) Box(Modifier.offset(cx - g.dp(80f), H * 0.665f).width(g.dp(160f)), contentAlignment = Alignment.Center) { Txt(problem, 10.5f, V2err, lh = 12.5f, maxLines = 2) }
                else if (micUsable) Box(Modifier.offset(cx - g.dp(70f), H * 0.665f).width(g.dp(140f)), contentAlignment = Alignment.Center) { Txt(agent.ifBlank { stringResource(R.string.agent_default) }, 12f, pal.primary, FontWeight.Bold, 1.6f, maxLines = 1) }
                else Box(Modifier.offset(cx - g.dp(80f), H * 0.665f).width(g.dp(160f)).clickable(onClick = onTryMic), contentAlignment = Alignment.Center) { Txt(stringResource(R.string.mic_unavailable), 10.5f, pal.dimText, lh = 12.5f, maxLines = 2) }
                if (onCall != null) ExShapeBtn(cx - g.d * 0.30f, H * 0.60f, g.dp(40f), MaterialShapes.Cookie6Sided, pal.secC, Ic.call, pal.onSecC, onClick = onCall)
                ExShapeBtn(cx + g.d * 0.30f, H * 0.60f, g.dp(40f), MaterialShapes.Cookie6Sided, pal.secC, Ic.keyboard, pal.onSecC, onClick = onType)
                Box(Modifier.offset(cx - g.dp(8f), H * 0.585f).size(g.dp(16f)), contentAlignment = Alignment.Center) { IconBox(Ic.chevron, g.dp(16f), pal.dimText.copy(alpha = 0.6f), rot = 90f) }
            }
            Column(Modifier.padding(horizontal = W * 0.1f).padding(bottom = g.dp(70f)), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(g.dp(6f))) {
                if (micUsable) ExPill(stringResource(R.string.speak_on_phone), onPhone, icon = Ic.call)
                else ExPill(stringResource(R.string.try_mic), onTryMic, icon = Ic.mic)
                quick.forEach { q -> ExPill(q, { onQuick(q) }) }
                if (hasRecent) ExPill(stringResource(R.string.recent), onRecent, icon = Ic.history)
                if (onMore != null) ExPill(stringResource(R.string.settings_title), onMore, icon = Ic.apps)
            }
        }
        TopLabel(clock, Color.White, 14f, FontWeight.Bold)
        ExEdge(g, mainLabel, if (needs) pal.amber else pal.primary, if (needs) pal.onAmber else pal.onPrimary, if (micUsable) Ic.mic else Ic.call, onClick = if (needs) onWaiting else if (micUsable) onMic else onPhone)
    }
}

private val V2err = Color(0xFFF2B8B5)

// ───────── Listening ─────────

/** The mic is open (or the take is on its way to the phone). */
@Composable
fun ListeningScreen(level: Float, sending: Boolean, onStop: () -> Unit) {
    val look = LocalLook.current; val pal = look.pal
    ExStage(pal.deep) { g ->
        val W = g.w; val H = g.h; val cx = W / 2; val cy = H * 0.34f
        TopLabel(stringResource(if (sending) R.string.sending else R.string.listening).uppercase(), pal.onContainer, 10.5f, FontWeight.Bold, 2.4f)
        if (sending) LiveWavy(cx, cy, g.d * 0.255f, g.d * 0.007f, 9, pal.primary, 3.dp * g.k, speed = -5f, sweep = 250f, startSpin = 120f)
        else LiveWavy(cx, cy, g.d * 0.255f, g.d * (0.004f + 0.014f * level), 11, pal.primary, 3.dp * g.k)
        LiveOrb(cx, cy, g.d * 0.155f, if (sending) Mood.Thinking else Mood.Listening, level)
        val half = g.chord(H * 0.7f, 12.dp)
        Box(Modifier.offset(cx - half, H * 0.60f).width(half * 2), contentAlignment = Alignment.Center) {
            Txt(stringResource(if (sending) R.string.sending else R.string.listening), 19f, pal.onContainer, FontWeight.Bold, -0.2f, lh = 22f)
        }
        if (!sending) ExEdge(g, stringResource(R.string.stop), pal.secC, pal.onSecC, Ic.close, 0.5f, 40f, onClick = onStop)
    }
}

// ───────── Answer ─────────

/** What the answer screen draws. */
data class AnswerUi(val you: String, val status: String, val answer: String, val working: Boolean, val needsYou: Boolean, val problem: String)

private val timeToken = Regex("""\b\d{1,2}[:.]\d{2}\b""")

@Composable
fun AnswerScreen(ui: AnswerUi, onReply: () -> Unit, onStop: () -> Unit, onReview: () -> Unit, onRetry: () -> Unit) {
    val look = LocalLook.current; val pal = look.pal
    val thinking = ui.working && ui.answer.isBlank() && ui.problem.isBlank()
    ExStage(if (thinking) pal.deep else Color.Black) { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        if (thinking) {
            val cy = H * 0.34f
            TopLabel(stringResource(R.string.thinking).uppercase(), pal.onContainer, 10.5f, FontWeight.Bold, 2.4f)
            LiveWavy(cx, cy, g.d * 0.255f, g.d * 0.007f, 9, pal.primary, 3.dp * g.k, speed = -5f, sweep = 250f, startSpin = 120f)
            LiveOrb(cx, cy, g.d * 0.15f, Mood.Thinking)
            val half = g.chord(H * 0.7f, 14.dp)
            Box(Modifier.offset(cx - half, H * 0.60f).width(half * 2), contentAlignment = Alignment.Center) { Txt(ui.status.ifBlank { stringResource(R.string.thinking) }, 19f, pal.onContainer, FontWeight.Bold, -0.2f, lh = 22f, maxLines = 3) }
            if (ui.you.isNotBlank()) Box(Modifier.offset(cx - half, H * 0.60f + g.dp(58f)).width(half * 2), contentAlignment = Alignment.Center) { Txt(ui.you, 10.5f, pal.onContainer.copy(alpha = 0.55f), maxLines = 1) }
            ExEdge(g, stringResource(R.string.stop), pal.secC, pal.onSecC, Ic.close, 0.5f, 40f, onClick = onStop)
        } else {
            val half = g.chord(H * 0.5f, if (g.round) 20.dp else 14.dp).coerceAtMost(g.dp(100f))
            val scroll = rememberScrollState()
            CrownColumn(Modifier.fillMaxSize(), scroll) {
                Spacer(Modifier.height(H * 0.26f))
                Box(Modifier.width(W).padding(horizontal = (W - half * 2) / 2).padding(bottom = g.dp(80f))) {
                    Column {
                        if (ui.problem.isNotBlank()) Txt(ui.problem, 15f, V2err, FontWeight.Bold, align = TextAlign.Start, lh = 20f)
                        if (ui.status.isNotBlank() && ui.answer.isBlank()) Txt(ui.status, 13f, if (ui.needsYou) pal.amber else pal.primary, FontWeight.Bold, align = TextAlign.Start)
                        if (ui.answer.isNotBlank()) {
                            val hl = SpanStyle(color = pal.onContainer, background = pal.container, fontWeight = FontWeight.Bold)
                            val a = buildAnnotatedString {
                                var i = 0
                                for (m in timeToken.findAll(ui.answer)) {
                                    append(ui.answer.substring(i, m.range.first)); withStyle(hl) { append(" " + m.value + " ") }; i = m.range.last + 1
                                }
                                append(ui.answer.substring(i))
                            }
                            TxtA(a, 16.5f, Color.White, lh = 24f)
                        }
                    }
                }
            }
            EdgeFades(0.3f, 0.22f)
            LiveBlob(MaterialShapes.Cookie9Sided, cx, H * 0.12f, g.d * 0.24f, if (ui.needsYou) pal.amberC else pal.container)
            LiveOrb(cx, H * 0.12f, g.d * 0.062f, if (ui.needsYou) Mood.Needs else if (ui.working) Mood.Thinking else Mood.Idle)
            when {
                ui.problem.isNotBlank() -> ExEdge(g, stringResource(R.string.try_again), pal.primary, pal.onPrimary, onClick = onRetry, wFrac = 0.62f, h = 42f)
                ui.needsYou -> ExEdge(g, stringResource(R.string.needs_you), pal.amber, pal.onAmber, Ic.shield, 0.66f, 42f, onClick = onReview)
                ui.working -> ExEdge(g, stringResource(R.string.stop), pal.secC, pal.onSecC, Ic.close, 0.5f, 40f, onClick = onStop)
                else -> ExEdge(g, stringResource(R.string.reply), pal.primary, pal.onPrimary, Ic.mic, 0.62f, 42f, onClick = onReply)
            }
        }
    }
}

// ───────── Approval ─────────

/**
 * A card that needs him. An approval is only ever answered by holding Approve (a tap cannot do it) or pressing Decline.
 * A question shows its own answers; one with none is answered on the phone.
 */
@Composable
fun ApprovalScreen(p: Pending, position: String, previewHold: Float = -1f, onAnswer: (String) -> Unit) {
    val look = LocalLook.current; val pal = look.pal
    ExStage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        TopLabel(stringResource(R.string.needs_you).uppercase() + position.uppercase(), pal.amber, 10f, FontWeight.Bold, 2f)
        LiveBlob(MaterialShapes.Flower, cx, H * 0.185f, g.d * 0.24f, pal.amberC)
        LiveOrb(cx, H * 0.185f, g.d * 0.066f, Mood.Needs)
        val half = g.chord(H * 0.46f, 16.dp)
        if (p.isApproval) {
            Box(Modifier.offset(cx - half, H * 0.31f).width(half * 2), contentAlignment = Alignment.Center) { Txt(p.title, 18f, pal.onAmberC, FontWeight.Bold, -0.2f, lh = 21f, maxLines = 3) }
            val sub = p.details.ifBlank { p.askedBy }
            if (sub.isNotBlank()) Box(Modifier.offset(cx - half, H * 0.56f).width(half * 2), contentAlignment = Alignment.Center) { Txt(sub, 11f, Color.White.copy(alpha = 0.62f), lh = 14f, maxLines = 2) }
            ApproveBar(g, pal.amber, pal.amberC, pal.onAmber, pal.onAmberC, stringResource(R.string.approve_hold), stringResource(R.string.approving), stringResource(R.string.decline), onDecline = { onAnswer("Decline") }, onApproved = { onAnswer("Approve") }, previewHold = previewHold)
        } else {
            CrownColumn(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(H * 0.29f))
                Column(Modifier.padding(horizontal = W * 0.1f).padding(bottom = g.dp(60f)), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(g.dp(6f))) {
                    Txt(p.title, 17f, pal.onAmberC, FontWeight.Bold, -0.2f, lh = 20f, maxLines = 4)
                    Spacer(Modifier.height(g.dp(4f)))
                    if (p.options.isEmpty()) Txt(stringResource(R.string.answer_on_phone), 12f, Color.White.copy(alpha = 0.7f), lh = 15f)
                    else p.options.forEachIndexed { i, o -> ExPill(o, { onAnswer(o) }, container = if (i == 0) pal.container else pal.secC, content = if (i == 0) pal.onContainer else pal.onSecC) }
                }
            }
            EdgeFades(0.28f, 0.1f)
            LiveBlob(MaterialShapes.Flower, cx, H * 0.185f, g.d * 0.24f, pal.amberC)
            LiveOrb(cx, H * 0.185f, g.d * 0.066f, Mood.Needs)
        }
    }
}

/** The bottom bar of an approval: Decline on the left, and Hold to approve, a wavy fill that crosses the bar over 0.9 s. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ApproveBar(
    g: com.past9.phoneaos.wear.ui.ex.Geo, fill: Color, track: Color, onFill: Color, onTrack: Color, label: String, holding: String, decline: String,
    onDecline: () -> Unit, onApproved: () -> Unit, previewHold: Float = -1f,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var fired by remember { mutableStateOf(false) }
    val bh = g.dp(46f); val bw = g.d * 0.68f
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
    Box(Modifier.align(Alignment.BottomCenter).offset(0.dp, g.dp(2f)).size(bw, bh).clip(shape).background(track)) {
        // the fill: a wavy edge moving right
        val shown = if (previewHold >= 0f) previewHold else progress.value
        val t = if (shown > 0f && previewHold < 0f) com.past9.phoneaos.wear.ui.ex.rememberT() else 0f
        Canvas(Modifier.fillMaxSize()) {
            val split = size.width * 0.30f; val fillTo = split + (size.width - split) * shown
            if (shown > 0f) {
                val p = Path(); p.moveTo(split, 0f)
                var yy = 0f; while (yy <= size.height) { p.lineTo(fillTo + 4.dp.toPx() * sin(yy / 6.dp.toPx() + t * 6f), yy); yy += 2f }
                p.lineTo(split, size.height); p.close(); drawPath(p, fill)
            }
            drawLine(Color.Black.copy(alpha = 0.55f), Offset(split, 10.dp.toPx()), Offset(split, size.height), 1.5f * density)
        }
        Box(Modifier.fillMaxSize().padding(end = bw * 0.7f).clickable(onClick = onDecline).semantics { role = Role.Button; onLongClick(decline) { onDecline(); true } }, contentAlignment = Alignment.TopCenter) {
            Box(Modifier.padding(top = g.dp(12f))) { IconBox(Ic.close, g.dp(17f), onTrack) }
        }
        Box(
            Modifier.fillMaxSize().padding(start = bw * 0.3f)
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
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(Modifier.padding(top = g.dp(11f))) { Txt(if (shown > 0f) holding else stringResource(R.string.hold), 15f, if (shown > 0.4f) onFill else onTrack, FontWeight.Bold, maxLines = 1) }
        }
    }
}

/** A full-screen confirmation (never a pop-up): shown a moment after an answer, then it goes back by itself. */
@Composable
fun DoneScreen(text: String, positive: Boolean, onDone: () -> Unit) {
    val look = LocalLook.current; val pal = look.pal
    LaunchedEffect(Unit) { delay(1_400); onDone() }
    ExStage { g ->
        val W = g.w; val H = g.h; val cx = W / 2; val cy = H * 0.36f
        LiveBlob(MaterialShapes.Cookie12Sided, cx, cy, g.d * 0.55f, if (positive) pal.container else pal.surfHi)
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(cx.toPx(), cy.toPx()); val r = g.d.toPx() * 0.14f
            drawCircle(if (positive) pal.primary else pal.secC, r, c)
            icon(if (positive) Ic.check else Ic.close, c, r * 1.1f, if (positive) pal.onPrimary else pal.onSecC)
        }
        Box(Modifier.offset(cx - g.dp(90f), H * 0.66f).width(g.dp(180f)), contentAlignment = Alignment.Center) { Txt(text, 24f, Color.White, FontWeight.Black, -0.4f, maxLines = 2) }
    }
}

// ───────── Recent ─────────

@Composable
fun RecentScreen(items: List<Exchange>, onOpen: (Exchange) -> Unit) {
    val pal = LocalLook.current.pal
    ExStage { g ->
        val W = g.w; val H = g.h
        CrownColumn(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(H * 0.2f))
            Column(Modifier.padding(horizontal = W * 0.1f).padding(bottom = g.dp(50f)), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(g.dp(6f))) {
                if (items.isEmpty()) Txt(stringResource(R.string.no_recent), 13f, pal.dimText, lh = 17f)
                items.forEach { e -> ExPill(e.you, { onOpen(e) }, secondary = e.reply, height = 62f) }
            }
        }
        EdgeFades(0.19f, 0.12f)
        TopLabel(stringResource(R.string.recent).uppercase(), pal.primary, 10.5f, FontWeight.Bold, 2.4f)
    }
}

@Composable
fun ExchangeScreen(e: Exchange, onReply: () -> Unit) {
    val pal = LocalLook.current.pal
    ExStage { g ->
        val W = g.w; val H = g.h
        val half = g.chord(H * 0.5f, 20.dp).coerceAtMost(g.dp(100f))
        CrownColumn(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(H * 0.2f))
            Column(Modifier.padding(horizontal = (W - half * 2) / 2).padding(bottom = g.dp(80f))) {
                Txt(e.you, 12f, pal.primary, FontWeight.Bold, align = TextAlign.Start, maxLines = 3, lh = 15f)
                Spacer(Modifier.height(g.dp(8f)))
                Txt(e.reply, 16.5f, Color.White, align = TextAlign.Start, lh = 24f)
            }
        }
        EdgeFades(0.19f, 0.2f)
        ExEdge(g, stringResource(R.string.reply), pal.primary, pal.onPrimary, Ic.mic, 0.62f, 42f, onClick = onReply)
    }
}

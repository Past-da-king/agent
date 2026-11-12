package com.past9.phoneaos.wear.v2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Text on the top bezel (curved on a round watch, a plain line on a square one). */
@Composable
fun TopLabel(text: String, color: Color = V2.dim, size: Float = 12f, weight: FontWeight = FontWeight.SemiBold, track: Float = 0f, y: Float = 9f) {
    val g = LocalGeo.current
    val tm = rememberTextMeasurer()
    val st = TextStyle(color = color, fontSize = g.sp(size), fontWeight = weight, letterSpacing = track.sp, fontFeatureSettings = "tnum")
    Canvas(Modifier.fillMaxSize()) {
        if (g.round) {
            arcText(tm, text, st, min(this.size.width, this.size.height) / 2 - (y * g.k).dp.toPx(), 0f, track * density * 0.4f)
        } else {
            val l = tm.measure(text, st)
            drawText(tm, text, Offset((this.size.width - l.size.width) / 2, (y * g.k).dp.toPx()), st)
        }
    }
}

/** A tiny label pill on the stage: icon and a few words, glass. */
@Composable
fun Chip(text: String, icon: String? = null, tint: Color = V2.iris, cx: Dp, cy: Dp, w: Dp, h: Dp, size: Float = 12f, filled: Boolean = false) {
    val g = LocalGeo.current
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.offset(cx - w / 2, cy - h / 2).size(w, h).let { if (filled) it.clip(shape).background(tint.copy(alpha = 0.22f)).border(1.dp, tint.copy(alpha = 0.6f), shape) else it.glass(shape, tint) },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { IconBox(icon, g.dp(size + 2f), tint); androidx.compose.foundation.layout.Spacer(Modifier.width(g.dp(5f))) }
            Txt(text, size, if (tint == V2.amber) V2.amberHi else V2.text, FontWeight.SemiBold)
        }
    }
}

// ───────── 1. Home ─────────

/**
 * Home: the agent alive in the middle, tap it to talk. Calls, keyboard and recents sit on the bottom arc, the page rail
 * on the right is the crown. [pending] > 0 turns the agent amber and puts the approvals chip on top.
 */
@Composable
fun HomeScreen(t: Float, pending: Int = 0, kind: MascotKind = MascotKind.Scout, name: String = "NOVA", mood: Mood? = null, level: Float = 0f) {
    Stage { g ->
        val W = g.w; val H = g.h; val cx = W / 2; val cy = H * 0.43f; val r = g.d * 0.17f
        val m = mood ?: if (pending > 0) Mood.Needs else Mood.Idle
        Backdrop(m, t, 0.43f)
        TopLabel("14:20", V2.text, 13f, FontWeight.Medium)
        if (pending > 0) Chip("$pending needs you", Ic.shield, V2.amber, cx, H * 0.205f, g.dp(112f), g.dp(26f), 12f, filled = true)
        else Box(Modifier.offset(cx - g.dp(60f), H * 0.175f).width(g.dp(120f)), contentAlignment = Alignment.Center) { Txt(name, 10.5f, V2.iris, FontWeight.Bold, 2.2f) }
        Orb(cx, cy, r, m, t, level, kind)
        Box(Modifier.offset(cx - g.dp(80f), cy + r * 1.38f).width(g.dp(160f)), contentAlignment = Alignment.Center) { Txt("Tap to talk", 13f, V2.dim, FontWeight.Medium) }
        // bottom arc: call, type, recents
        val ry = H * 0.415f; val rx = if (g.round) g.d * 0.395f else W * 0.34f
        val ss = g.dp(38f)
        for ((i, ic) in listOf(Ic.call, Ic.keyboard, Ic.history).withIndex()) {
            val th = (140f - i * 50f) * PI.toFloat() / 180f
            val px = cx + rx * cos(th).let { if (g.round) it else it * 1.15f }; val py = H / 2 + ry * sin(th)
            Sat(ic, if (i == 1) ss else g.dp(34f), px, py + (if (i == 1) 0.dp else 0.dp), V2.iris)
        }
        CrownRail(0f, 0.3f)
    }
}

// ───────── 2. Listening ─────────

@Composable
fun ListeningScreen(t: Float, level: Float, words: String = "What's on my calendar today") {
    Stage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        Backdrop(Mood.Listening, t, 0.40f, 0.6f + 0.5f * level)
        TopLabel("LISTENING", V2.iris, 10.5f, FontWeight.Bold, 2.4f)
        Orb(cx, H * 0.385f, g.d * 0.215f, Mood.Listening, t, level)
        val half = g.chord(H * 0.78f, 12.dp)
        Box(Modifier.offset(cx - half, H * 0.645f).width(half * 2), contentAlignment = Alignment.Center) {
            val a = buildAnnotatedString {
                val parts = words.split(" ")
                parts.forEachIndexed { i, w -> withStyle(SpanStyle(color = if (i >= parts.size - 1) V2.dim else V2.text)) { append(w + if (i < parts.size - 1) " " else "") } }
            }
            TxtA(a, 15f, lh = 20f, align = TextAlign.Center)
        }
        Sat(Ic.close, g.dp(32f), cx, H * 0.915f - g.dp(6f), V2.dim)
    }
}

// ───────── 3. Thinking ─────────

@Composable
fun ThinkingScreen(t: Float, status: String = "Reading your calendar", echo: String = "What's on my calendar today") {
    Stage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        Backdrop(Mood.Thinking, t, 0.40f, 0.9f)
        TopLabel("THINKING", V2.iris, 10.5f, FontWeight.Bold, 2.4f)
        Orb(cx, H * 0.40f, g.d * 0.2f, Mood.Thinking, t)
        val half = g.chord(H * 0.76f, 18.dp)
        Box(Modifier.offset(cx - half, H * 0.67f).width(half * 2), contentAlignment = Alignment.Center) { Txt(echo, 11.5f, V2.faint, maxLines = 1) }
        val sweep = ((t * 0.55f) % 1.4f) - 0.2f
        Box(Modifier.offset(cx - half, H * 0.745f).width(half * 2), contentAlignment = Alignment.Center) {
            Txt(status, 15f, V2.text, FontWeight.Medium, brush = Brush.horizontalGradient(
                0f to V2.dim.copy(alpha = 0.55f), (sweep - 0.18f).coerceIn(0f, 1f) to V2.dim.copy(alpha = 0.55f), sweep.coerceIn(0f, 1f) to V2.irisHi, (sweep + 0.18f).coerceIn(0f, 1f) to V2.dim.copy(alpha = 0.55f), 1f to V2.dim.copy(alpha = 0.55f)))
        }
    }
}

// ───────── 4. Answer ─────────

val answerText: AnnotatedString = buildAnnotatedString {
    val hl = SpanStyle(color = V2.irisHi, fontWeight = FontWeight.SemiBold)
    append("You have two meetings today. The first is at ")
    withStyle(hl) { append("10:00") }
    append(" with the design team, the second at ")
    withStyle(hl) { append("14:30") }
    append(". Nothing needs you before then, and I moved the ")
    withStyle(hl) { append("16:00") }
    append(" call to tomorrow.")
}

@Composable
fun AnswerScreen(t: Float, scroll: Float = 0f, speaking: Boolean = true, level: Float = 0.5f) {
    Stage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        Backdrop(if (speaking) Mood.Speaking else Mood.Idle, t, 0.14f, 0.75f)
        TopLabel("14:20", V2.dim, 12f, FontWeight.Medium)
        Orb(cx, H * 0.165f, g.d * 0.075f, if (speaking) Mood.Speaking else Mood.Idle, t, level, glow = 0.7f)
        val half = g.chord(H * 0.5f, if (g.round) 20.dp else 14.dp).coerceAtMost(g.dp(96f))
        Box(Modifier.offset(cx - half, H * 0.27f - g.dp(scroll)).width(half * 2)) { TxtA(answerText, 16f, V2.text, lh = 22.5f) }
        // fades top and bottom so text slides under the glass
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(0f to Color.Black, 0.2f to Color.Black, 0.30f to Color.Transparent, 0.62f to Color.Transparent, 0.80f to Color.Black, 1f to Color.Black))
        }
        Orb(cx, H * 0.165f, g.d * 0.075f, if (speaking) Mood.Speaking else Mood.Idle, t, level, glow = 0.8f)
        Sat(Ic.volume, g.dp(34f), cx - g.dp(34f), H * 0.885f, V2.iris)
        Sat(Ic.mic, g.dp(34f), cx + g.dp(34f), H * 0.885f, V2.irisHi, filled = true, fillTint = V2.iris)
        CrownRail(clamp01(scroll / 60f), 0.4f)
    }
}

// ───────── 5. Approval ─────────

/** hold: 0..1 how far the hold ring has filled. done: show the approved state. */
@Composable
fun ApprovalScreen(t: Float, hold: Float = 0f, done: Float = 0f, title: String = "Send the invoice to the client", detail: String = "Invoice 0042, March retainer, to accounts@example.com") {
    Stage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        val calm = 1f - done
        Backdrop(if (done > 0.5f) Mood.Idle else Mood.Needs, t, 0.3f, 0.9f)
        if (done < 0.5f) {
            TopLabel("NEEDS YOU  ·  1 OF 2", V2.amber, 10f, FontWeight.Bold, 2f)
            Orb(cx, H * 0.185f, g.d * 0.07f, Mood.Needs, t, glow = 0.8f)
            val half = g.chord(H * 0.46f, 18.dp)
            Box(Modifier.offset(cx - half, H * 0.285f).width(half * 2).alpha(calm), contentAlignment = Alignment.Center) { Txt(title, 17f, V2.text, FontWeight.SemiBold, lh = 21f, maxLines = 2) }
            Box(Modifier.offset(cx - half, H * 0.51f).width(half * 2).alpha(calm), contentAlignment = Alignment.Center) { Txt(detail, 11.5f, V2.dim, lh = 15f, maxLines = 2) }
        }
        // hold ring
        val bx = cx; val by = H * 0.775f; val br = g.d * 0.115f
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(bx.toPx(), by.toPx()); val rad = br.toPx(); val sw = 4.dp.toPx() * g.k
            val ringA = if (done > 0f) 1f else 1f
            drawCircle(Brush.radialGradient(0f to V2.amber.copy(alpha = 0.30f * (0.4f + hold) * calm), 1f to Color.Transparent, center = c, radius = rad * 2.1f), rad * 2.1f, c)
            drawCircle(Color.White.copy(alpha = 0.08f), rad, c)
            drawCircle(Color.White.copy(alpha = 0.16f), rad, c, style = Stroke(sw))
            val sweep = 360f * (if (done > 0f) 1f else hold)
            val col = if (done > 0.5f) V2.iris else V2.amber
            if (sweep > 0f) drawArc(Brush.sweepGradient(listOf(col.copy(alpha = 0.55f), V2.amberHi, col), c), -90f, sweep, false, Offset(c.x - rad, c.y - rad), Size(rad * 2, rad * 2), style = Stroke(sw, cap = StrokeCap.Round))
            // fill grows from the centre as the hold completes
            drawCircle(Brush.verticalGradient(listOf(lerpC(V2.amberHi, V2.irisHi, done), lerpC(V2.amber, V2.iris, done)), c.y - rad, c.y + rad), rad * 0.98f * smooth((hold - 0.12f) / 0.88f), c, alpha = 0.95f)
            icon(Ic.check, c, rad * (0.8f + 0.2f * hold), if (hold > 0.5f) (if (done > 0.5f) V2.ink else V2.inkAmber) else V2.amberHi)
            if (done > 0f) for (i in 0 until 14) {
                val a = i * 2f * PI.toFloat() / 14f + 0.2f; val p = clamp01(done * 1.4f)
                val r0 = rad * (1.15f + 0.5f * p); val r1 = r0 + rad * 0.28f * (1f - p * 0.6f)
                drawLine(V2.irisHi.copy(alpha = (1f - p) * 0.9f), Offset(c.x + cos(a) * r0, c.y + sin(a) * r0), Offset(c.x + cos(a) * r1, c.y + sin(a) * r1), 2.5f * density, StrokeCap.Round)
            }
        }
        if (done < 0.5f) {
            Box(Modifier.offset(cx - g.dp(60f), H * 0.775f + br + g.dp(3f)).width(g.dp(120f)), contentAlignment = Alignment.Center) { Txt(if (hold > 0.05f) "Keep holding" else "Hold to approve", 10.5f, V2.dim, FontWeight.Medium, 0.4f) }
            Sat(Ic.close, g.dp(34f), cx - g.d * 0.29f, H * 0.80f, V2.dim)
            Sat(Ic.chevron, g.dp(34f), cx + g.d * 0.29f, H * 0.80f, V2.dim)
        } else {
            val a = easeOut((done - 0.5f) * 2f)
            Box(Modifier.offset(cx - g.dp(80f), H * 0.30f).width(g.dp(160f)).alpha(a), contentAlignment = Alignment.Center) { Txt("Approved", 22f, V2.text, FontWeight.Medium) }
            Box(Modifier.offset(cx - g.dp(90f), H * 0.43f).width(g.dp(180f)).alpha(a), contentAlignment = Alignment.Center) { Txt("Sent to the client", 12f, V2.dim) }
            Orb(cx, H * 0.185f, g.d * 0.07f, Mood.Idle, t, glow = 0.8f)
        }
    }
}

@Composable
fun QuestionScreen(t: Float) {
    Stage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        Backdrop(Mood.Needs, t, 0.2f, 0.7f)
        TopLabel("ASKING YOU", V2.amber, 10f, FontWeight.Bold, 2f)
        Orb(cx, H * 0.17f, g.d * 0.065f, Mood.Needs, t, glow = 0.7f)
        val half = g.chord(H * 0.4f, 20.dp)
        Box(Modifier.offset(cx - half, H * 0.265f).width(half * 2), contentAlignment = Alignment.Center) { Txt("Which time works for the call?", 15.5f, V2.text, FontWeight.SemiBold, lh = 19f, maxLines = 2) }
        val opts = listOf("Tuesday 10:00", "Wednesday 14:00", "Neither")
        for ((i, o) in opts.withIndex()) {
            val yy = H * (0.52f + i * 0.145f)
            val hw = g.chord(yy + H * 0.07f, 14.dp).coerceAtMost(g.dp(92f))
            Box(
                Modifier.offset(cx - hw, yy - g.dp(17f)).size(hw * 2, g.dp(34f)).let { if (i == 0) it.clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(V2.irisDeep.copy(alpha = 0.9f), V2.irisLo.copy(alpha = 0.55f)))).border(1.dp, V2.iris.copy(alpha = 0.55f), RoundedCornerShape(50)) else it.glass(RoundedCornerShape(50)) },
                contentAlignment = Alignment.Center,
            ) { Txt(o, 13f, if (i == 0) V2.irisHi else V2.text, FontWeight.Medium) }
        }
    }
}

// ───────── 6. Call ─────────

@Composable
fun CallScreen(t: Float, level: Float, muted: Boolean = false, route: String = "Watch speaker", routeIcon: String = Ic.volume, secs: Int = 134, sheet: Float = 0f) {
    Stage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        val mood = if (muted) Mood.Muted else Mood.Call
        Backdrop(mood, t, 0.375f, 0.9f)
        val mm = (secs / 60).toString().padStart(2, '0'); val ss = (secs % 60).toString().padStart(2, '0')
        TopLabel(if (muted) "MUTED  ·  $mm:$ss" else "IN CALL  ·  $mm:$ss", if (muted) V2.dim else V2.iris, 10f, FontWeight.Bold, 2f)
        Orb(cx, H * 0.375f, g.d * 0.15f, mood, t, if (muted) 0f else level)
        Chip(route, routeIcon, V2.iris, cx, H * 0.695f, g.dp(138f), g.dp(26f), 11.5f)
        val ry = H * 0.405f; val rx = if (g.round) g.d * 0.385f else W * 0.33f
        val th1 = 130f * PI.toFloat() / 180f; val th3 = 50f * PI.toFloat() / 180f
        Sat(if (muted) Ic.mic else Ic.mic, g.dp(38f), cx + rx * cos(th1), H / 2 + ry * sin(th1), if (muted) V2.amber else V2.iris, filled = muted, fillTint = V2.amber, glyph = if (muted) V2.inkAmber else null)
        Box(Modifier.offset(cx - g.dp(24f), H * 0.855f - g.dp(24f)).size(g.dp(48f)).clip(CircleShape).background(Brush.verticalGradient(listOf(V2.endRed, V2.endRedLo))).border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape), contentAlignment = Alignment.Center) { IconBox(Ic.call, g.dp(22f), Color.White, rot = 135f) }
        Sat(Ic.volume, g.dp(38f), cx + rx * cos(th3), H / 2 + ry * sin(th3), V2.iris)
        if (muted) Canvas(Modifier.fillMaxSize()) { // slash on the mute button
            val p = Offset((cx + rx * cos(th1)).toPx(), (H / 2 + ry * sin(th1)).toPx()); val s = g.dp(8f).toPx()
            drawLine(V2.inkAmber, Offset(p.x - s, p.y - s), Offset(p.x + s, p.y + s), 2.2f * density, StrokeCap.Round)
        }
        if (sheet > 0f) AudioSheet(sheet, route)
    }
}

/** Slide-up sheet, never a centred dialog: rises from the bottom edge over a dimmed screen. */
@Composable
private fun AudioSheet(p: Float, route: String) {
    val g = LocalGeo.current
    val e = easeOut(p)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f * e)))
    val top = g.h * (1f - 0.7f * e)
    Box(Modifier.offset(g.w * 0.0f, top).size(g.w, g.h * 0.74f).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Brush.verticalGradient(listOf(Color(0xFF26242F), Color(0xFF15141B)))).border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.25f), Color.Transparent)), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))) {
        Box(Modifier.offset(g.w / 2 - g.dp(14f), g.dp(8f)).size(g.dp(28f), g.dp(4f)).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.3f)))
        Box(Modifier.offset(0.dp, g.dp(18f)).width(g.w), contentAlignment = Alignment.Center) { Txt("AUDIO", 10f, V2.iris, FontWeight.Bold, 2f) }
        val rows = listOf(Triple("Watch speaker", Ic.volume, "Watch speaker"), Triple("Phone", Ic.call, "Phone"), Triple("Earbuds", Ic.headphones, "Earbuds"))
        for ((i, rw) in rows.withIndex()) {
            val sel = rw.first == route
            val yy = g.dp(40f + i * 38f); val hw = g.chord(g.h * 0.3f + yy, 18.dp).coerceAtMost(g.dp(88f))
            Box(Modifier.offset(g.w / 2 - hw, yy).size(hw * 2, g.dp(33f)).let { if (sel) it.clip(RoundedCornerShape(50)).background(V2.irisDeep.copy(alpha = 0.8f)).border(1.dp, V2.iris.copy(alpha = 0.6f), RoundedCornerShape(50)) else it.glass(RoundedCornerShape(50)) }) {
                IconBox(rw.second, g.dp(16f), if (sel) V2.irisHi else V2.dim, Modifier.offset(g.dp(12f), g.dp(8.5f)))
                Box(Modifier.offset(g.dp(34f), g.dp(8f))) { Txt(rw.first + if (rw.first == "Earbuds") "  ·  Buds" else "", 12.5f, if (sel) V2.irisHi else V2.text, FontWeight.Medium, align = TextAlign.Start) }
                if (sel) IconBox(Ic.check, g.dp(15f), V2.irisHi, Modifier.offset(hw * 2 - g.dp(28f), g.dp(9f)))
            }
        }
    }
}

// ───────── 7. Incoming ring ─────────

enum class RingKind { Wake, Call, Approval }

@Composable
fun RingScreen(t: Float, kind: RingKind = RingKind.Wake) {
    Stage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        val wake = kind != RingKind.Call
        val mood = if (wake) Mood.Needs else Mood.Call
        Backdrop(mood, t, 0.31f, 1.4f)
        val col = if (wake) V2.amber else V2.iris
        Canvas(Modifier.fillMaxSize()) { // expanding pulses from the agent
            val c = Offset(size.width / 2, size.height * 0.31f)
            for (k in 0 until 4) {
                val ph = (t * 0.6f + k / 4f) % 1f
                drawCircle(col.copy(alpha = (1f - ph) * 0.40f), g.d.toPx() * (0.16f + 0.62f * ph), c, style = Stroke(2.2f * density * (1f - ph * 0.6f)))
            }
        }
        val shake = sin(t * 38f) * 2.4f * (if (sin(t * 3.1f) > 0.1f) 1f else 0f)
        Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = shake; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.31f) }) {
            Orb(cx, H * 0.31f, g.d * (if (kind == RingKind.Call) 0.1f else 0.125f), mood, t, level = if (wake) 0f else 0.5f + 0.3f * sin(t * 6f))
        }
        TopLabel(when (kind) { RingKind.Wake -> "NOVA  ·  NOW"; RingKind.Call -> "CALLING YOU"; RingKind.Approval -> "NEEDS YOU NOW" }, col, 10f, FontWeight.Bold, 2f)
        val half = g.chord(H * 0.60f, 14.dp)
        Box(Modifier.offset(cx - half, (H * (if (kind == RingKind.Call) 0.5f else 0.455f))).width(half * 2), contentAlignment = Alignment.Center) {
            Txt(when (kind) { RingKind.Wake -> "Design review in 5 min"; RingKind.Call -> "Nova"; RingKind.Approval -> "Approve the invoice" }, 17f, V2.text, FontWeight.SemiBold, lh = 20f, maxLines = 2)
        }
        Box(Modifier.offset(cx - half, H * (if (kind == RingKind.Call) 0.5f else 0.455f) + g.dp(31f)).width(half * 2), contentAlignment = Alignment.Center) {
            Txt(when (kind) { RingKind.Wake -> "Thabo is presenting"; RingKind.Call -> "wants to talk now"; RingKind.Approval -> "Waiting on you for 4 min" }, 11.5f, V2.dim, lh = 14.5f, maxLines = 2)
        }
        val by = H * 0.80f
        Sat(if (wake) Ic.history else Ic.close, g.dp(38f), cx - g.d * 0.2f, by, if (wake) V2.dim else V2.dim)
        // answer: the primary, with a soft pulse
        val pulse = 0.5f + 0.5f * sin(t * 5f)
        Canvas(Modifier.fillMaxSize()) { drawCircle(col.copy(alpha = 0.25f * pulse), g.dp(30f).toPx() + 3.dp.toPx() * pulse, Offset((cx + g.d * 0.2f).toPx(), by.toPx())) }
        Sat(if (kind == RingKind.Call) Ic.call else Ic.check, g.dp(44f), cx + g.d * 0.2f, by, col, filled = true, fillTint = col, glyph = if (wake) V2.inkAmber else V2.ink)
    }
}

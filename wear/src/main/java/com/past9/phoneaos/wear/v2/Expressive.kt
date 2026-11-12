package com.past9.phoneaos.wear.v2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Direction B: Material 3 Expressive for Wear, in the Agent's own tokens. Flat tonal colour blocks (no glow, no gradients),
 * shape containers cut from Material's own shape library, wavy progress, a bold type scale, buttons that hug the bezel, and the
 * REAL mascot. The palette is derived from one seed colour (the accent), the way Material's dynamic colour does it, so a user
 * who picks another accent gets a matching set; the default seed is the Agent's iris.
 */
class Pal(
    val primary: Color, val onPrimary: Color, val container: Color, val onContainer: Color, val deep: Color,
    val secC: Color, val onSecC: Color, val surfHi: Color, val dimText: Color,
    val amber: Color = V2.amber, val onAmber: Color = V2.inkAmber, val amberC: Color = Color(0xFF6A3D00), val onAmberC: Color = Color(0xFFFFDDB8),
) {
    companion object {
        private fun hsv(h: Float, s: Float, v: Float) = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
        /** A tonal set from one seed colour: same hue, steps of saturation and value, as Material's tonal palette does. */
        fun of(seed: Color): Pal {
            val hsv = FloatArray(3); android.graphics.Color.colorToHSV(seed.toArgb(), hsv); val h = hsv[0]
            return Pal(seed, hsv(h, 0.79f, 0.48f), hsv(h, 0.73f, 0.69f), hsv(h, 0.11f, 1f), hsv(h, 0.78f, 0.31f), hsv(h, 0.18f, 0.35f), hsv(h, 0.07f, 0.95f), hsv(h, 0.08f, 0.19f), hsv(h, 0.08f, 0.78f))
        }
        val Iris = of(V2.iris)
    }
}

private fun polyPath(poly: RoundedPolygon, cx: Float, cy: Float, size: Float): Path {
    val p = Path(); var first = true
    for (c in poly.normalized().cubics) {
        if (first) { p.moveTo(cx + (c.anchor0X - 0.5f) * size, cy + (c.anchor0Y - 0.5f) * size); first = false }
        p.cubicTo(cx + (c.control0X - 0.5f) * size, cy + (c.control0Y - 0.5f) * size, cx + (c.control1X - 0.5f) * size, cy + (c.control1Y - 0.5f) * size, cx + (c.anchor1X - 0.5f) * size, cy + (c.anchor1Y - 0.5f) * size)
    }
    p.close(); return p
}

/** A filled Material shape (a morphing container) with an optional icon in its middle. */
fun DrawScope.shapeBlob(poly: RoundedPolygon, c: Offset, size: Float, fill: Color, rot: Float = 0f) {
    rotate(rot, pivot = c) { drawPath(polyPath(poly, c.x, c.y, size), fill) }
}

/** Wavy ring (the expressive wavy progress indicator): a circle whose radius ripples. */
fun DrawScope.wavyRing(c: Offset, r: Float, amp: Float, n: Int, phase: Float, color: Color, sw: Float, sweep: Float = 360f, start: Float = -90f) {
    val p = Path(); var a = 0f; var first = true
    while (a <= sweep + 0.01f) {
        val th = (start + a) * PI.toFloat() / 180f
        val rr = r + amp * sin(n * th + phase)
        val x = c.x + cos(th) * rr; val y = c.y + sin(th) * rr
        if (first) { p.moveTo(x, y); first = false } else p.lineTo(x, y)
        a += 2f
    }
    drawPath(p, color, style = Stroke(sw, cap = StrokeCap.Round))
}

/** The stage for direction B: flat black or a full-bleed tonal colour. */
@Composable
private fun ExStage(bg: Color = Color.Black, content: @Composable androidx.compose.foundation.layout.BoxScope.(Geo) -> Unit) {
    Stage { g -> Box(Modifier.fillMaxSize().background(bg)) { content(g) } }
}

@Composable
fun ExMascot(cx: Dp, cy: Dp, r: Dp, mood: Mood, t: Float, level: Float = 0f, kind: MascotKind = MascotKind.Scout) = Orb(cx, cy, r, mood, t, level, kind, glow = 0f)

/** The Material 3 Expressive edge button: a wide pill that rises from the bottom bezel and hugs it. */
@Composable
fun ExEdge(g: Geo, label: String, fill: Color, text: Color, icon: String? = null, wFrac: Float = 0.72f, h: Float = 46f) {
    val w = g.d * wFrac; val hh = g.dp(h)
    val shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
    Box(Modifier.offset((g.w - w) / 2, g.h - hh + g.dp(2f)).size(w, hh).clip(shape).background(fill), contentAlignment = Alignment.TopCenter) {
        androidx.compose.foundation.layout.Row(Modifier.offset(0.dp, g.dp(10f)), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { IconBox(icon, g.dp(18f), text); androidx.compose.foundation.layout.Spacer(Modifier.width(g.dp(6f))) }
            Txt(label, 15f, text, FontWeight.Bold, 0.1f)
        }
    }
}

/** A Material-shape icon button (cookie, flower, pill) in a tonal colour. */
@Composable
fun ExShapeBtn(cx: Dp, cy: Dp, size: Dp, poly: RoundedPolygon, fill: Color, icon: String, glyph: Color, rot: Float = 0f) {
    Canvas(Modifier.fillMaxSize()) { shapeBlob(poly, Offset(cx.toPx(), cy.toPx()), size.toPx(), fill, rot); icon(icon, Offset(cx.toPx(), cy.toPx()), size.toPx() * 0.46f, glyph) }
}

private fun boldTime(g: Geo) = g.sp(15f)

// ───────── screens ─────────

@Composable
fun ExHome(t: Float, pending: Int = 0, pal: Pal = Pal.Iris, kind: MascotKind = MascotKind.Scout) {
    ExStage { g ->
        val W = g.w; val H = g.h; val cx = W / 2; val cy = H * 0.385f
        val needs = pending > 0
        val m = if (needs) Mood.Needs else Mood.Idle
        Canvas(Modifier.fillMaxSize()) { // the scalloped tonal disc behind the agent
            shapeBlob(MaterialShapes.Cookie12Sided, Offset(cx.toPx(), cy.toPx()), g.d.toPx() * 0.5f, if (needs) pal.amberC else pal.container, rot = t * 5f)
        }
        TopLabel("14:20", Color.White, 14f, FontWeight.Bold)
        Orb(cx, cy, g.d * 0.155f, m, t, 0f, kind, glow = 0f)
        if (needs) Box(Modifier.offset(cx - g.dp(58f), H * 0.15f).size(g.dp(116f), g.dp(26f)).clip(RoundedCornerShape(50)).background(pal.amber), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) { IconBox(Ic.shield, g.dp(14f), pal.onAmber); androidx.compose.foundation.layout.Spacer(Modifier.width(g.dp(4f))); Txt("$pending needs you", 12f, pal.onAmber, FontWeight.Bold) }
        }
        ExShapeBtn(cx - g.d * 0.30f, H * 0.62f, g.dp(40f), MaterialShapes.Cookie6Sided, pal.secC, Ic.call, pal.onSecC)
        ExShapeBtn(cx + g.d * 0.30f, H * 0.62f, g.dp(40f), MaterialShapes.Cookie6Sided, pal.secC, Ic.history, pal.onSecC)
        ExEdge(g, if (needs) "Review" else "Talk", if (needs) pal.amber else pal.primary, if (needs) pal.onAmber else pal.onPrimary, if (needs) Ic.shield else Ic.mic)
    }
}

@Composable
fun ExListening(t: Float, level: Float, pal: Pal = Pal.Iris, words: String = "What's on my calendar today") {
    ExStage(pal.deep) { g ->
        val W = g.w; val H = g.h; val cx = W / 2; val cy = H * 0.34f
        TopLabel("LISTENING", pal.onContainer, 10.5f, FontWeight.Bold, 2.4f)
        Canvas(Modifier.fillMaxSize()) { wavyRing(Offset(cx.toPx(), cy.toPx()), g.d.toPx() * 0.255f, g.d.toPx() * (0.004f + 0.014f * level), 11, t * 4f, pal.primary, 3.dp.toPx() * g.k) }
        Orb(cx, cy, g.d * 0.155f, Mood.Listening, t, level, glow = 0f)
        val half = g.chord(H * 0.7f, 12.dp)
        val parts = words.split(" ")
        val a = buildAnnotatedString { parts.forEachIndexed { i, w -> withStyle(SpanStyle(color = if (i == parts.size - 1) pal.primary.copy(alpha = 0.6f) else pal.onContainer)) { append(w + if (i < parts.size - 1) " " else "") } } }
        Box(Modifier.offset(cx - half, H * 0.60f).width(half * 2), contentAlignment = Alignment.Center) { TxtA(a, 18f, lh = 22f, align = TextAlign.Center) }
        ExEdge(g, "Stop", pal.secC, pal.onSecC, Ic.close, 0.5f, 40f)
    }
}

@Composable
fun ExThinking(t: Float, pal: Pal = Pal.Iris) {
    ExStage(pal.deep) { g ->
        val W = g.w; val H = g.h; val cx = W / 2; val cy = H * 0.34f
        TopLabel("THINKING", pal.onContainer, 10.5f, FontWeight.Bold, 2.4f)
        Canvas(Modifier.fillMaxSize()) { wavyRing(Offset(cx.toPx(), cy.toPx()), g.d.toPx() * 0.255f, g.d.toPx() * 0.007f, 9, -t * 5f, pal.primary, 3.dp.toPx() * g.k, 250f, t * 120f) }
        Orb(cx, cy, g.d * 0.15f, Mood.Thinking, t, 0f, glow = 0f)
        val half = g.chord(H * 0.7f, 14.dp)
        Box(Modifier.offset(cx - half, H * 0.61f).width(half * 2), contentAlignment = Alignment.Center) { Txt("Reading your calendar", 19f, pal.onContainer, FontWeight.Bold, -0.2f, lh = 22f) }
        Box(Modifier.offset(cx - half, H * 0.61f + g.dp(52f)).width(half * 2), contentAlignment = Alignment.Center) { Txt("What's on my calendar today", 10.5f, pal.onContainer.copy(alpha = 0.55f), maxLines = 1) }
    }
}

@Composable
fun ExAnswer(t: Float, pal: Pal = Pal.Iris, scroll: Float = 0f) {
    ExStage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        Canvas(Modifier.fillMaxSize()) { shapeBlob(MaterialShapes.Cookie9Sided, Offset(cx.toPx(), (H * 0.12f).toPx()), g.d.toPx() * 0.24f, pal.container, t * 5f) }
        Orb(cx, H * 0.12f, g.d * 0.062f, Mood.Speaking, t, 0.5f, glow = 0f)
        val half = g.chord(H * 0.5f, if (g.round) 20.dp else 14.dp).coerceAtMost(g.dp(96f))
        val a = buildAnnotatedString {
            val hl = SpanStyle(color = pal.onContainer, background = pal.container, fontWeight = FontWeight.Bold)
            append("You have two meetings today. The first is at "); withStyle(hl) { append(" 10:00 ") }
            append(" with the design team, the second at "); withStyle(hl) { append(" 14:30 ") }
            append(". Nothing needs you before then, and I moved the "); withStyle(hl) { append(" 16:00 ") }; append(" call to tomorrow.")
        }
        Box(Modifier.offset(cx - half, H * 0.245f - g.dp(scroll)).width(half * 2)) { TxtA(a, 16.5f, Color.White, lh = 24f) }
        Canvas(Modifier.fillMaxSize()) { drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color.Black, 0.2f to Color.Black, 0.28f to Color.Transparent, 0.66f to Color.Transparent, 0.84f to Color.Black, 1f to Color.Black)) }
        Canvas(Modifier.fillMaxSize()) { shapeBlob(MaterialShapes.Cookie9Sided, Offset(cx.toPx(), (H * 0.12f).toPx()), g.d.toPx() * 0.24f, pal.container, t * 5f) }
        Orb(cx, H * 0.12f, g.d * 0.062f, Mood.Speaking, t, 0.5f, glow = 0f)
        ExEdge(g, "Reply", pal.primary, pal.onPrimary, Ic.mic, 0.62f, 42f)
    }
}

@Composable
fun ExApproval(t: Float, hold: Float = 0f, pal: Pal = Pal.Iris, title: String = "Send the invoice to the client", detail: String = "Invoice 0042, March retainer, to accounts@example.com") {
    ExStage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        TopLabel("NEEDS YOU  ·  1 OF 2", pal.amber, 10f, FontWeight.Bold, 2f)
        Canvas(Modifier.fillMaxSize()) { shapeBlob(MaterialShapes.Flower, Offset(cx.toPx(), (H * 0.2f).toPx()), g.d.toPx() * 0.25f, pal.amberC, t * 6f) }
        Orb(cx, H * 0.2f, g.d * 0.068f, Mood.Needs, t, glow = 0f)
        val half = g.chord(H * 0.5f, 16.dp)
        Box(Modifier.offset(cx - half, H * 0.33f).width(half * 2), contentAlignment = Alignment.Center) { Txt(title, 19f, pal.onAmberC, FontWeight.Bold, -0.2f, lh = 22f, maxLines = 2) }
        Box(Modifier.offset(cx - half, H * 0.55f).width(half * 2), contentAlignment = Alignment.Center) { Txt(detail, 11f, Color.White.copy(alpha = 0.62f), lh = 14f, maxLines = 2) }
        // bottom group: decline segment + hold-to-approve segment with a wavy fill
        val bh = g.dp(46f); val bw = g.d * 0.68f; val bx = (W - bw) / 2; val by = H - bh + g.dp(2f)
        val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
        Box(Modifier.offset(bx, by).size(bw, bh).clip(shape).background(pal.amberC))
        Canvas(Modifier.fillMaxSize()) {
            val x0 = bx.toPx(); val y0 = by.toPx(); val wpx = bw.toPx(); val hpx = bh.toPx()
            val split = x0 + wpx * 0.30f
            val fillTo = split + (x0 + wpx - split) * hold
            if (hold > 0f) {
                clipPath(Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(x0, y0, x0 + wpx, y0 + hpx, CornerRadius(28.dp.toPx()), CornerRadius(28.dp.toPx()), CornerRadius(18.dp.toPx()), CornerRadius(18.dp.toPx()))) }) {
                    val p = Path(); p.moveTo(split, y0)
                    var yy = y0; while (yy <= y0 + hpx) { p.lineTo(fillTo + 4.dp.toPx() * sin(yy / 6.dp.toPx() + t * 6f), yy); yy += 2f }
                    p.lineTo(split, y0 + hpx); p.close(); drawPath(p, pal.amber)
                }
            }
            drawLine(Color.Black.copy(alpha = 0.55f), Offset(split, y0 + 10.dp.toPx()), Offset(split, y0 + hpx), 1.5f * density)
            icon(Ic.close, Offset(x0 + wpx * 0.15f, y0 + 21.dp.toPx() * g.k), 17.dp.toPx() * g.k, pal.onAmberC)
        }
        Box(Modifier.offset(bx + bw * 0.30f, by + g.dp(11f)).width(bw * 0.70f), contentAlignment = Alignment.Center) { Txt(if (hold > 0.05f) "Keep holding" else "Hold", 15f, if (hold > 0.4f) pal.onAmber else pal.onAmberC, FontWeight.Bold) }
    }
}

@Composable
fun ExCall(t: Float, level: Float, pal: Pal = Pal.Iris, secs: Int = 134) {
    ExStage { g ->
        val W = g.w; val H = g.h; val cx = W / 2; val cy = H * 0.31f
        val mm = (secs / 60).toString().padStart(2, '0'); val ss = (secs % 60).toString().padStart(2, '0')
        Canvas(Modifier.fillMaxSize()) {
            shapeBlob(MaterialShapes.Cookie12Sided, Offset(cx.toPx(), cy.toPx()), g.d.toPx() * 0.47f, pal.container, rot = t * 5f)
            wavyRing(Offset(cx.toPx(), cy.toPx()), g.d.toPx() * 0.18f, g.d.toPx() * (0.004f + 0.014f * level), 13, t * 5f, pal.primary, 3.dp.toPx() * g.k)
        }
        TopLabel("IN CALL", pal.onContainer, 10f, FontWeight.Bold, 2f)
        Orb(cx, cy, g.d * 0.125f, Mood.Call, t, level, glow = 0f)
        Box(Modifier.offset(cx - g.dp(80f), H * 0.535f).width(g.dp(160f)), contentAlignment = Alignment.Center) { Txt("$mm:$ss", 30f, Color.White, FontWeight.Black, -0.5f) }
        Box(Modifier.offset(cx - g.dp(64f), H * 0.69f).size(g.dp(128f), g.dp(24f)).clip(RoundedCornerShape(50)).background(pal.secC), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) { IconBox(Ic.volume, g.dp(13f), pal.onSecC); androidx.compose.foundation.layout.Spacer(Modifier.width(g.dp(4f))); Txt("Watch speaker", 11f, pal.onSecC, FontWeight.SemiBold) }
        }
        // bottom group: mute | end | route
        val bh = g.dp(46f); val bw = g.d * 0.72f; val bx = (W - bw) / 2; val by = H - bh + g.dp(2f)
        val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
        Box(Modifier.offset(bx, by).size(bw, bh).clip(shape).background(Color(0xFF1E1D24)))
        Box(Modifier.offset(bx + bw * 0.27f, by).size(bw * 0.46f, bh).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(V2.endRed), contentAlignment = Alignment.TopCenter) { IconBox(Ic.call, g.dp(22f), Color.White, Modifier.offset(0.dp, g.dp(9f)), rot = 135f) }
        Canvas(Modifier.fillMaxSize()) {
            icon(Ic.mic, Offset((bx + bw * 0.135f).toPx(), (by + g.dp(21f)).toPx()), 19.dp.toPx() * g.k, pal.onSecC)
            icon(Ic.headphones, Offset((bx + bw * 0.865f).toPx(), (by + g.dp(21f)).toPx()), 19.dp.toPx() * g.k, pal.onSecC)
        }
    }
}

@Composable
fun ExPairScan(t: Float, pal: Pal = Pal.Iris) {
    ExStage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        TopLabel("PAIR WITH YOUR PHONE", pal.primary, 10f, FontWeight.Bold, 2f)
        val side = g.d * 0.47f
        Canvas(Modifier.fillMaxSize()) {
            val s = side.toPx(); val tl = Offset(cx.toPx() - s / 2, (H * 0.19f).toPx())
            shapeBlob(MaterialShapes.Cookie12Sided, Offset(cx.toPx(), tl.y + s / 2), s * 1.22f, pal.container, t * 4f)
            qrTile(tl, s, 20.dp.toPx())
        }
        Box(Modifier.offset(cx - g.dp(80f), H * 0.19f + side + g.dp(10f)).width(g.dp(160f)), contentAlignment = Alignment.Center) { Txt(pairCode, 24f, Color.White, FontWeight.Black, 2.5f) }
        Box(Modifier.offset(cx - g.dp(92f), H * 0.19f + side + g.dp(38f)).width(g.dp(184f)), contentAlignment = Alignment.Center) { Txt("Phone: Agent, Watch, Scan", 10.5f, Color.White.copy(alpha = 0.6f)) }
    }
}

@Composable
fun ExPairBusy(t: Float, step: Int = 1, pal: Pal = Pal.Iris) {
    ExStage(pal.deep) { g ->
        val W = g.w; val H = g.h; val cx = W / 2; val cy = H * 0.30f
        TopLabel("PAIRING", pal.onContainer, 10.5f, FontWeight.Bold, 2.4f)
        Canvas(Modifier.fillMaxSize()) { wavyRing(Offset(cx.toPx(), cy.toPx()), g.d.toPx() * 0.215f, g.d.toPx() * 0.014f, 9, -t * 5f, pal.primary, 3.dp.toPx() * g.k, 80f + 250f * clamp01((step + 0.4f) / 3f), t * 100f) }
        Orb(cx, cy, g.d * 0.135f, Mood.Thinking, t, 0f, glow = 0f)
        val steps = listOf("Paired with your phone", "Getting your name and look", "Syncing what needs you")
        for ((i, s) in steps.withIndex()) {
            val y = H * 0.575f + g.dp(i * 21f)
            val state = if (i < step) 2 else if (i == step) 1 else 0
            Box(Modifier.offset(cx - g.dp(88f), y).size(g.dp(176f), g.dp(18f))) {
                Canvas(Modifier.offset(0.dp, g.dp(1f)).size(g.dp(15f))) {
                    val c = Offset(size.width / 2, size.height / 2)
                    when (state) {
                        2 -> { drawCircle(pal.primary, size.width / 2); icon(Ic.check, c, size.width * 0.7f, pal.onPrimary) }
                        1 -> { shapeBlob(MaterialShapes.Cookie6Sided, c, size.width, pal.primary.copy(alpha = 0.55f), t * 90f) }
                        else -> drawCircle(pal.onContainer.copy(alpha = 0.3f), size.width / 2 - density, style = Stroke(1.4f * density))
                    }
                }
                Box(Modifier.offset(g.dp(23f), 0.dp)) { Txt(s, 12f, if (state == 0) pal.onContainer.copy(alpha = 0.45f) else pal.onContainer, if (state == 1) FontWeight.Bold else FontWeight.Normal, align = TextAlign.Start, maxLines = 1) }
            }
        }
    }
}

@Composable
fun ExPairDone(t: Float, name: String = "Nova", kind: MascotKind = MascotKind.Scout, pal: Pal = Pal.Iris) {
    ExStage { g ->
        val W = g.w; val H = g.h; val cx = W / 2; val cy = H * 0.30f
        Canvas(Modifier.fillMaxSize()) { shapeBlob(MaterialShapes.Cookie12Sided, Offset(cx.toPx(), cy.toPx()), g.d.toPx() * 0.5f, pal.container, t * 5f) }
        TopLabel("PAIRED", pal.primary, 10.5f, FontWeight.Bold, 2.4f)
        Orb(cx, cy, g.d * 0.17f, Mood.Idle, t, 0f, kind, glow = 0f)
        Box(Modifier.offset(cx - g.dp(90f), H * 0.52f).width(g.dp(180f)), contentAlignment = Alignment.Center) { Txt(name, 30f, Color.White, FontWeight.Black, -0.5f) }
        Box(Modifier.offset(cx - g.dp(90f), H * 0.52f + g.dp(37f)).width(g.dp(180f)), contentAlignment = Alignment.Center) { Txt("Ready on your wrist", 12f, Color.White.copy(alpha = 0.62f)) }
        ExEdge(g, "Start", pal.primary, pal.onPrimary, null, 0.56f, 42f)
    }
}

// ───────── faces ─────────

@Composable
fun ExFace(style: Int, t: Float, ambient: Boolean = false, pal: Pal = Pal.Iris) {
    ExStage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        val ink = if (ambient) V2.ambientInk else Color.White
        val m = if (ambient) Mood.Ambient else Mood.Idle
        fun fillOr(c: Color) = if (ambient) Color.Transparent else c
        fun DrawScope.blob(poly: RoundedPolygon, c: Offset, s: Float, fill: Color, rot: Float = 0f) {
            if (ambient) rotate(rot, c) { drawPath(polyPath(poly, c.x, c.y, s), V2.ambientInk.copy(alpha = 0.5f), style = Stroke(1.4f * density)) } else shapeBlob(poly, c, s, fill, rot)
        }
        when (style) {
            0 -> { // stacked bold clock
                TopLabel("THU  12  NOV", if (ambient) V2.ambientInk else pal.primary, 10.5f, FontWeight.Bold, 2.4f, y = 14f)
                Box(Modifier.offset(cx - g.dp(100f), H * 0.085f).width(g.dp(200f)), contentAlignment = Alignment.Center) { Txt("14", 84f, ink, FontWeight.Black, -3f) }
                Box(Modifier.offset(cx - g.dp(100f), H * 0.43f).width(g.dp(200f)), contentAlignment = Alignment.Center) { Txt("20", 84f, if (ambient) V2.ambientInk else pal.primary, FontWeight.Black, -3f) }
                Canvas(Modifier.fillMaxSize()) { blob(MaterialShapes.Cookie9Sided, Offset((cx + g.d * 0.33f).toPx(), (H * 0.5f).toPx()), g.d.toPx() * 0.2f, pal.container, t * 5f) }
                Orb(cx + g.d * 0.33f, H * 0.5f, g.d * 0.07f, m, t, 0f, glow = 0f)
            }
            1 -> { // the mascot is the face, bleeding off the bottom
                Box(Modifier.offset(cx - g.dp(100f), H * 0.05f).width(g.dp(200f)), contentAlignment = Alignment.Center) { Txt("14:20", 52f, ink, FontWeight.Black, -1.5f) }
                Canvas(Modifier.fillMaxSize()) { blob(MaterialShapes.Cookie12Sided, Offset(cx.toPx(), (H * 0.74f).toPx()), g.d.toPx() * 0.82f, pal.container, t * 4f) }
                Orb(cx, H * 0.72f, g.d * (if (ambient) 0.24f else 0.29f), m, t, 0f, glow = 0f)
                Box(Modifier.offset(cx - g.dp(70f), H * 0.275f).width(g.dp(140f)), contentAlignment = Alignment.Center) { Txt("THU 12", 10f, if (ambient) V2.ambientInk else pal.primary, FontWeight.Bold, 2.2f) }
                if (!ambient) Box(Modifier.at(cx + g.d * 0.28f, H * 0.5f, g.dp(30f), g.dp(30f)).clip(RoundedCornerShape(50)).background(pal.amber), contentAlignment = Alignment.Center) { Txt("2", 14f, pal.onAmber, FontWeight.Black) }
            }
            else -> { // data: shape-container complications
                TopLabel("THU 12 NOV", if (ambient) V2.ambientInk else pal.primary, 10f, FontWeight.Bold, 2.2f, y = 12f)
                Box(Modifier.offset(cx - g.dp(100f), H * 0.115f).width(g.dp(200f)), contentAlignment = Alignment.Center) { Txt("14:20", 52f, ink, FontWeight.Black, -1.5f) }
                val y = H * 0.55f; val s = g.d * 0.215f
                Canvas(Modifier.fillMaxSize()) {
                    blob(MaterialShapes.Cookie6Sided, Offset((cx - g.d * 0.29f).toPx(), y.toPx()), s.toPx(), pal.container, t * 5f)
                    blob(MaterialShapes.Cookie9Sided, Offset(cx.toPx(), y.toPx()), s.toPx() * 1.12f, pal.secC)
                    blob(MaterialShapes.Flower, Offset((cx + g.d * 0.29f).toPx(), y.toPx()), s.toPx(), pal.amberC, -t * 5f)
                }
                Box(Modifier.at(cx - g.d * 0.29f, y, s, s), contentAlignment = Alignment.Center) { Txt("3/5", 13f, if (ambient) V2.ambientInk else pal.onContainer, FontWeight.Black) }
                Orb(cx, y, g.d * 0.075f, m, t, 0f, glow = 0f)
                Box(Modifier.at(cx + g.d * 0.29f, y, s, s), contentAlignment = Alignment.Center) { Txt("2", 19f, if (ambient) V2.ambientInk else pal.onAmberC, FontWeight.Black) }
                if (!ambient) Box(Modifier.offset(cx - g.dp(70f), H * 0.78f).size(g.dp(140f), g.dp(30f)).clip(RoundedCornerShape(50)).background(pal.secC), contentAlignment = Alignment.Center) { Txt("15:00  Review", 12.5f, pal.onSecC, FontWeight.Bold) }
                else Box(Modifier.offset(cx - g.dp(84f), H * 0.80f).width(g.dp(168f)), contentAlignment = Alignment.Center) { Txt("15:00  Design review", 11.5f, V2.ambientInk.copy(alpha = 0.75f)) }
            }
        }
    }
}

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Draws the demo QR matrix as rounded dark modules on its own white tile. [pad] is the quiet zone in modules. */
fun DrawScope.qrTile(topLeft: Offset, side: Float, corner: Float, dark: Color = Color(0xFF14131A), light: Color = Color.White, pad: Int = 2) {
    drawRoundRect(light, topLeft, Size(side, side), CornerRadius(corner))
    val n = QR_ROWS.size
    val m = side / (n + 2 * pad)
    for (y in 0 until n) for (x in 0 until n) if (QR_ROWS[y][x] == '1') {
        drawRoundRect(dark, Offset(topLeft.x + (x + pad) * m, topLeft.y + (y + pad) * m), Size(m * 1.02f, m * 1.02f), CornerRadius(m * 0.18f))
    }
}

val pairCode = "482 913"

// ───────── glass direction: pairing ─────────

/** First open on the watch: a QR for the Agent phone app's camera and a short code. */
@Composable
fun PairScan(t: Float) {
    Stage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        Backdrop(Mood.Idle, t, 0.4f, 0.7f)
        TopLabel("PAIR WITH YOUR PHONE", V2.iris, 10f, FontWeight.Bold, 2f)
        val side = g.d * 0.47f
        Canvas(Modifier.fillMaxSize()) {
            val s = side.toPx(); val tl = Offset(cx.toPx() - s / 2, (H * 0.19f).toPx())
            drawRoundRect(V2.iris.copy(alpha = 0.18f), Offset(tl.x - 5.dp.toPx(), tl.y - 5.dp.toPx()), Size(s + 10.dp.toPx(), s + 10.dp.toPx()), CornerRadius(24.dp.toPx()))
            qrTile(tl, s, 18.dp.toPx())
        }
        Box(Modifier.offset(cx - g.dp(80f), H * 0.19f + side + g.dp(9f)).width(g.dp(160f)), contentAlignment = Alignment.Center) { Txt(pairCode, 22f, V2.text, FontWeight.SemiBold, 3.2f) }
        Box(Modifier.offset(cx - g.dp(92f), H * 0.19f + side + g.dp(36f)).width(g.dp(184f)), contentAlignment = Alignment.Center) { Txt("Phone: Agent, Watch, Scan", 10.5f, V2.dim) }
    }
}

@Composable
fun PairBusy(t: Float, step: Int = 1) {
    Stage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        Backdrop(Mood.Thinking, t, 0.33f, 0.9f)
        TopLabel("PAIRING", V2.iris, 10.5f, FontWeight.Bold, 2.4f)
        Orb(cx, H * 0.30f, g.d * 0.15f, Mood.Thinking, t)
        val steps = listOf("Paired with your phone", "Getting your name and look", "Syncing what needs you")
        for ((i, s) in steps.withIndex()) {
            val y = H * 0.575f + g.dp(i * 21f)
            val state = if (i < step) 2 else if (i == step) 1 else 0
            Box(Modifier.offset(cx - g.dp(88f), y).size(g.dp(176f), g.dp(18f))) {
                Canvas(Modifier.offset(0.dp, g.dp(1f)).size(g.dp(15f))) {
                    val c = Offset(size.width / 2, size.height / 2)
                    when (state) {
                        2 -> { drawCircle(V2.iris, size.width / 2); icon(Ic.check, c, size.width * 0.7f, V2.ink) }
                        1 -> { drawCircle(V2.iris.copy(alpha = 0.25f), size.width / 2); val a = (t * 3f) * 180f / PI.toFloat(); drawArc(V2.iris, a, 100f, false, Offset(1.5f * density, 1.5f * density), Size(size.width - 3f * density, size.height - 3f * density), style = Stroke(2f * density, cap = StrokeCap.Round)) }
                        else -> drawCircle(V2.faint.copy(alpha = 0.5f), size.width / 2 - density, style = Stroke(1.4f * density))
                    }
                }
                Box(Modifier.offset(g.dp(23f), 0.dp)) { Txt(s, 12f, if (state == 0) V2.faint else V2.text, if (state == 1) FontWeight.SemiBold else FontWeight.Normal, align = androidx.compose.ui.text.style.TextAlign.Start, maxLines = 1) }
            }
        }
    }
}

@Composable
fun PairDone(t: Float, name: String = "Nova", kind: MascotKind = MascotKind.Scout) {
    Stage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        Backdrop(Mood.Idle, t, 0.32f, 1f)
        TopLabel("PAIRED", V2.iris, 10.5f, FontWeight.Bold, 2.4f)
        Orb(cx, H * 0.31f, g.d * 0.17f, Mood.Idle, t, 0f, kind)
        Box(Modifier.offset(cx - g.dp(90f), H * 0.54f).width(g.dp(180f)), contentAlignment = Alignment.Center) { Txt(name, 26f, V2.text, FontWeight.Medium, -0.3f) }
        Box(Modifier.offset(cx - g.dp(90f), H * 0.54f + g.dp(34f)).width(g.dp(180f)), contentAlignment = Alignment.Center) { Txt("Ready on your wrist", 12f, V2.dim) }
        val bw = g.dp(120f)
        Box(Modifier.offset(cx - bw / 2, H * 0.79f).size(bw, g.dp(34f)).clip(RoundedCornerShape(50)).background(Brush.verticalGradient(listOf(V2.irisHi, V2.iris))), contentAlignment = Alignment.Center) { Txt("Start", 13.5f, V2.ink, FontWeight.SemiBold) }
    }
}

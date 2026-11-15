package com.past9.phoneaos.wear.ui.ex

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.math.sin

enum class GlyphKind { Flick, Pinch, Shake, Raise }

/** A small drawn picture of each gesture: the watch and the motion, quiet, in one colour. */
fun DrawScope.gestureGlyph(kind: GlyphKind, c: Offset, s: Float, col: Color, t: Float) {
    val sw = s * 0.07f
    val w = s * 0.30f; val h = s * 0.38f
    fun watch(rot: Float, dx: Float = 0f, dy: Float = 0f) = rotate(rot, pivot = c) {
        drawRoundRect(col, Offset(c.x - w / 2 + dx, c.y - h / 2 + dy), Size(w, h), CornerRadius(s * 0.09f), style = Stroke(sw))
        drawLine(col.copy(alpha = 0.6f), Offset(c.x - w * 0.2f + dx, c.y - h / 2 + dy - s * 0.06f), Offset(c.x + w * 0.2f + dx, c.y - h / 2 + dy - s * 0.06f), sw, StrokeCap.Round)
    }
    val sway = sin(t * 3f)
    when (kind) {
        GlyphKind.Flick -> {
            watch(-14f + 14f * sway)
            drawArc(col, 200f, 60f, false, Offset(c.x - s * 0.46f, c.y - s * 0.46f), Size(s * 0.92f, s * 0.92f), style = Stroke(sw, cap = StrokeCap.Round))
            drawArc(col, 280f, 60f, false, Offset(c.x - s * 0.46f, c.y - s * 0.46f), Size(s * 0.92f, s * 0.92f), style = Stroke(sw, cap = StrokeCap.Round))
        }
        GlyphKind.Pinch -> {
            watch(0f, 0f, -s * 0.06f)
            val g = s * (0.05f + 0.03f * (0.5f + 0.5f * sin(t * 4f)))
            drawCircle(col, s * 0.065f, Offset(c.x - g - s * 0.02f, c.y + s * 0.32f)); drawCircle(col, s * 0.065f, Offset(c.x + g + s * 0.02f, c.y + s * 0.32f))
        }
        GlyphKind.Shake -> {
            watch(0f, sway * s * 0.05f)
            for (side in listOf(-1f, 1f)) {
                val x0 = c.x + side * s * 0.30f
                drawLine(col, Offset(x0, c.y - s * 0.16f), Offset(x0 + side * s * 0.08f, c.y - s * 0.05f), sw, StrokeCap.Round)
                drawLine(col, Offset(x0 + side * s * 0.08f, c.y - s * 0.05f), Offset(x0, c.y + s * 0.06f), sw, StrokeCap.Round)
                drawLine(col, Offset(x0, c.y + s * 0.06f), Offset(x0 + side * s * 0.08f, c.y + s * 0.17f), sw, StrokeCap.Round)
            }
        }
        GlyphKind.Raise -> {
            watch(0f, 0f, s * 0.06f)
            icon(Ic.back, Offset(c.x, c.y - s * 0.34f), s * 0.28f, col, rot = 90f)
        }
    }
}

/** The QR from [rows] ("1" dark, "0" light) as rounded dark modules on its own white tile; [pad] is the quiet zone in modules. */
fun DrawScope.qrTile(rows: List<String>, topLeft: Offset, side: Float, corner: Float, dark: Color = Color(0xFF14131A), light: Color = Color.White, pad: Int = 2) {
    drawRoundRect(light, topLeft, Size(side, side), CornerRadius(corner))
    val n = rows.size
    val m = side / (n + 2 * pad)
    for (y in 0 until n) for (x in 0 until n) if (rows[y][x] == '1') {
        drawRoundRect(dark, Offset(topLeft.x + (x + pad) * m, topLeft.y + (y + pad) * m), Size(m * 1.02f, m * 1.02f), CornerRadius(m * 0.18f))
    }
}

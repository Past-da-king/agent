package com.past9.phoneaos.wear.ui.ex

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The look of the watch app: Material 3 Expressive for Wear in the Agent's own tokens. Flat tonal colour blocks, shape containers cut
 * from Material's own shape library, wavy progress, a bold type scale, buttons that rise from the bottom bezel, and the phone's own
 * mascot. The palette is derived from one seed colour (the accent the phone syncs), the way Material's dynamic colour does it; the
 * default seed is the Agent's iris.
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

        /** The phone's accents (ui/theme/Theme.kt Accents), dark: primary, on primary, container, on container. */
        private val accents: Map<String, List<Long>> = mapOf(
            "iris" to listOf(0xFFC3BBFF, 0xFF241A7A, 0xFF3A2FB0, 0xFFE7E3FF), "ocean" to listOf(0xFFA9C7FF, 0xFF003064, 0xFF004A99, 0xFFD6E4FF),
            "teal" to listOf(0xFF7FD6D4, 0xFF003737, 0xFF004F50, 0xFFB9F0EE), "forest" to listOf(0xFF9CD89A, 0xFF0A390D, 0xFF1B5220, 0xFFC6EFBC),
            "ember" to listOf(0xFFFFB591, 0xFF5A1C00, 0xFF853000, 0xFFFFDBCB), "rose" to listOf(0xFFFFB1C8, 0xFF650033, 0xFF8E0D48, 0xFFFFD9E2),
            "red" to listOf(0xFFFF5A4F, 0xFF2D0001, 0xFF93000F, 0xFFFFDAD6), "tangerine" to listOf(0xFFFF8F3A, 0xFF2A1100, 0xFF8A3D00, 0xFFFFDCC4),
            "sunflower" to listOf(0xFFFFD233, 0xFF231A00, 0xFF5B4300, 0xFFFFE08A), "lime" to listOf(0xFF9BE34A, 0xFF132100, 0xFF2E5200, 0xFFD2F5A6),
            "mint" to listOf(0xFF4FDDB4, 0xFF00201A, 0xFF00513E, 0xFFB2F2DF), "sky" to listOf(0xFF52B8FF, 0xFF00182B, 0xFF004B78, 0xFFCDE5FF),
            "violet" to listOf(0xFFB98AFF, 0xFF25005A, 0xFF5A00C6, 0xFFEADDFF), "magenta" to listOf(0xFFFF6BC1, 0xFF3D0026, 0xFF8F005B, 0xFFFFD8EA),
            "graphite" to listOf(0xFFE6E4EC, 0xFF1B1A1F, 0xFF3A3940, 0xFFE6E4EC),
        )

        /** The palette for the accent id the phone syncs (unknown or empty: iris), with the container steps the phone itself uses. */
        fun ofAccent(id: String?): Pal {
            val a = accents[id ?: ""] ?: return Iris
            val base = of(Color(a[0].toInt()))
            return Pal(Color(a[0].toInt()), Color(a[1].toInt()), Color(a[2].toInt()), Color(a[3].toInt()), base.deep, base.secC, base.onSecC, base.surfHi, base.dimText)
        }
    }
}

fun polyPath(poly: RoundedPolygon, cx: Float, cy: Float, size: Float): Path {
    val p = Path(); var first = true
    for (c in poly.normalized().cubics) {
        if (first) { p.moveTo(cx + (c.anchor0X - 0.5f) * size, cy + (c.anchor0Y - 0.5f) * size); first = false }
        p.cubicTo(cx + (c.control0X - 0.5f) * size, cy + (c.control0Y - 0.5f) * size, cx + (c.control1X - 0.5f) * size, cy + (c.control1Y - 0.5f) * size, cx + (c.anchor1X - 0.5f) * size, cy + (c.anchor1Y - 0.5f) * size)
    }
    p.close(); return p
}

/** A filled Material shape (a container) centred on [c], [size] px wide. */
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

/** The stage: flat black, or a full-bleed tonal colour. */
@Composable
fun ExStage(bg: Color = Color.Black, content: @Composable BoxScope.(Geo) -> Unit) {
    Stage { g -> Box(Modifier.fillMaxSize().background(bg)) { content(g) } }
}

/** The edge button: a wide pill that rises from the bottom bezel and hugs it. */
@Composable
fun BoxScope.ExEdge(g: Geo, label: String, fill: Color, text: Color, icon: String? = null, wFrac: Float = 0.72f, h: Float = 46f, onClick: () -> Unit = {}) {
    val w = g.d * (if (label.length > 9) maxOf(wFrac, 0.86f) else wFrac); val hh = g.dp(h)
    val shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
    Box(
        Modifier.align(Alignment.BottomCenter).offset(0.dp, g.dp(2f)).size(w, hh).clip(shape).background(fill)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(Modifier.offset(0.dp, g.dp(10f)), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { IconBox(icon, g.dp(18f), text); Spacer(Modifier.width(g.dp(6f))) }
            Txt(label, 15f, text, FontWeight.Bold, 0.1f, maxLines = 1)
        }
    }
}

/** A Material-shape icon button (cookie, flower, pill) in a tonal colour. */
@Composable
fun ExShapeBtn(cx: Dp, cy: Dp, size: Dp, poly: RoundedPolygon, fill: Color, icon: String, glyph: Color, rot: Float = 0f, onClick: (() -> Unit)? = null) {
    Canvas(Modifier.fillMaxSize()) { shapeBlob(poly, Offset(cx.toPx(), cy.toPx()), size.toPx(), fill, rot); icon(icon, Offset(cx.toPx(), cy.toPx()), size.toPx() * 0.46f, glyph) }
    if (onClick != null) Box(Modifier.offset(cx - size / 2, cy - size / 2).size(size).clip(RoundedCornerShape(50)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick))
}

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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// ───────── 8. Gestures ─────────

enum class Gesture(val title: String) { Flick("Wrist flick"), Pinch("Double pinch"), Shake("Shake"), Raise("Raise to talk") }

/** A small drawn picture of each gesture: the watch and the motion, quiet, in one colour. */
fun DrawScope.gestureGlyph(kind: Gesture, c: Offset, s: Float, col: Color, t: Float) {
    val sw = s * 0.07f
    val w = s * 0.30f; val h = s * 0.38f
    fun watch(rot: Float, dx: Float = 0f, dy: Float = 0f) = rotate(rot, pivot = c) {
        drawRoundRect(col, Offset(c.x - w / 2 + dx, c.y - h / 2 + dy), Size(w, h), CornerRadius(s * 0.09f), style = Stroke(sw))
        drawLine(col.copy(alpha = 0.6f), Offset(c.x - w * 0.2f + dx, c.y - h / 2 + dy - s * 0.06f), Offset(c.x + w * 0.2f + dx, c.y - h / 2 + dy - s * 0.06f), sw, StrokeCap.Round)
    }
    val sway = sin(t * 3f)
    when (kind) {
        Gesture.Flick -> {
            watch(-14f + 14f * sway)
            drawArc(col, 200f, 60f, false, Offset(c.x - s * 0.46f, c.y - s * 0.46f), Size(s * 0.92f, s * 0.92f), style = Stroke(sw, cap = StrokeCap.Round))
            drawArc(col, 280f, 60f, false, Offset(c.x - s * 0.46f, c.y - s * 0.46f), Size(s * 0.92f, s * 0.92f), style = Stroke(sw, cap = StrokeCap.Round))
        }
        Gesture.Pinch -> {
            watch(0f, 0f, -s * 0.06f)
            val g = s * (0.05f + 0.03f * (0.5f + 0.5f * sin(t * 4f)))
            drawCircle(col, s * 0.065f, Offset(c.x - g - s * 0.02f, c.y + s * 0.32f)); drawCircle(col, s * 0.065f, Offset(c.x + g + s * 0.02f, c.y + s * 0.32f))
            drawArc(col.copy(alpha = 0.6f), 20f, 140f, false, Offset(c.x - s * 0.18f, c.y + s * 0.18f), Size(s * 0.36f, s * 0.3f), style = Stroke(sw * 0.8f, cap = StrokeCap.Round))
        }
        Gesture.Shake -> {
            watch(0f, sway * s * 0.05f)
            for (side in listOf(-1f, 1f)) {
                val x0 = c.x + side * s * 0.30f
                drawLine(col, Offset(x0, c.y - s * 0.16f), Offset(x0 + side * s * 0.08f, c.y - s * 0.05f), sw, StrokeCap.Round)
                drawLine(col, Offset(x0 + side * s * 0.08f, c.y - s * 0.05f), Offset(x0, c.y + s * 0.06f), sw, StrokeCap.Round)
                drawLine(col, Offset(x0, c.y + s * 0.06f), Offset(x0 + side * s * 0.08f, c.y + s * 0.17f), sw, StrokeCap.Round)
            }
        }
        Gesture.Raise -> {
            watch(0f, 0f, s * 0.06f)
            icon(Ic.back, Offset(c.x, c.y - s * 0.34f), s * 0.28f, col, rot = 90f)
        }
    }
}

@Composable
fun GestureRow(kind: Gesture, action: String, supported: Boolean, t: Float, cx: Dp, y: Dp, hw: Dp, selected: Boolean = false) {
    val g = LocalGeo.current
    val shape = RoundedCornerShape(50)
    val a = if (supported) 1f else 0.5f
    Box(Modifier.offset(cx - hw, y).size(hw * 2, g.dp(42f)).alpha(a).let { if (selected) it.clip(shape).background(Brush.horizontalGradient(listOf(V2.irisDeep.copy(alpha = 0.75f), V2.irisDeep.copy(alpha = 0.25f)))).border(1.dp, V2.iris.copy(alpha = 0.5f), shape) else it.glass(shape) }) {
        Canvas(Modifier.offset(g.dp(5f), g.dp(5f)).size(g.dp(32f))) {
            drawCircle(V2.iris.copy(alpha = 0.14f), size.width / 2)
            gestureGlyph(kind, Offset(size.width / 2, size.height / 2), size.width * 0.82f, if (supported) V2.iris else V2.faint, t)
        }
        Box(Modifier.offset(g.dp(43f), g.dp(if (supported) 7f else 5f))) {
            androidx.compose.foundation.layout.Column {
                Txt(kind.title, 12.5f, V2.text, FontWeight.SemiBold, align = TextAlign.Start, maxLines = 1)
                if (supported) Txt(action, 11f, if (action == "None") V2.faint else V2.iris, FontWeight.Medium, align = TextAlign.Start)
                else Txt("Not on this watch", 10.5f, V2.faint, align = TextAlign.Start)
            }
        }
        if (!supported) IconBox(Ic.lock, g.dp(13f), V2.faint, Modifier.offset(hw * 2 - g.dp(26f), g.dp(14f)))
        else IconBox(Ic.chevron, g.dp(15f), V2.faint, Modifier.offset(hw * 2 - g.dp(24f), g.dp(13f)))
    }
}

@Composable
fun GesturesScreen(t: Float, picker: Float = 0f) {
    Stage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        Backdrop(Mood.Idle, t, 0.2f, 0.5f)
        TopLabel("GESTURES", V2.iris, 10.5f, FontWeight.Bold, 2.2f)
        val rows = listOf(Triple(Gesture.Flick, "Talk", true), Triple(Gesture.Pinch, "Approve", true), Triple(Gesture.Shake, "Dismiss", true), Triple(Gesture.Raise, "Off", false))
        for ((i, r) in rows.withIndex()) {
            val y = H * 0.2f + g.dp(i * 47f)
            val hw = minOf(g.chord(y, 6.dp), g.chord(y + g.dp(42f), 6.dp)).coerceAtMost(g.dp(104f))
            GestureRow(r.first, r.second, r.third, t, cx, y, hw)
        }
        Canvas(Modifier.fillMaxSize()) { drawRect(Brush.verticalGradient(0f to Color.Black, 0.12f to Color.Transparent, 0.78f to Color.Transparent, 1f to Color.Black)) }
        CrownRail(0f, 0.4f)
        if (picker > 0f) PickerSheet(picker, "Wrist flick")
    }
}

/** The action picker rises from the bottom, over a dimmed list. */
@Composable
private fun PickerSheet(p: Float, title: String) {
    val g = LocalGeo.current
    val e = easeOut(p)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f * e)))
    val top = g.h * (1f - 0.80f * e)
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Box(Modifier.offset(0.dp, top).size(g.w, g.h * 0.82f).clip(shape).background(Brush.verticalGradient(listOf(Color(0xFF26242F), Color(0xFF15141B)))).border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.25f), Color.Transparent)), shape)) {
        Box(Modifier.offset(g.w / 2 - g.dp(14f), g.dp(8f)).size(g.dp(28f), g.dp(4f)).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.3f)))
        Box(Modifier.offset(0.dp, g.dp(17f)).width(g.w), contentAlignment = Alignment.Center) { Txt(title.uppercase(), 10f, V2.iris, FontWeight.Bold, 2f) }
        Box(Modifier.offset(0.dp, g.dp(31f)).width(g.w), contentAlignment = Alignment.Center) { Txt("Choose the action", 10.5f, V2.faint) }
        val opts = listOf("Talk", "Approve", "Dismiss", "Call", "None")
        for ((i, o) in opts.withIndex()) {
            val y = g.dp(48f + i * 35f); val hw = g.chord(g.h * 0.2f + y, 16.dp).coerceAtMost(g.dp(88f))
            val sel = i == 0
            Box(Modifier.offset(g.w / 2 - hw, y).size(hw * 2, g.dp(31f)).let { if (sel) it.clip(RoundedCornerShape(50)).background(V2.irisDeep.copy(alpha = 0.85f)).border(1.dp, V2.iris.copy(alpha = 0.6f), RoundedCornerShape(50)) else it.glass(RoundedCornerShape(50)) }) {
                Box(Modifier.offset(g.dp(14f), g.dp(7f))) { Txt(o, 12.5f, if (sel) V2.irisHi else V2.text, FontWeight.Medium) }
                Box(Modifier.offset(hw * 2 - g.dp(26f), g.dp(8f)).size(g.dp(14f)).clip(CircleShape).border(1.5.dp, if (sel) V2.irisHi else V2.faint, CircleShape), contentAlignment = Alignment.Center) {
                    if (sel) Box(Modifier.size(g.dp(7f)).clip(CircleShape).background(V2.irisHi))
                }
            }
        }
    }
}

/** A gesture this watch cannot do: the agent sleeps, says why, and offers the one that works. */
@Composable
fun GestureUnsupportedScreen(t: Float) {
    Stage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        Backdrop(Mood.Asleep, t, 0.3f, 0.35f)
        TopLabel("RAISE TO TALK", V2.faint, 10f, FontWeight.Bold, 2f)
        Orb(cx, H * 0.30f, g.d * 0.12f, Mood.Asleep, t)
        val half = g.chord(H * 0.55f, 18.dp)
        Box(Modifier.offset(cx - half, H * 0.47f).width(half * 2), contentAlignment = Alignment.Center) { Txt("Not on this watch", 17f, V2.text, FontWeight.SemiBold) }
        Box(Modifier.offset(cx - half, H * 0.575f).width(half * 2), contentAlignment = Alignment.Center) { Txt("No sensor for it here. Wrist flick does the same job.", 11.5f, V2.dim, lh = 15f) }
        Chip("Use wrist flick", Ic.chevron, V2.iris, cx, H * 0.855f, g.dp(138f), g.dp(32f), 12.5f, filled = true)
    }
}

// ───────── 9. Tiles and complications ─────────

@Composable
fun TileTalk(t: Float) {
    Stage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        Backdrop(Mood.Idle, t, 0.42f, 0.9f)
        TopLabel("NOVA", V2.iris, 10.5f, FontWeight.Bold, 2.4f)
        Orb(cx, H * 0.42f, g.d * 0.2f, Mood.Idle, t)
        Chip("Tap to talk", Ic.mic, V2.iris, cx, H * 0.80f, g.dp(132f), g.dp(34f), 13f)
    }
}

@Composable
fun TileGlance(t: Float) {
    Stage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        Backdrop(Mood.Needs, t, 0.3f, 0.5f)
        TopLabel("TODAY", V2.iris, 10.5f, FontWeight.Bold, 2.4f)
        val hw = g.chord(H * 0.5f, 16.dp).coerceAtMost(g.dp(96f))
        // approvals card
        Box(Modifier.offset(cx - hw, H * 0.17f).size(hw * 2, g.dp(54f)).clip(RoundedCornerShape(26.dp)).background(Brush.horizontalGradient(listOf(V2.amberDeep.copy(alpha = 0.85f), V2.amberDeep.copy(alpha = 0.3f)))).border(1.dp, V2.amber.copy(alpha = 0.45f), RoundedCornerShape(26.dp))) {
            Orb(g.dp(30f), g.dp(27f), g.dp(12f), Mood.Needs, t, glow = 0.4f)
            Box(Modifier.offset(g.dp(52f), g.dp(8f))) { androidx.compose.foundation.layout.Column {
                Txt("2 need you", 14.5f, V2.amberHi, FontWeight.SemiBold, align = TextAlign.Start)
                Txt("Invoice 0042 and 1 more", 10.5f, V2.amberHi.copy(alpha = 0.7f), align = TextAlign.Start)
            } }
        }
        // next task card
        Box(Modifier.offset(cx - hw, H * 0.17f + g.dp(60f)).size(hw * 2, g.dp(54f)).glass(RoundedCornerShape(26.dp))) {
            IconBox(Ic.history, g.dp(18f), V2.iris, Modifier.offset(g.dp(14f), g.dp(18f)))
            Box(Modifier.offset(g.dp(42f), g.dp(8f))) { androidx.compose.foundation.layout.Column {
                Txt("15:00", 14.5f, V2.text, FontWeight.SemiBold, align = TextAlign.Start)
                Txt("Design review", 10.5f, V2.dim, align = TextAlign.Start)
            } }
        }
        Chip("Talk", Ic.mic, V2.iris, cx, H * 0.84f, g.dp(98f), g.dp(32f), 12.5f, filled = true)
    }
}

/** Four complication types as they sit on a face, one image so they can be judged together. */
@Composable
fun ComplicationGallery(t: Float) {
    Stage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        Backdrop(Mood.Idle, t, 0.4f, 0.3f)
        TopLabel("COMPLICATIONS", V2.iris, 10f, FontWeight.Bold, 2f)
        val s = g.d * 0.21f; val y1 = H * 0.30f
        // 1: tap to talk (small image)
        Canvas(Modifier.fillMaxSize()) {
            val r = s.toPx() / 2; val pts = listOf(Offset((cx - g.d * 0.26f).toPx(), y1.toPx()), Offset(cx.toPx(), y1.toPx()), Offset((cx + g.d * 0.26f).toPx(), y1.toPx()))
            // small image: the agent
            drawCircle(Color.White.copy(alpha = 0.07f), r, pts[0]); orb(pts[0], r * 0.78f, Mood.Idle, t, glow = 0.4f)
            // short text: approvals, amber ring
            drawCircle(Color.White.copy(alpha = 0.07f), r, pts[1]); drawCircle(V2.amber, r - 1.5f * density, pts[1], style = Stroke(2.2f * density))
            icon(Ic.shield, Offset(pts[1].x, pts[1].y - r * 0.38f), r * 0.55f, V2.amber)
            // range: tasks 3 of 5
            drawCircle(Color.White.copy(alpha = 0.07f), r, pts[2])
            drawArc(Color.White.copy(alpha = 0.14f), 0f, 360f, false, Offset(pts[2].x - r + 3 * density, pts[2].y - r + 3 * density), Size((r - 3 * density) * 2, (r - 3 * density) * 2), style = Stroke(3 * density))
            drawArc(V2.iris, -90f, 216f, false, Offset(pts[2].x - r + 3 * density, pts[2].y - r + 3 * density), Size((r - 3 * density) * 2, (r - 3 * density) * 2), style = Stroke(3 * density, cap = StrokeCap.Round))
        }
        Box(Modifier.offset(cx - g.d * 0.26f - s / 2, y1 + s * 0.62f).width(s), contentAlignment = Alignment.Center) { Txt("Tap to talk", 8.5f, V2.faint) }
        Box(Modifier.offset(cx - s / 2, y1 + s * 0.62f).width(s), contentAlignment = Alignment.Center) { Txt("Approvals", 8.5f, V2.faint) }
        Box(Modifier.offset(cx + g.d * 0.26f - s / 2, y1 + s * 0.62f).width(s), contentAlignment = Alignment.Center) { Txt("Tasks", 8.5f, V2.faint) }
        Box(Modifier.offset(cx - s / 2, y1 - g.dp(4f)).width(s).offset(0.dp, s * 0.46f), contentAlignment = Alignment.Center) { }
        Box(Modifier.offset(cx - s / 2, y1 + g.dp(2f)).width(s), contentAlignment = Alignment.Center) { Txt("2", 15f, V2.amberHi, FontWeight.SemiBold) }
        Box(Modifier.offset(cx + g.d * 0.26f - s / 2, y1 - g.dp(8f)).width(s).offset(0.dp, s * 0.34f), contentAlignment = Alignment.Center) { Txt("3/5", 11f, V2.text, FontWeight.SemiBold) }
        // long text
        val hw = g.chord(H * 0.66f, 14.dp).coerceAtMost(g.dp(100f))
        Box(Modifier.offset(cx - hw, H * 0.60f).size(hw * 2, g.dp(40f)).glass(RoundedCornerShape(50))) {
            Box(Modifier.offset(g.dp(16f), g.dp(5f))) { androidx.compose.foundation.layout.Column {
                Txt("NEXT TASK", 8.5f, V2.iris, FontWeight.Bold, 1.4f, align = TextAlign.Start)
                Txt("15:00  Design review", 13f, V2.text, FontWeight.SemiBold, align = TextAlign.Start)
            } }
        }
    }
}

// ───────── 10. Launcher ─────────

private val appSpecs = listOf(
    Triple("Phone", Ic.call, Color(0xFF3A2FB0)), Triple("Messages", Ic.chat, Color(0xFF45435A)), Triple("Timer", Ic.history, Color(0xFF292830)),
    Triple("Maps", Ic.pin, Color(0xFF45435A)), Triple("Music", Ic.music, Color(0xFF3A2FB0)), Triple("Fitness", Ic.heart, Color(0xFF292830)),
    Triple("Alarms", Ic.bell, Color(0xFF292830)), Triple("Weather", Ic.sun, Color(0xFF3A2FB0)), Triple("Settings", Ic.apps, Color(0xFF45435A)),
    Triple("Wallet", Ic.star, Color(0xFF292830)), Triple("Notes", Ic.keyboard, Color(0xFF3A2FB0)), Triple("Camera", Ic.volume, Color(0xFF45435A)),
)

/** The Agent as the home screen: the same home, with the clock big, and hints for what each swipe reaches. */
@Composable
fun LauncherHome(t: Float, pending: Int = 0) {
    Stage { g ->
        val W = g.w; val H = g.h; val cx = W / 2; val cy = H * 0.575f; val r = g.d * 0.15f
        val m = if (pending > 0) Mood.Needs else Mood.Idle
        Backdrop(m, t, 0.55f, 1f)
        Box(Modifier.offset(cx - g.dp(80f), H * 0.085f).width(g.dp(160f)), contentAlignment = Alignment.Center) {
            Txt("14:20", 46f, V2.text, FontWeight.Light, -0.5f, brush = Brush.verticalGradient(listOf(Color.White, V2.irisHi.copy(alpha = 0.78f))))
        }
        Box(Modifier.offset(cx - g.dp(80f), H * 0.285f).width(g.dp(160f)), contentAlignment = Alignment.Center) { Txt("THU  12 NOV", 10f, V2.iris, FontWeight.Bold, 2.1f) }
        Orb(cx, cy, r, m, t)
        if (pending > 0) Chip("$pending needs you", Ic.shield, V2.amber, cx + g.d * 0.0f, H * 0.80f, g.dp(108f), g.dp(24f), 11.5f, filled = true)
        else Box(Modifier.offset(cx - g.dp(80f), cy + r * 1.62f).width(g.dp(160f)), contentAlignment = Alignment.Center) { Txt("Tap to talk", 12.5f, V2.dim, FontWeight.Medium) }
        // swipe hints: up = apps, sides = tiles, down = notifications
        Canvas(Modifier.fillMaxSize()) {
            val up = Offset(size.width / 2, size.height - 13.dp.toPx())
            icon(Ic.chevron, up, 14.dp.toPx(), V2.faint, rot = -90f)
            for (i in 0 until 3) drawCircle(if (i == 1) V2.iris else V2.faint.copy(alpha = 0.5f), (if (i == 1) 2.2f else 1.6f) * density, Offset(10.dp.toPx(), size.height / 2 + (i - 1) * 9.dp.toPx()))
            drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(size.width / 2 - 11.dp.toPx(), 3.dp.toPx()), Size(22.dp.toPx(), 3.dp.toPx()), CornerRadius(2.dp.toPx()))
        }
    }
}

/**
 * The app grid as a fish-eye honeycomb: the agent is the centre cell, apps fan out around it and shrink toward the edge.
 * [fan] 0..1 grows the cells from the centre (the home to grid transition); [pan] slides the grid under the crown.
 */
@Composable
fun AppGridScreen(t: Float, fan: Float = 1f, pan: Offset = Offset.Zero, focus: Int = -1) {
    Stage { g ->
        val W = g.w; val H = g.h; val cx = W / 2; val cy = H / 2
        Backdrop(Mood.Idle, t, 0.5f, 0.45f * fan)
        val cell = g.d * 0.215f; val step = g.d * 0.262f
        // positions: centre, ring of 6, ring of 12
        val pts = ArrayList<Pair<Float, Float>>() // unit hex coordinates in step units
        pts += 0f to 0f
        for (k in 0 until 6) { val a = (30f + 60f * k) * PI.toFloat() / 180f; pts += cos(a) to sin(a) }
        for (k in 0 until 6) { val a = (30f + 60f * k) * PI.toFloat() / 180f; pts += 2f * cos(a) to 2f * sin(a); val a2 = (60f * k) * PI.toFloat() / 180f; pts += sqrt(3f) * cos(a2) to sqrt(3f) * sin(a2) }
        val reach = g.d * 0.56f
        for ((i, p) in pts.withIndex().reversed()) {
            val px = cx + step * p.first * fan + pan.x.dp; val py = cy + step * p.second * fan + pan.y.dp
            val dx = (px - cx).value; val dy = (py - cy).value
            val dist = sqrt(dx * dx + dy * dy)
            val sc = (1.08f - 0.62f * (dist / reach.value)).coerceIn(0.2f, 1.08f) * (0.35f + 0.65f * fan)
            if (sc < 0.26f) continue
            val sz = cell * sc
            val al = clamp01((1.0f - (dist / reach.value - 0.62f) * 2.6f)).coerceAtLeast(0f)
            if (al <= 0.02f) continue
            if (i == 0) {
                Orb(px, py, sz * 0.5f * 0.92f, Mood.Idle, t, glow = 0.8f * fan)
            } else {
                val spec = appSpecs[(i - 1) % appSpecs.size]
                Box(Modifier.offset(px - sz / 2, py - sz / 2).size(sz).alpha(al).clip(CircleShape).background(Brush.verticalGradient(listOf(lerpC(spec.third, Color.White, 0.16f), spec.third))).border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                    IconBox(spec.second, sz * 0.46f, V2.irisHi.copy(alpha = 0.92f))
                }
            }
        }
        Canvas(Modifier.fillMaxSize()) { drawRect(Brush.verticalGradient(0.78f to Color.Transparent, 0.9f to Color.Black.copy(alpha = 0.85f * fan), 1f to Color.Black.copy(alpha = fan))) }
        val label = if (focus < 0) "Agent" else appSpecs[focus % appSpecs.size].first
        Box(Modifier.offset(cx - g.dp(60f), H * 0.915f - g.dp(8f)).width(g.dp(120f)).alpha(fan), contentAlignment = Alignment.Center) { Txt(label, 12f, V2.text, FontWeight.SemiBold) }
        CrownRail(0.3f, 0.35f)
    }
}

/** Recent apps: a deck of cards, the newest in front. */
@Composable
fun RecentsScreen(t: Float) {
    Stage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        Backdrop(Mood.Idle, t, 0.4f, 0.4f)
        TopLabel("RECENT", V2.iris, 10.5f, FontWeight.Bold, 2.4f)
        val names = listOf("Agent", "Maps", "Timer")
        for (i in names.indices.reversed()) {
            val sc = 1f - 0.085f * i; val cw = g.dp(168f) * sc
            val ch = g.dp(112f) * sc; val y = H * 0.19f + g.dp(i * -16f) + (if (i == 0) g.dp(8f) else 0.dp) - (if (i > 0) g.dp(0f) else 0.dp)
            val yy = H * 0.50f - ch / 2 - g.dp(i * 17f) + g.dp(10f)
            val spec = if (i == 0) null else appSpecs[if (i == 1) 3 else 2]
            Box(Modifier.offset(cx - cw / 2, yy).size(cw, ch).alpha(1f - 0.18f * i).clip(RoundedCornerShape(26.dp)).background(Brush.verticalGradient(listOf(Color(0xFF2A2833), Color(0xFF17161D)))).border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.04f))), RoundedCornerShape(26.dp))) {
                if (i == 0) {
                    Canvas(Modifier.fillMaxSize()) { drawRect(Brush.radialGradient(0f to V2.iris.copy(alpha = 0.22f), 1f to Color.Transparent, center = Offset(size.width / 2, size.height * 0.45f), radius = size.width * 0.7f)) }
                    Orb(cw / 2, ch * 0.45f, g.dp(24f) * sc, Mood.Idle, t, glow = 0.5f)
                    Box(Modifier.offset(0.dp, ch - g.dp(26f)).width(cw), contentAlignment = Alignment.Center) { Txt("Agent  ·  now", 10.5f, V2.dim, FontWeight.Medium) }
                } else if (spec != null) {
                    IconBox(spec.second, g.dp(30f) * sc, V2.irisHi.copy(alpha = 0.8f), Modifier.offset(cw / 2 - g.dp(15f) * sc, ch * 0.30f))
                    Box(Modifier.offset(0.dp, ch - g.dp(26f)).width(cw), contentAlignment = Alignment.Center) { Txt(names[i] + (if (i == 1) "  ·  2 min" else "  ·  14 min"), 10f, V2.faint, FontWeight.Medium) }
                }
            }
        }
        Chip("Clear all", Ic.close, V2.iris, cx, H * 0.875f, g.dp(104f), g.dp(26f), 11f)
        CrownRail(0f, 0.5f)
    }
}

// choose / switch-back flow

@Composable
fun HomeAppSetting(t: Float, selected: Int = 1) {
    Stage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        Backdrop(Mood.Idle, t, 0.3f, 0.45f)
        TopLabel("WATCH HOME", V2.iris, 10.5f, FontWeight.Bold, 2.2f)
        val hw = g.chord(H * 0.5f, 14.dp).coerceAtMost(g.dp(96f))
        val options = listOf("Agent home" to "The agent opens first. Apps are a swipe away.", "Standard home" to "Your watch as it came.")
        for ((i, o) in options.withIndex()) {
            val y = H * 0.17f + g.dp(i * 76f); val sel = (i == 0 && selected == 0) || (i == 1 && selected == 1)
            val shape = RoundedCornerShape(26.dp)
            Box(Modifier.offset(cx - hw, y).size(hw * 2, g.dp(72f)).let { if (sel) it.clip(shape).background(Brush.horizontalGradient(listOf(V2.irisDeep.copy(alpha = 0.85f), V2.irisDeep.copy(alpha = 0.3f)))).border(1.dp, V2.iris.copy(alpha = 0.6f), shape) else it.glass(shape) }) {
                if (i == 0) Orb(g.dp(30f), g.dp(36f), g.dp(11f), Mood.Idle, t, glow = 0.4f)
                else Canvas(Modifier.offset(g.dp(17f), g.dp(23f)).size(g.dp(26f))) { drawCircle(V2.faint, size.width / 2, style = Stroke(1.6f * density)); drawLine(V2.faint, center, Offset(center.x, center.y - 8 * density), 1.6f * density, StrokeCap.Round); drawLine(V2.faint, center, Offset(center.x + 6 * density, center.y), 1.6f * density, StrokeCap.Round) }
                Box(Modifier.offset(g.dp(52f), g.dp(11f))) { androidx.compose.foundation.layout.Column(Modifier.width(hw * 2 - g.dp(66f))) {
                    Txt(o.first, 13.5f, if (sel) V2.irisHi else V2.text, FontWeight.SemiBold, align = TextAlign.Start)
                    Txt(o.second, 10f, V2.dim, align = TextAlign.Start, lh = 12.5f)
                } }
                if (sel) IconBox(Ic.check, g.dp(15f), V2.irisHi, Modifier.offset(hw * 2 - g.dp(26f), g.dp(8f)))
            }
        }
        Box(Modifier.offset(cx - hw, H * 0.17f + g.dp(152f)).width(hw * 2), contentAlignment = Alignment.Center) { Txt("Optional. Change it any time.", 10f, V2.faint) }
    }
}

@Composable
fun HomeChooseSheet(t: Float, p: Float = 1f) {
    Stage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        Backdrop(Mood.Idle, t, 0.2f, 0.4f)
        TopLabel("WATCH HOME", V2.iris, 10.5f, FontWeight.Bold, 2.2f)
        Orb(cx, H * 0.2f, g.d * 0.075f, Mood.Idle, t)
        val e = easeOut(p)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f * e)))
        val top = H * (1f - 0.76f * e); val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        Box(Modifier.offset(0.dp, top).size(W, H * 0.78f).clip(shape).background(Brush.verticalGradient(listOf(Color(0xFF26242F), Color(0xFF15141B)))).border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.25f), Color.Transparent)), shape)) {
            Box(Modifier.offset(W / 2 - g.dp(14f), g.dp(8f)).size(g.dp(28f), g.dp(4f)).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.3f)))
            val hw = g.chord(top + g.dp(40f), 10.dp).coerceAtMost(g.dp(96f))
            Box(Modifier.offset(W / 2 - hw, g.dp(18f)).width(hw * 2), contentAlignment = Alignment.Center) { Txt("Use Agent as your watch home?", 14f, V2.text, FontWeight.SemiBold, lh = 17f) }
            Box(Modifier.offset(W / 2 - hw, g.dp(54f)).width(hw * 2), contentAlignment = Alignment.Center) { Txt("Apps, tiles and notifications work as usual. Switch back in one tap.", 10.5f, V2.dim, lh = 13f) }
            val bw = g.chord(top + g.dp(92f + 34f), 6.dp).coerceAtMost(g.dp(92f))
            Box(Modifier.offset(W / 2 - bw, g.dp(90f)).size(bw * 2, g.dp(32f)).clip(RoundedCornerShape(50)).background(Brush.verticalGradient(listOf(V2.irisHi, V2.iris))), contentAlignment = Alignment.Center) { Txt("Use Agent", 13f, V2.ink, FontWeight.SemiBold) }
            Box(Modifier.offset(W / 2 - hw, g.dp(126f)).size(hw * 2, g.dp(24f)), contentAlignment = Alignment.Center) { Txt("Not now", 12f, V2.dim, FontWeight.Medium) }
        }
    }
}

@Composable
fun SwitchBackScreen(t: Float, sheet: Float = 0f) {
    Stage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        Backdrop(Mood.Idle, t, 0.2f, 0.4f)
        TopLabel("SETTINGS", V2.iris, 10.5f, FontWeight.Bold, 2.2f)
        val hw = g.chord(H * 0.5f, 14.dp).coerceAtMost(g.dp(100f))
        val rows = listOf(Triple("Gestures", "4 set", Ic.history), Triple("Audio", "Watch speaker", Ic.volume))
        for ((i, r) in rows.withIndex()) {
            val y = H * 0.17f + g.dp(i * 44f)
            Box(Modifier.offset(cx - hw, y).size(hw * 2, g.dp(38f)).glass(RoundedCornerShape(50))) {
                IconBox(r.third, g.dp(16f), V2.iris, Modifier.offset(g.dp(14f), g.dp(11f)))
                Box(Modifier.offset(g.dp(38f), g.dp(11f))) { Txt(r.first, 12.5f, V2.text, FontWeight.Medium, align = TextAlign.Start) }
                Box(Modifier.offset(hw * 2 - g.dp(100f), g.dp(12f)).width(g.dp(78f)), contentAlignment = Alignment.CenterEnd) { Txt(r.second, 10f, V2.faint, align = TextAlign.End, maxLines = 1) }
            }
        }
        val y = H * 0.17f + g.dp(88f)
        Box(Modifier.offset(cx - hw, y).size(hw * 2, g.dp(54f)).clip(RoundedCornerShape(26.dp)).background(Brush.horizontalGradient(listOf(V2.irisDeep.copy(alpha = 0.85f), V2.irisDeep.copy(alpha = 0.3f)))).border(1.dp, V2.iris.copy(alpha = 0.6f), RoundedCornerShape(26.dp))) {
            Orb(g.dp(28f), g.dp(27f), g.dp(10f), Mood.Idle, t, glow = 0.3f)
            Box(Modifier.offset(g.dp(48f), g.dp(8f))) { androidx.compose.foundation.layout.Column(Modifier.width(hw * 2 - g.dp(58f))) {
                Txt("Switch back to the standard watch home", 11.5f, V2.irisHi, FontWeight.SemiBold, align = TextAlign.Start, lh = 13.5f, maxLines = 2)
            } }
        }
        if (sheet > 0f) {
            val e = easeOut(sheet)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f * e)))
            val top = H * (1f - 0.76f * e); val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            Box(Modifier.offset(0.dp, top).size(W, H * 0.78f).clip(shape).background(Brush.verticalGradient(listOf(Color(0xFF26242F), Color(0xFF15141B)))).border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.25f), Color.Transparent)), shape)) {
                Box(Modifier.offset(W / 2 - g.dp(14f), g.dp(8f)).size(g.dp(28f), g.dp(4f)).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.3f)))
                val h2 = g.chord(top + g.dp(40f), 10.dp).coerceAtMost(g.dp(96f))
                Box(Modifier.offset(W / 2 - h2, g.dp(18f)).width(h2 * 2), contentAlignment = Alignment.Center) { Txt("Back to the standard home?", 14f, V2.text, FontWeight.SemiBold, lh = 17f) }
                Box(Modifier.offset(W / 2 - h2, g.dp(54f)).width(h2 * 2), contentAlignment = Alignment.Center) { Txt("Agent stays on your watch as a normal app.", 10.5f, V2.dim, lh = 13f) }
                val bw = g.chord(top + g.dp(92f + 34f), 6.dp).coerceAtMost(g.dp(92f))
                Box(Modifier.offset(W / 2 - bw, g.dp(90f)).size(bw * 2, g.dp(32f)).clip(RoundedCornerShape(50)).background(Brush.verticalGradient(listOf(V2.irisHi, V2.iris))), contentAlignment = Alignment.Center) { Txt("Switch back", 13f, V2.ink, FontWeight.SemiBold) }
                Box(Modifier.offset(W / 2 - h2, g.dp(126f)).size(h2 * 2, g.dp(24f)), contentAlignment = Alignment.Center) { Txt("Keep Agent home", 12f, V2.dim, FontWeight.Medium) }
            }
        }
    }
}

@Composable
fun SwitchedBackDone(t: Float) {
    Stage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        Backdrop(Mood.Idle, t, 0.4f, 0.5f)
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height * 0.36f); val r = g.d.toPx() * 0.15f
            drawCircle(Brush.radialGradient(0f to V2.iris.copy(alpha = 0.35f), 1f to Color.Transparent, center = c, radius = r * 2.4f), r * 2.4f, c)
            drawCircle(Brush.verticalGradient(listOf(V2.irisHi, V2.iris), c.y - r, c.y + r), r, c)
            icon(Ic.check, c, r * 1.1f, V2.ink)
        }
        Box(Modifier.offset(cx - g.dp(90f), H * 0.58f).width(g.dp(180f)), contentAlignment = Alignment.Center) { Txt("Standard home is back", 15f, V2.text, FontWeight.SemiBold, lh = 18f) }
        Box(Modifier.offset(cx - g.dp(86f), H * 0.725f).width(g.dp(172f)), contentAlignment = Alignment.Center) { Txt("Open Agent from your apps, or choose it again in settings.", 10.5f, V2.dim, lh = 13.5f) }
    }
}

// ───────── 11. Watch faces ─────────

/** 0 = Still (minimal), 1 = Orb (the agent is the dial), 2 = Data (rich). [ambient] is the low-power look. */
@Composable
fun WatchFace(style: Int, t: Float, ambient: Boolean = false) {
    Stage { g ->
        val W = g.w; val H = g.h; val cx = W / 2; val cy = H / 2
        val m = if (ambient) Mood.Ambient else Mood.Idle
        val ink = if (ambient) V2.ambientInk else V2.text
        val colon = if (ambient) 1f else 0.55f + 0.45f * (0.5f + 0.5f * sin(t * PI.toFloat()))
        if (!ambient) Backdrop(if (style == 1) Mood.Idle else Mood.Idle, t, if (style == 1) 0.58f else 0.5f, if (style == 0) 0.5f else 0.9f, bezel = style != 0)
        when (style) {
            0 -> {
                TopLabel("THU  12  NOV", if (ambient) V2.ambientInk else V2.iris, 10.5f, FontWeight.Bold, 2.4f, y = 14f)
                Box(Modifier.offset(cx - g.dp(110f), H * 0.255f).width(g.dp(220f)), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt("14", 78f, ink, if (ambient) FontWeight.Normal else FontWeight.Thin, -2f)
                        Box(Modifier.alpha(colon)) { Txt(":", 70f, if (ambient) ink else V2.iris, FontWeight.Thin) }
                        Txt("20", 78f, ink, if (ambient) FontWeight.Normal else FontWeight.Thin, -2f)
                    }
                }
                Orb(cx, H * 0.655f, g.d * 0.07f, m, t, glow = 0.8f)
                Box(Modifier.offset(cx - g.dp(80f), H * 0.72f).width(g.dp(160f)), contentAlignment = Alignment.Center) { Txt(if (ambient) "Nova" else "Nova  ·  ready", 10.5f, if (ambient) V2.ambientInk.copy(alpha = 0.7f) else V2.dim, FontWeight.Medium, 0.6f) }
                if (!ambient) Chip("15:00  Design review", null, V2.iris, cx, H * 0.885f, g.dp(138f), g.dp(24f), 10.5f)
                else Box(Modifier.offset(cx - g.dp(80f), H * 0.855f).width(g.dp(160f)), contentAlignment = Alignment.Center) { Txt("15:00  Design review", 10.5f, V2.ambientInk.copy(alpha = 0.6f)) }
            }
            1 -> {
                Box(Modifier.offset(cx - g.dp(100f), H * 0.045f).width(g.dp(200f)), contentAlignment = Alignment.Center) { Txt("14:20", 48f, ink, if (ambient) FontWeight.Normal else FontWeight.Light, -1f, brush = if (ambient) null else Brush.verticalGradient(listOf(Color.White, V2.irisHi.copy(alpha = 0.8f)))) }
                Box(Modifier.offset(cx - g.dp(70f), H * 0.30f).width(g.dp(140f)), contentAlignment = Alignment.Center) { Txt("THU 12", 9.5f, if (ambient) V2.ambientInk else V2.iris, FontWeight.Bold, 2.2f) }
                Orb(cx, H * 0.635f, g.d * (if (ambient) 0.2f else 0.24f), m, t)
                if (!ambient) {
                    // seconds: a comet of light running the bezel
                    Canvas(Modifier.fillMaxSize()) {
                        val rad = min(size.width, size.height) / 2 - 3.dp.toPx(); val c = Offset(size.width / 2, size.height / 2)
                        val head = (t * 6f) % 360f - 90f
                        rotate(head, pivot = c) {
                            drawArc(Brush.sweepGradient(0f to Color.Transparent, 0.82f to Color.Transparent, 1f to V2.irisHi, center = c), -40f, 40f, false, Offset(c.x - rad, c.y - rad), Size(rad * 2, rad * 2), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                        }
                    }
                    // pending badge on the agent's shoulder
                    Box(Modifier.at(cx + g.d * 0.20f, H * 0.635f - g.d * 0.19f, g.dp(22f), g.dp(22f)).clip(CircleShape).background(Brush.verticalGradient(listOf(V2.amberHi, V2.amber))).border(1.5.dp, Color.Black, CircleShape), contentAlignment = Alignment.Center) { Txt("2", 12f, V2.inkAmber, FontWeight.Bold) }
                } else {
                    Box(Modifier.offset(cx - g.dp(60f), H * 0.90f).width(g.dp(120f)), contentAlignment = Alignment.Center) { Txt("2 need you", 10f, V2.ambientInk.copy(alpha = 0.8f)) }
                }
            }
            else -> {
                TopLabel("THU 12 NOV", if (ambient) V2.ambientInk else V2.iris, 10f, FontWeight.Bold, 2.2f, y = 12f)
                Box(Modifier.offset(cx - g.dp(100f), H * 0.125f).width(g.dp(200f)), contentAlignment = Alignment.Center) { Txt("14:20", 48f, ink, if (ambient) FontWeight.Normal else FontWeight.Light, -1f) }
                Orb(cx, H * 0.515f, g.d * 0.105f, m, t, glow = 0.9f)
                val s = g.d * 0.185f
                Canvas(Modifier.fillMaxSize()) {
                    val lc = Offset((cx - g.d * 0.295f).toPx(), (H * 0.515f).toPx()); val rc = Offset((cx + g.d * 0.295f).toPx(), (H * 0.515f).toPx()); val r = s.toPx() / 2
                    val lineA = if (ambient) 0.5f else 1f
                    drawCircle(Color.White.copy(alpha = if (ambient) 0f else 0.06f), r, lc)
                    drawArc(Color.White.copy(alpha = 0.14f * lineA), 0f, 360f, false, Offset(lc.x - r + 3 * density, lc.y - r + 3 * density), Size((r - 3 * density) * 2, (r - 3 * density) * 2), style = Stroke(3 * density))
                    drawArc(if (ambient) V2.ambientInk else V2.iris, -90f, 216f, false, Offset(lc.x - r + 3 * density, lc.y - r + 3 * density), Size((r - 3 * density) * 2, (r - 3 * density) * 2), style = Stroke(if (ambient) 1.5f * density else 3 * density, cap = StrokeCap.Round))
                    drawCircle(Color.White.copy(alpha = if (ambient) 0f else 0.06f), r, rc)
                    drawCircle(if (ambient) V2.ambientInk else V2.amber, r - 1.5f * density, rc, style = Stroke(if (ambient) 1.5f * density else 2.2f * density))
                }
                Box(Modifier.at(cx - g.d * 0.295f, H * 0.515f, s, s), contentAlignment = Alignment.Center) { Txt("3/5", 11.5f, ink, FontWeight.SemiBold) }
                Box(Modifier.at(cx + g.d * 0.295f, H * 0.515f, s, s), contentAlignment = Alignment.Center) { Txt("2", 17f, if (ambient) V2.ambientInk else V2.amberHi, FontWeight.SemiBold) }
                Box(Modifier.at(cx - g.d * 0.295f, H * 0.515f + s * 0.62f, s, g.dp(12f)), contentAlignment = Alignment.Center) { Txt("Tasks", 8f, V2.faint) }
                Box(Modifier.at(cx + g.d * 0.295f, H * 0.515f + s * 0.62f, s, g.dp(12f)), contentAlignment = Alignment.Center) { Txt("Needs you", 8f, V2.faint) }
                val hw = g.dp(78f)
                if (!ambient) Box(Modifier.at(cx, H * 0.845f, hw * 2, g.dp(34f)).glass(RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) { Txt("NEXT TASK", 7.5f, V2.iris, FontWeight.Bold, 1.4f); Txt("15:00  Design review", 11.5f, V2.text, FontWeight.SemiBold) }
                } else Box(Modifier.at(cx, H * 0.845f, hw * 2, g.dp(34f)), contentAlignment = Alignment.Center) { Txt("15:00  Design review", 11f, V2.ambientInk.copy(alpha = 0.75f)) }
            }
        }
    }
}

/** The nine mascots the phone offers, at rest and working, so the watch can be seen to follow the user's choice. */
@Composable
fun MascotStrip(t: Float) {
    Stage { g ->
        val cx = g.w / 2
        Backdrop(Mood.Idle, t, 0.5f, 0.4f)
        TopLabel("YOUR AGENT'S FACE", V2.iris, 9.5f, FontWeight.Bold, 2f)
        val ks = MascotKind.values()
        for ((i, k) in ks.withIndex()) {
            val col = i % 3; val row = i / 3
            val x = cx + g.d * (col - 1) * 0.27f; val y = g.h / 2 + g.d * (row - 1) * 0.25f
            Orb(x, y, g.d * 0.085f, if (row == 1) Mood.Thinking else Mood.Idle, t + 0.3f * i, 0f, k, glow = 0.3f)
        }
    }
}

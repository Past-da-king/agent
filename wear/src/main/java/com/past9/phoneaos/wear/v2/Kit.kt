package com.past9.phoneaos.wear.v2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.MaterialShapes
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import com.past9.phoneaos.wear.ui.WatchTokens
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Watch v2 design kit: the Agent's own look on a wrist. Everything is a function of time `t` (seconds) so a still, a clip
 * frame and the live screen are the same code: live screens pass [rememberT], previews pass a fixed t.
 *
 * Identity, taken from the phone app: the iris accent, amber that means only "this needs you", the user's mascot (a
 * Material-expressive shape that breathes into its busy shape, with two pill eyes), the phone's corner steps.
 */
object V2 {
    val iris = WatchTokens.iris; val irisHi = Color(0xFFE7E3FF); val irisLo = Color(0xFF6A5CF0); val irisDeep = Color(0xFF3A2FB0)
    val amber = WatchTokens.amber; val amberHi = Color(0xFFFFDDB8); val amberLo = Color(0xFFC26A00); val amberDeep = Color(0xFF6A3D00)
    val ink = WatchTokens.onIris; val inkAmber = WatchTokens.onAmber
    val text = WatchTokens.onSurface; val dim = WatchTokens.onSurfaceVariant; val faint = WatchTokens.outline
    val endRed = Color(0xFFE5534B); val endRedLo = Color(0xFF9C2B24)
    val ambientInk = Color(0xFFB9B5C6)
}

@Immutable
class Geo(val w: Dp, val h: Dp, val round: Boolean) {
    val d: Dp = if (w < h) w else h
    val k: Float = (d.value / 227f).coerceIn(0.82f, 1.15f)
    fun sp(v: Float): TextUnit = (v * k).sp
    fun dp(v: Float): Dp = (v * k).dp
    /** Usable half-width at a vertical position (0..h): the glass narrows toward the top and bottom of a round watch. */
    fun chord(y: Dp, inset: Dp = 8.dp): Dp {
        if (!round) return w / 2 - inset
        val r = d.value / 2f; val dy = y.value - h.value / 2f
        return (sqrt(max(0f, r * r - dy * dy)) - inset.value).dp
    }
}

val LocalGeo = staticCompositionLocalOf<Geo> { error("Stage missing") }

@Composable
fun Stage(modifier: Modifier = Modifier, content: @Composable BoxScope.(Geo) -> Unit) {
    val round = LocalConfiguration.current.isScreenRound
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val g = Geo(maxWidth, maxHeight, round)
        CompositionLocalProvider(LocalGeo provides g) { Box(Modifier.fillMaxSize()) { content(g) } }
    }
}

/** Time for a live screen. Previews and clips pass their own. */
@Composable
fun rememberT(): Float {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { val s = withFrameNanos { it }; while (true) withFrameNanos { t = (it - s) / 1e9f } }
    return t
}

fun clamp01(x: Float) = x.coerceIn(0f, 1f)
fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
fun smooth(x: Float): Float { val c = clamp01(x); return c * c * (3 - 2 * c) }
fun easeOut(x: Float): Float { val c = clamp01(x); return 1f - (1f - c) * (1f - c) * (1f - c) }
fun lerpC(a: Color, b: Color, f: Float) = Color(lerp(a.red, b.red, f), lerp(a.green, b.green, f), lerp(a.blue, b.blue, f), lerp(a.alpha, b.alpha, f))

// ───────── text ─────────

@Composable
fun Txt(
    s: String, size: Float, color: Color = V2.text, weight: FontWeight = FontWeight.Normal, track: Float = 0f,
    align: TextAlign = TextAlign.Center, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, lh: Float = 0f, brush: Brush? = null,
) {
    val g = LocalGeo.current
    val lhs = if (lh > 0f) g.sp(lh) else TextUnit.Unspecified
    val st = if (brush != null) TextStyle(brush = brush, fontSize = g.sp(size), fontWeight = weight, letterSpacing = track.sp, textAlign = align, lineHeight = lhs, fontFeatureSettings = "tnum")
    else TextStyle(color = color, fontSize = g.sp(size), fontWeight = weight, letterSpacing = track.sp, textAlign = align, lineHeight = lhs, fontFeatureSettings = "tnum")
    BasicText(s, modifier, style = st, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
fun TxtA(s: AnnotatedString, size: Float, color: Color = V2.text, lh: Float = 0f, align: TextAlign = TextAlign.Start, modifier: Modifier = Modifier) {
    val g = LocalGeo.current
    BasicText(s, modifier, style = TextStyle(color = color, fontSize = g.sp(size), lineHeight = if (lh > 0f) g.sp(lh) else TextUnit.Unspecified, textAlign = align))
}

/** Text laid along the top bezel, one letter at a time, centred on [centerDeg] (0 = 12 o'clock). */
fun DrawScope.arcText(tm: TextMeasurer, text: String, style: TextStyle, radius: Float, centerDeg: Float = 0f, track: Float = 0f) {
    val layouts = text.map { tm.measure(it.toString(), style) }
    val widths = layouts.map { it.size.width.toFloat() + track }
    val total = widths.sum() / radius
    var a = -total / 2f
    layouts.forEachIndexed { i, l ->
        val mid = a + widths[i] / radius / 2f
        val deg = centerDeg + mid * 180f / PI.toFloat()
        rotate(deg, pivot = center) { drawText(l, topLeft = Offset(center.x - l.size.width / 2f, center.y - radius)) }
        a += widths[i] / radius
    }
}

// ───────── glass, icons ─────────

fun Modifier.glass(shape: Shape, tint: Color = Color.White, strength: Float = 1f): Modifier =
    this.clip(shape)
        .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.16f * strength), tint.copy(alpha = 0.05f * strength))))
        .border(1.dp, Brush.verticalGradient(listOf(tint.copy(alpha = 0.34f * min(1f, strength + 0.2f)), tint.copy(alpha = 0.03f))), shape)

object Ic {
    const val call = "M6.62,10.79c1.44,2.83 3.76,5.14 6.59,6.59l2.2,-2.2c0.27,-0.27 0.67,-0.36 1.02,-0.24 1.12,0.37 2.33,0.57 3.57,0.57 0.55,0 1,0.45 1,1V20c0,0.55 -0.45,1 -1,1 -9.39,0 -17,-7.61 -17,-17 0,-0.55 0.45,-1 1,-1h3.5c0.55,0 1,0.45 1,1 0,1.25 0.2,2.45 0.57,3.57 0.11,0.35 0.03,0.74 -0.25,1.02l-2.2,2.2z"
    const val mic = "M12,14c1.66,0 3,-1.34 3,-3V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z"
    const val keyboard = "M20,5H4c-1.1,0 -1.99,0.9 -1.99,2L2,17c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2zM11,8h2v2h-2V8zM11,11h2v2h-2v-2zM8,8h2v2H8V8zM8,11h2v2H8v-2zM7,13H5v-2h2v2zM7,10H5V8h2v2zM16,17H8v-2h8v2zM16,13h-2v-2h2v2zM16,10h-2V8h2v2zM19,13h-2v-2h2v2zM19,10h-2V8h2v2z"
    const val check = "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z"
    const val close = "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z"
    const val shield = "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12V5l-9,-4z"
    const val bell = "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z"
    const val apps = "M4,8h4V4H4v4zM10,20h4v-4h-4v4zM4,20h4v-4H4v4zM4,14h4v-4H4v4zM10,14h4v-4h-4v4zM16,4v4h4V4h-4zM10,8h4V4h-4v4zM16,14h4v-4h-4v4zM16,20h4v-4h-4v4z"
    const val history = "M13,3c-4.97,0 -9,4.03 -9,9H1l3.89,3.89 0.07,0.14L9,12H6c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08V8H12z"
    const val headphones = "M12,3c-4.97,0 -9,4.03 -9,9v7c0,1.1 0.9,2 2,2h4v-8H5v-1c0,-3.87 3.13,-7 7,-7s7,3.13 7,7v1h-4v8h4c1.1,0 2,-0.9 2,-2v-7c0,-4.97 -4.03,-9 -9,-9z"
    const val volume = "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z"
    const val chevron = "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z"
    const val chat = "M20,2H4c-1.1,0 -2,0.9 -2,2v18l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2z"
    const val pin = "M12,2C8.13,2 5,5.13 5,9c0,5.25 7,13 7,13s7,-7.75 7,-13c0,-3.87 -3.13,-7 -7,-7zM12,11.5c-1.38,0 -2.5,-1.12 -2.5,-2.5s1.12,-2.5 2.5,-2.5 2.5,1.12 2.5,2.5 -1.12,2.5 -2.5,2.5z"
    const val music = "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z"
    const val heart = "M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z"
    const val lock = "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM15.1,8H8.9V6c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2z"
    const val back = "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z"
    const val star = "M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z"
    const val sun = "M6.76,4.84l-1.8,-1.79 -1.41,1.41 1.79,1.79 1.42,-1.41zM4,10.5H1v2h3v-2zM13,0.55h-2V3.5h2V0.55zM20.45,4.46l-1.41,-1.41 -1.79,1.8 1.41,1.41 1.79,-1.8zM17.24,18.16l1.79,1.8 1.41,-1.41 -1.8,-1.79 -1.4,1.4zM20,10.5v2h3v-2h-3zM12,5.5c-3.31,0 -6,2.69 -6,6s2.69,6 6,6 6,-2.69 6,-6 -2.69,-6 -6,-6zM11,22.45h2V19.5h-2v2.95zM3.55,18.54l1.41,1.41 1.79,-1.8 -1.41,-1.41 -1.79,1.8z"
}

fun DrawScope.icon(d: String, c: Offset, s: Float, color: Color, rot: Float = 0f) {
    val p = PathParser().parsePathString(d).toPath()
    translate(c.x - s / 2f, c.y - s / 2f) {
        scale(s / 24f, s / 24f, pivot = Offset.Zero) {
            if (rot != 0f) rotate(rot, pivot = Offset(12f, 12f)) { drawPath(p, color) } else drawPath(p, color)
        }
    }
}

@Composable
fun IconBox(d: String, size: Dp, color: Color, modifier: Modifier = Modifier, rot: Float = 0f) {
    Canvas(modifier.size(size)) { icon(d, Offset(this.size.width / 2, this.size.height / 2), this.size.width, color, rot) }
}

// ───────── the mascot orb ─────────

enum class Mood { Idle, Listening, Thinking, Speaking, Needs, Call, Muted, Asleep, Ambient }

/**
 * The agent's mascot, the SAME one the phone and the desktop draw (phone: ui/Components.kt Mascots + AgentAvatar, desktop:
 * ui/Mascot.tsx). A flat iris shape with two pill eyes; at rest it sits still, while the agent works it breathes into its busy
 * shape (and turns, if the shape is radially symmetric, else it bobs). The shapes are Material's own [MaterialShapes], the
 * same polygons the phone uses; the colours are the phone's primary (iris) and tertiary (amber, "needs you").
 */
enum class MascotKind(val id: String, val label: String, val spins: Boolean) {
    Scout("scout", "Scout", true), Ghost("ghost", "Ghost", false), Clover("clover", "Clover", true), Bloom("bloom", "Bloom", true),
    Puff("puff", "Puff", false), Gem("gem", "Gem", false), Pebble("pebble", "Pebble", false), Heart("heart", "Heart", false), Bun("bun", "Bun", false);
    companion object { fun of(id: String?) = values().firstOrNull { it.id == id } ?: Scout }
}

private fun restOf(k: MascotKind): RoundedPolygon = when (k) {
    MascotKind.Scout -> MaterialShapes.Cookie9Sided; MascotKind.Ghost -> MaterialShapes.Ghostish; MascotKind.Clover -> MaterialShapes.Clover4Leaf
    MascotKind.Bloom -> MaterialShapes.Flower; MascotKind.Puff -> MaterialShapes.Puffy; MascotKind.Gem -> MaterialShapes.Gem
    MascotKind.Pebble -> MaterialShapes.Pill; MascotKind.Heart -> MaterialShapes.Heart; MascotKind.Bun -> MaterialShapes.Bun
}
private fun busyOf(k: MascotKind): RoundedPolygon = when (k) {
    MascotKind.Scout -> MaterialShapes.SoftBurst; MascotKind.Ghost -> MaterialShapes.Ghostish; MascotKind.Clover -> MaterialShapes.Clover8Leaf
    MascotKind.Bloom -> MaterialShapes.Sunny; MascotKind.Puff -> MaterialShapes.Cookie12Sided; MascotKind.Gem -> MaterialShapes.PuffyDiamond
    MascotKind.Pebble -> MaterialShapes.Oval; MascotKind.Heart -> MaterialShapes.Heart; MascotKind.Bun -> MaterialShapes.Arch
}

private val morphCache = HashMap<MascotKind, Morph>()
private fun morphOf(k: MascotKind) = morphCache.getOrPut(k) { Morph(restOf(k).normalized(), busyOf(k).normalized()) }

/** The mascot's outline at [progress] (0 rest, 1 busy), [size] px wide, centred on (cx, cy). */
fun mascotPath(k: MascotKind, progress: Float, cx: Float, cy: Float, size: Float): Path {
    val cubics = morphOf(k).asCubics(progress.coerceIn(0f, 1f))
    val p = Path()
    fun x(u: Float) = cx + (u - 0.5f) * size
    fun y(u: Float) = cy + (u - 0.5f) * size
    p.moveTo(x(cubics[0].anchor0X), y(cubics[0].anchor0Y))
    for (c in cubics) p.cubicTo(x(c.control0X), y(c.control0Y), x(c.control1X), y(c.control1Y), x(c.anchor1X), y(c.anchor1Y))
    p.close()
    return p
}

private fun hash(i: Int, t: Float): Float = abs(sin(i * 0.93f + t * 7.1f) * cos(i * 0.37f - t * 3.3f))

/** The phone's breathe: a 1.4 s tween that reverses, eased. */
fun breathe(t: Float): Float { val ph = (t / 1.4f) % 2f; return androidx.compose.animation.core.FastOutSlowInEasing.transform(if (ph < 1f) ph else 2f - ph) }

/**
 * Draws the agent at [c], radius [r] (the body is about 2r wide), exactly as the phone's AgentAvatar does: flat fill, rest
 * shape still, busy shape breathing and turning. Moods map onto the phone's own states: Idle is rest; Thinking, Listening,
 * Speaking and Call are "working" (the voice level adds a little scale); Needs is the tertiary (amber) colour. Around it,
 * only light: a soft halo, listening rings, a speaking ring of bars, a thinking comet.
 */
fun DrawScope.orb(c: Offset, r: Float, mood: Mood, t: Float, level: Float = 0f, kind: MascotKind = MascotKind.Scout, glow: Float = 1f, dpx: Float = 1f) {
    val needs = mood == Mood.Needs
    val muted = mood == Mood.Muted || mood == Mood.Asleep
    val fill = when { needs -> V2.amber; muted -> V2.faint; else -> V2.iris }
    val eye = if (needs) V2.inkAmber else V2.ink
    val working = when (mood) { Mood.Listening, Mood.Thinking, Mood.Speaking, Mood.Call -> 1f; else -> 0f }
    val br = breathe(t)
    val size = r * 2f
    val spin = if (working > 0f && kind.spins) (t / 9f * 360f) % 360f * working else 0f
    val sc = 1f + 0.04f * br * working + (if (mood == Mood.Listening || mood == Mood.Speaking || mood == Mood.Call) 0.06f * level else 0f)
    val bobY = if (working > 0f && !kind.spins) -size * 0.05f * br * working else 0f
    val progress = (0.15f + 0.85f * br) * working

    if (mood != Mood.Ambient) {
        val ga = glow * (if (muted) 0.16f else 0.55f + 0.35f * level + (if (working > 0f) 0.15f else 0f))
        drawCircle(Brush.radialGradient(0.0f to fill.copy(alpha = 0.40f * ga), 0.45f to fill.copy(alpha = 0.14f * ga), 1f to Color.Transparent, center = c, radius = r * 2.4f), r * 2.4f, c)
        if (mood == Mood.Listening || mood == Mood.Call) for (k in 0 until 3) {
            val ph = ((t * 0.65f + k / 3f) % 1f)
            drawCircle(fill.copy(alpha = (1f - ph) * (0.14f + 0.45f * level)), r * (1.12f + ph * 0.75f), c, style = Stroke(1.5f * dpx))
        }
        if (mood == Mood.Speaking || mood == Mood.Call) {
            val n = 56
            for (i in 0 until n) {
                val a = i * 2f * PI.toFloat() / n
                val len = r * (0.04f + 0.26f * level * (0.25f + hash(i, t)))
                val r0 = r * 1.2f
                val p0 = Offset(c.x + cos(a) * r0, c.y + sin(a) * r0); val p1 = Offset(c.x + cos(a) * (r0 + len), c.y + sin(a) * (r0 + len))
                drawLine(fill.copy(alpha = 0.85f), p0, p1, strokeWidth = 2.2f * dpx, cap = StrokeCap.Round)
            }
        }
        if (mood == Mood.Thinking) for (k in 0 until 3) {
            val base = t * 2.4f + k * 2f * PI.toFloat() / 3f
            for (j in 0 until 7) {
                val a = base - j * 0.11f; val orbitR = r * (1.34f + 0.06f * sin(t * 2f + k))
                drawCircle(fill.copy(alpha = (1f - j / 7f) * 0.9f), r * (0.075f - j * 0.007f), Offset(c.x + cos(a) * orbitR, c.y + sin(a) * orbitR))
            }
        }
    }

    val path = mascotPath(kind, progress, c.x, c.y + bobY, size)
    if (mood == Mood.Ambient) {
        rotate(spin, pivot = c) { drawPath(path, V2.ambientInk, style = Stroke(1.6f * dpx)) }
    } else {
        withTransform({ rotate(spin, pivot = c); scale(sc, sc, pivot = c) }) { drawPath(path, fill) }
    }
    // the eyes: the phone's two pills, 0.09 x 0.15 of the body, 0.14 apart, upright while the body turns
    val ew = size * 0.09f; val eh = size * 0.15f; val gap = size * 0.14f
    val ey = c.y + bobY
    for (s in listOf(-1f, 1f)) {
        val ex = c.x + s * (gap + ew) / 2f
        drawRoundRect(if (mood == Mood.Ambient) V2.ambientInk.copy(alpha = 0.85f) else eye, Offset(ex - ew / 2f, ey - eh / 2f), Size(ew, eh), CornerRadius(ew / 2f))
    }
}

@Composable
fun Orb(cx: Dp, cy: Dp, r: Dp, mood: Mood, t: Float, level: Float = 0f, kind: MascotKind = MascotKind.Scout, glow: Float = 1f) {
    // Drawn on the whole stage so the halo is never clipped and the centre is exactly where the layout says.
    Canvas(Modifier.fillMaxSize()) { orb(Offset(cx.toPx(), cy.toPx()), r.toPx(), mood, t, level, kind, glow, 1.dp.toPx()) }
}

// ───────── stage dressing ─────────

/** The black stage with a breath of colour behind the agent, a bezel ring of light, and a few fixed motes of depth. */
@Composable
fun Backdrop(mood: Mood, t: Float, focusY: Float = 0.45f, strength: Float = 1f, bezel: Boolean = true) {
    val g = LocalGeo.current
    Canvas(Modifier.fillMaxSize()) {
        if (mood == Mood.Ambient) return@Canvas
        val needs = mood == Mood.Needs
        val col = if (needs) V2.amber else V2.iris
        val cx = size.width / 2; val cy = size.height * focusY
        val d = min(size.width, size.height)
        drawRect(Brush.radialGradient(0f to col.copy(alpha = 0.26f * strength), 0.55f to col.copy(alpha = 0.07f * strength), 1f to Color.Transparent, center = Offset(cx, cy), radius = d * 0.95f))
        drawRect(Brush.radialGradient(0f to (if (needs) V2.amberLo else V2.irisDeep).copy(alpha = 0.22f * strength), 1f to Color.Transparent, center = Offset(size.width * 0.5f, size.height * 1.05f), radius = d * 0.7f))
        // motes
        for (i in 0 until 26) {
            val a = (i * 2.399963f); val rr = sqrt((i + 0.5f) / 26f) * d * 0.48f
            val px = size.width / 2 + cos(a) * rr; val py = size.height / 2 + sin(a) * rr
            val tw = 0.5f + 0.5f * sin(t * (0.6f + (i % 5) * 0.17f) + i)
            drawCircle(Color.White.copy(alpha = 0.06f + 0.10f * tw), (0.6f + (i % 3) * 0.4f) * density, Offset(px, py))
        }
        if (bezel) {
            val sw = 1.5f * density
            if (g.round) {
                val rad = d / 2 - sw
                drawCircle(Brush.sweepGradient(listOf(col.copy(alpha = 0.55f * strength), col.copy(alpha = 0.04f), col.copy(alpha = 0.30f * strength), col.copy(alpha = 0.04f), col.copy(alpha = 0.55f * strength)), Offset(size.width / 2, size.height / 2)), rad, Offset(size.width / 2, size.height / 2), style = Stroke(sw))
            } else {
                drawRoundRect(Brush.sweepGradient(listOf(col.copy(alpha = 0.5f * strength), col.copy(alpha = 0.04f), col.copy(alpha = 0.3f * strength), col.copy(alpha = 0.04f), col.copy(alpha = 0.5f * strength)), Offset(size.width / 2, size.height / 2)), Offset(sw, sw), Size(size.width - 2 * sw, size.height - 2 * sw), CornerRadius(26.dp.toPx()), style = Stroke(sw))
            }
        }
    }
}

/** Where the crown has scrolled to: a slim arc on the right edge of a round watch, a bar on a square one. */
@Composable
fun CrownRail(pos: Float, span: Float = 0.25f, tint: Color = V2.iris, pages: Int = 0, page: Int = 0) {
    val g = LocalGeo.current
    Canvas(Modifier.fillMaxSize()) {
        val sw = 3.dp.toPx(); val inset = 5.dp.toPx()
        if (g.round) {
            val rad = min(size.width, size.height) / 2 - inset - sw / 2
            val total = 44f; val start = -total / 2
            drawArc(Color.White.copy(alpha = 0.10f), start, total, false, Offset(size.width / 2 - rad, size.height / 2 - rad), Size(rad * 2, rad * 2), style = Stroke(sw, cap = StrokeCap.Round))
            val thumb = total * span; val s0 = start + (total - thumb) * clamp01(pos)
            drawArc(tint.copy(alpha = 0.9f), s0, thumb, false, Offset(size.width / 2 - rad, size.height / 2 - rad), Size(rad * 2, rad * 2), style = Stroke(sw, cap = StrokeCap.Round))
        } else {
            val x = size.width - inset - sw / 2; val h = size.height * 0.4f; val y0 = size.height * 0.3f
            drawLine(Color.White.copy(alpha = 0.10f), Offset(x, y0), Offset(x, y0 + h), sw, StrokeCap.Round)
            val th = h * span; val ty = y0 + (h - th) * clamp01(pos)
            drawLine(tint.copy(alpha = 0.9f), Offset(x, ty), Offset(x, ty + th), sw, StrokeCap.Round)
        }
    }
}

/** A glass round button with an icon. */
@Composable
fun Sat(icon: String, size: Dp, cx: Dp, cy: Dp, tint: Color = V2.iris, filled: Boolean = false, fillTint: Color = V2.iris, glyph: Color? = null, alpha: Float = 1f) {
    val shape = androidx.compose.foundation.shape.CircleShape
    Box(
        Modifier.offset(cx - size / 2, cy - size / 2).size(size).let {
            if (filled) it.clip(shape).background(Brush.verticalGradient(listOf(lerpC(fillTint, Color.White, 0.25f), fillTint, lerpC(fillTint, Color.Black, 0.25f)))).border(1.dp, Color.White.copy(alpha = 0.35f), shape)
            else it.glass(shape, tint, 1f)
        },
        contentAlignment = Alignment.Center,
    ) { IconBox(icon, size * 0.46f, glyph ?: if (filled) V2.ink else tint.copy(alpha = alpha)) }
}

fun Modifier.at(x: Dp, y: Dp, w: Dp, h: Dp) = this.offset(x - w / 2, y - h / 2).size(w, h)

package com.past9.phoneaos.wear.ui.ex

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.sp
import kotlin.math.min
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import kotlinx.coroutines.delay

/** The agent's look, synced from the phone: which mascot, and the colours seeded from the user's accent. */
class AgentLook(val kind: MascotKind, val pal: Pal)

val LocalLook = staticCompositionLocalOf { AgentLook(MascotKind.Scout, Pal.Iris) }

/** The clock text for the top of a screen, refreshed while the screen is on. */
@Composable
fun rememberClock(): String {
    val ctx = LocalContext.current
    fun now(): String = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm"))
    var s by remember { mutableStateOf(now()) }
    LaunchedEffect(Unit) { while (true) { s = now(); delay(15_000) } }
    return s
}

/** A column the crown and a finger scroll. */
@Composable
fun CrownColumn(modifier: Modifier = Modifier, state: ScrollState = rememberScrollState(), content: @Composable ColumnScope.() -> Unit) {
    val focus = remember { FocusRequester() }
    Column(modifier.rotaryScrollable(RotaryScrollableDefaults.behavior(state), focus).verticalScroll(state), content = content)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** The expressive list button: a tonal pill whose corners tighten while it is pressed. */
@Composable
fun ExPill(
    label: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier, secondary: String? = null, icon: String? = null,
    container: Color = LocalLook.current.pal.secC, content: Color = LocalLook.current.pal.onSecC, enabled: Boolean = true, height: Float = 0f,
    trailing: @Composable (() -> Unit)? = null,
) {
    val g = LocalGeo.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val r by animateDpAsState(if (pressed) 12.dp else 30.dp, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "pill")
    val shape = RoundedCornerShape(r)
    val h = if (height > 0f) g.dp(height) else if (secondary != null) g.dp(52f) else g.dp(44f)
    Row(
        modifier.fillMaxWidth().height(h).alpha(if (enabled) 1f else 0.45f).clip(shape).background(container)
            .let { if (onClick != null && enabled) it.clickable(interactionSource = src, indication = null, onClick = onClick) else it }
            .padding(horizontal = g.dp(14f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { IconBox(icon, g.dp(20f), content); Spacer(Modifier.width(g.dp(9f))) }
        Column(Modifier.weight(1f)) {
            Txt(label, 14.5f, content, FontWeight.Bold, -0.1f, TextAlign.Start, maxLines = if (secondary != null) 1 else 2, lh = 17f)
            if (secondary != null) Txt(secondary, 11.5f, content.copy(alpha = 0.72f), FontWeight.Normal, 0f, TextAlign.Start, maxLines = 2, lh = 13.5f)
        }
        if (trailing != null) { Spacer(Modifier.width(g.dp(6f))); trailing() }
    }
}

/**
 * A slide-up sheet over a dimmed screen (never a centred dialog): it rises from the bottom edge, a tap on the dim or the system
 * back closes it. [heightFrac] is how much of the screen it takes.
 */
@Composable
fun BoxScope.ExSheet(visible: Boolean, onDismiss: () -> Unit, heightFrac: Float = 0.74f, pal: Pal = LocalLook.current.pal, content: @Composable BoxScope.(Geo) -> Unit) {
    val g = LocalGeo.current
    val p by animateFloatAsState(if (visible) 1f else 0f, spring(dampingRatio = 0.82f, stiffness = 380f), label = "sheet")
    if (p > 0.01f || visible) {
        BackHandler(visible) { onDismiss() }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f * p.coerceIn(0f, 1f))).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() })
        val shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
        val sh = g.h * heightFrac
        Box(Modifier.align(Alignment.BottomCenter).offset(0.dp, sh * (1f - p)).width(g.w).height(sh).clip(shape).background(pal.surfHi).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = g.dp(8f)).size(g.dp(28f), g.dp(4f)).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.3f)))
            content(g)
        }
    }
}

/** A sheet's title line. */
@Composable
fun BoxScope.SheetTitle(text: String, color: Color = Color.White) {
    val g = LocalGeo.current
    Box(Modifier.align(Alignment.TopCenter).padding(top = g.dp(20f)).fillMaxWidth().padding(horizontal = g.dp(22f)), contentAlignment = Alignment.Center) { Txt(text, 14.5f, color, FontWeight.Bold, -0.1f, lh = 17f, maxLines = 2) }
}

/** The fade that lets a scrolling list slide under the round glass. */
@Composable
fun EdgeFades(top: Float = 0.17f, bottom: Float = 0.2f) {
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(0f to Color.Black, top to Color.Transparent, (1f - bottom) to Color.Transparent, 1f to Color.Black))
    }
}

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
            drawText(l, topLeft = Offset((this.size.width - l.size.width) / 2, (y * g.k).dp.toPx()))
        }
    }
}

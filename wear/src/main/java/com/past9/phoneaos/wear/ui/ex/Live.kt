package com.past9.phoneaos.wear.ui.ex

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.graphics.shapes.RoundedPolygon

/**
 * The agent on screen. Only the moods that move read the clock, so a resting agent costs nothing: the phone's mascot sits still at
 * rest and the screen is not redrawn every frame.
 */
@Composable
fun LiveOrb(cx: Dp, cy: Dp, r: Dp, mood: Mood, level: Float = 0f, kind: MascotKind = LocalLook.current.kind) {
    val moves = mood == Mood.Listening || mood == Mood.Thinking || mood == Mood.Speaking || mood == Mood.Call
    val t = if (moves) rememberT() else 0f
    val pal = LocalLook.current.pal
    Orb(cx, cy, r, mood, t, level, kind, glow = 0f, tint = pal.primary, ink = pal.onPrimary)
}

/** A tonal Material shape behind the agent, turning slowly while it works. */
@Composable
fun LiveBlob(poly: RoundedPolygon, cx: Dp, cy: Dp, size: Dp, fill: Color, spin: Float = 0f) {
    val t = if (spin != 0f) rememberT() else 0f
    Canvas(Modifier.fillMaxSize()) { shapeBlob(poly, Offset(cx.toPx(), cy.toPx()), size.toPx(), fill, t * spin) }
}

/** The wavy ring (expressive progress): its ripple runs while [animated]. */
@Composable
fun LiveWavy(cx: Dp, cy: Dp, r: Dp, amp: Dp, n: Int, color: Color, sw: Dp, animated: Boolean = true, speed: Float = 4f, sweep: Float = 360f, startSpin: Float = 0f) {
    val t = if (animated) rememberT() else 0f
    Canvas(Modifier.fillMaxSize()) { wavyRing(Offset(cx.toPx(), cy.toPx()), r.toPx(), amp.toPx(), n, t * speed, color, sw.toPx(), sweep, -90f + t * startSpin) }
}

/** A synthetic voice level for previews and for a call's "speaking" look: syllable-like pulses. */
fun voice(t: Float, seed: Float = 0f): Float {
    val env = 0.5f + 0.5f * kotlin.math.sin(t * 2.1f + seed)
    val syl = 0.5f + 0.5f * kotlin.math.sin(t * 11f + seed * 3f) * kotlin.math.cos(t * 4.3f)
    return (env * syl * 1.15f).coerceIn(0f, 1f)
}

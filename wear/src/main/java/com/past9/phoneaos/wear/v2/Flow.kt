package com.past9.phoneaos.wear.v2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A synthetic voice level for clips: syllable-like pulses, so the orb has something honest to follow. */
fun voice(t: Float, seed: Float = 0f): Float {
    val env = 0.5f + 0.5f * sin(t * 2.1f + seed)
    val syl = 0.5f + 0.5f * sin(t * 11f + seed * 3f) * cos(t * 4.3f)
    return clamp01(env * syl * 1.15f)
}

/**
 * The whole ask in one scene, for the clip: one agent that glides from home to listening to thinking to answering, while the
 * words around it hand over. 0-1.2 idle, 1.2-3.6 listening, 3.6-5.4 thinking, 5.4-9 answer.
 */
@Composable
fun TalkFlow(t: Float) {
    Stage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        val toListen = smooth((t - 1.1f) / 0.55f); val toThink = smooth((t - 3.6f) / 0.55f); val toAnswer = smooth((t - 5.4f) / 0.7f)
        val y = lerp(lerp(lerp(0.43f, 0.40f, toListen), 0.40f, toThink), 0.165f, toAnswer)
        val r = lerp(lerp(lerp(0.17f, 0.235f, toListen), 0.20f, toThink), 0.075f, toAnswer)
        val mood = when { t < 1.15f -> Mood.Idle; t < 3.6f -> Mood.Listening; t < 5.4f -> Mood.Thinking; else -> Mood.Speaking }
        val level = when (mood) { Mood.Listening -> voice(t - 1.2f); Mood.Speaking -> voice(t - 5.4f, 2f) * 0.9f; else -> 0f }
        Backdrop(mood, t, y, 0.8f)
        TopLabel(when { t < 1.15f -> "14:20"; t < 3.6f -> "LISTENING"; t < 5.4f -> "THINKING"; else -> "14:20" }, if (t < 1.15f || t >= 5.4f) V2.text else V2.iris, if (t < 1.15f || t >= 5.4f) 13f else 10.5f, if (t < 1.15f || t >= 5.4f) FontWeight.Medium else FontWeight.Bold, if (t < 1.15f || t >= 5.4f) 0f else 2.4f)
        Orb(cx, H * y, g.d * r, mood, t, level)
        // home chrome
        val home = 1f - toListen
        Box(Modifier.alpha(home).fillMaxSize()) {
            Box(Modifier.offset(cx - g.dp(80f), H * 0.43f + g.d * 0.17f * 1.38f).width(g.dp(160f)), contentAlignment = Alignment.Center) { Txt("Tap to talk", 13f, V2.dim, FontWeight.Medium) }
            val ry = H * 0.415f; val rx = if (g.round) g.d * 0.395f else W * 0.34f
            for ((i, ic) in listOf(Ic.call, Ic.keyboard, Ic.history).withIndex()) {
                val th = (140f - i * 50f) * PI.toFloat() / 180f
                Sat(ic, if (i == 1) g.dp(38f) else g.dp(34f), cx + rx * cos(th), H / 2 + ry * sin(th), V2.iris)
            }
        }
        val half = g.chord(H * 0.78f, 12.dp)
        // listening words arrive one by one
        val words = "What's on my calendar today".split(" ")
        val shown = ((t - 1.8f) / 0.32f).toInt().coerceIn(0, words.size)
        Box(Modifier.offset(cx - half, H * 0.645f).width(half * 2).alpha(toListen * (1f - toThink)), contentAlignment = Alignment.Center) {
            Txt(words.take(shown).joinToString(" ").ifEmpty { " " }, 15f, V2.text, lh = 20f)
        }
        // thinking status
        Box(Modifier.offset(cx - half, H * 0.745f).width(half * 2).alpha(toThink * (1f - toAnswer)), contentAlignment = Alignment.Center) {
            val sweep = ((t * 0.55f) % 1.4f) - 0.2f
            Txt(if (t < 4.6f) "Reading your calendar" else "Checking tomorrow", 15f, V2.text, FontWeight.Medium, brush = Brush.horizontalGradient(
                0f to V2.dim.copy(alpha = 0.55f), (sweep - 0.18f).coerceIn(0f, 1f) to V2.dim.copy(alpha = 0.55f), sweep.coerceIn(0f, 1f) to V2.irisHi, (sweep + 0.18f).coerceIn(0f, 1f) to V2.dim.copy(alpha = 0.55f), 1f to V2.dim.copy(alpha = 0.55f)))
        }
        // the answer: the same text block, fades and buttons as AnswerScreen
        val ah = clamp01((t - 5.9f) / 0.7f)
        val ah2 = g.chord(H * 0.5f, if (g.round) 20.dp else 14.dp).coerceAtMost(g.dp(96f))
        Box(Modifier.offset(cx - ah2, H * 0.27f + g.dp(10f * (1f - ah))).width(ah2 * 2).alpha(ah)) { TxtA(answerText, 16f, V2.text, lh = 22.5f) }
        Box(Modifier.alpha(toAnswer).fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) { drawRect(Brush.verticalGradient(0f to Color.Black, 0.2f to Color.Black, 0.30f to Color.Transparent, 0.62f to Color.Transparent, 0.80f to Color.Black, 1f to Color.Black)) }
        }
        if (toAnswer > 0f) Orb(cx, H * y, g.d * r, mood, t, level)
        Box(Modifier.alpha(ah).fillMaxSize()) {
            Sat(Ic.volume, g.dp(34f), cx - g.dp(34f), H * 0.885f, V2.iris)
            Sat(Ic.mic, g.dp(34f), cx + g.dp(34f), H * 0.885f, V2.irisHi, filled = true, fillTint = V2.iris)
        }
    }
}

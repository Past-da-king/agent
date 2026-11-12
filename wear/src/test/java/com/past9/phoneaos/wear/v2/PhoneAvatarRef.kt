package com.past9.phoneaos.wear.v2

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon

/**
 * TEST ONLY. The phone app's AgentAvatar and Mascots, copied verbatim from app/.../ui/Components.kt (only the theme colours are
 * passed in), so a render of the watch's mascot can be held next to the phone's own code.
 */
private class RefMorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress)
        path.transform(Matrix().apply { scale(size.width, size.height) })
        return Outline.Generic(path)
    }
}

private data class RefMascot(val id: String, val rest: RoundedPolygon, val busy: RoundedPolygon, val spins: Boolean)

private val RefMascots = listOf(
    RefMascot("scout", MaterialShapes.Cookie9Sided, MaterialShapes.SoftBurst, true),
    RefMascot("ghost", MaterialShapes.Ghostish, MaterialShapes.Ghostish, false),
    RefMascot("clover", MaterialShapes.Clover4Leaf, MaterialShapes.Clover8Leaf, true),
    RefMascot("bloom", MaterialShapes.Flower, MaterialShapes.Sunny, true),
    RefMascot("puff", MaterialShapes.Puffy, MaterialShapes.Cookie12Sided, false),
    RefMascot("gem", MaterialShapes.Gem, MaterialShapes.PuffyDiamond, false),
    RefMascot("pebble", MaterialShapes.Pill, MaterialShapes.Oval, false),
    RefMascot("heart", MaterialShapes.Heart, MaterialShapes.Heart, false),
    RefMascot("bun", MaterialShapes.Bun, MaterialShapes.Arch, false),
)

@Composable
fun PhoneAgentAvatar(working: Boolean, size: Dp, needsYou: Boolean = false, mascot: String = "scout") {
    val m = RefMascots.firstOrNull { it.id == mascot } ?: RefMascots.first()
    val morph = remember(m) { Morph(m.rest.normalized(), m.busy.normalized()) }
    val amount by animateFloatAsState(if (working) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 380f), label = "work")
    val animating = working || amount > 0.001f
    val (spin, breathe) = if (animating) {
        val t = rememberInfiniteTransition(label = "avatar")
        val sp by t.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "spin")
        val br by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "breathe")
        sp to br
    } else 0f to 0f
    val fill = if (needsYou) Color(0xFFFFB86B) else Color(0xFFC3BBFF)
    val eye = if (needsYou) Color(0xFF4A2800) else Color(0xFF241A7A)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    if (m.spins) rotationZ = spin * amount else translationY = -size.toPx() * 0.05f * breathe * amount
                    val s = 1f + 0.04f * breathe * amount; scaleX = s; scaleY = s
                }
                .clip(RefMorphShape(morph, (0.15f + 0.85f * breathe) * amount))
                .background(fill)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(size * 0.14f), modifier = Modifier.graphicsLayer { if (!m.spins) translationY = -size.toPx() * 0.05f * breathe * amount }) {
            repeat(2) { Box(Modifier.size(size * 0.09f, size * 0.15f).clip(RoundedCornerShape(50)).background(eye)) }
        }
    }
}

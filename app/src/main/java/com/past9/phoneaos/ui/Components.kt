package com.past9.phoneaos.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import com.past9.phoneaos.ui.theme.Eyebrow

/** Draws a [Morph] at a given progress, scaled to the layout bounds. */
class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress)
        path.transform(Matrix().apply { scale(size.width, size.height) })
        return Outline.Generic(path)
    }
}

fun RoundedPolygon.unit(): RoundedPolygon = normalized()

/** A mascot: its resting shape, the shape it breathes into while working, and whether it may spin. */
data class Mascot(val id: String, val label: String, val rest: RoundedPolygon, val busy: RoundedPolygon, val spins: Boolean)

val Mascots = listOf(
    Mascot("scout", "Scout", MaterialShapes.Cookie9Sided, MaterialShapes.SoftBurst, true),
    Mascot("ghost", "Ghost", MaterialShapes.Ghostish, MaterialShapes.Ghostish, false),
    Mascot("clover", "Clover", MaterialShapes.Clover4Leaf, MaterialShapes.Clover8Leaf, true),
    Mascot("bloom", "Bloom", MaterialShapes.Flower, MaterialShapes.Sunny, true),
    Mascot("puff", "Puff", MaterialShapes.Puffy, MaterialShapes.Cookie12Sided, false),
    Mascot("gem", "Gem", MaterialShapes.Gem, MaterialShapes.PuffyDiamond, false),
    Mascot("pebble", "Pebble", MaterialShapes.Pill, MaterialShapes.Oval, false),
    Mascot("heart", "Heart", MaterialShapes.Heart, MaterialShapes.Heart, false),
    Mascot("bun", "Bun", MaterialShapes.Bun, MaterialShapes.Arch, false),
)

fun mascotOf(id: String) = Mascots.firstOrNull { it.id == id } ?: Mascots.first()

/**
 * THE MASCOT SLOT. The user's chosen shape with a face. At rest it sits still; while the agent
 * works it breathes into its busy shape (and turns, if the shape is radially symmetric; a
 * spinning ghost or heart would look broken, so those bob instead). The customizable
 * character drops in here later without touching any screen.
 */
@Composable
fun AgentAvatar(working: Boolean, modifier: Modifier = Modifier, size: Dp = 40.dp, needsYou: Boolean = false, mascot: String? = null, color: Color? = null) {
    val m = mascotOf(mascot ?: com.past9.phoneaos.ui.theme.LocalMascot.current)
    val morph = remember(m) { Morph(m.rest.unit(), m.busy.unit()) }
    val t = rememberInfiniteTransition(label = "avatar")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "spin")
    val breathe by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "breathe")
    val amount by animateFloatAsState(if (working) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 380f), label = "work")
    val fill = color ?: if (needsYou) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val eye = if (needsYou && color == null) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onPrimary
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    if (m.spins) rotationZ = spin * amount else translationY = -size.toPx() * 0.05f * breathe * amount
                    val s = 1f + 0.04f * breathe * amount; scaleX = s; scaleY = s
                }
                .clip(MorphShape(morph, (0.15f + 0.85f * breathe) * amount))
                .background(fill)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(size * 0.14f), modifier = Modifier.graphicsLayer { if (!m.spins) translationY = -size.toPx() * 0.05f * breathe * amount }) {
            repeat(2) { Box(Modifier.size(size * 0.09f, size * 0.15f).clip(RoundedCornerShape(50)).background(eye)) }
        }
    }
}

/** Eyebrow + one line saying what the section is for. */
@Composable
fun SectionHeader(eyebrow: String, line: String? = null, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(eyebrow.uppercase(), style = Eyebrow, color = MaterialTheme.colorScheme.primary)
            if (line != null) Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    shape: Shape = MaterialTheme.shapes.large,
    padding: PaddingValues = PaddingValues(18.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(onClick = onClick ?: {}, modifier = modifier.fillMaxWidth(), enabled = onClick != null, shape = shape, color = container) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** Press-to-squish: the container morphs and springs back. */
@Composable
fun MorphIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 48.dp, container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    rest: RoundedPolygon = MaterialShapes.Circle, pressed: RoundedPolygon = MaterialShapes.Cookie6Sided, enabled: Boolean = true, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val isPressed by source.collectIsPressedAsState()
    val morph = remember(rest, pressed) { Morph(rest.unit(), pressed.unit()) }
    val p by animateFloatAsState(if (isPressed) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 800f), label = "press")
    Box(
        modifier.size(size).graphicsLayer { val s = 1f - p * 0.06f; scaleX = s; scaleY = s }
            .clip(MorphShape(morph, p)).background(container)
            .clickable(enabled = enabled, interactionSource = source, indication = androidx.compose.material3.ripple(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Secondary screens: a large flexible top bar that collapses as you scroll, and a back arrow. */
@Composable
fun SubScreen(title: String, subtitle: String?, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, fab: @Composable () -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title) },
                subtitle = subtitle?.let { { Text(it) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = actions,
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
        floatingActionButton = fab,
        content = content,
    )
}

@Composable
fun EmptyState(title: String, line: String, modifier: Modifier = Modifier, shape: RoundedPolygon = MaterialShapes.Clover4Leaf) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val s = remember(shape) { Morph(shape.unit(), shape.unit()) }
        Box(Modifier.size(88.dp).clip(MorphShape(s, 0f)).background(MaterialTheme.colorScheme.secondaryContainer))
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun StatusPill(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(50), color = container) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, color = content)
    }
}

fun relativeTime(ms: Long, now: Long = System.currentTimeMillis()): String {
    val d = (now - ms) / 1000
    return when {
        d < 60 -> "just now"; d < 3600 -> "${d / 60} min ago"; d < 86400 -> "${d / 3600} h ago"
        d < 7 * 86400 -> "${d / 86400} d ago"
        else -> java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))
    }
}

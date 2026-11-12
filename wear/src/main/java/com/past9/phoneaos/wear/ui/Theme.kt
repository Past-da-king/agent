package com.past9.phoneaos.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * The phone app's own palette (ui/theme/Theme.kt, dark), adapted for a watch: the same iris accent, the same amber that
 * means "this needs you" and nothing else, on pure black so an OLED screen switches the background off. Wear is
 * dark-first; there is no light scheme.
 */
object WatchTokens {
    val iris = Color(0xFFC3BBFF); val onIris = Color(0xFF241A7A)
    val irisContainer = Color(0xFF3A2FB0); val onIrisContainer = Color(0xFFE7E3FF)
    val secondary = Color(0xFFC8C4DB); val secondaryContainer = Color(0xFF45435A); val onSecondaryContainer = Color(0xFFE6E3F2)
    /** Amber: reserved for one meaning, "this needs you". */
    val amber = Color(0xFFFFB86B); val onAmber = Color(0xFF4A2800)
    val amberContainer = Color(0xFF6A3D00); val onAmberContainer = Color(0xFFFFDDB8)
    val error = Color(0xFFF2B8B5); val onError = Color(0xFF601410); val errorContainer = Color(0xFF8C1D18); val onErrorContainer = Color(0xFFF9DEDC)
    val black = Color(0xFF000000)
    val surfaceLow = Color(0xFF1A1920); val surface = Color(0xFF1F1E25); val surfaceHigh = Color(0xFF292830)
    val onSurface = Color(0xFFE9E7EE); val onSurfaceVariant = Color(0xFFBDBAC8)
    val outline = Color(0xFF928F9C); val outlineVariant = Color(0xFF47454F)
    /** The phone's corner steps (Radius.sm and md); a pill is a full circle. */
    const val RADIUS_SM = 12; const val RADIUS_MD = 20
}

private val WatchColors = ColorScheme(
    primary = WatchTokens.iris, onPrimary = WatchTokens.onIris,
    primaryContainer = WatchTokens.irisContainer, onPrimaryContainer = WatchTokens.onIrisContainer,
    secondary = WatchTokens.secondary, secondaryContainer = WatchTokens.secondaryContainer, onSecondaryContainer = WatchTokens.onSecondaryContainer,
    tertiary = WatchTokens.amber, onTertiary = WatchTokens.onAmber,
    tertiaryContainer = WatchTokens.amberContainer, onTertiaryContainer = WatchTokens.onAmberContainer,
    surfaceContainerLow = WatchTokens.surfaceLow, surfaceContainer = WatchTokens.surface, surfaceContainerHigh = WatchTokens.surfaceHigh,
    onSurface = WatchTokens.onSurface, onSurfaceVariant = WatchTokens.onSurfaceVariant,
    outline = WatchTokens.outline, outlineVariant = WatchTokens.outlineVariant,
    background = WatchTokens.black, onBackground = WatchTokens.onSurface,
    error = WatchTokens.error, onError = WatchTokens.onError, errorContainer = WatchTokens.errorContainer, onErrorContainer = WatchTokens.onErrorContainer,
)

@Composable
fun AgentWatchTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = WatchColors, content = content)

private fun vec(name: String, path: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black)).build()

/** The few icons the watch needs, drawn from Material's own paths (the watch has no icon library on board). */
object WIcons {
    val Mic = vec("mic", "M12,14c1.66,0 3,-1.34 3,-3V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z")
    val Keyboard = vec("keyboard", "M20,5H4c-1.1,0 -1.99,0.9 -1.99,2L2,17c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2zM11,8h2v2h-2V8zM11,11h2v2h-2v-2zM8,8h2v2H8V8zM8,11h2v2H8v-2zM7,13H5v-2h2v2zM7,10H5V8h2v2zM16,17H8v-2h8v2zM16,13h-2v-2h2v2zM16,10h-2V8h2v2zM19,13h-2v-2h2v2zM19,10h-2V8h2v2z")
    val Phone = vec("phone", "M17,1.01L7,1c-1.1,0 -2,0.9 -2,2v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-1.99 -2,-1.99zM17,19H7V5h10v14z")
    val Check = vec("check", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z")
    val Close = vec("close", "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z")
    val Shield = vec("shield", "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12V5l-9,-4z")
}

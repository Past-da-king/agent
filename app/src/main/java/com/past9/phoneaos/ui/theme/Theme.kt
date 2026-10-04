package com.past9.phoneaos.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// One accent (iris) for action. Amber is reserved for exactly one meaning: "this needs you".
private val Light = lightColorScheme(
    primary = Color(0xFF4A3FD1), onPrimary = Color(0xFFFBFAFF),
    primaryContainer = Color(0xFFE4E0FF), onPrimaryContainer = Color(0xFF1C1466),
    secondary = Color(0xFF5D5A70), onSecondary = Color(0xFFFBFAFF),
    secondaryContainer = Color(0xFFE6E3F2), onSecondaryContainer = Color(0xFF1D1B2B),
    tertiary = Color(0xFF8A5100), onTertiary = Color(0xFFFFFBF7),
    tertiaryContainer = Color(0xFFFFE2BF), onTertiaryContainer = Color(0xFF2D1700),
    error = Color(0xFFB3261E), onError = Color(0xFFFFFBFA), errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFAF8F4), onBackground = Color(0xFF1B1A1F),
    surface = Color(0xFFFAF8F4), onSurface = Color(0xFF1B1A1F),
    surfaceVariant = Color(0xFFE6E3EC), onSurfaceVariant = Color(0xFF4A4852),
    surfaceContainerLowest = Color(0xFFFEFDFB), surfaceContainerLow = Color(0xFFF5F3EE),
    surfaceContainer = Color(0xFFEFEDE8), surfaceContainerHigh = Color(0xFFE9E7E2), surfaceContainerHighest = Color(0xFFE3E1DC),
    outline = Color(0xFF797683), outlineVariant = Color(0xFFCAC6D2),
    inverseSurface = Color(0xFF302F35), inverseOnSurface = Color(0xFFF3F0F7), inversePrimary = Color(0xFFC3BBFF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFC3BBFF), onPrimary = Color(0xFF241A7A),
    primaryContainer = Color(0xFF3A2FB0), onPrimaryContainer = Color(0xFFE7E3FF),
    secondary = Color(0xFFC8C4DB), onSecondary = Color(0xFF2E2C40),
    secondaryContainer = Color(0xFF45435A), onSecondaryContainer = Color(0xFFE6E3F2),
    tertiary = Color(0xFFFFB86B), onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF6A3D00), onTertiaryContainer = Color(0xFFFFDDB8),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410), errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF131218), onBackground = Color(0xFFE9E7EE),
    surface = Color(0xFF131218), onSurface = Color(0xFFE9E7EE),
    surfaceVariant = Color(0xFF47454F), onSurfaceVariant = Color(0xFFBDBAC8),
    surfaceContainerLowest = Color(0xFF0E0D12), surfaceContainerLow = Color(0xFF1A1920),
    surfaceContainer = Color(0xFF1F1E25), surfaceContainerHigh = Color(0xFF292830), surfaceContainerHighest = Color(0xFF34333B),
    outline = Color(0xFF928F9C), outlineVariant = Color(0xFF47454F),
    inverseSurface = Color(0xFFE9E7EE), inverseOnSurface = Color(0xFF302F35), inversePrimary = Color(0xFF4A3FD1),
)

private val base = Typography()
val AppType = Typography(
    displayLarge = base.displayLarge, displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.25).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Medium), headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Medium),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Medium),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium), titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold), titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(lineHeight = 25.sp), bodyMedium = base.bodyMedium, bodySmall = base.bodySmall,
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold), labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold), labelSmall = base.labelSmall,
)

/** Eyebrow: the small label over each section. 11sp min, uppercase, tracked out. */
val Eyebrow = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, lineHeight = 16.sp)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(36.dp),
)

@Immutable data class Extra(val success: Color, val onSuccess: Color, val successContainer: Color)
val LocalExtra = staticCompositionLocalOf { Extra(Color(0xFF1E6B3A), Color.White, Color(0xFFCFEBD7)) }

@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val extra = if (dark) Extra(Color(0xFF8FD6A6), Color(0xFF0D3A1D), Color(0xFF1F4D2E)) else Extra(Color(0xFF1E6B3A), Color(0xFFF4FFF6), Color(0xFFCFEBD7))
    androidx.compose.runtime.CompositionLocalProvider(LocalExtra provides extra) {
        MaterialExpressiveTheme(colorScheme = if (dark) Dark else Light, motionScheme = MotionScheme.expressive(), shapes = AppShapes, typography = AppType, content = content)
    }
}

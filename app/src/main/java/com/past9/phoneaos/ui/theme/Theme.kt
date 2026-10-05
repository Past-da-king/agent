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

/** The user's colour. Each one is hand-tuned for contrast in light and dark, not generated. */
data class Accent(
    val id: String, val label: String,
    val lP: Color, val lOnP: Color, val lPC: Color, val lOnPC: Color,
    val dP: Color, val dOnP: Color, val dPC: Color, val dOnPC: Color,
)

val Accents = listOf(
    Accent("iris", "Iris", Color(0xFF4A3FD1), Color(0xFFFBFAFF), Color(0xFFE4E0FF), Color(0xFF1C1466), Color(0xFFC3BBFF), Color(0xFF241A7A), Color(0xFF3A2FB0), Color(0xFFE7E3FF)),
    Accent("ocean", "Ocean", Color(0xFF0B5FC9), Color(0xFFF7FAFF), Color(0xFFD6E4FF), Color(0xFF001B3F), Color(0xFFA9C7FF), Color(0xFF003064), Color(0xFF004A99), Color(0xFFD6E4FF)),
    Accent("teal", "Teal", Color(0xFF00696B), Color(0xFFF2FFFE), Color(0xFFB9F0EE), Color(0xFF002020), Color(0xFF7FD6D4), Color(0xFF003737), Color(0xFF004F50), Color(0xFFB9F0EE)),
    Accent("forest", "Forest", Color(0xFF2E6A2F), Color(0xFFF5FFF0), Color(0xFFC6EFBC), Color(0xFF032105), Color(0xFF9CD89A), Color(0xFF0A390D), Color(0xFF1B5220), Color(0xFFC6EFBC)),
    Accent("ember", "Ember", Color(0xFFB0410C), Color(0xFFFFF8F5), Color(0xFFFFDBCB), Color(0xFF390C00), Color(0xFFFFB591), Color(0xFF5A1C00), Color(0xFF853000), Color(0xFFFFDBCB)),
    Accent("rose", "Rose", Color(0xFFB0255E), Color(0xFFFFF7F8), Color(0xFFFFD9E2), Color(0xFF3E001D), Color(0xFFFFB1C8), Color(0xFF650033), Color(0xFF8E0D48), Color(0xFFFFD9E2)),
    // Bright and bold: full-strength colours for people who want the app to shout a little.
    Accent("red", "Red", Color(0xFFD7141E), Color(0xFFFFF8F7), Color(0xFFFFDAD6), Color(0xFF410002), Color(0xFFFF5A4F), Color(0xFF2D0001), Color(0xFF93000F), Color(0xFFFFDAD6)),
    Accent("tangerine", "Tangerine", Color(0xFFF26B00), Color(0xFF231000), Color(0xFFFFDCC4), Color(0xFF301400), Color(0xFFFF8F3A), Color(0xFF2A1100), Color(0xFF8A3D00), Color(0xFFFFDCC4)),
    Accent("sunflower", "Sunflower", Color(0xFFF2BC00), Color(0xFF231A00), Color(0xFFFFE08A), Color(0xFF241A00), Color(0xFFFFD233), Color(0xFF231A00), Color(0xFF5B4300), Color(0xFFFFE08A)),
    Accent("lime", "Lime", Color(0xFF5EA300), Color(0xFF0E1F00), Color(0xFFD2F5A6), Color(0xFF132100), Color(0xFF9BE34A), Color(0xFF132100), Color(0xFF2E5200), Color(0xFFD2F5A6)),
    Accent("mint", "Mint", Color(0xFF00A880), Color(0xFF00201A), Color(0xFFB2F2DF), Color(0xFF002019), Color(0xFF4FDDB4), Color(0xFF00201A), Color(0xFF00513E), Color(0xFFB2F2DF)),
    Accent("sky", "Sky", Color(0xFF008FE0), Color(0xFF00182B), Color(0xFFCDE5FF), Color(0xFF001D33), Color(0xFF52B8FF), Color(0xFF00182B), Color(0xFF004B78), Color(0xFFCDE5FF)),
    Accent("violet", "Violet", Color(0xFF7A2BF0), Color(0xFFFCF8FF), Color(0xFFEADDFF), Color(0xFF25005A), Color(0xFFB98AFF), Color(0xFF25005A), Color(0xFF5A00C6), Color(0xFFEADDFF)),
    Accent("magenta", "Magenta", Color(0xFFD6008A), Color(0xFFFFF7F9), Color(0xFFFFD8EA), Color(0xFF3D0026), Color(0xFFFF6BC1), Color(0xFF3D0026), Color(0xFF8F005B), Color(0xFFFFD8EA)),
    Accent("graphite", "Graphite", Color(0xFF1F1E24), Color(0xFFF7F6FA), Color(0xFFE3E1E8), Color(0xFF1B1A1F), Color(0xFFE6E4EC), Color(0xFF1B1A1F), Color(0xFF3A3940), Color(0xFFE6E4EC)),
)

fun accentOf(id: String) = Accents.firstOrNull { it.id == id } ?: Accents.first()

/** Which mascot shape the user picked. Read by AgentAvatar. */
val LocalMascot = staticCompositionLocalOf { "scout" }

@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), accent: String = "iris", mascot: String = "scout", content: @Composable () -> Unit) {
    val extra = if (dark) Extra(Color(0xFF8FD6A6), Color(0xFF0D3A1D), Color(0xFF1F4D2E)) else Extra(Color(0xFF1E6B3A), Color(0xFFF4FFF6), Color(0xFFCFEBD7))
    val a = accentOf(accent)
    val scheme = if (dark) Dark.copy(primary = a.dP, onPrimary = a.dOnP, primaryContainer = a.dPC, onPrimaryContainer = a.dOnPC, inversePrimary = a.lP, surfaceTint = a.dP)
        else Light.copy(primary = a.lP, onPrimary = a.lOnP, primaryContainer = a.lPC, onPrimaryContainer = a.lOnPC, inversePrimary = a.dP, surfaceTint = a.lP)
    androidx.compose.runtime.CompositionLocalProvider(LocalExtra provides extra, LocalMascot provides mascot) {
        MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), shapes = AppShapes, typography = AppType, content = content)
    }
}

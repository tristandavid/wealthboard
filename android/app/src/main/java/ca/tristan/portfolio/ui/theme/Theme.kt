package ca.tristan.portfolio.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// WealthBoard brand palette — deep navy + warm gold, matching the launcher
// icon and the colors.xml used there. A finance app reads as more
// trustworthy in a restrained, low-chroma navy/ivory scheme than in
// Material's stock purple defaults, so this replaces them everywhere.
val BrandNavy = Color(0xFF0F2A43)
val BrandNavyDark = Color(0xFF0A1D2F)
val BrandNavyLight = Color(0xFF1D3F5F)
val BrandGold = Color(0xFFC9A227)
val BrandGoldLight = Color(0xFFE4C766)
val BrandIvory = Color(0xFFF4F1E8)

// Semantic colors for gains/losses, used on the dashboard's day-change line
// and anywhere a value needs a positive/negative read at a glance.
val GainGreen = Color(0xFF1E8E5A)
val LossRed = Color(0xFFC0392B)

private val LightColors = lightColorScheme(
    primary = BrandNavy,
    onPrimary = Color.White,
    primaryContainer = BrandNavyLight,
    onPrimaryContainer = Color.White,
    secondary = BrandGold,
    onSecondary = Color(0xFF3A2E00),
    secondaryContainer = BrandGoldLight,
    onSecondaryContainer = Color(0xFF3A2E00),
    background = Color(0xFFFAFAF7),
    onBackground = Color(0xFF1A1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE9E6DD),
    onSurfaceVariant = Color(0xFF48454A),
    // Set for the same reason as the dark scheme: unset roles fall back to
    // Material's baseline, which is a cool purple-grey and sits awkwardly
    // against this warm ivory palette. Card containers resolve to
    // surfaceContainerHighest, so the ladder is defined here too.
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFAF5),
    surfaceContainer = Color(0xFFF7F5EF),
    surfaceContainerHigh = Color(0xFFF2EFE6),
    surfaceContainerHighest = Color(0xFFECE9DE),
    outline = Color(0xFF7C7A73),
    outlineVariant = Color(0xFFD8D5CC),
    scrim = Color(0xFF000000),
    error = LossRed,
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDA),
    onErrorContainer = Color(0xFF410E09)
)

private val DarkColors = darkColorScheme(
    primary = BrandGoldLight,
    onPrimary = Color(0xFF3A2E00),
    primaryContainer = BrandNavyLight,
    onPrimaryContainer = Color.White,
    secondary = BrandGold,
    onSecondary = Color(0xFF3A2E00),
    // Every role the app paints with is set explicitly. Anything left unset
    // falls back to Material's baseline dark palette, which is a purple-grey
    // that reads as off-brand against the navy. Card containers matter most:
    // CardDefaults resolves to surfaceContainerHighest, so without the
    // surfaceContainer ladder every card in the app would come out grey.
    secondaryContainer = Color(0xFF4A3C0E),
    onSecondaryContainer = Color(0xFFF2E4B0),
    tertiary = BrandGoldLight,
    onTertiary = Color(0xFF3A2E00),
    background = BrandNavyDark,
    onBackground = Color(0xFFE8E9EB),
    surface = BrandNavy,
    onSurface = Color(0xFFE8E9EB),
    surfaceVariant = BrandNavyLight,
    onSurfaceVariant = Color(0xFFB9C2CC),
    surfaceContainerLowest = Color(0xFF071523),
    surfaceContainerLow = Color(0xFF0C2138),
    surfaceContainer = Color(0xFF102845),
    surfaceContainerHigh = Color(0xFF163150),
    surfaceContainerHighest = Color(0xFF1C3A5C),
    inverseSurface = Color(0xFFE8E9EB),
    inverseOnSurface = BrandNavyDark,
    outline = Color(0xFF6E7F92),
    outlineVariant = Color(0xFF33506D),
    scrim = Color(0xFF000000),
    error = Color(0xFFE5847A),
    onError = Color(0xFF3A0906),
    errorContainer = Color(0xFF6B231C),
    onErrorContainer = Color(0xFFFFDAD5)
)

/**
 * Which colour scheme the app paints in.
 *
 * [SYSTEM] follows the device's light/dark setting; the other two pin it
 * regardless of what the device is doing.
 */
enum class ThemeMode(val label: String, val description: String) {
    LIGHT("Light", "Always use the light theme"),
    DARK("Dark", "Always use the dark theme"),
    SYSTEM("System default", "Follow your device's setting");

    companion object {
        const val PREF_KEY = "theme_mode"

        /** Parses the stored preference; anything unrecognised follows the device. */
        fun from(stored: String?): ThemeMode =
            values().firstOrNull { it.name == stored } ?: SYSTEM
    }
}

val WealthBoardTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.4.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp)
)

/**
 * WealthBoard's Material theme.
 *
 * The app shipped light-only for a while because the dark scheme was missing
 * most of its roles and produced screens that mixed navy surfaces with
 * Material's baseline grey. With the scheme above filled in, dark is a
 * first-class option and [darkTheme] is driven by the user's Appearance
 * setting (Settings → Appearance), defaulting to whatever the device is doing.
 */
@Composable
fun WealthBoardTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        typography = WealthBoardTypography,
        content = content
    )
}

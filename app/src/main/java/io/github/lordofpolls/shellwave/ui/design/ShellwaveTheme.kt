package io.github.lordofpolls.shellwave.ui.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.lordofpolls.shellwave.core.prefs.ColourTheme

/**
 * "Schematic", used whenever [ColourTheme.SCHEMATIC] is selected. Never a fallback for older
 * devices: minSdk 31 guarantees dynamic colour exists. Only `primary` is fixed to the brand hex;
 * everything else is Material 3's own baseline.
 */
private val SchematicLightColors = lightColorScheme(
    primary = Color(0xFF4D7C0F),
    secondary = Color(0xFF53634F),
    tertiary = Color(0xFF396569),
)

private val SchematicDarkColors = darkColorScheme(
    primary = Color(0xFFA3E635),
    secondary = Color(0xFFBBCCB5),
    tertiary = Color(0xFFA1CDD1),
)

/**
 * Nothing design tokens. Monochrome surfaces and text; the signal red is `primary` so it lands
 * only on live and selected things (switches, focus rings, the wordmark tilde) while
 * `secondaryContainer` stays grey so the nav pill and tonal buttons don't compete with it.
 */
private val NothingDarkColors = darkColorScheme(
    primary = Color(0xFFD71921),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD71921),
    onPrimaryContainer = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFF8A0F15),
    secondary = Color(0xFF999999),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF1A1A1A),
    onSecondaryContainer = Color(0xFFE8E8E8),
    tertiary = Color(0xFFD71921),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF2B0608),
    onTertiaryContainer = Color(0xFFFFB4AB),
    error = Color(0xFFD71921),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF2B0608),
    onErrorContainer = Color(0xFFFFB4AB),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE8E8E8),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE8E8E8),
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFF999999),
    surfaceTint = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFFE8E8E8),
    inverseOnSurface = Color(0xFF000000),
    outline = Color(0xFF333333),
    outlineVariant = Color(0xFF222222),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF222222),
    surfaceDim = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF111111),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF222222),
)

private val NothingLightColors = lightColorScheme(
    primary = Color(0xFFD71921),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD71921),
    onPrimaryContainer = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFFFFB4AB),
    secondary = Color(0xFF666666),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF0F0F0),
    onSecondaryContainer = Color(0xFF1A1A1A),
    tertiary = Color(0xFFD71921),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFBDCDD),
    onTertiaryContainer = Color(0xFF8A0F15),
    error = Color(0xFFD71921),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBDCDD),
    onErrorContainer = Color(0xFF8A0F15),
    background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFF5F5F5),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF666666),
    surfaceTint = Color(0xFF000000),
    inverseSurface = Color(0xFF1A1A1A),
    inverseOnSurface = Color(0xFFF5F5F5),
    outline = Color(0xFFCCCCCC),
    outlineVariant = Color(0xFFE8E8E8),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE8E8E8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0F0F0),
    surfaceContainerHighest = Color(0xFFE8E8E8),
)

/**
 * P3 amber phosphor CRT palette (classic VT100/Wyse amber terminals in dark mode; warm technical
 * manual parchment in light mode).
 */
private val AmberDarkColors = darkColorScheme(
    primary = Color(0xFFFFB000),
    onPrimary = Color(0xFF1A1100),
    primaryContainer = Color(0xFF3D2800),
    onPrimaryContainer = Color(0xFFFFDDA0),
    inversePrimary = Color(0xFF8C5A00),
    secondary = Color(0xFFD6A048),
    onSecondary = Color(0xFF1A1100),
    secondaryContainer = Color(0xFF261C0A),
    onSecondaryContainer = Color(0xFFFFD68A),
    tertiary = Color(0xFFFF8C24),
    onTertiary = Color(0xFF1A0D00),
    tertiaryContainer = Color(0xFF361D05),
    onTertiaryContainer = Color(0xFFFFCC99),
    error = Color(0xFFFF6B57),
    onError = Color(0xFF2B0905),
    errorContainer = Color(0xFF47120A),
    onErrorContainer = Color(0xFFFFDAD4),
    background = Color(0xFF0A0804),
    onBackground = Color(0xFFFFCF70),
    surface = Color(0xFF0A0804),
    onSurface = Color(0xFFFFCF70),
    surfaceVariant = Color(0xFF1D160A),
    onSurfaceVariant = Color(0xFFC49A4B),
    surfaceTint = Color(0xFFFFB000),
    inverseSurface = Color(0xFFFFDDA0),
    inverseOnSurface = Color(0xFF140E05),
    outline = Color(0xFF543E18),
    outlineVariant = Color(0xFF2E220D),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF291F0E),
    surfaceDim = Color(0xFF070502),
    surfaceContainerLowest = Color(0xFF050402),
    surfaceContainerLow = Color(0xFF120E06),
    surfaceContainer = Color(0xFF181208),
    surfaceContainerHigh = Color(0xFF21190B),
    surfaceContainerHighest = Color(0xFF2B2110),
)

private val AmberLightColors = lightColorScheme(
    primary = Color(0xFF945700),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE0A6),
    onPrimaryContainer = Color(0xFF311B00),
    inversePrimary = Color(0xFFFFB000),
    secondary = Color(0xFF6E5329),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF3E2C4),
    onSecondaryContainer = Color(0xFF291B07),
    tertiary = Color(0xFF8C4600),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCC2),
    onTertiaryContainer = Color(0xFF2F1500),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFAF5EA),
    onBackground = Color(0xFF231A0B),
    surface = Color(0xFFFAF5EA),
    onSurface = Color(0xFF231A0B),
    surfaceVariant = Color(0xFFEDE4D2),
    onSurfaceVariant = Color(0xFF5C4B32),
    surfaceTint = Color(0xFF945700),
    inverseSurface = Color(0xFF231A0B),
    inverseOnSurface = Color(0xFFFAF5EA),
    outline = Color(0xFFC2B295),
    outlineVariant = Color(0xFFE0D5BF),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFCF5),
    surfaceDim = Color(0xFFEAE1CE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F0E3),
    surfaceContainer = Color(0xFFF1E9D9),
    surfaceContainerHigh = Color(0xFFEBE1CE),
    surfaceContainerHighest = Color(0xFFE4D8C1),
)

/**
 * P1 green phosphor CRT palette (classic VT220/IBM 3270 phosphor green in dark mode; greenbar
 * tractor-feed paper in light mode).
 */
private val PhosphorDarkColors = darkColorScheme(
    primary = Color(0xFF33FF66),
    onPrimary = Color(0xFF00210A),
    primaryContainer = Color(0xFF083816),
    onPrimaryContainer = Color(0xFFA8FFBD),
    inversePrimary = Color(0xFF0E7A2E),
    secondary = Color(0xFF66C980),
    onSecondary = Color(0xFF00210A),
    secondaryContainer = Color(0xFF0B2412),
    onSecondaryContainer = Color(0xFFB2F5C4),
    tertiary = Color(0xFF5CE6B8),
    onTertiary = Color(0xFF002116),
    tertiaryContainer = Color(0xFF073324),
    onTertiaryContainer = Color(0xFFA6F7DC),
    error = Color(0xFFFF6B57),
    onError = Color(0xFF2B0905),
    errorContainer = Color(0xFF47120A),
    onErrorContainer = Color(0xFFFFDAD4),
    background = Color(0xFF040A06),
    onBackground = Color(0xFF9EFFB4),
    surface = Color(0xFF040A06),
    onSurface = Color(0xFF9EFFB4),
    surfaceVariant = Color(0xFF0B1C10),
    onSurfaceVariant = Color(0xFF5AB874),
    surfaceTint = Color(0xFF33FF66),
    inverseSurface = Color(0xFFB2F5C4),
    inverseOnSurface = Color(0xFF041409),
    outline = Color(0xFF1D4D2B),
    outlineVariant = Color(0xFF102E19),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF132919),
    surfaceDim = Color(0xFF020603),
    surfaceContainerLowest = Color(0xFF020503),
    surfaceContainerLow = Color(0xFF08120B),
    surfaceContainer = Color(0xFF0C1810),
    surfaceContainerHigh = Color(0xFF112116),
    surfaceContainerHighest = Color(0xFF162B1D),
)

private val PhosphorLightColors = lightColorScheme(
    primary = Color(0xFF0F6B2B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC2F0CE),
    onPrimaryContainer = Color(0xFF03260D),
    inversePrimary = Color(0xFF33FF66),
    secondary = Color(0xFF3B6648),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6ECD9),
    onSecondaryContainer = Color(0xFF0D2614),
    tertiary = Color(0xFF166958),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC2EFE5),
    onTertiaryContainer = Color(0xFF04261E),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF1F7F2),
    onBackground = Color(0xFF0D1F12),
    surface = Color(0xFFF1F7F2),
    onSurface = Color(0xFF0D1F12),
    surfaceVariant = Color(0xFFDFECE1),
    onSurfaceVariant = Color(0xFF36523E),
    surfaceTint = Color(0xFF0F6B2B),
    inverseSurface = Color(0xFF0D1F12),
    inverseOnSurface = Color(0xFFF1F7F2),
    outline = Color(0xFF9CB8A3),
    outlineVariant = Color(0xFFCCDEC8),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF8FCF9),
    surfaceDim = Color(0xFFDBE8DD),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEBF4EC),
    surfaceContainer = Color(0xFFE4EFE6),
    surfaceContainerHigh = Color(0xFFDCE9DE),
    surfaceContainerHighest = Color(0xFFD3E3D6),
)

/**
 * Dot-matrix only at title size and up: Doto is unreadable as body copy and Nothing itself keeps
 * NDot for clocks, titles and hero numbers.
 */
private val NothingTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = NothingDisplayFontFamily),
        displayMedium = base.displayMedium.copy(fontFamily = NothingDisplayFontFamily),
        displaySmall = base.displaySmall.copy(fontFamily = NothingDisplayFontFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = NothingDisplayFontFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = NothingDisplayFontFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = NothingDisplayFontFamily),
        titleLarge = base.titleLarge.copy(fontFamily = NothingDisplayFontFamily),
    )
}

/**
 * Monospace chrome headings and labels for retro CRT themes while keeping `body*` as clean sans-serif
 * so long settings descriptions remain easy to scan on mobile screens.
 */
private val RetroMonoTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = ChromeMonoFontFamily),
        displayMedium = base.displayMedium.copy(fontFamily = ChromeMonoFontFamily),
        displaySmall = base.displaySmall.copy(fontFamily = ChromeMonoFontFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = ChromeMonoFontFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = ChromeMonoFontFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = ChromeMonoFontFamily),
        titleLarge = base.titleLarge.copy(fontFamily = ChromeMonoFontFamily),
        titleMedium = base.titleMedium.copy(fontFamily = ChromeMonoFontFamily),
        titleSmall = base.titleSmall.copy(fontFamily = ChromeMonoFontFamily),
        labelLarge = base.labelLarge.copy(fontFamily = ChromeMonoFontFamily),
        labelMedium = base.labelMedium.copy(fontFamily = ChromeMonoFontFamily),
        labelSmall = base.labelSmall.copy(fontFamily = ChromeMonoFontFamily),
    )
}

/**
 * Threaded down rather than re-derived, because `isSystemInDarkTheme()` alone is wrong here: the
 * theme-mode setting can force light or dark independent of the system. [StatusColors] needs this
 * one bit of theme state directly, being fixed constants that must stay independent of
 * [MaterialTheme.colorScheme].
 */
val LocalShellwaveDarkTheme = staticCompositionLocalOf { false }

@Composable
fun ShellwaveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    colourTheme: ColourTheme = ColourTheme.DYNAMIC,
    content: @Composable () -> Unit,
) {
    val colorScheme = when (colourTheme) {
        ColourTheme.DYNAMIC -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        ColourTheme.SCHEMATIC -> if (darkTheme) SchematicDarkColors else SchematicLightColors
        ColourTheme.NOTHING -> if (darkTheme) NothingDarkColors else NothingLightColors
        ColourTheme.AMBER -> if (darkTheme) AmberDarkColors else AmberLightColors
        ColourTheme.PHOSPHOR -> if (darkTheme) PhosphorDarkColors else PhosphorLightColors
    }

    val typography = when (colourTheme) {
        ColourTheme.NOTHING -> NothingTypography
        ColourTheme.AMBER, ColourTheme.PHOSPHOR -> RetroMonoTypography
        else -> Typography()
    }

    CompositionLocalProvider(LocalShellwaveDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content,
        )
    }
}

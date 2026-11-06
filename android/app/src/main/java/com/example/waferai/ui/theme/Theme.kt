package com.example.waferai.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

@Composable
fun WaferAITheme(
    appearance: Appearance = Appearance(),
    content: @Composable () -> Unit
) {
    val dark = when (appearance.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val seed = when {
        appearance.accent == ThemeAccent.CUSTOM -> Color(appearance.customColor)
        appearance.accent != ThemeAccent.SYSTEM -> appearance.accent.color
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (dark) dynamicDarkColorScheme(context).primary else dynamicLightColorScheme(context).primary
        }
        else -> ThemeAccent.BLUE.color
    }
    val neutral = Color(0xFF89909C)
    val styledSeed = when (appearance.palette) {
        PaletteStyle.VIVID -> seed
        PaletteStyle.SOFT -> lerp(seed, neutral, 0.50f)
        PaletteStyle.NEUTRAL -> lerp(seed, neutral, 0.84f)
    }
    val tintAmount = when (appearance.palette) {
        PaletteStyle.VIVID -> 1f
        PaletteStyle.SOFT -> 0.70f
        PaletteStyle.NEUTRAL -> 0.35f
    }
    val primary = readableAccent(styledSeed, dark)
    val onAccent = if (primary.luminance() > 0.179f) Color(0xFF111114) else Color.White
    val scheme = remember(dark, appearance.pureBlack, primary, styledSeed, tintAmount) {
        if (dark) {
            val base = if (appearance.pureBlack) Color.Black else Color(0xFF101012)
            val card = Color(0xFF1C1C1F)
            darkColorScheme(
                primary = primary,
                onPrimary = onAccent,
                primaryContainer = lerp(card, primary, 0.075f * tintAmount),
                onPrimaryContainer = Color(0xFFF5F5FA),
                secondaryContainer = lerp(card, primary, 0.10f * tintAmount),
                onSecondaryContainer = Color(0xFFF5F5FA),
                surfaceTint = primary,
                background = base,
                surface = base,
                onSurface = Color(0xFFF3F3F8),
                onBackground = Color(0xFFF3F3F8),
                onSurfaceVariant = Color(0xFFAAAAB2),
                outline = Color(0xFF83838B),
                outlineVariant = Color(0xFF38383E),
                surfaceVariant = Color(0xFF303034),
                surfaceContainer = card,
                surfaceContainerHigh = Color(0xFF27272B),
                surfaceContainerHighest = Color(0xFF343438)
            )
        } else {
            val base = Color(0xFFF2F2F7)
            lightColorScheme(
                primary = primary,
                onPrimary = onAccent,
                primaryContainer = lerp(Color.White, styledSeed, 0.065f * tintAmount),
                onPrimaryContainer = Color(0xFF191922),
                secondaryContainer = lerp(base, styledSeed, 0.11f * tintAmount),
                onSecondaryContainer = Color(0xFF191922),
                surfaceTint = primary,
                background = base,
                surface = base,
                onSurface = Color(0xFF191922),
                onBackground = Color(0xFF191922),
                onSurfaceVariant = Color(0xFF666670),
                outline = Color(0xFF85858E),
                outlineVariant = Color(0xFFD8D8DF),
                surfaceVariant = Color(0xFFE8E8ED),
                surfaceContainer = Color.White,
                surfaceContainerHigh = Color(0xFFF7F7FA),
                surfaceContainerHighest = Color(0xFFECECF1)
            )
        }
    }
    val family = remember(context, appearance.font, appearance.downloadedFont) {
        FontLibrary(context).family(appearance.downloadedFont) ?: appearance.font.family
    }
    val typography = remember(family) { typographyWith(family) }
    val systemDensity = LocalDensity.current
    val density = if (appearance.followSystemText) systemDensity else remember(systemDensity.density, appearance.textPercent) {
        Density(systemDensity.density, appearance.textPercent / 100f)
    }
    val language = appearance.language.resolve(LocalConfiguration.current.locales[0])
    val direction = if (language == AppLanguage.ARABIC) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(
        LocalDensity provides density, LocalGlassLevel provides appearance.glass,
        LocalAppLanguage provides language, LocalEnglish provides !language.chinese, LocalLayoutDirection provides direction
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = StudioShapes) {
            content()
        }
    }
}

internal fun readableAccent(seed: Color, dark: Boolean): Color {
    val background = if (dark) Color(0xFF27272B) else Color(0xFFF2F2F7)
    val towards = if (dark) Color.White else Color.Black
    var color = lerp(seed.copy(alpha = 1f), towards, if (dark) 0.32f else 0.18f)
    repeat(24) {
        val contrast = (maxOf(color.luminance(), background.luminance()) + 0.05f) /
            (minOf(color.luminance(), background.luminance()) + 0.05f)
        if (contrast >= 4.5f) return color
        color = lerp(color, towards, 0.08f)
    }
    return color
}

private val StudioShapes = Shapes(
    small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(26.dp)
)

private fun typographyWith(family: FontFamily) = Typography.copy(
    displayLarge = Typography.displayLarge.copy(fontFamily = family),
    displayMedium = Typography.displayMedium.copy(fontFamily = family),
    displaySmall = Typography.displaySmall.copy(fontFamily = family),
    headlineLarge = Typography.headlineLarge.copy(fontFamily = family),
    headlineMedium = Typography.headlineMedium.copy(fontFamily = family),
    headlineSmall = Typography.headlineSmall.copy(fontFamily = family),
    titleLarge = Typography.titleLarge.copy(fontFamily = family),
    titleMedium = Typography.titleMedium.copy(fontFamily = family),
    titleSmall = Typography.titleSmall.copy(fontFamily = family),
    bodyLarge = Typography.bodyLarge.copy(fontFamily = family),
    bodyMedium = Typography.bodyMedium.copy(fontFamily = family),
    bodySmall = Typography.bodySmall.copy(fontFamily = family),
    labelLarge = Typography.labelLarge.copy(fontFamily = family),
    labelMedium = Typography.labelMedium.copy(fontFamily = family),
    labelSmall = Typography.labelSmall.copy(fontFamily = family)
)

package com.example.waferai.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.waferai.ui.theme.Appearance
import com.example.waferai.ui.theme.BottomBarStyle
import com.example.waferai.ui.theme.GlassLevel
import com.example.waferai.ui.theme.StarryNightBase
import com.example.waferai.ui.theme.StarryNightCard
import com.example.waferai.ui.theme.ThemeAccent
import com.example.waferai.ui.theme.starryNightWallpaper

@Composable
internal fun ThemePreview(appearance: Appearance) {
    val scheme = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer)
    ) {
        Box(
            Modifier.fillMaxWidth().padding(22.dp).semantics { contentDescription = "WaferAI 主题实时预览" },
            contentAlignment = Alignment.Center
        ) {
            ThemeMiniature(appearance, Modifier.widthIn(max = 158.dp).fillMaxWidth().aspectRatio(0.62f),
                showWallpaper = true)
        }
    }
}

@Composable
internal fun ThemeMiniature(
    appearance: Appearance,
    modifier: Modifier,
    darkOverride: Boolean? = null,
    split: Boolean = false,
    slide: Boolean = false,
    fade: Boolean = false,
    showWallpaper: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val dark = darkOverride ?: (scheme.background.luminance() < 0.4f)
    val starry = appearance.accent == ThemeAccent.STARRY_NIGHT
    val background = when {
        dark && appearance.pureBlack -> Color.Black
        dark && starry -> StarryNightBase
        dark -> Color(0xFF15161A)
        else -> Color(0xFFF7F7FC)
    }
    val card = if (dark && starry) StarryNightCard else if (dark) Color(0xFF272930) else Color(0xFFE5E6EF)
    val detail = if (dark) Color(0xFF858995) else Color(0xFF8A8E9B)
    val accent = if (darkOverride == null) scheme.primary else lerp(
        scheme.primary, if (dark) Color.White else Color.Black, 0.22f
    )
    val painted = showWallpaper && dark && starry && !appearance.pureBlack
    val miniature = if (painted) modifier.clip(RoundedCornerShape(9)).starryNightWallpaper(false) else modifier
    Canvas(miniature) {
        val w = size.width
        val h = size.height
        val radius = w * 0.095f
        fun block(color: Color, x: Float, y: Float, width: Float, height: Float, corner: Float = 0.055f) {
            drawRoundRect(
                color, Offset(w * x, h * y), Size(w * width, h * height), CornerRadius(w * corner)
            )
        }
        if (!painted) drawRoundRect(background, cornerRadius = CornerRadius(radius))
        if (split) block(Color(0xFFF7F7FC), 0f, 0f, 0.49f, 1f, 0.09f)
        block(detail, 0.085f, 0.084f, 0.30f, 0.032f)
        drawCircle(detail, w * 0.025f, Offset(w * 0.88f, h * 0.10f))
        val shift = if (slide) 0.055f else 0f
        block(card.copy(alpha = if (fade) 0.65f else 1f), 0.085f + shift, 0.155f, 0.83f - shift, 0.24f)
        block(detail.copy(alpha = 0.72f), 0.15f + shift, 0.199f, 0.29f, 0.024f)
        block(accent, 0.15f + shift, 0.304f, 0.38f, 0.047f)
        val die = minOf(w * 0.032f, h * 0.029f)
        for (row in -3..3) {
            for (column in -3..3) {
                if (row * row + column * column <= 12) {
                    val color = if ((row + column * 2) % 5 == 0) accent else detail.copy(alpha = 0.65f)
                    drawRoundRect(
                        color, Offset(w * 0.74f + column * die * 1.22f, h * 0.27f + row * die * 1.22f),
                        Size(die, die), CornerRadius(die * 0.16f)
                    )
                }
            }
        }
        for (index in 0..2) {
            val x = 0.085f + 0.28f * index
            block(card, x, 0.423f, 0.265f, 0.16f)
            block(detail.copy(alpha = 0.65f), x + 0.045f, 0.458f, 0.13f, 0.022f)
            block(accent, x + 0.045f, 0.509f, 0.16f, 0.027f)
        }
        block(card, 0.085f, 0.615f, 0.83f, 0.16f)
        block(detail, 0.145f, 0.665f, 0.32f, 0.02f)
        block(detail.copy(alpha = 0.52f), 0.145f, 0.71f, 0.50f, 0.018f)
        val floating = appearance.bottomBar == BottomBarStyle.FLOATING
        val x = if (floating) 0.085f else 0.03f
        val y = if (floating) 0.824f else 0.866f
        val width = 1f - 2f * x
        val navSize = Size(w * width, h * 0.104f)
        val navOrigin = Offset(w * x, h * y)
        val navRadius = CornerRadius(w * if (floating) 0.08f else 0.035f)
        val glass = appearance.glass
        if (glass != GlassLevel.NONE && floating) {
            val intense = glass == GlassLevel.INTENSE
            drawRoundRect(
                Brush.verticalGradient(
                    listOf(lerp(card, Color.White, if (intense) 0.35f else 0.16f), card, lerp(card, accent, 0.2f)),
                    navOrigin.y, navOrigin.y + navSize.height
                ), navOrigin, navSize, navRadius
            )
            drawRoundRect(detail, navOrigin, navSize, navRadius, style = Stroke(w * if (intense) 0.012f else 0.006f))
        } else {
            drawRoundRect(card, navOrigin, navSize, navRadius)
        }
        for (index in 0..4) {
            val center = x + width * (index + 0.5f) / 5f
            val color = if (index == 0) accent else detail
            drawCircle(color, w * 0.017f, Offset(w * center, h * (y + 0.038f)))
            block(color, center - 0.036f, y + 0.066f, 0.072f, 0.012f, 0.012f)
        }
    }
}

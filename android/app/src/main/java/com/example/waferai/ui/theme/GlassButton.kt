package com.example.waferai.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

internal fun Modifier.glassSurface(
    tint: Color,
    accent: Color,
    shape: Shape,
    level: GlassLevel,
    dark: Boolean,
    opacity: Float = 1f
): Modifier {
    if (level == GlassLevel.NONE) return clip(shape).background(tint)

    val intense = level == GlassLevel.INTENSE
    val highlight = if (intense) 0.11f else 0.055f
    val safeHighlight = if (tint.luminance() < 0.18f) highlight * 0.6f else highlight
    val fill = Brush.verticalGradient(
        0f to lerp(tint, Color.White, safeHighlight).copy(alpha = opacity),
        0.52f to tint.copy(alpha = opacity * if (intense) 0.83f else 0.93f),
        1f to lerp(tint, accent, 0.07f).copy(alpha = opacity)
    )
    val rim = Brush.linearGradient(
        0f to Color.White.copy(alpha = if (intense) 0.58f else 0.32f),
        0.48f to Color.White.copy(alpha = if (dark) 0.09f else 0.22f),
        1f to accent.copy(alpha = if (intense) 0.30f else 0.17f)
    )
    return shadow(if (intense) 7.dp else 3.dp, shape)
        .background(fill)
        .drawWithCache {
            val sheen = Brush.radialGradient(
                listOf(Color.White.copy(alpha = if (intense) 0.15f else 0.08f), Color.Transparent),
                center = Offset(size.width * 0.17f, -size.height * 0.25f),
                radius = size.width.coerceAtLeast(size.height).coerceAtLeast(1f) * 0.65f
            )
            onDrawBehind { drawRect(sheen) }
        }
        .border(0.7.dp, rim, shape)
}

@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    prominent: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.4f
    val level = if (enabled) LocalGlassLevel.current else GlassLevel.NONE
    val shape = RoundedCornerShape(50)
    val interaction = remember { MutableInteractionSource() }
    val tint = when {
        !enabled -> scheme.surfaceVariant
        prominent -> scheme.primary
        else -> scheme.surfaceContainerHigh
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.glassPress(interaction, level)
            .glassSurface(tint, scheme.primary, shape, level, dark),
        shape = shape,
        interactionSource = interaction,
        elevation = null,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = if (prominent) scheme.onPrimary else scheme.onSurface,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = scheme.onSurface.copy(alpha = 0.38f)
        ),
        content = content
    )
}

@Composable
internal fun Modifier.glassPress(interaction: MutableInteractionSource, level: GlassLevel): Modifier {
    if (level == GlassLevel.NONE || !android.animation.ValueAnimator.areAnimatorsEnabled()) return this
    val pressed = interaction.collectIsPressedAsState()
    val lift = animateFloatAsState(if (pressed.value) 1f else 0f, spring(0.68f, 340f), label = "glass press")
    return graphicsLayer {
        val amount = lift.value
        scaleX = 1f + amount * 0.035f
        scaleY = 1f + amount * 0.035f
        translationY = -1.dp.toPx() * amount
    }
}

package com.example.waferai.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
    val highlight = if (intense) 0.17f else 0.045f
    val safeHighlight = if (!dark && tint.luminance() < 0.18f) highlight * 0.45f else highlight
    val fill = Brush.verticalGradient(
        0f to lerp(tint, Color.White, safeHighlight).copy(alpha = opacity),
        0.48f to tint.copy(alpha = opacity * if (intense) 0.84f else 0.93f),
        1f to lerp(tint, Color.Black, if (intense) 0.12f else 0.025f).copy(alpha = opacity)
    )
    val rim = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (intense) 0.42f else 0.19f),
        0.48f to Color.White.copy(alpha = if (intense) 0.12f else 0.07f),
        1f to accent.copy(alpha = if (intense) 0.48f else 0.22f)
    )
    return shadow(if (intense) 4.dp else 1.dp, shape)
        .clip(shape)
        .background(fill)
        .border(1.dp, rim, shape)
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
    val shape = RoundedCornerShape(16.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && level != GlassLevel.NONE) 0.98f else 1f,
        animationSpec = tween(if (pressed) 90 else 145),
        label = "glass press"
    )
    val tint = when {
        !enabled -> scheme.surfaceVariant
        prominent -> scheme.primary
        else -> scheme.surfaceContainerHigh
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        }.glassSurface(tint, scheme.primary, shape, level, dark),
        shape = shape,
        interactionSource = interaction,
        elevation = null,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = if (prominent) scheme.onPrimary else scheme.onSurface,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = scheme.onSurface.copy(alpha = 0.38f)
        ),
        content = content
    )
}

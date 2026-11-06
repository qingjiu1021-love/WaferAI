package com.example.waferai.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

@Composable
internal fun GlassTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    TextButton(
        onClick, modifier.compactControlGlass(interaction, scheme.surfaceContainerHigh, enabled, 0.dp, 7.dp),
        enabled = enabled, shape = CircleShape, interactionSource = interaction,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp), content = content
    )
}

@Composable
internal fun GlassOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val level = LocalGlassLevel.current
    val interaction = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick, modifier.controlGlass(interaction, scheme.surfaceContainerHigh, enabled),
        enabled = enabled, shape = CircleShape, interactionSource = interaction,
        border = if (enabled && level != GlassLevel.NONE) null else ButtonDefaults.outlinedButtonBorder(enabled),
        content = content
    )
}

@Composable
internal fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    content: @Composable () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val tint = if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh
    val hasGlass = enabled && LocalGlassLevel.current != GlassLevel.NONE
    IconButton(
        onClick, modifier.compactControlGlass(interaction, tint, enabled, 8.dp, 8.dp),
        enabled = enabled, interactionSource = interaction,
        colors = if (selected && !hasGlass) IconButtonDefaults.iconButtonColors(containerColor = tint)
            else IconButtonDefaults.iconButtonColors(),
        content = content
    )
}

@Composable
private fun Modifier.compactControlGlass(
    interaction: MutableInteractionSource,
    tint: Color,
    enabled: Boolean,
    horizontalInset: Dp,
    verticalInset: Dp
): Modifier {
    val level = LocalGlassLevel.current
    if (!enabled || level == GlassLevel.NONE) return this
    val accent = MaterialTheme.colorScheme.primary
    return glassPress(interaction, level).drawWithCache {
        val horizontal = horizontalInset.toPx().coerceAtMost(size.width / 4f)
        val vertical = verticalInset.toPx().coerceAtMost(size.height / 4f)
        val start = Offset(horizontal, vertical)
        val end = Offset(size.width - horizontal, size.height - vertical)
        val panel = Size(end.x - start.x, end.y - start.y)
        val corner = CornerRadius(minOf(panel.height, panel.width) / 2f)
        val intense = level == GlassLevel.INTENSE
        val fill = Brush.verticalGradient(
            listOf(lerp(tint, Color.White, if (intense) 0.12f else 0.075f).copy(alpha = 0.81f),
                tint.copy(alpha = 0.63f), lerp(tint, accent, 0.09f).copy(alpha = 0.75f)),
            startY = start.y, endY = end.y
        )
        val rim = Brush.linearGradient(
            listOf(Color.White.copy(alpha = if (intense) 0.47f else 0.29f),
                Color.White.copy(alpha = 0.09f), accent.copy(alpha = 0.20f)),
            start = start, end = end
        )
        onDrawBehind {
            drawRoundRect(fill, start, panel, corner)
            drawRoundRect(rim, start, panel, corner, style = Stroke(0.65.dp.toPx()))
        }
    }
}

@Composable
internal fun GlassFilledIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val glass = enabled && LocalGlassLevel.current != GlassLevel.NONE
    FilledIconButton(
        onClick, modifier.controlGlass(interaction, scheme.primary, enabled),
        enabled = enabled, interactionSource = interaction,
        colors = if (glass) IconButtonDefaults.filledIconButtonColors(
            containerColor = Color.Transparent, contentColor = scheme.onPrimary
        ) else IconButtonDefaults.filledIconButtonColors(),
        content = content
    )
}

@Composable
internal fun GlassFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val glass = enabled && LocalGlassLevel.current != GlassLevel.NONE
    val tint = if (selected) scheme.secondaryContainer else scheme.surfaceContainer
    FilterChip(
        selected = selected, onClick = onClick, label = label,
        modifier = modifier.controlGlass(interaction, tint, enabled),
        enabled = enabled, shape = CircleShape, interactionSource = interaction,
        border = if (glass) null else FilterChipDefaults.filterChipBorder(enabled, selected),
        colors = if (glass) FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent, selectedContainerColor = Color.Transparent
        ) else FilterChipDefaults.filterChipColors()
    )
}

@Composable
internal fun GlassActionSurface(
    onClick: () -> Unit,
    modifier: Modifier,
    shape: Shape,
    enabled: Boolean,
    content: @Composable () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val glass = enabled && LocalGlassLevel.current != GlassLevel.NONE
    Surface(
        onClick = onClick,
        modifier = modifier.controlGlass(interaction, scheme.surfaceContainer, enabled, 0.92f, shape),
        enabled = enabled,
        shape = shape,
        color = if (glass) Color.Transparent else scheme.surfaceContainer,
        border = if (glass) null else BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.18f)),
        interactionSource = interaction,
        content = content
    )
}

@Composable
private fun Modifier.controlGlass(
    interaction: MutableInteractionSource,
    tint: Color,
    enabled: Boolean,
    opacity: Float = 1f,
    shape: Shape = CircleShape
): Modifier {
    val level = LocalGlassLevel.current
    if (!enabled || level == GlassLevel.NONE) return this
    val scheme = MaterialTheme.colorScheme
    return glassPress(interaction, level)
        .glassSurface(tint, scheme.primary, shape, level, scheme.surface.luminance() < 0.4f, opacity)
}

@Composable
internal fun GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    properties: PopupProperties = PopupProperties(focusable = true),
    shape: Shape = RoundedCornerShape(22.dp),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val level = LocalGlassLevel.current
    val glass = level != GlassLevel.NONE
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = if (glass) modifier.glassSurface(
            containerColor, scheme.primary, shape, level, scheme.surface.luminance() < 0.4f, 0.97f
        ) else modifier,
        offset = offset,
        scrollState = rememberScrollState(),
        properties = properties,
        shape = shape,
        containerColor = if (glass) Color.Transparent else containerColor,
        border = if (glass) null else border,
        content = content
    )
}

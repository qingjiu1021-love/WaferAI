package com.example.waferai.ui.theme

import android.animation.ValueAnimator
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun GlassSegmentedTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    GlassTabs(
        labels, selectedIndex, onSelect,
        modifier = Modifier.fillMaxWidth().glassSurface(
            scheme.surfaceContainer, scheme.primary, RoundedCornerShape(50),
            LocalGlassLevel.current, scheme.surface.luminance() < 0.4f
        ).padding(3.dp),
        enabled = enabled
    )
}

@Composable
internal fun GlassTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    surface: Modifier = Modifier,
    floating: Boolean = false,
    dragEnabled: Boolean = false,
    enabled: Boolean = true,
    onPressChanged: ((Boolean) -> Unit)? = null,
    icon: (@Composable (Int, Color) -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val level = LocalGlassLevel.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val animated = ValueAnimator.areAnimatorsEnabled()
    val currentSelect by rememberUpdatedState(onSelect)
    val pressChanged by rememberUpdatedState(onPressChanged)
    val currentIndex by rememberUpdatedState(selectedIndex)
    val view = LocalView.current
    val count = labels.size
    val motion = remember(animated) { DockMotion(selectedIndex, animated) }
    LaunchedEffect(motion) { if (animated) motion.animate() }
    LaunchedEffect(motion, selectedIndex) { motion.select(selectedIndex) }
    val touch = if (dragEnabled && enabled) Modifier.pointerInput(motion, count, rtl, floating) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial)
            val geometry = tabGeometry(size.width.toFloat(), count, floating, 4.dp.toPx(), 84.dp.toPx())
            val direction = if (rtl) -1 else 1
            fun indexAt(x: Float): Float {
                val start = if (rtl) size.width - x else x
                val position = (start - geometry.inset) / geometry.pitch - 0.5f
                val edge = position.coerceIn(0f, (count - 1).toFloat())
                return edge + rubberBand(position - edge, 0.35f)
            }

            var fingerIndex = indexAt(down.position.x)
            var selected = fingerIndex.roundToInt().coerceIn(0, count - 1)
            var committed = false
            var dragged = false
            val velocity = VelocityTracker()
            velocity.addPosition(down.uptimeMillis, down.position)
            motion.press(selected)
            pressChanged?.invoke(true)
            down.consume()
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed) break
                    if (!change.pressed) {
                        if (dragged) {
                            val speed = velocity.calculateVelocity().x * direction / geometry.pitch
                            selected = dockReleaseIndex(fingerIndex, speed, count - 1)
                        }
                        change.consume()
                        committed = true
                        break
                    }
                    if (change.position != change.previousPosition) {
                        velocity.addPosition(change.uptimeMillis, change.position)
                    }
                    if (dragged || abs(change.position.x - down.position.x) >= viewConfiguration.touchSlop) {
                        dragged = true
                        fingerIndex = indexAt(change.position.x)
                        val outside = change.position.x - change.position.x.coerceIn(0f, size.width.toFloat())
                        val limit = min(size.width, size.height) * (7f / 32f)
                        motion.drag(fingerIndex, rubberBand(outside, limit).toDp().value)
                        val next = fingerIndex.roundToInt().coerceIn(0, count - 1)
                        if (next != selected) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        selected = next
                    }
                    change.consume()
                }
            } finally {
                pressChanged?.invoke(false)
                motion.release(if (committed) selected else currentIndex)
                if (committed && selected != currentIndex) currentSelect(selected)
            }
        }
    } else Modifier

    Box(modifier.then(touch).graphicsLayer {
        if (floating && size.width > 0f) {
            val frame = motion.frame
            val stretch = frame.pull.dp.toPx()
            val scale = 1f + frame.swell * min(0.25f, 16.dp.toPx() / size.maxDimension)
            scaleX = scale * (1f + abs(stretch) / size.width * 0.5f)
            scaleY = scale
            translationX = stretch
        }
    }) {
        Box(Modifier.matchParentSize().then(surface))
        Row(
            Modifier.fillMaxWidth().drawWithCache {
                val pad = 4.dp.toPx()
                val geometry = tabGeometry(size.width, count, floating, pad, 84.dp.toPx())
                val height = (size.height - if (floating) pad * 2 else 2.dp.toPx()).coerceAtLeast(1f)
                val rest = lerp(scheme.surface, scheme.secondary, if (scheme.surface.luminance() < 0.4f) 0.08f else 0.18f)
                val raised = lerp(scheme.surfaceContainerHigh, scheme.primary, 0.16f)
                val rimWidth = 0.8.dp.toPx()
                val shine = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.58f), Color.Transparent,
                    scheme.primary.copy(alpha = 0.32f)), endY = height + 16.dp.toPx())
                onDrawBehind {
                    val frame = motion.frame
                    val lift = if (floating && level != GlassLevel.NONE) frame.lift else 0f
                    val growth = 16.dp.toPx() * lift
                    val span = (geometry.lensWidth + growth) * (1f + frame.wobble)
                    val lensHeight = (height + growth) * (1f - 1.4f * frame.wobble)
                    val center = geometry.inset + (frame.position + 0.5f) * geometry.pitch
                    val x = (if (rtl) size.width - center else center) - span / 2
                    translate(x, (size.height - lensHeight) / 2) {
                        val body = Size(span, lensHeight)
                        val corner = CornerRadius(lensHeight / 2f)
                        val base = if (level == GlassLevel.NONE) scheme.primary.copy(alpha = 0.12f)
                            else lerp(rest, raised, lift).copy(alpha = 0.85f - lift * 0.34f)
                        drawRoundRect(base, size = body, cornerRadius = corner)
                        if (level != GlassLevel.NONE) {
                            drawRoundRect(shine, size = body, cornerRadius = corner,
                                alpha = 0.16f + lift * 0.84f, style = Stroke(rimWidth))
                        }
                    }
                }
            }.layout { measurable, constraints ->
                val pad = 4.dp.roundToPx()
                val geometry = tabGeometry(constraints.maxWidth.toFloat(), count, floating, pad.toFloat(), 84.dp.toPx())
                val inset = geometry.inset.roundToInt()
                val vertical = if (floating) pad else 0
                val inner = measurable.measure(constraints.offset(horizontal = -2 * inset, vertical = -2 * vertical))
                layout(inner.width + inset * 2, inner.height + vertical * 2) {
                    inner.placeRelative(inset, vertical)
                }
            }.selectableGroup(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            labels.forEachIndexed { index, label ->
                val selected = selectedIndex == index
                val underLens by remember(index, motion) {
                    derivedStateOf { abs(motion.frame.position - index) < 0.5f }
                }
                val color = when {
                    !enabled -> scheme.onSurface.copy(alpha = 0.38f)
                    underLens -> scheme.primary
                    icon != null -> scheme.onSurface
                    else -> scheme.onSurfaceVariant
                }
                Column(
                    Modifier.weight(1f).selectable(
                        selected = selected, enabled = enabled, role = Role.Tab,
                        interactionSource = null, indication = null, onClick = { onSelect(index) }
                    ).heightIn(min = if (icon == null) 48.dp else 60.dp)
                        .padding(horizontal = 2.dp, vertical = 5.dp)
                        .graphicsLayer {
                            val frame = motion.frame
                            val scale = if (floating && level != GlassLevel.NONE &&
                                abs(frame.position - index) < 0.5f) 1f + 0.16f * frame.lift else 1f
                            scaleX = scale
                            scaleY = scale
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
                ) {
                    icon?.invoke(index, color)
                    UiText(label, maxLines = 1, color = color, style = (if (icon == null)
                        MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium).copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium))
                }
            }
        }
    }
}

private data class TabGeometry(val pitch: Float, val lensWidth: Float, val inset: Float)

private fun tabGeometry(width: Float, count: Int, floating: Boolean, pad: Float, minLens: Float): TabGeometry {
    if (!floating || count <= 1) return TabGeometry((width / count).coerceAtLeast(1f), width / count, 0f)
    val span = (width - 2 * pad).coerceAtLeast(1f)
    val pitch = ((span - 2 * pad) / count).coerceAtLeast(1f)
    if (pitch + 2 * pad >= minLens) return TabGeometry(pitch, pitch + 2 * pad, 2 * pad)
    val lens = min(minLens, span)
    val closedPitch = ((span - lens) / (count - 1)).coerceAtLeast(1f)
    return TabGeometry(closedPitch, lens, pad + (lens - closedPitch) / 2f)
}

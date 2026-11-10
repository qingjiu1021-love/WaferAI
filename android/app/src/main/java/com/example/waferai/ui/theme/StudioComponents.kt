package com.example.waferai.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.waferai.ui.theme.UiText as Text
import kotlin.math.abs

internal enum class StudioIcon {
    WAFER, DATA, AI, TOOLS, FILE, THEME, LANGUAGE, BACKUP, ARROW, SEND, BOOK, TRAINING, PLUG, PLUS, MEMORY, MENU,
    CAMERA, PHOTO, ATTACH, THINK, CLOCK, CHECK, PERSON, CONVERT
}

@Composable
internal fun StudioHeader(title: String, section: String, icon: StudioIcon, modifier: Modifier = Modifier,
                          compact: Boolean = false,
                          action: (@Composable () -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().padding(vertical = if (compact) 7.dp else 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge)
            val subtitle = when (icon) {
                StudioIcon.WAFER -> if (LocalEnglish.current) "Import. Inspect. Understand." else "导入、检测，发现每一片的价值"
                StudioIcon.DATA -> if (LocalEnglish.current) "Your results, at a glance" else "从每一次检测，发现趋势"
                StudioIcon.AI -> if (LocalEnglish.current) "A little help, a fresh perspective" else "随时提问，让想法更进一步"
                StudioIcon.TOOLS -> if (LocalEnglish.current) "Everything you need, together" else "常用能力，触手可及"
                else -> ""
            }
            if (subtitle.isNotBlank()) Text(subtitle, color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 5.dp))
        }
        if (action != null) action() else if (!compact) StudioBadge(icon, size = 36)
    }
}

@Composable
internal fun StudioPanel(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = color,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
internal fun StudioAction(
    title: String,
    detail: String,
    icon: StudioIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    grouped: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth(),
        shape = if (grouped) RectangleShape else RoundedCornerShape(20.dp), color = scheme.surfaceContainer,
        border = if (grouped) null else BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.18f))) {
        Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            StudioBadge(icon, size = 36)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (detail.isNotEmpty()) Text(detail, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            StudioGlyph(StudioIcon.ARROW, Modifier.size(12.dp), scheme.onSurfaceVariant.copy(alpha = 0.65f))
        }
    }
}

@Composable
internal fun StudioBadge(icon: StudioIcon, size: Int = 36) {
    val scheme = MaterialTheme.colorScheme
    val starry = LocalStarryNight.current
    val accent = if (starry) when (icon) {
        StudioIcon.DATA, StudioIcon.LANGUAGE, StudioIcon.BACKUP, StudioIcon.BOOK -> scheme.secondary
        else -> scheme.primary
    } else when (icon) {
        StudioIcon.BOOK, StudioIcon.MEMORY -> Color(0xFFAF74EA)
        StudioIcon.TRAINING, StudioIcon.DATA -> Color(0xFF38AA8C)
        StudioIcon.FILE, StudioIcon.CLOCK, StudioIcon.CONVERT -> Color(0xFFDB9754)
        else -> scheme.primary
    }
    val shape = RoundedCornerShape((size / 3).dp)
    val background = if (starry) Modifier.background(
        Brush.linearGradient(listOf(accent.copy(alpha = 0.26f), accent.copy(alpha = 0.09f))), shape
    ) else Modifier.background(accent.copy(alpha = 0.12f), shape)
    Box(Modifier.size(size.dp).then(background),
        contentAlignment = Alignment.Center) {
        StudioGlyph(icon, Modifier.size((size * 0.65f).dp), accent)
    }
}

internal data class StudioLink(val title: String, val detail: String, val icon: StudioIcon, val onClick: () -> Unit)

@Composable internal fun StudioActionGroup(links: List<StudioLink>) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f))) {
        Column {
            links.forEachIndexed { index, link ->
                StudioAction(link.title, link.detail, link.icon, link.onClick, grouped = true)
                if (index < links.lastIndex) HorizontalDivider(Modifier.padding(start = 64.dp), thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            }
        }
    }
}

@Composable
internal fun StudioGlyph(icon: StudioIcon, modifier: Modifier, color: Color = MaterialTheme.colorScheme.primary,
                         navigation: Boolean = false) {
    val starry = LocalStarryNight.current
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val stroke = (if (starry || navigation) 2.05f else 1.7f) * u
        fun point(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x: Float, y: Float, a: Float, b: Float) {
            drawLine(color, point(x, y), point(a, b), stroke, StrokeCap.Round)
        }
        fun rect(x: Float, y: Float, w: Float, h: Float) {
            drawRoundRect(color, point(x, y), Size(w * u, h * u), CornerRadius(2f * u), style = Stroke(stroke))
        }
        when (icon) {
            StudioIcon.WAFER -> {
                if (starry) {
                    val orbit = Stroke(stroke, cap = StrokeCap.Round)
                    drawPath(Path().apply {
                        moveTo(3.5f * u, 18.5f * u)
                        cubicTo(3.1f * u, 17.7f * u, 3.8f * u, 16.7f * u, 4.8f * u, 15.5f * u)
                        moveTo(19.6f * u, 9.3f * u)
                        cubicTo(20.7f * u, 9.3f * u, 21.4f * u, 9.6f * u, 21.8f * u, 10.3f * u)
                    }, color.copy(alpha = 0.62f), style = orbit)
                    drawCircle(color, 7.4f * u, point(12f, 12f), style = Stroke(stroke))
                    line(7.2f, 10f, 16.8f, 10f)
                    line(12f, 7f, 12f, 14.2f)
                    drawPath(Path().apply {
                        moveTo(21.8f * u, 10.3f * u)
                        cubicTo(23.6f * u, 13.7f * u, 5.6f * u, 22.4f * u, 3.5f * u, 18.5f * u)
                    }, color, style = orbit)
                } else {
                    drawCircle(color, 9f * u, point(12f, 12f), style = Stroke(stroke))
                    line(7f, 9f, 17f, 9f)
                    line(7f, 15f, 17f, 15f)
                    line(12f, 5f, 12f, 19f)
                }
            }
            StudioIcon.DATA -> {
                line(4f, 20f, 20f, 20f)
                line(7f, 16f, 7f, 12f); line(12f, 16f, 12f, 8f); line(17f, 16f, 17f, 4f)
            }
            StudioIcon.AI -> {
                val star = Path().apply {
                    moveTo(12f * u, 2f * u); lineTo(15f * u, 9f * u); lineTo(22f * u, 12f * u)
                    lineTo(15f * u, 15f * u); lineTo(12f * u, 22f * u); lineTo(9f * u, 15f * u)
                    lineTo(2f * u, 12f * u); lineTo(9f * u, 9f * u); close()
                }
                if (starry || navigation) drawPath(star, color)
                else drawPath(star, color, style = Stroke(stroke))
            }
            StudioIcon.TOOLS -> listOf(6f to 9f, 12f to 16f, 18f to 12f).forEach { (y, x) ->
                line(3f, y, 21f, y); drawCircle(color, 2.4f * u, point(x, y))
            }
            StudioIcon.FILE -> {
                rect(5f, 3f, 14f, 18f)
                line(9f, 9f, 15f, 9f); line(9f, 13f, 15f, 13f); line(9f, 17f, 13f, 17f)
            }
            StudioIcon.CONVERT -> {
                line(4f, 8f, 20f, 8f)
                line(16f, 4f, 20f, 8f); line(20f, 8f, 16f, 12f)
                line(20f, 16f, 4f, 16f)
                line(8f, 12f, 4f, 16f); line(4f, 16f, 8f, 20f)
            }
            StudioIcon.THEME -> {
                if (starry) {
                    drawPath(Path().apply {
                        moveTo(13f * u, 3f * u)
                        cubicTo(4f * u, 4f * u, 1f * u, 13f * u, 6f * u, 18f * u)
                        cubicTo(10f * u, 23f * u, 18f * u, 22f * u, 21f * u, 16f * u)
                        cubicTo(12f * u, 18f * u, 8f * u, 10f * u, 13f * u, 3f * u)
                        close()
                    }, color)
                    line(18f, 5f, 18f, 11f); line(15f, 8f, 21f, 8f)
                } else {
                    drawCircle(color, 5.5f * u, point(12f, 12f), style = Stroke(stroke))
                    line(12f, 2f, 12f, 4f); line(12f, 20f, 12f, 22f); line(2f, 12f, 4f, 12f); line(20f, 12f, 22f, 12f)
                }
            }
            StudioIcon.LANGUAGE -> {
                drawCircle(color, 9f * u, point(12f, 12f), style = Stroke(stroke))
                drawOval(color, point(8f, 3f), Size(8f * u, 18f * u), style = Stroke(stroke))
                line(4f, 12f, 20f, 12f)
            }
            StudioIcon.BACKUP -> {
                rect(3f, 4f, 18f, 6f); rect(3f, 14f, 18f, 6f)
                drawCircle(color, u, point(7f, 7f)); drawCircle(color, u, point(7f, 17f))
            }
            StudioIcon.ARROW -> { line(9f, 5f, 16f, 12f); line(16f, 12f, 9f, 19f) }
            StudioIcon.BOOK -> {
                rect(3f, 5f, 18f, 15f)
                line(12f, 6f, 12f, 19f)
                line(6f, 10f, 9f, 10f); line(15f, 10f, 18f, 10f)
                line(6f, 14f, 9f, 14f); line(15f, 14f, 18f, 14f)
            }
            StudioIcon.TRAINING -> {
                rect(6f, 6f, 12f, 12f); rect(9f, 9f, 6f, 6f)
                listOf(8f, 12f, 16f).forEach {
                    line(it, 3f, it, 6f); line(it, 18f, it, 21f)
                    line(3f, it, 6f, it); line(18f, it, 21f, it)
                }
            }
            StudioIcon.PLUG -> {
                rect(6f, 8f, 12f, 8f)
                line(9f, 4f, 9f, 8f); line(15f, 4f, 15f, 8f); line(12f, 16f, 12f, 21f)
            }
            StudioIcon.PLUS -> { line(5f, 12f, 19f, 12f); line(12f, 5f, 12f, 19f) }
            StudioIcon.MENU -> {
                line(4f, 6f, 20f, 6f); line(4f, 12f, 16f, 12f); line(4f, 18f, 20f, 18f)
            }
            StudioIcon.MEMORY -> {
                rect(5f, 4f, 14f, 16f)
                line(9f, 9f, 15f, 9f); line(9f, 13f, 15f, 13f)
                line(9f, 17f, 12f, 17f)
            }
            StudioIcon.SEND -> { line(12f, 20f, 12f, 4f); line(12f, 4f, 5f, 11f); line(12f, 4f, 19f, 11f) }
            StudioIcon.CAMERA -> {
                rect(3f, 7f, 18f, 13f)
                line(8f, 7f, 10f, 4f)
                line(10f, 4f, 14f, 4f)
                line(14f, 4f, 16f, 7f)
                drawCircle(color, 3.5f * u, point(12f, 13.5f), style = Stroke(stroke))
            }
            StudioIcon.PHOTO -> {
                rect(3f, 4f, 18f, 16f)
                drawCircle(color, 2f * u, point(15f, 9f), style = Stroke(stroke))
                line(4f, 16f, 8f, 12f)
                line(8f, 12f, 16f, 20f)
            }
            StudioIcon.ATTACH -> {
                drawPath(Path().apply {
                    moveTo(7f * u, 8f * u)
                    lineTo(7f * u, 16f * u)
                    cubicTo(7f * u, 23f * u, 18f * u, 23f * u, 18f * u, 16f * u)
                    lineTo(18f * u, 7f * u)
                    cubicTo(18f * u, 2f * u, 11f * u, 2f * u, 11f * u, 7f * u)
                    lineTo(11f * u, 16f * u)
                    cubicTo(11f * u, 18f * u, 14f * u, 18f * u, 14f * u, 16f * u)
                    lineTo(14f * u, 8f * u)
                }, color, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            StudioIcon.THINK -> {
                drawArc(color, 145f, 250f, false, point(3f, 4f), Size(18f * u, 18f * u),
                    style = Stroke(stroke, cap = StrokeCap.Round))
                line(12f, 13f, 18f, 7f)
                drawCircle(color, 1.5f * u, point(12f, 13f))
            }
            StudioIcon.CLOCK -> {
                drawCircle(color, 9f * u, point(12f, 12f), style = Stroke(stroke))
                line(12f, 7f, 12f, 12f)
                line(12f, 12f, 16f, 14f)
            }
            StudioIcon.CHECK -> {
                line(5f, 12f, 10f, 17f)
                line(10f, 17f, 19f, 7f)
            }
            StudioIcon.PERSON -> {
                drawCircle(color, 3.5f * u, point(12f, 7f), style = Stroke(stroke))
                val shoulder = Path().apply {
                    moveTo(4f * u, 21f * u)
                    cubicTo(4f * u, 11f * u, 20f * u, 11f * u, 20f * u, 21f * u)
                }
                drawPath(shoulder, color, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
internal fun WaferArtwork(modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val primary = scheme.primary
    val starry = LocalStarryNight.current
    Canvas(modifier) {
        val unit = size.minDimension
        val radius = unit * 0.40f
        val center = Offset(size.width * 0.5f, size.height * 0.5f)
        if (starry) {
            for (arc in 0..4) {
                val band = radius * (0.84f + arc * 0.080f)
                val paint = if (arc == 3) primary else scheme.secondary
                drawArc(paint.copy(alpha = if (arc == 3) 0.58f else 0.36f),
                    startAngle = 143f + arc * 10f, sweepAngle = 100f - arc * 8f,
                    useCenter = false, topLeft = center - Offset(band, band),
                    size = Size(band * 2f, band * 2f), style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        drawCircle(Brush.radialGradient(listOf(primary.copy(alpha = 0.22f), Color.Transparent),
            center, radius * 1.32f), radius * 1.32f, center)
        drawCircle(primary.copy(alpha = 0.29f), radius * 1.12f, center, style = Stroke(0.8.dp.toPx()))
        drawCircle(primary.copy(alpha = 0.50f), radius, center, style = Stroke(1.1.dp.toPx()))
        val cell = radius / 4.2f
        for (y in -3..3) for (x in -3..3) {
            if (x * x + y * y > 13) continue
            val alpha = when {
                x == 1 && y == -1 -> 0.95f
                abs(x + 2 * y) % 4 == 0 -> 0.46f
                else -> 0.15f
            }
            val corner = center + Offset(x * cell - cell * 0.41f, y * cell - cell * 0.41f)
            drawRoundRect(primary.copy(alpha = alpha), corner,
                Size(cell * 0.82f, cell * 0.82f), CornerRadius(cell * 0.16f))
        }
        val point = center + Offset(radius * 0.76f, -radius * 0.83f)
        drawCircle(primary.copy(alpha = 0.22f), 5.dp.toPx(), point)
        drawCircle(primary, 2.3.dp.toPx(), point)
    }
}

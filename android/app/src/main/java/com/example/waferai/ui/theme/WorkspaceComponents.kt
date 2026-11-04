package com.example.waferai.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.waferai.data.remote.WaferRecord
import com.example.waferai.ui.theme.UiText as Text
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun SectionHeading(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface)
        if (action != null) TextButton(onAction) { Text(action) }
    }
}

@Composable
internal fun StatusPill(label: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Surface(modifier, color = color.copy(alpha = 0.10f), shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(5.dp)) { drawCircle(color) }
            Text(label, color = color, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun WorkspaceMetric(label: String, value: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ActionTile(title: String, detail: String, icon: StudioIcon, onClick: () -> Unit,
                        modifier: Modifier = Modifier, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick, modifier.fillMaxWidth(), enabled = enabled, shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceContainer, border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StudioBadge(icon, 32)
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun YieldRing(rate: Double, modifier: Modifier = Modifier, diameter: Dp = 64.dp,
                       color: Color = MaterialTheme.colorScheme.primary) {
    val track = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val label = String.format(Locale.ROOT, "%.1f%%", rate * 100)
    Box(modifier.size(diameter).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize().padding(4.dp)) {
            val stroke = Stroke(4.dp.toPx(), cap = StrokeCap.Round)
            drawArc(track, -90f, 360f, false, style = stroke)
            drawArc(color, -90f, (rate * 360).toFloat().coerceIn(0f, 360f), false, style = stroke)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
internal fun WaferRecordCard(record: WaferRecord, onClick: () -> Unit, modifier: Modifier = Modifier,
                             threshold: Int = 90, selected: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val color = if (100.0 * record.yieldRate < threshold) scheme.error else scheme.primary
    Surface(onClick, modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = if (selected) scheme.primaryContainer else scheme.surfaceContainer,
        border = BorderStroke(1.dp, if (selected) scheme.primary else scheme.outlineVariant.copy(alpha = 0.5f))) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            YieldRing(record.yieldRate, color = color)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(record.waferCode, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(record.lotCode, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(record.filename, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(String.format(Locale.CHINA, "良率 %.2f%% · 有效芯片 %d", record.yieldRate * 100, record.totalCount),
                    style = MaterialTheme.typography.bodySmall, color = color)
                Text(importedTime(record.createdAt), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                if (selected) Text("已选为晶圆 A", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
            }
            StudioGlyph(StudioIcon.ARROW, Modifier.size(14.dp), scheme.onSurfaceVariant)
        }
    }
}

private fun importedTime(value: String) = runCatching {
    OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of("Asia/Shanghai"))
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}.getOrDefault(value)

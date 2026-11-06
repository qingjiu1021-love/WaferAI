package com.example.waferai.ui.importdata

import com.example.waferai.ui.theme.GlassFilterChip as FilterChip
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.waferai.data.remote.WaferDetail
import com.example.waferai.data.report.PositionRate
import com.example.waferai.data.report.WaferReportData
import com.example.waferai.ui.theme.StudioPanel
import com.example.waferai.ui.theme.UiText as Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

private val PassColor = Color(0xFF2EA043)
private val FailColor = Color(0xFFE62D37)
private fun percent(value: Double) = String.format(Locale.ROOT, "%.2f%%", value * 100)

@Composable internal fun ReportCharts(wafer: WaferDetail, prepared: WaferReportData? = null) {
    val current = prepared ?: run {
        val data by produceState<Pair<com.example.waferai.data.analysis.MatrixIdentity, WaferReportData>?>(null, com.example.waferai.data.analysis.MatrixIdentity(wafer.map)) {
            value = withContext(Dispatchers.Default) { com.example.waferai.data.analysis.MatrixIdentity(wafer.map) to WaferReportData.calculate(wafer) }
        }
        data?.takeIf { it.first == com.example.waferai.data.analysis.MatrixIdentity(wafer.map) }?.second
    }
    if (current == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return }
    val scheme = MaterialTheme.colorScheme
    StudioPanel(Modifier.fillMaxWidth()) {
        Text("芯片状态占比", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().semantics { contentDescription = "合格 ${current.good}，不合格 ${current.bad}，良率 ${percent(current.yield)}" }) {
                    val stroke = 12.dp.toPx()
                    val inset = stroke / 2
                    val size = Size(size.width - stroke, size.height - stroke)
                    drawArc(PassColor, -90f, 360f * current.yield.toFloat(), false, Offset(inset, inset), size, style = Stroke(stroke))
                    drawArc(FailColor, -90f + 360f * current.yield.toFloat(), 360f * (1 - current.yield).toFloat(), false,
                        Offset(inset, inset), size, style = Stroke(stroke))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(percent(current.yield), style = MaterialTheme.typography.titleMedium)
                    Text("芯片良率", style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("合格 ${current.good} · ${percent(current.yield)}", color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                Text("不合格 ${current.bad} · ${percent(1 - current.yield)}", color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                Text("有效芯片 ${current.total}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("非芯片区域 ${current.outside} 格，不参与占比和良率计算。", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
    StudioPanel(Modifier.fillMaxWidth()) {
        Text("分区不合格率", style = MaterialTheme.typography.titleLarge)
        current.rings.forEach { region ->
            val rate = region.badRate
            Text("${region.name} · ${rate?.let(::percent) ?: "无有效芯片"}", style = MaterialTheme.typography.titleSmall)
            Canvas(Modifier.fillMaxWidth().height(10.dp).semantics { contentDescription = "${region.name}，不合格 ${region.bad}，有效 ${region.total}" }) {
                drawRoundRect(scheme.surfaceContainerHighest, cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
                if (rate != null && rate > 0) drawRoundRect(FailColor,
                    size = Size(size.width * rate.toFloat(), size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
            }
            Text("不合格 ${region.bad} / 有效 ${region.total}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        Text("横轴 0%—100%；参考矩阵中心的归一化半径，以 0.35 / 0.70 分区，不代表实际制造边界。", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
    var rows by remember { mutableStateOf(true) }
    StudioPanel(Modifier.fillMaxWidth()) {
        Text("异常位置分布", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(rows, { rows = true }, label = { Text("逐行") })
            FilterChip(!rows, { rows = false }, label = { Text("逐列") })
        }
        PositionChart(if (rows) current.rows else current.columns, if (rows) "行" else "列")
        Text("横轴为网格位置，纵轴为该行/列的不合格率；空白区断开绘制，不按 0% 处理。这是空间分布，非时间趋势。",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
}

@Composable private fun PositionChart(series: List<PositionRate>, unit: String) {
    val scheme = MaterialTheme.colorScheme
    var selected by remember(series) { mutableStateOf<Int?>(null) }
    val peak = remember(series) { series.filter { it.rate != null }.maxByOrNull { it.rate!! } }
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.height(146.dp).width(36.dp), verticalArrangement = Arrangement.SpaceBetween) {
            listOf("100%", "50%", "0%").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant) }
        }
        Column(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().height(146.dp)
                .semantics { contentDescription = "逐${unit}不合格率，0%至100%，共${series.size}$unit" }
                .pointerInput(series) { detectTapGestures { offset ->
                    selected = if (series.size == 1) 0 else ((offset.x / size.width) * (series.size - 1)).roundToInt().coerceIn(series.indices)
                } }) {
                val pad = 4.dp.toPx()
                fun point(index: Int, rate: Double) = Offset(pad + (size.width - pad * 2) * index / (series.size - 1).coerceAtLeast(1),
                    pad + (size.height - pad * 2) * (1f - rate.toFloat()))
                listOf(0f, 0.5f, 1f).forEach { f -> drawLine(scheme.outlineVariant, Offset(0f, pad + (size.height - pad * 2) * f), Offset(size.width, pad + (size.height - pad * 2) * f), 1.dp.toPx()) }
                val path = Path()
                var connected = false
                series.forEachIndexed { index, value ->
                    val rate = value.rate
                    if (rate == null) connected = false else {
                        val p = point(index, rate)
                        if (connected) path.lineTo(p.x, p.y) else path.moveTo(p.x, p.y)
                        drawCircle(scheme.primary, 1.8.dp.toPx(), p)
                        connected = true
                    }
                }
                drawPath(path, scheme.primary, style = Stroke(2.dp.toPx()))
                selected?.let { index -> series[index].rate?.let { rate -> drawCircle(FailColor, 4.dp.toPx(), point(index, rate)) } }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("1$unit", style = MaterialTheme.typography.labelSmall)
                Text("${series.size}$unit", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    val value = selected?.let { series[it] } ?: peak
    Text(if (value == null) "无有效芯片" else
        "${if (selected == null) "最高" else "选中"}：第 ${value.number} $unit · ${value.rate?.let(::percent) ?: "无有效芯片"} · 不合格 ${value.bad}/${value.total}",
        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    Text("点击图中位置查看具体统计。", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
}

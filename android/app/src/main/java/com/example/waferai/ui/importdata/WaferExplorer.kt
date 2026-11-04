package com.example.waferai.ui.importdata

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.example.waferai.ui.theme.UiText as Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.waferai.data.analysis.WaferMapFilter
import com.example.waferai.data.analysis.WaferClusters
import com.example.waferai.data.analysis.WaferSpatialStats
import com.example.waferai.data.remote.WaferDetail
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val GoodColor = Color(0xFF2E7D32)
private val BadColor = Color(0xFFD32F2F)
private const val MaxZoom = 16f

private data class Die(val row: Int, val column: Int)

private data class MapFrame(val origin: Offset, val cell: Float) {
    fun dieAt(tap: Offset, rows: Int, columns: Int): Die? {
        if (cell <= 0) return null
        val column = floor((tap.x - origin.x) / cell).toInt()
        val row = floor((tap.y - origin.y) / cell).toInt()
        return if (row in 0 until rows && column in 0 until columns) Die(row, column) else null
    }
}

private data class Viewport(val scale: Float = 1f, val pan: Offset = Offset.Zero) {
    fun frame(size: IntSize, wafer: WaferDetail): MapFrame {
        val cell = fittedCell(size, wafer) * scale
        return MapFrame(
            Offset((size.width - wafer.cols * cell) / 2f, (size.height - wafer.rows * cell) / 2f) + pan,
            cell
        )
    }

    fun transform(size: IntSize, wafer: WaferDetail, multiplier: Float, movement: Offset): Viewport {
        val nextScale = (scale * multiplier).coerceIn(1f, MaxZoom)
        if (nextScale == 1f) return Viewport()
        val requested = pan * (nextScale / scale) + movement
        val nextCell = fittedCell(size, wafer) * nextScale
        val limitX = ((nextCell * wafer.cols - size.width) / 2f).coerceAtLeast(0f)
        val limitY = ((nextCell * wafer.rows - size.height) / 2f).coerceAtLeast(0f)
        return Viewport(
            nextScale,
            Offset(requested.x.coerceIn(-limitX, limitX), requested.y.coerceIn(-limitY, limitY))
        )
    }
}

private fun fittedCell(size: IntSize, wafer: WaferDetail) =
    minOf(size.width.toFloat() / wafer.cols, size.height.toFloat() / wafer.rows)

@Composable
internal fun rememberWaferImage(wafer: WaferDetail, filter: WaferMapFilter = WaferMapFilter.ALL): androidx.compose.ui.graphics.ImageBitmap {
    val scheme = MaterialTheme.colorScheme
    val outside = lerp(scheme.surface, scheme.onSurface, 0.13f)
    return remember(wafer.id, wafer.map, filter, outside) {
        val palette = intArrayOf(
            outside.toArgb(),
            (if (filter == WaferMapFilter.FAIL) lerp(outside, GoodColor, 0.16f) else GoodColor).toArgb(),
            (if (filter == WaferMapFilter.PASS) lerp(outside, BadColor, 0.16f) else BadColor).toArgb()
        )
        val data = IntArray(wafer.rows * wafer.cols) { index ->
            palette[wafer.map[index / wafer.cols][index % wafer.cols]]
        }
        Bitmap.createBitmap(data, wafer.cols, wafer.rows, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun WaferExplorer(
    wafer: WaferDetail,
    filter: WaferMapFilter,
    showRegionGuides: Boolean = false,
    onToggleRegionGuides: () -> Unit = {},
    onFilterChange: (WaferMapFilter) -> Unit
) {
    var viewport by remember(wafer.id) { mutableStateOf(Viewport()) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var selected by remember(wafer.id) { mutableStateOf<Die?>(null) }
    val mapFocus = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val failures = remember(wafer.id, wafer.map) {
        IntArray(wafer.badCount).also { indices ->
            var position = 0
            wafer.map.forEachIndexed { row, values ->
                values.forEachIndexed { col, value -> if (value == 2) indices[position++] = row * wafer.cols + col }
            }
        }
    }
    val failureIndex = selected?.let { failures.binarySearch(it.row * wafer.cols + it.column) } ?: -1
    val scheme = MaterialTheme.colorScheme
    val outside = lerp(scheme.surface, scheme.onSurface, 0.13f)
    val mapBackground = scheme.surfaceContainer
    val muted = lerp(outside, GoodColor, 0.16f)
    val pixels = rememberWaferImage(wafer, filter)
    val frame by rememberUpdatedState(viewport.frame(viewSize, wafer))
    val transformState = rememberTransformableState { zoom, pan, _ ->
        viewport = viewport.transform(viewSize, wafer, zoom, pan)
    }
    fun zoom(factor: Float) {
        viewport = viewport.transform(viewSize, wafer, factor, Offset.Zero)
    }
    fun locate(index: Int) {
        val cellIndex = failures[index]
        val die = Die(cellIndex / wafer.cols, cellIndex % wafer.cols)
        selected = die
        val scale = maxOf(viewport.scale, 4f)
        val cell = fittedCell(viewSize, wafer) * scale
        val pan = Offset(
            (wafer.cols / 2f - die.column - 0.5f) * cell,
            (wafer.rows / 2f - die.row - 0.5f) * cell
        )
        viewport = Viewport().transform(viewSize, wafer, scale, pan)
        scope.launch { mapFocus.bringIntoView() }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("晶圆图", style = MaterialTheme.typography.titleMedium)
        Text("${wafer.rows} × ${wafer.cols}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WaferMapFilter.entries.forEach { option ->
            FilterChip(
                selected = filter == option,
                onClick = { onFilterChange(option) },
                label = { Text(option.label) }
            )
        }
        FilterChip(showRegionGuides, onToggleRegionGuides, label = { Text("分区参考线") })
    }
    val die = selected
    val status = die?.let {
        when (wafer.map[it.row][it.column]) {
            1 -> "合格"
            2 -> "不合格"
            else -> "非芯片区域"
        }
    }
    Column(Modifier.bringIntoViewRequester(mapFocus), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(300.dp)
                .clip(RoundedCornerShape(22.dp)).background(mapBackground)
                .onSizeChanged { viewSize = it }
                .transformable(transformState, canPan = { viewport.scale > 1f })
                .pointerInput(wafer.id) {
                    detectTapGestures { tap -> selected = frame.dieAt(tap, wafer.rows, wafer.cols) }
                }
                .semantics {
                    contentDescription = "晶圆图，${wafer.rows} 行 ${wafer.cols} 列。可双指缩放，放大后拖动，点击查看芯片状态。"
                }
        ) {
            val guide = scheme.outlineVariant.copy(alpha = 0.28f)
            for (step in 1 until 8) {
                val x = size.width * step / 8
                val y = size.height * step / 8
                drawLine(guide, Offset(x, 0f), Offset(x, size.height))
                drawLine(guide, Offset(0f, y), Offset(size.width, y))
            }
            val current = viewport.frame(IntSize(size.width.roundToInt(), size.height.roundToInt()), wafer)
            val cell = current.cell
            if (cell > 0f) {
                withTransform({
                    translate(current.origin.x, current.origin.y)
                    scale(cell, cell, Offset.Zero)
                }) {
                    drawImage(pixels, dstSize = IntSize(wafer.cols, wafer.rows), filterQuality = FilterQuality.None)
                }
                if (cell >= 9.dp.toPx()) {
                    val left = current.origin.x
                    val top = current.origin.y
                    val right = left + wafer.cols * cell
                    val bottom = top + wafer.rows * cell
                    val gridColor = Color.White.copy(alpha = 0.26f)
                    for (column in 1 until wafer.cols) {
                        val x = left + column * cell
                        if (x in 0f..size.width) drawLine(gridColor, Offset(x, top), Offset(x, bottom))
                    }
                    for (row in 1 until wafer.rows) {
                        val y = top + row * cell
                        if (y in 0f..size.height) drawLine(gridColor, Offset(left, y), Offset(right, y))
                    }
                }
                if (showRegionGuides) {
                    val width = wafer.cols * cell
                    val height = wafer.rows * cell
                    val center = current.origin + Offset(width / 2, height / 2)
                    for (ratio in RegionGuideRadii) {
                        val diameter = Size(width * ratio, height * ratio)
                        val topLeft = center - Offset(diameter.width / 2, diameter.height / 2)
                        drawOval(Color.Black.copy(alpha = 0.8f), topLeft, diameter, style = Stroke(3.dp.toPx()))
                        drawOval(Color.White, topLeft, diameter, style = Stroke(1.dp.toPx()))
                    }
                }
                selected?.let { die ->
                    val topLeft = current.origin + Offset(die.column * cell, die.row * cell)
                    val line = minOf(1.5.dp.toPx(), cell / 4f)
                    drawRect(Color.White, topLeft, Size(cell, cell), style = Stroke(line * 2))
                    drawRect(Color(0xFF121621), topLeft, Size(cell, cell), style = Stroke(line))
                }
            }
        }
        Text(
            if (die == null) "双指缩放 · 拖动浏览 · 点选芯片"
            else "第 ${die.row + 1} 行 · 第 ${die.column + 1} 列 · $status",
            modifier = Modifier.padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Legend(if (filter == WaferMapFilter.FAIL) muted else GoodColor, "合格")
        Legend(if (filter == WaferMapFilter.PASS) lerp(outside, BadColor, 0.16f) else BadColor, "不合格")
        Legend(outside, "非芯片")
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("${(viewport.scale * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
        Row {
            TextButton({ zoom(1f / 1.6f) }, enabled = viewport.scale > 1f) { Text("缩小") }
            TextButton({ zoom(1.6f) }, enabled = viewport.scale < MaxZoom) { Text("放大") }
            TextButton({ viewport = Viewport() }, enabled = viewport.scale > 1f) { Text("还原视图") }
        }
    }
    if (failures.isEmpty()) {
        Text("当前晶圆没有标记为不合格的芯片。", style = MaterialTheme.typography.bodySmall)
    } else if (failureIndex < 0) {
        TextButton({ locate(0) }) { Text("查看异常位置") }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton({ locate(failureIndex - 1) }, enabled = failureIndex > 0) { Text("上一处") }
            TextButton({ locate(failureIndex + 1) }, enabled = failureIndex < failures.lastIndex) { Text("下一处") }
            Text("异常 ${failureIndex + 1} / ${failures.size}", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
        }
    }
    if (showRegionGuides) {
        Text("两条参考线对应下方统计的中心、中部和边缘分区。", style = MaterialTheme.typography.bodySmall)
    }
}

private val RegionGuideRadii = listOf(
    WaferSpatialStats.CENTER_BOUNDARY.toFloat(), WaferSpatialStats.MIDDLE_BOUNDARY.toFloat()
)

@Composable
internal fun SpatialStatsCard(wafer: WaferDetail) {
    val stats by produceState<Pair<WaferSpatialStats, WaferClusters>?>(null, wafer.id, wafer.map) {
        value = null
        value = withContext(Dispatchers.Default) {
            WaferSpatialStats.calculate(wafer) to WaferClusters.calculate(wafer.map)
        }
    }
    val (current, clusters) = stats ?: return
    val scheme = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("分区缺陷统计", style = MaterialTheme.typography.titleMedium)
            current.rings.forEachIndexed { index, region ->
                if (index > 0) HorizontalDivider()
                val rate = region.badRate?.let { String.format(Locale.CHINA, "%.2f%%", it * 100) } ?: "无有效芯片"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(region.name, style = MaterialTheme.typography.titleSmall)
                        Text("不合格 ${region.bad} / 有效 ${region.total}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(rate, color = scheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            }
            Text("按矩阵中心的归一化距离分区。比率为各区不合格芯片数 ÷ 该区有效芯片数。",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            HorizontalDivider()
            Text("缺陷聚集分析", style = MaterialTheme.typography.titleMedium)
            Text("连通区数量 ${clusters.count}")
            clusters.largest?.let { region ->
                Text("最大连通区 ${region.size} 颗芯片", color = scheme.primary)
                Text("第 ${region.firstRow}–${region.lastRow} 行 · 第 ${region.firstCol}–${region.lastCol} 列",
                    style = MaterialTheme.typography.bodyMedium)
            }
            Text("按上下左右相邻统计，位置以导入后的晶圆网格为准。",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(color, RoundedCornerShape(3.dp)))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

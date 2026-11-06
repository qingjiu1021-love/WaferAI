package com.example.waferai.ui.importdata

import com.example.waferai.ui.theme.GlassFilterChip as FilterChip
import com.example.waferai.ui.theme.GlassTextButton as TextButton
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.example.waferai.ui.theme.UiText as Text
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.waferai.data.analysis.WaferMapFilter
import com.example.waferai.data.analysis.WaferClusters
import com.example.waferai.data.analysis.WaferSpatialStats
import com.example.waferai.data.remote.WaferDetail
import java.util.Locale
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import com.example.waferai.data.analysis.MatrixIdentity
import com.example.waferai.data.analysis.WaferSummary
import com.example.waferai.data.analysis.failureIndices
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

private data class RasterKey(val matrix: MatrixIdentity, val focus: MatrixIdentity, val palette: List<Int>)

@Composable
internal fun rememberWaferImage(
    wafer: WaferDetail, filter: WaferMapFilter = WaferMapFilter.ALL,
    focus: com.example.waferai.data.analysis.WaferFinding? = null
): androidx.compose.ui.graphics.ImageBitmap? {
    val scheme = MaterialTheme.colorScheme
    val outside = lerp(scheme.surface, scheme.onSurface, 0.13f)
    val palette = listOf(outside.toArgb(),
        (if (filter == WaferMapFilter.FAIL) lerp(outside, GoodColor, 0.16f) else GoodColor).toArgb(),
        (if (filter == WaferMapFilter.PASS) lerp(outside, BadColor, 0.16f) else BadColor).toArgb(),
        scheme.primary.toArgb(),
        lerp(scheme.surfaceContainer, outside, 0.18f).toArgb(),
        lerp(scheme.surfaceContainer, GoodColor, 0.18f).toArgb(),
        lerp(scheme.surfaceContainer, BadColor, 0.18f).toArgb())
    val key = RasterKey(MatrixIdentity(wafer.map), MatrixIdentity(focus?.indices), palette)
    val rendered by produceState<Pair<RasterKey, androidx.compose.ui.graphics.ImageBitmap>?>(null, key) {
        value = withContext(Dispatchers.Default) {
            val mask = focus?.let { BooleanArray(wafer.rows * wafer.cols).apply { it.indices.forEach { index -> this[index] = true } } }
            val data = IntArray(wafer.rows * wafer.cols)
            for (row in wafer.map.indices) {
                currentCoroutineContext().ensureActive()
                val bins = wafer.map[row]
                for (col in bins.indices) {
                    val index = row * wafer.cols + col
                    data[index] = if (mask == null) palette[bins[col]] else if (mask[index]) palette[3] else palette[4 + bins[col]]
                }
            }
            key to Bitmap.createBitmap(data, wafer.cols, wafer.rows, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
    // A completed old request must never flash a different matrix or filter.
    return rendered?.takeIf { it.first == key }?.second
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun WaferExplorer(
    wafer: WaferDetail,
    filter: WaferMapFilter,
    showRegionGuides: Boolean = false,
    onToggleRegionGuides: () -> Unit = {},
    focus: com.example.waferai.data.analysis.WaferFinding? = null,
    focusRevision: Int = 0,
    onClearFocus: () -> Unit = {},
    preparedFailures: IntArray? = null,
    onFilterChange: (WaferMapFilter) -> Unit
) {
    var viewport by remember(wafer.id, MatrixIdentity(wafer.map)) { mutableStateOf(Viewport()) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var selected by remember(wafer.id, MatrixIdentity(wafer.map)) { mutableStateOf<Die?>(null) }
    val mapFocus = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val failures = preparedFailures ?: run {
        val calculated by produceState<Pair<MatrixIdentity, IntArray>?>(null, MatrixIdentity(wafer.map)) {
            value = withContext(Dispatchers.Default) { MatrixIdentity(wafer.map) to failureIndices(wafer) }
        }
        calculated?.takeIf { it.first == MatrixIdentity(wafer.map) }?.second ?: intArrayOf()
    }
    val failureIndex = selected?.let { failures.binarySearch(it.row * wafer.cols + it.column) } ?: -1
    val scheme = MaterialTheme.colorScheme
    val outside = lerp(scheme.surface, scheme.onSurface, 0.13f)
    val mapBackground = scheme.surfaceContainer
    val muted = lerp(outside, GoodColor, 0.16f)
    val pixels = rememberWaferImage(wafer, filter, focus)
    androidx.compose.runtime.LaunchedEffect(MatrixIdentity(wafer.map), MatrixIdentity(focus?.indices), focusRevision, viewSize) {
        if (focus != null && viewSize.width > 0 && viewSize.height > 0 && focus.indices.isNotEmpty()) {
            val bounds = withContext(Dispatchers.Default) {
                intArrayOf(focus.indices.minOf { it / wafer.cols }, focus.indices.maxOf { it / wafer.cols },
                    focus.indices.minOf { it % wafer.cols }, focus.indices.maxOf { it % wafer.cols })
            }
            val (firstRow, lastRow, firstCol, lastCol) = bounds
            val cell = fittedCell(viewSize, wafer)
            val scale = minOf(viewSize.width / ((lastCol - firstCol + 3) * cell),
                viewSize.height / ((lastRow - firstRow + 3) * cell)).coerceIn(1f, MaxZoom)
            val centerCol = (firstCol + lastCol + 1) / 2f
            val centerRow = (firstRow + lastRow + 1) / 2f
            viewport = Viewport().transform(viewSize, wafer, scale,
                Offset((wafer.cols / 2f - centerCol) * cell * scale, (wafer.rows / 2f - centerRow) * cell * scale))
            selected = null
            mapFocus.bringIntoView()
        } else if (focus == null) viewport = Viewport()
    }
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
    if (focus != null) {
        Text("已定位：${focus.title} · 高亮 ${focus.indices.size} 颗不合格芯片", style = MaterialTheme.typography.titleSmall)
        TextButton(onClearFocus) { Text("清除发现高亮") }
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
                .pointerInput(wafer.id, MatrixIdentity(wafer.map)) {
                    detectTapGestures { tap -> selected = viewport.frame(viewSize, wafer).dieAt(tap, wafer.rows, wafer.cols) }
                }
                .semantics {
                    stateDescription = if (pixels == null) "正在绘制" else "绘制完成"
                    contentDescription = "晶圆图，${wafer.rows} 行 ${wafer.cols} 列。可双指缩放，放大后拖动，点击查看芯片状态。" +
                        (focus?.let { "已定位：${it.title}，高亮${it.indices.size}颗。" } ?: "")
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
            if (cell > 0f && pixels != null) {
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
                    val firstCol = ceil(-left / cell).toInt().coerceIn(1, wafer.cols)
                    val lastCol = floor((size.width - left) / cell).toInt().coerceIn(0, wafer.cols - 1)
                    for (column in firstCol..lastCol) {
                        val x = left + column * cell
                        if (x in 0f..size.width) drawLine(gridColor, Offset(x, top), Offset(x, bottom))
                    }
                    val firstRow = ceil(-top / cell).toInt().coerceIn(1, wafer.rows)
                    val lastRow = floor((size.height - top) / cell).toInt().coerceIn(0, wafer.rows - 1)
                    for (row in firstRow..lastRow) {
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
        Legend(if (focus != null) lerp(mapBackground, GoodColor, 0.18f) else if (filter == WaferMapFilter.FAIL) muted else GoodColor, "合格")
        Legend(if (focus != null) lerp(mapBackground, BadColor, 0.18f) else if (filter == WaferMapFilter.PASS) lerp(outside, BadColor, 0.16f) else BadColor, "不合格")
        Legend(outside, "非芯片")
        if (focus != null) Legend(scheme.primary, "当前发现中的不合格")
    }
    ZoomControls({ viewport.scale }, { zoom(it) }, { viewport = Viewport() })
    if (wafer.badCount == 0) {
        Text("当前晶圆没有标记为不合格的芯片。", style = MaterialTheme.typography.bodySmall)
    } else if (failures.isEmpty()) {
        Text("正在准备异常坐标…", style = MaterialTheme.typography.bodySmall)
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

@Composable
private fun ZoomControls(scale: () -> Float, onZoom: (Float) -> Unit, onReset: () -> Unit) {
    // Read gesture state in this small scope; panning now invalidates drawing only.
    val currentScale by rememberUpdatedState(scale)
    val percent by remember { androidx.compose.runtime.derivedStateOf { (currentScale() * 100).roundToInt() } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("$percent%", style = MaterialTheme.typography.labelLarge)
        Row {
            TextButton({ onZoom(1f / 1.6f) }, enabled = percent > 100) { Text("缩小") }
            TextButton({ onZoom(1.6f) }, enabled = percent < (MaxZoom * 100).toInt()) { Text("放大") }
            TextButton(onReset, enabled = percent > 100) { Text("还原视图") }
        }
    }
}

private val RegionGuideRadii = listOf(
    WaferSpatialStats.CENTER_BOUNDARY.toFloat(), WaferSpatialStats.MIDDLE_BOUNDARY.toFloat()
)

@Composable
internal fun SpatialStatsCard(wafer: WaferDetail, showRings: Boolean = true, prepared: WaferSummary? = null) {
    val cached = prepared?.let { WaferSpatialStats(it.report.rings, emptyList()) to it.clusters }
    val stats = cached ?: run {
        val computed by produceState<Pair<MatrixIdentity, Pair<WaferSpatialStats, WaferClusters>>?>(null, MatrixIdentity(wafer.map)) {
            value = withContext(Dispatchers.Default) { MatrixIdentity(wafer.map) to (WaferSpatialStats.calculate(wafer) to WaferClusters.calculate(wafer.map)) }
        }
        computed?.takeIf { it.first == MatrixIdentity(wafer.map) }?.second
    }
    val (current, clusters) = stats ?: return
    val scheme = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("分区缺陷统计", style = MaterialTheme.typography.titleMedium)
            if (showRings) {
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
            }
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

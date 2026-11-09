package com.example.waferai.ui.lots

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.waferai.data.analysis.LotFailureStack
import com.example.waferai.data.repository.ImportRepository
import com.example.waferai.ui.importdata.Viewport
import com.example.waferai.ui.importdata.ZoomControls
import com.example.waferai.ui.theme.BackButton
import com.example.waferai.ui.theme.GlassTextButton
import com.example.waferai.ui.theme.LocalFloatingNavigationPadding
import com.example.waferai.ui.theme.StudioAction
import com.example.waferai.ui.theme.StudioIcon
import com.example.waferai.ui.theme.StudioPanel
import com.example.waferai.ui.theme.UiText as Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LotFailureStackScreen(
    repository: ImportRepository, lotId: Int, lotCode: String, waferIds: IntArray,
    onBack: () -> Unit, onOpen: (Int) -> Unit, modifier: Modifier = Modifier
) {
    val result by produceState<Result<LotFailureStack>?>(null, repository, lotId, waferIds) {
        value = try {
            Result.success(repository.lotFailureStack(lotId, waferIds))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
    val stack = result?.getOrNull()
    var selected by rememberSaveable(lotId, waferIds) { mutableIntStateOf(-1) }
    val colors = MaterialTheme.colorScheme
    LazyColumn(
        modifier.fillMaxSize().testTag("lot-stack-results"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp + LocalFloatingNavigationPadding.current),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            BackButton(onBack)
            Text("同批失效叠加图", style = MaterialTheme.typography.headlineMedium)
            Text(lotCode, color = colors.onSurfaceVariant)
        }
        if (result == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        result?.exceptionOrNull()?.let { error ->
            item {
                val message = if (error is IllegalArgumentException) error.message else null
                Text(message ?: "无法生成叠加图，请重新选择晶圆。", color = colors.error)
                GlassTextButton(onBack) { Text("重新选择") }
            }
        }
        if (stack != null) {
            item {
                StudioPanel(Modifier.fillMaxWidth()) {
                    Text("选中 ${stack.wafers.size} 片 · ${stack.rows} × ${stack.cols}", style = MaterialTheme.typography.titleMedium)
                    Text("重复失效 ${stack.repeatedPositions} 个位置", color = colors.primary)
                    Text("同一位置至少两片不合格即计为重复失效。点选位置查看各片结果。",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            item {
                StackMap(stack, selected, { selected = it })
            }
            val index = selected.takeIf { it in 0 until stack.rows * stack.cols }
            val failures = index?.let(stack::failuresAt)
            if (index != null && failures != null) {
                item {
                    StudioPanel(Modifier.fillMaxWidth()) {
                        Text("第 ${index / stack.cols + 1} 行 · 第 ${index % stack.cols + 1} 列",
                            style = MaterialTheme.typography.titleMedium)
                        if (failures < 0) Text("非芯片区域") else {
                            Text("参与 ${stack.wafers.size} 片 · 不合格 $failures 片")
                            Text("点击晶圆查看原图与分析报告。", color = colors.onSurfaceVariant)
                        }
                    }
                }
                if (failures >= 0) {
                    items(stack.wafers.sortedByDescending { it.failedAt(index) }, key = { it.id }) { wafer ->
                        StudioAction(wafer.code, if (wafer.failedAt(index)) "此位置不合格" else "此位置合格",
                            StudioIcon.WAFER, { onOpen(wafer.id) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StackMap(stack: LotFailureStack, selected: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pass = lerp(colors.surfaceContainer, Color(0xFF4484CF), 0.55f)
    val outside = colors.surface
    val failure = Color(0xFFE45646)
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, stack, pass, outside) {
        value = withContext(Dispatchers.Default) {
            val palette = IntArray(stack.wafers.size + 1) { count ->
                lerp(pass, failure, count.toFloat() / stack.wafers.size).toArgb()
            }
            val pixels = IntArray(stack.rows * stack.cols) { index ->
                val count = stack.failuresAt(index)
                if (count < 0) outside.toArgb() else palette[count]
            }
            Bitmap.createBitmap(pixels, stack.cols, stack.rows, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var viewport by remember(stack) { mutableStateOf(Viewport()) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        viewport = viewport.transform(viewSize, stack.rows, stack.cols, zoom, pan)
    }
    val select by rememberUpdatedState(onSelect)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(20.dp))
                .background(outside).onSizeChanged { viewSize = it }
                .transformable(transform, canPan = { viewport.scale > 1f })
                .pointerInput(stack) {
                    detectTapGestures { tap ->
                        viewport.frame(viewSize, stack.rows, stack.cols).dieAt(tap, stack.rows, stack.cols)?.let {
                            select(it.row * stack.cols + it.column)
                        }
                    }
                }
                .semantics { contentDescription = "失效叠加图。可缩放并点选芯片位置。" }
        ) {
            val frame = viewport.frame(IntSize(size.width.toInt(), size.height.toInt()), stack.rows, stack.cols)
            val bitmap = image ?: return@Canvas
            withTransform({
                translate(frame.origin.x, frame.origin.y)
                scale(frame.cell, frame.cell, Offset.Zero)
            }) {
                drawImage(bitmap, filterQuality = FilterQuality.None)
            }
            if (selected >= 0) {
                val corner = frame.origin + Offset((selected % stack.cols) * frame.cell, (selected / stack.cols) * frame.cell)
                val cell = Size(frame.cell, frame.cell)
                drawRect(Color.Black, corner, cell, style = Stroke(3.dp.toPx()))
                drawRect(Color.White, corner, cell, style = Stroke(1.dp.toPx()))
            }
        }
        ZoomControls({ viewport.scale }, { factor ->
            viewport = viewport.transform(viewSize, stack.rows, stack.cols, factor, Offset.Zero)
        }, { viewport = Viewport() })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("蓝色 0 片 · 越红失效越多", style = MaterialTheme.typography.labelSmall)
            stack.peakIndex?.let { peak ->
                GlassTextButton({
                    viewport = Viewport()
                    onSelect(peak)
                }) { Text("定位失效最多的位置") }
            }
        }
    }
}

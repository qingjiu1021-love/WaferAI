package com.example.waferai.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.example.waferai.ui.theme.BackButton
import com.example.waferai.ui.theme.UiText as Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.waferai.ui.theme.LocalFloatingNavigationPadding
import com.example.waferai.data.remote.WaferDetail
import com.example.waferai.data.repository.ImportRepository
import com.example.waferai.ui.importdata.rememberWaferImage
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

@Composable
internal fun WaferCompareScreen(
    repository: ImportRepository,
    firstId: Int,
    secondId: Int,
    onOpen: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val data by produceState<Result<Pair<WaferDetail, WaferDetail>>?>(null, firstId, secondId) {
        value = try {
            Result.success(repository.waferDetail(firstId) to repository.waferDetail(secondId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = LocalFloatingNavigationPadding.current),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        BackButton(onBack)
        Text("晶圆图对照", style = MaterialTheme.typography.headlineMedium)
        Text("红色为不合格，绿色为合格，灰色为非芯片区域。两张图按各自矩阵比例显示。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            data == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            data?.isFailure == true -> Text("无法读取这两片晶圆。", color = MaterialTheme.colorScheme.error)
            else -> data?.getOrNull()?.let { (first, second) ->
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth >= 320.dp && LocalDensity.current.fontScale <= 1.35f) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            WaferCompareCard("晶圆 A", first, onOpen, Modifier.weight(1f))
                            WaferCompareCard("晶圆 B", second, onOpen, Modifier.weight(1f))
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            WaferCompareCard("晶圆 A", first, onOpen, Modifier.fillMaxWidth())
                            WaferCompareCard("晶圆 B", second, onOpen, Modifier.fillMaxWidth())
                        }
                    }
                }
                val delta = (first.yieldRate - second.yieldRate) * 100
                val difference = when {
                    delta == 0.0 -> "两片晶圆良率相同。"
                    abs(delta) < 0.01 -> "两片晶圆良率相差不足 0.01 个百分点。"
                    else -> String.format(Locale.CHINA, "晶圆 %s 良率高 %.2f 个百分点。", if (delta > 0) "A" else "B", abs(delta))
                }
                Card(Modifier.fillMaxWidth()) { Text(difference, Modifier.padding(14.dp)) }
            }
        }
    }
}

@Composable
private fun WaferCompareCard(title: String, wafer: WaferDetail, onOpen: (Int) -> Unit, modifier: Modifier) {
    val image = rememberWaferImage(wafer)
    Card(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text("${wafer.lotCode} / ${wafer.waferCode}", style = MaterialTheme.typography.titleSmall)
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f).semantics { contentDescription = "$title 的晶圆图" }) {
                val factor = minOf(size.width / wafer.cols, size.height / wafer.rows)
                val width = (wafer.cols * factor).roundToInt().coerceAtLeast(1)
                val height = (wafer.rows * factor).roundToInt().coerceAtLeast(1)
                image?.let { drawImage(
                    it, dstOffset = IntOffset(((size.width - width) / 2f).roundToInt(), ((size.height - height) / 2f).roundToInt()),
                    dstSize = IntSize(width, height), filterQuality = FilterQuality.None
                ) }
            }
            Text(String.format(Locale.CHINA, "良率 %.2f%%", wafer.yieldRate * 100), color = MaterialTheme.colorScheme.primary)
            Text("${wafer.rows} × ${wafer.cols} · 不合格 ${wafer.badCount}/${wafer.totalCount}",
                style = MaterialTheme.typography.bodySmall)
            TextButton({ onOpen(wafer.id) }) { Text("打开$title") }
        }
    }
}

package com.example.waferai.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.example.waferai.ui.theme.LocalFloatingNavigationPadding
import com.example.waferai.data.local.InspectionPoint
import com.example.waferai.data.repository.ImportRepository
import com.example.waferai.ui.theme.StudioAction
import com.example.waferai.ui.theme.StudioHeader
import com.example.waferai.ui.theme.StudioIcon
import com.example.waferai.ui.theme.StudioPanel
import com.example.waferai.ui.theme.BackButton
import com.example.waferai.ui.theme.UiText as Text
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun InspectionTrendScreen(
    repository: ImportRepository, onBack: () -> Unit, onOpen: (Int) -> Unit, modifier: Modifier
) {
    val result by produceState<Result<List<InspectionPoint>>?>(null, repository) {
        value = try {
            Result.success(repository.recentInspections())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
    val records = result?.getOrNull().orEmpty()
    val scheme = MaterialTheme.colorScheme
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp,
            bottom = 20.dp + LocalFloatingNavigationPadding.current),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            BackButton(onBack)
            StudioHeader("近期检测记录", "WAFERAI / INSPECTIONS", StudioIcon.DATA)
            Text("最多展示最近 30 张晶圆，每张取最近一次检测。", style = MaterialTheme.typography.bodySmall)
        }
        if (result == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (result?.isFailure == true) item { Text("无法读取近期检测记录。", color = scheme.error) }
        if (result?.isSuccess == true && records.isEmpty()) item {
            Text("完成首次晶圆检测后，这里会显示检测记录。")
        }
        if (records.isNotEmpty()) {
            item { YieldTrendCard(records.asReversed()) }
            item {
                StudioPanel(Modifier.fillMaxWidth()) {
                    Text("检测类别分布", style = MaterialTheme.typography.titleMedium)
                    records.groupingBy { it.labelZh }.eachCount().entries.sortedByDescending { it.value }.forEach { (label, count) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label)
                            Text("$count 张")
                        }
                        LinearProgressIndicator({ count.toFloat() / records.size }, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        items(records, key = { it.record.waferId }) { point ->
            val time = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
                .format(Instant.parse(point.record.checkedAt))
            StudioAction("${point.lot} / ${point.wafer}",
                "$time · ${percent(point.yieldRate)} · ${point.labelZh}", StudioIcon.WAFER,
                { onOpen(point.record.waferId) })
        }
    }
}

@Composable
private fun YieldTrendCard(points: List<InspectionPoint>) {
    val scheme = MaterialTheme.colorScheme
    val totalGood = points.sumOf { it.good.toLong() }
    val totalBad = points.sumOf { it.bad.toLong() }
    StudioPanel(Modifier.fillMaxWidth()) {
        Text("良率 · 本机检测顺序", style = MaterialTheme.typography.titleMedium)
        Text("加权良率 ${percent(totalGood.toDouble() / (totalGood + totalBad))} · ${points.count { it.belowThreshold }} 张待排查")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.height(144.dp), verticalArrangement = Arrangement.SpaceBetween) {
                for (rate in listOf("100%", "50%", "0%")) Text(rate, style = MaterialTheme.typography.labelSmall)
            }
            Canvas(Modifier.weight(1f).height(144.dp)) {
                val inset = 6.dp.toPx()
                val height = size.height - 2 * inset
                val width = size.width - 2 * inset
                fun position(index: Int): Offset {
                    val fraction = if (points.size == 1) 0.5f else index.toFloat() / points.lastIndex
                    return Offset(inset + width * fraction, inset + height * (1f - points[index].yieldRate.toFloat()))
                }
                for (tick in 0..2) {
                    val y = inset + height * tick / 2
                    drawLine(scheme.outlineVariant, Offset(inset, y), Offset(inset + width, y))
                }
                for (index in 1..points.lastIndex) {
                    drawLine(scheme.primary, position(index - 1), position(index), 2.dp.toPx())
                }
                points.forEachIndexed { index, point ->
                    drawCircle(if (point.belowThreshold) scheme.error else scheme.primary,
                        radius = 3.5.dp.toPx(), center = position(index))
                }
            }
        }
        Text("从早到晚排列，红点表示低于该次检测的良率阈值。", style = MaterialTheme.typography.bodySmall)
    }
}

private fun percent(value: Double) = String.format(Locale.ROOT, "%.2f%%", value * 100)

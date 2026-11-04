package com.example.waferai.ui.lots

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.example.waferai.ui.theme.BackButton
import com.example.waferai.ui.theme.UiText as Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.waferai.ui.theme.LocalFloatingNavigationPadding
import com.example.waferai.data.remote.LotSummary
import java.util.Locale
import kotlin.math.abs

@Composable
fun LotScreen(state: LotUiState, onBack: () -> Unit, onRefresh: () -> Unit, onLoadMore: () -> Unit,
              onOpen: (Int, String) -> Unit, modifier: Modifier = Modifier) {
    var comparing by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(intArrayOf()) }
    val selectedLots = selectedIds.asList().mapNotNull { id -> state.records.find { it.id == id } }
    val listState = rememberLazyListState()
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            BackButton(onBack)
            Spacer(Modifier.weight(1f))
            TextButton({
                selectedIds = intArrayOf()
                onRefresh()
            }, enabled = !state.loading) { Text("刷新") }
        }
        Text("批次汇总", style = MaterialTheme.typography.headlineLarge)
        if (state.records.isNotEmpty()) {
            TextButton({
                comparing = !comparing
                selectedIds = intArrayOf()
                listState.requestScrollToItem(0)
            }) { Text(if (comparing) "退出对比" else "对比批次") }
        }
        if (comparing) Text("选择两个批次（${selectedLots.size}/2）", style = MaterialTheme.typography.bodyMedium)
        else Text("点击批次查看已导入的晶圆。", style = MaterialTheme.typography.bodyMedium)
        state.error?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
            TextButton(if (state.records.isEmpty()) onRefresh else onLoadMore) { Text("重试") }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.records.isEmpty() && !state.loading && state.error == null) {
            Card(Modifier.fillMaxWidth()) { Text("还没有批次数据，导入晶圆后会自动汇总。", Modifier.padding(20.dp)) }
        }
        LazyColumn(Modifier.weight(1f), state = listState,
            contentPadding = PaddingValues(bottom = LocalFloatingNavigationPadding.current), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (comparing && selectedLots.size == 2) item(key = "lot-comparison") {
                LotComparison(selectedLots[0], selectedLots[1])
            }
            items(state.records, key = { it.id }) { lot ->
                if (comparing) {
                    val selected = lot.id in selectedIds
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(lot.lotCode, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${lot.waferCount} 张晶圆 · ${lot.totalCount} 个有效芯片", style = MaterialTheme.typography.bodySmall)
                            FilterChip(
                                selected = selected,
                                enabled = selected || selectedLots.size < 2,
                                onClick = {
                                    selectedIds = if (selected) selectedIds.filterNot { it == lot.id }.toIntArray()
                                    else (selectedLots.map { it.id } + lot.id).toIntArray()
                                    if (selectedIds.size == 2) listState.requestScrollToItem(0)
                                },
                                label = { Text(if (selected) "取消选择 ${lot.lotCode}" else "选择 ${lot.lotCode}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                } else LotSummaryCard(lot, Modifier.clickable { onOpen(lot.id, lot.lotCode) })
            }
            if (state.records.isNotEmpty()) item {
                if (state.hasMore && state.error == null) {
                    Button(onLoadMore, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("加载更多") }
                } else if (!state.hasMore) Text("已显示全部批次", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LotComparison(first: LotSummary, second: LotSummary) {
    val firstYield = first.yieldRate
    val secondYield = second.yieldRate
    val difference = if (firstYield != null && secondYield != null) abs(firstYield - secondYield) * 100 else null
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("批次对比结果", style = MaterialTheme.typography.titleLarge)
            listOf(first, second).forEach { lot ->
                Text(lot.lotCode, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${lot.waferCount} 张晶圆 · ${lot.totalCount} 个有效芯片 · ${lot.badCount} 个不合格")
                val rate = lot.yieldRate
                if (rate != null) {
                    Text(String.format(Locale.CHINA, "总体良率 %.2f%%", rate * 100), color = MaterialTheme.colorScheme.primary)
                    LinearProgressIndicator(progress = { rate.toFloat() }, modifier = Modifier.fillMaxWidth())
                } else Text("暂无良率")
                HorizontalDivider()
            }
            val result = when {
                difference == null -> "两批次均有有效芯片后可比较良率差距。"
                difference == 0.0 -> "两批次总体良率相同。"
                difference < 0.01 -> "两批次良率相差不足 0.01 个百分点。"
                else -> {
                    val higher = if (firstYield!! > secondYield!!) first else second
                    String.format(Locale.CHINA, "%s 总体良率高 %.2f 个百分点。", higher.lotCode, difference)
                }
            }
            Text(result, style = MaterialTheme.typography.titleSmall)
            Text("总体良率按批次内的有效芯片数加权。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

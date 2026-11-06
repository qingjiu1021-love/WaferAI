package com.example.waferai.ui.history

import com.example.waferai.ui.theme.GlassFilterChip as FilterChip
import com.example.waferai.ui.theme.GlassButton as Button
import com.example.waferai.ui.theme.GlassTextButton as TextButton
import com.example.waferai.ui.theme.GlassDropdownMenu as DropdownMenu
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.example.waferai.ui.theme.BackButton
import com.example.waferai.ui.theme.UiText as Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.waferai.ui.theme.LocalFloatingNavigationPadding
import com.example.waferai.ui.lots.LotSummaryCard
import com.example.waferai.data.local.WaferOrder
import com.example.waferai.ui.theme.WaferRecordCard

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpen: (Int) -> Unit,
    onSearch: (String) -> Unit,
    onOrder: (WaferOrder) -> Unit,
    onToggleBelow: () -> Unit,
    onThreshold: (Int) -> Unit,
    csv: CsvUiState,
    onExportCsv: () -> Unit,
    onCompare: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    reviewingYield: Boolean = false,
    lotCode: String? = null
) {
    val listState = rememberSaveable(state.query, state.order, state.onlyBelowTarget, state.threshold,
        lotCode, reviewingYield, state.yieldBand, saver = LazyListState.Saver) { LazyListState() }
    var comparing by rememberSaveable(lotCode, reviewingYield, state.yieldBand) { mutableStateOf(false) }
    var firstId by rememberSaveable(lotCode, reviewingYield, state.yieldBand) { mutableStateOf<Int?>(null) }
    val chooseRecord: (Int) -> Unit = { id ->
        val first = firstId
        if (!comparing) onOpen(id)
        else if (first == null) firstId = id
        else if (first == id) firstId = null
        else {
            firstId = null
            comparing = false
            onCompare(first, id)
        }
    }
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BackButton(onBack)
            Spacer(Modifier.weight(1f))
            TextButton(onRefresh, enabled = !state.loading) { Text("刷新") }
        }
        Text(
            when {
                state.yieldBand != null -> "良率分组晶圆"
                reviewingYield -> "低良率排查"
                lotCode != null -> "批次晶圆"
                else -> "历史记录"
            }, style = MaterialTheme.typography.headlineMedium
        )
        state.yieldBand?.let { band ->
            Text(band.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = onSearch,
            label = { Text("搜索批次、晶圆或文件名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).testTag("history-records"), state = listState,
            contentPadding = PaddingValues(bottom = LocalFloatingNavigationPadding.current), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "yield-controls") {
                YieldControls(state, onOrder, onToggleBelow, onThreshold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({
                        comparing = !comparing
                        firstId = null
                    }, enabled = state.records.isNotEmpty() || comparing) {
                        Text(if (comparing) "取消晶圆对照" else "对照晶圆")
                    }
                    TextButton(onExportCsv, enabled = !csv.busy && !state.loading && state.records.isNotEmpty()) {
                        Text(if (csv.busy) "正在导出…" else "导出筛选结果 CSV")
                    }
                }
                if (comparing) Text(if (firstId == null) "选择第一片晶圆" else "已选第一片，请选择第二片晶圆。",
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                csv.message?.let { Text(it, color = if (csv.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
            }
            if (state.records.isNotEmpty()) item {
                Text("已加载 ${state.records.size} 条记录", style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let { message -> item {
                Text(message, color = MaterialTheme.colorScheme.error)
                TextButton(if (state.records.isEmpty()) onRefresh else onLoadMore) { Text("重试") }
            } }
            if (state.records.isEmpty() && !state.loading && state.error == null) item {
                Card(Modifier.fillMaxWidth()) {
                    val message = when {
                        state.onlyBelowTarget -> "没有找到低于 ${state.threshold}% 的匹配记录。"
                        state.query.isNotBlank() -> "没有找到匹配的晶圆。"
                        state.yieldBand != null -> "这个良率区间暂无晶圆。"
                        else -> "还没有导入记录。"
                    }
                    Text(message, Modifier.padding(20.dp))
                }
            }
            state.lot?.let { lot -> item(key = "lot-summary") { LotSummaryCard(lot) } }
            items(state.records, key = { it.id }) { record ->
                WaferRecordCard(record, { chooseRecord(record.id) }, threshold = state.threshold,
                    selected = comparing && firstId == record.id)
            }
            if (state.records.isNotEmpty()) {
                item {
                    if (state.hasMore && state.error == null) {
                        Button(onLoadMore, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("加载更多") }
                    } else if (!state.hasMore) {
                        Text("已显示全部记录", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun YieldControls(
    state: HistoryUiState,
    onOrder: (WaferOrder) -> Unit,
    onToggleBelow: () -> Unit,
    onThreshold: (Int) -> Unit
) {
    var orderMenu by remember { mutableStateOf(false) }
    var thresholdMenu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                TextButton({ orderMenu = true }) { Text(state.order.label + " ▾") }
                DropdownMenu(orderMenu, { orderMenu = false }) {
                    WaferOrder.entries.forEach { order ->
                        DropdownMenuItem(text = { Text(order.label) }, onClick = {
                            orderMenu = false
                            onOrder(order)
                        })
                    }
                }
            }
            if (state.yieldBand == null) {
                Box {
                    TextButton({ thresholdMenu = true }) { Text("阈值 ${state.threshold}% ▾") }
                    DropdownMenu(thresholdMenu, { thresholdMenu = false }) {
                        listOf(80, 85, 90, 95, 99).forEach { threshold ->
                            DropdownMenuItem(text = { Text("$threshold%") }, onClick = {
                                thresholdMenu = false
                                onThreshold(threshold)
                            })
                        }
                    }
                }
                FilterChip(state.onlyBelowTarget, onToggleBelow, label = { Text("仅低良率") })
            }
        }
        Text(if (state.yieldBand == null) "按手动设定的阈值筛选低良率晶圆。" else "点击晶圆记录查看晶圆图与良率详情。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

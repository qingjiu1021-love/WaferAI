package com.example.waferai.ui.importdata

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.waferai.data.analysis.WaferMapFilter
import com.example.waferai.data.local.DemoWafer
import com.example.waferai.data.local.WaferTestComparison
import com.example.waferai.data.local.WaferTestHistory
import com.example.waferai.data.local.WaferTestPlan
import com.example.waferai.data.local.testLimitText
import com.example.waferai.data.remote.WaferDetail
import com.example.waferai.ui.theme.BackButton
import com.example.waferai.ui.theme.GlassButton
import com.example.waferai.ui.theme.GlassSegmentedTabs
import com.example.waferai.ui.theme.GlassTextButton
import com.example.waferai.ui.theme.LocalFloatingNavigationPadding
import com.example.waferai.ui.theme.StatusPill
import com.example.waferai.ui.theme.StudioAction
import com.example.waferai.ui.theme.StudioIcon
import com.example.waferai.ui.theme.StudioPanel
import com.example.waferai.ui.theme.UiText as Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val testModes = listOf("教学仿真", WaferTestPlan.REPLAY)
private val resultTabs = listOf("晶圆图", "检测图表")
private val passColor = Color(0xFF2E9C52)
private val failColor = Color(0xFFE2535B)
private val testDate = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

@Composable
internal fun WaferTestScreen(viewModel: WaferTestViewModel, current: WaferDetail?, onBack: () -> Unit,
                             modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var limit by rememberSaveable { mutableStateOf("10") }
    var comparisonLimit by rememberSaveable { mutableStateOf("15") }
    var sample by rememberSaveable { mutableStateOf(DemoWafer.WM_CENTER_1) }
    var showSamples by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val scroll = rememberScrollState()
    val reportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf"), viewModel::export)
    val colors = MaterialTheme.colorScheme
    val plan = state.plan
    LaunchedEffect(plan) { scroll.scrollTo(0) }

    if (showSamples) DemoWaferPicker(state.running, onSelect = {
        sample = it
        showSamples = false
    }, onDismiss = { showSamples = false }, realOnly = true)
    if (showHistory) {
        val history by viewModel.history.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { viewModel.loadHistory() }
        WaferTestHistoryDialog(history, onDismiss = { showHistory = false }, onSelect = { id ->
            showHistory = false
            viewModel.openRecord(id)
        })
    }

    Column(
        modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 12.dp)
            .padding(bottom = LocalFloatingNavigationPadding.current),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            BackButton(onBack)
            GlassTextButton({ focus.clearFocus(); showHistory = true }, enabled = !state.busy) { Text("测试历史") }
        }
        Text("测试仿真与结果回放", style = MaterialTheme.typography.headlineMedium)
        if (state.openingRecord) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (plan == null && !state.busy) {
            Text("比较两种测试上限，或回放已有晶圆结果。晶圆图、AI 分类与报告均在本机生成。",
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            GlassSegmentedTabs(testModes, mode, { mode = it })
            StudioPanel(Modifier.fillMaxWidth()) {
                if (mode == 0) {
                    Text("漏电流仿真 · 21 × 21 网格", style = MaterialTheme.typography.titleMedium)
                    Text("同一组仿真读数分别按两个上限判定。读数不超过上限即合格，非芯片区域跳过。",
                        style = MaterialTheme.typography.bodyMedium)
                    val upper = parseTestLimit(limit)
                    val alternate = parseTestLimit(comparisonLimit)
                    TestLimitField("基准上限（µA）", limit, { limit = it }, upper != null)
                    TestLimitField("对比上限（µA）", comparisonLimit, { comparisonLimit = it }, alternate != null)
                    Text("可设 0.1～100 µA。晶圆图、图表和 AI 分类按基准上限生成。",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    GlassButton({
                        if (upper != null && alternate != null) {
                            focus.clearFocus()
                            viewModel.simulate(upper, alternate)
                        }
                    }, Modifier.fillMaxWidth(), enabled = upper != null && alternate != null, prominent = true) {
                        Text("开始模拟测试")
                    }
                } else {
                    Text("WM-811K 历史样本", style = MaterialTheme.typography.titleMedium)
                    Text(sample.description, style = MaterialTheme.typography.bodyMedium)
                    Text("逐颗回放原有合格状态，完成后运行本地 AI 分类。此模式只读取合格状态。",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    GlassTextButton({ showSamples = true }) { Text("选择回放样本") }
                    GlassButton({ viewModel.replay(sample) }, Modifier.fillMaxWidth(), prominent = true) { Text("开始回放") }
                    current?.let { wafer ->
                        GlassButton({ viewModel.replay(wafer) }, Modifier.fillMaxWidth()) { Text("回放当前晶圆") }
                    }
                }
            }
        }
        if (plan != null) {
            StatusPill(if (plan.mode == WaferTestPlan.SIMULATION) "仿真数据" else plan.mode)
            Text(plan.source, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        if (state.running) {
            if (plan == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else {
                val total = plan.dieIndices.size
                Text(when {
                    state.savingResult -> "正在保存测试结果…"
                    state.finished -> "正在运行本地 AI 检测…"
                    state.paused -> "已暂停 · ${state.completed} / $total 颗芯片"
                    else -> "已处理 ${state.completed} / $total 颗芯片"
                })
                LinearProgressIndicator(progress = { state.completed.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                if (!state.finished) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassTextButton(viewModel::togglePause) { Text(if (state.paused) "继续测试" else "暂停测试") }
                        GlassTextButton(viewModel::cancel) { Text("取消测试") }
                    }
                }
                WaferTestScan(plan, state.completed)
                Text("合格 ${state.passed} · 不合格 ${state.completed - state.passed} · 待处理 ${total - state.completed}",
                    style = MaterialTheme.typography.labelMedium)
            }
            if (plan == null) GlassTextButton(viewModel::cancel) { Text("取消测试") }
        } else if (plan != null && state.finished) {
            StudioPanel(Modifier.fillMaxWidth()) {
                val wafer = plan.wafer
                Text("测试流程已完成", style = MaterialTheme.typography.titleMedium)
                state.completedAt?.let {
                    Text(testDate.format(it) + if (state.recorded) " · 已存入测试历史" else "",
                        style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
                val yieldLabel = if (plan.comparison != null) "基准良率 " else "良率 "
                Text(yieldLabel + String.format(Locale.ROOT, "%.2f%%", wafer.yieldRate * 100),
                    style = MaterialTheme.typography.headlineSmall, color = colors.primary)
                Text("合格 ${wafer.goodCount} · 不合格 ${wafer.badCount} · 有效 ${wafer.totalCount}",
                    style = MaterialTheme.typography.bodySmall)
                state.prediction?.let {
                    Text("AI 缺陷模式 · ${it.labelZh}（${it.label}）", style = MaterialTheme.typography.titleSmall)
                }
                plan.comparison?.let { ThresholdComparison(it) }
                GlassButton({
                    viewModel.prepareReport()?.let { filename ->
                        runCatching { reportPicker.launch(filename) }.onFailure { viewModel.export(null) }
                    }
                }, Modifier.fillMaxWidth(), enabled = !state.busy, prominent = true) { Text("导出测试 PDF") }
                GlassTextButton(viewModel::reset, enabled = !state.busy) { Text("新建测试") }
            }
        } else if (plan != null) {
            GlassTextButton(viewModel::reset) { Text("新建测试") }
        }
        state.message?.let { Text(it, color = colors.primary) }
        state.error?.let { Text(it, color = colors.error) }
        if (!state.running && state.finished && plan != null) {
            key(plan) { WaferTestResults(plan) }
        }
    }
}

private fun parseTestLimit(text: String) =
    text.replace(',', '.').toDoubleOrNull()?.takeIf { it in WaferTestPlan.simulationLimits }

@Composable
private fun TestLimitField(label: String, value: String, onChange: (String) -> Unit, valid: Boolean) {
    OutlinedTextField(value, { onChange(it.take(8)) }, label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = !valid, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ThresholdComparison(comparison: WaferTestComparison) {
    var showChanges by remember(comparison) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val direction = if (comparison.becamePass) "不合格 → 合格" else "合格 → 不合格"
    Text("阈值对比", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ThresholdMetric("基准上限", comparison.baselineLimit, comparison.baselineYield,
            comparison.baselinePassed, comparison.total, Modifier.weight(1f))
        ThresholdMetric("对比上限", comparison.comparisonLimit, comparison.comparisonYield,
            comparison.comparisonPassed, comparison.total, Modifier.weight(1f))
    }
    val delta = String.format(Locale.ROOT, "%+.2f", comparison.yieldDeltaPoints)
    Text("良率变化 $delta 个百分点", color = colors.primary, style = MaterialTheme.typography.titleSmall)
    if (comparison.changedDies.isEmpty()) {
        Text("两种上限下，所有芯片的判定相同。", style = MaterialTheme.typography.bodySmall)
    } else {
        Text("判定变化 ${comparison.changedDies.size} 颗 · $direction",
            style = MaterialTheme.typography.bodySmall)
        GlassTextButton({ showChanges = true }) { Text("查看变化位置") }
    }
    if (showChanges) AlertDialog(
        onDismissRequest = { showChanges = false },
        title = { Text("判定变化位置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(direction, style = MaterialTheme.typography.labelLarge)
                LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(comparison.changedDies) { die ->
                        val reading = String.format(Locale.ROOT, "%.2f µA", die.reading)
                        Text("第 ${die.row} 行 · 第 ${die.col} 列 · $reading")
                    }
                }
            }
        },
        confirmButton = { GlassTextButton({ showChanges = false }) { Text("关闭") } }
    )
}

@Composable
private fun ThresholdMetric(label: String, limit: Double, yield: Double, passed: Int, total: Int, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text("${testLimitText(limit)} µA", style = MaterialTheme.typography.titleMedium)
        val rate = String.format(Locale.ROOT, "%.2f%%", yield * 100)
        Text("$rate · 合格 $passed/$total", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun WaferTestHistoryDialog(history: WaferTestHistoryUiState, onDismiss: () -> Unit, onSelect: (Long) -> Unit) {
    val entries = history.entries
    AlertDialog(onDismissRequest = onDismiss, title = { Text("测试历史") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("保留最近 ${WaferTestHistory.Capacity} 次完成的测试与回放。", style = MaterialTheme.typography.bodySmall)
            when {
                history.error != null -> Text(history.error, color = MaterialTheme.colorScheme.error)
                entries == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                entries.isEmpty() -> Text("还没有已完成的测试。")
                else -> LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        val yield = String.format(Locale.ROOT, "%.2f%%", entry.yieldRate * 100)
                        val source = if (entry.mode == WaferTestPlan.SIMULATION) "仿真数据" else entry.mode
                        StudioAction(entry.waferName, "$source · 良率 $yield · ${testDate.format(entry.completedAt)}",
                            StudioIcon.DATA, { onSelect(entry.id) })
                    }
                }
            }
        }
    }, confirmButton = { GlassTextButton(onDismiss) { Text("关闭") } })
}

@Composable
private fun WaferTestResults(plan: WaferTestPlan) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf(WaferMapFilter.ALL) }
    var guides by remember { mutableStateOf(false) }
    GlassSegmentedTabs(resultTabs, tab, { tab = it })
    if (tab == 0) {
        WaferExplorer(plan.wafer, filter, showRegionGuides = guides, onToggleRegionGuides = { guides = !guides },
            selectedDetail = { row, col -> plan.readingAt(row, col)?.let {
                String.format(Locale.ROOT, "模拟漏电流 %.2f µA", it)
            } },
            onFilterChange = { filter = it })
    } else {
        ReportCharts(plan.wafer)
    }
}

@Composable
private fun WaferTestScan(plan: WaferTestPlan, completed: Int) {
    val colors = MaterialTheme.colorScheme
    val pending = colors.onSurfaceVariant.copy(alpha = 0.28f).toArgb()
    val wafer = plan.wafer
    val images by produceState<Pair<ImageBitmap, ImageBitmap>?>(null, plan, pending) {
        value = withContext(Dispatchers.Default) {
            val wait = IntArray(wafer.rows * wafer.cols)
            val done = IntArray(wait.size)
            for (index in plan.dieIndices) {
                wait[index] = pending
                done[index] = if (plan.statusAt(index) == 1) passColor.toArgb() else failColor.toArgb()
            }
            Bitmap.createBitmap(wait, wafer.cols, wafer.rows, Bitmap.Config.ARGB_8888).asImageBitmap() to
                Bitmap.createBitmap(done, wafer.cols, wafer.rows, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
    Canvas(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(20.dp))
        .background(colors.surfaceContainer).testTag("wafer-test-scan").semantics { contentDescription = "测试进度晶圆图" }) {
        val (wait, done) = images ?: return@Canvas
        val cell = minOf((size.width - 24.dp.toPx()) / wafer.cols, (size.height - 24.dp.toPx()) / wafer.rows)
        val scaled = IntSize((wafer.cols * cell).roundToInt(), (wafer.rows * cell).roundToInt())
        if (scaled.width <= 0 || scaled.height <= 0) return@Canvas
        val offset = IntOffset(((size.width - scaled.width) / 2).roundToInt(), ((size.height - scaled.height) / 2).roundToInt())
        drawImage(wait, dstOffset = offset, dstSize = scaled, filterQuality = FilterQuality.None)
        if (completed == 0) return@Canvas
        val last = plan.dieIndices[completed - 1]
        val rowHeight = scaled.height.toFloat() / wafer.rows
        val rowTop = offset.y + (last / wafer.cols) * rowHeight
        val rowRight = offset.x + (last % wafer.cols + 1) * scaled.width.toFloat() / wafer.cols
        clipRect(bottom = rowTop) {
            drawImage(done, dstOffset = offset, dstSize = scaled, filterQuality = FilterQuality.None)
        }
        clipRect(top = rowTop, bottom = rowTop + rowHeight, right = rowRight) {
            drawImage(done, dstOffset = offset, dstSize = scaled, filterQuality = FilterQuality.None)
        }
    }
}

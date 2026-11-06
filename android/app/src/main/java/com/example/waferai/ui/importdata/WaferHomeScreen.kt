package com.example.waferai.ui.importdata

import com.example.waferai.ui.theme.GlassSegmentedTabs
import com.example.waferai.ui.theme.GlassFilterChip as FilterChip
import com.example.waferai.ui.theme.GlassTextButton as TextButton
import com.example.waferai.ui.theme.GlassIconButton as IconButton
import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.example.waferai.ui.theme.UiText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.waferai.ui.theme.LocalFloatingNavigationPadding
import androidx.compose.foundation.text.selection.SelectionContainer
import com.example.waferai.ui.settings.AiProfilePicker
import com.example.waferai.data.ai.AiProfilesState
import com.example.waferai.data.analysis.WaferMapFilter
import com.example.waferai.data.local.DemoWafer
import com.example.waferai.data.local.WaferOverview
import com.example.waferai.ui.theme.SectionHeading
import com.example.waferai.ui.theme.WorkspaceMetric
import com.example.waferai.ui.theme.WaferRecordCard
import com.example.waferai.ui.theme.YieldRing
import com.example.waferai.ui.theme.StatusPill
import com.example.waferai.ui.theme.ActionTile
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import com.example.waferai.ui.theme.GlassButton
import com.example.waferai.ui.theme.BackButton
import com.example.waferai.ui.theme.StudioHeader
import com.example.waferai.ui.theme.StudioGlyph
import com.example.waferai.ui.theme.TabMotion
import com.example.waferai.ui.theme.tabTransition
import com.example.waferai.ui.theme.LocalEnglish
import com.example.waferai.ui.theme.StudioIcon
import com.example.waferai.ui.theme.StudioPanel
import com.example.waferai.ui.theme.WaferArtwork
import java.util.Locale

private enum class WaferPanel(val label: String) { MAP("晶圆图"), DETECT("AI 检测"), REPORT("分析报告") }

private val waferPanelLabels = WaferPanel.entries.map { it.label }

@Composable
fun WaferHomeScreen(
    importState: ImportUiState,
    onOpenConverter: () -> Unit,
    tabMotion: TabMotion,
    guideState: GuideUiState,
    onSaveGuideFile: (com.example.waferai.data.local.GuideFile) -> Unit,
    onSelectFile: () -> Unit,
    onSelectImage: () -> Unit,
    onPredict: () -> Unit,
    onPredictTrained: (() -> Unit)?,
    onExportReport: () -> Unit,
    onOpenSettings: () -> Unit,
    aiProfiles: AiProfilesState,
    aiSaving: Boolean,
    aiError: String?,
    onSelectAiProfile: (String) -> Unit,
    onLoadDemo: (DemoWafer) -> Unit,
    onAnalyze: () -> Unit,
    analysisPreset: com.example.waferai.data.ai.AnalysisPreset,
    onAnalysisPreset: (com.example.waferai.data.ai.AnalysisPreset) -> Unit,
    onCancelAnalysis: () -> Unit,
    onChat: () -> Unit,
    mapFilter: WaferMapFilter,
    onMapFilterChange: (WaferMapFilter) -> Unit,
    showRegionGuides: Boolean,
    onToggleRegionGuides: () -> Unit,
    yieldThreshold: Int,
    overview: WaferOverview?,
    onHistory: () -> Unit,
    onOpenWafer: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    val busy=importState.loading || importState.predicting || importState.exporting || importState.analyzing
    var showGuide by rememberSaveable { mutableStateOf(false) }
    var showDemoPicker by rememberSaveable { mutableStateOf(false) }
    var panel by rememberSaveable(importState.wafer?.id) { mutableStateOf(WaferPanel.MAP) }
    val scroll = rememberScrollState()
    val wafer = importState.wafer
    val computedSummary by androidx.compose.runtime.produceState<Pair<com.example.waferai.data.analysis.MatrixIdentity, com.example.waferai.data.analysis.WaferSummary>?>(null, com.example.waferai.data.analysis.MatrixIdentity(wafer), yieldThreshold) {
        value = wafer?.let { current -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            com.example.waferai.data.analysis.MatrixIdentity(current) to com.example.waferai.data.analysis.WaferSummary.calculate(current, yieldThreshold)
        } }
    }
    val summary = computedSummary?.takeIf { it.first == com.example.waferai.data.analysis.MatrixIdentity(wafer) && it.second.threshold == yieldThreshold }?.second
    var focusId by rememberSaveable(wafer?.id) { mutableStateOf<String?>(null) }
    var focusRevision by rememberSaveable(wafer?.id) { androidx.compose.runtime.mutableIntStateOf(0) }
    val focus = summary?.findings?.firstOrNull { it.id == focusId }
    LaunchedEffect(panel, wafer?.id) { if (focusId == null) scroll.scrollTo(0) }
    if (showDemoPicker) DemoWaferPicker(busy, onSelect = { sample ->
        showDemoPicker = false
        onLoadDemo(sample)
    }, onDismiss = { showDemoPicker = false })

    if (showGuide) WaferImportGuide(guideState, busy, onSaveGuideFile,
        onDemo = { showGuide = false; showDemoPicker = true },
        onData = { showGuide = false; onSelectFile() },
        onImage = { showGuide = false; onSelectImage() }, onDismiss = { showGuide = false })

    Column(
        modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 12.dp).padding(bottom = LocalFloatingNavigationPadding.current),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        onBack?.let { BackButton(it) }
        StudioHeader("晶圆工作台", "WAFERAI / WORKSPACE", StudioIcon.WAFER, compact = true, action = {
            val label = if (LocalEnglish.current) "Convert wafer file" else "晶圆文件转换"
            IconButton(onOpenConverter, modifier = Modifier.semantics { contentDescription = label }) {
                StudioGlyph(StudioIcon.CONVERT, Modifier.size(19.dp), Color(0xFFDB9754))
            }
        })
        if (wafer == null) {
            WorkspaceIntro(onSelectFile, onSelectImage, { showDemoPicker = true }, { showGuide = true }, enabled = !busy)
            StudioPanel(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    WorkspaceMetric("已存晶圆", overview?.wafers?.toString() ?: "—", Modifier.weight(1f))
                    WorkspaceMetric("批次", overview?.lots?.toString() ?: "—", Modifier.weight(1f))
                    WorkspaceMetric("总体良率", overview?.yieldRate?.let { String.format(Locale.CHINA, "%.1f%%", it * 100) } ?: "—", Modifier.weight(1f))
                }
            }
            SectionHeading("继续分析", "历史记录", onHistory)
            if (overview?.recent.isNullOrEmpty()) Text("导入数据或载入演示，检测结果会自动保存在手机。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            overview?.recent?.take(3)?.forEach { record -> WaferRecordCard(record, { onOpenWafer(record.id) }, threshold = yieldThreshold) }
        }
        if (importState.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("正在读取并导入晶圆数据…")
        }
        importState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (wafer != null) {
            StudioPanel(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    YieldRing(wafer.yieldRate, diameter = 52.dp,
                        color = if (100L * wafer.goodCount < yieldThreshold.toLong() * wafer.totalCount)
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(wafer.waferCode, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(wafer.lotCode, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(wafer.filename, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("合格 ${wafer.goodCount} · 不合格 ${wafer.badCount} · 总数 ${wafer.totalCount}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            GlassSegmentedTabs(waferPanelLabels, panel.ordinal, { panel = WaferPanel.entries[it] })
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            AnimatedContent(
                targetState = panel,
                modifier = Modifier.fillMaxWidth().clipToBounds(),
                transitionSpec = {
                    val direction = targetState.ordinal.compareTo(initialState.ordinal)
                    tabTransition(if (rtl) -direction else direction, tabMotion,
                        ValueAnimator.areAnimatorsEnabled()).using(null)
                },
                label = "inspection panels"
            ) { displayedPanel ->
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (displayedPanel == WaferPanel.MAP) {
                        WaferExplorer(wafer, if (focus != null) WaferMapFilter.ALL else mapFilter, showRegionGuides, onToggleRegionGuides,
                            onFilterChange = { focusId = null; onMapFilterChange(it) }, focus = focus, focusRevision = focusRevision,
                            onClearFocus = { focusId = null }, preparedFailures = summary?.findings?.firstOrNull()?.indices)
                        summary?.let { current ->
                            key(wafer.id) {
                                WaferSummaryCard(current, importState.prediction) { finding ->
                                    focusId = finding.id
                                    focusRevision++
                                }
                            }
                        }
                        GlassButton({ panel = WaferPanel.DETECT }, Modifier.fillMaxWidth(), prominent = true) { Text("进入 AI 检测") }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onSelectFile, enabled = !busy) { Text("更换数据") }
                            TextButton(onSelectImage, enabled = !busy) { Text("导入图片") }
                            TextButton({ showDemoPicker = true }, enabled = !busy) { Text("演示样本") }
                        }
                        TextButton({ showGuide = true }, enabled = !busy) { Text("一键导入晶圆指引") }
                        if (wafer.lotCode == "IMAGE") Text("良率由图片网格推算，请对照原图核对行列和颜色。", style = MaterialTheme.typography.bodySmall)
                    }
                    if (displayedPanel == WaferPanel.DETECT) {
                        StatusPill(if (importState.prediction == null) "等待检测" else "检测结果已保存")
                        Text(importState.prediction?.labelZh ?: "AI 缺陷检测", style = MaterialTheme.typography.headlineSmall)
                        importState.prediction?.let { prediction ->
                            Text(prediction.label + " · 模型得分 " + scorePercent(prediction.score),
                                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                        }
                        Text("使用手机本地模型识别晶圆缺陷模式。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlassButton(onPredict, enabled = !busy, prominent = true, modifier = Modifier.weight(1f)) {
                                Text(if (importState.predicting) "正在检测…" else "开始晶圆检测")
                            }
                            GlassButton({ panel = WaferPanel.REPORT }, enabled = !busy, modifier = Modifier.weight(1f)) {
                                Text("查看分析报告")
                            }
                        }
                        onPredictTrained?.let { predict ->
                            GlassButton(predict, Modifier.fillMaxWidth(), enabled = !busy) { Text("用训练模型检测") }
                        }
                        importState.inspection?.let { inspection ->
                            StudioPanel(Modifier.fillMaxWidth()) {
                                Text("检测结论", style = MaterialTheme.typography.titleMedium)
                                val needsReview = 100L * wafer.goodCount < inspection.threshold.toLong() * wafer.totalCount
                                Text(if (needsReview) "低于检测阈值 · 待排查" else "达到检测阈值",
                                    color = if (needsReview) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                                Text("检测时良率阈值 ${inspection.threshold}%", style = MaterialTheme.typography.bodySmall)
                                Text("检测结果已纳入历史趋势。", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (importState.predicting || importState.exporting) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        importState.exportMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                        importState.exportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        importState.predictionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        importState.prediction?.let { prediction ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("缺陷模式识别", style = MaterialTheme.typography.titleMedium)
                                    Text("得分表示模型对当前分类的倾向。", style = MaterialTheme.typography.bodySmall)
                                    if (prediction.label == "none") {
                                        Text("该类别表示未识别到特定缺陷模式，芯片良率见本片统计。")
                                    }
                                    Text("模型 ${prediction.modelVersion}", style = MaterialTheme.typography.bodySmall)
                                    importState.predictionNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    Text("推理位置：手机本地",style=MaterialTheme.typography.bodySmall)
                                    val topScores = prediction.scores.sortedByDescending { it.score }.take(3)
                                    if (topScores.isNotEmpty()) {
                                        HorizontalDivider()
                                        Text("模型得分前 3 项", style = MaterialTheme.typography.titleSmall)
                                        topScores.forEach { candidate ->
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text("${candidate.labelZh}（${candidate.label}）", modifier = Modifier.weight(1f),
                                                    style = MaterialTheme.typography.bodyMedium)
                                                Text(scorePercent(candidate.score), style = MaterialTheme.typography.bodyMedium)
                                            }
                                            LinearProgressIndicator(
                                                progress = { candidate.score.toFloat().coerceIn(0f, 1f) },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        TextButton(onChat) { Text("在 AI 对话中追问") }
                    }
                    if (displayedPanel == WaferPanel.REPORT) {
                        if (summary == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else key(wafer.id) {
                            ReportCharts(wafer, prepared = summary.report)
                            SpatialStatsCard(wafer, showRings = false, prepared = summary)
                        }
                        StudioPanel(Modifier.fillMaxWidth()) {
                            Text("检测报告", style = MaterialTheme.typography.titleLarge)
                            Text("晶圆图、良率统计与已保存的识别结果。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            GlassButton(onExportReport, Modifier.fillMaxWidth(), enabled = !busy, prominent = true) { Text(if (importState.exporting) "正在导出…" else "导出 PDF 报告") }
                            importState.exportMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                            importState.exportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                        if (importState.prediction != null) {
                            AiProfilePicker(aiProfiles, onSelectAiProfile, onOpenSettings, enabled = !busy && !aiSaving)
                            aiError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            Text("AI 解析模板", style = MaterialTheme.typography.titleMedium)
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                com.example.waferai.data.ai.AnalysisPreset.entries.forEach { preset ->
                                    FilterChip(analysisPreset == preset,
                                        { onAnalysisPreset(preset) }, enabled = !busy,
                                        label = { Text(preset.title) })
                                }
                            }
                            Text("数字由本机事实填入；模型解释与假设仍需复核。切换模板后点击生成，已有解析保留。", style = MaterialTheme.typography.bodySmall)
                            GlassButton(
                                onAnalyze, enabled = !busy && !aiSaving && aiProfiles.active != null,
                                prominent = true, modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (importState.analysis == null) "AI 解析（联网）" else "重新解析（联网）")
                            }
                            aiProfiles.active?.let { profile ->
                                Text("解析会向 ${profile.provider.title} 发送统计、识别摘要及匹配的知识条目，可能产生 API 费用。",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if(importState.analyzing) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text("正在生成解析…")
                                TextButton(onCancelAnalysis) { Text("取消解析") }
                            }
                            importState.analysisError?.let {
                                Text(it,color=MaterialTheme.colorScheme.error)
                                TextButton(onOpenSettings,enabled=!busy) { Text("管理模型") }
                            }
                            importState.analysis?.let { analysis ->
                                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Text("AI 解析",style=MaterialTheme.typography.titleLarge)
                                    SelectionContainer { androidx.compose.material3.Text(analysis.content) }
                                    summary?.findings?.filter { it.indices.isNotEmpty() && analysis.content.contains(it.detail) }?.forEach { finding ->
                                        TextButton({ focusId = finding.id; focusRevision++; panel = WaferPanel.MAP }) {
                                            Text("定位证据：${finding.title}")
                                        }
                                    }
                                    Text("${analysis.model}\n${analysis.createdAt}",style=MaterialTheme.typography.bodySmall)
                                    Text("已保存在本机。可能原因属于待验证假设，应结合电测和实际工艺记录复核。",style=MaterialTheme.typography.bodySmall)
                                } }
                            }
                        }
                    }
                }
            }
            importState.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun scorePercent(score: Double): String = when {
    score > 0.9999 -> ">99.99%"
    score > 0.0 && score < 0.0001 -> "<0.01%"
    else -> String.format(Locale.CHINA, "%.2f%%", score * 100)
}

@Composable
private fun WorkspaceIntro(onImport: () -> Unit, onImage: () -> Unit, onDemo: () -> Unit, onGuide: () -> Unit, enabled: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(26.dp)
    val art = if (LocalDensity.current.fontScale > 1.25f) 84.dp else 124.dp
    Column(Modifier.fillMaxWidth().clip(shape)
        .background(Brush.linearGradient(listOf(scheme.primaryContainer, scheme.surfaceContainer)))
        .border(0.5.dp, scheme.outlineVariant.copy(alpha = 0.18f), shape).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill("端侧智能检测")
                Text("看清每一片晶圆", style = MaterialTheme.typography.headlineSmall)
                Text("从原始数据到缺陷洞察", style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant)
            }
            WaferArtwork(Modifier.size(art))
        }
        GlassButton(onImport, Modifier.fillMaxWidth(), enabled = enabled, prominent = true) { Text("导入晶圆数据") }
        Text("STDF · ATDF · CSV · JSON · Parquet", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionTile("识别晶圆图片", "选择图片并校准网格", StudioIcon.PHOTO, onImage, Modifier.weight(1f), enabled)
        ActionTile("载入演示晶圆", "27 张真实样本与 3 张人工演示", StudioIcon.WAFER, onDemo, Modifier.weight(1f), enabled)
    }
    TextButton(onGuide, enabled = enabled) { Text("一键导入晶圆指引") }
}


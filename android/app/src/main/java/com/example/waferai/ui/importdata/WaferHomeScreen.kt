package com.example.waferai.ui.importdata

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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import com.example.waferai.ui.theme.UiText as Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
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
import com.example.waferai.ui.theme.StudioIcon
import com.example.waferai.ui.theme.StudioPanel
import com.example.waferai.ui.theme.WaferArtwork
import java.util.Locale

private enum class WaferPanel(val label: String) { MAP("晶圆图"), DETECT("AI 检测"), REPORT("分析报告") }

@Composable
fun WaferHomeScreen(
    importState: ImportUiState,
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
    var showDemoPicker by rememberSaveable { mutableStateOf(false) }
    var panel by rememberSaveable(importState.wafer?.id) { mutableStateOf(WaferPanel.MAP) }
    val scroll = rememberScrollState()
    LaunchedEffect(panel, importState.wafer?.id) { scroll.scrollTo(0) }
    if (showDemoPicker) {
        AlertDialog(
            onDismissRequest = { showDemoPicker = false },
            title = { Text("选择演示晶圆") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("这些样本为人工构造，用于演示晶圆图和分析流程。")
                    DemoWafer.entries.forEach { sample ->
                        TextButton(
                            onClick = {
                                showDemoPicker = false
                                onLoadDemo(sample)
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(sample.title, style = MaterialTheme.typography.titleSmall)
                                Text(sample.description, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton({ showDemoPicker = false }) { Text("关闭") } }
        )
    }
    Column(
        modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 12.dp).padding(bottom = LocalFloatingNavigationPadding.current),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        onBack?.let { BackButton(it) }
        StudioHeader("晶圆工作台", "WAFERAI / WORKSPACE", StudioIcon.WAFER)
        val wafer = importState.wafer
        if (wafer == null) {
            WorkspaceIntro(onSelectFile, onSelectImage, { showDemoPicker = true }, enabled = !busy)
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
            TabRow(panel.ordinal, containerColor = MaterialTheme.colorScheme.surface) {
                WaferPanel.entries.forEach { option -> Tab(panel == option, { panel = option }, text = { Text(option.label) }) }
            }
            if (panel == WaferPanel.MAP) {
                WaferExplorer(wafer, mapFilter, showRegionGuides, onToggleRegionGuides, onMapFilterChange)
                GlassButton({ panel = WaferPanel.DETECT }, Modifier.fillMaxWidth(), prominent = true) { Text("进入 AI 检测") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onSelectFile, enabled = !busy) { Text("更换数据") }
                    TextButton(onSelectImage, enabled = !busy) { Text("导入图片") }
                    TextButton({ showDemoPicker = true }, enabled = !busy) { Text("演示样本") }
                }
                if (wafer.lotCode == "IMAGE") Text("良率由图片网格推算，请对照原图核对行列和颜色。", style = MaterialTheme.typography.bodySmall)
            }
            if (panel == WaferPanel.DETECT) {
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
            if (panel == WaferPanel.REPORT) {
                key(wafer.id) { SpatialStatsCard(wafer) }
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
                    GlassButton(
                        onAnalyze, enabled = !busy && !aiSaving && aiProfiles.active != null,
                        prominent = true, modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (importState.analysis == null) "AI 解析（联网）" else "重新解析（联网）")
                    }
                    aiProfiles.active?.let { profile ->
                        Text("解析会向 ${profile.provider.title} 发送统计与识别摘要，可能产生 API 费用。",
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
                            Text("${analysis.model}\n${analysis.createdAt}",style=MaterialTheme.typography.bodySmall)
                            Text("已保存在本机。可能原因属于待验证假设，应结合电测和实际工艺记录复核。",style=MaterialTheme.typography.bodySmall)
                        } }
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
private fun WorkspaceIntro(onImport: () -> Unit, onImage: () -> Unit, onDemo: () -> Unit, enabled: Boolean) {
    var showHelp by rememberSaveable { mutableStateOf(false) }
    if (showHelp) AlertDialog(
        onDismissRequest = { showHelp = false }, title = { Text("导入说明") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("JSON 须包含 lot_code、wafer_code 和 map。")
            Text("CSV 与 Parquet 使用 lot_code,wafer_code,row,col,status。行列从 1 开始，未列出的位置为 0。")
            Text("矩阵编码：0 为非芯片区域，1 为合格芯片，2 为不合格芯片。")
            Text("每个文件一张晶圆。CSV、JSON 最大 2 MB，STDF、ATDF、Parquet 最大 8 MB。")
            Text("图片支持 PNG、JPEG、WebP、BMP、GIF 首帧及设备可解码的 HEIF、AVIF，最大 10 MB。")
        } }, confirmButton = { TextButton({ showHelp = false }) { Text("关闭") } }
    )
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(26.dp)
    val art = if (LocalDensity.current.fontScale > 1.25f) 84.dp else 124.dp
    Column(Modifier.fillMaxWidth().clip(shape)
        .background(Brush.linearGradient(listOf(scheme.primaryContainer, scheme.surfaceContainer)))
        .border(1.dp, scheme.outlineVariant.copy(alpha = 0.75f), shape).padding(18.dp),
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
        ActionTile("载入演示晶圆", "体验缺陷与良率分析", StudioIcon.WAFER, onDemo, Modifier.weight(1f), enabled)
    }
    TextButton({ showHelp = true }) { Text("查看导入说明") }
}


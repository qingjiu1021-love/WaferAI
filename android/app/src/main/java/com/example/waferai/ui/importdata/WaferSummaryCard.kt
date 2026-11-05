package com.example.waferai.ui.importdata

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.waferai.data.analysis.WaferFinding
import com.example.waferai.data.analysis.WaferSummary
import com.example.waferai.data.remote.WaferPrediction
import com.example.waferai.ui.theme.StudioPanel
import com.example.waferai.ui.theme.UiText as Text

@Composable internal fun WaferSummaryCard(summary: WaferSummary, prediction: WaferPrediction?, onLocate: (WaferFinding) -> Unit) {
    var expanded by rememberSaveable(summary.threshold) { mutableStateOf(true) }
    StudioPanel(Modifier.fillMaxWidth().testTag("wafer-summary")) {
        Text("摘要", style = MaterialTheme.typography.titleLarge)
        Text("整片统计 · 本机自动生成 · 筛选仅改变图中显示", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        summary.facts.take(3).forEach { Text(it.text, style = MaterialTheme.typography.bodyMedium) }
        HorizontalDivider()
        Text("突出发现", style = MaterialTheme.typography.titleMedium)
        summary.findings.forEach { finding ->
            TextButton({ onLocate(finding) }, enabled = finding.indices.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("finding-${finding.id}")) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(finding.title, style = MaterialTheme.typography.titleSmall)
                    if (expanded) Text(finding.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text("点击发现可定位并高亮。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton({ expanded = !expanded }) { Text(if (expanded) "收起摘要" else "展开完整摘要") }
        if (expanded) summary.facts.drop(3).forEach { Text(it.text, style = MaterialTheme.typography.bodySmall) }
        Text(prediction?.let { "CNN 模型输出：${it.labelZh}。模型判断与以上统计事实分别解读。" }
            ?: "尚未运行本次 CNN 检测；可进入 AI 检测。", style = MaterialTheme.typography.bodySmall)
        Text("分区与聚集仅描述空间位置，不是统计显著性或已确认工艺根因。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

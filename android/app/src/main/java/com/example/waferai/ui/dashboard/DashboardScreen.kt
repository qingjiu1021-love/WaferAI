package com.example.waferai.ui.dashboard

import com.example.waferai.ui.theme.GlassSegmentedTabs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.waferai.ui.theme.LocalFloatingNavigationPadding
import com.example.waferai.data.local.WaferOverview
import com.example.waferai.data.local.WaferYieldBand
import com.example.waferai.ui.theme.StudioAction
import com.example.waferai.ui.theme.StudioHeader
import com.example.waferai.ui.theme.StudioIcon
import com.example.waferai.ui.theme.StudioPanel
import com.example.waferai.ui.theme.StudioGlyph
import com.example.waferai.ui.theme.SectionHeading
import com.example.waferai.ui.theme.WorkspaceMetric
import com.example.waferai.ui.theme.ActionTile
import com.example.waferai.ui.theme.YieldRing
import com.example.waferai.ui.theme.WaferRecordCard
import com.example.waferai.ui.theme.UiText as Text
import java.util.Locale

@Composable
fun StatisticsScreen(
    data: WaferOverview?,
    error: String?,
    onHistory: () -> Unit,
    onLots: () -> Unit,
    onReviewYield: () -> Unit,
    onTrend: () -> Unit,
    onOpenWafer: (Int) -> Unit,
    onYieldBand: (WaferYieldBand) -> Unit,
    yieldThreshold: Int,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp).padding(bottom = LocalFloatingNavigationPadding.current),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StudioHeader("数据中心", "WAFERAI / INSIGHTS", StudioIcon.DATA, compact = true)
        GlassSegmentedTabs(listOf("数据总览", "历史记录"), 0, { if (it == 1) onHistory() })
        StudioPanel(Modifier.fillMaxWidth(), color = scheme.primaryContainer) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                data?.yieldRate?.let { YieldRing(it, diameter = 80.dp) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("总体良率", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    Text(data?.yieldRate?.let(::percent) ?: "—", style = MaterialTheme.typography.headlineLarge, color = scheme.primary)
                }
            }
            Text("总体良率按全部有效芯片加权计算。", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WorkspaceMetric("晶圆", data?.wafers?.toString() ?: "—", Modifier.weight(1f))
                WorkspaceMetric("批次", data?.lots?.toString() ?: "—", Modifier.weight(1f))
                WorkspaceMetric("有效芯片", data?.total?.toString() ?: "—", Modifier.weight(1f))
            }
        }
        error?.let { Text(it, color = scheme.error) }
        SectionHeading("分析工作区")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionTile("批次汇总", "跨晶圆查看批次表现", StudioIcon.WAFER, onLots, Modifier.weight(1f))
            ActionTile("检测趋势", "良率曲线与类别分布", StudioIcon.DATA, onTrend, Modifier.weight(1f))
        }
        StudioAction("低良率排查", "按阈值筛查并打开可疑晶圆。", StudioIcon.THINK, onReviewYield)
        data?.let { summary ->
            StudioPanel(Modifier.fillMaxWidth()) {
                Text("单张晶圆良率分布", style = MaterialTheme.typography.titleMedium)
                Text("点击良率区间查看对应晶圆。", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                WaferYieldBand.entries.forEachIndexed { index, band ->
                    val count = summary.yieldBands[index]
                    Column(
                        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "查看对应晶圆") {
                            onYieldBand(band)
                        }.padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(band.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text("$count 张", style = MaterialTheme.typography.labelLarge)
                            StudioGlyph(StudioIcon.ARROW, Modifier.size(16.dp), scheme.onSurfaceVariant)
                        }
                        LinearProgressIndicator(
                            progress = { if (summary.wafers == 0) 0f else count.toFloat() / summary.wafers },
                            modifier = Modifier.fillMaxWidth(),
                            color = if (band == WaferYieldBand.LOW) scheme.error else scheme.primary
                        )
                    }
                }
                Text("合格芯片 ${summary.good} · 不合格芯片 ${summary.bad}", color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        SectionHeading("最近导入", "查看全部", onHistory)
        if (data?.recent.isNullOrEmpty()) Text("导入晶圆后，这里会显示最近记录。", color = scheme.onSurfaceVariant)
        data?.recent?.forEach { wafer ->
            WaferRecordCard(wafer, { onOpenWafer(wafer.id) }, threshold = yieldThreshold)
        }
    }
}

private fun percent(value: Double) = String.format(Locale.CHINA, "%.2f%%", value * 100)

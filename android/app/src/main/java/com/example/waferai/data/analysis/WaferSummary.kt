package com.example.waferai.data.analysis

import com.example.waferai.data.remote.WaferDetail
import com.example.waferai.data.report.WaferReportData
import java.util.Locale

internal data class SummaryFact(val id: String, val text: String)
internal data class WaferFinding(val id: String, val title: String, val detail: String, val indices: IntArray)
internal data class WaferSummary(
    val facts: List<SummaryFact>, val findings: List<WaferFinding>, val threshold: Int,
    val report: WaferReportData, val clusters: WaferClusters
) {
    companion object {
        fun calculate(wafer: WaferDetail, threshold: Int = 90): WaferSummary {
            require(threshold in 1..100)
            val data = WaferReportData.calculate(wafer)
            val clusters = WaferClusters.calculate(wafer.map)
            fun pct(value: Double) = String.format(Locale.ROOT, "%.2f%%", value * 100)
            val facts = mutableListOf(
                SummaryFact("dimensions", "晶圆尺寸 ${wafer.rows} 行 × ${wafer.cols} 列"),
                SummaryFact("counts", "有效芯片 ${data.total}，合格 ${data.good}，不合格 ${data.bad}；非芯片区域 ${data.outside} 格"),
                SummaryFact("yield", "芯片良率 ${pct(data.yield)}，当前参考阈值 $threshold%"),
                SummaryFact("clusters", "按上下左右相邻统计，不合格连通区 ${clusters.count} 处")
            )
            data.rings.forEachIndexed { index, region -> facts += SummaryFact("ring_$index",
                "${region.name}：不合格 ${region.bad} / 有效 ${region.total}，不合格率 ${region.badRate?.let(::pct) ?: "无有效芯片"}") }
            clusters.largest?.let { c -> facts += SummaryFact("largest_cluster",
                "最大不合格连通区 ${c.size} 颗，第 ${c.firstRow}–${c.lastRow} 行、第 ${c.firstCol}–${c.lastCol} 列") }
            listOf("row_peak" to data.rows, "column_peak" to data.columns).forEach { (id, positions) ->
                positions.filter { it.rate != null }.maxByOrNull { it.rate!! }?.let { value ->
                    val unit = if (id == "row_peak") "行" else "列"
                    facts += SummaryFact(id, "逐${unit}最高不合格率：第 ${value.number} $unit，${pct(value.rate!!)}，不合格 ${value.bad}/${value.total}")
                }
            }
            val failures = failureIndices(wafer)
            val findings = mutableListOf(WaferFinding("yield", if (wafer.badCount == 0) "原始网格中无不合格芯片" else
                if (100L * data.good < threshold.toLong() * data.total) "良率低于参考阈值" else "良率达到参考阈值",
                facts.first { it.id == "yield" }.text, failures))
            if (wafer.badCount > 0) {
                data.rings.withIndex().filter { it.value.total > 0 }.maxByOrNull { it.value.badRate!! }?.let { top ->
                    val indices = failures.filter { WaferSpatialStats.ringAt(it / wafer.cols, it % wafer.cols, wafer.rows, wafer.cols) == top.index }.toIntArray()
                    findings += WaferFinding("ring_${top.index}", "${top.value.name}不合格率最高",
                        facts.first { it.id == "ring_${top.index}" }.text, indices)
                }
                clusters.largest?.let { c -> findings += WaferFinding("largest_cluster", "最大缺陷聚集区",
                    facts.first { it.id == "largest_cluster" }.text, c.members) }
            }
            return WaferSummary(facts, findings, threshold, data, clusters)
        }
    }
}

internal fun failureIndices(wafer: WaferDetail): IntArray {
    val indices = IntArray(wafer.badCount)
    var position = 0
    wafer.map.forEachIndexed { row, bins ->
        bins.forEachIndexed { col, bin -> if (bin == 2) indices[position++] = row * wafer.cols + col }
    }
    return indices
}

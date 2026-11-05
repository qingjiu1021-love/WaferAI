package com.example.waferai.data.report

import com.example.waferai.data.analysis.DieRegion
import com.example.waferai.data.analysis.WaferSpatialStats
import com.example.waferai.data.remote.WaferDetail

internal data class PositionRate(val number: Int, val total: Int, val bad: Int) {
    val rate: Double? get() = if (total == 0) null else bad.toDouble() / total
}

internal data class WaferReportData(val good: Int, val bad: Int, val outside: Int,
    val rings: List<DieRegion>, val rows: List<PositionRate>, val columns: List<PositionRate>) {
    val total get() = good + bad
    val yield get() = good.toDouble() / total
    companion object {
        fun calculate(wafer: WaferDetail): WaferReportData {
            val rowTotal = IntArray(wafer.rows)
            val rowBad = IntArray(wafer.rows)
            val colTotal = IntArray(wafer.cols)
            val colBad = IntArray(wafer.cols)
            val ringTotal = IntArray(3)
            val ringBad = IntArray(3)
            for (row in 0 until wafer.rows) {
                val bins = wafer.map[row]
                for (col in bins.indices) {
                    val bin = bins[col]
                    if (bin == 0) continue
                    val ring = WaferSpatialStats.ringAt(row, col, wafer.rows, wafer.cols)
                    rowTotal[row]++; colTotal[col]++; ringTotal[ring]++
                    if (bin == 2) { rowBad[row]++; colBad[col]++; ringBad[ring]++ }
                }
            }
            val bad = rowBad.sum()
            val total = rowTotal.sum()
            return WaferReportData(total - bad, bad, wafer.rows * wafer.cols - total,
                listOf("矩阵中心", "矩阵中部", "矩阵边缘").mapIndexed { i, name -> DieRegion(name, ringTotal[i], ringBad[i]) },
                List(wafer.rows) { PositionRate(it + 1, rowTotal[it], rowBad[it]) },
                List(wafer.cols) { PositionRate(it + 1, colTotal[it], colBad[it]) })
        }
    }
}

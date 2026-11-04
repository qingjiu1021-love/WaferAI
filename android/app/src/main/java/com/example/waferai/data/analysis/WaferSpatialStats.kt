package com.example.waferai.data.analysis

import com.example.waferai.data.remote.WaferDetail
import kotlin.math.sqrt

internal data class DieRegion(val name: String, val total: Int, val bad: Int) {
    val badRate: Double? get() = if (total > 0) bad.toDouble() / total else null
}

internal data class WaferSpatialStats(val rings: List<DieRegion>, val grid: List<DieRegion>) {
    companion object {
        const val CENTER_BOUNDARY = 0.35
        const val MIDDLE_BOUNDARY = 0.70

        fun calculate(wafer: WaferDetail): WaferSpatialStats {
            val ringNames = listOf("矩阵中心", "矩阵中部", "矩阵边缘")
            val ringTotal = IntArray(3)
            val ringBad = IntArray(3)
            val gridTotal = IntArray(9)
            val gridBad = IntArray(9)
            val centerX = (wafer.cols - 1) / 2.0
            val centerY = (wafer.rows - 1) / 2.0
            for (y in 0 until wafer.rows) {
                val dy = (y - centerY) / (wafer.rows / 2.0)
                for (x in 0 until wafer.cols) {
                    val bin = wafer.map[y][x]
                    if (bin == 0) continue
                    val dx = (x - centerX) / (wafer.cols / 2.0)
                    val radius = sqrt(dx * dx + dy * dy)
                    val ring = when {
                        radius < CENTER_BOUNDARY -> 0
                        radius < MIDDLE_BOUNDARY -> 1
                        else -> 2
                    }
                    val tile = (y * 3 / wafer.rows) * 3 + x * 3 / wafer.cols
                    ringTotal[ring]++
                    gridTotal[tile]++
                    if (bin == 2) {
                        ringBad[ring]++
                        gridBad[tile]++
                    }
                }
            }
            return WaferSpatialStats(
                ringNames.mapIndexed { index, name -> DieRegion(name, ringTotal[index], ringBad[index]) },
                List(9) { DieRegion("${it / 3 + 1}行${it % 3 + 1}列", gridTotal[it], gridBad[it]) }
            )
        }
    }
}

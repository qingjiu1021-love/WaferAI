package com.example.waferai.data.local

import com.example.waferai.data.remote.WaferDetail
import java.math.BigDecimal
import kotlin.math.round
import kotlin.random.Random

internal data class WaferTestSnapshot(
    val wafer: WaferDetail,
    val mode: String,
    val source: String,
    val readings: List<List<Double?>>?,
    val limits: WaferTestLimits? = null
)

internal data class WaferTestLimits(val baseline: Double, val comparison: Double)

data class WaferTestChangedDie(val row: Int, val col: Int, val reading: Double)

data class WaferTestComparison(
    val baselineLimit: Double,
    val comparisonLimit: Double,
    val baselinePassed: Int,
    val comparisonPassed: Int,
    val total: Int,
    val changedDies: List<WaferTestChangedDie>
) {
    val baselineYield get() = baselinePassed.toDouble() / total
    val comparisonYield get() = comparisonPassed.toDouble() / total
    val yieldDeltaPoints get() = 100.0 * (comparisonPassed - baselinePassed) / total
    val becamePass get() = comparisonPassed > baselinePassed
}

internal fun testLimitText(limit: Double) = BigDecimal.valueOf(limit).stripTrailingZeros().toPlainString()

internal class WaferTestPlan private constructor(
    val wafer: WaferDetail,
    val mode: String,
    val source: String,
    private val readings: Array<DoubleArray>? = null,
    private val limits: WaferTestLimits? = null
) {
    val dieIndices = IntArray(wafer.totalCount).also { indices ->
        var count = 0
        wafer.map.forEachIndexed { row, bins ->
            bins.forEachIndexed { col, bin -> if (bin != 0) indices[count++] = row * wafer.cols + col }
        }
    }

    fun statusAt(index: Int): Int = wafer.map[index / wafer.cols][index % wafer.cols]

    fun readingAt(row: Int, col: Int): Double? = readings?.get(row)?.get(col)?.takeIf { it.isFinite() }

    val comparison: WaferTestComparison? = limits?.let { pair ->
        val changed = mutableListOf<WaferTestChangedDie>()
        var passed = 0
        for (index in dieIndices) {
            val row = index / wafer.cols
            val col = index % wafer.cols
            val reading = requireNotNull(readingAt(row, col))
            val passes = reading <= pair.comparison
            if (passes) passed++
            if (passes != (statusAt(index) == 1)) {
                changed.add(WaferTestChangedDie(row + 1, col + 1, reading))
            }
        }
        WaferTestComparison(pair.baseline, pair.comparison, wafer.goodCount, passed, wafer.totalCount, changed)
    }

    fun snapshot() = WaferTestSnapshot(wafer, mode, source,
        readings?.map { row -> row.map { it.takeIf(Double::isFinite) } }, limits)

    companion object {
        const val SIMULATION = "模拟测量"
        const val REPLAY = "历史结果回放"
        val simulationLimits = 0.1..100.0

        fun restore(saved: WaferTestSnapshot) = WaferTestPlan(saved.wafer, saved.mode, saved.source,
            saved.readings?.map { row -> row.map { it ?: Double.NaN }.toDoubleArray() }?.toTypedArray(), saved.limits)

        fun simulated(upperLimit: Double, comparisonLimit: Double? = null): WaferTestPlan {
            require(upperLimit in simulationLimits && (comparisonLimit == null || comparisonLimit in simulationLimits)) {
                "请输入 0.1～100 µA 的模拟漏电流上限。"
            }
            val random = Random(811)
            val width = 21
            val readings = Array(width) { row ->
                DoubleArray(width) { col ->
                    val x = col - width / 2
                    val y = row - width / 2
                    val distance = x * x + y * y
                    when {
                        distance > 100 -> Double.NaN
                        distance <= 10 -> round(random.nextDouble(10.5, 18.0) * 100) / 100
                        else -> round(random.nextDouble(0.8, 8.5) * 100) / 100
                    }
                }
            }
            val map = readings.map { row ->
                row.map { value -> when {
                    value.isNaN() -> 0
                    value <= upperLimit -> 1
                    else -> 2
                } }
            }
            val wafer = parsedWafer("SIM-LEAKAGE", "W01", map, "WaferAI 模拟漏电流", WaferSource.SIMULATION).detail
            val limit = testLimitText(upperLimit)
            return WaferTestPlan(wafer, SIMULATION,
                "软件生成的漏电流读数 · 合格范围 0～$limit µA（含边界）", readings,
                comparisonLimit?.let { WaferTestLimits(upperLimit, it) })
        }

        fun replay(wafer: WaferDetail, sample: DemoWafer? = null): WaferTestPlan = WaferTestPlan(
            wafer, REPLAY, if (sample?.isReal == true) {
                "WM-811K · ${wafer.lotCode} / ${wafer.waferCode} · 按原有合格状态回放，无电参数读数"
            } else {
                "已载入晶圆 · ${wafer.lotCode} / ${wafer.waferCode} · 按原有合格状态回放，无电参数读数"
            }
        )
    }
}

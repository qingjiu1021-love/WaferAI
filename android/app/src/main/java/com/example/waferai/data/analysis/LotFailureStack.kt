package com.example.waferai.data.analysis

import com.example.waferai.data.remote.WaferDetail

internal data class StackedWafer(val id: Int, val code: String, private val bins: ByteArray) {
    fun failedAt(index: Int) = bins[index] == 2.toByte()
}

internal class LotFailureStack private constructor(
    val rows: Int,
    val cols: Int,
    val wafers: List<StackedWafer>,
    private val failures: IntArray,
    val repeatedPositions: Int,
    val peakIndex: Int?
) {
    // -1 denotes a position outside the shared die mask.
    fun failuresAt(index: Int) = failures[index]

    companion object {
        const val MAX_WAFERS = 30

        fun calculate(lotCode: String, input: Sequence<WaferDetail>, checkActive: () -> Unit = {}): LotFailureStack {
            val iterator = input.iterator()
            require(iterator.hasNext()) { "请至少选择两片晶圆。" }
            val first = iterator.next()
            val rows = first.rows
            val cols = first.cols
            val source = first.sourceKind
            val failures = IntArray(rows * cols) { index -> if (first.map[index / cols][index % cols] == 0) -1 else 0 }
            val wafers = mutableListOf<StackedWafer>()
            val ids = mutableSetOf<Int>()

            fun append(wafer: WaferDetail) {
                checkActive()
                require(wafers.size < MAX_WAFERS) { "一次最多叠加 $MAX_WAFERS 片晶圆。" }
                require(wafer.lotCode == lotCode && ids.add(wafer.id)) { "请选择同一批次内不同的晶圆。" }
                require(wafer.sourceKind == source && source.supportsStack) { "请选择来源类型相同、且已标记来源的晶圆。" }
                require(wafer.rows == rows && wafer.cols == cols) { "网格尺寸不同，请选择坐标可对齐的晶圆。" }
                val bins = ByteArray(failures.size)
                wafer.map.forEachIndexed { row, values ->
                    checkActive()
                    values.forEachIndexed { col, bin ->
                        val index = row * cols + col
                        require((bin == 0) == (failures[index] == -1)) {
                            "有效芯片位置不同，请选择坐标可对齐的晶圆。"
                        }
                        bins[index] = bin.toByte()
                        if (bin == 2) failures[index]++
                    }
                }
                wafers.add(StackedWafer(wafer.id, wafer.waferCode, bins))
            }

            append(first)
            while (iterator.hasNext()) append(iterator.next())
            require(wafers.size >= 2) { "请至少选择两片晶圆。" }
            val peak = failures.indices.maxByOrNull { failures[it] }?.takeIf { failures[it] > 0 }
            return LotFailureStack(rows, cols, wafers, failures, failures.count { it >= 2 }, peak)
        }
    }
}

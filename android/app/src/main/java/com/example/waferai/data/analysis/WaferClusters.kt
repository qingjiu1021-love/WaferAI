package com.example.waferai.data.analysis

internal data class DefectCluster(
    val size: Int, val firstRow: Int, val lastRow: Int, val firstCol: Int, val lastCol: Int,
    val members: IntArray = intArrayOf()
)

internal data class WaferClusters(val count: Int, val largest: DefectCluster?) {
    companion object {
        fun calculate(map: List<List<Int>>): WaferClusters {
            val rows = map.size
            val cols = map.first().size
            val seen = BooleanArray(rows * cols)
            val queue = IntArray(seen.size)
            var count = 0
            var largest: DefectCluster? = null
            for (start in seen.indices) {
                if (seen[start] || map[start / cols][start % cols] != 2) continue
                var head = 0
                var tail = 0
                fun enqueue(row: Int, col: Int) {
                    if (row !in 0 until rows || col !in 0 until cols) return
                    val index = row * cols + col
                    if (!seen[index] && map[row][col] == 2) {
                        seen[index] = true
                        queue[tail++] = index
                    }
                }
                var firstRow = start / cols
                var lastRow = firstRow
                var firstCol = start % cols
                var lastCol = firstCol
                enqueue(firstRow, firstCol)
                while (head < tail) {
                    val index = queue[head++]
                    val row = index / cols
                    val col = index % cols
                    firstRow = minOf(firstRow, row)
                    lastRow = maxOf(lastRow, row)
                    firstCol = minOf(firstCol, col)
                    lastCol = maxOf(lastCol, col)
                    enqueue(row - 1, col)
                    enqueue(row + 1, col)
                    enqueue(row, col - 1)
                    enqueue(row, col + 1)
                }
                count++
                if (tail > (largest?.size ?: 0)) {
                    largest = DefectCluster(tail, firstRow + 1, lastRow + 1, firstCol + 1, lastCol + 1, queue.copyOf(tail))
                }
            }
            return WaferClusters(count, largest)
        }
    }
}

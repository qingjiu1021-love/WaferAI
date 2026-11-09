package com.example.waferai

import com.example.waferai.data.analysis.LotFailureStack
import com.example.waferai.data.local.WaferSource
import com.example.waferai.data.local.parsedWafer
import org.junit.Assert.*
import org.junit.Test

class LotFailureStackTest {
    private fun wafer(id: Int, bins: List<Int>, lot: String = "A", source: WaferSource = WaferSource.GRID_FILE) =
        parsedWafer(lot, "W$id", listOf(bins), "wafer.json", source).detail.copy(id = id)

    @Test fun repeatedFailureCountsAndLinkedWaferStatusesUseTheSameCell() {
        val stack = LotFailureStack.calculate("A", sequenceOf(
            wafer(1, listOf(0, 1, 2, 2)), wafer(2, listOf(0, 1, 2, 1)), wafer(3, listOf(0, 1, 2, 2))
        ))
        assertEquals(listOf(-1, 0, 3, 2), List(4, stack::failuresAt))
        assertEquals(2, stack.repeatedPositions)
        assertEquals(2, stack.peakIndex)
        assertEquals(listOf(1, 3), stack.wafers.filter { it.failedAt(3) }.map { it.id })
    }

    @Test fun refusesMixedSourcesBatchesMasksAndDimensions() {
        val base = wafer(1, listOf(0, 1, 2))
        for (other in listOf(
            wafer(2, listOf(0, 2, 1), lot = "B"),
            wafer(2, listOf(0, 2, 1), source = WaferSource.WM811K),
            wafer(2, listOf(0, 2, 1), source = WaferSource.UNKNOWN),
            wafer(2, listOf(1, 0, 2)),
            wafer(2, listOf(0, 2)),
            base
        )) {
            assertThrows(IllegalArgumentException::class.java) { LotFailureStack.calculate("A", sequenceOf(base, other)) }
        }
    }
}

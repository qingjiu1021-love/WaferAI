package com.example.waferai

import com.example.waferai.data.analysis.WaferSpatialStats
import com.example.waferai.data.remote.WaferDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WaferSpatialStatsTest {
    @Test fun splitsEffectiveDiesIntoRingsAndTiles() {
        val map = listOf(
            listOf(2, 0, 0, 0, 0),
            listOf(0, 0, 0, 0, 0),
            listOf(0, 0, 1, 2, 0),
            listOf(0, 0, 0, 0, 0),
            listOf(0, 0, 0, 0, 1)
        )
        val wafer = WaferDetail(1, "L", "W", "test.json", 5, 5, 2, 2, 4, 0.5, map)
        val stats = WaferSpatialStats.calculate(wafer)
        assertEquals(listOf(1, 1, 2), stats.rings.map { it.total })
        assertEquals(listOf(0.0, 1.0, 0.5), stats.rings.map { it.badRate })
        assertEquals(wafer.totalCount, stats.grid.sumOf { it.total })
        assertEquals(wafer.badCount, stats.grid.sumOf { it.bad })
        assertNull(stats.grid[1].badRate)
    }
}

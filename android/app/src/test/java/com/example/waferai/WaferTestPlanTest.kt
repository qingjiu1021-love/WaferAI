package com.example.waferai

import com.example.waferai.data.local.WaferTestPlan
import org.junit.Assert.*
import org.junit.Test

class WaferTestPlanTest {
    @Test fun simulationUsesExactReadingsAndInclusiveLimits() {
        val plan = WaferTestPlan.simulated(10.0)
        val wafer = plan.wafer
        assertEquals(317, wafer.totalCount)
        assertTrue(wafer.goodCount > 0)
        assertTrue(wafer.badCount > 0)
        assertNull(plan.readingAt(0, 0))
        assertEquals(0, wafer.map[0][0])
        assertEquals(wafer.totalCount, plan.dieIndices.size)
        assertEquals(wafer.totalCount, plan.dieIndices.distinct().size)
        plan.dieIndices.forEach { index ->
            val value = requireNotNull(plan.readingAt(index / wafer.cols, index % wafer.cols))
            assertEquals(if (value <= 10.0) 1 else 2, plan.statusAt(index))
        }
        val center = requireNotNull(plan.readingAt(10, 10))
        assertEquals(1, WaferTestPlan.simulated(center).wafer.map[10][10])
        assertEquals(2, WaferTestPlan.simulated(center - 0.01).wafer.map[10][10])
    }

    @Test fun replayPreservesTheMapAndHasNoInventedReadings() {
        val original = WaferTestPlan.simulated(10.0).wafer
        val replay = WaferTestPlan.replay(original)
        assertSame(original, replay.wafer)
        assertEquals(WaferTestPlan.REPLAY, replay.mode)
        assertNull(replay.readingAt(10, 10))
        assertNull(replay.comparison)
    }

    @Test fun comparisonCountsOnlyDiesThatCrossTheLimitAndSurvivesRestore() {
        val center = requireNotNull(WaferTestPlan.simulated(10.0).readingAt(10, 10))
        val plan = WaferTestPlan.simulated(center - 0.01, center)
        val comparison = requireNotNull(plan.comparison)
        val changed = comparison.changedDies.single { it.row == 11 && it.col == 11 }
        assertEquals(center, changed.reading, 0.0)
        assertEquals(plan.wafer.goodCount, comparison.baselinePassed)
        assertEquals(WaferTestPlan.simulated(center).wafer.goodCount, comparison.comparisonPassed)
        assertEquals(comparison.comparisonPassed - comparison.baselinePassed, comparison.changedDies.size)
        assertTrue(comparison.becamePass)
        assertEquals(comparison, WaferTestPlan.restore(plan.snapshot()).comparison)

        val stricter = requireNotNull(WaferTestPlan.simulated(center, center - 0.01).comparison)
        assertEquals(comparison.changedDies, stricter.changedDies)
        assertEquals(-comparison.yieldDeltaPoints, stricter.yieldDeltaPoints, 0.0)
        assertFalse(stricter.becamePass)

        val equal = requireNotNull(WaferTestPlan.simulated(center, center).comparison)
        assertTrue(equal.changedDies.isEmpty())
        assertEquals(0.0, equal.yieldDeltaPoints, 0.0)
        assertNull(WaferTestPlan.restore(WaferTestPlan.simulated(10.0).snapshot()).comparison)
    }

    @Test fun simulationRejectsInvalidLimits() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 100.01)) {
            assertThrows(IllegalArgumentException::class.java) { WaferTestPlan.simulated(invalid) }
            assertThrows(IllegalArgumentException::class.java) { WaferTestPlan.simulated(10.0, invalid) }
        }
    }
}

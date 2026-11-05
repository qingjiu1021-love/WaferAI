package com.example.waferai

import com.example.waferai.data.analysis.*
import com.example.waferai.data.local.WaferJson
import org.junit.Assert.*
import org.junit.Test

class WaferSummaryTest {
    private fun wafer(map: String) = WaferJson.parse("{\"lot_code\":\"QA\",\"wafer_code\":\"W\",\"map\":$map}".toByteArray(), "qa.json").detail
    @Test fun clusterHighlightContainsOnlyConnectedFailingDiesAndLeavesOtherIslandsOut() {
        val wafer = wafer("[[2,2,0,2],[1,2,1,1],[0,1,1,0]]")
        val summary = WaferSummary.calculate(wafer)
        val largest = summary.findings.single { it.id == "largest_cluster" }
        assertEquals(setOf(0, 1, 5), largest.indices.toSet())
        assertTrue(largest.indices.all { wafer.map[it / wafer.cols][it % wafer.cols] == 2 })
        assertEquals(summary.findings.size, summary.findings.map { it.id }.toSet().size)
    }
    @Test fun allPassDoesNotInventDefectLocationsOrAnAiPrediction() {
        val summary = WaferSummary.calculate(wafer("[[0,1],[1,0]]"))
        assertEquals(1, summary.findings.size)
        assertTrue(summary.findings.single().indices.isEmpty())
        assertTrue(summary.facts.none { it.id.startsWith("cnn") })
        assertTrue(summary.facts.any { it.text.contains("100.00%") })
    }
    @Test fun allFailAndSparseRowsHaveFiniteWholeWaferStatistics() {
        val summary = WaferSummary.calculate(wafer("[[0,0,0],[2,2,2]]"), 85)
        assertTrue(summary.facts.any { it.id == "yield" && it.text.contains("0.00%") })
        assertEquals(3, summary.findings.single { it.id == "yield" }.indices.size)
        assertTrue(summary.facts.none { it.text.contains("NaN") })
    }
}

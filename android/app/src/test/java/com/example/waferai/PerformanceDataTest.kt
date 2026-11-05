package com.example.waferai

import com.example.waferai.data.ai.*
import com.example.waferai.data.analysis.*
import com.example.waferai.data.remote.WaferDetail
import com.example.waferai.data.report.WaferReportData
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class PerformanceDataTest {
    @Test fun optimizedReportMatchesIndependentCountsForSparseAndMaximumMatrices() {
        val random = Random(701)
        for ((rows, cols) in listOf(1 to 1, 3 to 57, 90 to 20, 512 to 512)) {
            val map = List(rows) { List(cols) { random.nextInt(3) } }.let { if (rows == 1) listOf(listOf(2)) else it }
            val good = map.sumOf { r -> r.count { it == 1 } }
            val bad = map.sumOf { r -> r.count { it == 2 } }
            val wafer = WaferDetail(1,"L","W","test",rows,cols,good,bad,good+bad,good.toDouble()/(good+bad),map)
            val report = WaferReportData.calculate(wafer)
            assertEquals(good, report.good); assertEquals(bad, report.bad)
            assertEquals(WaferSpatialStats.calculate(wafer).rings, report.rings)
            report.rows.forEachIndexed { i, r -> assertEquals(map[i].count { it != 0 },r.total); assertEquals(map[i].count { it == 2 },r.bad) }
            report.columns.forEachIndexed { i, c -> assertEquals(map.count { it[i] != 0 },c.total); assertEquals(map.count { it[i] == 2 },c.bad) }
            val summary = WaferSummary.calculate(wafer)
            assertEquals(report, summary.report)
            assertEquals(bad, summary.findings.first().indices.size)
        }
    }

    @Test fun matrixKeysNeverCallDeepEquality() {
        val poison = object : AbstractList<Int>() {
            override val size = 262144
            override fun get(index: Int): Int = error("Must not scan matrix for a UI key")
        }
        assertEquals(MatrixIdentity(poison), MatrixIdentity(poison))
        assertNotEquals(MatrixIdentity(poison), MatrixIdentity(List(1) { 1 }))
    }

    @Test fun oldAndDuplicateChatIdsUpgradeAndSurviveTrimming() {
        val turns = List(30) { ChatTurn("same question", "answer", "model", id = if (it < 2) "duplicate" else null) }
        val upgraded = ChatArchive().updateActive(turns).withTurnIds()
        val ids = upgraded.active.turns.map { it.id }
        assertEquals(30, ids.toSet().size); assertFalse(ids.any { it.isNullOrBlank() })
        assertEquals(ids, upgraded.withTurnIds().active.turns.map { it.id })
        val next = upgraded.updateActive(upgraded.active.turns + ChatTurn("next", "reply", "model"))
        assertEquals(ids.drop(1), next.active.turns.dropLast(1).map { it.id })
    }
}

package com.example.waferai

import com.example.waferai.data.local.WaferJson
import com.example.waferai.data.report.WaferReportData
import org.junit.Assert.*
import org.junit.Test

class WaferReportDataTest {
    private fun data(map: String) = WaferReportData.calculate(WaferJson.parse(
        "{\"lot_code\":\"QA\",\"wafer_code\":\"W1\",\"map\":$map}".toByteArray(), "qa.json").detail)

    @Test fun chartDenominatorsExcludeOutsideAndEmptyPositionsStayMissing() {
        val d = data("[[0,0,0],[1,2,0],[2,1,2]]")
        assertEquals(2, d.good); assertEquals(3, d.bad); assertEquals(4, d.outside)
        assertEquals(0.4, d.yield, 0.0)
        assertNull(d.rows[0].rate)
        assertEquals(0.5, d.rows[1].rate!!, 0.0)
        assertEquals(2.0 / 3, d.rows[2].rate!!, 0.0)
        assertEquals(1.0, d.columns[2].rate!!, 0.0)
        assertEquals(d.total, d.rings.sumOf { it.total })
        assertEquals(d.bad, d.rings.sumOf { it.bad })
    }

    @Test fun singlePointAndAllPassOrFailRemainFinite() {
        for (bin in 1..2) {
            val d = data("[[$bin]]")
            assertEquals(if (bin == 1) 1.0 else 0.0, d.yield, 0.0)
            assertEquals(if (bin == 2) 1.0 else 0.0, d.rows.single().rate!!, 0.0)
            assertEquals(d.rows.single().rate, d.columns.single().rate)
            assertTrue(d.rings.any { it.badRate == null })
        }
    }
}

package com.example.waferai

import com.example.waferai.data.analysis.WaferClusters
import com.example.waferai.data.local.WaferTestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WaferTestDataTest {
    @Test fun stdfReadsBothByteOrdersAndSkipsInvalidResults() {
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val far = record(order, 0, 10, byteArrayOf(if (order == ByteOrder.BIG_ENDIAN) 1 else 2, 4))
            fun prr(x: Int, y: Int, flags: Int) = record(order, 5, 20,
                ByteBuffer.allocate(17).order(order).put(1).put(1).put(flags.toByte())
                    .putShort(1).putShort(1).putShort(1).putShort(x.toShort()).putShort(y.toShort()).putInt(0).array())
            val bytes = far + prr(-1, 1, 0) + prr(0, 0, 8) + prr(10, 10, 16)
            val parsed = WaferTestData.stdf(bytes, "test.stdf")
            assertEquals(listOf(listOf(1, 0), listOf(0, 2)), parsed.detail.map)
            assertThrows(IllegalArgumentException::class.java) { WaferTestData.stdf(bytes.copyOf(bytes.size - 1), "broken.stdf") }
        }
    }

    @Test fun atdfHonorsCoordinateRetestAndRejectsMultipleWafers() {
        val text = "FAR:A|4|2\nMIR:LOT\nWIR:1|01:00|1|W01\n" +
            "PRR:1|1|01|1|F|1|1|-2|3||||\n" +
            "PRR:1|1|01|1|P|1|1|-2|3|C|||\n" +
            "PRR:1|1|02|1|F|2|2|-1|3||Y||\n" +
            "WRR:1|02:00|3|W01\n"
        val wafer = WaferTestData.atdf(text.toByteArray(), "test.adtf").detail
        assertEquals("LOT", wafer.lotCode)
        assertEquals("W01", wafer.waferCode)
        assertEquals(listOf(listOf(1)), wafer.map)
        assertThrows(IllegalArgumentException::class.java) {
            WaferTestData.atdf((text + "WIR:1|03:00|1|W02").toByteArray(), "multi.atdf")
        }
    }

    @Test fun clustersDoNotJoinDiagonalFailures() {
        val result = WaferClusters.calculate(listOf(listOf(2, 2, 0), listOf(1, 1, 2)))
        assertEquals(2, result.count)
        assertEquals(2, result.largest?.size)
        assertEquals(1, result.largest?.firstRow)
        assertEquals(2, result.largest?.lastCol)
    }

    private fun record(order: ByteOrder, type: Int, subtype: Int, body: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + body.size).order(order).putShort(body.size.toShort())
            .put(type.toByte()).put(subtype.toByte()).put(body).array()
}

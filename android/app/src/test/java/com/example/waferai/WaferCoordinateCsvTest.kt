package com.example.waferai

import com.example.waferai.data.local.WaferCoordinateCsv
import com.example.waferai.data.local.WaferJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WaferCoordinateCsvTest {
    @Test fun readsBomQuotedCodesAndSparseCoordinatesWithCanonicalHash() {
        val csv = "\uFEFFstatus,col,row,wafer_code,LOT_CODE\r\n" +
            "1,2,1,\"W\"\"01\",\"LOT,A\"\r\n" +
            "2,1,2,\"W\"\"01\",\"LOT,A\"\r\n"
        val json = """{"lot_code":"LOT,A","wafer_code":"W\"01","map":[[0,1],[2,0]]}"""
        val parsed = WaferCoordinateCsv.parse(csv.toByteArray(), "a.csv")
        assertEquals(WaferJson.parse(json.toByteArray(), "a.json").inputHash, parsed.inputHash)
        assertEquals(listOf(listOf(0, 1), listOf(2, 0)), parsed.detail.map)
        assertEquals(0.5, parsed.detail.yieldRate, 0.0)
    }

    @Test fun rejectsDuplicateCoordinatesEvenWhenFirstValueIsZero() {
        assertInvalid("L,W,1,1,0\nL,W,1,1,1", "坐标重复")
    }

    @Test fun rejectsMixedWafersAndOutOfRangeValues() {
        assertInvalid("L,W,1,1,1\nL,W2,1,2,2", "编号不同")
        assertInvalid("L,W,513,1,1", "1～512")
        assertInvalid("L,W,1,1,3", "状态须为")
    }

    @Test fun rejectsMalformedQuotesAndEmptyWafer() {
        assertInvalid("L,\"W,1,1,1", "引号格式")
        assertInvalid("L,W,1,1,0", "没有有效芯片")
    }

    private fun assertInvalid(rows: String, expected: String) {
        val csv = "lot_code,wafer_code,row,col,status\n$rows"
        val error = assertThrows(IllegalArgumentException::class.java) {
            WaferCoordinateCsv.parse(csv.toByteArray(), "a.csv")
        }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(expected))
    }
}

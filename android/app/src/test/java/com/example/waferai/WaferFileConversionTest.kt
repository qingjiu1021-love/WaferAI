package com.example.waferai

import com.example.waferai.data.local.WaferCoordinateCsv
import com.example.waferai.data.local.WaferFileWriter
import com.example.waferai.data.local.WaferJson
import com.example.waferai.data.local.WaferTestData
import com.example.waferai.data.local.WaferSource
import com.example.waferai.data.local.parsedWafer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.StringWriter

class WaferFileConversionTest {
    @Test fun preservesIdentifiersAndEmptyBordersInAllNativeFormats() {
        val source = parsedWafer("批次,\"甲\"", "片|" + "🧪".repeat(62), listOf(
            listOf(0, 0, 0, 0), listOf(0, 1, 2, 0), listOf(0, 0, 0, 0)
        ), "source.json")
        val wafer = source.detail
        val stdf = ByteArrayOutputStream().also { WaferFileWriter.stdf(wafer, it) }.toByteArray()
        val atdf = StringWriter().also { WaferFileWriter.atdf(wafer, it) }.toString()
        val csv = StringWriter().also { WaferFileWriter.csv(wafer, it) }.toString()
        val results = listOf(
            WaferTestData.stdf(stdf, "map.stdf"), WaferTestData.atdf(atdf.toByteArray(), "map.atdf"),
            WaferCoordinateCsv.parse(csv.reader(), "map.csv"), WaferJson.parse(WaferFileWriter.json(wafer).toByteArray(), "map.json")
        )
        assertEquals(listOf(WaferSource.MAP_EXPORT, WaferSource.MAP_EXPORT, WaferSource.GRID_FILE, WaferSource.GRID_FILE),
            results.map { it.detail.sourceKind })
        results.forEach { parsed ->
            assertEquals(source.inputHash, parsed.inputHash)
            assertEquals(wafer.lotCode, parsed.detail.lotCode)
            assertEquals(wafer.waferCode, parsed.detail.waferCode)
            assertEquals(1, parsed.detail.goodCount)
            assertEquals(1, parsed.detail.badCount)
        }
    }

    @Test fun maximumDenseGridFitsStdfAndAtdfImportLimits() {
        val bins = List(512) { if (it % 7 == 0) 2 else 1 }
        val source = parsedWafer("LOT", "WAFER", List(512) { bins }, "full.json")
        val stdf = ByteArrayOutputStream().also { WaferFileWriter.stdf(source.detail, it) }.toByteArray()
        val atdf = StringWriter().also { WaferFileWriter.atdf(source.detail, it) }.toString().toByteArray()
        assertTrue(stdf.size <= WaferTestData.MAX_BYTES)
        assertTrue(atdf.size <= WaferTestData.MAX_ATDF_BYTES)
        assertEquals(source.inputHash, WaferTestData.stdf(stdf, "full.stdf").inputHash)
        assertEquals(source.inputHash, WaferTestData.atdf(atdf, "full.atdf").inputHash)
    }

    @Test fun streamRejectsOverlongCsvRowsAndInvalidStdfGridExtensions() {
        val header = "lot_code,wafer_code,row,col,status\n"
        assertThrows(IllegalArgumentException::class.java) {
            WaferCoordinateCsv.parse((header + "A".repeat(1025)).reader(), "bad.csv")
        }
        val source = parsedWafer("L", "W", listOf(listOf(0, 1)), "source.json")
        val atdf = StringWriter().also { WaferFileWriter.atdf(source.detail, it) }.toString()
        assertThrows(IllegalArgumentException::class.java) {
            WaferTestData.atdf(atdf.replace("WaferAI-map-v1:grid:1:2", "WaferAI-map-v1:grid:1:1").toByteArray(), "bad.atdf")
        }
    }
}

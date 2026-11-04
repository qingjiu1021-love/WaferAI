package com.example.waferai

import com.example.waferai.data.local.*
import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DemoWaferCatalogTest {
    private val assets = File("src/main/assets/samples")

    @Test fun catalogHasTwentySevenRealInputsInNineCategories() {
        val real = DemoWafer.entries.filter { it.isReal }
        assertEquals(30, DemoWafer.entries.size)
        assertEquals(27, real.size)
        assertEquals(9, real.groupBy { it.datasetLabel }.size)
        assertTrue(real.groupBy { it.datasetLabel }.values.all { it.size == 3 })
        assertEquals(30, DemoWafer.entries.map { it.filename }.toSet().size)
    }

    @Test fun everyBundledMatrixMatchesReviewedSourceCountsAndHashes() {
        val manifest = JsonParser.parseString(File(assets, "wafertools-manifest.json").readText()).asJsonObject
        val reviewed = manifest.getAsJsonArray("samples").associate { it.asJsonObject["file"].asString to it.asJsonObject }
        val hashes = mutableSetOf<String>()
        for (sample in DemoWafer.entries) {
            val bytes = File(assets, sample.filename).readBytes()
            val parsed = WaferJson.parse(bytes, sample.filename)
            if (!sample.isReal) continue
            val wafer = parsed.detail
            val source = reviewed.getValue(sample.filename)
            assertEquals(source["asset_sha256"].asString, WaferJson.sha256(bytes))
            assertEquals(source["matrix_sha256"].asString, parsed.inputHash)
            assertEquals(sample.goodCount, wafer.goodCount)
            assertEquals(sample.badCount, wafer.badCount)
            assertEquals(sample.rows, wafer.rows); assertEquals(sample.cols, wafer.cols)
            assertEquals(sample.lotCode, wafer.lotCode); assertEquals(sample.waferCode, wafer.waferCode)
            assertEquals(sample.goodCount.toDouble() / (sample.goodCount + sample.badCount), wafer.yieldRate, 0.0)
            assertTrue(hashes.add(parsed.inputHash))
        }
        assertEquals(27, hashes.size)
    }

    @Test fun filtersSearchOriginalLabelsLotsAndChineseNamesWithoutInventingClasses() {
        assertEquals(3, DemoWafer.search("donut").size)
        assertEquals(1, DemoWafer.search("边缘局部型 02").size)
        assertEquals(3, DemoWafer.search("", DemoCategory.NONE).size)
        assertEquals(3, DemoWafer.search("人工").size)
        assertEquals(7, DemoWafer.search("WM811K-lot2").count { it.lotCode == "WM811K-lot2" })
        assertTrue(DemoWafer.search("does-not-exist").isEmpty())
        assertTrue(DemoWafer.search("donut", DemoCategory.SCRATCH).isEmpty())
    }
}

package com.example.waferai

import com.example.waferai.data.local.WaferJson
import com.example.waferai.data.local.MobilePreprocessing
import org.junit.Assert.*
import org.junit.Test

class WaferJsonTest {
    private fun parse(map: String) = WaferJson.parse("{\"lot_code\":\"L\",\"wafer_code\":\"W\",\"map\":$map}".toByteArray(),"test.json")
    @Test fun computesYieldAndExcludesOutside() {
        val wafer=parse("[[0,1,1],[2,0,1]]").detail
        assertEquals(3,wafer.goodCount); assertEquals(1,wafer.badCount); assertEquals(4,wafer.totalCount); assertEquals(0.75,wafer.yieldRate,0.0)
    }
    @Test fun rejectsInvalidBins() {
        listOf("1.0","true","\"1\"","3","-1","null","1e0").forEach { bin -> assertThrows(IllegalArgumentException::class.java) { parse("[[1,$bin]]") } }
    }
    @Test fun rejectsEmptyRaggedAndNonArrayMaps() {
        listOf("[]","[[]]","[[0,0]]","[[1],[1,2]]","{}","[[[1]]]").forEach { assertThrows(IllegalArgumentException::class.java) { parse(it) } }
    }
    @Test fun acceptsBomAndIgnoresWhitespaceForIdentity() {
        val text="{\"lot_code\":\"L\",\"wafer_code\":\"W\",\"map\":[[1,2]]}"
        assertEquals(parse("[[1,2]]").inputHash,WaferJson.parse(("\uFEFF"+text).toByteArray(),"bom.json").inputHash)
    }
    @Test fun shapeIsPartOfIdentity() { assertNotEquals(parse("[[1,2]]").inputHash,parse("[[1],[2]]").inputHash) }
    @Test fun rejectsLargeFilesAndUnknownFields() {
        assertThrows(IllegalArgumentException::class.java) { WaferJson.parse(ByteArray(WaferJson.MAX_BYTES+1),"large.json") }
        assertThrows(IllegalArgumentException::class.java) { WaferJson.parse("{\"lot_code\":\"L\",\"wafer_code\":\"W\",\"map\":[[1]],\"extra\":1}".toByteArray(),"extra.json") }
    }
    @Test fun oneHotIncludesOutsideAsSeparateChannel() {
        val bins=MobilePreprocessing.bins(listOf(listOf(0,1,2)))
        val oneHot=MobilePreprocessing.oneHot(bins)
        for(index in bins.indices) assertEquals(1f,oneHot[index]+oneHot[4096+index]+oneHot[8192+index],0f)
    }
}

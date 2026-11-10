package com.example.waferai

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.example.waferai.data.local.WaferFileFormat
import com.example.waferai.data.local.WaferJson
import com.example.waferai.data.repository.ConversionSource
import com.example.waferai.data.repository.ImportRepository
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaferArchiveConversionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val json = """{"lot_code":"LOT","wafer_code":"W1","map":[[0,1],[2,1]]}"""
    private val csv = "lot_code,wafer_code,row,col,status\nLOT,W2,1,1,1\nLOT,W2,1,2,2\n"

    @Test fun choosesOneWaferFromZipAndExportsIt() = runBlocking {
        val repo = ImportRepository(context)
        val zip = File.createTempFile("wafer-source-", ".zip", context.cacheDir)
        val output = File.createTempFile("wafer-output-", ".json", context.cacheDir)
        try {
            ZipOutputStream(zip.outputStream()).use { archive ->
                mapOf("notes.txt" to "ignore", "maps/W1.json" to json, "W2.csv" to csv).forEach { (name, data) ->
                    archive.putNextEntry(ZipEntry(name))
                    archive.write(data.toByteArray())
                    archive.closeEntry()
                }
            }
            val uri = Uri.fromFile(zip)
            val listed = repo.readForConversion(uri) as ConversionSource.Archive
            assertEquals(listOf("maps/W1.json", "W2.csv"), listed.names)
            val wafer = (repo.readForConversion(uri, "W2.csv") as ConversionSource.Wafer).detail
            assertEquals("W2", wafer.waferCode)
            assertEquals(listOf(listOf(1, 2)), wafer.map)
            repo.convertFile(Uri.fromFile(output), wafer, WaferFileFormat.JSON)
            assertEquals(wafer.map, WaferJson.parse(output.readBytes(), output.name).detail.map)
            assertTrue(runCatching { repo.readForConversion(uri, "missing.csv") }.isFailure)
        } finally {
            zip.delete()
            output.delete()
        }
    }

    @Test fun opensSingleZipAndGzipWithoutChoosingAnEntry() = runBlocking {
        val repo = ImportRepository(context)
        val zip = File.createTempFile("wafer-source-", ".zip", context.cacheDir)
        val gzip = File.createTempFile("wafer-source-", ".json.gz", context.cacheDir)
        try {
            ZipOutputStream(zip.outputStream()).use { archive ->
                archive.putNextEntry(ZipEntry("maps/W1.json"))
                archive.write(json.toByteArray())
            }
            GZIPOutputStream(gzip.outputStream()).use { it.write(json.toByteArray()) }
            for (source in listOf(zip, gzip)) {
                val wafer = (repo.readForConversion(Uri.fromFile(source)) as ConversionSource.Wafer).detail
                assertEquals("W1", wafer.waferCode)
                assertEquals(2, wafer.goodCount)
                assertEquals(1, wafer.badCount)
            }
            GZIPOutputStream(gzip.outputStream()).use { it.write(ByteArray(WaferJson.MAX_BYTES + 1)) }
            assertTrue(runCatching { repo.readForConversion(Uri.fromFile(gzip)) }.exceptionOrNull() is IllegalArgumentException)
        } finally {
            zip.delete()
            gzip.delete()
        }
    }
}

package com.example.waferai

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.example.waferai.data.ai.WaferKnowledge
import com.example.waferai.data.ai.SearchPlugin
import com.example.waferai.data.local.MobilePredictor
import com.example.waferai.data.local.OperationLog
import com.example.waferai.data.local.WaferImage
import com.example.waferai.data.local.WaferImageOptions
import com.example.waferai.data.local.WaferPalette
import com.example.waferai.data.local.WaferParquet
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class WaferOfflineInputsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun snappyParquetRebuildsDemoMap() = runBlocking {
        val bytes = instrumentation.context.assets.open("qa-wafer.parquet").use { it.readBytes() }
        val wafer = WaferParquet.parse(context, bytes, "sample.parquet").detail
        assertEquals("QA32", wafer.lotCode)
        assertEquals(42, wafer.goodCount)
        assertEquals(7, wafer.badCount)
        assertEquals(9, wafer.rows)
        assertEquals(9, wafer.cols)
    }

    @Test fun pngAndJpegProduceTheSameMapAndOnDeviceScores() {
        val bitmap = Bitmap.createBitmap(90, 90, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until 90) for (x in 0 until 90) {
                bitmap.setPixel(x, y, when {
                    x < 30 -> Color.LTGRAY
                    x < 60 -> Color.rgb(46, 125, 50)
                    else -> Color.rgb(211, 47, 47)
                })
            }
            val options = WaferImageOptions(3, 3, WaferPalette.RED_GREEN)
            val png = WaferImage.parse(encode(bitmap, Bitmap.CompressFormat.PNG), "sample.png", options)
            val jpg = WaferImage.parse(encode(bitmap, Bitmap.CompressFormat.JPEG), "sample.jpg", options)
            assertEquals(png.inputHash, jpg.inputHash)
            assertEquals(listOf(0, 1, 2), png.detail.map.first())
            assertEquals(0.5, jpg.detail.yieldRate, 0.0)
            val scores = MobilePredictor(context).scores(png.detail.map)
            assertEquals(9, scores.size)
            assertEquals(1.0, scores.sum(), 1e-6)
        } finally { bitmap.recycle() }
    }

    @Test fun cachesPublicSearchResultsAndReadsThemWithoutNetwork() = runBlocking {
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "wafer-knowledge-test").toFile()
        val isolated = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int) =
                context.getSharedPreferences("${directory.name}-$name", mode)
        }
        try {
            val transport = OkHttpClient.Builder().addInterceptor { chain ->
                assertTrue(chain.request().url.queryParameter("q")!!.contains("wafer"))
                val payload = when (chain.request().url.host) {
                    "api.github.com" -> """{"items":[{"html_url":"https://github.com/qa/wafer-calibration",
                        "full_name":"qa/wafer-calibration","description":"Calibration method","private":false}]}"""
                    "en.wikipedia.org" -> """{"pages":[{"id":123456,"title":"Wafer map calibration",
                        "excerpt":"<span>Calibration</span> method"}]}"""
                    else -> throw java.io.IOException("Unexpected search provider in this test")
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(payload.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val log = OperationLog(isolated)
            val online = WaferKnowledge(isolated, log, transport)
            online.plugins.save(SearchPlugin.GITHUB, true)
            online.plugins.save(SearchPlugin.WIKIPEDIA, true)
            val result = online.refresh(force = true)
            assertEquals(2, result.added)
            assertTrue(result.failures.isEmpty())
            val offline = WaferKnowledge(isolated, OperationLog(isolated))
            val matches = offline.search("calibration")
            assertEquals(2, matches.size)
            val found = matches.single { it.source == "https://en.wikipedia.org/?curid=123456" }
            assertEquals("Wafer map calibration", found.title)
            assertTrue(found.body.contains("Calibration method"))
            assertTrue(offline.search("Ward 论文").any { "michaelward.pdf" in it.source })
            assertTrue(OperationLog(isolated).entries.value.single().success)
        } finally {
            context.deleteSharedPreferences("${directory.name}-knowledge_plugins")
            directory.deleteRecursively()
        }
    }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat) = ByteArrayOutputStream().use {
        assertTrue(bitmap.compress(format, 92, it))
        it.toByteArray()
    }
}

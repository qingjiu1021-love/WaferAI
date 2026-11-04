package com.example.waferai

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.example.waferai.data.local.LocalWaferStore
import com.example.waferai.data.local.MobilePredictor
import com.example.waferai.data.local.WaferTrainer
import com.example.waferai.data.report.ReportSnapshot
import com.example.waferai.data.report.WaferPdfReport
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.StringWriter
import java.nio.file.Files

class TrainingInspectionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun sample() = context.assets.open("samples/demo-wafer-001.json").use { it.readBytes() }

    @Test fun trainsAndRestoresPhoneModelWithCustomLabel() = runBlocking {
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "wafer-training-test").toFile()
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir(): File = directory }
        val name = "wafer-training-test.db"
        try {
            LocalWaferStore(context, name).use { store ->
                val wafer = store.importJson(sample(), "demo.json").wafer
                val trainer = WaferTrainer(isolated)
                val baseline = trainer.train(null)
                assertEquals(360, baseline.samples)
                assertEquals(108, baseline.validation)
                val custom = """{"samples":[{"lot_code":"CUSTOM","wafer_code":"X1","map":[[1,1,2],[1,2,2]],"label":"Edge-Loc"}]}"""
                val result = trainer.train(custom.toByteArray())
                assertEquals(361, result.samples)
                assertEquals(1, result.customSamples)
                assertTrue(result.accuracy in 0.0..1.0 && result.macroF1 in 0.0..1.0)
                val reopened = WaferTrainer(isolated)
                assertEquals(result, reopened.summary.value)
                val trained = reopened.predict(wafer)
                assertEquals("centroid-v1-mobile", trained.modelVersion)
                assertEquals(9, trained.scores.size)
                assertEquals(1.0, trained.scores.sumOf { it.score }, 1e-6)
                assertEquals(64, trained.modelSha256.length)
                assertEquals(1, reopened.train(null).customSamples)
                assertEquals(1, reopened.train(custom.toByteArray()).customSamples)
                val conflict = custom.replace("Edge-Loc", "Center").toByteArray()
                assertTrue(runCatching { reopened.train(conflict) }.isFailure)
                assertEquals(1, WaferTrainer(isolated).summary.value?.customSamples)
            }
        } finally {
            context.deleteDatabase(name)
            directory.deleteRecursively()
        }
    }

    @Test fun migratesExistingDatabaseAndBacksUpLatestInspection() {
        val old = "wafer-inspections-old-test.db"
        val restored = "wafer-inspections-new-test.db"
        try {
            val wafer = LocalWaferStore(context, old).use { store ->
                val saved = store.importJson(sample(), "demo.json").wafer
                store.writableDatabase.execSQL("DROP TABLE inspections")
                store.writableDatabase.version = 1
                saved
            }
            LocalWaferStore(context, old).use { source ->
                assertEquals(wafer, source.detail(wafer.id))
                assertNull(source.latestInspection(wafer.id))
                val prediction = MobilePredictor(context).predict(wafer)
                source.saveInspection(prediction, 80)
                val latest = source.saveInspection(prediction, 90)
                assertEquals(latest, source.latestInspection(wafer.id))
                assertTrue(source.recentInspections().single().belowThreshold)
                val report = WaferPdfReport(ReportSnapshot(wafer, latest.prediction, inspection = latest.record)).generate()
                assertTrue(String(report.take(5).toByteArray()) == "%PDF-")
                val backup = StringWriter().also { source.backup(it) {} }.toString()
                LocalWaferStore(context, restored).use { target ->
                    assertEquals(1, target.restoreBackup(backup.reader()) {})
                    assertEquals(latest, target.latestInspection(wafer.id))
                    val legacy = JsonParser.parseString(backup).asJsonObject
                    legacy.addProperty("version", 1)
                    legacy.remove("inspections")
                    assertEquals(1, target.restoreBackup(legacy.toString().reader()) {})
                    assertNotNull(target.prediction(wafer.id))
                    assertNull(target.latestInspection(wafer.id))
                }
            }
        } finally {
            context.deleteDatabase(old)
            context.deleteDatabase(restored)
        }
    }
}

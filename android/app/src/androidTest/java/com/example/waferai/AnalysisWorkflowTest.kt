package com.example.waferai

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.waferai.data.local.DemoWafer
import com.example.waferai.data.report.AnalysisStage
import com.example.waferai.data.repository.ImportRepository
import com.example.waferai.ui.importdata.BatchAnalysisViewModel
import com.example.waferai.ui.importdata.BatchStatus
import com.example.waferai.ui.theme.Appearance
import com.example.waferai.ui.theme.AppearancePreferences
import com.example.waferai.ui.theme.StartTab
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AnalysisWorkflowTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app = instrument.targetContext

    private class Isolated(base: Context) : ContextWrapper(base) {
        val folder = File(base.cacheDir, "analysis-qa-${UUID.randomUUID()}").apply { mkdirs() }
        override fun getNoBackupFilesDir() = folder
        override fun getDatabasePath(name: String) = File(folder, name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
            errorHandler: android.database.DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
        override fun getFilesDir() = File(folder, "files").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences(folder.name + name, mode)
    }

    @Test fun pipelineCreatesOfflinePdfAndCancellationDoesNotReplaceIt(): Unit = runBlocking {
        val context = Isolated(app)
        val repository = ImportRepository(context)
        try {
            val wafer = repository.loadDemo(DemoWafer.WM_CENTER_1).wafer
            val stages = mutableListOf<AnalysisStage>()
            val result = repository.localAnalysis.analyzeCurrent(wafer, 95) { stages += it }
            assertEquals(listOf(AnalysisStage.STATISTICS, AnalysisStage.DETECTION, AnalysisStage.REPORT), stages)
            assertEquals(2145, result.wafer.goodCount)
            assertEquals(315, result.wafer.badCount)
            assertEquals(95, result.inspection.record.threshold)
            assertEquals("Center", result.inspection.prediction.label)
            val old = result.report.readBytes()
            assertTrue(old.toString(Charsets.ISO_8859_1).startsWith("%PDF-"))
            val task = launch {
                repository.localAnalysis.analyzeCurrent(wafer, 95) { if (it == AnalysisStage.REPORT) throw CancellationException("fixture") }
            }
            task.join()
            assertTrue(task.isCancelled)
            assertArrayEquals(old, result.report.readBytes())
            assertTrue(repository.latestInspection(wafer.id) != null)
        } finally { repository.store.close(); context.folder.deleteRecursively() }
    }

    @Test fun batchContinuesPastBadFileAndRetriesOnlyFailures(): Unit = runBlocking {
        val context = Isolated(app)
        val repository = ImportRepository(context)
        val models = ViewModelStore()
        val good = File(context.folder, "good.json").apply { writeText("""{"lot_code":"QA","wafer_code":"A","map":[[1,2],[1,1]]}""") }
        val bad = File(context.folder, "bad.json").apply { writeText("not json") }
        try {
            lateinit var queue: BatchAnalysisViewModel
            instrument.runOnMainSync { queue = BatchAnalysisViewModel(repository.localAnalysis); models.put("queue", queue) }
            withTimeout(5000) { queue.state.first { !it.loading } }
            instrument.runOnMainSync { queue.add(listOf(Uri.fromFile(bad), Uri.fromFile(good)), 90) }
            val first = withTimeout(30000) { queue.state.first { !it.running && it.items.size == 2 && it.items.none { item -> item.status == BatchStatus.QUEUED } } }
            assertEquals(1, first.completed)
            assertEquals(BatchStatus.FAILED, first.items[0].status)
            val savedReport = first.items[1].report
            bad.writeText("""{"lot_code":"QA","wafer_code":"B","map":[[1,1],[2,1]]}""")
            instrument.runOnMainSync { queue.retry() }
            val retried = withTimeout(30000) { queue.state.first { it.completed == 2 && !it.running } }
            assertEquals(savedReport, retried.items[1].report)
            assertTrue(retried.items.all { File(it.report!!).isFile })
            val exported = File(context.folder, "exported.pdf")
            instrument.runOnMainSync { queue.export(retried.items[0].id, Uri.fromFile(exported)) }
            withTimeout(5000) { queue.state.first { !it.exporting && it.message == "PDF 报告已保存到所选位置。" } }
            assertArrayEquals(File(retried.items[0].report!!).readBytes(), exported.readBytes())
            instrument.runOnMainSync { models.clear() }
            lateinit var restored: BatchAnalysisViewModel
            instrument.runOnMainSync { restored = BatchAnalysisViewModel(repository.localAnalysis); models.put("restored", restored) }
            assertEquals(2, withTimeout(5000) { restored.state.first { !it.loading } }.completed)
        } finally {
            instrument.runOnMainSync { models.clear() }
            repository.store.close(); context.folder.deleteRecursively()
        }
    }

    @Test fun transparencyReadingPreferenceAndOldProfileTabSurviveUpgrade() {
        val context = Isolated(app)
        val prefs = context.getSharedPreferences("appearance", 0)
        try {
            assertTrue(prefs.edit().putString("start_tab", "PROFILE").commit())
            assertEquals(StartTab.TOOLS, AppearancePreferences(context).load().startTab)
            for (opacity in listOf(0, 37, 100)) {
                AppearancePreferences(context).save(Appearance(navOpacity = opacity, panelOpacity = 100 - opacity,
                    panelGlass = com.example.waferai.ui.theme.GlassLevel.INTENSE,
                    reportReading = false, startTab = StartTab.TOOLS))
                val restored = AppearancePreferences(context).load()
                assertEquals(opacity, restored.navOpacity)
                assertEquals(100 - opacity, restored.panelOpacity)
                assertEquals(com.example.waferai.ui.theme.GlassLevel.INTENSE, restored.panelGlass)
                assertFalse(restored.reportReading)
                assertEquals(StartTab.TOOLS, restored.startTab)
            }
        } finally { prefs.edit().clear().commit(); context.folder.deleteRecursively() }
    }

    @Test fun cancellingBatchPreservesFinishedItemsAndStopsPendingWork(): Unit = runBlocking {
        val context = Isolated(app)
        val repository = ImportRepository(context)
        val models = ViewModelStore()
        try {
            val inputs = (1..20).map { n -> File(context.folder, "cancel-$n.json").apply {
                writeText("""{"lot_code":"CANCEL-QA","wafer_code":"W$n","map":[[1,2],[1,1]]}""")
            } }
            lateinit var queue: BatchAnalysisViewModel
            instrument.runOnMainSync { queue = BatchAnalysisViewModel(repository.localAnalysis); models.put("cancel", queue) }
            withTimeout(5000) { queue.state.first { !it.loading } }
            instrument.runOnMainSync { queue.add(inputs.map(Uri::fromFile), 90) }
            withTimeout(10000) { queue.state.first { it.running } }
            instrument.runOnMainSync { queue.cancel() }
            val cancelled = withTimeout(10000) { queue.state.first { !it.running && it.items.any { item -> item.status == BatchStatus.CANCELLED } } }
            assertFalse(cancelled.items.any { it.status == BatchStatus.QUEUED || it.status == BatchStatus.RUNNING })
            assertTrue(cancelled.items.filter { it.status == BatchStatus.DONE }.all { File(it.report!!).isFile })
        } finally {
            instrument.runOnMainSync { models.clear() }
            repository.store.close(); context.folder.deleteRecursively()
        }
    }
}

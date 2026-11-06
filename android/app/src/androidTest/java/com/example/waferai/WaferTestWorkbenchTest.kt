package com.example.waferai

import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.waferai.data.local.DemoWafer
import com.example.waferai.data.local.WaferTestHistory
import com.example.waferai.data.local.WaferTestPlan
import com.example.waferai.data.repository.ImportRepository
import com.example.waferai.ui.importdata.WaferTestScreen
import com.example.waferai.ui.importdata.WaferTestViewModel
import com.example.waferai.ui.theme.WaferAITheme
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WaferTestWorkbenchTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun simulationCancelsAndReplayProducesAnIsolatedReport() {
        val repository = ImportRepository(context)
        val baseline = repository.store.overview()
        val dbName = "wafer-test-${UUID.randomUUID()}.db"
        val history = WaferTestHistory(context, dbName)
        val model = WaferTestViewModel(repository, history)
        compose.setContent { WaferAITheme { WaferTestScreen(model, current = null, onBack = {}) } }
        compose.onNodeWithText("开始模拟测试").performScrollTo().performClick()
        compose.onNodeWithText("取消测试").performScrollTo().performClick()
        assertFalse(model.state.value.finished)
        assertNull(model.state.value.plan)
        assertTrue(history.list().isEmpty())

        compose.onNodeWithText("开始模拟测试").performScrollTo().performClick()
        compose.onNodeWithText("暂停测试").performScrollTo().performClick()
        val paused = model.state.value.completed
        android.os.SystemClock.sleep(400)
        assertTrue(model.state.value.paused)
        assertEquals(paused, model.state.value.completed)
        compose.onNodeWithText("继续测试").performClick()
        compose.waitUntil(15_000) { !model.state.value.running && model.state.value.prediction != null }
        compose.onNodeWithText("测试流程已完成").assertExists()
        assertEquals(317, model.state.value.completed)
        assertTrue(model.state.value.plan!!.source.contains("0～10 µA"))
        compose.onNodeWithText("查看异常位置").performScrollTo().performClick()
        compose.onNodeWithText("模拟漏电流", substring = true).assertExists()
        compose.onNodeWithText("新建测试").performScrollTo().performClick()
        compose.onNodeWithText("历史结果回放").performClick()
        compose.onNodeWithText("开始回放").performScrollTo().performClick()
        compose.waitUntil(15_000) { !model.state.value.running && model.state.value.prediction != null }
        val replay = model.state.value
        assertEquals(DemoWafer.WM_CENTER_1.goodCount, replay.passed)
        assertEquals(DemoWafer.WM_CENTER_1.badCount, replay.completed - replay.passed)
        assertTrue(replay.plan!!.source.startsWith("WM-811K"))
        assertNull(replay.plan.readingAt(20, 20))
        val savedAt = replay.completedAt
        WaferTestHistory(context, dbName).use { reopened ->
            val entries = reopened.list()
            assertEquals(listOf(WaferTestPlan.REPLAY, WaferTestPlan.SIMULATION), entries.map { it.mode })
            val original = reopened.open(entries.last().id).first
            val restored = WaferTestPlan.restore(original.plan)
            assertNotNull(restored.readingAt(10, 10))
            assertNull(restored.readingAt(0, 0))
        }
        compose.onNodeWithText("测试历史").performScrollTo().performClick()
        compose.onNodeWithText("WM811K-lot2 / W20").performClick()
        compose.waitUntil(5_000) { !model.state.value.openingRecord && model.state.value.recorded }
        assertEquals(savedAt, model.state.value.completedAt)
        compose.onNodeWithText("检测图表").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("芯片状态占比").fetchSemanticsNodes().isNotEmpty() }

        val file = File.createTempFile("wafer-test-replay-", ".pdf", context.cacheDir)
        try {
            compose.runOnIdle {
                assertNotNull(model.prepareReport())
                model.export(Uri.fromFile(file))
            }
            compose.waitUntil(10_000) { !model.state.value.exporting }
            assertEquals("测试 PDF 报告已保存。", model.state.value.message)
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use {
                assertTrue(it.pageCount >= 1)
            }
            assertEquals(baseline, repository.store.overview())
        } finally {
            file.delete()
            history.close()
            File(context.noBackupFilesDir, dbName).delete()
            repository.store.close()
        }
    }
}

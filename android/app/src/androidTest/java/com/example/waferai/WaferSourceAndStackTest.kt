package com.example.waferai

import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.waferai.data.local.DemoWafer
import com.example.waferai.data.local.WaferSource
import com.example.waferai.data.report.ReportSnapshot
import com.example.waferai.data.report.WaferPdfReport
import com.example.waferai.data.repository.ImportRepository
import com.example.waferai.ui.history.CsvUiState
import com.example.waferai.ui.history.HistoryScreen
import com.example.waferai.ui.history.HistoryUiState
import com.example.waferai.ui.importdata.ImportViewModel
import com.example.waferai.ui.importdata.WaferFilePreview
import com.example.waferai.ui.lots.LotFailureStackScreen
import com.example.waferai.ui.theme.WaferAITheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.text.Normalizer

class WaferSourceAndStackTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun previewCanBeCancelledThenConfirmedAndSourceReachesPdf() = withRepository { repository, directory ->
        val input = File(directory, "incoming.json")
        context.assets.open("samples/${DemoWafer.WM_CENTER_1.filename}").use { input.writeBytes(it.readBytes()) }
        val owner = ViewModelStore()
        val model = compose.runOnIdle { ImportViewModel(repository).also { owner.put("import", it) } }
        try {
            compose.setContent {
                WaferAITheme {
                    val state by model.state.collectAsState()
                    state.filePreview?.let { WaferFilePreview(it, model::confirmFileImport, model::dismissFilePreview) }
                }
            }
            compose.runOnIdle { model.previewFile(Uri.fromFile(input)) }
            compose.waitUntil(5_000) { model.state.value.filePreview != null }
            compose.onNodeWithText("导入预览").assertIsDisplayed()
            compose.onNodeWithText("晶圆图数据文件").assertIsDisplayed()
            assertEquals(0, repository.store.overview().wafers)
            compose.onNodeWithText("取消").performClick()
            assertEquals(0, repository.store.overview().wafers)
            compose.runOnIdle { model.previewFile(Uri.fromFile(input)) }
            compose.waitUntil(5_000) { model.state.value.filePreview != null }
            compose.onNodeWithText("确认导入").performClick()
            compose.waitUntil(5_000) { model.state.value.wafer != null }
            val saved = repository.store.detail(requireNotNull(model.state.value.wafer).id)
            assertEquals(WaferSource.GRID_FILE, saved.sourceKind)
            assertEquals("incoming.json", saved.filename)
            val report = File(directory, "source.pdf").apply { writeBytes(WaferPdfReport(ReportSnapshot(saved, null)).generate()) }
            PdfRenderer(ParcelFileDescriptor.open(report, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                assertTrue(pdf.pageCount > 0)
                if (Build.VERSION.SDK_INT >= 35) pdf.openPage(0).use { page ->
                    val text = Normalizer.normalize(page.textContents.joinToString("") { it.text }, Normalizer.Form.NFKC)
                    assertTrue(text.contains("晶圆图数据文件"))
                }
            }
        } finally {
            compose.runOnIdle { owner.clear() }
        }
    }

    @Test fun selectsThreeRealWafersAndOpensOneFromARepeatedFailure() = withRepository { repository, _ ->
        val samples = listOf(DemoWafer.WM_EDGE_LOCAL_1, DemoWafer.WM_LOCAL_2, DemoWafer.WM_CENTER_1)
        val wafers = runBlocking { samples.map { repository.loadDemo(it).wafer } }
        val lot = repository.store.listLots(1, 0).single()
        val records = repository.store.listWafers(20, 0, lot.id)
        assertTrue(records.all { it.sourceKind == WaferSource.WM811K })
        val result = runBlocking { repository.lotFailureStack(lot.id, wafers.map { it.id }.toIntArray()) }
        assertEquals(36, (0 until result.rows * result.cols).count { result.failuresAt(it) == 3 })
        assertEquals(217, result.repeatedPositions)
        var opened = -1
        compose.setContent {
            WaferAITheme {
                var selection by remember { mutableStateOf<IntArray?>(null) }
                val ids = selection
                if (ids == null) HistoryScreen(
                    HistoryUiState(records = records, hasMore = false, lot = lot),
                    {}, {}, {}, {}, {}, {}, {}, {}, CsvUiState(), {}, { _, _ -> },
                    lotCode = lot.lotCode, onStack = { selection = it }
                ) else LotFailureStackScreen(repository, lot.id, lot.lotCode, ids, { selection = null }, { opened = it })
            }
        }
        val history = compose.onNodeWithTag("history-records")
        history.performScrollToNode(hasText("同批失效叠加"))
        compose.onNodeWithText("同批失效叠加").performClick()
        for (wafer in wafers) {
            history.performScrollToNode(hasText(wafer.waferCode))
            compose.onNodeWithText(wafer.waferCode).performClick()
        }
        compose.onNodeWithText("坐标一致，生成叠加图").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("重复失效 217 个位置").fetchSemanticsNodes().isNotEmpty() }
        val stack = compose.onNodeWithTag("lot-stack-results")
        stack.performScrollToNode(hasText("定位失效最多的位置"))
        compose.onNodeWithText("定位失效最多的位置").performClick()
        stack.performScrollToNode(hasText("参与 3 片 · 不合格 3 片"))
        compose.onNodeWithText("参与 3 片 · 不合格 3 片").assertIsDisplayed()
        stack.performScrollToNode(hasText("W20"))
        compose.onNodeWithText("W20").performClick()
        assertEquals(wafers.last().id, opened)
    }

    private fun withRepository(block: (ImportRepository, File) -> Unit) {
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "wafer-source-").toFile()
        val prefix = directory.name
        val isolated = object : ContextWrapper(context) {
            override fun getDatabasePath(name: String) = context.getDatabasePath("$prefix-$name")
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) =
                context.openOrCreateDatabase("$prefix-$name", mode, factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?) = context.openOrCreateDatabase("$prefix-$name", mode, factory, errorHandler)
            override fun getNoBackupFilesDir(): File = directory
        }
        val repository = ImportRepository(isolated)
        try {
            block(repository, directory)
        } finally {
            repository.store.close()
            context.deleteDatabase("$prefix-waferai-local.db")
            directory.deleteRecursively()
        }
    }
}

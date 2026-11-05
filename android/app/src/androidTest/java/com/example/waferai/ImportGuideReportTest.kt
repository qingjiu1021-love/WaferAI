package com.example.waferai

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.waferai.data.local.*
import com.example.waferai.data.report.*
import com.example.waferai.ui.importdata.*
import com.example.waferai.ui.theme.WaferAITheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImportGuideReportTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun real() = WaferJson.parse(PracticeFiles.bytes(context, GuideFile.JSON), "practice.json").detail

    @Test fun practiceJsonCsvAndCalibratedPngDecodeToTheSameOriginalGrid() {
        val original = real()
        val csv = WaferCoordinateCsv.parse(PracticeFiles.bytes(context, GuideFile.CSV), "practice.csv").detail
        val image = WaferImage.parse(PracticeFiles.bytes(context, GuideFile.IMAGE), "practice.png",
            WaferImageOptions(53, 58, WaferPalette.RED_GREEN)).detail
        assertEquals(original.map, csv.map); assertEquals(original.map, image.map)
        assertEquals(2145, image.goodCount); assertEquals(315, image.badCount)
    }

    @Test fun guideHasActionableDataAndImagePaths() {
        var saved: GuideFile? = null
        var data = false
        var image = false
        compose.setContent { WaferAITheme {
            WaferImportGuide(GuideUiState(), false, { saved = it }, {}, { data = true }, { image = true }, {})
        } }
        compose.onNodeWithText("保存 JSON 练习文件").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(GuideFile.JSON, saved) }
        repeat(2) { compose.onNodeWithText("下一步").performClick() }
        compose.onNodeWithText("选择数据文件并导入").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(data) }
        compose.onNodeWithText("晶圆图片").performClick()
        compose.onNodeWithText("保存晶圆练习图片").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(GuideFile.IMAGE, saved) }
        repeat(2) { compose.onNodeWithText("下一步").performClick() }
        compose.onNodeWithText("选择晶圆图片并识别").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(image) }
    }

    @Test fun chartsCanSwitchToColumnsAndShowMeasuredValues() {
        compose.setContent { WaferAITheme {
            Column(Modifier.verticalScroll(rememberScrollState())) { ReportCharts(real()) }
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("芯片状态占比").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("芯片状态占比").assertExists()
        compose.onNodeWithText("逐列").performScrollTo().performClick()
        compose.onNodeWithContentDescription("逐列不合格率，0%至100%，共58列").assertExists()
    }

    @Test fun nativePdfWithChartsRemainsReadableAndPaginates() {
        val file = File(context.filesDir, "qa-report470.pdf")
        file.writeBytes(WaferPdfReport(ReportSnapshot(real(), null)).generate())
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer -> assertTrue(renderer.pageCount >= 2) }
        }
        for (map in listOf("[[1]]", "[[2]]", "[[0,0,0],[1,2,0]]")) {
            val wafer = WaferJson.parse("{\"lot_code\":\"QA\",\"wafer_code\":\"W\",\"map\":$map}".toByteArray(), "qa.json").detail
            assertTrue(WaferPdfReport(ReportSnapshot(wafer, null)).generate().size > 1000)
        }
    }
}

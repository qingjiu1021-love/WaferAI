package com.example.waferai

import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.waferai.data.ai.*
import com.example.waferai.data.analysis.*
import com.example.waferai.data.local.*
import com.example.waferai.ui.importdata.*
import com.example.waferai.ui.theme.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

class SummaryAnalysisTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun real() = WaferJson.parse(context.assets.open("samples/wm811k-real-center-01.json").use { it.readBytes() }, "PRIVATE_FILENAME.json").detail.copy(id = 7)

    @Test fun realSampleSummaryLocatesExactConnectedDiesAndCanClearHighlight() {
        val wafer = real()
        val summary = WaferSummary.calculate(wafer)
        val cluster = summary.findings.single { it.id == "largest_cluster" }
        assertEquals(80, cluster.indices.size)
        assertTrue(cluster.indices.all { wafer.map[it / wafer.cols][it % wafer.cols] == 2 })
        compose.setContent { WaferAITheme {
            var focus by remember { mutableStateOf<WaferFinding?>(null) }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                WaferSummaryCard(summary, null, { focus = it })
                WaferExplorer(wafer, WaferMapFilter.ALL, focus = focus, onClearFocus = { focus = null }, onFilterChange = {})
            }
        } }
        compose.onNodeWithTag("finding-largest_cluster").performScrollTo().performClick()
        compose.onNodeWithText("已定位：最大缺陷聚集区 · 高亮 80 颗不合格芯片").assertExists()
        compose.onNodeWithText("清除发现高亮").performScrollTo().performClick()
        compose.onNodeWithText("已定位：最大缺陷聚集区 · 高亮 80 颗不合格芯片").assertDoesNotExist()
    }

    @Test fun analysisPresetPersistsAndDefaultsCanBeRestoredWithoutTouchingUserPreferences() {
        val name = "qa-analysis-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(key: String, mode: Int) = context.getSharedPreferences("$name-$key", mode)
        }
        try {
            val prefs = AppearancePreferences(isolated)
            assertEquals(AnalysisPreset.ENGINEERING, prefs.load().analysisPreset)
            prefs.save(prefs.load().copy(analysisPreset = AnalysisPreset.CHARTS))
            assertEquals(AnalysisPreset.CHARTS, AppearancePreferences(isolated).load().analysisPreset)
            prefs.save(Appearance())
            assertEquals(AnalysisPreset.ENGINEERING, prefs.load().analysisPreset)
        } finally { context.deleteSharedPreferences("$name-appearance") }
    }

    @Test fun specializedAnalysisSendsBoundedFactsAndTemplateAndRendersVerifiedReferences(): Unit = runBlocking {
        val wafer = real()
        val prediction = MobilePredictor(context).predict(wafer)
        val facts = AnalysisEvidence.facts(wafer, prediction, 90)
        val sources = listOf(AnalysisSource("K1", "本地参考", "参考资料不是当前样本测量", "https://example.org/source"))
        val packet = AnalysisEvidence.packet(wafer, prediction, 90, facts, sources)
        assertFalse(packet.has("map")); assertFalse(packet.toString().contains("PRIVATE_FILENAME"))
        val reply = """{"conclusion":"中心区域值得复核","fact_ids":["yield","largest_cluster"],"interpretation":"查看聚集位置再结合测试记录解释","hypotheses":[],"steps":["核对原始测试结果"],"missing_information":["设备与工艺记录"],"source_ids":["K1"]}"""
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(JSONObject().put("choices", org.json.JSONArray().put(JSONObject()
                .put("finish_reason", "stop").put("message", JSONObject().put("content", reply)))).toString()))
            val raw = DeepSeekClient().analyze("dummy-qa-key", "qa-model", packet, server.url("/v1/chat/completions").toString(),
                AiProvider.OPENAI, AiProtocol.OPENAI, AnalysisPreset.QUICK)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            val payload = request.body.readUtf8()
            assertTrue(payload.contains("快速解读")); assertTrue(payload.contains("fact_registry"))
            assertTrue(payload.contains("references_untrusted"))
            val result = VerifiedAnalysis.render(raw, facts, sources, AnalysisPreset.QUICK)
            assertTrue(result.contains("87.20%")); assertTrue(result.contains("80 颗"))
            assertTrue(result.contains("https://example.org/source"))
            assertThrows(IllegalArgumentException::class.java) {
                VerifiedAnalysis.render(reply.replace("中心区域值得复核", "良率99%"), facts, sources, AnalysisPreset.QUICK)
            }
        }
    }
}

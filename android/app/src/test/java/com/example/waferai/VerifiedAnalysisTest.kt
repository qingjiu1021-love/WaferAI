package com.example.waferai

import com.example.waferai.data.ai.*
import com.example.waferai.data.analysis.SummaryFact
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class VerifiedAnalysisTest {
    private val facts = listOf(SummaryFact("yield", "芯片良率 87.20%"), SummaryFact("counts", "合格 2145，不合格 315"))
    private val sources = listOf(AnalysisSource("K1", "本地知识", "仅为参考", "https://example.org/source"))
    private val json = """{"conclusion":"中心区域值得复核","fact_ids":["yield","counts"],"interpretation":"先对照原始网格确认聚集位置","hypotheses":[{"cause":"测试接触差异可能影响分布","fact_ids":["counts"],"verification":"结合测试记录复核"}],"steps":["核对网格与测试记录"],"missing_information":["设备及工艺记录"],"source_ids":["K1"]}"""
    @Test fun factsAndSourcesAreRenderedFromApplicationDataForEveryPreset() {
        AnalysisPreset.entries.forEach { preset ->
            val text = VerifiedAnalysis.render(json, facts, sources, preset)
            assertTrue(text.contains("87.20%")); assertTrue(text.contains("2145"))
            assertTrue(text.contains("解析模板：${preset.title}"))
            assertTrue(text.contains("https://example.org/source"))
            assertTrue(text.contains("模型解释仍需人工复核"))
        }
    }
    @Test fun keepsNarrativeReadableWithoutMarkdownMarkers() {
        val value = JsonParser.parseString(json).asJsonObject.apply { addProperty("conclusion", "### **中心区域值得复核**") }
        val result = VerifiedAnalysis.render(value.toString(), facts, sources, AnalysisPreset.ENGINEERING)
        assertFalse(result.contains("###")); assertFalse(result.contains("**"))
    }
    @Test fun rejectsNumericNarrativesIncludingFullWidthAndPercentSymbols() {
        for (bad in listOf("良率99%", "良率９９％", "为百分之九十九", "第２行异常")) {
            val value = JsonParser.parseString(json).asJsonObject.apply { addProperty("conclusion", bad) }
            assertThrows(IllegalArgumentException::class.java) { VerifiedAnalysis.render(value.toString(), facts, sources, AnalysisPreset.ENGINEERING) }
        }
    }
    @Test fun rejectsUnknownFactAndSourceReferences() {
        for ((key, replacement) in listOf("yield" to "invented", "K1" to "K999")) {
            assertThrows(IllegalArgumentException::class.java) { VerifiedAnalysis.render(json.replace("\"$key\"", "\"$replacement\""), facts, sources, AnalysisPreset.ENGINEERING) }
        }
    }
    @Test fun rejectsMalformedExtraFieldsAndMissingRequiredSections() {
        val extra = JsonParser.parseString(json).asJsonObject.apply { addProperty("invented_yield", 99) }
        for (value in listOf("正常文本", "{}", extra.toString(), "[".repeat(160))) {
            assertThrows(IllegalArgumentException::class.java) { VerifiedAnalysis.render(value, facts, sources, AnalysisPreset.QUICK) }
        }
    }
    @Test fun acceptsOnlyBoundedStructuredOutputAndOptionalJsonFence() {
        assertTrue(VerifiedAnalysis.render("```json\n$json\n```", facts, sources, AnalysisPreset.QUICK).contains("观测事实"))
        val tooMany = JsonParser.parseString(json).asJsonObject
        tooMany.getAsJsonArray("hypotheses").add(tooMany.getAsJsonArray("hypotheses")[0].deepCopy())
        assertThrows(IllegalArgumentException::class.java) { VerifiedAnalysis.render(tooMany.toString(), facts, sources, AnalysisPreset.QUICK) }
    }
}

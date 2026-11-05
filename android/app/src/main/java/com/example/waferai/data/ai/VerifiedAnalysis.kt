package com.example.waferai.data.ai

import com.example.waferai.data.analysis.SummaryFact
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal data class AnalysisSource(val id: String, val title: String, val body: String, val url: String)

/** Model narratives contain no numeric claims. Only application-owned facts carry displayed numbers. */
internal object VerifiedAnalysis {
    const val Header = "事实引用已核对"
    fun render(raw: String, facts: List<SummaryFact>, sources: List<AnalysisSource>, preset: AnalysisPreset): String {
        fun reject(): Nothing = throw IllegalArgumentException("AI 回复未通过格式或数值引用核对，未保存新解析。可重试或切换模板；已有解析和本地摘要仍保留。")
        if (raw.length > 16000 || raw.count { it == '{' || it == '[' } > 150) reject()
        var text = raw.trim()
        if (text.startsWith("```json") && text.endsWith("```")) text = text.removePrefix("```json").removeSuffix("```").trim()
        val root = try { JsonParser.parseString(text).asJsonObject } catch (_: Exception) { reject() }
        val required = setOf("conclusion", "fact_ids", "interpretation", "hypotheses", "steps", "missing_information", "source_ids")
        if (root.keySet() != required) reject()
        fun phrase(objectValue: JsonObject, key: String, max: Int = 500): String {
            val item = objectValue[key] ?: reject()
            if (!item.isJsonPrimitive || !item.asJsonPrimitive.isString) reject()
            val value = item.asString.trim()
            if (value.isBlank() || value.length > max || Regex("[\\p{N}%％]|百分之").containsMatchIn(value) || value.any { it.isISOControl() && it != '\n' }) reject()
            return readableReply(value).takeIf { it.isNotBlank() } ?: reject()
        }
        fun ids(objectValue: JsonObject, key: String, allowed: Set<String>, max: Int, min: Int): List<String> {
            val array = objectValue[key]?.takeIf { it.isJsonArray }?.asJsonArray ?: reject()
            if (array.size() !in min..max) reject()
            val result = array.map { if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) reject(); it.asString }
            if (result.toSet().size != result.size || result.any { it !in allowed }) reject()
            return result
        }
        fun phrases(key: String, max: Int, min: Int): List<String> {
            val array = root[key]?.takeIf { it.isJsonArray }?.asJsonArray ?: reject()
            if (array.size() !in min..max) reject()
            return array.map { item -> phrase(JsonObject().apply { add("value", item) }, "value", 300) }
        }
        val dictionary = facts.associateBy { it.id }
        val chosen = ids(root, "fact_ids", dictionary.keys, 8, 1)
        val cited = ids(root, "source_ids", sources.map { it.id }.toSet(), 3, 0)
        val hypotheses = root["hypotheses"]?.takeIf { it.isJsonArray }?.asJsonArray ?: reject()
        if (hypotheses.size() > preset.maxHypotheses) reject()
        val renderedHypotheses = hypotheses.map { element ->
            val value = element.takeIf { it.isJsonObject }?.asJsonObject ?: reject()
            if (value.keySet() != setOf("cause", "fact_ids", "verification")) reject()
            val evidence = ids(value, "fact_ids", dictionary.keys, 4, 1)
            "待验证假设：${phrase(value, "cause", 240)}\n支持事实：${evidence.joinToString("；") { dictionary.getValue(it).text }}\n验证方法：${phrase(value, "verification", 300)}"
        }
        val conclusion = phrase(root, "conclusion", 240)
        val interpretation = phrase(root, "interpretation", 800)
        val steps = phrases("steps", preset.maxSteps, 1)
        val missing = phrases("missing_information", 6, 0)
        return buildString {
            append("解析模板：${preset.title}\n$Header：统计值由本机填入，模型解释仍需人工复核。\n\n")
            append("一句话结论\n$conclusion\n\n观测事实\n")
            chosen.forEach { append(dictionary.getValue(it).text).append('\n') }
            append("\n模式解读（模型解释）\n$interpretation\n\n")
            if (renderedHypotheses.isNotEmpty()) append(renderedHypotheses.joinToString("\n\n")).append("\n\n")
            append("验证顺序\n").append(steps.mapIndexed { i, value -> "${i + 1}. $value" }.joinToString("\n"))
            append("\n\n缺失信息\n").append(missing.joinToString("；").ifEmpty { "模型未列出；电测和工艺记录仍需人工核对。" })
            if (cited.isNotEmpty()) {
                append("\n\n知识库参考（参考资料并非本片测量）\n")
                cited.forEach { id -> val source = sources.single { it.id == id }; append("$id · ${source.title}\n${source.url}\n") }
            }
        }.trim()
    }
}

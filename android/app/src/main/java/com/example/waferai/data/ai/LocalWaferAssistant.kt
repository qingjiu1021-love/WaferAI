package com.example.waferai.data.ai

import com.example.waferai.data.remote.WaferDetail
import com.example.waferai.data.remote.WaferPrediction
import java.util.Locale

internal object LocalWaferAssistant {
    const val Name = "基础助手"

    fun topic(question: String, history: List<ChatTurn>): String {
        val text = question.lowercase(Locale.ROOT)
        val followUp = text in setOf("为什么", "为什么？", "继续", "继续。", "what next?", "why?", "continue")
        return if (followUp) history.lastOrNull()?.question?.lowercase(Locale.ROOT).orEmpty() else text
    }

    fun answer(question: String, history: List<ChatTurn>, summary: String?, english: Boolean): String {
        val topic = topic(question, history)
        val evidence = summary ?: history.lastOrNull { it.waferSummary != null }?.waferSummary
        val guidance = when {
            topic in setOf("你好", "你好！", "你好!", "hi", "hello", "hello!") -> if (english)
                "Hello! I can help with the app and saved knowledge. Select an online model for open-ended conversation, writing, translation or coding."
                else "你好！我可以帮你了解应用和已保存的知识。想自由聊天、写作、翻译或编程，可以在模型选择中切换到在线 AI。"
            topic.hasAny("边缘", "环形", "edge", "ring") -> if (english) {
                "An edge-ring pattern follows much of the outer rim. An edge-local pattern is concentrated in a small part of it. Highlight failures and inspect whether the red area wraps around the wafer."
            } else {
                "边缘环形缺陷沿晶圆外圈大范围分布，边缘局部缺陷通常只集中在外圈的一段。可以在晶圆图中打开「突出不合格」，观察红色区域是否绕外圈连续分布。"
            }
            topic.hasAny("排查", "根因", "低良率", "troubleshoot", "root cause", "low yield") -> if (english) {
                "First verify the die counts and yield. Then inspect the failed dies and compare the center, middle and edge rates. Finally compare wafers in the same lot and check electrical tests and process records before assigning a cause."
            } else {
                "先核对有效芯片数和良率，再查看不合格芯片的位置及中心、中部、边缘的不合格率。随后对照同批次其他晶圆，结合电测结果和工艺记录复核原因。"
            }
            topic.hasAny("晶圆图", "wafer map") && topic.hasAny("良率", "yield") -> if (english) {
                "Green dies pass, red dies fail, and gray cells are outside the wafer. Yield is passing dies divided by passing plus failing dies. Tap a die to see its row, column and status."
            } else {
                "绿色芯片合格，红色芯片不合格，灰色区域不参与良率计算。良率等于合格芯片数除以有效芯片数。点一下晶圆图中的格子，可以查看原始行列和状态。"
            }
            topic.hasAny("良率", "合格", "yield", "pass rate") -> if (english) {
                "Yield equals passing dies divided by valid dies. Valid dies are passing plus failing dies. Outside-wafer cells are excluded. Lot yield uses the pooled die counts."
            } else {
                "良率等于合格芯片数除以有效芯片数。有效芯片包括合格与不合格，矩阵中的非芯片区域不参与计算。批次良率按总合格数与总有效芯片数计算。"
            }
            topic.hasAny("晶圆图", "颜色", "wafer map", "color") -> if (english) {
                "Green cells are passing dies, red cells are failing dies, and gray cells are outside the wafer. Zoom in and tap a cell to inspect its position and status."
            } else {
                "晶圆图中绿色表示合格，红色表示不合格，灰色是非芯片区域。可以双指放大，再点某个格子查看原始行列与状态。"
            }
            topic.hasAny("缺陷", "识别", "defect", "classif") -> if (english) {
                "The on-device classifier recognizes spatial defect patterns such as center, edge ring and scratch. Compare the predicted class with the highlighted wafer map and test records."
            } else {
                "本地 CNN 识别的是中心型、边缘环形、划痕等空间分布模式。可以结合晶圆图和实际测试记录核对分类，模型得分表示对该类别的倾向。"
            }
            topic.hasAny("报告", "导出", "report", "export", "csv") -> if (english) {
                "Open a wafer to export a PDF report. In History or Low-yield review, export all matching records as CSV for Excel."
            } else {
                "在晶圆工作台可导出单片 PDF 报告。在「历史与搜索」或「低良率排查」可将筛选结果导出为 CSV，再用 Excel 打开。"
            }
            evidence != null -> if (english) "Review the attached wafer summary below. Ask about yield, the map, or troubleshooting for more detail."
                else "可以先看下面的晶圆摘要，再追问良率、晶圆图或排查顺序。"
            else -> if (english) "The offline assistant uses rules and saved knowledge; it is not a general language model. Select an online AI model for this question. You can freely discuss everyday topics, writing, translation or code there."
                else "离线基础助手使用规则和已保存知识，并非通用大模型。这个问题可以切换到在线 AI 来讨论，支持日常话题、写作、翻译和编程等自由对话。"
        }
        return if (evidence == null) guidance else guidance + "\n\n" +
            (if (english) "Attached wafer summary\n" else "已附晶圆摘要\n") + evidence
    }

    fun summarize(wafer: WaferDetail, prediction: WaferPrediction?, english: Boolean): String {
        if (!english) return ChatPrompt.summarize(wafer, prediction)
        val summary = String.format(Locale.ROOT, "%d × %d map. Valid dies: %d. Pass: %d. Fail: %d. Yield: %.2f%%.",
            wafer.rows, wafer.cols, wafer.totalCount, wafer.goodCount, wafer.badCount, wafer.yieldRate * 100)
        return prediction?.takeIf { it.waferId == wafer.id }?.let { "$summary On-device prediction: ${it.label}." } ?: summary
    }

    private fun String.hasAny(vararg terms: String) = terms.any { contains(it) }
}

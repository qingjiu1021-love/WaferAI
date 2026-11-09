package com.example.waferai.data.report

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.waferai.data.remote.WaferDetail
import com.example.waferai.data.remote.WaferPrediction
import com.example.waferai.data.local.AiAnalysis
import com.example.waferai.data.local.InspectionRecord
import com.example.waferai.data.local.WaferSource
import com.example.waferai.data.local.WaferTestComparison
import com.example.waferai.data.local.testLimitText
import com.example.waferai.data.analysis.WaferClusters
import com.example.waferai.data.analysis.WaferSpatialStats
import android.graphics.DashPathEffect
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

data class ReportSnapshot(
    val wafer: WaferDetail,
    val prediction: WaferPrediction?,
    val requestedAt: Instant = Instant.now(),
    val analysis: AiAnalysis? = null,
    val inspection: InspectionRecord? = null,
    val testSource: String? = null,
    val testCompletedAt: Instant? = null,
    val testComparison: WaferTestComparison? = null
) {
    fun suggestedFilename(): String {
        val safe = "${wafer.lotCode}_${wafer.waferCode}"
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        val code = safe.substring(0, safe.offsetByCodePoints(0, minOf(40, safe.codePointCount(0, safe.length))))
        val time = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneId.of("Asia/Shanghai")).format(requestedAt)
        return "WaferAI_${code}_$time.pdf"
    }
}

/** Native Android PDF generation; the snapshot is independent of later UI changes. */
class WaferPdfReport(private val snapshot: ReportSnapshot) {
    private val document = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var pageNumber = 0
    private var y = 0f
    private var bitmap: Bitmap? = null
    private val margin = 36f
    private val width = 595
    private val height = 842
    private val contentWidth = width - margin * 2
    private val ink = Color.rgb(31, 41, 55)
    private val muted = Color.rgb(89, 101, 118)
    private val blue = Color.rgb(55, 78, 128)
    private val good = Color.rgb(46, 125, 50)
    private val bad = Color.rgb(211, 47, 47)
    private val outside = Color.rgb(236, 239, 241)
    private val clusters by lazy { WaferClusters.calculate(snapshot.wafer.map) }

    private fun paint(size: Float, color: Int = ink, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun newPage() {
        finishPage()
        pageNumber++
        page = document.startPage(PdfDocument.PageInfo.Builder(width, height, pageNumber).create())
        page!!.canvas.drawText("WaferAI 晶圆分析报告", margin, 53f, paint(21f, blue, true))
        page!!.canvas.drawLine(margin, 69f, width - margin, 69f, paint(1f, Color.LTGRAY))
        y = 83f
    }

    private fun finishPage() {
        val active = page ?: return
        val footer = paint(9f, muted)
        active.canvas.drawText("WaferAI | 单张晶圆数据与模型识别报告", margin, height - 23f, footer)
        val number = "第 $pageNumber 页"
        active.canvas.drawText(number, width - margin - footer.measureText(number), height - 23f, footer)
        document.finishPage(active)
        page = null
    }

    private fun reserve(space: Float) {
        if (y + space > height - 47f) newPage()
    }

    private fun paragraph(text: String, size: Float = 10.5f, color: Int = ink, bold: Boolean = false) {
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint(size, color, bold), contentWidth.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
            .setLineSpacing(2f, 1f).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build()
        var first=0
        while(first<layout.lineCount) {
            val top=layout.getLineTop(first)
            reserve((layout.getLineBottom(first)-top+7).toFloat())
            val available=height-47f-y
            var last=first
            while(last+1<layout.lineCount && layout.getLineBottom(last+1)-top<=available) last++
            val partHeight=layout.getLineBottom(last)-top
            page!!.canvas.save()
            page!!.canvas.translate(margin,y)
            page!!.canvas.clipRect(0f,0f,contentWidth,partHeight.toFloat())
            page!!.canvas.translate(0f,-top.toFloat())
            layout.draw(page!!.canvas)
            page!!.canvas.restore()
            y+=partHeight
            first=last+1
            if(first<layout.lineCount) newPage()
        }
        y+=7f
    }

    private fun statistics() {
        reserve(75f)
        val wafer = snapshot.wafer
        val values = listOf("合格芯片" to wafer.goodCount.toString(), "不合格芯片" to wafer.badCount.toString(),
            "有效芯片" to wafer.totalCount.toString(), "芯片良率" to String.format(Locale.CHINA, "%.2f%%", wafer.yieldRate * 100))
        val cellWidth = (contentWidth - 24f) / 4
        values.forEachIndexed { index, (label, value) ->
            val left = margin + index * (cellWidth + 8f)
            page!!.canvas.drawRoundRect(RectF(left, y, left + cellWidth, y + 61f), 5f, 5f, paint(1f, Color.rgb(242, 245, 250)))
            page!!.canvas.drawText(label, left + 10, y + 19, paint(10f, muted))
            page!!.canvas.drawText(value, left + 10, y + 47, paint(19f, blue, true))
        }
        y += 75f
    }

    private val chartData by lazy { WaferReportData.calculate(snapshot.wafer) }

    private fun thresholdComparison(comparison: WaferTestComparison) {
        reserve(125f)
        paragraph("仿真阈值对比", 12f, blue, true)
        val baseline = String.format(Locale.ROOT, "%.2f%%", comparison.baselineYield * 100)
        val alternate = String.format(Locale.ROOT, "%.2f%%", comparison.comparisonYield * 100)
        paragraph("基准上限 ${testLimitText(comparison.baselineLimit)} µA · 合格 ${comparison.baselinePassed}/${comparison.total} · 良率 $baseline")
        paragraph("对比上限 ${testLimitText(comparison.comparisonLimit)} µA · 合格 ${comparison.comparisonPassed}/${comparison.total} · 良率 $alternate")
        val delta = String.format(Locale.ROOT, "%+.2f", comparison.yieldDeltaPoints)
        val changed = comparison.changedDies
        val direction = if (comparison.becamePass) "不合格 → 合格" else "合格 → 不合格"
        val changeLabel = if (changed.isEmpty()) "判定无变化" else "判定变化 ${changed.size} 颗（$direction）"
        paragraph("良率变化 $delta 个百分点 · $changeLabel")
        if (changed.isNotEmpty()) {
            val example = changed.take(20).joinToString("  ") { "(${it.row}, ${it.col})" }
            paragraph("变化坐标（行, 列，最多前 20 颗）：$example", 9f, muted)
        }
        paragraph("两种上限使用同一组仿真读数，读数不超过上限即合格。报告晶圆图、图表和 AI 分类对应基准判定。", 9f, muted)
    }

    private fun detectionCharts() {
        newPage()
        paragraph("数据图表", 16f, blue, true)
        val data = chartData
        reserve(142f)
        paragraph("芯片状态占比", 12f, blue, true)
        val canvas = page!!.canvas
        val ring = RectF(margin + 8f, y + 3f, margin + 106f, y + 101f)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 12f }
        stroke.color = good
        canvas.drawArc(ring, -90f, (data.yield * 360).toFloat(), false, stroke)
        stroke.color = bad
        canvas.drawArc(ring, (-90 + data.yield * 360).toFloat(), ((1 - data.yield) * 360).toFloat(), false, stroke)
        val value = String.format(Locale.ROOT, "%.2f%%", data.yield * 100)
        val center = paint(17f, blue, true)
        canvas.drawText(value, ring.centerX() - center.measureText(value) / 2, ring.centerY() + 6f, center)
        val x = margin + 140f
        canvas.drawText("合格 ${data.good}  ($value)", x, y + 25f, paint(12f, good, true))
        canvas.drawText("不合格 ${data.bad}  (${String.format(Locale.ROOT, "%.2f%%", (1-data.yield)*100)})", x, y + 50f, paint(12f, bad, true))
        canvas.drawText("有效芯片 ${data.total}，非芯片区域 ${data.outside} 格", x, y + 76f, paint(10f, muted))
        y += 122f
        paragraph("非芯片区域不计入占比；图表与原始矩阵使用同一份统计。", 9f, muted)
        reserve(122f)
        paragraph("分区不合格率（横轴 0%—100%）", 12f, blue, true)
        data.rings.forEach { region ->
            val left = margin + 150f
            val right = width - margin - 66f
            val rate = region.badRate
            page!!.canvas.drawText("${region.name}  ${region.bad}/${region.total}", margin, y + 11f, paint(9f))
            page!!.canvas.drawRect(left, y + 2f, right, y + 11f, paint(1f, outside))
            if (rate != null) page!!.canvas.drawRect(left, y + 2f, left + (right-left) * rate.toFloat(), y + 11f, paint(1f, bad))
            page!!.canvas.drawText(rate?.let { String.format(Locale.ROOT, "%.2f%%", it*100) } ?: "无数据", right + 8f, y + 11f, paint(9f))
            y += 20f
        }
        paragraph("矩阵归一化半径以 0.35 / 0.70 分区，是几何参考，不代表实际制造边界。", 9f, muted)
        positionChart(data.rows, "行")
        positionChart(data.columns, "列")
        paragraph("分布曲线横轴为网格位置，非时间趋势；无有效芯片的行列断开绘制。", 9f, muted)
    }

    private fun positionChart(series: List<PositionRate>, unit: String) {
        reserve(160f)
        paragraph("逐${unit}不合格率", 12f, blue, true)
        val left = margin + 32f
        val top = y + 6f
        val chartWidth = contentWidth - 42f
        val chartHeight = 72f
        val canvas = page!!.canvas
        for (percent in listOf(0, 50, 100)) {
            val py = top + chartHeight * (1 - percent / 100f)
            canvas.drawLine(left, py, left + chartWidth, py, paint(1f, Color.LTGRAY))
            canvas.drawText("$percent%", margin, py + 3f, paint(8f, muted))
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blue; strokeWidth = 1.5f }
        var previous: Pair<Float, Float>? = null
        series.forEachIndexed { index, value ->
            val rate = value.rate
            if (rate == null) previous = null else {
                val x = left + chartWidth * index / (series.size - 1).coerceAtLeast(1)
                val py = top + chartHeight * (1 - rate.toFloat())
                previous?.let { canvas.drawLine(it.first, it.second, x, py, line) }
                canvas.drawCircle(x, py, 1.3f, line)
                previous = x to py
            }
        }
        canvas.drawText("1$unit", left, top + chartHeight + 14f, paint(8f, muted))
        val end = "${series.size}$unit"
        canvas.drawText(end, left + chartWidth - paint(8f).measureText(end), top + chartHeight + 14f, paint(8f, muted))
        y += 108f
        val peak = series.filter { it.rate != null }.maxByOrNull { it.rate!! }
        paragraph(peak?.let { "最高：第 ${it.number} $unit，${String.format(Locale.ROOT, "%.2f%%", it.rate!!*100)}，不合格 ${it.bad}/${it.total}。" } ?: "无有效芯片。", 9f, muted)
    }

    private fun waferMap() {
        val wafer = snapshot.wafer
        // Leave room for the anomaly coordinates below the comparison report's map.
        val maxMapHeight = if (snapshot.testComparison != null) 180f else 260f
        val scale = min(360f / wafer.cols, maxMapHeight / wafer.rows)
        val mapWidth = wafer.cols * scale
        val mapHeight = wafer.rows * scale
        reserve(mapHeight + 62f)
        val title = if (snapshot.testComparison != null) "基准判定晶圆图" else "原始晶圆图"
        paragraph("$title（${wafer.rows} 行 × ${wafer.cols} 列）", 12f, blue, true)
        val pixels = IntArray(wafer.rows * wafer.cols) { index ->
            when (wafer.map[index / wafer.cols][index % wafer.cols]) {
                1 -> good
                2 -> bad
                else -> outside
            }
        }
        val image = Bitmap.createBitmap(pixels, wafer.cols, wafer.rows, Bitmap.Config.ARGB_8888)
        bitmap = image
        val left = (width - mapWidth) / 2
        val imagePaint = Paint().apply { isFilterBitmap = false }
        page!!.canvas.drawBitmap(image, null, RectF(left, y, left + mapWidth, y + mapHeight), imagePaint)
        if (wafer.rows <= 128 && wafer.cols <= 128) {
            val grid = Paint().apply { color = Color.WHITE; strokeWidth = 0.2f }
            for (column in 0..wafer.cols) {
                val x = left + column * scale
                page!!.canvas.drawLine(x, y, x, y + mapHeight, grid)
            }
            for (row in 0..wafer.rows) {
                val top = y + row * scale
                page!!.canvas.drawLine(left, top, left + mapWidth, top, grid)
            }
        }
        clusters.largest?.let { region ->
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.rgb(22, 45, 130)
                strokeWidth = 1.5f
                pathEffect = DashPathEffect(floatArrayOf(4f, 2f), 0f)
            }
            page!!.canvas.drawRect(
                left + (region.firstCol - 1) * scale, y + (region.firstRow - 1) * scale,
                left + region.lastCol * scale, y + region.lastRow * scale, outline
            )
        }
        y += mapHeight + 12f
        listOf(good to "合格", bad to "不合格", outside to "非芯片区域").forEachIndexed { index, (color, label) ->
            val x = margin + 95f + index * 120f
            page!!.canvas.drawRect(x, y, x + 9, y + 9, paint(1f, color))
            page!!.canvas.drawText(label, x + 14, y + 9, paint(10f))
        }
        y += 26f
    }

    private fun anomalyLocations() {
        val wafer = snapshot.wafer
        reserve(65f)
        paragraph("异常位置", 12f, blue, true)
        val largest = clusters.largest
        if (largest == null) {
            paragraph("原始数据中没有不合格芯片。")
            return
        }
        paragraph("不合格芯片 ${wafer.badCount} 颗，连通区 ${clusters.count} 处。图中蓝色虚线标示最大连通区的外接范围。")
        paragraph("最大连通区 ${largest.size} 颗，第 ${largest.firstRow}–${largest.lastRow} 行，第 ${largest.firstCol}–${largest.lastCol} 列。")
        val example = sequence {
            wafer.map.forEachIndexed { row, values ->
                values.forEachIndexed { col, status -> if (status == 2) yield("(${row + 1}, ${col + 1})") }
            }
        }.take(20).joinToString("  ")
        paragraph("不合格坐标（行, 列，最多前 20 颗）：$example", 9f, muted)
    }

    fun generate(): ByteArray {
        try {
            newPage()
            val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.of("Asia/Shanghai")).format(snapshot.requestedAt)
            paragraph("报告时间（北京时间）：$time", 9f, muted)
            paragraph("批次：${snapshot.wafer.lotCode}", bold = true)
            val record = if (snapshot.wafer.id > 0) "    记录编号：${snapshot.wafer.id}" else ""
            paragraph("晶圆编号：${snapshot.wafer.waferCode}$record")
            val source = snapshot.wafer.sourceKind
            if (snapshot.testSource == null || source != WaferSource.UNKNOWN && source != WaferSource.SIMULATION) {
                paragraph("数据来源：${source.label}", 10f, blue, true)
                if (source != WaferSource.UNKNOWN) paragraph(source.description, 9f, muted)
            }
            snapshot.testSource?.let { paragraph("测试来源：$it", 10f, blue, true) }
            snapshot.testCompletedAt?.let {
                val completed = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.of("Asia/Shanghai")).format(it)
                paragraph("测试完成（北京时间）：$completed", 9f, muted)
            }
            paragraph("来源文件：${snapshot.wafer.filename}", 9.5f, muted)
            y += 3f
            statistics()
            snapshot.testComparison?.let { thresholdComparison(it) }
            snapshot.inspection?.let { inspection ->
                val wafer = snapshot.wafer
                val review = if (100L * wafer.goodCount < inspection.threshold.toLong() * wafer.totalCount)
                    "低于阈值，建议排查" else "达到阈值"
                paragraph("检测结论：$review（检测时良率阈值 ${inspection.threshold}%）", bold = true)
                paragraph("检测时间（UTC）：${inspection.checkedAt}", 9f, muted)
            }
            reserve(77f)
            paragraph("缺陷模式识别", 12f, blue, true)
            val prediction = snapshot.prediction
            if (prediction == null) {
                paragraph("尚未进行缺陷识别。报告保留原始晶圆图与良率统计。")
            } else {
                paragraph("${prediction.labelZh}（${prediction.label}）", 16f, ink, true)
                val score = if (prediction.score > 0.9999) ">99.99%" else
                    String.format(Locale.CHINA, "%.2f%%", prediction.score * 100)
                paragraph("模型得分：$score    模型版本：${prediction.modelVersion}")
            }
            y += 3f
            waferMap()
            anomalyLocations()
            detectionCharts()
            paragraph("良率 = 合格芯片数 ÷ 有效芯片总数；非芯片区域不计入分母。", 9f, muted)
            if (prediction != null) {
                paragraph("模型得分表示当前样本的分类倾向，未经概率校准。", 9f, muted)
                if (prediction.label == "none") {
                    paragraph("无特定缺陷模式不表示所有芯片均合格，请结合原始良率查看。", 9f, muted)
                }
                paragraph("识别记录时间（UTC）：${prediction.createdAt}", 9f, muted)
                paragraph("预处理版本：${prediction.preprocessingId}", 9f, muted)
                paragraph("模型 SHA-256：${prediction.modelSha256.chunked(16).joinToString(" ")}", 9f, muted)
            }
            snapshot.analysis?.let { analysis ->
                reserve(90f)
                paragraph("AI 在线解析",12f,blue,true)
                paragraph("解析模型：${analysis.model}    保存时间（UTC）：${analysis.createdAt}",9f,muted)
                paragraph("以下为在线模型生成的解释与待验证假设，不代表已确认的工艺根因。",9f,muted)
                analysis.content.lines().filter { it.isNotBlank() }.forEach { paragraph(it) }
            }
            finishPage()
            val output = ByteArrayOutputStream()
            document.writeTo(output)
            return output.toByteArray()
        } finally {
            page?.let { document.finishPage(it); page = null }
            document.close()
            bitmap?.recycle()
        }
    }
}

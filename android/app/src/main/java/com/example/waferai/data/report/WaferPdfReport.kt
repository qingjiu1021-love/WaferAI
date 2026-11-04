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
    val inspection: InspectionRecord? = null
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

    private fun detectionCharts() {
        val wafer = snapshot.wafer
        reserve(116f)
        paragraph("芯片状态与分区不合格率", 12f, blue, true)
        val canvas = page!!.canvas
        val goodEdge = margin + contentWidth * wafer.yieldRate.toFloat()
        canvas.drawRect(margin, y, goodEdge, y + 9f, paint(1f, good))
        canvas.drawRect(goodEdge, y, width - margin, y + 9f, paint(1f, bad))
        y += 12f
        paragraph("绿色为合格，红色为不合格。", 9f, muted)
        WaferSpatialStats.calculate(wafer).rings.forEach { region ->
            val label = "${region.name}  ${region.bad}/${region.total}"
            val ratio = region.badRate
            val text = ratio?.let { String.format(Locale.ROOT, "%.2f%%", it * 100) } ?: "无有效芯片"
            val start = margin + 145f
            val end = width - margin - 62f
            canvas.drawText(label, margin, y + 10f, paint(9f))
            canvas.drawRect(start, y + 2f, end, y + 10f, paint(1f, outside))
            ratio?.let { canvas.drawRect(start, y + 2f, start + (end - start) * it.toFloat(), y + 10f, paint(1f, bad)) }
            canvas.drawText(text, end + 8f, y + 10f, paint(9f))
            y += 16f
        }
        y += 8f
    }

    private fun waferMap() {
        val wafer = snapshot.wafer
        val scale = min(360f / wafer.cols, 260f / wafer.rows)
        val mapWidth = wafer.cols * scale
        val mapHeight = wafer.rows * scale
        reserve(mapHeight + 62f)
        paragraph("原始晶圆图（${wafer.rows} 行 × ${wafer.cols} 列）", 12f, blue, true)
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
            paragraph("晶圆编号：${snapshot.wafer.waferCode}    记录编号：${snapshot.wafer.id}")
            paragraph("来源文件：${snapshot.wafer.filename}", 9.5f, muted)
            y += 3f
            statistics()
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

package com.example.waferai.data.report

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.example.waferai.data.local.InspectionResult
import com.example.waferai.data.remote.WaferDetail
import com.example.waferai.data.repository.ImportRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

enum class AnalysisStage(val label: String, val fraction: Float) {
    READING("读取文件", .1f), STATISTICS("核对统计", .3f),
    DETECTION("本地检测", .6f), REPORT("生成报告", .85f)
}

internal data class LocalAnalysisResult(val wafer: WaferDetail, val inspection: InspectionResult, val report: File)

/** Shared sequential pipeline. No online provider is called by this workflow. */
internal class LocalAnalysisService(private val context: Context, private val repository: ImportRepository) {
    private val mutex = Mutex()
    private val reports = File(context.noBackupFilesDir, "analysis-reports")
    val queueFile get() = AtomicFile(File(context.noBackupFilesDir, "analysis-queue.json"))

    suspend fun analyzeFile(uri: Uri, threshold: Int, stage: (AnalysisStage) -> Unit): LocalAnalysisResult = mutex.withLock {
        stage(AnalysisStage.READING)
        val input = repository.prepareFileImport(uri)
        currentCoroutineContext().ensureActive()
        val wafer = repository.saveFileImport(input).wafer
        analyze(wafer, threshold, stage)
    }

    suspend fun analyzeCurrent(wafer: WaferDetail, threshold: Int, stage: (AnalysisStage) -> Unit) = mutex.withLock {
        analyze(wafer, threshold, stage)
    }

    private suspend fun analyze(wafer: WaferDetail, threshold: Int, stage: (AnalysisStage) -> Unit): LocalAnalysisResult {
        stage(AnalysisStage.STATISTICS)
        withContext(Dispatchers.Default) {
            var good = 0
            var bad = 0
            wafer.map.forEach { row ->
                currentCoroutineContext().ensureActive()
                row.forEach { bin -> if (bin == 1) good++ else if (bin == 2) bad++ }
            }
            check(good == wafer.goodCount && bad == wafer.badCount && good + bad > 0) { "芯片计数不一致，请重新导入文件。" }
        }
        stage(AnalysisStage.DETECTION)
        val inspection = repository.inspectWafer(wafer.id, threshold, trained = false)
        currentCoroutineContext().ensureActive()
        stage(AnalysisStage.REPORT)
        val snapshot = ReportSnapshot(wafer, inspection.prediction, inspection = inspection.record)
        val bytes = withContext(Dispatchers.Default) {
            val task = currentCoroutineContext()
            WaferPdfReport(snapshot) { task.ensureActive() }.generate()
        }
        val report = withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            check(reports.isDirectory || reports.mkdirs()) { "无法创建报告目录，请检查可用空间。" }
            val target = AtomicFile(File(reports, "wafer-${wafer.id}-${inspection.record.threshold}.pdf"))
            val output = target.startWrite()
            try {
                output.write(bytes)
                currentCoroutineContext().ensureActive()
                target.finishWrite(output)
            } catch (error: Throwable) {
                target.failWrite(output)
                throw error
            }
            target.baseFile
        }
        return LocalAnalysisResult(wafer, inspection, report)
    }

    fun retainPermission(uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    suspend fun filename(uri: Uri): String = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()?.takeIf(String::isNotBlank) ?: uri.lastPathSegment.orEmpty().substringAfterLast('/').ifBlank { "晶圆文件" }
    }

    suspend fun export(path: String, destination: Uri) = withContext(Dispatchers.IO) {
        val source = File(path).canonicalFile
        require(source.parentFile == reports.canonicalFile && source.isFile) { "报告已不存在，请重新分析。" }
        val target = context.contentResolver.openOutputStream(destination, "wt") ?: error("无法打开报告保存位置。")
        target.use { output -> source.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val size = input.read(buffer)
                if (size < 0) break
                output.write(buffer, 0, size)
            }
        } }
    }
}

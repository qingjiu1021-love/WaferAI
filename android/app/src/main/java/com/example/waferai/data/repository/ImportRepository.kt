package com.example.waferai.data.repository

import android.content.Context
import com.example.waferai.data.ai.readableReply
import android.net.Uri
import android.provider.OpenableColumns
import com.example.waferai.data.remote.*
import com.example.waferai.data.ai.AnalysisEvidence
import com.example.waferai.data.ai.DeepSeekClient
import com.example.waferai.data.ai.AiProfileStore
import com.example.waferai.data.local.LocalWaferStore
import com.example.waferai.data.local.LogOperation
import com.example.waferai.data.local.OperationLog
import com.example.waferai.data.local.WaferImage
import com.example.waferai.data.local.WaferImageOptions
import com.example.waferai.data.local.WaferParquet
import com.example.waferai.data.local.WaferCoordinateCsv
import com.example.waferai.data.local.WaferFileFormat
import com.example.waferai.data.local.WaferFileWriter
import com.example.waferai.data.local.ParsedWafer
import com.example.waferai.data.local.WaferTestData
import com.example.waferai.data.local.DemoWafer
import com.example.waferai.data.local.MobilePredictor
import com.example.waferai.data.local.WaferJson
import com.example.waferai.data.local.WaferOrder
import com.example.waferai.data.local.WaferCsvQuery
import com.example.waferai.data.local.WaferYieldBand
import com.example.waferai.data.local.WaferTrainer
import com.example.waferai.data.report.ReportSnapshot
import com.example.waferai.data.report.WaferPdfReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

class ImportRepository(private val context: Context) {
    private val resolver = context.contentResolver
    val store = LocalWaferStore(context)
    internal val operationLog = OperationLog(context)
    val predictor = MobilePredictor(context)
    val aiProfiles = AiProfileStore(context)
    private val aiClient = DeepSeekClient()
    private val trainer = WaferTrainer(context)
    val trainingSummary = trainer.summary

    internal suspend fun restoreTraining() = withContext(Dispatchers.IO) { trainer.restore() }

    internal suspend fun trainModel(uri: Uri?) = logged(LogOperation.TRAIN) {
        val custom = uri?.let { readFile(it, WaferJson.MAX_BYTES) }
        withContext(Dispatchers.Default) { trainer.train(custom) }
    }

    internal suspend fun inspectWafer(id: Int, threshold: Int, trained: Boolean) = logged(LogOperation.CLASSIFY) {
        val wafer = store.detail(id)
        val cached = if (trained) null else store.prediction(id, predictor.modelHash)
        val prediction = cached ?: withContext(Dispatchers.Default) {
            if (trained) trainer.predict(wafer) else predictor.predict(wafer)
        }
        store.saveInspection(prediction, threshold)
    }

    internal suspend fun latestInspection(waferId: Int) = withContext(Dispatchers.IO) { store.latestInspection(waferId) }
    internal suspend fun recentInspections() = withContext(Dispatchers.IO) { store.recentInspections() }

    suspend fun latestPrediction(waferId: Int) = withContext(Dispatchers.IO) { store.prediction(waferId) ?: error("这张晶圆尚未识别。") }

    suspend fun listWafers(
        limit: Int, offset: Int, lotId: Int? = null, query: String = "",
        order: WaferOrder = WaferOrder.RECENT, belowPercent: Int? = null,
        yieldBand: WaferYieldBand? = null
    ) = withContext(Dispatchers.IO) { store.listWafers(limit, offset, lotId, query, order, belowPercent, yieldBand) }

    suspend fun overview() = withContext(Dispatchers.IO) { store.overview() }

    suspend fun exportBackup(uri: Uri): Int = withContext(Dispatchers.IO) {
        val staging = File.createTempFile("wafer-backup-", ".json", context.cacheDir)
        try {
            val count = staging.bufferedWriter(Charsets.UTF_8).use { store.backup(it) { ensureActive() } }
            require(staging.length() <= BackupSizeLimit)
            val target = resolver.openOutputStream(uri, "wt") ?: throw IOException("无法打开备份位置。")
            target.use { output -> staging.inputStream().use { it.copyTo(output) } }
            count
        } finally {
            staging.delete()
        }
    }

    suspend fun restoreBackup(uri: Uri): Int = withContext(Dispatchers.IO) {
        val source = resolver.openInputStream(uri) ?: throw IOException("无法打开备份文件。")
        BoundedInput(source, BackupSizeLimit).bufferedReader(Charsets.UTF_8).use { store.restoreBackup(it) { ensureActive() } }
    }

    suspend fun exportCsv(uri: Uri, query: WaferCsvQuery): Int = withContext(Dispatchers.IO) {
        val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException("无法写入所选位置。")
        stream.bufferedWriter(Charsets.UTF_8).use { writer ->
            store.writeWafersCsv(writer, query) { ensureActive() }
        }
    }

    suspend fun waferDetail(waferId: Int) = withContext(Dispatchers.IO) { store.detail(waferId) }

    suspend fun listLots(limit: Int, offset: Int) = withContext(Dispatchers.IO) { store.listLots(limit, offset) }

    suspend fun lotDetail(lotId: Int) = withContext(Dispatchers.IO) { store.lotDetail(lotId) }

    suspend fun latestAnalysis(waferId: Int,predictionId: Int) = withContext(Dispatchers.IO) { store.latestAnalysis(waferId,predictionId) }
    suspend fun analyze(wafer: WaferDetail, prediction: WaferPrediction,
                        preset: com.example.waferai.data.ai.AnalysisPreset = com.example.waferai.data.ai.AnalysisPreset.ENGINEERING,
                        threshold: Int = 90): com.example.waferai.data.local.AiAnalysis {
        val request = withContext(Dispatchers.IO) { aiProfiles.request() }
        val profile = request.profile
        val sources = withContext(Dispatchers.IO) {
            com.example.waferai.data.ai.WaferKnowledge(context, operationLog)
                .search("${prediction.label} ${prediction.labelZh} 缺陷")
                .filterNot { it.title.startsWith("真实样本 ·") }.take(3).mapIndexed { index, entry ->
                    com.example.waferai.data.ai.AnalysisSource("K${index + 1}", entry.title, entry.body, entry.source)
                }
        }
        val facts = withContext(Dispatchers.Default) { AnalysisEvidence.facts(wafer, prediction, threshold) }
        val evidence = withContext(Dispatchers.Default) { AnalysisEvidence.packet(wafer, prediction, threshold, facts, sources) }
        val raw = aiClient.analyze(request.key, profile.model, evidence, profile.endpoint, profile.provider, profile.protocol, preset)
        val verified = withContext(Dispatchers.Default) { com.example.waferai.data.ai.VerifiedAnalysis.render(raw, facts, sources, preset) }
        return withContext(Dispatchers.IO) {
            store.saveAnalysis(wafer.id, prediction.id, "${profile.provider.title} · ${profile.model} · ${preset.title}", verified)
        }
    }
    suspend fun loadDemo(sample: DemoWafer) = logged(LogOperation.IMPORT) {
        val bytes = context.assets.open("samples/${sample.filename}").use { it.readBytes() }
        store.importJson(bytes, sample.filename)
    }

    suspend fun saveGuideFile(uri: Uri, file: com.example.waferai.data.local.GuideFile) = withContext(Dispatchers.IO) {
        val bytes = com.example.waferai.data.local.PracticeFiles.bytes(context, file)
        resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
            ?: throw IOException("无法保存练习文件，请重新选择位置。")
    }

    suspend fun exportPdf(uri: Uri, snapshot: ReportSnapshot) = logged(LogOperation.REPORT) {
        val bytes = WaferPdfReport(snapshot).generate()
        resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
            ?: throw IOException("无法写入所选位置。")
    }

    suspend fun importFile(uri: Uri): ImportResponse = logged(LogOperation.IMPORT) {
        store.saveParsed(readWaferFile(uri))
    }

    internal suspend fun readForConversion(uri: Uri): WaferDetail = withContext(Dispatchers.IO) {
        readWaferFile(uri).detail
    }

    private suspend fun readWaferFile(uri: Uri): ParsedWafer {
        val name = displayName(uri) ?: "wafer.json"
        val format = WaferFileFormat.fromFilename(name)
            ?: throw IllegalArgumentException("请选择 STDF、ATDF、CSV、JSON 或 Parquet 晶圆数据。")
        if (format == WaferFileFormat.CSV) {
            val source = resolver.openInputStream(uri) ?: throw IOException("无法打开文件，请重新选择。")
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            val job = kotlinx.coroutines.currentCoroutineContext()
            return InputStreamReader(BoundedInput(source, WaferCoordinateCsv.MAX_BYTES.toLong()), decoder).use { reader ->
                WaferCoordinateCsv.parse(reader, name) { job.ensureActive() }
            }
        }
        val limit = when (format) {
            WaferFileFormat.JSON -> WaferJson.MAX_BYTES
            WaferFileFormat.ATDF -> WaferTestData.MAX_ATDF_BYTES
            else -> WaferTestData.MAX_BYTES
        }
        val bytes = readFile(uri, limit)
        return when (format) {
            WaferFileFormat.STDF -> WaferTestData.stdf(bytes, name)
            WaferFileFormat.ATDF -> WaferTestData.atdf(bytes, name)
            WaferFileFormat.PARQUET -> WaferParquet.parse(context, bytes, name)
            else -> WaferJson.parse(bytes, name)
        }
    }

    internal suspend fun convertFile(uri: Uri, wafer: WaferDetail, format: WaferFileFormat) = withContext(Dispatchers.IO) {
        val temporary = File.createTempFile("wafer-conversion-", ".${format.extension}", context.cacheDir)
        val job = kotlinx.coroutines.currentCoroutineContext()
        try {
            when (format) {
                WaferFileFormat.JSON -> temporary.writeText(WaferFileWriter.json(wafer))
                WaferFileFormat.CSV -> temporary.bufferedWriter().use { WaferFileWriter.csv(wafer, it) { job.ensureActive() } }
                WaferFileFormat.ATDF -> temporary.bufferedWriter().use { WaferFileWriter.atdf(wafer, it) { job.ensureActive() } }
                WaferFileFormat.STDF -> temporary.outputStream().buffered().use { WaferFileWriter.stdf(wafer, it) { job.ensureActive() } }
                WaferFileFormat.PARQUET -> temporary.writeBytes(WaferParquet.write(context, wafer))
            }
            ensureActive()
            val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("无法打开保存位置。")
            output.use { destination -> temporary.inputStream().use { it.copyTo(destination) } }
        } finally {
            temporary.delete()
        }
    }

    suspend fun importImage(uri: Uri, options: WaferImageOptions): ImportResponse = logged(LogOperation.IMAGE) {
        val name = displayName(uri) ?: "wafer.png"
        require(WaferImage.supports(name) || resolver.getType(uri)?.startsWith("image/") == true) {
            "请选择设备支持的晶圆图片。"
        }
        val bytes = readFile(uri, WaferImage.MAX_BYTES)
        store.saveParsed(WaferImage.parse(bytes, name, options))
    }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }

    private fun readFile(uri: Uri, limit: Int): ByteArray = resolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "文件不能超过 ${limit / (1024 * 1024)} MB。" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: throw IOException("无法打开文件，请重新选择。")

    private suspend fun <T> logged(operation: LogOperation, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block().also { operationLog.record(operation, true) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            operationLog.record(operation, false)
            throw error
        }
    }
}

private const val BackupSizeLimit = 128L * 1024 * 1024

private class BoundedInput(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int = super.read().also { if (it >= 0) add(1) }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
        super.read(bytes, offset, length).also { if (it > 0) add(it) }

    private fun add(bytes: Int) {
        count += bytes
        require(count <= limit) { "文件不能超过 ${limit / (1024 * 1024)} MB。" }
    }
}

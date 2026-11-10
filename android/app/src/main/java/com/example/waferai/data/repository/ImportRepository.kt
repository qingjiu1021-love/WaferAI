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
import com.example.waferai.data.local.WaferSource
import com.example.waferai.data.local.WaferTrainer
import com.example.waferai.data.report.ReportSnapshot
import com.example.waferai.data.report.WaferPdfReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.CheckedInputStream
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile

internal sealed interface ConversionSource {
    data class Wafer(val detail: WaferDetail) : ConversionSource
    data class Archive(val names: List<String>) : ConversionSource
}

class ImportRepository(private val context: Context) {
    private val resolver = context.contentResolver
    val store = LocalWaferStore(context)
    internal val testHistory by lazy { com.example.waferai.data.local.WaferTestHistory(context) }
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
    internal suspend fun lotFailureStack(lotId: Int, waferIds: IntArray) = withContext(Dispatchers.IO) {
        store.lotFailureStack(lotId, waferIds) { ensureActive() }
    }

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
        store.saveParsed(readDemo(sample))
    }

    internal suspend fun readDemo(sample: DemoWafer): ParsedWafer = withContext(Dispatchers.IO) {
        val bytes = context.assets.open("samples/${sample.filename}").use { it.readBytes() }
        val parsed = WaferJson.parse(bytes, sample.filename)
        val source = if (sample.isReal) WaferSource.WM811K else WaferSource.DEMO
        parsed.copy(detail = parsed.detail.copy(source = source))
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

    internal suspend fun prepareFileImport(uri: Uri): ParsedWafer = withContext(Dispatchers.IO) { readWaferFile(uri) }

    internal suspend fun saveFileImport(parsed: ParsedWafer): ImportResponse = logged(LogOperation.IMPORT) {
        store.saveParsed(parsed)
    }

    internal suspend fun readForConversion(uri: Uri, archiveEntry: String? = null): ConversionSource = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: "wafer.json"
        when (name.substringAfterLast('.').lowercase(Locale.ROOT)) {
            "zip" -> readZipConversion(uri, archiveEntry)
            "gz", "gzip" -> {
                val waferName = name.substringBeforeLast('.')
                require(WaferFileFormat.fromFilename(waferName) != null) { "GZIP 文件名应保留晶圆扩展名，例如 wafer.stdf.gz。" }
                val parsed = try {
                    openSource(uri).use { source -> GZIPInputStream(source).use { parseWaferFile(waferName, it) } }
                } catch (_: ZipException) {
                    throw IOException("无法解压 GZIP 文件，请检查文件是否完整。")
                }
                ConversionSource.Wafer(parsed.detail)
            }
            else -> {
                require(WaferFileFormat.fromFilename(name) != null) { "请选择晶圆数据、ZIP 或 GZIP 文件。" }
                ConversionSource.Wafer(openSource(uri).use { parseWaferFile(name, it).detail })
            }
        }
    }

    private suspend fun readZipConversion(uri: Uri, selected: String?): ConversionSource {
        val temporary = File.createTempFile("wafer-zip-", ".zip", context.cacheDir)
        val job = currentCoroutineContext()
        try {
            BoundedInput(openSource(uri), 64L * 1024 * 1024).use { source ->
                temporary.outputStream().buffered().use { target ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        job.ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        target.write(buffer, 0, count)
                    }
                }
            }
            ZipFile(temporary).use { zip ->
                require(zip.size() <= 512) { "ZIP 最多支持 512 个条目。" }
                val entries = zip.entries().asSequence().filter { entry ->
                    !entry.isDirectory && entry.name.split('/').none { it.startsWith('.') || it == "__MACOSX" } &&
                        WaferFileFormat.fromFilename(entry.name.substringAfterLast('/')) != null
                }.toList()
                require(entries.isNotEmpty()) { "ZIP 内没有可转换的晶圆数据文件。" }
                require(entries.map { it.name }.toSet().size == entries.size) { "ZIP 含同路径的重名晶圆文件。" }
                val entry = if (selected == null) entries.singleOrNull() else
                    entries.firstOrNull { it.name == selected } ?: error("压缩包内容已变化，请重新选择源文件。")
                if (entry == null) return ConversionSource.Archive(entries.map { it.name })
                val name = entry.name.substringAfterLast('/')
                val crc = CRC32()
                val parsed = CheckedInputStream(zip.getInputStream(entry), crc).use { parseWaferFile(name, it) }
                require(crc.value == entry.crc) { "ZIP 中的晶圆文件校验失败。" }
                return ConversionSource.Wafer(parsed.detail)
            }
        } catch (_: ZipException) {
            throw IOException("无法解压 ZIP 文件，请检查文件是否完整或已加密。")
        } finally {
            temporary.delete()
        }
    }

    private suspend fun readWaferFile(uri: Uri): ParsedWafer =
        openSource(uri).use { parseWaferFile(displayName(uri) ?: "wafer.json", it) }

    private suspend fun parseWaferFile(name: String, source: InputStream): ParsedWafer {
        val format = WaferFileFormat.fromFilename(name)
            ?: throw IllegalArgumentException("请选择 STDF、ATDF、CSV、JSON 或 Parquet 晶圆数据。")
        val job = currentCoroutineContext()
        if (format == WaferFileFormat.CSV) {
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            val reader = InputStreamReader(BoundedInput(source, WaferCoordinateCsv.MAX_BYTES.toLong()), decoder)
            return WaferCoordinateCsv.parse(reader, name) { job.ensureActive() }
        }
        val limit = when (format) {
            WaferFileFormat.JSON -> WaferJson.MAX_BYTES
            WaferFileFormat.ATDF -> WaferTestData.MAX_ATDF_BYTES
            else -> WaferTestData.MAX_BYTES
        }
        val bytes = readBytes(source, limit) { job.ensureActive() }
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
        if (uri.scheme == "file") uri.lastPathSegment
        else resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }

    private fun openSource(uri: Uri) = resolver.openInputStream(uri) ?: throw IOException("无法打开文件，请重新选择。")

    private fun readFile(uri: Uri, limit: Int): ByteArray = openSource(uri).use { readBytes(it, limit) }

    private fun readBytes(input: InputStream, limit: Int, checkCancelled: () -> Unit = {}): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "文件不能超过 ${limit / (1024 * 1024)} MB。" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

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

package com.example.waferai.data.local

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import com.example.waferai.data.remote.ClassScore
import com.example.waferai.data.remote.WaferDetail
import com.example.waferai.data.remote.WaferPrediction
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.time.Instant
import java.util.zip.GZIPInputStream
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

data class TrainingSummary(
    val trainedAt: String, val samples: Int, val customSamples: Int, val validation: Int,
    val accuracy: Double, val macroF1: Double
)

internal class WaferTrainer(private val context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "wafer-trained-model-v1.json"))
    private val lock = Mutex()
    private val gson = Gson()
    @Volatile private var current: Model? = null
    @Volatile private var loaded = false
    private val mutable = MutableStateFlow<TrainingSummary?>(null)
    val summary = mutable.asStateFlow()

    private data class Sample(val label: Int, val bins: ByteArray)
    private data class Parameters(val featureId: String, val mean: DoubleArray, val scale: DoubleArray,
                                  val centroids: List<DoubleArray>)
    private data class SavedSample(val label: Int, val bins: String)
    private data class Model(val params: Parameters, val summary: TrainingSummary, val saved: List<SavedSample>? = null)

    /** Called on a worker, never deserialize saved training samples in the app constructor. */
    @Synchronized fun restore(): TrainingSummary? {
        if (!loaded) {
            current = load()
            loaded = true
            mutable.value = current?.summary
        }
        return current?.summary
    }

    suspend fun train(custom: ByteArray?): TrainingSummary = lock.withLock {
        restore()
        val job = currentCoroutineContext()
        val metadata = context.assets.open("models/phone-training-wm811k.json").bufferedReader().use {
            JsonParser.parseReader(it).asJsonObject
        }
        val content = context.assets.open("models/phone-training-wm811k.bin").use { raw ->
            GZIPInputStream(raw).use { it.readBytes() }
        }
        check(WaferJson.sha256(content) == metadata["subset_sha256"].asString)
        val classes = MobilePreprocessing.classes
        val (seed, validation) = DataInputStream(ByteArrayInputStream(content)).use { stream ->
            check(stream.readInt() == 0x57415431)
            val trainingSize = stream.readUnsignedShort()
            val validationSize = stream.readUnsignedShort()
            fun read(count: Int) = List(count) {
                val label = stream.readUnsignedByte()
                check(label in classes.indices)
                Sample(label, ByteArray(4096).also(stream::readFully))
            }
            (read(trainingSize) to read(validationSize)).also { check(stream.read() == -1) }
        }
        val seen = seed.associate { WaferJson.sha256(it.bins) to it.label }.toMutableMap()
        val validationHashes = validation.mapTo(HashSet()) { WaferJson.sha256(it.bins) }
        val validationLots = metadata.getAsJsonArray("validation_lots").mapTo(HashSet()) { it.asString.lowercase() }
        val previous = current?.saved.orEmpty().map { item ->
            val bins = Base64.decode(item.bins, Base64.NO_WRAP)
            check(bins.size == 4096 && item.label in classes.indices)
            Sample(item.label, bins)
        }
        val retained = previous.filter { sample ->
            val hash = WaferJson.sha256(sample.bins)
            hash !in validationHashes && seen.putIfAbsent(hash, sample.label) == null
        }
        val imported = if (custom == null) emptyList() else readCustom(custom, seen, validationHashes, validationLots)
        val extra = retained + imported
        require(extra.size <= 1000) { "手机已标注样本库最多保存 1000 张。" }
        val training = seed + extra
        val features = training.map { job.ensureActive(); features(it.bins) }
        val dimension = features.first().size
        val mean = DoubleArray(dimension) { index -> features.sumOf { it[index] } / features.size }
        val scale = DoubleArray(dimension) { index ->
            sqrt(features.sumOf { val delta = it[index] - mean[index]; delta * delta } / features.size).coerceAtLeast(0.02)
        }
        val sums = List(classes.size) { DoubleArray(dimension) }
        val counts = IntArray(classes.size)
        training.forEachIndexed { index, sample ->
            counts[sample.label]++
            val vector = normalized(features[index], mean, scale)
            vector.forEachIndexed { j, value -> sums[sample.label][j] += value }
        }
        check(counts.all { it > 0 })
        val params = Parameters(FeatureId, mean, scale, sums.mapIndexed { label, sum ->
            DoubleArray(dimension) { sum[it] / counts[label] }
        })
        val matrix = Array(classes.size) { IntArray(classes.size) }
        validation.forEach { sample ->
            job.ensureActive()
            val scores = scores(params, sample.bins)
            matrix[sample.label][scores.indices.maxBy { scores[it] }]++
        }
        val macroF1 = matrix.indices.map { label ->
            val correct = matrix[label][label]
            val denominator = matrix[label].sum() + matrix.sumOf { it[label] }
            if (denominator == 0) 0.0 else 2.0 * correct / denominator
        }.average()
        val model = Model(params, TrainingSummary(Instant.now().toString(), training.size, extra.size,
            validation.size, matrix.indices.sumOf { matrix[it][it] }.toDouble() / validation.size, macroF1),
            extra.map { SavedSample(it.label, Base64.encodeToString(it.bins, Base64.NO_WRAP)) })
        job.ensureActive()
        val out = file.startWrite()
        try {
            out.write(gson.toJson(model).toByteArray())
            file.finishWrite(out)
        } catch (error: Exception) {
            file.failWrite(out)
            throw error
        }
        current = model
        mutable.value = model.summary
        model.summary
    }

    fun predict(wafer: WaferDetail): WaferPrediction {
        restore()
        val params = requireNotNull(current?.params) { "请先在工具中训练手机模型。" }
        val scores = scores(params, MobilePreprocessing.bins(wafer.map))
        val index = scores.indices.maxBy { scores[it] }
        val labels = MobilePreprocessing.classes
        val names = MobilePreprocessing.names
        return WaferPrediction(
            0, wafer.id, "centroid-v1-mobile", WaferJson.sha256(gson.toJson(params).toByteArray()),
            FeatureId, Instant.now().toString(), names[index], labels[index], scores[index], "centroid_uncalibrated",
            scores.mapIndexed { i, score -> ClassScore(labels[i], names[i], score) }
        )
    }

    private suspend fun readCustom(
        bytes: ByteArray, seen: MutableMap<String, Int>, validationHashes: Set<String>, validationLots: Set<String>
    ): List<Sample> {
        val entries = runCatching { JsonParser.parseString(decodeWaferText(bytes)).asJsonObject.getAsJsonArray("samples") }
            .getOrNull()
        require(entries != null && entries.size() in 1..200) { "训练文件须包含 1～200 条 samples。" }
        return buildList {
            entries.forEachIndexed { index, item ->
                currentCoroutineContext().ensureActive()
                require(item.isJsonObject) { "训练样本须为对象。" }
                val entry = item.asJsonObject
                val label = entry.remove("label")
                val id = if (label?.isJsonPrimitive == true && label.asJsonPrimitive.isString)
                    MobilePreprocessing.classes.indexOf(label.asString) else -1
                require(id >= 0) { "训练样本 label 须为支持的九个类别之一。" }
                val wafer = WaferJson.parse(entry.toString().toByteArray(), "training-$index.json").detail
                val bins = MobilePreprocessing.bins(wafer.map)
                val hash = WaferJson.sha256(bins)
                val lot = wafer.lotCode.lowercase().removePrefix("wm811k-")
                require(hash !in validationHashes && lot !in validationLots) { "训练样本与内置验证集的批次或晶圆图重复。" }
                val prior = seen.putIfAbsent(hash, id)
                require(prior == null || prior == id) { "同一晶圆图存在冲突的训练标签。" }
                if (prior == null) add(Sample(id, bins))
            }
        }
    }

    private fun load(): Model? = runCatching {
        gson.fromJson(file.readFully().toString(Charsets.UTF_8), Model::class.java)
            .takeIf { it.params.featureId == FeatureId && it.params.centroids.size == MobilePreprocessing.classes.size }
    }.getOrNull()

    private fun normalized(values: DoubleArray, mean: DoubleArray, scale: DoubleArray) =
        DoubleArray(values.size) { ((values[it] - mean[it]) / scale[it]).coerceIn(-8.0, 8.0) }

    private fun scores(params: Parameters, bins: ByteArray): List<Double> {
        val vector = normalized(features(bins), params.mean, params.scale)
        val logWeights = params.centroids.map { center ->
            -3 * vector.indices.sumOf { val d = vector[it] - center[it]; d * d } / vector.size
        }
        val peak = logWeights.max()
        val weights = logWeights.map { exp(it - peak) }
        val total = weights.sum()
        return weights.map { it / total }
    }

    private fun features(bins: ByteArray): DoubleArray {
        val tileValid = IntArray(16)
        val tileBad = IntArray(16)
        val rowBad = IntArray(64)
        val colBad = IntArray(64)
        val ringBad = IntArray(3)
        var valid = 0
        var bad = 0
        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumYY = 0.0
        var sumXY = 0.0
        bins.forEachIndexed { index, bin ->
            if (bin.toInt() == 0) return@forEachIndexed
            val x = index % 64
            val y = index / 64
            val tile = y / 16 * 4 + x / 16
            tileValid[tile]++
            valid++
            if (bin.toInt() == 2) {
                bad++
                tileBad[tile]++
                rowBad[y]++
                colBad[x]++
                val nx = (x - 31.5) / 32
                val ny = (y - 31.5) / 32
                sumX += nx
                sumY += ny
                sumXX += nx * nx
                sumYY += ny * ny
                sumXY += nx * ny
                val radius = nx * nx + ny * ny
                ringBad[when {
                    radius < 0.35 * 0.35 -> 0
                    radius < 0.70 * 0.70 -> 1
                    else -> 2
                }]++
            }
        }
        val denominator = max(bad, 1).toDouble()
        val xMean = sumX / denominator
        val yMean = sumY / denominator
        return buildList {
            add(bad.toDouble() / max(valid, 1))
            for (i in tileValid.indices) add(tileBad[i].toDouble() / max(tileValid[i], 1))
            for (value in tileBad) add(value / denominator)
            for (value in ringBad) add(value / denominator)
            add(xMean)
            add(yMean)
            add(sumXX / denominator - xMean * xMean)
            add(sumYY / denominator - yMean * yMean)
            add(sumXY / denominator - xMean * yMean)
            add(rowBad.max() / denominator)
            add(colBad.max() / denominator)
        }.toDoubleArray()
    }

    private companion object { const val FeatureId = "grid-region-moments-v1" }
}

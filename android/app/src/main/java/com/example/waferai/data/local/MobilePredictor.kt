package com.example.waferai.data.local

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.waferai.data.remote.ClassScore
import com.example.waferai.data.remote.WaferDetail
import com.example.waferai.data.remote.WaferPrediction
import org.json.JSONObject
import java.nio.FloatBuffer
import java.time.Instant
import kotlin.math.exp
import kotlin.math.round

object MobilePreprocessing {
    val classes = listOf("none", "Center", "Donut", "Edge-Loc", "Edge-Ring", "Loc", "Random", "Scratch", "Near-full")
    val names = listOf("无特定缺陷模式", "中心型", "环圈型", "边缘局部型", "边缘环型", "局部型", "随机型", "划痕型", "近全片型")
    const val ID = "nearest_pixel_center_aspect_pad64_v1"
    fun bins(map: List<List<Int>>): ByteArray {
        val height = map.size; val width = map[0].size
        val scale = 64.0 / maxOf(height, width)
        val newHeight = round(height * scale).toInt().coerceIn(1, 64)
        val newWidth = round(width * scale).toInt().coerceIn(1, 64)
        val top = (64 - newHeight) / 2; val left = (64 - newWidth) / 2
        val result = ByteArray(4096)
        for (y in 0 until newHeight) for (x in 0 until newWidth) {
            val sourceY = (((y + 0.5) * height) / newHeight).toInt().coerceAtMost(height - 1)
            val sourceX = (((x + 0.5) * width) / newWidth).toInt().coerceAtMost(width - 1)
            result[(top + y) * 64 + left + x] = map[sourceY][sourceX].toByte()
        }
        require(result.any { it.toInt() != 0 }) { "晶圆在模型缩放后没有有效芯片，无法识别。" }
        return result
    }
    fun oneHot(bins: ByteArray): FloatArray = FloatArray(3 * 4096).also { input ->
        bins.forEachIndexed { index, bin -> input[bin.toInt() * 4096 + index] = 1f }
    }
}

class MobilePredictor(private val context: Context) {
    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private val info by lazy { JSONObject(context.assets.open("models/model-info.json").bufferedReader().use { it.readText() }) }
    val modelHash: String get() = info.getString("model_sha256")
    private val session: OrtSession by lazy {
        val bytes = context.assets.open("models/wafer-cnn-v1.onnx").use { it.readBytes() }
        check(WaferJson.sha256(bytes) == modelHash) { "本地模型校验失败，请重新安装完整安装包。" }
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
            environment.createSession(bytes, options)
        }
    }
    @Synchronized fun scores(map: List<List<Int>>): List<Double> {
        val input = MobilePreprocessing.oneHot(MobilePreprocessing.bins(map))
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1,3,64,64)).use { tensor ->
            session.run(mapOf("bins_one_hot" to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val logits = (result[0].value as Array<FloatArray>)[0]
                check(logits.size == 9 && logits.all { it.isFinite() }) { "本地模型输出异常。" }
                val maximum = logits.max().toDouble()
                val values = logits.map { exp(it.toDouble() - maximum) }; val sum = values.sum()
                return values.map { it / sum }
            }
        }
    }
    fun predict(wafer: WaferDetail): WaferPrediction {
        val scores = scores(wafer.map)
        val index = scores.indices.maxBy { scores[it] }
        return WaferPrediction(0, wafer.id, "cnn-v1-mobile", modelHash, MobilePreprocessing.ID,
            Instant.now().toString(), MobilePreprocessing.names[index], MobilePreprocessing.classes[index], scores[index],
            "softmax_uncalibrated", scores.mapIndexed { i, score -> ClassScore(MobilePreprocessing.classes[i], MobilePreprocessing.names[i], score) })
    }
}

package com.example.waferai.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.example.waferai.data.remote.WaferDetail
import java.io.ByteArrayOutputStream

enum class GuideFile(val filename: String, val title: String) {
    JSON("waferai-center-53x58.json", "保存 JSON 练习文件"),
    CSV("waferai-center-53x58.csv", "保存 CSV 练习文件"),
    IMAGE("waferai-center-53x58.png", "保存晶圆练习图片")
}

internal object PracticeFiles {
    fun bytes(context: Context, file: GuideFile): ByteArray {
        val bytes = sampleJson(context)
        if (file == GuideFile.JSON) return bytes
        val wafer = WaferJson.parse(bytes, file.filename).detail
        if (file == GuideFile.CSV) return buildString {
            append("lot_code,wafer_code,row,col,status\n")
            wafer.map.forEachIndexed { r, row -> row.forEachIndexed { c, bin ->
                append("${wafer.lotCode},${wafer.waferCode},${r + 1},${c + 1},$bin\n")
            } }
        }.toByteArray(Charsets.UTF_8)
        val bitmap = renderImage(wafer, scale = 8)
        return try { ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        } } finally { bitmap.recycle() }
    }

    fun previewImage(context: Context): Bitmap = renderImage(
        WaferJson.parse(sampleJson(context), GuideFile.IMAGE.filename).detail,
        scale = 2
    )

    private fun sampleJson(context: Context): ByteArray =
        context.assets.open("samples/wm811k-real-center-01.json").use { it.readBytes() }

    private fun renderImage(wafer: WaferDetail, scale: Int): Bitmap {
        val width = wafer.cols * scale
        val height = wafer.rows * scale
        val pixels = IntArray(width * height) { index ->
            when (wafer.map[(index / width) / scale][(index % width) / scale]) {
                1 -> Color.rgb(46, 160, 67)
                2 -> Color.rgb(230, 45, 55)
                else -> Color.rgb(235, 235, 235)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}

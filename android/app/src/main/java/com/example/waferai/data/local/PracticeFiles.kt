package com.example.waferai.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream

enum class GuideFile(val filename: String, val title: String) {
    JSON("waferai-center-53x58.json", "保存 JSON 练习文件"),
    CSV("waferai-center-53x58.csv", "保存 CSV 练习文件"),
    IMAGE("waferai-center-53x58.png", "保存晶圆练习图片")
}

internal object PracticeFiles {
    fun bytes(context: Context, file: GuideFile): ByteArray {
        val bytes = context.assets.open("samples/wm811k-real-center-01.json").use { it.readBytes() }
        if (file == GuideFile.JSON) return bytes
        val wafer = WaferJson.parse(bytes, file.filename).detail
        if (file == GuideFile.CSV) return buildString {
            append("lot_code,wafer_code,row,col,status\n")
            wafer.map.forEachIndexed { r, row -> row.forEachIndexed { c, bin ->
                append("${wafer.lotCode},${wafer.waferCode},${r + 1},${c + 1},$bin\n")
            } }
        }.toByteArray(Charsets.UTF_8)
        val scale = 8
        val width = wafer.cols * scale
        val height = wafer.rows * scale
        val pixels = IntArray(width * height) { index ->
            when (wafer.map[(index / width) / scale][(index % width) / scale]) {
                1 -> Color.rgb(46, 160, 67)
                2 -> Color.rgb(230, 45, 55)
                else -> Color.rgb(235, 235, 235)
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        } } finally { bitmap.recycle() }
    }
}

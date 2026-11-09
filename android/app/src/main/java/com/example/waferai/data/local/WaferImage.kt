package com.example.waferai.data.local

import android.graphics.BitmapFactory
import kotlin.math.max
import kotlin.math.min

enum class WaferPalette(val title: String) {
    RED_GREEN("绿合格 · 红不合格"),
    VIRIDIS("青绿合格 · 黄不合格 · 紫背景"),
    GRAYSCALE("灰合格 · 白不合格 · 黑背景")
}

data class WaferImageOptions(val rows: Int?, val cols: Int?, val palette: WaferPalette)

internal object WaferImage {
    const val MAX_BYTES = 10 * 1024 * 1024
    private val extensions = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "heif", "heic", "avif")
    fun supports(filename: String) = filename.substringAfterLast('.').lowercase() in extensions

    fun parse(bytes: ByteArray, filename: String, options: WaferImageOptions): ParsedWafer {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "设备无法解码该图片。" }
        val rows = options.rows ?: bounds.outHeight
        val cols = options.cols ?: bounds.outWidth
        require(rows in 1..512 && cols in 1..512) { "请填写 1～512 范围内的晶圆图行数和列数。" }
        require(rows <= bounds.outHeight && cols <= bounds.outWidth) { "网格行列不能超过图片像素尺寸。" }
        require(bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000) { "图片最多支持 1600 万像素。" }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("设备无法解码该图片。")
        val map = try {
            List(rows) { row ->
                List(cols) { col ->
                    val x = ((col + 0.5) * bitmap.width / cols).toInt()
                    val y = ((row + 0.5) * bitmap.height / rows).toInt()
                    val bin = classify(bitmap.getPixel(x, y), options.palette)
                    require(bin != null) { "网格内出现不匹配的颜色。请裁掉图例和留白，再确认行列和配色。" }
                    bin
                }
            }
        } finally {
            bitmap.recycle()
        }
        val wafer = parsedWafer("IMAGE", "IMG-${WaferJson.sha256(bytes).take(16)}", map, filename, WaferSource.IMAGE)
        // Include the decoded map in the identifier so a corrected palette or grid is importable.
        return wafer.copy(detail = wafer.detail.copy(waferCode = "${wafer.detail.waferCode}-${wafer.inputHash.take(8)}"))
    }

    private fun classify(argb: Int, palette: WaferPalette): Int? {
        if (argb ushr 24 < 128) return 0
        val r = argb ushr 16 and 255
        val g = argb ushr 8 and 255
        val b = argb and 255
        val spread = max(r, max(g, b)) - min(r, min(g, b))
        return when (palette) {
            WaferPalette.RED_GREEN -> when {
                spread <= 35 -> 0
                r > g + 32 && r > b + 25 -> 2
                g > r + 22 && g > b + 17 -> 1
                else -> null
            }
            WaferPalette.VIRIDIS -> when {
                r >= 140 && g >= 145 && b < g - 30 -> 2
                g > r + 35 && g > 75 -> 1
                (b > g + 20 && g < 85) || spread <= 30 -> 0
                else -> null
            }
            WaferPalette.GRAYSCALE -> when {
                spread > 28 -> null
                g < 48 -> 0
                g >= 210 -> 2
                g in 65..185 -> 1
                else -> null
            }
        }
    }
}

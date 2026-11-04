package com.example.waferai.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.example.waferai.data.local.WaferImage
import com.example.waferai.data.local.WaferImageOptions
import com.example.waferai.data.local.WaferPalette
import com.example.waferai.data.local.decodeWaferText
import java.io.ByteArrayOutputStream

internal data class ChatAttachment(val name: String, val text: String? = null, val jpeg: String? = null) {
    companion object {
        fun read(context: Context, uri: Uri, photo: Boolean): ChatAttachment {
            val resolver = context.contentResolver
            require(uri.scheme == "content") { "请从系统文件选择器添加附件。" }
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
                ?.filterNot(Char::isISOControl)?.take(100) ?: if (photo) "照片" else "文件"
            val limit = if (photo) WaferImage.MAX_BYTES else 64 * 1024
            val bytes = requireNotNull(resolver.openInputStream(uri)) { "无法打开附件。" }.use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    require(out.size() + read <= limit) {
                        if (photo) "照片不能超过 10 MB。" else "文本文件不能超过 64 KB。"
                    }
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
            if (!photo) {
                val content = decodeWaferText(bytes).trim()
                require(content.isNotEmpty() && !content.any { it.isISOControl() && it !in "\r\n\t" }) {
                    "请选择 UTF-8 文本、CSV、JSON 或 Markdown 文件。"
                }
                return ChatAttachment(name, content.take(8_000))
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
                bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000) { "无法读取该照片或照片尺寸过大。" }
            val options = BitmapFactory.Options().apply {
                while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize.coerceAtLeast(1) > 1600) {
                    inSampleSize = inSampleSize.coerceAtLeast(1) * 2
                }
            }
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) { "无法解码该照片。" }
            val encoded = try {
                val out = ByteArrayOutputStream()
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 76, out)) { "照片压缩失败。" }
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            } finally { bitmap.recycle() }
            val offlineSummary = if (bounds.outWidth in 1..512 && bounds.outHeight in 1..512) runCatching {
                val map = WaferImage.parse(bytes, name, WaferImageOptions(null, null, WaferPalette.RED_GREEN)).detail
                "按绿合格、红不合格、灰背景且每像素一格读取的图像摘要：" + ChatPrompt.summarize(map, null)
            }.getOrNull() else null
            return ChatAttachment(name, offlineSummary, encoded)
        }
    }
}

package com.example.waferai.data.account

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.net.Uri
import android.util.AtomicFile
import android.util.Base64
import com.google.gson.Gson
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

internal data class LocalUserProfile(val name: String = "", val avatar: String? = null)

internal class LocalUserProfileStore(private val context: Context) {
    fun key(origin: String, userId: String?) = MessageDigest.getInstance("SHA-256")
        .digest((if (userId == null) "guest" else "$origin|$userId").toByteArray()).joinToString("") { "%02x".format(it) }
    private fun file(key: String): AtomicFile {
        require(key.matches(Regex("[a-f0-9]{64}")))
        return AtomicFile(File(context.noBackupFilesDir, "user-profile-$key.json"))
    }
    fun read(key: String): LocalUserProfile = try {
        Gson().fromJson(file(key).readFully().toString(Charsets.UTF_8), LocalUserProfile::class.java)
    } catch (_: FileNotFoundException) { LocalUserProfile() }
    fun write(key: String, profile: LocalUserProfile) {
        require(profile.name.length <= 40 && profile.name.none(Char::isISOControl)) { "昵称最多 40 个字符，不能包含控制字符。" }
        require((profile.avatar?.length ?: 0) <= 180_000)
        val target = file(key)
        val output = target.startWrite()
        try { output.write(Gson().toJson(profile).toByteArray()); target.finishWrite(output) }
        catch (error: Exception) { target.failWrite(output); throw error }
    }
    fun avatar(uri: Uri): String {
        val bytes = ByteArrayOutputStream().use { output ->
            requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取照片。" }.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 12 * 1024 * 1024) { "请选择小于 12 MB 的图片。" }
                    output.write(buffer, 0, count)
                }
            }
            output.toByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法识别这张图片，请使用 JPEG、PNG 或 WebP。" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
        val bitmap = if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val factor = maxOf(info.size.width, info.size.height).toFloat() / 512f
            if (factor > 1f) decoder.setTargetSize((info.size.width / factor).toInt().coerceAtLeast(1), (info.size.height / factor).toInt().coerceAtLeast(1))
        } else requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }))
        try {
            val side = minOf(bitmap.width, bitmap.height)
            val square = Bitmap.createBitmap(bitmap, (bitmap.width-side)/2, (bitmap.height-side)/2, side, side)
            val small = Bitmap.createScaledBitmap(square, 256, 256, true)
            return try {
                ByteArrayOutputStream().use { output ->
                    check(small.compress(Bitmap.CompressFormat.JPEG, 85, output))
                    Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                }
            } finally { if (small !== square && small !== bitmap) small.recycle(); if (square !== bitmap) square.recycle() }
        } finally { bitmap.recycle() }
    }
}

package com.example.waferai.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.util.AtomicFile
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class PublicFont(val name: String, val category: String, val subsets: List<String>) {
    fun matches(query: String): Boolean = query.trim().split(Regex("\\s+")).all { word ->
        val token = when (word) {
            "中文", "汉字" -> "chinese"
            "简体" -> "chinese-simplified"
            "繁体" -> "chinese-traditional"
            "日文" -> "japanese"
            "韩文" -> "korean"
            "手写" -> return@all category.equals("handwriting", true)
            "衬线" -> return@all category.equals("serif", true)
            "无衬线" -> return@all category.equals("sans serif", true)
            "等宽" -> return@all category.equals("monospace", true)
            else -> word
        }
        name.contains(token, true) || category.contains(token, true) || subsets.any { it.contains(token, true) }
    }
}

internal data class FontCatalog(val fonts: List<PublicFont>, val online: Boolean)

internal class FontLibrary(context: Context) {
    private val dir = File(context.noBackupFilesDir, "downloaded-fonts")
    private val assets = context.assets

    fun installed(): List<String> = dir.listFiles().orEmpty()
        .filter { it.extension == "name" && File(dir, it.nameWithoutExtension + ".font").isFile }
        .mapNotNull { runCatching { it.readText().take(140) }.getOrNull() }.sortedBy { it.lowercase() }

    fun family(name: String?): FontFamily? = name?.let {
        val file = File(dir, "${id(it)}.font")
        if (!file.isFile) null else runCatching { FontFamily(Typeface.createFromFile(file)) }.getOrNull()
    }

    fun license(name: String): String? = runCatching { File(dir, "${id(name)}.license").readText() }.getOrNull()

    suspend fun catalog(): FontCatalog = withContext(Dispatchers.IO) {
        cachedCatalog?.let { return@withContext FontCatalog(it, online = true) }
        try {
            val url = "https://fonts.google.com/metadata/fonts".toHttpUrl()
            val fonts = parseCatalog(download(url, 8 * 1024 * 1024, quick = true))
            cachedCatalog = fonts
            FontCatalog(fonts, online = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            FontCatalog(assets.open("public-font-catalog.json").use { parseCatalog(it.readBytes()) }, online = false)
        }
    }

    private fun parseCatalog(bytes: ByteArray): List<PublicFont> {
        val rows = json(bytes).getJSONArray("familyMetadataList")
        val fonts = rows.objects().mapNotNull { entry ->
            val name = entry.optString("family").trim().takeIf { it.length in 1..140 } ?: return@mapNotNull null
            val subsets = entry.optJSONArray("subsets") ?: JSONArray()
            PublicFont(name, entry.optString("category"), List(subsets.length()) { subsets.optString(it) })
        }
        check(fonts.isNotEmpty()) { "字体目录没有返回结果。" }
        return fonts.distinctBy { it.name }
    }

    suspend fun install(font: PublicFont): String = withContext(Dispatchers.IO) {
        val name = font.name
        if (name in installed()) return@withContext name
        val (bytes, license) = try {
            googleFont(name)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            githubFont(name)
        }
        val magic = bytes.take(4)
        require(magic == listOf<Byte>(0, 1, 0, 0) || magic == "OTTO".toByteArray().toList()) {
            "下载结果不是有效的字体文件。"
        }
        synchronized(storageLock) {
            check(dir.isDirectory || dir.mkdirs()) { "无法创建字体目录。" }
            val id = id(name)
            val stage = File.createTempFile("font-", ".part", dir)
            try {
                stage.writeBytes(bytes)
                Typeface.createFromFile(stage)
                write(File(dir, "$id.font"), bytes)
                write(File(dir, "$id.license"), license.toByteArray())
                write(File(dir, "$id.name"), name.toByteArray())
            } finally {
                stage.delete()
            }
        }
        name
    }

    private suspend fun googleFont(name: String): Pair<ByteArray, String> {
        val url = "https://fonts.google.com/download/list".toHttpUrl().newBuilder()
            .addQueryParameter("family", name).build()
        val manifest = json(download(url, 384 * 1024, quick = true)).getJSONObject("manifest")
        val license = manifest.optJSONArray("files")?.objects()?.firstOrNull { entry ->
            isLicense(entry.optString("filename"))
        }?.optString("contents")?.takeIf { it.isNotBlank() }
            ?: error("此字体没有可确认的公开许可。")
        val regular = pickFont(manifest.getJSONArray("fileRefs").objects(), "filename")
        val asset = regular.optString("url").toHttpUrlOrNull()
            ?.takeIf { it.isHttps && it.host == "fonts.gstatic.com" && it.username.isEmpty() && it.password.isEmpty() }
            ?: error("字体下载地址无法验证。")
        return download(asset, 28 * 1024 * 1024) to license
    }

    private suspend fun githubFont(name: String): Pair<ByteArray, String> {
        val slug = name.lowercase(java.util.Locale.ROOT).filter { it in 'a'..'z' || it in '0'..'9' }
        require(slug.isNotEmpty()) { "备用来源找不到此字体。" }
        for (folder in listOf("ofl", "apache", "ufl")) {
            val url = "https://api.github.com/repos/google/fonts/contents/$folder/$slug".toHttpUrl()
            val refs = try {
                JSONArray(download(url, 384 * 1024, quick = true).toString(Charsets.UTF_8)).objects()
            } catch (error: FontHttpError) {
                if (error.code == 404) continue
                throw error
            }
            val licenseRef = refs.firstOrNull { isLicense(it.optString("name")) }
                ?: error("此字体没有可确认的公开许可。")
            val fontRef = pickFont(refs, "name")
            fun source(ref: JSONObject): HttpUrl =
                ref.optString("download_url").toHttpUrlOrNull()?.takeIf {
                    it.isHttps && it.host == "raw.githubusercontent.com" &&
                        it.encodedPath.startsWith("/google/fonts/") && it.username.isEmpty() && it.password.isEmpty()
                } ?: error("备用字体地址无法验证。")
            val license = download(source(licenseRef), 120 * 1024).toString(Charsets.UTF_8)
            check(license.isNotBlank()) { "公开许可文件为空。" }
            return download(source(fontRef), 28 * 1024 * 1024) to license
        }
        error("备用来源找不到此字体，请稍后重试。")
    }

    private fun isLicense(name: String) = name.substringAfterLast('/').lowercase() in
        setOf("ofl.txt", "license", "license.txt", "apache.txt", "ufl.txt", "licence.txt", "licence")

    private fun pickFont(refs: List<JSONObject>, field: String): JSONObject {
        val files = refs.filter { it.optString(field).substringAfterLast('.').lowercase() in setOf("ttf", "otf") }
        return files.firstOrNull { it.optString(field).contains("-Regular.", true) }
            ?: files.firstOrNull { !it.optString(field).contains("Italic", true) }
            ?: files.firstOrNull() ?: error("此字体暂未提供可下载的 TTF 或 OTF。")
    }

    private fun write(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun id(name: String) = MessageDigest.getInstance("SHA-256").digest(name.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun json(bytes: ByteArray): JSONObject {
        val text = bytes.toString(Charsets.UTF_8)
        val start = text.indexOf('{')
        require(start >= 0) { "字体服务返回了无法读取的数据。" }
        return JSONObject(text.substring(start))
    }

    private fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }

    private class FontHttpError(val code: Int) : IllegalStateException("字体服务请求失败（$code）。")

    private suspend fun download(url: HttpUrl, max: Int, quick: Boolean = false): ByteArray = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("User-Agent", "WaferAI/3.7 Android").build()
        val call = http.newCall(request)
        if (quick) call.timeout().timeout(6, TimeUnit.SECONDS)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        if (!it.isSuccessful) throw FontHttpError(it.code)
                        checkNotNull(it.body).source().use { source ->
                            check(!source.request(max.toLong() + 1)) { "字体文件超过下载大小限制。" }
                            source.readByteArray()
                        }
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    companion object {
        @Volatile private var cachedCatalog: List<PublicFont>? = null
        private val storageLock = Any()
        private val http = OkHttpClient.Builder().followRedirects(false).callTimeout(45, TimeUnit.SECONDS).build()
    }
}

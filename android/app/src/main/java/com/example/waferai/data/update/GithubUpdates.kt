package com.example.waferai.data.update

import com.example.waferai.BuildConfig
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit

internal data class AppRelease(val version: String, val code: Int, val notes: String, val page: String,
    val apk: String, val sha256: String, val newer: Boolean)

internal object GithubUpdates {
    const val repository = "https://github.com/qingjiu1021-love/WaferAI"
    private const val api = "https://api.github.com/repos/qingjiu1021-love/WaferAI/releases/latest"
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).followRedirects(false).build()

    suspend fun latest(): AppRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(api).header("Accept", "application/vnd.github+json")
            .header("User-Agent", "WaferAI/${BuildConfig.VERSION_NAME} (Android)").build()
        client.newCall(request).execute().use { response ->
            check(response.code != 404) { "暂时没有可用的正式版本，请稍后再试。" }
            check(response.code != 403 && response.code != 429) { "GitHub 请求受限，请稍后重试或打开发布页面查看。" }
            check(response.isSuccessful) { "无法获取版本信息（${response.code}）。" }
            val source = response.body?.source() ?: error("版本信息为空。")
            source.request(1_000_001)
            require(source.buffer.size <= 1_000_000) { "版本信息过大。" }
            val bytes = source.readByteArray()
            parse(String(bytes, Charsets.UTF_8), BuildConfig.VERSION_CODE)
        }
    }

    fun parse(text: String, installedCode: Int): AppRelease {
        val data = JsonParser.parseString(text).asJsonObject
        require(!data.get("draft").asBoolean && !data.get("prerelease").asBoolean) { "当前发布不是正式版本。" }
        val tag = data.get("tag_name").asString
        require(tag.matches(Regex("v\\d+\\.\\d+\\.\\d+"))) { "版本号格式异常。" }
        val version = tag.removePrefix("v")
        val body = data.get("body")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        val code = Regex("<!-- waferai-version-code:(\\d+) -->").find(body)?.groupValues?.get(1)?.toIntOrNull()
            ?: error("此版本未提供升级信息，请打开发布页面查看。")
        val hash = Regex("<!-- waferai-apk-sha256:([a-f0-9]{64}) -->").find(body)?.groupValues?.get(1)
            ?: error("此版本缺少安装包校验信息。")
        val asset = data.getAsJsonArray("assets").map { it.asJsonObject }.singleOrNull {
            it.get("name").asString == "WaferAI-$version.apk" && it.get("size").asLong > 0
        } ?: error("此版本尚未提供可下载的 Android 安装包。")
        val page = data.get("html_url").asString
        val apk = asset.get("browser_download_url").asString
        require(page == "$repository/releases/tag/$tag") { "发布地址校验失败。" }
        val url = apk.toHttpUrlOrNull() ?: error("安装包地址无效。")
        require(url.isHttps && url.host == "github.com" && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
            url.encodedPath == "/qingjiu1021-love/WaferAI/releases/download/$tag/WaferAI-$version.apk") { "安装包地址不属于 WaferAI 官方仓库。" }
        require(code > 0)
        return AppRelease(version, code, body.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "").trim().take(4000), page, apk, hash, code > installedCode)
    }
}

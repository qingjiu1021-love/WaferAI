package com.example.waferai.data.ai

import android.text.Html
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class ChatSource(val title: String, val url: String, val snippet: String, val provider: String, val retrievedAt: Long)
internal data class ChatSearchResult(val sources: List<ChatSource>, val notice: String? = null)

internal class ChatWebSearch(private val knowledge: WaferKnowledge? = null,
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).followRedirects(false).build(),
    private val endpoint: String = "https://www.bing.com/search") {
    suspend fun search(question: String): ChatSearchResult {
        val query = question.trim().take(200)
        require(query.isNotBlank())
        val issues = mutableListOf<String>()
        val configured = knowledge?.let { k -> SearchPlugin.entries.filter { k.plugins.state(it).enabled }
            .sortedBy { if (it in setOf(SearchPlugin.BAIDU, SearchPlugin.GOOGLE)) 0 else 1 }.take(2) }.orEmpty()
        val fromPlugins = coroutineScope {
            configured.map { plugin -> async {
                try { knowledge!!.lookupForChat(plugin, query).map { ChatSource(it.title, it.source, it.body, plugin.title, System.currentTimeMillis()) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptyList() }
            } }.awaitAll().flatten()
        }
        val web = try { val body = fetch(query); withContext(Dispatchers.IO) { parse(body) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { issues += "通用网页搜索暂时不可用。"; emptyList() }
        val hits = (web + fromPlugins).distinctBy { it.url }.take(6)
        return ChatSearchResult(hits, if (hits.isEmpty()) "未取得可用网页资料，请检查网络、调整关键词，或在工具中连接搜索插件。" else issues.firstOrNull())
    }
    private suspend fun fetch(query: String): String = suspendCancellableCoroutine { continuation ->
        val url = endpoint.toHttpUrl().newBuilder().addQueryParameter("format", "rss").addQueryParameter("q", query).build()
        val call = http.newCall(Request.Builder().url(url).header("User-Agent", "WaferAI (Android; web search)").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.use {
                        check(it.isSuccessful) { "搜索服务未响应。" }
                        val source = requireNotNull(it.body).source()
                        check(!source.request(500_001)) { "搜索结果过大。" }
                        source.readUtf8()
                    }
                    if (continuation.isActive) continuation.resume(body)
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            }
        })
    }
    companion object {
        fun parse(xml: String): List<ChatSource> {
            require(xml.length <= 500_000 && !xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true))
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(xml.reader())
            val result = mutableListOf<ChatSource>()
            var fields: MutableMap<String, String>? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "item" -> fields = mutableMapOf()
                        "title", "link", "description" -> if (fields != null) { val name = parser.name; fields[name] = parser.nextText() }
                    }
                } else if (event == XmlPullParser.END_TAG && parser.name == "item") {
                    val item = fields.orEmpty()
                    val url = item["link"]?.trim()?.toHttpUrlOrNull()?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.host != "localhost" }
                    fun clean(value: String?) = Html.fromHtml(value.orEmpty(), Html.FROM_HTML_MODE_LEGACY).toString().filterNot(Char::isISOControl).trim()
                    val title = clean(item["title"]).take(200)
                    if (url != null && title.isNotBlank()) result += ChatSource(title, url.toString(), clean(item["description"]).take(600), "Bing", System.currentTimeMillis())
                    fields = null
                }
                event = parser.next()
            }
            require(parser.depth == 0)
            return result.distinctBy { it.url }.take(6)
        }
        fun context(result: ChatSearchResult): String = JSONObject()
            .put("search_status", if (result.sources.isEmpty()) "no_results" else "retrieved")
            .put("notice", result.notice.orEmpty())
            .put("sources", JSONArray(result.sources.mapIndexed { index, source -> JSONObject()
                .put("number", index + 1).put("title", source.title).put("url", source.url)
                .put("snippet", source.snippet).put("retrieved_at_ms", source.retrievedAt) })).toString()
    }
}

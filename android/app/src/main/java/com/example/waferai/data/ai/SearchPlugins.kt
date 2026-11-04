package com.example.waferai.data.ai

import android.content.Context
import android.text.Html
import androidx.annotation.DrawableRes
import com.example.waferai.BuildConfig
import com.example.waferai.R
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal enum class SearchRegion { DOMESTIC, OVERSEAS }

internal enum class SearchPlugin(
    val title: String,
    val help: String,
    val region: SearchRegion,
    val category: String,
    @DrawableRes val icon: Int,
    val sites: List<String> = emptyList()
) {
    BAIDU("百度", "查找公开的行业文章、晶圆资料和技术文档。",
        SearchRegion.DOMESTIC, "网页搜索", R.drawable.plugin_baidu),
    GITEE("Gitee 码云", "查找公开的代码仓库和晶圆图工具。",
        SearchRegion.DOMESTIC, "代码仓库", R.drawable.plugin_gitee),
    ZHIHU("知乎", "查找公开的技术问答和文章。",
        SearchRegion.DOMESTIC, "技术问答", R.drawable.plugin_zhihu, listOf("zhihu.com")),
    BILIBILI("哔哩哔哩", "查找公开的课程、技术演示和视频页面。",
        SearchRegion.DOMESTIC, "视频教程", R.drawable.plugin_bilibili, listOf("bilibili.com")),
    CSDN("CSDN", "查找公开博客、开发笔记和技术教程。",
        SearchRegion.DOMESTIC, "技术博客", R.drawable.plugin_csdn, listOf("csdn.net")),
    JUEJIN("掘金", "查找公开的 Android、Python 与数据可视化文章。",
        SearchRegion.DOMESTIC, "技术博客", R.drawable.plugin_juejin, listOf("juejin.cn")),
    CNBLOGS("博客园", "查找公开的开发笔记与工程文章。",
        SearchRegion.DOMESTIC, "技术博客", R.drawable.plugin_cnblogs, listOf("cnblogs.com")),
    MODELSCOPE("魔搭社区", "查找公开的模型、数据集与技术页面。",
        SearchRegion.DOMESTIC, "模型与数据集", R.drawable.plugin_modelscope, listOf("modelscope.cn")),
    WECHAT_ARTICLES("微信公众号文章", "查找可公开访问的行业文章。",
        SearchRegion.DOMESTIC, "行业资料", R.drawable.plugin_wechat_articles, listOf("mp.weixin.qq.com")),
    GITHUB("GitHub", "查找公开的晶圆项目和代码仓库。",
        SearchRegion.OVERSEAS, "代码仓库", R.drawable.plugin_github),
    WIKIPEDIA("Wikipedia", "查找晶圆制造、良率和缺陷分类等百科条目。",
        SearchRegion.OVERSEAS, "百科", R.drawable.plugin_wikipedia),
    GOOGLE("Google", "通过 Serper 查找公开资料、技术指南和网页。",
        SearchRegion.OVERSEAS, "网页搜索", R.drawable.plugin_google),
    CROSSREF("Crossref", "查找公开的论文书目、出版信息和 DOI。",
        SearchRegion.OVERSEAS, "学术文献", R.drawable.plugin_crossref),
    STACKOVERFLOW("Stack Overflow", "查找公开的编程问答和实现思路。",
        SearchRegion.OVERSEAS, "技术问答", R.drawable.plugin_stackoverflow);

    val credentialOwner: SearchPlugin?
        get() = when {
            sites.isNotEmpty() -> BAIDU
            this == BAIDU || this == GOOGLE -> this
            else -> null
        }
    val needsKey get() = credentialOwner != null
    val providerName get() = when (credentialOwner) {
        BAIDU -> "百度千帆"
        GOOGLE -> "Serper"
        else -> title
    }
    val keyLabel get() = when (credentialOwner) {
        BAIDU -> "千帆 AppBuilder API Key"
        GOOGLE -> "Serper API Key"
        else -> "API Key"
    }
    val testQuery get() = when (this) {
        GITEE -> "gitee-cli"
        WIKIPEDIA -> "semiconductor"
        STACKOVERFLOW -> "image classification"
        else -> if (region == SearchRegion.DOMESTIC) "半导体 晶圆" else "wafer map"
    }
}

internal data class SearchPluginState(val enabled: Boolean, val hasKey: Boolean)

internal class SearchPluginStore(context: Context) {
    private val prefs = context.getSharedPreferences("knowledge_plugins", Context.MODE_PRIVATE)
    private val vault = AiKeyVault("wafer-search-plugin-keys-v1")

    fun state(plugin: SearchPlugin) = SearchPluginState(
        prefs.getBoolean(plugin.name + "_on", false),
        plugin.credentialOwner?.let { prefs.contains(it.name + "_key") } ?: false
    )

    @Synchronized
    fun save(plugin: SearchPlugin, enabled: Boolean, key: String? = null, clear: Boolean = false) {
        val input = pluginKey(plugin, key)
        require(!enabled || !plugin.needsKey || input != null || (state(plugin).hasKey && !clear)) { "请先填写 API Key。" }
        val edit = prefs.edit().putBoolean(plugin.name + "_on", enabled)
        val owner = plugin.credentialOwner
        if (clear && owner != null) {
            edit.remove(owner.name + "_key").remove(owner.name + "_iv")
            SearchPlugin.entries.filter { it.credentialOwner == owner }.forEach {
                edit.putBoolean(it.name + "_on", false)
            }
        }
        if (input != null) {
            requireNotNull(owner)
            val (encrypted, iv) = vault.encrypt(input)
            edit.putString(owner.name + "_key", encrypted).putString(owner.name + "_iv", iv)
        }
        check(edit.commit()) { "无法保存插件设置。" }
    }

    @Synchronized
    fun key(plugin: SearchPlugin): String? {
        val owner = plugin.credentialOwner ?: return null
        val encrypted = prefs.getString(owner.name + "_key", null) ?: return null
        return runCatching {
            vault.decrypt(encrypted, requireNotNull(prefs.getString(owner.name + "_iv", null)))
        }.getOrElse {
            throw SearchPluginException("${plugin.title} 的本机密钥无法读取，请在插件中重新填写。")
        }
    }
}

internal fun pluginKey(plugin: SearchPlugin, key: String?): String? {
    val trimmed = key?.trim().orEmpty()
    val value = if (plugin == SearchPlugin.GOOGLE && trimmed.startsWith("X-API-KEY:", ignoreCase = true)) {
        trimmed.substringAfter(':').trim()
    } else {
        trimmed
    }
    require(trimmed.isEmpty() || (value.length in 1..512 && value.all { it.code in 33..126 })) {
        "请填写完整的 ${plugin.keyLabel}。"
    }
    return value.takeIf(String::isNotEmpty)
}

internal class SearchPluginException(message: String, val status: Int? = null) : IllegalStateException(message)

internal object SearchPluginApi {
    fun request(plugin: SearchPlugin, query: String, key: String?, wikiMobile: Boolean = false): Request {
        if (plugin.needsKey && key.isNullOrBlank()) {
            throw SearchPluginException("${plugin.title} 需要 ${plugin.keyLabel}，请在插件中填写。")
        }
        val base = Request.Builder()
            .header("User-Agent", "WaferAI/${BuildConfig.VERSION_NAME} (Android wafer-map knowledge search)")
        fun url(endpoint: String, vararg parameters: Pair<String, String>) =
            endpoint.toHttpUrl().newBuilder().apply { parameters.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        fun post(payload: JSONObject) = payload.toString().toRequestBody("application/json".toMediaType())
        return when (plugin) {
            SearchPlugin.GITHUB -> base.url(url("https://api.github.com/search/repositories",
                "q" to query, "per_page" to "6")).header("Accept", "application/vnd.github+json").build()
            SearchPlugin.GITEE -> base.url(url("https://gitee.com/api/v5/search/repos",
                "q" to query, "size" to "6")).header("Accept", "application/json").build()
            SearchPlugin.WIKIPEDIA -> {
                val language = if (query.any { it in '\u4e00'..'\u9fff' }) "zh" else "en"
                val subdomain = if (wikiMobile) "$language.m" else language
                base.url(url("https://$subdomain.wikipedia.org/w/rest.php/v1/search/page",
                    "q" to query, "limit" to "6")).header("Accept", "application/json").build()
            }
            SearchPlugin.GOOGLE -> base.url("https://google.serper.dev/search").header("X-API-KEY", requireNotNull(key))
                .post(post(JSONObject().put("q", query).put("num", 6))).build()
            SearchPlugin.CROSSREF -> base.url(url("https://api.crossref.org/works",
                "query.bibliographic" to query, "rows" to "6", "select" to "DOI,title,container-title")).build()
            SearchPlugin.STACKOVERFLOW -> base.url(url("https://api.stackexchange.com/2.3/search/advanced",
                "q" to query, "pagesize" to "6", "site" to "stackoverflow", "sort" to "relevance")).build()
            else -> {
                check(plugin.credentialOwner == SearchPlugin.BAIDU)
                val body = JSONObject().put("messages", JSONArray().put(chatMessage("user", query)))
                    .put("search_source", "baidu_search_v2")
                    .put("resource_type_filter", JSONArray().put(JSONObject().put("type", "web").put("top_k", 6)))
                if (plugin.sites.isNotEmpty()) {
                    body.put("search_filter", JSONObject().put("match", JSONObject().put("site", JSONArray(plugin.sites))))
                }
                base.url("https://qianfan.baidubce.com/v2/ai_search/web_search")
                    .header("Authorization", "Bearer ${requireNotNull(key)}")
                    .post(post(body)).build()
            }
        }
    }

    fun parse(plugin: SearchPlugin, request: Request, payload: String): List<KnowledgeEntry> {
        val json = JSONObject(payload)
        val code = json.optInt("statusCode", json.optInt("code", 0))
        if (json.has("error") || code >= 400 || json.optInt("code", 0) != 0) {
            throw SearchPluginException(httpError(plugin, code, payload), code)
        }
        val rows = when (plugin) {
            SearchPlugin.GITHUB, SearchPlugin.STACKOVERFLOW -> json.getJSONArray("items")
            SearchPlugin.GITEE -> json.getJSONArray("data")
            SearchPlugin.CROSSREF -> json.getJSONObject("message").getJSONArray("items")
            SearchPlugin.WIKIPEDIA -> json.getJSONArray("pages")
            SearchPlugin.GOOGLE -> json.optJSONArray("organic") ?: run {
                if (!json.has("searchParameters")) throw SearchPluginException("Google 返回了无法读取的搜索结果。")
                JSONArray()
            }
            else -> json.getJSONArray("references")
        }
        fun JSONObject.value(key: String) = (opt(key) as? String).orEmpty()
        fun clean(text: String, length: Int) = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()
            .filterNot(Char::isISOControl).trim().take(length)
        return (0 until minOf(rows.length(), 6)).mapNotNull { index ->
            val item = rows.optJSONObject(index) ?: return@mapNotNull null
            val (address, title, description) = when (plugin) {
                SearchPlugin.GITHUB -> {
                    if (item.optBoolean("private")) return@mapNotNull null
                    Triple(item.value("html_url"), item.value("full_name"), item.value("description"))
                }
                SearchPlugin.GITEE -> {
                    if (item.optBoolean("private")) return@mapNotNull null
                    Triple(item.value("url"), item.value("title").ifBlank { item.value("name") }, item.value("description"))
                }
                SearchPlugin.WIKIPEDIA -> {
                    val id = item.optLong("id")
                    if (id <= 0) return@mapNotNull null
                    val host = request.url.host.replace(".m.wikipedia.org", ".wikipedia.org")
                    Triple("https://$host/?curid=$id", item.value("title"),
                        item.value("excerpt").ifBlank { item.value("description") })
                }
                SearchPlugin.GOOGLE -> Triple(item.value("link"), item.value("title"), item.value("snippet"))
                SearchPlugin.CROSSREF -> {
                    val doi = item.value("DOI")
                    if (!doi.startsWith("10.") || "/" !in doi) return@mapNotNull null
                    val target = "https://doi.org".toHttpUrl().newBuilder().addPathSegments(doi).build()
                    Triple(target.toString(), item.optJSONArray("title")?.optString(0).orEmpty(),
                        item.optJSONArray("container-title")?.optString(0).orEmpty())
                }
                SearchPlugin.STACKOVERFLOW -> Triple(item.value("link"), item.value("title"), "Stack Overflow question")
                else -> Triple(item.value("url"), item.value("title"),
                    item.value("content").ifBlank { item.value("snippet") })
            }
            val safe = address.toHttpUrlOrNull()?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }
                ?: return@mapNotNull null
            if (plugin == SearchPlugin.GITEE && safe.host != "gitee.com") return@mapNotNull null
            if (plugin.sites.isNotEmpty() && plugin.sites.none { safe.host == it || safe.host.endsWith(".$it") }) {
                return@mapNotNull null
            }
            val name = clean(title, 240).takeIf(String::isNotBlank) ?: return@mapNotNull null
            KnowledgeEntry(safe.toString(), name, (listOf(plugin.title, clean(description, 440))
                .filter(String::isNotBlank)).joinToString(" · "))
        }
    }

    fun httpError(plugin: SearchPlugin, code: Int, payload: String = ""): String {
        val status = if (code >= 300) "（HTTP $code）" else ""
        val credits = plugin == SearchPlugin.GOOGLE &&
            listOf("not enough credit", "insufficient credit", "no credits", "out of credits").any {
                payload.contains(it, ignoreCase = true)
            }
        val reason = when {
            credits || code == 402 -> "账户搜索额度不足，请检查 ${plugin.providerName} 账户。"
            (code == 401 || code == 403) && plugin.needsKey -> "服务拒绝了 ${plugin.keyLabel}，请检查密钥和账户权限。"
            code == 429 -> "请求过于频繁，请稍后重试。"
            code == 403 -> "服务拒绝了当前连接，请检查手机网络后重试。"
            else -> "搜索服务暂时不可用，请稍后重试。"
        }
        return "${plugin.title}$status $reason"
    }
}

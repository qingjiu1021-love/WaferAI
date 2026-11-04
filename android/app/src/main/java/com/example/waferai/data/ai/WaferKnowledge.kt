package com.example.waferai.data.ai

import android.content.Context
import android.util.AtomicFile
import com.example.waferai.data.local.LogOperation
import com.example.waferai.data.local.OperationLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.TimeUnit

internal data class KnowledgeEntry(
    val source: String, val title: String, val body: String,
    val keywords: List<String> = emptyList(), val titleEn: String = title, val bodyEn: String = body
) {
    fun title(english: Boolean) = if (english) titleEn else title
    fun body(english: Boolean) = if (english) bodyEn else body
}

internal data class KnowledgeState(val online: List<KnowledgeEntry> = emptyList(), val syncedAt: Long = 0)

private const val Ward = "https://norma.ncirl.ie/6681/1/michaelward.pdf"
internal val BuiltInKnowledge = listOf(
    KnowledgeEntry("$Ward#page=20", "Ward 的模型对照与验证",
        "Ward 在 WM-811K 上比较了 SVM 和顺序神经网络，并选用顺序模型制作原型。类别不均衡时，可同时看各类召回率、宏平均 F1 与混淆矩阵。WaferAI 评估时按批次隔离训练和测试，以减少相似样本泄漏。",
        listOf("ward", "论文", "svm", "cnn", "wm-811k", "不均衡", "验证", "召回率", "macro", "recall", "validation"),
        "Ward's model comparison and validation",
        "Ward compared an SVM and a sequential neural network on WM-811K and used the sequential model in his prototype. For class imbalance, inspect per-class recall, macro F1 and the confusion matrix. WaferAI separates evaluation lots to reduce leakage."),
    KnowledgeEntry("$Ward#page=21", "Ward 的原型与新样本复核",
        "Ward 的桌面原型可手动载入晶圆图，也能监测新文件并发出分类提示。论文用人工绘制的划痕样本演示流程，单片预测得分与独立测试集准确率应分开解读。",
        listOf("ward", "原型", "划痕", "自动监测", "论文", "图片", "scratch", "prototype", "watch", "image", "confidence"),
        "Ward's prototype and sample checks",
        "Ward's prototype loaded maps manually or watched for new files and alerted on a classification. It demonstrated a synthetic scratch case. Interpret a single sample's score separately from held-out accuracy."),
    KnowledgeEntry("https://github.com/ambruhsia/WaferVision", "缺陷连通区和区域密度",
        "WaferVision 使用区域缺陷密度、Radon 变换统计与连通区域几何特征作为分类输入。WaferAI 按上下左右相邻合并不合格芯片，可用最大连通区的位置和大小复核空间模式。",
        listOf("聚集", "连通", "区域", "密度", "缺陷", "wafervision", "cluster", "density", "radon"),
        "Defect clusters and regional density",
        "WaferVision uses regional density, Radon statistics and connected-region geometry. WaferAI groups orthogonally adjacent failed dies so the largest cluster can inform review."),
    KnowledgeEntry("https://github.com/wafertools/tsmap", "STDF、ATDF 与坐标导入",
        "STDF 用二进制记录存储电测数据，ATDF 用文本交换记录。WaferAI 从有效 PRR 的坐标和合格标记重建晶圆图。CSV 和 Parquet 采用 lot_code、wafer_code、row、col、status 五列。",
        listOf("stdf", "atdf", "prr", "parquet", "csv", "坐标", "导入", "电测"),
        "STDF, ATDF and coordinate import",
        "STDF stores binary test records and ATDF is a text interchange format. WaferAI uses valid PRR coordinates and pass/fail flags. CSV and Parquet use lot_code, wafer_code, row, col and status.")
)

internal class WaferKnowledge(
    context: Context,
    private val log: OperationLog,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build()
) {
    val plugins = SearchPluginStore(context)
    private val file = AtomicFile(File(context.noBackupFilesDir, "wafer-knowledge.json"))
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    private val lock = Mutex()

    fun search(query: String): List<KnowledgeEntry> {
        val input = query.lowercase().trim()
        val entries = BuiltInKnowledge + mutable.value.online
        if (input.isBlank()) return entries
        val aliases = mapOf("晶圆图" to "wafer map", "缺陷" to "defect", "良率" to "yield",
            "划痕" to "scratch", "分类" to "classification", "边缘" to "edge", "连通" to "cluster")
        val terms = Regex("[a-z0-9-]{3,}").findAll(input).map { it.value }.toSet() +
            aliases.filterKeys { it in input }.values
        return entries.map { entry ->
            val title = entry.title.lowercase() + " " + entry.titleEn.lowercase()
            val score = entry.keywords.count { it in input } * 3 + terms.count { it in title } * 2 +
                if (input.length >= 2 && (input in title || input in entry.body.lowercase())) 5 else 0
            entry to score
        }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
    }

    data class RefreshResult(val added: Int, val failures: List<String> = emptyList())

    suspend fun lookupForChat(plugin: SearchPlugin, query: String): List<KnowledgeEntry> = withContext(Dispatchers.IO) {
        check(plugins.state(plugin).enabled)
        fetch(plugin, query.trim().take(200))
    }

    suspend fun test(plugin: SearchPlugin, key: String? = null): Int = withContext(Dispatchers.IO) {
        fetch(plugin, plugin.testQuery, pluginKey(plugin, key) ?: plugins.key(plugin)).size
    }

    suspend fun searchWith(plugin: SearchPlugin, query: String): List<KnowledgeEntry> = withContext(Dispatchers.IO) {
        val input = query.trim().take(120)
        require(input.isNotBlank()) { "请输入要搜索的关键词。" }
        check(plugins.state(plugin).enabled) { "请先连接此插件。" }
        try {
            val hits = fetch(plugin, input).distinctBy { it.source }
            lock.withLock {
                ensureActive()
                if (plugins.state(plugin).enabled && hits.isNotEmpty()) saveHits(hits, mutable.value.syncedAt)
            }
            log.record(LogOperation.KNOWLEDGE, true)
            hits
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log.record(LogOperation.KNOWLEDGE, false)
            throw error
        }
    }

    suspend fun refresh(
        force: Boolean = false, manualQuery: String = "",
        minimumAgeMs: Long = TimeUnit.HOURS.toMillis(20), recordAutomatic: Boolean = true
    ): RefreshResult = withContext(Dispatchers.IO) {
        lock.withLock {
            val now = System.currentTimeMillis()
            if (!force && now - mutable.value.syncedAt < minimumAgeMs) return@withLock RefreshResult(0)
            try {
                val topics = listOf("wafer map defect classification", "wafer map yield analysis", "semiconductor wafer bin map")
                val enabled = SearchPlugin.entries.filter { plugins.state(it).enabled }
                if (enabled.isEmpty()) {
                    if (force) error("请先连接并启用搜索插件。")
                    return@withLock RefreshResult(0)
                }
                val query = manualQuery.trim().take(120).ifBlank { topics[(now / TimeUnit.DAYS.toMillis(1) % topics.size).toInt()] }
                val results = coroutineScope {
                    enabled.map { plugin -> async {
                        try { fetch(plugin, query) to null }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            emptyList<KnowledgeEntry>() to
                                ((error as? SearchPluginException)?.message ?: "${plugin.title} 搜索失败。")
                        }
                    } }.awaitAll()
                }
                val failures = results.mapNotNull { it.second }
                check(failures.size < enabled.size) { failures.joinToString("\n") }
                val hits = results.flatMap { it.first }.distinctBy { it.source }
                ensureActive()
                val added = saveHits(hits, if (recordAutomatic) now else mutable.value.syncedAt)
                log.record(LogOperation.KNOWLEDGE, true)
                RefreshResult(added, failures)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                log.record(LogOperation.KNOWLEDGE, false)
                throw error
            }
        }
    }

    private fun saveHits(hits: List<KnowledgeEntry>, syncedAt: Long): Int {
        val old = mutable.value.online
        val next = KnowledgeState((hits + old).distinctBy { it.source }.take(60), syncedAt)
        write(next)
        mutable.value = next
        val existing = old.mapTo(HashSet()) { it.source }
        return hits.count { it.source !in existing }
    }

    private suspend fun fetch(plugin: SearchPlugin, query: String, key: String? = plugins.key(plugin)): List<KnowledgeEntry> {
        suspend fun search(wikiMobile: Boolean): List<KnowledgeEntry> {
            val request = SearchPluginApi.request(plugin, query, key, wikiMobile)
            val body = fetchBody(plugin, request)
            return try {
                SearchPluginApi.parse(plugin, request, body)
            } catch (_: JSONException) {
                throw SearchPluginException("${plugin.title} 返回了无法读取的搜索结果。")
            }
        }
        return try {
            search(false)
        } catch (error: SearchPluginException) {
            if (plugin != SearchPlugin.WIKIPEDIA || error.status == 429) throw error
            search(true)
        }
    }

    private suspend fun fetchBody(plugin: SearchPlugin, request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(
                    SearchPluginException("无法连接 ${plugin.providerName}，请检查手机网络。")
                )
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val payload = response.use {
                        if (!it.isSuccessful) {
                            val details = if (plugin == SearchPlugin.GOOGLE) it.peekBody(4096).string() else ""
                            throw SearchPluginException(SearchPluginApi.httpError(plugin, it.code, details), it.code)
                        }
                        checkNotNull(it.body).source().use { source ->
                            if (source.request(1_000_001)) {
                                throw SearchPluginException("${plugin.title} 的搜索结果超过大小限制。")
                            }
                            source.readUtf8()
                        }
                    }
                    if (continuation.isActive) continuation.resume(payload)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(
                        if (error is IOException) SearchPluginException("读取 ${plugin.providerName} 响应失败，请检查手机网络。") else error
                    )
                }
            }
        })
    }

    private fun read(): KnowledgeState = runCatching {
        val json = JSONObject(file.readFully().toString(Charsets.UTF_8))
        val entries = json.getJSONArray("entries")
        KnowledgeState(List(minOf(entries.length(), 60)) { index ->
            val item = entries.getJSONObject(index)
            KnowledgeEntry(item.getString("source"), item.getString("title"), item.getString("body"))
        }, json.getLong("synced_at"))
    }.getOrDefault(KnowledgeState())

    private fun write(state: KnowledgeState) {
        val entries = JSONArray(state.online.map {
            JSONObject().put("title", it.title).put("body", it.body).put("source", it.source)
        })
        val json = JSONObject().put("entries", entries).put("synced_at", state.syncedAt)
        val output = file.startWrite()
        try {
            output.write(json.toString().toByteArray())
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }
}

package com.example.waferai.ui.settings

import com.example.waferai.ui.theme.GlassFilterChip as FilterChip
import com.example.waferai.ui.theme.GlassButton as Button
import com.example.waferai.ui.theme.GlassTextButton as TextButton
import com.example.waferai.ui.theme.GlassOutlinedButton as OutlinedButton
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.waferai.data.ai.KnowledgeEntry
import com.example.waferai.data.ai.KnowledgeSyncJob
import com.example.waferai.data.ai.SearchPlugin
import com.example.waferai.data.ai.SearchPluginException
import com.example.waferai.data.ai.SearchPluginState
import com.example.waferai.data.ai.SearchRegion
import com.example.waferai.data.ai.WaferKnowledge
import com.example.waferai.ui.theme.LocalFloatingNavigationPadding
import com.example.waferai.ui.theme.StudioHeader
import com.example.waferai.ui.theme.StudioIcon
import com.example.waferai.ui.theme.StudioPanel
import com.example.waferai.ui.theme.BackButton
import com.example.waferai.ui.theme.UiText as Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class CatalogFilter(val label: String) {
    FEATURED("推荐"), DOMESTIC("国内"), OVERSEAS("海外"), CONNECTED("已连接")
}

private data class CatalogSection(val name: String, val plugins: List<SearchPlugin>)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchPluginsScreen(knowledge: WaferKnowledge, onBack: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val store = knowledge.plugins
    var settings by remember { mutableStateOf(SearchPlugin.entries.associateWith(store::state)) }
    var selected by rememberSaveable { mutableStateOf<SearchPlugin?>(null) }
    var filter by rememberSaveable { mutableStateOf(CatalogFilter.FEATURED) }
    var catalogQuery by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf(false) }
    var searchFeedback by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<KnowledgeEntry>?>(null) }
    val catalogScroll = rememberLazyListState()
    val detail = selected

    fun closeDetail() {
        selected = null
        message = ""
        results = null
    }

    fun runAction(forSearch: Boolean = false, block: suspend () -> String) {
        if (busy) return
        busy = true
        searchFeedback = forSearch
        failure = false
        message = ""
        scope.launch {
            try {
                message = block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = true
                message = when (error) {
                    is SearchPluginException, is IllegalArgumentException -> error.message.orEmpty()
                    else -> "操作失败，请检查网络或插件配置后重试。"
                }
            } finally {
                busy = false
            }
        }
    }

    fun save(plugin: SearchPlugin, enabled: Boolean, key: String? = null, clear: Boolean = false) {
        runAction {
            val verifiedCount = if (enabled && plugin == SearchPlugin.GOOGLE) knowledge.test(plugin, key) else null
            withContext(Dispatchers.IO) { store.save(plugin, enabled, key, clear) }
            settings = SearchPlugin.entries.associateWith(store::state)
            editing = false
            if (!enabled) results = null
            KnowledgeSyncJob.configure(context, knowledge)
            when {
                clear -> "已移除密钥，并断开依赖它的插件。"
                verifiedCount != null -> "Google 已连接，测试返回 $verifiedCount 条。"
                enabled -> "插件已连接，可以开始搜索。"
                else -> "插件已断开。"
            }
        }
    }

    val padding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp,
        bottom = 20.dp + LocalFloatingNavigationPadding.current)
    if (detail == null) {
        val visible = SearchPlugin.entries.filter { plugin ->
            val matches = catalogQuery.trim().let { term ->
                listOf(plugin.title, plugin.category, plugin.help, plugin.providerName, plugin.name)
                    .any { it.contains(term, ignoreCase = true) }
            }
            matches && when (filter) {
                CatalogFilter.FEATURED -> true
                CatalogFilter.DOMESTIC -> plugin.region == SearchRegion.DOMESTIC
                CatalogFilter.OVERSEAS -> plugin.region == SearchRegion.OVERSEAS
                CatalogFilter.CONNECTED -> settings.getValue(plugin).enabled
            }
        }
        val sections = when (filter) {
            CatalogFilter.FEATURED -> listOf(
                CatalogSection("国内应用", visible.filter { it.region == SearchRegion.DOMESTIC }),
                CatalogSection("海外应用", visible.filter { it.region == SearchRegion.OVERSEAS })
            )
            CatalogFilter.CONNECTED -> listOf(CatalogSection("已连接", visible))
            else -> listOf(CatalogSection(filter.label + "应用", visible))
        }
        LazyColumn(modifier.fillMaxSize(), state = catalogScroll, contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "catalog-header") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackButton(onBack)
                    StudioHeader("插件", "WAFERAI / APPS", StudioIcon.PLUG)
                    Text("连接常用应用，把公开资料保存到知识库。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(catalogQuery, { catalogQuery = it.take(80) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("搜索插件") }, shape = RoundedCornerShape(20.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CatalogFilter.entries.forEach { choice ->
                            FilterChip(filter == choice, { filter = choice }, { Text(choice.label) })
                        }
                    }
                }
            }
            if (visible.isEmpty()) item(key = "empty") {
                Text(if (filter == CatalogFilter.CONNECTED && catalogQuery.isBlank())
                    "还没有连接插件。可在推荐中选择一个应用。" else "没有找到匹配的插件。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            sections.filter { it.plugins.isNotEmpty() }.forEach { section ->
                item(key = section.name) {
                    Text(section.name, style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                }
                items(section.plugins, key = { it.name }) { plugin ->
                    CatalogCard(plugin, settings.getValue(plugin)) {
                        selected = plugin
                        message = ""
                        results = null
                    }
                }
            }
        }
    } else {
        val status = settings.getValue(detail)
        var keyword by rememberSaveable(detail.name) { mutableStateOf("") }
        val uris = LocalUriHandler.current
        BackHandler(enabled = !busy, onBack = ::closeDetail)
        LazyColumn(modifier.fillMaxSize(), contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                BackButton(::closeDetail, "返回插件", enabled = !busy)
                StudioPanel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PluginBadge(detail, 50)
                        Column(Modifier.weight(1f)) {
                            Text(detail.title, style = MaterialTheme.typography.headlineSmall)
                            Text(detail.category, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(detail.help)
                    if (status.enabled) {
                        Text("已连接", color = MaterialTheme.colorScheme.primary)
                        OutlinedButton({ save(detail, false) }, enabled = !busy) { Text("断开连接") }
                    } else {
                        Button({
                            if (detail.needsKey && !status.hasKey) editing = true else save(detail, true)
                        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("连接") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (detail.needsKey) {
                            TextButton({ message = ""; editing = true }, enabled = !busy) { Text("管理密钥") }
                        }
                        if (!detail.needsKey || status.hasKey) {
                            TextButton({
                                runAction {
                                    val count = knowledge.test(detail)
                                    "连接测试成功，返回 $count 条。"
                                }
                            }, enabled = !busy) { Text("测试连接") }
                        }
                    }
                    if (busy && !searchFeedback) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (message.isNotEmpty() && !searchFeedback) Text(message,
                        color = if (failure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
            item {
                StudioPanel(Modifier.fillMaxWidth()) {
                    Text("连接与权限", style = MaterialTheme.typography.titleMedium)
                    if (detail.needsKey) {
                        Text("通过 ${detail.providerName} 接入，需配置 ${detail.keyLabel}，使用时可能计费。")
                    } else {
                        Text("通过 ${detail.providerName} 的公开搜索接口接入，无需登录。")
                    }
                    Text("插件只读取公开搜索结果。服务会收到检索词，标题、摘要和来源链接可离线查看。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (detail.credentialOwner == SearchPlugin.BAIDU) {
                        Text("使用百度千帆的插件共用一把密钥。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (detail == SearchPlugin.GOOGLE) {
                        Text("连接时会向 Serper 发起一次测试搜索，可能消耗 1 次搜索额度。")
                        TextButton({ uris.openUri("https://serper.dev/") }) { Text("获取 Serper API Key") }
                    }
                }
            }
            if (status.enabled) item {
                StudioPanel(Modifier.fillMaxWidth()) {
                    Text("在此应用中搜索", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(keyword, { keyword = it.take(120) }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true, label = { Text("搜索关键词") }, enabled = !busy)
                    Button({
                        results = null
                        runAction(forSearch = true) {
                            val hits = knowledge.searchWith(detail, keyword)
                            results = hits
                            if (hits.isEmpty()) "此来源没有匹配结果。" else "已将 ${hits.size} 条结果保存到知识库。"
                        }
                    }, enabled = keyword.isNotBlank() && !busy) { Text("搜索并保存") }
                    if (busy && searchFeedback) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (searchFeedback && message.isNotBlank()) Text(message,
                        color = if (failure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(results.orEmpty(), key = { it.source }) { entry ->
                StudioPanel(Modifier.fillMaxWidth()) {
                    Text(entry.title, style = MaterialTheme.typography.titleMedium)
                    Text(entry.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton({
                        runCatching { uris.openUri(entry.source) }
                            .onFailure { message = "无法打开来源链接。"; failure = true; searchFeedback = true }
                    }) { Text("查看来源") }
                }
            }
        }
        if (editing) {
            var key by remember(detail) { mutableStateOf("") }
            var confirmRemove by remember(detail) { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { if (!busy) editing = false },
                title = { Text(if (confirmRemove) "移除密钥？" else "连接 ${detail.title}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (confirmRemove) {
                            Text(if (detail.credentialOwner == SearchPlugin.BAIDU)
                                "所有使用百度千帆的插件都将断开连接。" else "此插件将断开连接。")
                        } else {
                            OutlinedTextField(key, { key = it.take(512) }, label = { Text(detail.keyLabel) },
                                singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !busy)
                            if (status.hasKey) TextButton({ confirmRemove = true }, enabled = !busy) { Text("移除密钥") }
                        }
                        if (failure) Text(message, color = MaterialTheme.colorScheme.error)
                    }
                },
                confirmButton = {
                    if (confirmRemove) TextButton({ save(detail, false, clear = true) }, enabled = !busy) { Text("移除") }
                    else TextButton({ save(detail, true, key) }, enabled = key.isNotBlank() && !busy) { Text("保存并连接") }
                },
                dismissButton = { TextButton({ if (confirmRemove) confirmRemove = false else editing = false },
                    enabled = !busy) { Text("取消") } }
            )
        }
    }
}

@Composable
private fun CatalogCard(plugin: SearchPlugin, status: SearchPluginState, open: () -> Unit) {
    OutlinedCard(onClick = open, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PluginBadge(plugin, 44)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(plugin.title, style = MaterialTheme.typography.titleMedium)
                Text(plugin.help, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(if (status.enabled) "已连接" else "查看",
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun PluginBadge(plugin: SearchPlugin, size: Int) {
    Image(
        painter = painterResource(plugin.icon),
        contentDescription = null,
        modifier = Modifier.size(size.dp).clip(RoundedCornerShape(13.dp))
    )
}

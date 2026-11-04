package com.example.waferai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.waferai.ui.theme.LocalFloatingNavigationPadding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.waferai.data.ai.BuiltInKnowledge
import com.example.waferai.data.ai.WaferKnowledge
import com.example.waferai.data.local.OperationLog
import com.example.waferai.ui.theme.GlassButton
import com.example.waferai.ui.theme.LocalEnglish
import com.example.waferai.ui.theme.StudioPanel
import com.example.waferai.ui.theme.StudioHeader
import com.example.waferai.ui.theme.StudioIcon
import com.example.waferai.ui.theme.BackButton
import com.example.waferai.ui.theme.UiText as Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun OperationLogScreen(log: OperationLog, onBack: () -> Unit, modifier: Modifier) {
    val entries by log.entries.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirm by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp,
            bottom = 20.dp + LocalFloatingNavigationPadding.current),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            BackButton(onBack)
            Text("操作日志", style = MaterialTheme.typography.headlineLarge)
            Text("本机保留最近 150 条操作及结果。")
            TextButton({ confirm = true }, enabled = entries.isNotEmpty()) { Text("清空日志") }
            if (error) Text("无法清空日志，请重试。", color = MaterialTheme.colorScheme.error)
            if (entries.isEmpty()) Text("暂无操作记录。")
        }
        items(entries) { entry ->
            StudioPanel(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(entry.operation.label, style = MaterialTheme.typography.titleSmall)
                    Text(if (entry.success) "成功" else "失败",
                        color = if (entry.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
                Text(showTime(Instant.parse(entry.time)), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("清空操作日志？") },
        confirmButton = {
            TextButton({
                confirm = false
                scope.launch {
                    error = try {
                        withContext(Dispatchers.IO) { log.clear() }
                        false
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { true }
                }
            }) { Text("确认") }
        },
        dismissButton = { TextButton({ confirm = false }) { Text("取消") } }
    )
}

@Composable
internal fun KnowledgeScreen(
    knowledge: WaferKnowledge, auto: Boolean, onAuto: (Boolean) -> Unit,
    interval: Int, onInterval: (Int) -> Unit, onPlugins: () -> Unit, onBack: () -> Unit, modifier: Modifier
) {
    val state by knowledge.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val uris = LocalUriHandler.current
    val english = LocalEnglish.current
    var query by rememberSaveable { mutableStateOf("") }
    var busy by androidx.compose.runtime.remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    val entries = if (query.isBlank()) BuiltInKnowledge + state.online else knowledge.search(query)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp,
            bottom = 20.dp + LocalFloatingNavigationPadding.current),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            BackButton(onBack)
            StudioHeader("知识库", "WAFERAI / LIBRARY", StudioIcon.BOOK)
        }
        item {
            StudioPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("自动搜寻文献", style = MaterialTheme.typography.titleMedium)
                        Text("联网后，已启用的插件会定期搜索公开资料。自动搜索只发送预设晶圆关键词。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    val switchDescription = if (english) "Automatically find papers" else "自动搜寻文献"
                    Switch(auto, onAuto, Modifier.semantics { contentDescription = switchDescription })
                }
                Text("自动搜寻间隔", style = MaterialTheme.typography.titleSmall)
                KnowledgeIntervalPicker(interval, onInterval)
                TextButton(onPlugins) { Text("管理搜索插件") }
                Text("内置 ${BuiltInKnowledge.size} 条 · 已缓存 ${state.online.size} 条")
                if (state.syncedAt > 0L) Text("上次同步 ${showTime(Instant.ofEpochMilli(state.syncedAt))}")
                GlassButton({
                    busy = true
                    message = null
                    scope.launch {
                        try {
                            val result = knowledge.refresh(force = true, manualQuery = query)
                            message = "已更新知识库，新增 ${result.added} 条。" +
                                if (result.failures.isEmpty()) "" else "\n${result.failures.joinToString("\n")}"
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            message = error.message ?: "同步失败，已保存条目仍可离线查阅。"
                        } finally {
                            busy = false
                        }
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("立即搜寻") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        item {
            OutlinedTextField(query, { query = it.take(120) }, Modifier.fillMaxWidth(),
                label = { Text("搜索知识或输入联网检索词") }, singleLine = true)
            if (entries.isEmpty()) Text("没有找到相关知识。")
        }
        items(entries, key = { it.source }) { entry ->
            StudioPanel(Modifier.fillMaxWidth()) {
                androidx.compose.material3.Text(entry.title(english), style = MaterialTheme.typography.titleMedium)
                if (entry.body(english).isNotBlank()) {
                    androidx.compose.material3.Text(entry.body(english), style = MaterialTheme.typography.bodyMedium)
                }
                TextButton({
                    runCatching { uris.openUri(entry.source) }
                        .onFailure { message = "无法打开来源链接。" }
                }) { Text("查看来源") }
            }
        }
    }
}

private fun showTime(time: Instant) =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(time)

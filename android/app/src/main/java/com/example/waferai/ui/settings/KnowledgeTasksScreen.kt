package com.example.waferai.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.waferai.data.ai.KnowledgeSyncJob
import com.example.waferai.data.ai.KnowledgeTaskStore
import com.example.waferai.data.ai.AiProfile
import com.example.waferai.data.ai.AiProfileStore
import com.example.waferai.data.ai.AiProfilesState
import com.example.waferai.ui.theme.BackButton
import com.example.waferai.ui.theme.KnowledgeIntervals
import com.example.waferai.ui.theme.LocalFloatingNavigationPadding
import com.example.waferai.ui.theme.StudioHeader
import com.example.waferai.ui.theme.StudioIcon
import com.example.waferai.ui.theme.StudioPanel
import com.example.waferai.ui.theme.UiText as Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun KnowledgeIntervalPicker(hours: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }) { Text("每 $hours 小时") }
        DropdownMenu(open, { open = false }) {
            KnowledgeIntervals.forEach { interval ->
                DropdownMenuItem(text = { Text("每 $interval 小时") }, onClick = {
                    open = false
                    onChange(interval)
                })
            }
        }
    }
}

@Composable
internal fun AiTasksScreen(
    profiles: AiProfilesState, profileStore: AiProfileStore, selectedProfileId: String?,
    store: KnowledgeTaskStore, onBack: () -> Unit, modifier: Modifier
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val tasks by store.tasks.collectAsStateWithLifecycle()
    val running by store.running.collectAsStateWithLifecycle()
    var create by rememberSaveable { mutableStateOf(false) }
    var removeId by rememberSaveable { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }

    fun act(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = ""
        scope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.message ?: "任务操作失败，请重试。" }
            finally { busy = false }
        }
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp,
        bottom = 20.dp + LocalFloatingNavigationPadding.current), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            BackButton(onBack)
            StudioHeader("AI 定时任务", "WAFERAI / TASKS", StudioIcon.AI)
        }
        item {
            StudioPanel(Modifier.fillMaxWidth()) {
                Text("按设定间隔，把任务指令发给指定 AI 模型，并在这里查看结果。")
                Text("在线任务可能产生 API 费用。Android 会根据网络和电量安排实际执行时间。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button({ create = true }, enabled = !busy && tasks.size < 12 && profiles.profiles.isNotEmpty()) { Text("新建任务") }
                if (profiles.profiles.isEmpty()) Text("请先在 AI 对话里添加一个在线模型。")
                if (tasks.isEmpty()) Text("还没有定时任务。")
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
            }
        }
        items(tasks, key = { it.id }) { task ->
            val profile = profiles.profiles.find { it.id == task.profileId }
            val runnable = task.matches(profile)
            val active = task.id in running
            StudioPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(task.name, style = MaterialTheme.typography.titleMedium)
                        Text("每 ${task.hours} 小时", style = MaterialTheme.typography.labelMedium)
                        if (runnable && profile != null) Text(profile.selectionName, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(task.enabled && runnable, { enabled ->
                        act {
                            val updated = store.toggle(task.id, enabled) ?: return@act
                            try {
                                KnowledgeSyncJob.configureTask(context, updated)
                            } catch (error: Exception) {
                                store.toggle(task.id, task.enabled)
                                throw error
                            }
                        }
                    }, enabled = !busy && !active && runnable)
                }
                Text(task.query)
                if (!runnable) Text(if (task.profileId == null) "旧知识搜索任务已停用，可新建 AI 任务。"
                    else "绑定的模型已移除或接口已变更，请重新创建任务。")
                else if (active) Text("AI 正在执行…")
                else if (task.lastRun == 0L) Text("尚未执行")
                if (task.lastRun > 0L && task.profileId != null) {
                    val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(task.lastRun))
                    Text(time, style = MaterialTheme.typography.bodySmall)
                    Text(task.lastError ?: "AI 已完成任务。")
                }
                task.reply?.let { reply ->
                    Text(if (task.lastFailed) "最近成功结果" else "最新结果", style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(reply) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton({ act { store.execute(task.id, profileStore) } }, enabled = !busy && !active && runnable) { Text("立即运行") }
                    TextButton({ removeId = task.id }, enabled = !busy && !active) { Text("删除") }
                }
            }
        }
    }
    if (create) {
        var name by rememberSaveable { mutableStateOf("") }
        var query by rememberSaveable { mutableStateOf("") }
        var hours by rememberSaveable { mutableIntStateOf(24) }
        var profileId by rememberSaveable { mutableStateOf(selectedProfileId ?: profiles.activeId) }
        val profile = profiles.profiles.find { it.id == profileId } ?: profiles.active
        AlertDialog(
            onDismissRequest = { create = false },
            title = { Text("新建任务") },
            text = {
                Column(Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(48) }, singleLine = true, label = { Text("任务名称") })
                    OutlinedTextField(query, { query = it.take(2000) }, minLines = 2, maxLines = 4, label = { Text("交给 AI 的指令") })
                    TaskModelPicker(profiles.profiles, profile) { profileId = it }
                    KnowledgeIntervalPicker(hours) { hours = it }
                }
            },
            confirmButton = {
                TextButton({
                    val chosen = profile ?: return@TextButton
                    create = false
                    act {
                        val task = store.add(name, query, hours, chosen)
                        try {
                            KnowledgeSyncJob.configureTask(context, task)
                        } catch (error: Exception) {
                            store.remove(task.id)
                            throw error
                        }
                    }
                }, enabled = name.isNotBlank() && query.isNotBlank() && profile != null) { Text("保存") }
            },
            dismissButton = { TextButton({ create = false }) { Text("取消") } }
        )
    }
    removeId?.let { id ->
        AlertDialog(onDismissRequest = { removeId = null }, title = { Text("删除这个任务？") },
            confirmButton = {
                TextButton({
                    removeId = null
                    act {
                        store.remove(id)
                        KnowledgeSyncJob.cancelTask(context, id)
                    }
                }) { Text("删除") }
            }, dismissButton = { TextButton({ removeId = null }) { Text("取消") } })
    }
}

@Composable
private fun TaskModelPicker(profiles: List<AiProfile>, selected: AiProfile?, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }) { Text(selected?.selectionName ?: "选择执行模型", maxLines = 1) }
        DropdownMenu(open, { open = false }) {
            profiles.forEach { profile ->
                DropdownMenuItem(text = { Text(profile.selectionName) }, onClick = { onSelect(profile.id); open = false })
            }
        }
    }
}

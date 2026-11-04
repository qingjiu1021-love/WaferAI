package com.example.waferai.ui.chat

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.widthIn
import com.example.waferai.ui.theme.LocalAppLanguage
import com.example.waferai.ui.theme.localizedCore
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Surface
import com.example.waferai.ui.theme.BackButton
import com.example.waferai.ui.theme.UiText as Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.delay
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import com.example.waferai.data.ai.AiProfilesState
import com.example.waferai.data.ai.ChatThinking
import com.example.waferai.data.ai.LocalWaferAssistant
import com.example.waferai.data.ai.readableReply
import com.example.waferai.data.ai.thinkingOptions
import com.example.waferai.data.remote.WaferDetail
import com.example.waferai.data.remote.WaferPrediction
import com.example.waferai.ui.theme.LocalEnglish
import com.example.waferai.ui.theme.StudioAction
import com.example.waferai.ui.theme.StudioBadge
import com.example.waferai.ui.theme.StudioGlyph
import com.example.waferai.ui.theme.StudioHeader
import com.example.waferai.ui.theme.StudioIcon
import com.example.waferai.ui.theme.StudioPanel
import java.io.File

@Composable
internal fun ChatScreen(
    state: ChatUiState,
    viewModel: ChatViewModel,
    profiles: AiProfilesState,
    selectedProfileId: String?,
    profileBusy: Boolean,
    wafer: WaferDetail?,
    prediction: WaferPrediction?,
    onSelectModel: (String?) -> Unit,
    onAddModel: () -> Unit,
    onManageModels: () -> Unit,
    onEditModel: (String) -> Unit,
    onOpenTasks: () -> Unit,
    onOpenPlugins: () -> Unit,
    thinking: ChatThinking,
    onThinking: (ChatThinking) -> Unit,
    memoryEnabled: Boolean,
    onMemoryEnabled: (Boolean) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    var attach by rememberSaveable(wafer?.id, state.archive.activeId) { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var modelMenu by rememberSaveable { mutableStateOf(false) }
    var attachMenu by rememberSaveable { mutableStateOf(false) }
    var fileActions by rememberSaveable { mutableStateOf(false) }
    var thinkingDialog by rememberSaveable { mutableStateOf(false) }
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    var textDialog by rememberSaveable { mutableStateOf(false) }
    var memoryDialog by rememberSaveable { mutableStateOf(false) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.attach(it, photo = true) }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.attach(it, photo = false) }
    }
    val context = LocalContext.current
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        pendingCapture?.let { address ->
            val uri = Uri.parse(address)
            if (saved) viewModel.attach(uri, photo = true, capture = true) else viewModel.discardCapture(uri)
        }
        pendingCapture = null
    }
    val model = profiles.profiles.firstOrNull { it.id == selectedProfileId }
    val thinkingOptions = model?.thinkingOptions().orEmpty()
    val activeThinking = thinking.takeIf { it in thinkingOptions } ?: ChatThinking.DEFAULT
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val english = LocalEnglish.current
    val language = LocalAppLanguage.current.label
    val list = rememberLazyListState()
    val focus = LocalFocusManager.current
    val scheme = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    var sourceError by remember { mutableStateOf<String?>(null) }
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(state.startedAt, state.pending) {
        if (state.pending != null) while (true) {
            elapsed = ((System.currentTimeMillis() - state.startedAt) / 1000).coerceAtLeast(0)
            delay(1000)
        }
    }
    val showIntro = state.turns.isEmpty() && state.pending == null
    val disclosure = if (model == null) "基础助手提供本机规则问答与知识检索。自由对话请在模型选择中添加在线 AI。"
        else "发送时，消息与附件将交给当前 AI 服务处理，可能产生 API 费用。"
    val historyNote = if (!memoryEnabled) "记忆已关闭。" else "记忆已开启。本机保留最近 30 轮，可在右上角编辑记忆。"
    LaunchedEffect(state.archive.activeId, state.turns.lastOrNull(), state.pending) {
        if (state.pending != null) list.scrollToItem(state.turns.size)
        else list.scrollToItem(state.turns.lastIndex.coerceAtLeast(0))
    }
    val historyLabel = localizedCore("对话历史", LocalAppLanguage.current)
        ?: if (english) "Chat history" else "对话历史"
    BackHandler(attachMenu) { attachMenu = false }
    ModalNavigationDrawer(
        modifier = modifier.fillMaxSize().imePadding(),
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ChatHistoryDrawer(
                drawer = drawer,
                archive = state.archive,
                busy = state.busy,
                onClose = { drawerScope.launch { drawer.close() } },
                onOpen = { id ->
                    viewModel.openChat(id)
                    drawerScope.launch { drawer.close() }
                },
                onNew = {
                    viewModel.newChat()
                    drawerScope.launch { drawer.close() }
                },
                onDelete = { deleteId = it },
                onTasks = {
                    drawerScope.launch {
                        drawer.close()
                        onOpenTasks()
                    }
                }
            )
        }
    ) {
        Column(
            Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            onBack?.let { BackButton(it) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton({
                    focus.clearFocus()
                    drawerScope.launch { drawer.open() }
                }, modifier = Modifier.semantics { contentDescription = historyLabel }) {
                    StudioGlyph(StudioIcon.MENU, Modifier.size(22.dp), LocalContentColor.current)
                }
                StudioHeader("AI 对话", "WAFERAI / ASSISTANT", StudioIcon.AI, Modifier.weight(1f))
                IconButton({ memoryDialog = true }, enabled = !state.busy,
                    modifier = Modifier.semantics { contentDescription = "AI 记忆" }) {
                    StudioGlyph(StudioIcon.MEMORY, Modifier.size(22.dp), if (memoryEnabled) scheme.primary else scheme.onSurfaceVariant)
                }
            }
            if (!showIntro) {
                Text(if (model == null) "离线 · 基础问答" else disclosure,
                    modifier = Modifier.padding(horizontal = 4.dp), color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (showIntro) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StudioPanel(Modifier.fillMaxWidth(), color = scheme.primaryContainer) {
                            Text(if (model == null) "离线 · 基础问答" else model.selectionName,
                                style = MaterialTheme.typography.titleSmall, color = scheme.primary)
                            Text(disclosure, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            Text(historyNote, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.turns.isEmpty()) {
                            Text("快捷提问", style = MaterialTheme.typography.titleMedium)
                            (if (model != null) listOf(
                                "自由聊一聊" to if (english) "Let's have a conversation. What can you help me with?" else "我们随便聊聊吧，你能帮我做些什么？",
                                "写作与学习" to if (english) "Help me plan a week of study, and ask about my goals first." else "帮我安排一周的学习计划，先了解一下我的学习目标。",
                                "编程与创意" to if (english) "Help me brainstorm a practical app idea, and ask about my needs first." else "帮我构思一个实用的小工具，先问问我的需求。"
                            ) else listOf(
                                "怎么看晶圆图？" to if (english) "How do I read a wafer map?" else "怎样读懂晶圆图和芯片良率？",
                                "区分边缘缺陷" to if (english) "How do I distinguish edge-ring and edge-local defects?" else "怎样区分边缘环形缺陷和边缘局部缺陷？",
                                "排查思路" to if (english) "How should I troubleshoot low yield?" else "看到一张低良率晶圆图时，应该如何安排排查顺序？"
                            )).forEach { (label, prompt) ->
                                StudioAction(label, "", StudioIcon.AI, { viewModel.edit(prompt) }, enabled = !state.busy)
                            }
                        }
                    }
                }
                items(state.turns) { turn ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChatBubble(if (turn.waferSummary == null) "我" else "我 · 已附晶圆摘要",
                            turn.question + turn.attachmentName?.let { "\n附件：$it" }.orEmpty(), user = true)
                        ChatBubble(turn.model, turn.reply, user = false)
                        turn.searchNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                        if (!turn.sources.isNullOrEmpty()) StudioPanel(Modifier.fillMaxWidth()) {
                            Text("联网来源 · 网页摘要", style = MaterialTheme.typography.labelLarge)
                            turn.sources.orEmpty().forEachIndexed { index, source ->
                                TextButton({ runCatching { uriHandler.openUri(source.url) }.onFailure { sourceError = "无法打开来源链接。" } }) {
                                    Text("[${index + 1}] ${source.title}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                state.pending?.let { question ->
                    item { ChatBubble("我 · 正在发送", question, user = true) }
                }
            }
            state.error?.let {
                Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall)
                if (model != null) {
                    TextButton({ onEditModel(model.id) }, enabled = !profileBusy && !state.busy) { Text("检查当前模型") }
                }
            }
            sourceError?.let { Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall) }
            if (state.attachment != null || (attach && wafer != null)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val label = listOfNotNull(state.attachment?.name,
                        "已附当前晶圆摘要".takeIf { attach && wafer != null }).joinToString(" · ")
                    Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                    TextButton({
                        if (state.attachment != null) viewModel.removeAttachment() else attach = false
                    }, enabled = !state.busy) { Text("移除") }
                }
            }
            if (state.busy) {
                state.stage?.let { Text("$it · 已用 ${elapsed} 秒", style = MaterialTheme.typography.labelMedium, color = scheme.primary) }
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.stage == "等待模型思考与回复") Text("当前接口未提供思考百分比，显示实际等待时间。", style = MaterialTheme.typography.bodySmall)
                state.searchNotice?.let { Text(it, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                if (state.pending != null && !state.writing) TextButton(viewModel::cancel) { Text("停止等待") }
            }
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = scheme.surfaceContainer,
                shadowElevation = 2.dp, border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.35f))) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    if (state.webSearch) Text("发送后将用本次问题检索网页；附件与记忆不会交给搜索服务。", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall)
                    TextField(state.draft, viewModel::edit, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("输入问题") }, maxLines = 4, enabled = !state.busy,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent
                        ))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box {
                            IconButton({ attachMenu = true }, enabled = !state.busy,
                                modifier = Modifier.semantics { contentDescription = "扩展功能" }) {
                                StudioGlyph(StudioIcon.PLUS, Modifier.size(21.dp), scheme.primary)
                            }
                            ChatActionsMenu(
                                attachMenu && !state.busy, activeThinking, { attachMenu = false },
                                onCamera = {
                                    attachMenu = false
                                    runCatching {
                                        val directory = File(context.cacheDir, "chat-capture")
                                        directory.mkdirs()
                                        val file = File.createTempFile("capture-", ".jpg", directory)
                                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.chat-capture", file)
                                        pendingCapture = uri.toString()
                                        camera.launch(uri)
                                    }.onFailure {
                                        pendingCapture?.let { viewModel.discardCapture(Uri.parse(it)) }
                                        pendingCapture = null
                                        viewModel.cameraUnavailable()
                                    }
                                },
                                onPhoto = {
                                    attachMenu = false
                                    photoPicker.launch(arrayOf("image/*"))
                                },
                                onFiles = { attachMenu = false; fileActions = true },
                                onPlugins = { attachMenu = false; focus.clearFocus(); onOpenPlugins() },
                                onThinking = { attachMenu = false; focus.clearFocus(); thinkingDialog = true }
                            )
                        }
                        IconButton(viewModel::toggleWebSearch, enabled = !state.busy,
                            modifier = Modifier.semantics {
                                contentDescription = if (state.webSearch) "联网搜索已开启" else "联网搜索"
                                stateDescription = if (state.webSearch) "已开启" else "已关闭"
                            }.clip(RoundedCornerShape(50)).background(
                                if (state.webSearch) scheme.primary.copy(alpha = 0.12f) else Color.Transparent)) {
                            StudioGlyph(StudioIcon.LANGUAGE, Modifier.size(21.dp),
                                if (state.webSearch) scheme.primary else scheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.widthIn(max = 136.dp)) {
                            TextButton({ modelMenu = true }, enabled = !state.busy && !profileBusy) {
                                Text(model?.selectionName ?: LocalWaferAssistant.Name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium)
                                Text("⌄", modifier = Modifier.padding(start = 4.dp))
                            }
                            DropdownMenu(modelMenu && !state.busy && !profileBusy, { modelMenu = false }) {
                                DropdownMenuItem(text = {
                                    Column {
                                        Text(LocalWaferAssistant.Name)
                                        Text("离线 · 基础问答与知识检索", style = MaterialTheme.typography.bodySmall)
                                    }
                                }, trailingIcon = { if (model == null) Text("✓") }, onClick = {
                                    modelMenu = false
                                    onSelectModel(null)
                                })
                                profiles.profiles.forEach { profile ->
                                    DropdownMenuItem(text = {
                                        Column {
                                            Text(profile.selectionName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(profile.selectionDetail, style = MaterialTheme.typography.bodySmall,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }, trailingIcon = { if (profile.id == model?.id) Text("✓") }, onClick = {
                                        modelMenu = false
                                        onSelectModel(profile.id)
                                    })
                                }
                                DropdownMenuItem(text = { Text("添加模型") }, onClick = { modelMenu = false; onAddModel() })
                                if (profiles.profiles.isNotEmpty()) {
                                    DropdownMenuItem(text = { Text("管理模型") }, onClick = { modelMenu = false; onManageModels() })
                                }
                            }
                        }
                        FilledIconButton({
                            focus.clearFocus()
                            viewModel.send(wafer?.takeIf { attach }?.let { LocalWaferAssistant.summarize(it, prediction, english) },
                                model?.id, english, memoryEnabled, language, activeThinking)
                        }, modifier = Modifier.semantics { contentDescription = "发送" },
                            enabled = !state.busy && (model == null || !profileBusy) && (state.draft.isNotBlank() || state.attachment != null)) {
                            StudioGlyph(StudioIcon.SEND, Modifier.size(20.dp), LocalContentColor.current)
                        }
                    }
                }
            }
        }
    }
    deleteId?.let { id ->
        AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("删除这条对话？") },
            confirmButton = { TextButton({ viewModel.deleteChat(id); deleteId = null }) { Text("删除") } },
            dismissButton = { TextButton({ deleteId = null }) { Text("取消") } })
    }
    if (fileActions) {
        AlertDialog(
            onDismissRequest = { fileActions = false },
            title = { Text("文件") },
            text = {
                Column {
                    DropdownMenuItem(text = { Text("选择文件") }, onClick = {
                        fileActions = false
                        filePicker.launch(arrayOf("text/*", "application/json", "application/octet-stream"))
                    })
                    DropdownMenuItem(text = { Text("添加文本") }, onClick = { fileActions = false; textDialog = true })
                    if (wafer != null) DropdownMenuItem(
                        text = { Text(if (attach) "取消附带晶圆摘要" else "附带当前晶圆摘要") },
                        onClick = { attach = !attach; fileActions = false })
                }
            },
            confirmButton = { TextButton({ fileActions = false }) { Text("取消") } }
        )
    }
    if (thinkingDialog) {
        ChatThinkingDialog(thinkingOptions, activeThinking, { thinkingDialog = false }) {
            onThinking(it)
            thinkingDialog = false
        }
    }
    if (textDialog) {
        var text by androidx.compose.runtime.remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { textDialog = false },
            title = { Text("添加文本") },
            text = {
                OutlinedTextField(text, { text = it.take(8000) }, label = { Text("输入或粘贴文本") }, maxLines = 6)
            },
            confirmButton = { TextButton({ viewModel.attachText(text); textDialog = false }, enabled = text.isNotBlank()) { Text("添加") } },
            dismissButton = { TextButton({ textDialog = false }) { Text("取消") } })
    }
    if (memoryDialog) {
        var notes by androidx.compose.runtime.remember { mutableStateOf(state.memory) }
        AlertDialog(onDismissRequest = { memoryDialog = false },
            title = { Text("AI 记忆") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("使用记忆与近期对话", Modifier.weight(1f))
                        Switch(memoryEnabled, onMemoryEnabled)
                    }
                    Text("手动保存偏好或项目背景。开启时，在线模型会收到这些备注和近期对话。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(notes, { notes = it.take(1800) }, label = { Text("记忆备注") }, maxLines = 5)
                    if (state.turns.isNotEmpty()) TextButton({ memoryDialog = false; confirmClear = true }) { Text("清空对话") }
                }
            },
            confirmButton = { TextButton({ viewModel.saveMemory(notes); memoryDialog = false }) { Text("保存记忆") } },
            dismissButton = { TextButton({ memoryDialog = false }) { Text("关闭") } })
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空本机对话？") },
            text = { Text("这会清空当前对话。记忆备注可在 AI 记忆中单独编辑或删除。") },
            confirmButton = { TextButton({ confirmClear = false; viewModel.clear() }) { Text("确认清空") } },
            dismissButton = { TextButton({ confirmClear = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun ChatBubble(title: String, body: String, user: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Card(
            modifier = Modifier.fillMaxWidth(if (user) 0.88f else 1f),
            shape = RoundedCornerShape(20.dp),
            border = if (user) null else BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.18f)),
            colors = CardDefaults.cardColors(containerColor = if (user) scheme.primary else scheme.surfaceContainer,
                contentColor = if (user) scheme.onPrimary else scheme.onSurface)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!user) StudioBadge(StudioIcon.AI, 24)
                    Text(title, style = MaterialTheme.typography.labelLarge, color = if (user) scheme.onPrimary.copy(alpha = 0.8f) else scheme.primary)
                }
                val display = remember(body, user) { if (user) body else readableReply(body) }
                SelectionContainer { androidx.compose.material3.Text(display, color = if (user) scheme.onPrimary else scheme.onSurface) }
            }
        }
    }
}

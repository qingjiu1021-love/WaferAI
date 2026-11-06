package com.example.waferai.ui.settings

import com.example.waferai.ui.theme.GlassSegmentedTabs
import com.example.waferai.ui.theme.GlassTextButton as TextButton
import com.example.waferai.ui.theme.GlassOutlinedButton as OutlinedButton
import com.example.waferai.ui.theme.GlassDropdownMenu as DropdownMenu
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.waferai.data.ai.AiModelEntry
import com.example.waferai.data.ai.AiProtocol
import com.example.waferai.data.ai.AiProvider
import com.example.waferai.data.ai.MaxProviderModels
import com.example.waferai.ui.theme.BackButton
import com.example.waferai.ui.theme.GlassButton
import com.example.waferai.ui.theme.StudioHeader
import com.example.waferai.ui.theme.StudioIcon
import com.example.waferai.ui.theme.StudioPanel
import com.example.waferai.ui.theme.UiText as Text

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiProviderEditor(
    state: AiCenterState, draft: AiDraft, viewModel: AiCenterViewModel, enabled: Boolean, modifier: Modifier
) {
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    val uris = LocalUriHandler.current
    val scheme = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    var protocolMenu by remember { mutableStateOf(false) }
    var providerMenu by rememberSaveable { mutableStateOf(false) }
    var customSettings by rememberSaveable(draft.provider) { mutableStateOf(false) }
    var manualModel by rememberSaveable { mutableStateOf<String?>(null) }
    var manualName by rememberSaveable { mutableStateOf("") }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var configHelp by rememberSaveable { mutableStateOf(false) }
    val openConfig = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importConfig(context.contentResolver, it) }
    }
    val canConnect = enabled && (draft.key.isNotBlank() || draft.id != null) &&
        (!draft.custom || draft.address.isNotBlank())
    val canSave = canConnect && draft.selectedModel.isNotBlank() &&
        (!draft.custom || draft.providerId.isNotBlank())

    Column(modifier.fillMaxSize().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                BackButton(viewModel::close, "取消", enabled)
                if (draft.id == null) OutlinedButton({
                    focus.clearFocus()
                    openConfig.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                }, enabled = enabled) { Text("打开配置文件") }
            }
            StudioHeader(if (draft.id == null) "添加 AI 模型" else "编辑模型提供商", "WAFERAI / MODELS", StudioIcon.AI)
            if (draft.id == null) {
                GlassSegmentedTabs(
                    listOf("第三方模型提供商", "自定义模型 API"), if (draft.custom) 1 else 0,
                    onSelect = { index ->
                        if (index == 0 && draft.custom) viewModel.edit(AiDraft())
                        if (index == 1 && !draft.custom) viewModel.edit(AiDraft(provider = AiProvider.CUSTOM))
                    },
                    enabled = enabled
                )
            }
            if (draft.custom) {
                Text("连接中转站、自部署服务或兼容 OpenAI、Anthropic 协议的接口。",
                    color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("从目录选择提供商，填写该平台的开发者 API 密钥。", color = scheme.onSurfaceVariant)
            }
            StudioPanel(Modifier.fillMaxWidth()) {
                if (draft.custom) {
                    OutlinedTextField(draft.providerId, { viewModel.edit(draft.copy(providerId = it.take(40))) },
                        Modifier.fillMaxWidth(), label = { Text("Provider ID") },
                        placeholder = { Text("acme-gateway") },
                        supportingText = { Text("以小写字母开头，用于唯一标识这个提供商。") },
                        singleLine = true, enabled = enabled)
                } else {
                    Text("提供商", style = MaterialTheme.typography.titleSmall)
                    OutlinedButton({ focus.clearFocus(); providerMenu = true }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(draft.provider.title, Modifier.weight(1f))
                        Text("⌄")
                    }
                }
                OutlinedTextField(draft.name, { viewModel.edit(draft.copy(name = it.take(60))) }, Modifier.fillMaxWidth(),
                    label = { Text("提供商名称（可选）") },
                    singleLine = true, enabled = enabled)
                if (draft.custom) {
                    OutlinedTextField(draft.address, { viewModel.edit(draft.copy(address = it.take(1024))) },
                        Modifier.fillMaxWidth(), label = { Text("API 地址") },
                        placeholder = { Text("https://gateway.example/v1") }, singleLine = true, enabled = enabled,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Text("API 协议", style = MaterialTheme.typography.titleSmall)
                    Box {
                        OutlinedButton({ protocolMenu = true }, enabled = enabled) { Text("${draft.protocol.title} ▾") }
                        DropdownMenu(protocolMenu, onDismissRequest = { protocolMenu = false }) {
                            AiProtocol.entries.forEach { protocol ->
                                DropdownMenuItem(text = { Text(protocol.title) }, onClick = {
                                    protocolMenu = false
                                    viewModel.edit(draft.copy(protocol = protocol))
                                })
                            }
                        }
                    }
                }
                OutlinedTextField(draft.key, { viewModel.edit(draft.copy(key = it.take(1100))) }, Modifier.fillMaxWidth(),
                    label = { Text("API 密钥") }, singleLine = true, enabled = enabled,
                    supportingText = if (draft.id == null) null else { { Text("同一地址和协议留空可保留密钥") } },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                if (!draft.custom) {
                    HorizontalDivider()
                    TextButton({ customSettings = !customSettings }, enabled = enabled) {
                        Text(if (customSettings) "⌄ 自定义设置" else "› 自定义设置")
                    }
                    if (customSettings) {
                        OutlinedTextField(draft.address, { viewModel.edit(draft.copy(address = it.take(1024))) },
                            Modifier.fillMaxWidth(), label = { Text("API 地址") },
                            placeholder = { Text(draft.provider.baseUrl) }, singleLine = true, enabled = enabled,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                        Text(draft.protocol.title, style = MaterialTheme.typography.bodySmall)
                    }
                    if (draft.provider == AiProvider.BEDROCK) {
                        Text("请填写 Bedrock API 密钥，并使用模型所在的 AWS 区域和模型 ID。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (draft.provider == AiProvider.DEEPSEEK) {
                    TextButton({ runCatching { uris.openUri("https://platform.deepseek.com/api_keys") } }, enabled = enabled) {
                        Text("获取 DeepSeek API 密钥")
                    }
                }
            }
            StudioPanel(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("模型目录", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton({ focus.clearFocus(); viewModel.fetchModels() }, enabled = canConnect) { Text("获取可用模型") }
                }
                if (draft.models.isEmpty()) {
                    Text("目录为空。可从接口获取，或手动添加平台提供的模型 ID。",
                        color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                } else Text("每个模型可单独命名，留空时显示模型 ID。",
                    color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                draft.models.forEach { model ->
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        RadioButton(draft.selectedModel == model.id, { viewModel.edit(draft.copy(model = model.id)) },
                            enabled = enabled)
                        Column(Modifier.weight(1f).padding(top = 10.dp)) {
                            Text(model.label, style = MaterialTheme.typography.bodyMedium)
                            if (model.displayName.isNotBlank()) {
                                Text(model.id, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                            FlowRow {
                                TextButton({ renameId = model.id }, enabled = enabled) { Text("重命名") }
                                TextButton({ viewModel.edit(draft.copy(models = draft.models - model)) }, enabled = enabled) {
                                    Text("移除")
                                }
                            }
                        }
                    }
                }
                OutlinedButton({ manualModel = ""; manualName = "" }, enabled = enabled && draft.models.size < MaxProviderModels) {
                    Text("+ 添加模型")
                }
                if (draft.custom && draft.id == null) TextButton({ configHelp = true }) { Text("配置文件格式") }
            }
            Text("测试会发送一条简短消息，可能产生少量 API 费用。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton({ focus.clearFocus(); viewModel.testConnection() },
                enabled = canConnect && draft.selectedModel.isNotBlank(),
                modifier = Modifier.fillMaxWidth()) { Text("测试模型连接") }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.notice?.let { Text(it, color = scheme.primary, style = MaterialTheme.typography.bodySmall) }
            state.error?.let { Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(viewModel::close, enabled = enabled) { Text("取消") }
                GlassButton({ focus.clearFocus(); viewModel.save() }, enabled = canSave, prominent = true) {
                    Text(if (draft.id == null) "创建提供商" else "保存配置")
                }
            }
        }
    }
    manualModel?.let { id ->
        AlertDialog(onDismissRequest = { manualModel = null }, title = { Text("添加模型") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(id, { manualModel = it.take(160) }, label = { Text("模型 ID") }, singleLine = true)
                    OutlinedTextField(manualName, { manualName = it.take(60) }, label = { Text("模型名称（可选）") },
                        singleLine = true)
                }
            },
            confirmButton = { TextButton({
                viewModel.addModels(listOf(id), manualName)
                manualModel = null
            }, enabled = id.isNotBlank()) { Text("添加") } },
            dismissButton = { TextButton({ manualModel = null }) { Text("取消") } })
    }
    draft.models.find { it.id == renameId }?.let { model ->
        ModelNameDialog(model.id, model.displayName, { renameId = null }) { name ->
            viewModel.edit(draft.copy(models = draft.models.map {
                if (it.id == model.id) it.copy(displayName = name.trim()) else it
            }))
            renameId = null
        }
    }
    if (state.availableModels.isNotEmpty()) {
        ModelCatalogDialog(state.availableModels, draft.models.map(AiModelEntry::id),
            viewModel::dismissCatalog) { viewModel.addModels(it) }
    }
    if (providerMenu) {
        ProviderCatalogDialog({ providerMenu = false }) { provider ->
            providerMenu = false
            if (provider != draft.provider) {
                customSettings = false
                viewModel.edit(AiDraft(id = draft.id, provider = provider))
            }
        }
    }
    if (configHelp) {
        AlertDialog(onDismissRequest = { configHelp = false }, title = { Text("配置文件格式") },
            text = {
                Text("使用 JSON 文件。protocol 可填 openai、responses 或 anthropic，密钥在 App 内填写。\n\n" +
                    """{"providerId":"acme-gateway","displayName":"我的网关","baseUrl":"https://gateway.example/v1","protocol":"openai","models":[{"id":"model-a","displayName":"快速问答"},"model-b"]}""")
            }, confirmButton = { TextButton({ configHelp = false }) { Text("知道了") } })
    }
}

@Composable
internal fun ModelNameDialog(modelId: String, initialName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable(modelId, initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(modelId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it.filterNot(Char::isISOControl).take(60) },
                    label = { Text("模型名称") }, supportingText = { Text("留空时显示模型 ID") }, singleLine = true)
            }
        },
        confirmButton = { TextButton({ onSave(name) }) { Text("保存名称") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ModelCatalogDialog(
    models: List<String>, existing: List<String>,
    onDismiss: () -> Unit, onAdd: (List<String>) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val limit = MaxProviderModels - existing.size
    AlertDialog(onDismissRequest = onDismiss, title = { Text("可用模型") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("搜索模型 ID") }, singleLine = true)
            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(models.filter { it.contains(query.trim(), ignoreCase = true) }, key = { it }) { id ->
                    val checked = id in selected || id in existing
                    val canSelect = id !in existing && (checked || selected.size < limit)
                    val select = {
                        selected = if (checked) selected - id else selected + id
                    }
                    Row(Modifier.fillMaxWidth().clickable(enabled = canSelect, onClick = select)
                        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, null, Modifier.padding(end = 10.dp), enabled = canSelect)
                        Text(id, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }, confirmButton = {
        TextButton({ onAdd(selected.toList()) }, enabled = selected.isNotEmpty()) {
            Text("加入 ${selected.size} 个模型")
        }
    }, dismissButton = { TextButton(onDismiss) { Text("关闭") } })
}


@Composable
private fun ProviderCatalogDialog(onDismiss: () -> Unit, onSelect: (AiProvider) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val providers = AiProvider.entries.filter { provider ->
        provider != AiProvider.CUSTOM && (provider.title.contains(query.trim(), ignoreCase = true) ||
            provider.name.contains(query.trim(), ignoreCase = true))
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择提供商") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("搜索提供商") }, singleLine = true)
            if (providers.isEmpty()) Text("没有匹配的提供商。")
            LazyColumn(Modifier.heightIn(max = 310.dp)) {
                items(providers, key = { it.name }) { provider ->
                    ListItem(headlineContent = { Text(provider.title) },
                        supportingContent = { Text(provider.protocol.title) },
                        modifier = Modifier.clickable { onSelect(provider) })
                }
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("关闭") } })
}

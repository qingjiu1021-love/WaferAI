package com.example.waferai.ui.settings

import com.example.waferai.ui.theme.GlassTextButton as TextButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import com.example.waferai.ui.theme.BackButton
import com.example.waferai.ui.theme.UiText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.waferai.ui.theme.LocalFloatingNavigationPadding
import com.example.waferai.data.ai.AiProfile
import com.example.waferai.ui.theme.GlassButton

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiCenterScreen(
    state: AiCenterState,
    viewModel: AiCenterViewModel,
    allowChanges: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val draft = state.draft
    val enabled = allowChanges && !state.saving
    var remove by remember { mutableStateOf<AiProfile?>(null) }
    var rename by remember { mutableStateOf<AiProfile?>(null) }
    if (draft != null) {
        AiProviderEditor(state, draft, viewModel, enabled, modifier)
        return
    }
    Column(
        modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp).padding(bottom = LocalFloatingNavigationPadding.current),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        BackButton(onBack)
        Text("管理模型", style = MaterialTheme.typography.headlineLarge)
        Text("已添加的模型可在 AI 对话的发送键左侧选择。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("当前解析模型", style = MaterialTheme.typography.labelLarge)
                Text(state.library.active?.selectionName ?: "尚未添加 AI", style = MaterialTheme.typography.titleLarge)
                state.library.active?.let { Text(it.selectionDetail) }
            }
        }
        GlassButton({ viewModel.open() }, enabled = enabled, prominent = true, modifier = Modifier.fillMaxWidth()) {
            Text("添加 AI")
        }
        Text("已添加的 AI · ${state.library.profiles.size}", style = MaterialTheme.typography.titleMedium)
        if (state.library.profiles.isEmpty()) {
            Text("添加后可在这里管理配置，也可在晶圆页面切换。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.library.profiles.forEach { profile ->
            val selected = profile.id == state.library.activeId
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(profile.selectionName, style = MaterialTheme.typography.titleMedium)
                    Text(profile.selectionDetail, style = MaterialTheme.typography.bodyMedium)
                    if (selected) Text("正在使用", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!selected) TextButton({ viewModel.select(profile.id) }, enabled = enabled) { Text("设为当前") }
                        TextButton({ viewModel.open(profile) }, enabled = enabled) { Text("编辑") }
                        TextButton({ rename = profile }, enabled = enabled) { Text("重命名") }
                        TextButton({ remove = profile }, enabled = enabled) { Text("移除") }
                    }
                }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("在线解析会将晶圆统计和识别摘要发送到当前服务，可能产生 API 费用。密钥加密保存在本机。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    rename?.let { profile ->
        ModelNameDialog(profile.model, profile.modelDisplayName.orEmpty(), { rename = null }) {
            viewModel.renameModel(profile.id, it)
            rename = null
        }
    }
    remove?.let { profile ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text("移除 ${profile.selectionName}？") },
            text = { Text("该配置及其本机密钥会被移除。") },
            confirmButton = { TextButton({ viewModel.remove(profile.id); remove = null }, enabled = enabled) { Text("移除配置") } },
            dismissButton = { TextButton({ remove = null }) { Text("取消") } }
        )
    }
}

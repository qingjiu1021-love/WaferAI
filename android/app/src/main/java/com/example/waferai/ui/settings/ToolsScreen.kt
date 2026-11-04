package com.example.waferai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.waferai.ui.theme.LocalFloatingNavigationPadding
import com.example.waferai.BuildConfig
import com.example.waferai.data.analysis.WaferMapFilter
import com.example.waferai.ui.theme.GlassButton
import com.example.waferai.ui.theme.Appearance
import com.example.waferai.ui.theme.AppLanguage
import com.example.waferai.ui.theme.StartTab
import com.example.waferai.ui.theme.StudioAction
import com.example.waferai.ui.theme.StudioHeader
import com.example.waferai.ui.theme.StudioIcon
import com.example.waferai.ui.theme.BackButton
import androidx.compose.foundation.layout.Row
import com.example.waferai.ui.theme.ActionTile
import com.example.waferai.ui.theme.SectionHeading
import com.example.waferai.ui.theme.StudioPanel
import com.example.waferai.ui.theme.StatusPill
import com.example.waferai.ui.theme.UiText as Text

@Composable
internal fun ToolsScreen(onLogs: () -> Unit, onKnowledge: () -> Unit, onTrain: () -> Unit, onPlugins: () -> Unit,
                         modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp).padding(bottom = LocalFloatingNavigationPadding.current),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StudioHeader("工具箱", "WAFERAI / TOOLKIT", StudioIcon.TOOLS)
        StudioPanel(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.primaryContainer) {
            StatusPill("专业工作流")
            Text("让分析更进一步", style = MaterialTheme.typography.headlineSmall)
            Text("连接资料、积累知识、训练模型与追溯操作。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionHeading("知识与连接")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionTile("知识库", "本机检索与联网搜寻", StudioIcon.BOOK, onKnowledge, Modifier.weight(1f))
            ActionTile("插件", "连接国内外资料来源", StudioIcon.PLUG, onPlugins, Modifier.weight(1f))
        }
        SectionHeading("模型与追溯")
        StudioAction("手机模型训练", "自动训练并验证可用于检测的轻量模型。", StudioIcon.TRAINING, onTrain)
        StudioAction("操作日志", "查看导入、识别、导出与知识库同步记录。", StudioIcon.FILE, onLogs)
        Text("WaferAI ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun LanguageScreen(appearance: Appearance, onChange: (AppLanguage) -> Unit, onBack: () -> Unit, modifier: Modifier) {
    SettingsPage("语言选择", onBack, modifier) {
        Text("更改后立即生效。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("主要界面已本地化，部分专业说明保留中文或英文。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        AppLanguage.entries.forEach { language ->
            GlassButton({ onChange(language) }, Modifier.fillMaxWidth(), prominent = language == appearance.language) {
                Text(language.label)
            }
        }
    }
}

@Composable
internal fun GeneralScreen(
    appearance: Appearance, onChange: (Appearance) -> Unit, onBack: () -> Unit,
    onTheme: () -> Unit, modifier: Modifier
) {
    SettingsPage("常规设置", onBack, modifier) {
        SettingGroup("启动页面") {
            StartTab.entries.forEach { tab ->
                FilterChip(tab == appearance.startTab, { onChange(appearance.copy(startTab = tab)) }, { Text(tab.label) })
            }
        }
        SettingGroup("良率排查阈值") {
            listOf(80, 85, 90, 95, 99).forEach { value ->
                FilterChip(value == appearance.yieldThreshold, { onChange(appearance.copy(yieldThreshold = value)) }, { Text("$value%") })
            }
        }
        SettingGroup("晶圆图筛选") {
            WaferMapFilter.entries.forEach { filter ->
                FilterChip(filter == appearance.mapFilter, { onChange(appearance.copy(mapFilter = filter)) }, { Text(filter.label) })
            }
        }
        TextButton(onTheme) { Text("打开主题与恢复默认设置") }
    }
}

@Composable
internal fun BackupScreen(
    busy: Boolean, canRestore: Boolean, message: String?, error: Boolean, onExport: () -> Unit,
    onRestore: () -> Unit, onBack: () -> Unit, modifier: Modifier
) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    SettingsPage("备份与恢复", onBack.takeUnless { busy }, modifier) {
        Text("备份包括晶圆、批次、识别结果、检测记录和已保存的晶圆解析。")
        Text("聊天记录、AI 配置、密钥和设置留在本机。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        GlassButton(onExport, Modifier.fillMaxWidth(), enabled = !busy, prominent = true) { Text("导出备份") }
        GlassButton({ confirm = true }, Modifier.fillMaxWidth(), enabled = !busy && canRestore) { Text("恢复备份") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("恢复晶圆数据？") },
        text = { Text("所选备份将替换本机晶圆、批次和相关结果。请先导出需要保留的数据。") },
        confirmButton = { TextButton({ confirm = false; onRestore() }) { Text("选择备份文件") } },
        dismissButton = { TextButton({ confirm = false }) { Text("取消") } }
    )
}

@Composable
private fun SettingsPage(title: String, onBack: (() -> Unit)?, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
        .padding(bottom = LocalFloatingNavigationPadding.current),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        onBack?.let { BackButton(it) }
        Text(title, style = MaterialTheme.typography.headlineLarge)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingGroup(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }
}

package com.example.waferai.ui.settings

import com.example.waferai.ui.theme.GlassTextButton as TextButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.waferai.data.local.WaferOverview
import com.example.waferai.data.local.LocalUserProfile
import com.example.waferai.ui.theme.*
import com.example.waferai.ui.theme.UiText as Text

@Composable
internal fun ProfileScreen(overview: WaferOverview?, modelCount: Int,
                           onTheme: () -> Unit, onLanguage: () -> Unit, onGeneral: () -> Unit,
                           onBackup: () -> Unit, onModels: () -> Unit, onHistory: () -> Unit,
                           onAbout: () -> Unit,
                           profile: LocalUserProfile, onEditProfile: () -> Unit,
                           modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 12.dp).padding(bottom = LocalFloatingNavigationPadding.current),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StudioHeader("我的", "WAFERAI / MY WORKSPACE", StudioIcon.PERSON, compact = true)
        StudioPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                UserAvatar(profile)
                Column(Modifier.weight(1f)) {
                    Text(profile.name.ifBlank { "本机用户" }, style = MaterialTheme.typography.titleLarge)
                    Text("本机个人资料", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onEditProfile) { Text("编辑头像与昵称") }
        }
        SectionHeading("我的工作空间")
        StudioPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WorkspaceMetric("晶圆记录", overview?.wafers?.toString() ?: "—", Modifier.weight(1f))
                WorkspaceMetric("批次", overview?.lots?.toString() ?: "—", Modifier.weight(1f))
                WorkspaceMetric("在线模型", modelCount.toString(), Modifier.weight(1f))
            }
        }
        SectionHeading("个性化")
        StudioActionGroup(listOf(
            StudioLink("主题", "颜色、字体、透明底栏与动画", StudioIcon.THEME, onTheme),
            StudioLink("语言选择", "选择 App 的显示语言。", StudioIcon.LANGUAGE, onLanguage),
            StudioLink("常规设置", "启动页面、良率阈值与默认筛选", StudioIcon.TOOLS, onGeneral)
        ))
        SectionHeading("我的数据与 AI")
        StudioActionGroup(listOf(
            StudioLink("历史记录", "查找晶圆、对照与导出", StudioIcon.CLOCK, onHistory),
            StudioLink("备份与恢复", "导出或恢复本机晶圆数据。", StudioIcon.BACKUP, onBackup),
            StudioLink("AI 模型管理", "管理服务商、模型与连接配置", StudioIcon.AI, onModels),
            StudioLink("关于", "版本、帮助中心、使用条款与隐私政策", StudioIcon.BOOK, onAbout)
        ))
    }
}

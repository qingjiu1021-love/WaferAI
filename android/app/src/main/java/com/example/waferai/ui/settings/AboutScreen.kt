package com.example.waferai.ui.settings

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalUriHandler
import com.example.waferai.data.account.GithubUpdates
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.waferai.BuildConfig
import com.example.waferai.R
import com.example.waferai.ui.theme.*
import com.example.waferai.ui.theme.UiText as Text

@Composable
internal fun AboutScreen(onBack: () -> Unit, onHelp: () -> Unit, onLegal: (String) -> Unit, updates: UpdateViewModel, modifier: Modifier = Modifier) {
    val update by updates.state.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    var download by remember { mutableStateOf(false) }
    var browserError by remember { mutableStateOf<String?>(null) }
    fun open(url: String) { runCatching { uri.openUri(url) }.onFailure { browserError = "无法打开系统浏览器。" } }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
        .padding(bottom = LocalFloatingNavigationPadding.current), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BackButton(onBack)
        StudioHeader("关于", "WAFERAI / ABOUT", StudioIcon.PERSON)
        StudioPanel(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.primaryContainer) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Image(painterResource(R.drawable.wafer_launcher_art), null, Modifier.size(76.dp))
                Column {
                    Text("WaferAI", style = MaterialTheme.typography.headlineSmall)
                    Text("版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    Text("晶圆分析与 AI 助手", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        StudioAction("检查版本更新", "从 GitHub 获取最新正式版", StudioIcon.BACKUP, updates::check)
        if (update.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        update.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        browserError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        update.release?.let { release ->
            StudioPanel(Modifier.fillMaxWidth()) {
                Text(if (release.newer) "发现新版本 ${release.version}" else "当前已是最新版本", style = MaterialTheme.typography.titleMedium)
                Text("GitHub 最新正式版：${release.version}", style = MaterialTheme.typography.bodySmall)
                if (release.notes.isNotBlank()) Text(release.notes, style = MaterialTheme.typography.bodySmall)
                if (release.newer) GlassButton({ download = true }, Modifier.fillMaxWidth()) { Text("下载更新") }
            }
        }
        StudioAction("GitHub 项目", "源码、安装包与更新说明", StudioIcon.FILE, { open(GithubUpdates.repository) })
        StudioAction("帮助中心", "登录后打开常见问题网页", StudioIcon.BOOK, onHelp)
        StudioAction("使用条款", "服务规则、账号与第三方服务", StudioIcon.FILE, { onLegal("terms") })
        StudioAction("隐私政策", "了解数据使用、保存与删除方式", StudioIcon.MEMORY, { onLegal("privacy") })
        Text("毕业设计测试版 · 账号服务和第三方登录需由维护者开通。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (download) update.release?.let { release ->
        AlertDialog(onDismissRequest = { download = false }, title = { Text("下载 ${release.version}？") },
            text = { Text("将在系统浏览器打开 GitHub 安装包，下载完成后由你确认安装。请保留已有应用数据。") },
            confirmButton = { TextButton({ download = false; open(release.apk) }) { Text("开始下载") } },
            dismissButton = { TextButton({ download = false }) { Text("取消") } })
    }
}

@Composable
internal fun LegalScreen(document: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val page = if (document == "privacy") "privacy" else "terms"
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp).padding(bottom = LocalFloatingNavigationPadding.current)) {
        BackButton(onBack)
        AndroidView(factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean =
                        request.url.toString() !in listOf("file:///android_asset/legal/terms.html", "file:///android_asset/legal/privacy.html")
                }
            }
        }, update = { view ->
            val url = "file:///android_asset/legal/$page.html"
            if (view.tag != page) { view.tag = page; view.loadUrl(url) }
        }, onRelease = { it.stopLoading(); it.destroy() }, modifier = Modifier.fillMaxWidth().weight(1f))
    }
}

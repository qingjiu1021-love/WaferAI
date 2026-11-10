package com.example.waferai.ui.settings

import com.example.waferai.ui.theme.GlassTextButton as TextButton
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import com.example.waferai.data.update.GithubUpdates
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.waferai.BuildConfig
import com.example.waferai.R
import com.example.waferai.ui.theme.*
import com.example.waferai.ui.theme.UiText as Text

@Composable
internal fun AboutScreen(onBack: () -> Unit, onDocument: (String) -> Unit, updates: UpdateViewModel, modifier: Modifier = Modifier) {
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
                Image(painterResource(R.drawable.wafer_starry_badge), "WaferAI",
                    Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF142852)).padding(8.dp))
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
        StudioAction("帮助中心", "在本机查看常见问题与操作指南", StudioIcon.BOOK, { onDocument("help") })
        StudioAction("使用条款", "使用规则与第三方服务", StudioIcon.FILE, { onDocument("terms") })
        StudioAction("隐私政策", "了解数据使用、保存与删除方式", StudioIcon.MEMORY, { onDocument("privacy") })
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
    val documents = remember { setOf("help", "privacy", "terms") }
    var page by remember(document) { mutableStateOf(document.takeIf(documents::contains) ?: "terms") }
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val stylesheet = remember(context) { context.assets.open("legal/style.css").bufferedReader().use { it.readText() } }
    val html = remember(context, page, scheme) {
        val asset = context.assets.open("legal/$page.html").bufferedReader().use { it.readText() }
        val dark = scheme.background.luminance() < 0.5f
        val panel = scheme.surfaceContainer
        val colors = """<style>:root {
            color-scheme: ${if (dark) "dark" else "light"};
            --ink: ${scheme.onBackground.cssHex()}; --muted: ${scheme.onSurfaceVariant.cssHex()};
            --accent: ${scheme.primary.cssHex()}; --line: ${scheme.outlineVariant.cssHex()};
            --panel: rgba(${(panel.red * 255).toInt()},${(panel.green * 255).toInt()},${(panel.blue * 255).toInt()},0.84);
        }</style>"""
        asset.replace("<link rel=\"stylesheet\" href=\"style.css\">", "<style>$stylesheet</style>")
            .replace("</head>", "$colors</head>")
    }
    val base = "file:///android_asset/legal/"
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp).padding(bottom = LocalFloatingNavigationPadding.current)) {
        BackButton(onBack)
        AndroidView(factory = { context ->
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                        documents.firstOrNull { request.url.toString() == "$base$it.html" }?.let { page = it }
                        return true
                    }
                }
            }
        }, update = { view ->
            if (view.tag != html) {
                view.tag = html
                view.loadDataWithBaseURL(base, html, "text/html", "UTF-8", null)
            }
        }, onRelease = { it.stopLoading(); it.destroy() }, modifier = Modifier.fillMaxWidth().weight(1f))
    }
}

private fun Color.cssHex() = "#" + (toArgb() and 0xffffff).toString(16).padStart(6, '0')

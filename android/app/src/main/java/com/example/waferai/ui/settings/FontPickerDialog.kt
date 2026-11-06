package com.example.waferai.ui.settings

import com.example.waferai.ui.theme.GlassFilterChip as FilterChip
import com.example.waferai.ui.theme.GlassTextButton as TextButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.waferai.ui.theme.AppFont
import com.example.waferai.ui.theme.Appearance
import com.example.waferai.ui.theme.FontLibrary
import com.example.waferai.ui.theme.FontCatalog
import com.example.waferai.ui.theme.UiText as Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class FontTab(val label: String) { PRESETS("预设"), ONLINE("在线字体"), SAVED("已下载") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FontPickerDialog(appearance: Appearance, onChange: (Appearance) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val library = remember(context) { FontLibrary(context) }
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(FontTab.PRESETS) }
    var query by rememberSaveable { mutableStateOf("") }
    var catalog by remember { mutableStateOf<FontCatalog?>(null) }
    var installed by remember { mutableStateOf(emptyList<String>()) }
    var catalogError by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var license by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(library) {
        installed = withContext(Dispatchers.IO) { library.installed() }
    }
    LaunchedEffect(tab, retry) {
        if (tab != FontTab.ONLINE || catalog != null) return@LaunchedEffect
        catalogError = false
        try {
            catalog = library.catalog()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            catalogError = true
        }
    }
    val matches = remember(catalog, query) {
        catalog?.fonts.orEmpty().asSequence().filter { it.matches(query) }.take(100).toList()
    }

    fun applyDownloaded(name: String) {
        onChange(appearance.copy(downloadedFont = name))
        onClose()
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("选择字体") },
        text = {
            Column(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    FontTab.entries.forEach { choice ->
                        FilterChip(tab == choice, { tab = choice }, { Text(choice.label) })
                    }
                }
                if (tab == FontTab.ONLINE) {
                    OutlinedTextField(query, { query = it.take(60) }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("搜索字体") }, placeholder = { Text("例如中文、手写、Noto Sans") })
                    Text(if (catalog?.online == false) "使用 Google Fonts 本地索引，下载仍需联网。"
                        else "来源：Google Fonts 公开字体目录", style = MaterialTheme.typography.labelSmall)
                    when {
                        catalogError -> {
                            Text("无法连接字体目录，请检查网络。", color = MaterialTheme.colorScheme.error)
                            TextButton({ retry++ }) { Text("重试") }
                        }
                        catalog == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        matches.isEmpty() -> Text("没有匹配的字体。")
                    }
                }
                if (downloading != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                downloadError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                    when (tab) {
                        FontTab.PRESETS -> items(AppFont.entries) { font ->
                            FontPickerRow(font.label, font.family, appearance.downloadedFont == null && appearance.font == font) {
                                TextButton({
                                    onChange(appearance.copy(font = font, downloadedFont = null))
                                    onClose()
                                }) { Text("应用") }
                            }
                        }
                        FontTab.SAVED -> {
                            if (installed.isEmpty()) item { Text("还没有下载字体。") }
                            items(installed, key = { it }) { name ->
                                val family = remember(name) { library.family(name) }
                                FontPickerRow(name, family, name == appearance.downloadedFont) {
                                    TextButton({ applyDownloaded(name) }, enabled = family != null) { Text("应用") }
                                }
                                TextButton({
                                    scope.launch { license = withContext(Dispatchers.IO) { library.license(name) } }
                                }) { Text("查看许可") }
                            }
                        }
                        FontTab.ONLINE -> items(matches, key = { it.name }) { font ->
                            val downloaded = font.name in installed
                            val family = remember(font.name, downloaded) { if (downloaded) library.family(font.name) else null }
                            val supportsChinese = font.subsets.any { it.startsWith("chinese") }
                            val details = if (supportsChinese) "支持中文 · ${font.category}" else font.category
                            FontPickerRow(font.name, family, font.name == appearance.downloadedFont, details) {
                                if (family != null) {
                                    TextButton({ applyDownloaded(font.name) }) { Text("应用") }
                                } else {
                                    TextButton({
                                        downloading = font.name
                                        downloadError = null
                                        scope.launch {
                                            try {
                                                library.install(font)
                                                installed = withContext(Dispatchers.IO) { library.installed() }
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (error: IllegalArgumentException) {
                                                downloadError = error.message ?: "字体无法读取。"
                                            } catch (error: IllegalStateException) {
                                                downloadError = error.message ?: "字体下载失败。"
                                            } catch (_: Exception) {
                                                downloadError = "字体下载失败，请检查网络。"
                                            } finally {
                                                downloading = null
                                            }
                                        }
                                    }, enabled = downloading == null) { Text(if (downloading == font.name) "下载中" else "下载") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("关闭") } }
    )
    license?.let { text ->
        AlertDialog(
            onDismissRequest = { license = null },
            title = { Text("字体许可") },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    androidx.compose.material3.Text(text, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton({ license = null }) { Text("关闭") } }
        )
    }
}

@Composable
private fun FontPickerRow(
    title: String, family: FontFamily?, selected: Boolean, details: String = "",
    action: @Composable RowScope.() -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (selected) "✓ $title" else title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall)
            if (details.isNotBlank()) Text(details, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (family != null) Text("WaferAI 晶圆 87.20%", fontFamily = family,
                style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        action()
    }
    HorizontalDivider()
}

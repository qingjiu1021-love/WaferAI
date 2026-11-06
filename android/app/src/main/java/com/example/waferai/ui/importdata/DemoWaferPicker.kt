package com.example.waferai.ui.importdata

import com.example.waferai.ui.theme.GlassFilterChip as FilterChip
import com.example.waferai.ui.theme.GlassTextButton as TextButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.waferai.data.local.DemoCategory
import com.example.waferai.data.local.DemoWafer
import com.example.waferai.ui.theme.StudioBadge
import com.example.waferai.ui.theme.StudioIcon
import com.example.waferai.ui.theme.UiText as Text

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DemoWaferPicker(busy: Boolean, onSelect: (DemoWafer) -> Unit, onDismiss: () -> Unit, realOnly: Boolean = false) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<DemoCategory?>(null) }
    var sourceError by rememberSaveable { mutableStateOf(false) }
    val visible = remember(query, category, realOnly) {
        DemoWafer.search(query, category).filter { !realOnly || it.isReal }
    }
    val list = rememberLazyListState()
    val uri = LocalUriHandler.current
    val scheme = MaterialTheme.colorScheme
    LaunchedEffect(query, category) { list.scrollToItem(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = scheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (realOnly) "选择 WM-811K 回放样本" else "选择演示晶圆",
                    Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onDismiss) { Text("关闭") }
            }
            Text(if (realOnly) "27 张真实样本 · 9 类标注" else "27 张真实样本 · 9 类标注 · 3 张人工演示",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            OutlinedTextField(query, { query = it.take(80) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("demo-search"),
                label = { Text("搜索类别、批次或晶圆编号") })
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("demo-categories")) {
                item { FilterChip(category == null, { category = null }, label = { Text("全部") }) }
                items(DemoCategory.entries.filter { !realOnly || it != DemoCategory.SYNTHETIC }
                    .sortedBy { if (it == DemoCategory.SYNTHETIC) 1 else 0 }) { value ->
                    FilterChip(category == value, { category = value }, label = { Text(value.title) })
                }
            }
            Text("找到 ${visible.size} 个样本。原始标注不等于 AI 预测。", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("demo-samples"), state = list,
                verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                if (visible.isEmpty()) item { Text("没有匹配样本，试试其他类别或编号。") }
                items(visible, key = { it.filename }) { sample ->
                    Surface(onClick = { onSelect(sample) }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag(sample.filename),
                        shape = MaterialTheme.shapes.large, color = scheme.surfaceContainerHigh) {
                        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            StudioBadge(StudioIcon.WAFER, 32)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(sample.title, style = MaterialTheme.typography.titleSmall)
                                Text(if (sample.isReal) "真实 · 数据集标注 ${sample.datasetLabel}" else "人工构造 · 教学演示",
                                    style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                                Text(sample.description, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            TextButton({ runCatching { uri.openUri(DemoWafer.SourcePage) }.onFailure { sourceError = true } }, Modifier.fillMaxWidth()) {
                Text("查看 wafertools 真实样本来源")
            }
            if (sourceError) Text("无法打开浏览器，来源也已保存在知识库。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

package com.example.waferai.ui.importdata

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.example.waferai.ui.theme.GlassButton
import com.example.waferai.ui.theme.GlassTextButton as TextButton
import com.example.waferai.ui.theme.StudioPanel
import com.example.waferai.ui.theme.UiText as Text

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BatchAnalysisSheet(state: BatchState, model: BatchAnalysisViewModel,
                                onAdd: () -> Unit, onOpen: (Int) -> Unit, onExport: (BatchItem) -> Unit,
                                onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp)
            .padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("批量分析", style = MaterialTheme.typography.headlineSmall)
            Text("逐个校验、统计、检测并生成 PDF；文件仅在手机本地处理。", style = MaterialTheme.typography.bodySmall)
            GlassButton(onAdd, Modifier.fillMaxWidth(), enabled = !state.loading && !state.exporting, prominent = true) { Text("选择多个文件") }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (state.items.isNotEmpty()) {
                Text("已完成 ${state.completed} / ${state.items.size} · 失败或取消 ${state.failed}")
                LinearProgressIndicator(progress = { state.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.running) TextButton(model::cancel) { Text("取消剩余任务") }
                    else {
                        if (state.failed > 0) TextButton(model::retry) { Text("重试失败与取消项") }
                        TextButton(model::clearFinished, enabled = !state.exporting) { Text("清除任务列表") }
                    }
                }
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            if (!state.loading && state.items.isEmpty()) Text("选择多张晶圆文件开始分析。处理失败的文件不会阻塞其余任务。")
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.items, key = { it.id }) { item ->
                    StudioPanel(Modifier.fillMaxWidth()) {
                        Text(item.name, style = MaterialTheme.typography.titleSmall)
                        Text(item.stage?.label ?: item.status.label,
                            color = if (item.status == BatchStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        item.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (item.status == BatchStatus.DONE) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                item.waferId?.let { id -> TextButton({ onOpen(id) }) { Text("查看结果") } }
                                TextButton({ onExport(item) }, enabled = !state.exporting) { Text("导出 PDF") }
                            }
                        }
                    }
                }
            }
        }
    }
}

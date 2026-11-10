package com.example.waferai.ui.importdata

import com.example.waferai.ui.theme.GlassFilterChip as FilterChip
import com.example.waferai.ui.theme.GlassTextButton as TextButton
import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.waferai.data.local.WaferFileFormat
import com.example.waferai.ui.theme.UiText as Text

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WaferConversionDialog(viewModel: ImportViewModel) {
    val state by viewModel.conversion.collectAsStateWithLifecycle()
    val form = state ?: return
    val current by viewModel.state.collectAsStateWithLifecycle()
    var format by rememberSaveable { mutableStateOf(WaferFileFormat.JSON) }
    var savingFormat by rememberSaveable { mutableStateOf<WaferFileFormat?>(null) }
    var pickerError by rememberSaveable { mutableStateOf(false) }
    var archiveMenu by rememberSaveable { mutableStateOf(false) }
    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::selectConversionFile)
    }
    val outputPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = savingFormat
        savingFormat = null
        if (target != null && result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { viewModel.saveConversion(it, target) }
        }
    }
    val enabled = !form.busy && savingFormat == null
    AlertDialog(
        onDismissRequest = { if (enabled) viewModel.closeConversion() },
        title = { Text("晶圆文件转换") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("选择晶圆数据、ZIP 或 GZIP 压缩包，也可以使用当前晶圆。转换在手机本地完成。")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({
                        pickerError = false
                        runCatching { sourcePicker.launch(arrayOf("*/*")) }.onFailure { pickerError = true }
                    }, enabled = enabled) { Text("选择源文件") }
                    if (current.wafer != null) TextButton({ pickerError = false; viewModel.useCurrentForConversion() }, enabled = enabled) {
                        Text("使用当前晶圆")
                    }
                }
                form.archive?.let { archive ->
                    Box {
                        TextButton({ archiveMenu = true }, enabled = enabled) {
                            Text(archive.selected ?: "选择压缩包内的晶圆文件", maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(archiveMenu, { archiveMenu = false }, Modifier.heightIn(max = 320.dp)) {
                            archive.entries.forEach { name ->
                                DropdownMenuItem(text = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) }, onClick = {
                                    archiveMenu = false
                                    viewModel.selectConversionEntry(name)
                                })
                            }
                        }
                    }
                }
                form.wafer?.let { wafer ->
                    Text("${wafer.lotCode} / ${wafer.waferCode} · ${wafer.rows} × ${wafer.cols}", style = MaterialTheme.typography.titleSmall)
                    Text(wafer.filename, style = MaterialTheme.typography.bodySmall)
                }
                Text("输出格式", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WaferFileFormat.entries.forEach { option ->
                        FilterChip(format == option, { format = option }, enabled = enabled, label = { Text(option.name) })
                    }
                }
                if (format == WaferFileFormat.STDF || format == WaferFileFormat.ATDF) {
                    Text("晶圆图导出。文件仅包含晶圆坐标、合格状态和计数。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (form.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                form.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (pickerError) Text("无法打开文件选择器。", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton({
                val wafer = form.wafer ?: return@TextButton
                val stem = wafer.filename.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N}_.-]"), "-")
                    .take(80).trim('.', '-').ifBlank { "wafer" }
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(format.mimeType).putExtra(Intent.EXTRA_TITLE, "$stem-converted.${format.extension}")
                pickerError = false
                savingFormat = format
                runCatching { outputPicker.launch(intent) }.onFailure { savingFormat = null; pickerError = true }
            }, enabled = enabled && form.wafer != null) { Text("转换并保存") }
        },
        dismissButton = { TextButton(viewModel::closeConversion, enabled = enabled) { Text("关闭") } }
    )
}

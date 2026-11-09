package com.example.waferai.ui.importdata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.waferai.data.remote.WaferDetail
import com.example.waferai.ui.theme.GlassTextButton
import com.example.waferai.ui.theme.UiText as Text
import java.util.Locale

@Composable
internal fun WaferFilePreview(wafer: WaferDetail, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("导入预览") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(wafer.sourceKind.label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
            Text(wafer.filename)
            Text("批次 ${wafer.lotCode} · 晶圆 ${wafer.waferCode}")
            Text("${wafer.rows} 行 × ${wafer.cols} 列 · 有效芯片 ${wafer.totalCount}")
            val rate = String.format(Locale.ROOT, "%.2f%%", wafer.yieldRate * 100)
            Text("合格 ${wafer.goodCount} · 不合格 ${wafer.badCount} · 良率 $rate")
            Text(wafer.sourceKind.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = {
        GlassTextButton(onConfirm) { Text("确认导入") }
    }, dismissButton = {
        GlassTextButton(onDismiss) { Text("取消") }
    })
}

package com.example.waferai.ui.importdata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.waferai.data.local.WaferImageOptions
import com.example.waferai.data.local.WaferPalette
import com.example.waferai.ui.theme.UiText as Text

@Composable
internal fun WaferImageDialog(onDismiss: () -> Unit, onImport: (WaferImageOptions) -> Unit) {
    var rowText by rememberSaveable { mutableStateOf("") }
    var colText by rememberSaveable { mutableStateOf("") }
    var palette by rememberSaveable { mutableStateOf(WaferPalette.RED_GREEN) }
    val rows = rowText.toIntOrNull()?.takeIf { it in 1..512 }
    val cols = colText.toIntOrNull()?.takeIf { it in 1..512 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置晶圆图片网格") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("请使用已裁切到网格的晶圆图，去掉图例、坐标轴和边距。放大过的图请填写实际芯片行列数。留空时每个像素算一格。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(rowText, { rowText = it.filter(Char::isDigit).take(3) },
                        modifier = Modifier.weight(1f), label = { Text("行数") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(colText, { colText = it.filter(Char::isDigit).take(3) },
                        modifier = Modifier.weight(1f), label = { Text("列数") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Text("原图配色")
                WaferPalette.entries.forEach { option ->
                    FilterChip(palette == option, { palette = option }, { Text(option.title) })
                }
            }
        },
        confirmButton = {
            TextButton({ onImport(WaferImageOptions(rows, cols, palette)) },
                enabled = (rows != null && cols != null) || (rowText.isEmpty() && colText.isEmpty())) {
                Text("解析并识别")
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } }
    )
}

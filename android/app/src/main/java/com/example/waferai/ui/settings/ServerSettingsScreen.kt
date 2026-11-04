package com.example.waferai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.waferai.BuildConfig
import com.example.waferai.ui.theme.BackButton
import com.example.waferai.data.remote.ServerAddress

@Composable
fun ServerSettingsScreen(
    state: ServerSettingsState,
    onEdit: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focus = LocalFocusManager.current
    Column(
        modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BackButton(onBack, enabled = !state.saving)
        Text("服务设置", style = MaterialTheme.typography.headlineLarge)
        Text("当前服务", style = MaterialTheme.typography.labelLarge)
        Text(state.activeAddress, style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = state.draft, onValueChange = onEdit,
            label = { Text("分析服务地址") },
            placeholder = { Text("http://192.168.1.10:8000") },
            singleLine = true, enabled = !state.saving,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            isError = state.error != null,
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton({ focus.clearFocus(); onEdit(ServerAddress.EMULATOR) }, enabled = !state.saving, modifier = Modifier.weight(1f)) {
                Text("模拟器连接")
            }
            OutlinedButton({ focus.clearFocus(); onEdit(ServerAddress.USB) }, enabled = !state.saving, modifier = Modifier.weight(1f)) {
                Text("USB 连接")
            }
        }
        Text("只有连接成功才保存新地址。切换服务会清空当前页面，已保存数据仍在对应电脑上。", style = MaterialTheme.typography.bodyMedium)
        Button({ focus.clearFocus(); onSave() }, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.saving) "正在验证连接…" else "检查并保存")
        }
        if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("如何连接", style = MaterialTheme.typography.titleMedium)
                Text("模拟器：电脑启动分析服务后，选择“模拟器连接”。")
                Text("USB 手机：用数据线连接并允许调试，在电脑运行“连接手机.bat”，然后选择“USB 连接”。重新插线后可能需要再次运行。")
                Text("Wi-Fi 手机：手机与电脑连接同一局域网，电脑启动局域网服务后，填写电脑的 IPv4 地址和端口。")
                Text("晶圆数据与模型识别由电脑服务提供，手机需要保持连接。")
            }
        }
        Text("WaferAI ${BuildConfig.VERSION_NAME} · 毕业设计演示版", style = MaterialTheme.typography.bodySmall)
    }
}

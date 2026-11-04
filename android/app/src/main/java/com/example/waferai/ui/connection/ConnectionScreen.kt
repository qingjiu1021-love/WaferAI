package com.example.waferai.ui.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.waferai.ui.theme.WaferAITheme

@Composable
fun ConnectionScreen(
    state: ConnectionState,
    onCheckConnection: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("WaferAI", style = MaterialTheme.typography.headlineLarge)
        Text("晶圆缺陷识别与良率分析", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("分析服务", style = MaterialTheme.typography.titleMedium)
                val status = when (state) {
                    ConnectionState.Idle -> "尚未检查"
                    ConnectionState.Checking -> "正在连接"
                    ConnectionState.Connected -> "已连接"
                    is ConnectionState.Failed -> "连接失败"
                }
                Text(
                    text = status,
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (state is ConnectionState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                when (state) {
                    ConnectionState.Idle -> Text("连接电脑上的分析服务，开始检查服务状态。")
                    ConnectionState.Checking -> CircularProgressIndicator()
                    ConnectionState.Connected -> Text("分析服务已就绪。")
                    is ConnectionState.Failed -> Text(state.message)
                }
                Button(
                    onClick = onCheckConnection,
                    enabled = state != ConnectionState.Checking,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("检查连接")
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectionPreview() {
    WaferAITheme {
        ConnectionScreen(ConnectionState.Idle, {})
    }
}

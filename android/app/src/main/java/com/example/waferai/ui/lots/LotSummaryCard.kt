package com.example.waferai.ui.lots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import com.example.waferai.ui.theme.UiText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.waferai.data.remote.LotSummary
import java.util.Locale

@Composable
fun LotSummaryCard(lot: LotSummary, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(lot.lotCode, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("晶圆数量", style = MaterialTheme.typography.bodySmall)
                    Text("${lot.waferCount} 张", style = MaterialTheme.typography.headlineSmall)
                }
                Column(Modifier.weight(1f)) {
                    Text("总体良率", style = MaterialTheme.typography.bodySmall)
                    Text(lot.yieldRate?.let { String.format(Locale.CHINA, "%.2f%%", it * 100) } ?: "暂无",
                        style = MaterialTheme.typography.headlineSmall)
                }
            }
            Text("合格 ${lot.goodCount} · 不合格 ${lot.badCount}")
            Text("有效芯片 ${lot.totalCount}")
            Text("总体良率 = 总合格芯片数 ÷ 总有效芯片数", style = MaterialTheme.typography.bodySmall)
        }
    }
}

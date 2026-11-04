package com.example.waferai.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.waferai.ui.theme.LocalFloatingNavigationPadding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.waferai.data.repository.ImportRepository
import com.example.waferai.ui.theme.GlassButton
import com.example.waferai.ui.theme.StudioHeader
import com.example.waferai.ui.theme.StudioIcon
import com.example.waferai.ui.theme.StudioPanel
import com.example.waferai.ui.theme.BackButton
import com.example.waferai.ui.theme.UiText as Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun TrainingScreen(repository: ImportRepository, onBack: () -> Unit, modifier: Modifier) {
    val summary by repository.trainingSummary.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun train(uri: Uri?) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                repository.trainModel(uri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IllegalArgumentException) {
                error = failure.message ?: "训练样本格式不正确。"
            } catch (_: Exception) {
                error = "手机模型训练失败，请检查样本和可用存储。"
            } finally {
                busy = false
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) train(uri)
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).padding(bottom = LocalFloatingNavigationPadding.current),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BackButton(onBack)
        StudioHeader("手机模型训练", "WAFERAI / TRAINING", StudioIcon.TRAINING)
        StudioPanel(Modifier.fillMaxWidth()) {
            Text("在手机上学习缺陷模式", style = MaterialTheme.typography.titleLarge)
            Text("使用 WM-811K 带标签样本，从分区密度和形状特征训练九类轻量质心模型。")
            GlassButton({ train(null) }, Modifier.fillMaxWidth(), enabled = !busy, prominent = true) {
                Text(if (busy) "正在训练…" else "开始自动训练")
            }
            GlassButton({ picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                Modifier.fillMaxWidth(), enabled = !busy) { Text("加入已标注样本并训练") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        summary?.let { result ->
            StudioPanel(Modifier.fillMaxWidth()) {
                Text("手机模型已保存", style = MaterialTheme.typography.titleMedium)
                Text("训练 ${result.samples} 张 · 自定义 ${result.customSamples} 张 · 验证 ${result.validation} 张")
                val accuracy = String.format(Locale.ROOT, "%.2f%%", result.accuracy * 100)
                val macroF1 = String.format(Locale.ROOT, "%.4f", result.macroF1)
                Text("验证准确率 $accuracy · 宏平均 F1 $macroF1")
                Text("验证样本按批次隔离。这组数值来自本次手机训练的验证子集。",
                    style = MaterialTheme.typography.bodySmall)
                Text("回到晶圆工作台，选择「用训练模型检测」。", color = MaterialTheme.colorScheme.primary)
            }
        }
        StudioPanel(Modifier.fillMaxWidth()) {
            Text("自定义样本格式", style = MaterialTheme.typography.titleMedium)
            Text("使用含 samples 数组的 JSON 文件，每项包含 lot_code、wafer_code、map、label，最多 200 项和 2 MB。")
            Text("label 由你确认，可用 none、Center、Donut、Edge-Loc、Edge-Ring、Loc、Random、Scratch、Near-full。",
                style = MaterialTheme.typography.bodySmall)
            Text("训练成功后自动保存已标注样本。以后训练会合并已保存和新导入的样本，重复项只保留一份。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

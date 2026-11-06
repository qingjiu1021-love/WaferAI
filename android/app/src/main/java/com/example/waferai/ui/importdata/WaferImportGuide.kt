package com.example.waferai.ui.importdata

import com.example.waferai.ui.theme.GlassSegmentedTabs
import com.example.waferai.ui.theme.GlassTextButton as TextButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.waferai.data.local.DemoWafer
import com.example.waferai.data.local.GuideFile
import com.example.waferai.data.local.PracticeFiles
import com.example.waferai.ui.theme.GlassButton
import com.example.waferai.ui.theme.LocalEnglish
import com.example.waferai.ui.theme.StudioBadge
import com.example.waferai.ui.theme.StudioGlyph
import com.example.waferai.ui.theme.StudioIcon
import com.example.waferai.ui.theme.StudioPanel
import com.example.waferai.ui.theme.UiText as Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val WaferSourceGuideTitle = "如何获取晶圆源文件"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WaferImportGuide(state: GuideUiState, busy: Boolean, onSave: (GuideFile) -> Unit,
    onDemo: () -> Unit, onData: () -> Unit, onImage: () -> Unit, onDismiss: () -> Unit) {
    var images by rememberSaveable { mutableStateOf(false) }
    var step by rememberSaveable { mutableIntStateOf(0) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    val uri = LocalUriHandler.current
    val enabled = !busy && !state.saving
    val open: (String) -> Unit = { url -> runCatching { uri.openUri(url) }.onFailure { error = "无法打开浏览器，请稍后重试。" } }
    LaunchedEffect(images, step) { scroll.scrollTo(0) }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.93f).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(WaferSourceGuideTitle, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onDismiss) { Text("关闭") }
            }
            GlassSegmentedTabs(listOf("数据文件", "晶圆图片"), if (images) 1 else 0, { index ->
                images = index == 1
                step = 0
            })
            val titles = if (images) listOf("获取晶圆图片", "裁剪与确认网格", "上传图片并校准", "识别与查看报告")
                else listOf("获取晶圆数据", "整理支持的格式", "传到手机并导入", "检测与查看报告")
            Text("${step + 1} / 4 · ${titles[step]}", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = { (step + 1) / 4f }, modifier = Modifier.fillMaxWidth())
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).testTag("import-guide-body"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GuideIllustration(images, step)
                when {
                    !images && step == 0 -> {
                        Text("在测试软件中选中一张晶圆，导出带芯片坐标和合格状态的单片文件。没有设备数据时，可保存下方的真实练习样本，或下载公开数据。")
                        GuideDownloads(listOf(GuideFile.JSON, GuideFile.CSV), enabled, onSave)
                        TextButton({ open("https://www.kaggle.com/datasets/qingyi/wm811k-wafer-map") }) { Text("打开 WM-811K 数据下载页") }
                        Text("在 Kaggle 下载 ZIP 后解压。里面的 LSWMD.pkl 需先在电脑上转换为单片 JSON，转换方法见下一步。", style = MaterialTheme.typography.bodySmall)
                        TextButton(onDemo, enabled = enabled) { Text("一键载入内置晶圆") }
                    }
                    !images && step == 1 -> {
                        Text("JSON 包含批次号 lot_code、晶圆号 wafer_code 和网格 map。0 为非芯片区域，1 为合格，2 为不合格。")
                        Text("每个文件一张晶圆，最大 512 行 × 512 列。JSON 限 2 MB，STDF 和 Parquet 限 8 MB，ATDF 限 16 MB，CSV 限 144 MB。")
                        Text("CSV / Parquet 的列名为 lot_code,wafer_code,row,col,status。行列从 1 开始，同一文件保持相同批次和晶圆编号，未列出的位置按 0 处理。STDF / ATDF 需包含有效的 PRR 坐标及合格标记。")
                        Text("电脑转换 WM-811K：解压 LSWMD.pkl 到项目 data/raw/wm811k；在 backend 目录执行下面的命令。", style = MaterialTheme.typography.bodySmall)
                        Text(".venv\\Scripts\\python.exe scripts\\export_wm811k_sample.py", fontFamily = FontFamily.Monospace)
                        Text("生成 data/samples/wm811k-center-001.json。该命令需使用本项目已配置的电脑环境；无需在手机执行。", style = MaterialTheme.typography.bodySmall)
                        GuideDownloads(listOf(GuideFile.JSON, GuideFile.CSV), enabled, onSave)
                    }
                    !images && step == 2 -> {
                        Text("① 用 USB 文件传输、聊天软件文件传输或浏览器下载，把文件保存到手机 Download（下载）目录。聊天收到的文件需先保存到本机。")
                        Text("② 点击下方按钮，在系统文件选择器里进入「下载」或对应保存目录，选中一个支持的文件。")
                        Text("③ App 在手机本地解析并保存。成功后核对批次、晶圆编号、尺寸和有效芯片数；重复数据会打开已有记录。")
                        GlassButton(onData, enabled = enabled, prominent = true, modifier = Modifier.fillMaxWidth()) { Text("选择数据文件并导入") }
                        Text("找不到文件：切换到文件选择器侧栏的「下载 / 内部存储」，确认已解压，选择 JSON、CSV、STDF、ATDF 或 Parquet 文件。", style = MaterialTheme.typography.bodySmall)
                    }
                    !images && step == 3 -> {
                        Text("导入成功后查看「晶圆图」，双指缩放并点选芯片；进入「AI 检测」点击「开始晶圆检测」，再进入「分析报告」查看图表并导出 PDF。")
                        Text("良率按原始矩阵计算。公开样本的原始标注与本次模型预测分别解读；数据和报告保存在手机。")
                        GlassButton(onData, enabled = enabled, prominent = true, modifier = Modifier.fillMaxWidth()) { Text("开始导入数据") }
                        GlassButton(onDemo, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("用内置样本练习") }
                    }
                    images && step == 0 -> {
                        Text("在测试软件中导出晶圆网格 PNG，或展开公开样本并截取完整网格。记下实际行列数和原图图例。")
                        GuideDownloads(listOf(GuideFile.IMAGE), enabled, onSave)
                        TextButton({ open(DemoWafer.SourcePage) }) { Text("打开真实晶圆图示例") }
                        Text("练习图片中，绿为合格，红为不合格，灰为非芯片区域。", style = MaterialTheme.typography.bodySmall)
                        Text("当前识别入口针对规则芯片网格 / bin map。显微缺陷照片和实物晶圆照片不属于这种网格输入。", style = MaterialTheme.typography.bodySmall)
                    }
                    images && step == 1 -> {
                        Text("① 在相册裁剪到完整矩形网格边界，保留网格内灰色非芯片区域，去掉工具栏、图例、坐标轴和多余留白。")
                        Text("② 记录真实行列数。例如练习图片填 53 行、58 列；放大后的图片像素数不等于芯片行列数。")
                        Text("③ 对照原图图例选择配色：绿合格/红不合格；青绿合格/黄不合格/紫背景；或灰合格/白不合格/黑背景。其他颜色需先转换为支持的配色。")
                        Text("图片最大 10 MB、1600 万像素。支持 PNG、JPEG、WebP、BMP、GIF 首帧；HEIF / AVIF 取决于设备解码能力。推荐清晰 PNG。")
                        GuideDownloads(listOf(GuideFile.IMAGE), enabled, onSave)
                    }
                    images && step == 2 -> {
                        Text("① 把图片保存到手机相册或下载目录，点击下方按钮选图。")
                        Text("② 在「设置晶圆图片网格」填写实际行列数，选择原图配色。练习图片填 53 × 58，选「绿合格 · 红不合格」。")
                        Text("③ 点击「解析并识别」。App 先将网格颜色还原为芯片状态，再运行本地分类。只有原图每个像素确实对应一颗芯片时才可留空行列数。")
                        GlassButton(onImage, enabled = enabled, prominent = true, modifier = Modifier.fillMaxWidth()) { Text("选择晶圆图片并识别") }
                    }
                    images && step == 3 -> {
                        Text("成功后对照原图核对网格、合格数和不合格数，在「AI 检测」查看结果，在「分析报告」查看占比和空间分布图表，并导出 PDF。")
                        Text("若提示颜色不匹配或行列不合理，请重新裁剪并确认配色、行列。图片还原的良率应与原始电测数据复核。")
                        GlassButton(onImage, enabled = enabled, prominent = true, modifier = Modifier.fillMaxWidth()) { Text("开始导入图片") }
                    }
                }
                if (state.saving) Text("正在保存练习文件…")
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                (state.error ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ step-- }, enabled = step > 0) { Text("上一步") }
                TextButton({ if (step < 3) step++ else onDismiss() }) { Text(if (step < 3) "下一步" else "完成指引") }
            }
        }
    }
}

@Composable
private fun GuideIllustration(images: Boolean, step: Int) {
    val colors = MaterialTheme.colorScheme
    StudioPanel(Modifier.fillMaxWidth().testTag("wafer-source-illustration"),
        color = colors.surfaceContainerHighest) {
        when {
            !images && step == 0 -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        StudioBadge(StudioIcon.DATA, 44)
                        Text("晶圆测试软件", style = MaterialTheme.typography.labelMedium)
                    }
                    StudioGlyph(StudioIcon.ARROW, Modifier.size(20.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        StudioBadge(StudioIcon.FILE, 44)
                        Text("导出单片文件", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text("STDF · ATDF · CSV · JSON · Parquet", color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            !images && step == 1 -> {
                Text("单片 JSON 示例", style = MaterialTheme.typography.titleSmall, color = colors.primary)
                Text("{\n  \"lot_code\": \"LOT-01\",\n  \"wafer_code\": \"W01\",\n  \"map\": [[0,1,2],[1,1,0]]\n}",
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            !images && step == 2 -> {
                Text("手机文件选择器示意", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                Text("内部存储 / Download", style = MaterialTheme.typography.titleSmall)
                Surface(shape = RoundedCornerShape(12.dp), color = colors.surface,
                    border = BorderStroke(1.dp, colors.outlineVariant)) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StudioBadge(StudioIcon.FILE, 30)
                        Text(GuideFile.JSON.filename, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        StudioGlyph(StudioIcon.CHECK, Modifier.size(16.dp))
                    }
                }
            }
            else -> GuideWaferPreview(step)
        }
    }
}

@Composable
private fun GuideWaferPreview(step: Int) {
    val context = LocalContext.current.applicationContext
    val colors = MaterialTheme.colorScheme
    val preview by produceState<ImageBitmap?>(null, context) {
        value = withContext(Dispatchers.IO) { PracticeFiles.previewImage(context).asImageBitmap() }
    }
    val english = LocalEnglish.current
    val description = if (english) "WM-811K Center wafer map, 53 rows and 58 columns"
        else "WM-811K 中心型晶圆网格，53 行 58 列"
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(Modifier.size(112.dp), shape = RoundedCornerShape(12.dp), color = colors.surface,
            border = BorderStroke(1.dp, if (step == 1) colors.primary else colors.outlineVariant)) {
            preview?.let {
                Image(it, description, Modifier.fillMaxSize().padding(6.dp).clip(RoundedCornerShape(6.dp)))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(when (step) {
                0 -> "真实晶圆练习图"
                1 -> "沿完整网格边界裁剪"
                2 -> "填写实际网格"
                else -> "示例良率"
            }, style = MaterialTheme.typography.titleSmall)
            Text(if (step == 3) "87.20%" else "53 × 58",
                style = MaterialTheme.typography.titleLarge, color = colors.primary)
            Text(when (step) {
                0 -> "WM-811K · Center"
                1 -> "保留灰色背景"
                2 -> "绿合格 · 红不合格"
                else -> "2,145 合格 · 315 不合格"
            }, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
    }
}

@Composable private fun GuideDownloads(files: List<GuideFile>, enabled: Boolean, onSave: (GuideFile) -> Unit) {
    files.forEach { file ->
        GlassButton({ onSave(file) }, Modifier.fillMaxWidth(), enabled = enabled) { Text(file.title) }
    }
}

package com.example.waferai.ui.settings

import com.example.waferai.ui.theme.GlassFilterChip as FilterChip
import com.example.waferai.ui.theme.GlassTextButton as TextButton
import com.example.waferai.ui.theme.GlassIconButton as IconButton
import com.example.waferai.ui.theme.GlassDropdownMenu as DropdownMenu
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import com.example.waferai.ui.theme.UiText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.waferai.ui.theme.Appearance
import com.example.waferai.ui.theme.BottomBarStyle
import com.example.waferai.ui.theme.GlassLevel
import com.example.waferai.ui.theme.PaletteStyle
import com.example.waferai.ui.theme.TabMotion
import com.example.waferai.ui.theme.ThemeAccent
import com.example.waferai.ui.theme.ThemeMode
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(
    appearance: Appearance,
    onChange: (Appearance) -> Unit,
    onReset: () -> Boolean,
    canReset: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showPalette by rememberSaveable { mutableStateOf(false) }
    var showFonts by rememberSaveable { mutableStateOf(false) }
    var showColor by rememberSaveable { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onBack,
                Modifier.size(46.dp).background(scheme.surfaceContainer, CircleShape)
                    .semantics { contentDescription = "返回" }
            ) {
                Canvas(Modifier.size(20.dp)) {
                    val stroke = 2.dp.toPx()
                    val middle = center.y
                    drawLine(scheme.onSurface, Offset(2f, middle), Offset(size.width - 2f, middle), stroke, StrokeCap.Round)
                    drawLine(scheme.onSurface, Offset(2f, middle), Offset(center.x, 4f), stroke, StrokeCap.Round)
                    drawLine(scheme.onSurface, Offset(2f, middle), Offset(center.x, size.height - 4f), stroke, StrokeCap.Round)
                }
            }
            Text("主题", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.weight(1f).verticalScroll(scrollState).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ThemePreview(appearance)
            SectionTitle("☼", "主题模式")
            ThemeChoices(ThemeMode.entries, appearance.mode, { it.label }, { onChange(appearance.copy(mode = it)) }) { mode ->
                ThemeMiniature(
                    appearance, Modifier.size(44.dp, 32.dp),
                    darkOverride = mode != ThemeMode.LIGHT, split = mode == ThemeMode.SYSTEM
                )
            }

            SectionTitle("▣", "纯黑模式")
            ThemeChoices(listOf(false, true), appearance.pureBlack, { if (it) "纯黑" else "标准" }, {
                onChange(appearance.copy(pureBlack = it))
            }) { black ->
                ThemeMiniature(appearance.copy(pureBlack = black), Modifier.size(44.dp, 32.dp), darkOverride = true)
            }

            SectionTitle("◉", "主题色彩")
            SettingsCard {
                FlowRow(
                    Modifier.fillMaxWidth().padding(10.dp).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    ThemeAccent.entries.filter { it != ThemeAccent.CUSTOM }.forEach { accent ->
                        ColorSwatch(accent.label, accent.color, appearance.accent == accent, {
                            onChange(appearance.copy(accent = accent))
                        }, mark = if (accent == ThemeAccent.SYSTEM) "◈" else null)
                    }
                    appearance.savedColors.forEach { color ->
                        ColorSwatch(hexColor(color), Color(color), appearance.accent == ThemeAccent.CUSTOM && appearance.customColor == color, {
                            onChange(appearance.copy(accent = ThemeAccent.CUSTOM, customColor = color))
                        })
                    }
                    Box(
                        Modifier.size(48.dp).clickable(role = Role.Button) { showColor = true }
                            .semantics { contentDescription = "添加自定义颜色" },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(Modifier.size(35.dp).background(scheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
                            Text("+", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
            Box {
                SettingsCard {
                    SettingsLink("配色风格", appearance.palette.label, "⌄") { showPalette = true }
                }
                DropdownMenu(showPalette, { showPalette = false }) {
                    PaletteStyle.entries.forEach { palette ->
                        DropdownMenuItem(text = { Text(palette.label) }, onClick = {
                            onChange(appearance.copy(palette = palette))
                            showPalette = false
                        }, trailingIcon = if (palette == appearance.palette) { { Checkmark() } } else null)
                    }
                }
            }

            SectionTitle("▱", "底栏")
            ThemeChoices(BottomBarStyle.entries, appearance.bottomBar, { it.label }, { onChange(appearance.copy(bottomBar = it)) }) {
                ThemeMiniature(appearance.copy(bottomBar = it), Modifier.size(44.dp, 32.dp))
            }

            SectionTitle("◌", "选项卡动画")
            ThemeChoices(TabMotion.entries, appearance.tabMotion, { it.label }, { onChange(appearance.copy(tabMotion = it)) }) {
                ThemeMiniature(
                    appearance, Modifier.size(44.dp, 32.dp),
                    slide = it == TabMotion.SLIDE, fade = it == TabMotion.FADE
                )
            }

            SectionTitle("◇", "液态玻璃")
            ThemeChoices(GlassLevel.entries, appearance.glass, { it.label }, { onChange(appearance.copy(glass = it)) }) {
                ThemeMiniature(
                    appearance.copy(glass = it, bottomBar = BottomBarStyle.FLOATING),
                    Modifier.size(44.dp, 32.dp)
                )
            }

            if (appearance.bottomBar == BottomBarStyle.FLOATING && appearance.glass != GlassLevel.NONE) {
                var opacity by remember(appearance.navOpacity) { mutableFloatStateOf(appearance.navOpacity.toFloat()) }
                SettingsCard {
                    Column(Modifier.padding(16.dp)) {
                        Text("底栏不透明度 · ${opacity.roundToInt()}%")
                        Slider(opacity, { opacity = it }, valueRange = 35f..100f, steps = 12,
                            onValueChangeFinished = { onChange(appearance.copy(navOpacity = opacity.roundToInt())) },
                            modifier = Modifier.semantics { contentDescription = "底栏不透明度" })
                    }
                }
            }

            SectionTitle("A", "字体")
            SettingsCard {
                SettingsLink("Aa", appearance.downloadedFont ?: appearance.font.label, "›") { showFonts = true }
            }

            SectionTitle("Tᵀ", "文本缩放")
            SettingsCard {
                Row(Modifier.fillMaxWidth().padding(4.dp).selectableGroup()) {
                    listOf(true to "跟随系统", false to "自定义").forEach { (system, label) ->
                        val selected = appearance.followSystemText == system
                        Box(
                            Modifier.weight(1f).heightIn(min = 48.dp)
                                .background(if (selected) scheme.primary else Color.Transparent, CircleShape)
                                .selectable(selected, role = Role.RadioButton) { onChange(appearance.copy(followSystemText = system)) }
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, color = if (selected) scheme.onPrimary else scheme.onSurface)
                        }
                    }
                }
            }
            val effective = (LocalDensity.current.fontScale * 100).roundToInt()
            var textDraft by remember(appearance.textPercent) { mutableFloatStateOf(appearance.textPercent.toFloat()) }
            val displayedPercent = if (appearance.followSystemText) effective else textDraft.roundToInt()
            SettingsCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Aa", style = MaterialTheme.typography.titleLarge)
                        Text("$displayedPercent%", Modifier.background(scheme.surfaceContainerHighest, CircleShape).padding(horizontal = 12.dp, vertical = 4.dp))
                    }
                    Text("应用内的文字将以这个大小显示", color = scheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("A", style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = displayedPercent.coerceIn(75, 150).toFloat(),
                            onValueChange = { textDraft = it },
                            onValueChangeFinished = { onChange(appearance.copy(textPercent = textDraft.roundToInt())) },
                            modifier = Modifier.weight(1f).semantics { contentDescription = "文字大小" },
                            enabled = !appearance.followSystemText,
                            valueRange = 75f..150f,
                            steps = 14
                        )
                        Text("A", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            TextButton(
                onClick = { confirmReset = true },
                enabled = canReset,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp)
            ) {
                Text("恢复默认设置")
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("恢复默认设置？") },
            text = { Text("主题、语言和常规设置会立即恢复默认，当前 AI 会切回第一条已添加的配置。已保存的 AI 配置、密钥和晶圆记录会保留。") },
            confirmButton = {
                TextButton({
                    confirmReset = false
                    if (onReset()) scope.launch { scrollState.scrollTo(0) }
                }, enabled = canReset) { Text("确认恢复") }
            },
            dismissButton = { TextButton({ confirmReset = false }) { Text("取消") } }
        )
    }

    if (showFonts) {
        FontPickerDialog(appearance, onChange) { showFonts = false }
    }
    if (showColor) {
        CustomColorDialog(appearance.customColor, { showColor = false }) { color ->
            onChange(appearance.copy(
                accent = ThemeAccent.CUSTOM,
                customColor = color,
                savedColors = (listOf(color) + appearance.savedColors).distinct().take(8)
            ))
            showColor = false
        }
    }
}

@Composable
private fun CustomColorDialog(initial: Int, onClose: () -> Unit, onSave: (Int) -> Unit) {
    var mode by rememberSaveable { mutableStateOf(0) }
    var input by rememberSaveable { mutableStateOf(hexColor(initial)) }
    var color by rememberSaveable { mutableStateOf(initial) }
    val hsv = FloatArray(3).apply { android.graphics.Color.colorToHSV(color, this) }
    fun select(value: Int) {
        color = value
        input = hexColor(value)
    }
    fun selectHsv(h: Float, s: Float, v: Float) = select(Color.hsv(h.coerceIn(0f, 360f), s, v).toArgb())
    val parsed = input.trim().removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)?.or(0xFF000000.toInt())
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("自定义主题色") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("网格", "光谱", "滑块").forEachIndexed { index, label ->
                        FilterChip(mode == index, { mode = index; input = hexColor(color) }, { Text(label) })
                    }
                }
                when (mode) {
                    0 -> Column {
                        (0..4).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                (0..5).forEach { col ->
                                    val swatch = if (row == 0) Color.hsv(0f, 0f, 1f - col * .18f)
                                        else Color.hsv(col * 60f + if (row % 2 == 0) 22f else 0f,
                                            if (row < 3) .48f + .12f * row else .85f, if (row < 4) .95f else .65f)
                                    val hex = hexColor(swatch.toArgb())
                                    Box(Modifier.weight(1f).heightIn(min = 40.dp)
                                        .background(swatch, RoundedCornerShape(7.dp))
                                        .semantics { contentDescription = hex }
                                        .clickable { select(swatch.toArgb()) }, contentAlignment = Alignment.Center) {
                                        if (swatch.toArgb() == color) Checkmark(if (swatch.luminance() > .4f) Color.Black else Color.White)
                                    }
                                }
                            }
                            Spacer(Modifier.height(3.dp))
                        }
                    }
                    1 -> {
                        val choose by androidx.compose.runtime.rememberUpdatedState<(Float, Float) -> Unit>({ x, y ->
                            selectHsv(hsv[0], x, 1f - y)
                        })
                        val hueColor = Color.hsv(hsv[0], 1f, 1f)
                        Canvas(Modifier.fillMaxWidth().height(132.dp).pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                fun selectAt(point: Offset) = choose((point.x / size.width).coerceIn(0f, 1f),
                                    (point.y / size.height).coerceIn(0f, 1f))
                                selectAt(down.position)
                                down.consume()
                                do {
                                    val event = awaitPointerEvent()
                                    val touch = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (touch.pressed) { selectAt(touch.position); touch.consume() }
                                } while (touch.pressed)
                            }
                        }) {
                            drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
                            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                            val point = Offset((size.width - 12.dp.toPx()) * hsv[1] + 6.dp.toPx(),
                                (size.height - 12.dp.toPx()) * (1 - hsv[2]) + 6.dp.toPx())
                            drawCircle(Color.Black, 6.dp.toPx(), point, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
                            drawCircle(Color.White, 6.dp.toPx(), point, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                        }
                        Text("色相")
                        Slider(hsv[0], { selectHsv(it, hsv[1], hsv[2]) }, valueRange = 0f..360f,
                            modifier = Modifier.semantics { contentDescription = "色相" })
                    }
                    else -> {
                        listOf("红" to 16, "绿" to 8, "蓝" to 0).forEach { (label, shift) ->
                            val channel = (color ushr shift) and 255
                            Text("$label · $channel", style = MaterialTheme.typography.labelLarge)
                            Slider(channel.toFloat(), { select((color and (255 shl shift).inv()) or (it.roundToInt() shl shift)) },
                                valueRange = 0f..255f, modifier = Modifier.semantics { contentDescription = label })
                        }
                        OutlinedTextField(input, {
                            input = it.take(7)
                            input.trim().removePrefix("#").takeIf { value -> value.length == 6 }
                                ?.toIntOrNull(16)?.let { value -> color = value or 0xFF000000.toInt() }
                        }, singleLine = true, isError = parsed == null, label = { Text("HEX 色值") },
                            modifier = Modifier.fillMaxWidth())
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(32.dp).background(Color(color), CircleShape))
                    Text(hexColor(color))
                }
            }
        },
        confirmButton = { TextButton({ onSave(color) }, enabled = mode != 2 || parsed != null) { Text("保存颜色") } },
        dismissButton = { TextButton(onClose) { Text("取消") } }
    )
}

private fun hexColor(value: Int) = String.format(Locale.ROOT, "#%06X", value and 0xFFFFFF)

@Composable
private fun SectionTitle(symbol: String, title: String) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(symbol, color = color, style = MaterialTheme.typography.titleLarge)
        Text(title, color = color, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) { content() }
}

@Composable
private fun <T> ThemeChoices(
    options: List<T>, value: T, label: (T) -> String, onSelect: (T) -> Unit,
    thumbnail: @Composable (T) -> Unit
) {
    SettingsCard {
        Column(Modifier.selectableGroup()) {
            options.forEachIndexed { index, option ->
                val selected = value == option
                if (index > 0) HorizontalDivider(
                    Modifier.padding(start = 74.dp, end = 14.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                )
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .selectable(selected, role = Role.RadioButton) { onSelect(option) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    thumbnail(option)
                    Text(label(option), Modifier.weight(1f))
                    if (selected) Checkmark() else Box(Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun SettingsLink(label: String, value: String, arrow: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 54.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(arrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ColorSwatch(label: String, color: Color, selected: Boolean, onClick: () -> Unit, mark: String? = null) {
    Box(
        Modifier.size(48.dp).selectable(selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        val outline = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
        Box(Modifier.size(43.dp).border(2.dp, outline, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(34.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
                val ink = if (color.luminance() > 0.36f) Color(0xFF15151B) else Color.White
                if (selected) Checkmark(ink) else if (mark != null) Text(mark, color = ink)
            }
        }
    }
}

@Composable
private fun Checkmark(color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(Modifier.size(20.dp)) {
        val stroke = 2.dp.toPx()
        val elbow = Offset(size.width * 0.42f, size.height * 0.72f)
        drawLine(color, Offset(size.width * 0.16f, size.height * 0.47f), elbow, stroke, StrokeCap.Round)
        drawLine(color, elbow, Offset(size.width * 0.85f, size.height * 0.25f), stroke, StrokeCap.Round)
    }
}

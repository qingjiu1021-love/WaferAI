package com.example.waferai.ui.theme

import com.example.waferai.data.ai.ChatThinking

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.waferai.data.analysis.WaferMapFilter

enum class ThemeMode(val label: String) {
    SYSTEM("自动"), LIGHT("浅色"), DARK("深色")
}

enum class ThemeAccent(val label: String, val color: Color) {
    SYSTEM("壁纸取色", Color(0xFFC5CBD3)),
    BLUE("海蓝", Color(0xFF1680F5)),
    VIOLET("紫罗兰", Color(0xFFB541D4)),
    ROSE("玫红", Color(0xFFFF3470)),
    RED("珊瑚红", Color(0xFFF54745)),
    ORANGE("暖橙", Color(0xFFFF902C)),
    AMBER("琥珀", Color(0xFFFFC200)),
    GREEN("翠绿", Color(0xFF31BE62)),
    TEAL("青绿", Color(0xFF25A995)),
    GRAY("岩灰", Color(0xFF92939B)),
    CUSTOM("自定义", Color(0xFF1680F5))
}

enum class PaletteStyle(val label: String) { VIVID("鲜明"), SOFT("柔和"), NEUTRAL("中性") }
enum class BottomBarStyle(val label: String) { FLOATING("悬浮"), FIXED("固定") }
enum class TabMotion(val label: String) { SLIDE("滑动"), FADE("淡入") }
enum class GlassLevel(val label: String) { NONE("无"), STANDARD("标准"), INTENSE("极致") }
enum class AppLanguage(val label: String, val tag: String) {
    SYSTEM("跟随系统", ""), CHINESE("简体中文", "zh-Hans"), TRADITIONAL("繁體中文", "zh-Hant"),
    ENGLISH("English", "en"), JAPANESE("日本語", "ja"), KOREAN("한국어", "ko"),
    FRENCH("Français", "fr"), GERMAN("Deutsch", "de"), SPANISH("Español", "es"),
    PORTUGUESE("Português", "pt"), ITALIAN("Italiano", "it"), RUSSIAN("Русский", "ru"),
    ARABIC("العربية", "ar"), HINDI("हिन्दी", "hi"), INDONESIAN("Bahasa Indonesia", "id"),
    VIETNAMESE("Tiếng Việt", "vi"), THAI("ไทย", "th"), TURKISH("Türkçe", "tr"),
    DUTCH("Nederlands", "nl"), POLISH("Polski", "pl"), SWEDISH("Svenska", "sv"),
    UKRAINIAN("Українська", "uk");

    internal val chinese get() = this == CHINESE || this == TRADITIONAL

    internal fun resolve(locale: java.util.Locale): AppLanguage {
        if (this != SYSTEM) return this
        if (locale.language == "zh") return if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO"))
            TRADITIONAL else CHINESE
        return entries.firstOrNull { it.tag == locale.language } ?: ENGLISH
    }
}
enum class StartTab(val label: String) { WAFER("晶圆"), DATA("数据"), AI("AI"), TOOLS("工具"), PROFILE("我的") }

enum class AppFont(val label: String, val family: FontFamily) {
    DEFAULT("默认", FontFamily.Default),
    SANS("无衬线", FontFamily.SansSerif),
    SERIF("衬线", FontFamily.Serif),
    MONOSPACE("等宽", FontFamily.Monospace),
    CURSIVE("手写", FontFamily.Cursive)
}

data class Appearance(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val accent: ThemeAccent = ThemeAccent.BLUE,
    val pureBlack: Boolean = false,
    val palette: PaletteStyle = PaletteStyle.VIVID,
    val customColor: Int = 0xFF1680F5.toInt(),
    val savedColors: List<Int> = emptyList(),
    val bottomBar: BottomBarStyle = BottomBarStyle.FLOATING,
    val tabMotion: TabMotion = TabMotion.SLIDE,
    val glass: GlassLevel = GlassLevel.STANDARD,
    val navOpacity: Int = 52,
    val chatMemory: Boolean = true,
    val font: AppFont = AppFont.DEFAULT,
    val downloadedFont: String? = null,
    val followSystemText: Boolean = true,
    val textPercent: Int = 100,
    val mapFilter: WaferMapFilter = WaferMapFilter.ALL,
    val showRegionGuides: Boolean = false,
    val autoKnowledge: Boolean = true,
    val knowledgeHours: Int = 24,
    val yieldThreshold: Int = 90,
    val language: AppLanguage = AppLanguage.CHINESE,
    val startTab: StartTab = StartTab.WAFER,
    val chatProfileId: String? = null,
    val chatThinking: Map<String, ChatThinking> = emptyMap(),
    val analysisPreset: com.example.waferai.data.ai.AnalysisPreset = com.example.waferai.data.ai.AnalysisPreset.ENGINEERING
)

internal val KnowledgeIntervals = listOf(1, 3, 6, 12, 24, 48, 168)

internal val LocalFloatingNavigationPadding = staticCompositionLocalOf { 0.dp }
internal val LocalGlassLevel = staticCompositionLocalOf { GlassLevel.STANDARD }

class AppearancePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    fun load(): Appearance {
        val legacyScale = when (preferences.getString("font_size", null)) {
            "SMALL" -> 90
            "LARGE" -> 115
            "EXTRA_LARGE" -> 130
            else -> 100
        }
        return Appearance(
            mode = preferences.option("mode", ThemeMode.SYSTEM),
            analysisPreset = preferences.option("analysis_preset", com.example.waferai.data.ai.AnalysisPreset.ENGINEERING),
            accent = preferences.option("accent", ThemeAccent.BLUE),
            pureBlack = preferences.getBoolean("pure_black", false),
            palette = preferences.option("palette", PaletteStyle.VIVID),
            customColor = preferences.getInt("custom_color", Appearance().customColor),
            savedColors = preferences.getString("saved_colors", "").orEmpty()
                .split(',').mapNotNull(String::toIntOrNull).take(8),
            bottomBar = preferences.option("bottom_bar", BottomBarStyle.FLOATING),
            tabMotion = preferences.option("tab_motion", TabMotion.SLIDE),
            glass = preferences.option("glass", GlassLevel.STANDARD),
            navOpacity = preferences.getInt("nav_opacity", Appearance().navOpacity).coerceIn(35, 100),
            chatMemory = preferences.getBoolean("chat_memory", true),
            font = preferences.option("font", AppFont.DEFAULT),
            downloadedFont = preferences.getString("downloaded_font", null),
            followSystemText = preferences.getBoolean("system_text", legacyScale == 100),
            textPercent = preferences.getInt("text_percent", legacyScale).coerceIn(75, 150),
            mapFilter = preferences.option("map_filter", WaferMapFilter.ALL),
            showRegionGuides = preferences.getBoolean("show_region_guides", false),
            autoKnowledge = preferences.getBoolean("auto_knowledge", true),
            knowledgeHours = preferences.getInt("knowledge_hours", 24).takeIf { it in KnowledgeIntervals } ?: 24,
            yieldThreshold = preferences.getInt("yield_threshold", 90).coerceIn(1, 100),
            language = preferences.option("language", AppLanguage.CHINESE),
            startTab = preferences.option("start_tab", StartTab.WAFER),
            chatProfileId = preferences.getString("chat_profile_id", null),
            chatThinking = preferences.getStringSet("chat_thinking", emptySet()).orEmpty().mapNotNull { entry ->
                val id = entry.substringBefore(':')
                ChatThinking.entries.find { it.name == entry.substringAfter(':', "") }?.let { id to it }
            }.toMap()
        )
    }

    fun save(appearance: Appearance) {
        val saved = preferences.edit()
            .putString("mode", appearance.mode.name)
            .putString("analysis_preset", appearance.analysisPreset.name)
            .putString("accent", appearance.accent.name)
            .putBoolean("pure_black", appearance.pureBlack)
            .putString("palette", appearance.palette.name)
            .putInt("custom_color", appearance.customColor)
            .putString("saved_colors", appearance.savedColors.joinToString(","))
            .putString("bottom_bar", appearance.bottomBar.name)
            .putString("tab_motion", appearance.tabMotion.name)
            .putString("glass", appearance.glass.name)
            .putInt("nav_opacity", appearance.navOpacity)
            .putBoolean("chat_memory", appearance.chatMemory)
            .putString("font", appearance.font.name)
            .putString("downloaded_font", appearance.downloadedFont)
            .putBoolean("system_text", appearance.followSystemText)
            .putInt("text_percent", appearance.textPercent)
            .putString("map_filter", appearance.mapFilter.name)
            .putBoolean("show_region_guides", appearance.showRegionGuides)
            .putBoolean("auto_knowledge", appearance.autoKnowledge)
            .putInt("knowledge_hours", appearance.knowledgeHours)
            .putInt("yield_threshold", appearance.yieldThreshold)
            .putString("language", appearance.language.name)
            .putString("start_tab", appearance.startTab.name)
            .putString("chat_profile_id", appearance.chatProfileId)
            .putStringSet("chat_thinking", appearance.chatThinking.map { (id, effort) -> "$id:${effort.name}" }.toSet())
            .remove("font_size")
            .commit()
        check(saved) { "无法保存主题设置，请重试。" }
    }

    private inline fun <reified T : Enum<T>> SharedPreferences.option(key: String, fallback: T): T {
        val value = getString(key, null)
        return enumValues<T>().firstOrNull { it.name == value } ?: fallback
    }
}

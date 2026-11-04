package com.example.waferai

import android.os.Bundle
import com.example.waferai.ui.account.AccountViewModel
import com.example.waferai.ui.account.AccountScreen
import com.example.waferai.ui.settings.AboutScreen
import com.example.waferai.ui.settings.LegalScreen
import android.animation.ValueAnimator
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.example.waferai.ui.theme.UiText as Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.core.view.WindowCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.waferai.data.local.WaferOverview
import com.example.waferai.data.local.WaferYieldBand
import com.example.waferai.data.ai.ChatStore
import com.example.waferai.data.ai.ChatThinking
import com.example.waferai.ui.chat.ChatScreen
import com.example.waferai.ui.chat.ChatViewModel
import com.example.waferai.ui.dashboard.StatisticsScreen
import com.example.waferai.ui.dashboard.TabIcon
import com.example.waferai.ui.history.HistoryScreen
import com.example.waferai.ui.history.HistoryViewModel
import com.example.waferai.ui.history.WaferCompareScreen
import com.example.waferai.ui.importdata.ImportViewModel
import com.example.waferai.ui.importdata.WaferHomeScreen
import com.example.waferai.ui.lots.LotScreen
import com.example.waferai.ui.lots.LotViewModel
import com.example.waferai.ui.settings.AiCenterScreen
import com.example.waferai.ui.settings.AiCenterViewModel
import com.example.waferai.ui.settings.AppearanceScreen
import com.example.waferai.ui.settings.BackupViewModel
import com.example.waferai.ui.settings.BackupScreen
import com.example.waferai.ui.settings.GeneralScreen
import com.example.waferai.ui.settings.LanguageScreen
import com.example.waferai.ui.settings.ToolsScreen
import com.example.waferai.ui.settings.ProfileScreen
import com.example.waferai.ui.settings.OperationLogScreen
import com.example.waferai.ui.settings.KnowledgeScreen
import com.example.waferai.ui.settings.AiTasksScreen
import com.example.waferai.ui.settings.SearchPluginsScreen
import com.example.waferai.ui.settings.TrainingScreen
import com.example.waferai.ui.dashboard.InspectionTrendScreen
import com.example.waferai.ui.importdata.WaferImageDialog
import com.example.waferai.data.ai.KnowledgeSyncJob
import android.net.Uri
import com.example.waferai.ui.theme.StartTab
import com.example.waferai.ui.theme.AppearancePreferences
import com.example.waferai.ui.theme.Appearance
import com.example.waferai.ui.theme.BottomBarStyle
import com.example.waferai.ui.theme.TabMotion
import com.example.waferai.ui.theme.WaferAITheme
import com.example.waferai.ui.theme.glassSurface
import com.example.waferai.ui.theme.GlassLevel
import com.example.waferai.ui.theme.LocalFloatingNavigationPadding
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.mutableIntStateOf

private enum class Page { OVERVIEW, WAFER, DATA, HISTORY, LOTS, AI, APPEARANCE, CHAT, COMPARE, TOOLS, LANGUAGE, BACKUP, GENERAL, MODELS, LOGS, KNOWLEDGE, TRAINING, TREND, PLUGINS, TASKS, PROFILE, ACCOUNT, ABOUT, LEGAL }

private val tabs = listOf(
    Page.WAFER to "晶圆",
    Page.DATA to "数据",
    Page.AI to "AI",
    Page.TOOLS to "工具",
    Page.PROFILE to "我的"
)

private fun StartTab.page(): Page = when (this) {
    StartTab.WAFER -> Page.WAFER
    StartTab.DATA -> Page.DATA
    StartTab.AI -> Page.AI
    StartTab.TOOLS -> Page.TOOLS
    StartTab.PROFILE -> Page.PROFILE
}

private val tabEasing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

@Composable
private fun WaferBottomBar(appearance: Appearance, selectedTab: Page, onSelect: (Page) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val floating = appearance.bottomBar == BottomBarStyle.FLOATING
    val modifier = if (floating) {
        Modifier.fillMaxWidth().windowInsetsPadding(NavigationBarDefaults.windowInsets)
            .padding(horizontal = 16.dp, vertical = 7.dp).height(68.dp)
            .glassSurface(
                scheme.surfaceContainer, scheme.primary, RoundedCornerShape(50),
                appearance.glass, scheme.surface.luminance() < 0.4f, appearance.navOpacity / 100f
            )
    } else Modifier.fillMaxWidth()
    NavigationBar(
        modifier = modifier,
        containerColor = if (floating) Color.Transparent else scheme.surfaceContainer,
        windowInsets = if (floating) WindowInsets(0, 0, 0, 0) else NavigationBarDefaults.windowInsets
    ) {
        tabs.forEachIndexed { index, (destination, label) ->
            val selected = selectedTab == destination
            val foreground = if (selected) scheme.primary else scheme.onSurfaceVariant
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(destination) },
                icon = { TabIcon(index, foreground) },
                label = { Text(label, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = scheme.primary.copy(alpha = if (floating) 0.17f else 0.22f),
                    selectedIconColor = scheme.primary,
                    selectedTextColor = scheme.primary,
                    unselectedIconColor = scheme.onSurfaceVariant,
                    unselectedTextColor = scheme.onSurfaceVariant
                )
            )
        }
    }
}

class MainActivity : ComponentActivity() {
    private val accountViewModel: AccountViewModel by viewModels()
    private val updateViewModel: com.example.waferai.ui.settings.UpdateViewModel by viewModels()
    private companion object {
        var launchShown = false
    }
    private val container get() = (application as WaferAiApplication).container
    private val repository get() = container.importRepository
    private val aiViewModel: AiCenterViewModel by viewModels { AiCenterViewModel.Factory(repository.aiProfiles) }
    private val chatViewModel: ChatViewModel by viewModels {
        ChatViewModel.Factory(applicationContext, ChatStore(applicationContext), repository.aiProfiles, container.knowledge)
    }
    private val lotViewModel: LotViewModel by viewModels { LotViewModel.Factory(repository) }
    private val historyViewModel: HistoryViewModel by viewModels { HistoryViewModel.Factory(repository) }
    private val importViewModel: ImportViewModel by viewModels { ImportViewModel.Factory(repository) }
    private val backupViewModel: BackupViewModel by viewModels { BackupViewModel.Factory(repository) }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val animateLaunch = !launchShown && savedInstanceState == null && ValueAnimator.areAnimatorsEnabled()
        launchShown = true
        var launchReady by mutableStateOf(false)
        var launchVisible by mutableStateOf(animateLaunch)
        // Release on the first drawable app frame. Never wait for networking, a model,
        // or a branding timer; the existing home screen initializes behind the overlay.
        splashScreen.setOnExitAnimationListener { provider ->
            launchReady = true
            provider.remove()
        }
        setContent {
            val appearancePreferences = remember { AppearancePreferences(applicationContext) }
            var appearance by remember { mutableStateOf(appearancePreferences.load()) }
            LaunchedEffect(appearance.autoKnowledge, appearance.knowledgeHours) {
                KnowledgeSyncJob.configure(applicationContext, container.knowledge)
            }
            val settingsSnackbar = remember { SnackbarHostState() }
            var settingsMessage by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(settingsMessage) {
                settingsMessage?.let {
                    settingsSnackbar.showSnackbar(it)
                    settingsMessage = null
                }
            }
            fun saveAppearance(updated: Appearance, restoreDefaults: Boolean = false): Boolean {
                try {
                    appearancePreferences.save(updated)
                    if (restoreDefaults) {
                        try {
                            aiViewModel.restoreDefaultSelection()
                        } catch (error: IllegalStateException) {
                            appearancePreferences.save(appearance)
                            throw error
                        }
                    }
                    appearance = updated
                    if (restoreDefaults) settingsMessage = "已恢复默认设置。"
                    return true
                } catch (error: IllegalStateException) {
                    settingsMessage = error.message ?: "无法保存设置，请重试。"
                    return false
                }
            }
            WaferAITheme(appearance, animateLaunch = animateLaunch, launchReady = launchReady,
                onLaunchFinished = { launchVisible = false }) {
                val lightBars = !launchVisible && MaterialTheme.colorScheme.background.luminance() > 0.5f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }

                val importState by importViewModel.state.collectAsStateWithLifecycle()
                val historyState by historyViewModel.state.collectAsStateWithLifecycle()
                val lotState by lotViewModel.state.collectAsStateWithLifecycle()
                val aiState by aiViewModel.state.collectAsStateWithLifecycle()
                val chatState by chatViewModel.state.collectAsStateWithLifecycle()
                val accountState by accountViewModel.state.collectAsStateWithLifecycle()
                LaunchedEffect(accountState.browser) {
                    accountState.browser?.let { url ->
                        runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                            .onSuccess { accountViewModel.browserOpened() }.onFailure { accountViewModel.browserFailed() }
                    }
                }
                val csvState by historyViewModel.csv.collectAsStateWithLifecycle()
                val backup by backupViewModel.state.collectAsStateWithLifecycle()
                val training by repository.trainingSummary.collectAsStateWithLifecycle()
                var appliedRestore by rememberSaveable { mutableIntStateOf(0) }
                var page by rememberSaveable { mutableStateOf(appearance.startTab.page()) }
                var accountReturn by rememberSaveable { mutableStateOf(Page.PROFILE) }
                var legalReturn by rememberSaveable { mutableStateOf(Page.ABOUT) }
                var legalDocument by rememberSaveable { mutableStateOf("terms") }
                LaunchedEffect(accountState.message, page) {
                    if (page != Page.ACCOUNT && accountState.error) accountState.message?.let { settingsSnackbar.showSnackbar(it) }
                }
                var themeReturn by rememberSaveable { mutableStateOf(Page.PROFILE) }
                var chatOrigin by rememberSaveable { mutableStateOf(Page.AI) }
                var taskReturn by rememberSaveable { mutableStateOf(Page.AI) }
                var pluginReturn by rememberSaveable { mutableStateOf(Page.TOOLS) }
                var modelReturn by rememberSaveable { mutableStateOf(Page.AI) }
                var directModelAdd by rememberSaveable { mutableStateOf(false) }
                var directEditorSeen by rememberSaveable { mutableStateOf(false) }
                var comparison by rememberSaveable { mutableStateOf(intArrayOf()) }
                var lotId by rememberSaveable { mutableStateOf<Int?>(null) }
                var lotCode by rememberSaveable { mutableStateOf<String?>(null) }
                var reviewingYield by rememberSaveable { mutableStateOf(false) }
                var yieldBand by rememberSaveable { mutableStateOf<WaferYieldBand?>(null) }
                var waferReturn by rememberSaveable { mutableStateOf<Page?>(null) }
                var historyReturn by rememberSaveable { mutableStateOf(Page.DATA) }
                var overview by remember { mutableStateOf<WaferOverview?>(null) }
                var overviewError by remember { mutableStateOf<String?>(null) }
                val scrollStates = rememberSaveableStateHolder()
                val selectedTab = when (page) {
                    Page.HISTORY, Page.LOTS, Page.COMPARE, Page.TREND -> Page.DATA
                    Page.APPEARANCE, Page.LANGUAGE, Page.BACKUP, Page.GENERAL, Page.ACCOUNT, Page.ABOUT, Page.LEGAL -> Page.PROFILE
                    Page.LOGS, Page.KNOWLEDGE, Page.TRAINING -> Page.TOOLS
                    Page.PLUGINS -> if (pluginReturn in setOf(Page.AI, Page.CHAT)) Page.AI else Page.TOOLS
                    Page.TASKS -> Page.AI
                    Page.OVERVIEW -> Page.WAFER
                    Page.MODELS -> when (modelReturn) { Page.WAFER -> Page.WAFER; Page.PROFILE -> Page.PROFILE; else -> Page.AI }
                    else -> page
                }
                val canRestoreBackup = !importState.loading && !importState.predicting &&
                    !importState.exporting && !importState.analyzing && !csvState.busy
                val goBack: () -> Unit = {
                    when {
                        backup.busy -> Unit
                        aiState.draft != null -> aiViewModel.close()
                        page == Page.WAFER && waferReturn != null -> {
                            page = waferReturn ?: Page.DATA
                            waferReturn = null
                        }
                        page == Page.HISTORY -> page = if (lotId == null) historyReturn else Page.LOTS
                        page == Page.LOTS || page == Page.TREND -> page = Page.DATA
                        page == Page.CHAT -> page = chatOrigin
                        page == Page.TASKS -> page = taskReturn
                        page == Page.PLUGINS -> page = pluginReturn
                        page == Page.MODELS -> page = modelReturn
                        page == Page.COMPARE -> page = Page.HISTORY
                        page == Page.APPEARANCE -> page = themeReturn
                        page == Page.LEGAL -> page = legalReturn
                        page == Page.ACCOUNT -> page = accountReturn
                        page == Page.ABOUT -> page = Page.PROFILE
                        page in setOf(Page.BACKUP, Page.LANGUAGE, Page.GENERAL) -> page = Page.PROFILE
                        page in setOf(Page.LOGS, Page.KNOWLEDGE, Page.TRAINING) -> page = Page.TOOLS
                        else -> page = appearance.startTab.page()
                    }
                }
                val openModels: (Boolean) -> Unit = { add ->
                    modelReturn = page
                    directModelAdd = add
                    directEditorSeen = false
                    if (add) aiViewModel.open()
                    page = Page.MODELS
                }
                val openWafer: (Int) -> Unit = { id ->
                    if (importViewModel.openWafer(id)) {
                        if (page != Page.WAFER) waferReturn = page
                        if (page != Page.WAFER) scrollStates.removeState(Page.WAFER.name)
                        page = Page.WAFER
                    }
                }

                val openHistory: () -> Unit = {
                    historyReturn = page
                    lotId = null
                    lotCode = null
                    reviewingYield = false
                    yieldBand = null
                    page = Page.HISTORY
                }
                BackHandler(enabled = backup.busy || aiState.draft != null || page != appearance.startTab.page() ||
                    (page == Page.WAFER && waferReturn != null), onBack = goBack)
                LaunchedEffect(page, aiState.draft, aiState.saving, directModelAdd) {
                    if (page == Page.MODELS && directModelAdd) {
                        if (aiState.draft != null) directEditorSeen = true
                        else if (directEditorSeen && !aiState.saving) {
                            directModelAdd = false
                            page = modelReturn
                        }
                    }
                }
                LaunchedEffect(backup.restoreRevision) {
                    if (backup.restoreRevision > appliedRestore) {
                        appliedRestore = backup.restoreRevision
                        importViewModel.clearForServerChange()
                        historyViewModel.clearForServerChange()
                        lotViewModel.clearForServerChange()
                        lotId = null
                        lotCode = null
                        yieldBand = null
                        waferReturn = null
                        comparison = intArrayOf()
                        overview = null
                        listOf(Page.WAFER, Page.DATA, Page.HISTORY, Page.LOTS, Page.COMPARE)
                            .forEach { scrollStates.removeState(it.name) }
                    }
                }
                LaunchedEffect(page, lotId, importState.wafer?.id, reviewingYield, yieldBand, appearance.yieldThreshold) {
                    when (page) {
                        Page.HISTORY -> historyViewModel.show(lotId, reviewingYield, appearance.yieldThreshold, yieldBand)
                        Page.LOTS -> lotViewModel.refresh()
                        Page.DATA, Page.WAFER, Page.OVERVIEW, Page.PROFILE -> {
                            overviewError = null
                            try {
                                overview = repository.overview()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                overviewError = "无法读取本机统计。"
                            }
                        }
                        else -> Unit
                    }
                }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let(importViewModel::importFile)
                }
                var selectedImage by rememberSaveable { mutableStateOf<Uri?>(null) }
                val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    selectedImage = uri
                }
                selectedImage?.let { uri ->
                    WaferImageDialog(onDismiss = { selectedImage = null }) { options ->
                        selectedImage = null
                        importViewModel.importImage(uri, options, appearance.yieldThreshold)
                    }
                }
                val reportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
                    if (uri == null) importViewModel.cancelReportExport() else importViewModel.exportReport(uri)
                }
                val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
                    if (uri == null) historyViewModel.cancelCsv() else historyViewModel.exportCsv(uri)
                }
                val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                    if (uri == null) backupViewModel.cancel() else backupViewModel.run(uri, restore = false)
                }
                val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri == null) backupViewModel.cancel() else backupViewModel.run(uri, restore = true)
                }

                val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                val showBottomBar = page != Page.APPEARANCE && page != Page.CHAT &&
                    aiState.draft == null && !backup.busy && !(page == Page.AI && keyboardVisible)
                val overlayBottomBar = showBottomBar && appearance.bottomBar == BottomBarStyle.FLOATING &&
                    appearance.glass != GlassLevel.NONE
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(settingsSnackbar) },
                    bottomBar = {
                        if (showBottomBar) {
                            WaferBottomBar(appearance, selectedTab) {
                                waferReturn = null
                                page = it
                            }
                        }
                    }
                ) { insets ->
                    val direction = LocalLayoutDirection.current
                    val barClearance = insets.calculateBottomPadding()
                    AnimatedContent(
                        targetState = page,
                        modifier = Modifier.fillMaxSize().padding(
                            start = insets.calculateStartPadding(direction),
                            top = insets.calculateTopPadding(),
                            end = insets.calculateEndPadding(direction),
                            bottom = if (overlayBottomBar) 0.dp else barClearance
                        ).clipToBounds(),
                        transitionSpec = {
                            val from = tabs.indexOfFirst { it.first == initialState }
                            val to = tabs.indexOfFirst { it.first == targetState }
                            when {
                                from < 0 || to < 0 -> EnterTransition.None togetherWith ExitTransition.None
                                appearance.tabMotion == TabMotion.FADE ->
                                    fadeIn(tween(160, easing = tabEasing)) togetherWith fadeOut(tween(100))
                                else -> {
                                    val direction = if (to > from) 1 else -1
                                    (slideInHorizontally(tween(190, easing = tabEasing)) { direction * it / 6 } +
                                        fadeIn(tween(160, easing = tabEasing))) togetherWith
                                        (slideOutHorizontally(tween(160, easing = tabEasing)) { -direction * it / 8 } +
                                            fadeOut(tween(120)))
                                }
                            }
                        },
                        label = "wafer tabs"
                    ) { destination ->
                        // Scrolling pages extend under the glass. The chat composer needs its own safe area.
                        val drawBehindBar = overlayBottomBar && destination !in setOf(Page.AI, Page.CHAT, Page.APPEARANCE)
                        val modifier = Modifier.fillMaxSize()
                            .padding(bottom = if (overlayBottomBar && !drawBehindBar) barClearance else 0.dp)
                        CompositionLocalProvider(LocalFloatingNavigationPadding provides
                            if (drawBehindBar) barClearance else 0.dp) {
                            scrollStates.SaveableStateProvider(destination.name) {
                                when (destination) {
                                    Page.DATA -> StatisticsScreen(
                                        overview, overviewError,
                                        yieldThreshold = appearance.yieldThreshold,
                                        onOpenWafer = openWafer,
                                        onHistory = openHistory,
                                        onReviewYield = {
                                            historyReturn = Page.DATA
                                            lotId = null
                                            lotCode = null
                                            reviewingYield = true
                                            yieldBand = null
                                            page = Page.HISTORY
                                        },
                                        onYieldBand = { band ->
                                            historyReturn = Page.DATA
                                            lotId = null
                                            lotCode = null
                                            reviewingYield = false
                                            yieldBand = band
                                            page = Page.HISTORY
                                        },
                                        onLots = { page = Page.LOTS },
                                        onTrend = { page = Page.TREND },
                                        modifier = modifier
                                    )
                                    Page.TREND -> InspectionTrendScreen(repository, goBack, openWafer, modifier)
                                    Page.HISTORY -> HistoryScreen(
                                        state = historyState, onBack = goBack, onRefresh = historyViewModel::refresh,
                                        onLoadMore = historyViewModel::loadMore, onOpen = openWafer,
                                        onSearch = historyViewModel::search,
                                        onOrder = historyViewModel::orderBy,
                                        onToggleBelow = historyViewModel::toggleBelowTarget,
                                        onThreshold = { saveAppearance(appearance.copy(yieldThreshold = it)) },
                                        csv = csvState,
                                        onExportCsv = {
                                            if (historyViewModel.prepareCsv()) {
                                                runCatching { csvPicker.launch("WaferAI-晶圆统计.csv") }.onFailure {
                                                    historyViewModel.cancelCsv("当前系统无法打开文件保存窗口。")
                                                }
                                            }
                                        },
                                        onCompare = { first, second ->
                                            comparison = intArrayOf(first, second)
                                            scrollStates.removeState(Page.COMPARE.name)
                                            page = Page.COMPARE
                                        },
                                        reviewingYield = reviewingYield, lotCode = lotCode, modifier = modifier
                                    )
                                    Page.LOTS -> LotScreen(
                                        state = lotState, onBack = goBack, onRefresh = lotViewModel::refresh,
                                        onLoadMore = lotViewModel::loadMore,
                                        onOpen = { id, code ->
                                            lotId = id
                                            lotCode = code
                                            reviewingYield = false
                                            yieldBand = null
                                            page = Page.HISTORY
                                        },
                                        modifier = modifier
                                    )
                                    Page.COMPARE -> if (comparison.size == 2) {
                                        WaferCompareScreen(repository, comparison[0], comparison[1], openWafer, goBack, modifier)
                                    }
                                    Page.MODELS -> AiCenterScreen(aiState, aiViewModel, !importState.analyzing && !chatState.busy,
                                        onBack = goBack, modifier = modifier.consumeWindowInsets(insets))
                                    Page.AI, Page.CHAT -> ChatScreen(
                                        chatState, chatViewModel, aiState.library, appearance.chatProfileId,
                                        aiState.saving || importState.analyzing, importState.wafer, importState.prediction,
                                        onSelectModel = { saveAppearance(appearance.copy(chatProfileId = it)) },
                                        onAddModel = { openModels(true) }, onManageModels = { openModels(false) },
                                        onEditModel = { id ->
                                            aiState.library.profiles.find { it.id == id }?.let {
                                                openModels(false)
                                                aiViewModel.open(it)
                                            }
                                        },
                                        onOpenTasks = { taskReturn = destination; page = Page.TASKS },
                                        onOpenPlugins = { pluginReturn = destination; page = Page.PLUGINS },
                                        thinking = appearance.chatProfileId?.let(appearance.chatThinking::get) ?: ChatThinking.DEFAULT,
                                        onThinking = { effort ->
                                            appearance.chatProfileId?.let { id ->
                                                val levels = if (effort == ChatThinking.DEFAULT) appearance.chatThinking - id
                                                    else appearance.chatThinking + (id to effort)
                                                saveAppearance(appearance.copy(chatThinking = levels))
                                            }
                                        },
                                        memoryEnabled = appearance.chatMemory, onMemoryEnabled = { saveAppearance(appearance.copy(chatMemory = it)) },
                                        onBack = goBack.takeIf { destination == Page.CHAT },
                                        modifier = modifier.consumeWindowInsets(insets)
                                    )
                                    Page.TOOLS -> ToolsScreen(
                                        onLogs = { page = Page.LOGS },
                                        onKnowledge = { page = Page.KNOWLEDGE }, onTrain = { page = Page.TRAINING },
                                        onPlugins = { pluginReturn = Page.TOOLS; page = Page.PLUGINS }, modifier = modifier
                                    )
                                    Page.TRAINING -> TrainingScreen(repository, goBack, modifier)
                                    Page.PROFILE -> ProfileScreen(
                                        overview, aiState.library.profiles.size,
                                        onTheme = { themeReturn = Page.PROFILE; page = Page.APPEARANCE },
                                        onLanguage = { page = Page.LANGUAGE }, onGeneral = { page = Page.GENERAL },
                                        onBackup = { page = Page.BACKUP }, onModels = { openModels(false) },
                                        onHistory = openHistory, account = accountState.user,
                                        onAccount = { accountReturn = Page.PROFILE; page = Page.ACCOUNT },
                                        onAbout = { page = Page.ABOUT }, modifier = modifier
                                    )
                                    Page.ACCOUNT -> AccountScreen(accountState, accountViewModel, goBack,
                                        onLegal = { legalReturn = Page.ACCOUNT; legalDocument = it; page = Page.LEGAL },
                                        modifier = modifier.consumeWindowInsets(insets))
                                    Page.ABOUT -> AboutScreen(goBack, onHelp = {
                                        if (accountState.user == null) {
                                            accountReturn = Page.ABOUT
                                            page = Page.ACCOUNT
                                            accountViewModel.hint("请先登录，再打开帮助中心。")
                                        } else accountViewModel.help()
                                    }, onLegal = { legalReturn = Page.ABOUT; legalDocument = it; page = Page.LEGAL }, updates = updateViewModel, modifier = modifier)
                                    Page.LEGAL -> LegalScreen(legalDocument, goBack, modifier)
                                    Page.LOGS -> OperationLogScreen(repository.operationLog, goBack, modifier)
                                    Page.PLUGINS -> SearchPluginsScreen(container.knowledge, goBack, modifier)
                                    Page.TASKS -> AiTasksScreen(aiState.library, repository.aiProfiles, appearance.chatProfileId,
                                        container.knowledgeTasks, goBack, modifier)
                                    Page.KNOWLEDGE -> KnowledgeScreen(container.knowledge, appearance.autoKnowledge,
                                        { saveAppearance(appearance.copy(autoKnowledge = it)) }, appearance.knowledgeHours,
                                        { saveAppearance(appearance.copy(knowledgeHours = it)) },
                                        { pluginReturn = Page.TOOLS; page = Page.PLUGINS }, goBack, modifier)
                                    Page.LANGUAGE -> LanguageScreen(appearance, { saveAppearance(appearance.copy(language = it)) }, goBack, modifier)
                                    Page.GENERAL -> GeneralScreen(appearance, { saveAppearance(it) }, goBack,
                                        { themeReturn = Page.GENERAL; page = Page.APPEARANCE }, modifier)
                                    Page.BACKUP -> BackupScreen(
                                        backup.busy, canRestoreBackup, backup.message, backup.error,
                                        onExport = {
                                            backupViewModel.begin()
                                            runCatching { backupPicker.launch("WaferAI-backup.json") }
                                                .onFailure { backupViewModel.cancel("无法打开文件选择器。") }
                                        },
                                        onRestore = {
                                            if (canRestoreBackup) {
                                                backupViewModel.begin()
                                                runCatching { restorePicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                                                    .onFailure { backupViewModel.cancel("无法打开文件选择器。") }
                                            }
                                        }, onBack = goBack, modifier = modifier
                                    )
                                    Page.APPEARANCE -> AppearanceScreen(
                                        appearance = appearance,
                                        onChange = { saveAppearance(it) },
                                        onReset = { saveAppearance(Appearance(), restoreDefaults = true) },
                                        canReset = !aiState.saving && !importState.analyzing && !chatState.busy,
                                        onBack = goBack,
                                        modifier = modifier
                                    )
                                    Page.OVERVIEW, Page.WAFER -> WaferHomeScreen(
                                        importState = importState,
                                        overview = overview, onHistory = openHistory, onOpenWafer = openWafer,
                                        onBack = goBack.takeIf { waferReturn != null },
                                        onSelectFile = { picker.launch(arrayOf("*/*")) },
                                        onSelectImage = { imagePicker.launch(arrayOf("image/*")) },
                                        onPredict = { importViewModel.inspectWafer(appearance.yieldThreshold) },
                                        onPredictTrained = if (training == null) null else {
                                            { importViewModel.inspectWafer(appearance.yieldThreshold, trained = true) }
                                        },
                                        onLoadDemo = importViewModel::loadDemo,
                                        onAnalyze = importViewModel::analyzeWafer,
                                        onCancelAnalysis = importViewModel::cancelAnalysis,
                                        mapFilter = appearance.mapFilter,
                                        onMapFilterChange = { saveAppearance(appearance.copy(mapFilter = it)) },
                                        showRegionGuides = appearance.showRegionGuides,
                                        onToggleRegionGuides = {
                                            saveAppearance(appearance.copy(showRegionGuides = !appearance.showRegionGuides))
                                        },
                                        yieldThreshold = appearance.yieldThreshold,
                                        onOpenSettings = { openModels(false) },
                                        aiProfiles = aiState.library,
                                        aiSaving = aiState.saving || chatState.busy,
                                        aiError = aiState.error,
                                        onSelectAiProfile = aiViewModel::select,
                                        onChat = { chatOrigin = Page.WAFER; page = Page.CHAT },
                                        onExportReport = {
                                            importViewModel.prepareReportExport()?.let { filename ->
                                                runCatching { reportPicker.launch(filename) }.onFailure {
                                                    importViewModel.cancelReportExport("当前系统无法打开文件保存窗口。")
                                                }
                                            }
                                        },
                                        modifier = modifier
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


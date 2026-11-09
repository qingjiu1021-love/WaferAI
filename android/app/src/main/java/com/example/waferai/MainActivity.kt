package com.example.waferai

import androidx.compose.foundation.background

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
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalFocusManager
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
import com.example.waferai.ui.importdata.WaferConversionDialog
import com.example.waferai.ui.importdata.WaferFilePreview
import com.example.waferai.ui.theme.DockBackdrop
import com.example.waferai.ui.theme.GlassTabs
import com.example.waferai.ui.theme.PrimaryTabHost
import com.example.waferai.ui.theme.StartupTransition
import com.example.waferai.ui.theme.TabNavigation
import com.example.waferai.ui.theme.recordDockBackdrop
import com.example.waferai.ui.theme.rememberDockBackdrop
import com.example.waferai.ui.theme.sampleDockBackdrop
import com.example.waferai.ui.lots.LotScreen
import com.example.waferai.ui.lots.LotFailureStackScreen
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
import com.example.waferai.ui.theme.WaferAITheme
import com.example.waferai.ui.theme.glassSurface
import com.example.waferai.ui.theme.GlassLevel
import com.example.waferai.ui.theme.LocalFloatingNavigationPadding
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.mutableIntStateOf

private enum class Page { OVERVIEW, WAFER, WAFER_TEST, DATA, HISTORY, LOTS, LOT_STACK, AI, APPEARANCE, CHAT, COMPARE, TOOLS, LANGUAGE, BACKUP, GENERAL, MODELS, LOGS, KNOWLEDGE, TRAINING, TREND, PLUGINS, TASKS, PROFILE, PROFILE_EDIT, ACCOUNT, ABOUT, LEGAL }

private val tabs = listOf(
    Page.WAFER to "晶圆",
    Page.DATA to "数据",
    Page.AI to "AI",
    Page.TOOLS to "工具",
    Page.PROFILE to "我的"
)

private val tabLabels = tabs.map { it.second }
private val primaryPages = tabs.map { it.first }

@Composable
private fun PageEffects(page: State<Page>, effects: @Composable (Page) -> Unit) {
    effects(page.value)
}

private fun StartTab.page(): Page = when (this) {
    StartTab.WAFER -> Page.WAFER
    StartTab.DATA -> Page.DATA
    StartTab.AI -> Page.AI
    StartTab.TOOLS -> Page.TOOLS
    StartTab.PROFILE -> Page.PROFILE
}

@Composable
private fun WaferBottomBar(
    appearance: Appearance,
    selectedTab: Page,
    backdrop: DockBackdrop?,
    onSelect: (Page) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    val floating = appearance.bottomBar == BottomBarStyle.FLOATING
    val base = Modifier.fillMaxWidth().windowInsetsPadding(NavigationBarDefaults.windowInsets)
    val surface = if (floating) {
        Modifier.clip(shape)
            .sampleDockBackdrop(backdrop, appearance.glass)
            .glassSurface(scheme.surfaceContainer, scheme.primary, shape,
                appearance.glass, scheme.surface.luminance() < 0.4f, appearance.navOpacity / 100f)
    } else {
        Modifier.background(scheme.surfaceContainer)
    }
    GlassTabs(
        labels = tabLabels,
        selectedIndex = tabs.indexOfFirst { it.first == selectedTab }.coerceAtLeast(0),
        onSelect = { onSelect(tabs[it].first) },
        modifier = if (floating) base.padding(horizontal = 21.dp, vertical = 8.dp) else base,
        surface = surface,
        floating = floating,
        dragEnabled = true,
        onPressChanged = { backdrop?.pressed = it },
        icon = { index, color -> TabIcon(index, color) }
    )
}

class MainActivity : ComponentActivity() {
    private val accountViewModel: AccountViewModel by viewModels()
    private val userProfileViewModel: com.example.waferai.ui.settings.UserProfileViewModel by viewModels()
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
    private val waferTestViewModel: com.example.waferai.ui.importdata.WaferTestViewModel by viewModels {
        com.example.waferai.ui.importdata.WaferTestViewModel.Factory(repository)
    }
    private val backupViewModel: BackupViewModel by viewModels { BackupViewModel.Factory(repository) }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val animateLaunch = !launchShown && savedInstanceState == null && ValueAnimator.areAnimatorsEnabled()
        launchShown = true
        var launchVisible by mutableStateOf(animateLaunch)
        if (animateLaunch) WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        splashScreen.setOnExitAnimationListener { it.remove() }
        val appContent: @Composable () -> Unit = {
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
            WaferAITheme(appearance) {
                val lightBars = !launchVisible && MaterialTheme.colorScheme.background.luminance() > 0.5f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }

                val importState by importViewModel.state.collectAsStateWithLifecycle()
                val guideState by importViewModel.guide.collectAsStateWithLifecycle()
                val historyState by historyViewModel.state.collectAsStateWithLifecycle()
                val lotState by lotViewModel.state.collectAsStateWithLifecycle()
                val aiState by aiViewModel.state.collectAsStateWithLifecycle()
                val chatBusy by chatViewModel.busy.collectAsStateWithLifecycle()
                val accountState by accountViewModel.state.collectAsStateWithLifecycle()
                val userProfileState by userProfileViewModel.state.collectAsStateWithLifecycle()
                LaunchedEffect(accountState.endpoint, accountState.user?.id) { userProfileViewModel.select(accountState.endpoint, accountState.user?.id) }
                LaunchedEffect(accountState.browser) {
                    accountState.browser?.let { url ->
                        runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                            .onSuccess { accountViewModel.browserOpened() }.onFailure { accountViewModel.browserFailed() }
                    }
                }
                val csvState by historyViewModel.csv.collectAsStateWithLifecycle()
                val backup by backupViewModel.state.collectAsStateWithLifecycle()
                val training by repository.trainingSummary.collectAsStateWithLifecycle()
                val waferRevision by repository.store.waferRevision.collectAsStateWithLifecycle()
                var appliedRestore by rememberSaveable { mutableIntStateOf(0) }
                var waferScreenRevision by rememberSaveable { mutableIntStateOf(0) }
                val pageState = rememberSaveable { mutableStateOf(appearance.startTab.page()) }
                val navigationScope = rememberCoroutineScope()
                val navigation = remember(pageState, navigationScope) {
                    TabNavigation(pageState, primaryPages, navigationScope)
                }
                val page by navigation.page
                var accountReturn by rememberSaveable { mutableStateOf(Page.PROFILE) }
                var legalReturn by rememberSaveable { mutableStateOf(Page.ABOUT) }
                var legalDocument by rememberSaveable { mutableStateOf("terms") }
                var themeReturn by rememberSaveable { mutableStateOf(Page.PROFILE) }
                var chatOrigin by rememberSaveable { mutableStateOf(Page.AI) }
                var taskReturn by rememberSaveable { mutableStateOf(Page.AI) }
                var pluginReturn by rememberSaveable { mutableStateOf(Page.TOOLS) }
                var modelReturn by rememberSaveable { mutableStateOf(Page.AI) }
                var directModelAdd by rememberSaveable { mutableStateOf(false) }
                var directEditorSeen by rememberSaveable { mutableStateOf(false) }
                var comparison by rememberSaveable { mutableStateOf(intArrayOf()) }
                var stackSelection by rememberSaveable { mutableStateOf(intArrayOf()) }
                var lotId by rememberSaveable { mutableStateOf<Int?>(null) }
                var lotCode by rememberSaveable { mutableStateOf<String?>(null) }
                var reviewingYield by rememberSaveable { mutableStateOf(false) }
                var yieldBand by rememberSaveable { mutableStateOf<WaferYieldBand?>(null) }
                var waferReturn by rememberSaveable { mutableStateOf<Page?>(null) }
                var historyReturn by rememberSaveable { mutableStateOf(Page.DATA) }
                var overview by remember { mutableStateOf<WaferOverview?>(null) }
                var overviewError by remember { mutableStateOf<String?>(null) }
                val scrollStates = rememberSaveableStateHolder()
                val selectedTab = remember {
                    derivedStateOf {
                        when (val current = page) {
                            Page.HISTORY, Page.LOTS, Page.LOT_STACK, Page.COMPARE, Page.TREND -> Page.DATA
                            Page.APPEARANCE, Page.LANGUAGE, Page.BACKUP, Page.GENERAL, Page.ACCOUNT,
                            Page.ABOUT, Page.LEGAL, Page.PROFILE_EDIT -> Page.PROFILE
                            Page.LOGS, Page.KNOWLEDGE, Page.TRAINING -> Page.TOOLS
                            Page.PLUGINS -> if (pluginReturn == Page.AI || pluginReturn == Page.CHAT) Page.AI else Page.TOOLS
                            Page.TASKS -> Page.AI
                            Page.OVERVIEW, Page.WAFER_TEST -> Page.WAFER
                            Page.MODELS -> when (modelReturn) {
                                Page.WAFER -> Page.WAFER
                                Page.PROFILE -> Page.PROFILE
                                else -> Page.AI
                            }
                            else -> current
                        }
                    }
                }
                val canRestoreBackup = !importState.loading && !importState.predicting &&
                    !importState.exporting && !importState.analyzing && !csvState.busy
                val goBack: () -> Unit = {
                    when {
                        backup.busy -> Unit
                        aiState.draft != null -> aiViewModel.close()
                        page == Page.WAFER_TEST -> navigation.navigate(Page.WAFER)
                        page == Page.WAFER && waferReturn != null -> {
                            navigation.navigate(waferReturn ?: Page.DATA)
                            waferReturn = null
                        }
                        (page == Page.WAFER || page == Page.OVERVIEW) && importState.wafer != null -> {
                            if (importViewModel.returnToWorkspace()) {
                                waferScreenRevision++
                                scrollStates.removeState(page.name)
                            }
                        }
                        page == Page.HISTORY -> navigation.navigate(if (lotId == null) historyReturn else Page.LOTS)
                        page == Page.LOTS || page == Page.TREND -> navigation.navigate(Page.DATA)
                        page == Page.CHAT -> navigation.navigate(chatOrigin)
                        page == Page.TASKS -> navigation.navigate(taskReturn)
                        page == Page.PLUGINS -> navigation.navigate(pluginReturn)
                        page == Page.MODELS -> navigation.navigate(modelReturn)
                        page == Page.COMPARE || page == Page.LOT_STACK -> navigation.navigate(Page.HISTORY)
                        page == Page.APPEARANCE -> navigation.navigate(themeReturn)
                        page == Page.LEGAL -> navigation.navigate(legalReturn)
                        page == Page.ACCOUNT -> navigation.navigate(accountReturn)
                        page == Page.ABOUT -> navigation.navigate(Page.PROFILE)
                        page in setOf(Page.BACKUP, Page.LANGUAGE, Page.GENERAL, Page.PROFILE_EDIT) -> navigation.navigate(Page.PROFILE)
                        page in setOf(Page.LOGS, Page.KNOWLEDGE, Page.TRAINING) -> navigation.navigate(Page.TOOLS)
                        else -> navigation.navigate(appearance.startTab.page())
                    }
                }
                val openModels: (Boolean) -> Unit = { add ->
                    modelReturn = page
                    directModelAdd = add
                    directEditorSeen = false
                    if (add) aiViewModel.open()
                    navigation.navigate(Page.MODELS)
                }
                val openWafer: (Int) -> Unit = { id ->
                    if (importViewModel.openWafer(id)) {
                        if (page != Page.WAFER) waferReturn = page
                        if (page != Page.WAFER) {
                            scrollStates.removeState(Page.WAFER.name)
                            waferScreenRevision++
                        }
                        navigation.navigate(Page.WAFER)
                    }
                }

                val openHistory: () -> Unit = {
                    historyReturn = page
                    lotId = null
                    lotCode = null
                    reviewingYield = false
                    yieldBand = null
                    navigation.navigate(Page.HISTORY)
                }
                PageEffects(pageState) { current ->
                    BackHandler(enabled = backup.busy || aiState.draft != null || current != appearance.startTab.page() ||
                        (current == Page.WAFER && (waferReturn != null || importState.wafer != null)), onBack = goBack)
                    LaunchedEffect(accountState.message, current) {
                        if (current != Page.ACCOUNT && accountState.error) {
                            accountState.message?.let { settingsSnackbar.showSnackbar(it) }
                        }
                    }
                    LaunchedEffect(current, aiState.draft, aiState.saving, directModelAdd) {
                        if (current == Page.MODELS && directModelAdd) {
                            if (aiState.draft != null) directEditorSeen = true
                            else if (directEditorSeen && !aiState.saving) {
                                directModelAdd = false
                                navigation.navigate(modelReturn)
                            }
                        }
                    }
                    LaunchedEffect(current, lotId, reviewingYield, yieldBand, appearance.yieldThreshold) {
                        when (current) {
                            Page.HISTORY -> historyViewModel.show(lotId, reviewingYield, appearance.yieldThreshold, yieldBand)
                            Page.LOTS -> lotViewModel.refresh()
                            else -> Unit
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
                        waferScreenRevision++
                        listOf(Page.WAFER, Page.DATA, Page.HISTORY, Page.LOTS, Page.COMPARE, Page.LOT_STACK)
                            .forEach { scrollStates.removeState(it.name) }
                    }
                }
                LaunchedEffect(waferRevision) {
                    try {
                        overview = repository.overview()
                        overviewError = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        overviewError = "无法读取本机统计。"
                    }
                }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let(importViewModel::previewFile)
                }
                val guideJsonSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                    uri?.let { importViewModel.saveGuideFile(it, com.example.waferai.data.local.GuideFile.JSON) }
                }
                val guideCsvSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
                    uri?.let { importViewModel.saveGuideFile(it, com.example.waferai.data.local.GuideFile.CSV) }
                }
                val guideImageSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
                    uri?.let { importViewModel.saveGuideFile(it, com.example.waferai.data.local.GuideFile.IMAGE) }
                }
                val saveGuideFile: (com.example.waferai.data.local.GuideFile) -> Unit = { file ->
                    when (file) {
                        com.example.waferai.data.local.GuideFile.JSON -> guideJsonSaver.launch(file.filename)
                        com.example.waferai.data.local.GuideFile.CSV -> guideCsvSaver.launch(file.filename)
                        com.example.waferai.data.local.GuideFile.IMAGE -> guideImageSaver.launch(file.filename)
                    }
                }
                var selectedImage by rememberSaveable { mutableStateOf<Uri?>(null) }
                val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    selectedImage = uri
                }
                selectedImage?.let { uri ->
                    WaferImageDialog(onDismiss = { selectedImage = null }) { options ->
                        selectedImage = null
                        waferReturn = null
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

                val ime = WindowInsets.ime
                val density = LocalDensity.current
                val showBottomBar = remember(ime, density) {
                    derivedStateOf {
                        val current = page
                        current != Page.APPEARANCE && current != Page.CHAT && aiState.draft == null &&
                            !backup.busy && !(current in setOf(Page.AI, Page.WAFER_TEST) && ime.getBottom(density) > 0)
                    }
                }
                val floatingGlass = appearance.bottomBar == BottomBarStyle.FLOATING &&
                    appearance.glass != GlassLevel.NONE
                val backdrop = rememberDockBackdrop(floatingGlass && !launchVisible, navigation.moving)
                val pageBackground = MaterialTheme.colorScheme.background
                val focus = LocalFocusManager.current
                WaferConversionDialog(importViewModel)
                importState.filePreview?.let {
                    WaferFilePreview(it, onConfirm = {
                        waferReturn = null
                        navigation.navigate(Page.WAFER)
                        importViewModel.confirmFileImport()
                    }, onDismiss = importViewModel::dismissFilePreview)
                }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(settingsSnackbar) },
                    bottomBar = {
                        if (showBottomBar.value) {
                            WaferBottomBar(appearance, selectedTab.value, backdrop) {
                                if (page == Page.AI && it != Page.AI) focus.clearFocus()
                                waferReturn = null
                                navigation.navigate(it)
                            }
                        }
                    }
                ) { insets ->
                    val direction = LocalLayoutDirection.current
                    val barClearance = insets.calculateBottomPadding()
                    val overlayBottomBar = showBottomBar.value && floatingGlass
                    PrimaryTabHost(
                        navigation = navigation,
                        motion = appearance.tabMotion,
                        prepareTabs = !launchVisible,
                        modifier = Modifier.fillMaxSize().padding(
                            start = insets.calculateStartPadding(direction),
                            top = insets.calculateTopPadding(),
                            end = insets.calculateEndPadding(direction),
                            bottom = if (overlayBottomBar) 0.dp else barClearance
                        ).clipToBounds().recordDockBackdrop(backdrop.takeIf { overlayBottomBar }, pageBackground)
                    ) { destination ->
                        // Scrolling pages extend under the glass. The chat composer needs its own safe area.
                        val drawBehindBar = overlayBottomBar && destination !in setOf(Page.AI, Page.CHAT, Page.APPEARANCE)
                        val modifier = Modifier.fillMaxSize()
                            .padding(bottom = if (overlayBottomBar && !drawBehindBar) barClearance else 0.dp)
                        CompositionLocalProvider(LocalFloatingNavigationPadding provides
                            if (drawBehindBar) barClearance else 0.dp) {
                            val revision = when (destination) {
                                Page.WAFER -> waferScreenRevision
                                Page.DATA -> appliedRestore
                                else -> 0
                            }
                            key(revision) { scrollStates.SaveableStateProvider(destination.name) {
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
                                            navigation.navigate(Page.HISTORY)
                                        },
                                        onYieldBand = { band ->
                                            historyReturn = Page.DATA
                                            lotId = null
                                            lotCode = null
                                            reviewingYield = false
                                            yieldBand = band
                                            navigation.navigate(Page.HISTORY)
                                        },
                                        onLots = { navigation.navigate(Page.LOTS) },
                                        onTrend = { navigation.navigate(Page.TREND) },
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
                                            navigation.navigate(Page.COMPARE)
                                        },
                                        reviewingYield = reviewingYield, lotCode = lotCode, modifier = modifier,
                                        onStack = if (lotId != null) { ids ->
                                            stackSelection = ids
                                            scrollStates.removeState(Page.LOT_STACK.name)
                                            navigation.navigate(Page.LOT_STACK)
                                        } else null
                                    )
                                    Page.LOT_STACK -> lotId?.let { id ->
                                        LotFailureStackScreen(repository, id, lotCode.orEmpty(), stackSelection,
                                            goBack, openWafer, modifier)
                                    } ?: Unit
                                    Page.LOTS -> LotScreen(
                                        state = lotState, onBack = goBack, onRefresh = lotViewModel::refresh,
                                        onLoadMore = lotViewModel::loadMore,
                                        onOpen = { id, code ->
                                            lotId = id
                                            lotCode = code
                                            reviewingYield = false
                                            yieldBand = null
                                            navigation.navigate(Page.HISTORY)
                                        },
                                        modifier = modifier
                                    )
                                    Page.COMPARE -> if (comparison.size == 2) {
                                        WaferCompareScreen(repository, comparison[0], comparison[1], openWafer, goBack, modifier)
                                    }
                                    Page.MODELS -> AiCenterScreen(aiState, aiViewModel, !importState.analyzing && !chatBusy,
                                        onBack = goBack, modifier = modifier.consumeWindowInsets(insets))
                                    Page.AI, Page.CHAT -> ChatScreen(
                                        chatViewModel, aiState.library, appearance.chatProfileId,
                                        aiState.saving || importState.analyzing, importState.wafer, importState.prediction,
                                        onSelectModel = { saveAppearance(appearance.copy(chatProfileId = it)) },
                                        onAddModel = { openModels(true) }, onManageModels = { openModels(false) },
                                        onEditModel = { id ->
                                            aiState.library.profiles.find { it.id == id }?.let {
                                                openModels(false)
                                                aiViewModel.open(it)
                                            }
                                        },
                                        onOpenTasks = { taskReturn = destination; navigation.navigate(Page.TASKS) },
                                        onOpenPlugins = { pluginReturn = destination; navigation.navigate(Page.PLUGINS) },
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
                                        active = pageState.value == destination,
                                        modifier = modifier.consumeWindowInsets(insets)
                                    )
                                    Page.TOOLS -> ToolsScreen(
                                        onLogs = { navigation.navigate(Page.LOGS) },
                                        onKnowledge = { navigation.navigate(Page.KNOWLEDGE) }, onTrain = { navigation.navigate(Page.TRAINING) },
                                        onPlugins = { pluginReturn = Page.TOOLS; navigation.navigate(Page.PLUGINS) }, modifier = modifier
                                    )
                                    Page.TRAINING -> TrainingScreen(repository, goBack, modifier)
                                    Page.PROFILE -> ProfileScreen(
                                        overview, aiState.library.profiles.size,
                                        onTheme = { themeReturn = Page.PROFILE; navigation.navigate(Page.APPEARANCE) },
                                        onLanguage = { navigation.navigate(Page.LANGUAGE) }, onGeneral = { navigation.navigate(Page.GENERAL) },
                                        onBackup = { navigation.navigate(Page.BACKUP) }, onModels = { openModels(false) },
                                        onHistory = openHistory, account = accountState.user,
                                        onAccount = { accountReturn = Page.PROFILE; navigation.navigate(Page.ACCOUNT) },
                                        profile = userProfileState.profile, onEditProfile = { navigation.navigate(Page.PROFILE_EDIT) },
                                        onAbout = { navigation.navigate(Page.ABOUT) }, modifier = modifier
                                    )
                                    Page.PROFILE_EDIT -> com.example.waferai.ui.settings.UserProfileScreen(userProfileState, userProfileViewModel,
                                        accountState.user?.name ?: "访客", goBack, modifier)
                                    Page.ACCOUNT -> AccountScreen(accountState, accountViewModel, goBack,
                                        onLegal = { legalReturn = Page.ACCOUNT; legalDocument = it; navigation.navigate(Page.LEGAL) },
                                        modifier = modifier.consumeWindowInsets(insets))
                                    Page.ABOUT -> AboutScreen(goBack, onHelp = {
                                        if (accountState.user == null) {
                                            accountReturn = Page.ABOUT
                                            navigation.navigate(Page.ACCOUNT)
                                            accountViewModel.hint("请先登录，再打开帮助中心。")
                                        } else accountViewModel.help()
                                    }, onLegal = { legalReturn = Page.ABOUT; legalDocument = it; navigation.navigate(Page.LEGAL) }, updates = updateViewModel, modifier = modifier)
                                    Page.LEGAL -> LegalScreen(legalDocument, goBack, modifier)
                                    Page.LOGS -> OperationLogScreen(repository.operationLog, goBack, modifier)
                                    Page.PLUGINS -> SearchPluginsScreen(container.knowledge, goBack, modifier)
                                    Page.TASKS -> AiTasksScreen(aiState.library, repository.aiProfiles, appearance.chatProfileId,
                                        container.knowledgeTasks, goBack, modifier)
                                    Page.KNOWLEDGE -> KnowledgeScreen(container.knowledge, appearance.autoKnowledge,
                                        { saveAppearance(appearance.copy(autoKnowledge = it)) }, appearance.knowledgeHours,
                                        { saveAppearance(appearance.copy(knowledgeHours = it)) },
                                        { pluginReturn = Page.TOOLS; navigation.navigate(Page.PLUGINS) }, goBack, modifier)
                                    Page.LANGUAGE -> LanguageScreen(appearance, { saveAppearance(appearance.copy(language = it)) }, goBack, modifier)
                                    Page.GENERAL -> GeneralScreen(appearance, { saveAppearance(it) }, goBack,
                                        { themeReturn = Page.GENERAL; navigation.navigate(Page.APPEARANCE) }, modifier)
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
                                        canReset = !aiState.saving && !importState.analyzing && !chatBusy,
                                        onBack = goBack,
                                        modifier = modifier
                                    )
                                    Page.WAFER_TEST -> com.example.waferai.ui.importdata.WaferTestScreen(
                                        waferTestViewModel, importState.wafer, goBack, modifier
                                    )
                                    Page.OVERVIEW, Page.WAFER -> WaferHomeScreen(
                                        importState = importState,
                                        onOpenConverter = importViewModel::openConversion,
                                        onOpenTest = { navigation.navigate(Page.WAFER_TEST) },
                                        tabMotion = appearance.tabMotion,
                                        guideState = guideState, onSaveGuideFile = saveGuideFile,
                                        overview = overview, onHistory = openHistory, onOpenWafer = openWafer,
                                        onBack = goBack.takeIf { waferReturn != null || importState.wafer != null },
                                        onSelectFile = { picker.launch(arrayOf("*/*")) },
                                        onSelectImage = { imagePicker.launch(arrayOf("image/*")) },
                                        onPredict = { importViewModel.inspectWafer(appearance.yieldThreshold) },
                                        onPredictTrained = if (training == null) null else {
                                            { importViewModel.inspectWafer(appearance.yieldThreshold, trained = true) }
                                        },
                                        onLoadDemo = { sample ->
                                            waferReturn = null
                                            importViewModel.loadDemo(sample)
                                        },
                                        onAnalyze = { importViewModel.analyzeWafer(appearance.analysisPreset, appearance.yieldThreshold) },
                                        analysisPreset = appearance.analysisPreset,
                                        onAnalysisPreset = { saveAppearance(appearance.copy(analysisPreset = it)) },
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
                                        aiSaving = aiState.saving || chatBusy,
                                        aiError = aiState.error,
                                        onSelectAiProfile = aiViewModel::select,
                                        onChat = { chatOrigin = Page.WAFER; navigation.navigate(Page.CHAT) },
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
                            } }
                        }
                    }
                }
            }
        }
        setContent {
            StartupTransition(animateLaunch, onFinished = { launchVisible = false }, content = appContent)
        }
    }
}


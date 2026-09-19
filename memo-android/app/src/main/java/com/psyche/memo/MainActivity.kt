package com.psyche.memo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.psyche.memo.common.AppLocale
import com.psyche.memo.data.assistant.AssistantStore
import com.psyche.memo.data.assistant.buildSeedAssistants
import com.psyche.memo.ui.AssistantDetailSectionScreen
import com.psyche.memo.ui.AssistantSettingsEditScreen
import com.psyche.memo.ui.AssistantSettingsScreen
import com.psyche.memo.ui.AssistantTabLayoutScreen
import com.psyche.memo.ui.R as UiR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.psyche.memo.ui.DefaultModelScreen
import com.psyche.memo.ui.StatsScreen
import com.psyche.memo.ui.DisplaySettingsScreen
import com.psyche.memo.ui.HomeScreen
import com.psyche.memo.ui.ProviderDetailScreen
import com.psyche.memo.ui.ProvidersScreen
import com.psyche.memo.ui.SearchServicesScreen
import com.psyche.memo.ui.GenerationServicesScreen
import com.psyche.memo.ui.McpServersScreen
import com.psyche.memo.ui.MemoryAboutScreen
import com.psyche.memo.ui.InstructionInjectionScreen
import com.psyche.memo.ui.QuickPhrasesScreen
import com.psyche.memo.ui.TagsManagerScreen
import com.psyche.memo.ui.TranslateScreen
import com.psyche.memo.ui.WorldBookScreen
import com.psyche.memo.ui.ProviderEditScreen
import com.psyche.memo.ui.ChatHistoryScreen
import com.psyche.memo.ui.ImageSettingsScreen
import com.psyche.memo.ui.MessageStyleSettingsScreen
import com.psyche.memo.ui.AutoRetrySettingsScreen
import com.psyche.memo.ui.HapticsSettingsScreen
import com.psyche.memo.ui.ProvideHapticsSettings
import com.psyche.memo.ui.ChatItemDisplaySettingsScreen
import com.psyche.memo.ui.RenderingSettingsScreen
import com.psyche.memo.ui.BehaviorStartupSettingsScreen
import com.psyche.memo.ui.AboutScreen
import com.psyche.memo.ui.MoreScreen
import com.psyche.memo.ui.StorageCategoryScreen
import com.psyche.memo.ui.StorageSpaceScreen
import com.psyche.memo.ui.StorageCategoryKey
import com.psyche.memo.ui.ThemeAdvancedScreen
import com.psyche.memo.ui.ThemeState
import com.psyche.memo.ui.ThemeSettingsScreen
import com.psyche.memo.ui.UserProfileScreen
import com.psyche.memo.ui.SettingsScreen
import com.psyche.memo.ui.SkillsScreen
import com.psyche.memo.ui.WorkspaceScreen
import com.psyche.memo.ui.WorkspaceDetailScreen
import com.psyche.memo.ui.WorkspaceTerminalScreen
import com.psyche.memo.ui.SkillDetailScreen
import com.psyche.memo.ui.DebugScreen
import com.psyche.memo.ui.BackupScreen
import com.psyche.memo.ui.WebDavSettingsScreen
import com.psyche.memo.ui.S3SettingsScreen
import com.psyche.memo.ui.LocalSnapshotsScreen
import com.psyche.memo.ui.SponsorScreen
import com.psyche.memo.ui.LogViewerScreen
import com.psyche.memo.ui.MemoryEntriesScreen
import com.psyche.memo.ui.MemorySettingsScreen
import com.psyche.memo.ui.MemoryTraceScreen
import com.psyche.memo.ui.NetworkProxyScreen
import com.psyche.memo.ui.ToolSchemaEditorScreen
import com.psyche.memo.ui.ToolSchemaSettingsScreen
import com.psyche.memo.ui.TtsServicesScreen
import com.psyche.memo.ui.TtsServicesEditorScreen
import com.psyche.memo.ui.AsrServicesEditorScreen
import com.psyche.memo.ui.TtsSettingsScreen
import com.psyche.memo.ui.locale.withAppLocale
import com.psyche.memo.ui.theme.MemoTheme
import com.psyche.memo.ui.theme.ProvideSemanticColors
import com.psyche.memo.ui.theme.withFontFamily

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        // Apply the stored language before any resource is resolved, so a cold
        // start (and every activity re-attach) already loads the right
        // strings.xml. The Compose layer re-applies it so switching in settings
        // takes effect immediately instead of after a restart.
        val stored = MemoApplication.instance?.container?.appLocaleStore?.read()
        super.attachBaseContext(newBase.withAppLocale(stored ?: AppLocale.DEFAULT))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge + windowLayoutInDisplayCutoutMode=always: the content
        // (page surface) extends behind the camera cutout so the OEM never
        // paints its black strip over the top of the app.
        enableEdgeToEdge()
        // Cold-start completion-notification tap: the launch intent carries
        // the conversation id (ChatBackgroundController.EXTRA_CONVERSATION_ID).
        deliverNotificationConversation(intent)
        setContent {
            MemoApp()
        }
    }

    override fun onStart() {
        super.onStart()
        // 回前台再让调度跑一次（原版是 launch + resume 两处，时间基判定）。
        MemoApplication.instance?.container?.maybeRunLocalSnapshot()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // singleTop launchMode + notification PendingIntent(CLEAR_TOP|SINGLE_TOP):
        // taps while the app is alive land here.
        deliverNotificationConversation(intent)
    }

    private fun deliverNotificationConversation(intent: android.content.Intent?) {
        intent?.getStringExtra(
            com.psyche.memo.service.ChatBackgroundController.EXTRA_CONVERSATION_ID,
        )?.let {
            com.psyche.memo.service.ChatBackgroundController.pendingOpenConversationId.value = it
        }
    }
}

@Composable
fun MemoApp() {
    val context = LocalContext.current
    val container = rememberAppContainer(context)
    // Mirrors Flutter's MaterialApp(locale: settings.appLocaleForMaterialApp):
    // SYSTEM leaves the platform's language alone, anything else overrides it.
    //
    // 语言切换走「写入 + recreate()」，由 attachBaseContext 在重建时套用新 locale。
    // **绝不能**在这里用 CompositionLocalProvider 替换 LocalContext：Compose 从
    // LocalContext 派生 ActivityResultRegistryOwner，包装后的 Context 不是
    // Activity，会让所有 rememberLauncherForActivityResult 抛
    // 「No ActivityResultRegistryOwner was provided」（聊天页导出/相册、头像编辑器
    // 全在内）——语言一切到具体值就必崩。
    AppThemeAndContent(
        container = container,
        appLocale = container.appLocaleStore.read(),
        onLocaleChange = { locale ->
            container.appLocaleStore.write(locale)
            (context as? android.app.Activity)?.recreate()
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppThemeAndContent(
    container: AppContainerImpl,
    appLocale: AppLocale,
    onLocaleChange: (AppLocale) -> Unit,
) {
    // Theme from preferences (theme_mode_v1 / theme_palette_v1) observed
    // reactively through ThemeState, so color mode / palette / advanced
    // switches take effect immediately (no restart). The default is system
    // (mirrors Flutter SettingsProvider.themeMode default); Compose
    // isSystemInDarkTheme is Flutter platformBrightness's equivalent.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ThemeState.load(container)
        // assistant_provider.dart ensureDefaults — seed the localized default
        // + sample assistants exactly once, then default currentAssistantId
        // to the first seed. Runs after locale resolution (attachBaseContext).
        launch {
            withContext(Dispatchers.IO) {
                val store = AssistantStore(container.database.writableDatabase)
                if (store.isEmpty()) {
                    val seeds = buildSeedAssistants(
                        defaultName = context.getString(UiR.string.assistant_provider_default_assistant_name),
                        sampleName = context.getString(UiR.string.assistant_provider_sample_assistant_name),
                        samplePrompt = context.getString(
                            UiR.string.assistant_provider_sample_assistant_system_prompt,
                            "{model_name}",
                        ),
                        newId = { java.util.UUID.randomUUID().toString() },
                    )
                    store.seedAll(seeds)
                    val pref = container.preferenceRepository
                    if (pref.readJson("current_assistant_id_v1").isNullOrBlank()) {
                        pref.writeJson("current_assistant_id_v1", "\"" + seeds.first().id + "\"")
                    }
                }
                container.refreshCurrentAssistant()
            }
        }
    }
    val themeMode = ThemeState.mode
    val systemDark = isSystemInDarkTheme()
    // MemoTheme.resolve's mode logic (system/light/dark); the palette comes
    // from ThemeState so custom themes resolve without re-reading prefs.
    val dark = remember(themeMode, systemDark) {
        MemoTheme.resolve("default", themeMode, systemDark).second
    }
    val resolvedPalette = remember(ThemeState.paletteId, ThemeState.selectedCustomThemeId, ThemeState.customThemes) {
        ThemeState.resolvePalette()
    }
    val colorScheme = if (ThemeState.useDynamicColor && android.os.Build.VERSION.SDK_INT >= 31) {
        // use_dynamic_color_v1: Material You scheme, then the same
        // pure-background / page-surface / derived-container pipeline.
        val dynamic = if (dark) {
            androidx.compose.material3.dynamicDarkColorScheme(context)
        } else {
            androidx.compose.material3.dynamicLightColorScheme(context)
        }
        MemoTheme.withDerivedSurfaceContainers(
            MemoTheme.applyPageSurface(
                dynamic,
                dark,
                ThemeState.usePureBackground,
                ThemeState.useLayeredSurfaces,
            ),
            dark,
            ThemeState.useLayeredSurfaces,
        )
    } else if (resolvedPalette.id in com.psyche.memo.ui.theme.authoredSurfacePaletteIds) {
        // RikkaHub 预设：「原样表面」通道 —— 预设自带完整中性阶梯，主题才铺满整个界面
        // （用户 2026-09-13「我们要更 rikkhub 一样覆盖多，不然主题不好看」，见 §4-44）。
        MemoTheme.authoredColorScheme(
            resolvedPalette,
            dark,
            ThemeState.usePureBackground,
        )
    } else {
        MemoTheme.colorScheme(
            resolvedPalette,
            dark,
            ThemeState.usePureBackground,
            ThemeState.useLayeredSurfaces,
        )
    }
    // 预设通道下语义 token 也按预设的角色取值（卡片=surfaceContainer 等）。
    val authoredSurfaces = !ThemeState.useDynamicColor &&
        resolvedPalette.id in com.psyche.memo.ui.theme.authoredSurfacePaletteIds
    // 显示设置 → 字体：App 字体刷到 Typography 的 15 个槽位（原版 main.dart `applyAppFont`），
    // 代码字体通过 core:ui 的 CompositionLocal 传给代码块/内联 code。
    val appTypography = remember(ThemeState.appFontFamily) {
        val family = ThemeState.appFontFamily
        if (family == null) {
            androidx.compose.material3.Typography()
        } else {
            androidx.compose.material3.Typography()
                .withFontFamily(family)
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = appTypography,
    ) {
        // Material3's LocalContentColor default is black; without this every
        // Text that relies on the inherited content color renders black (fine
        // in light mode, invisible on dark surfaces).
        CompositionLocalProvider(
            LocalRippleConfiguration provides null,
            LocalContentColor provides colorScheme.onSurface,
            com.psyche.memo.ui.markdown.LocalMarkdownCodeFont provides ThemeState.codeFontFamily,
        ) {
        // Memo paints through semantic tokens (surfaceCard / hairline), not
        // raw Material roles — see lib/theme/app_semantic_colors.dart.
        ProvideSemanticColors(scheme = colorScheme, dark = dark, authored = authoredSurfaces) {
        // settings_provider.dart L1120-1129: the haptics flags live in prefs and
        // the global one is pushed into the Haptics service on load.
        ProvideHapticsSettings(container = container) {
            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (view.context as android.app.Activity).window
                    androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
            }
            // Match Memo's SystemUiOverlayStyle: status + navigation bar
            // icons follow the theme (light icons on dark, dark icons on light)
            // while both bars stay transparent (edge-to-edge).
            // Opacity-free root background: fills the window (including the
            // status/navigation bar regions) with the theme surface so the
            // transparent system bars never reveal a black strip.
            Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            ) {
                com.psyche.memo.ui.snackbar.AppSnackBarOverlay {
                val navController = rememberNavController()
                val pendingOpenConversation = remember {
                    androidx.compose.runtime.mutableStateOf<String?>(null)
                }
                // Completion-notification taps land in the controller's
                // pendingOpenConversationId (MainActivity.onNewIntent/onCreate)
                // and are forwarded into the HomeScreen pipeline — the same
                // entry point ChatHistoryScreen's onOpenConversation uses.
                LaunchedEffect(Unit) {
                    com.psyche.memo.service.ChatBackgroundController
                        .pendingOpenConversationId.collect { id ->
                            if (id != null) {
                                pendingOpenConversation.value = id
                                com.psyche.memo.service.ChatBackgroundController
                                    .pendingOpenConversationId.value = null
                            }
                        }
                }
                NavHost(
                    navController = navController,
                    startDestination = "home",
                    modifier = Modifier.fillMaxSize(),
                    // Flutter's current Android default page transition
                    // (PredictiveBackPageTransitionsBuilder falls back to
                    // FadeForwardsPageTransitionsBuilder,
                    // page_transitions_theme.dart L451-510): 450ms; the new
                    // page slides in from 25% of its width while fading in
                    // over the first 75%, the page behind slides 25% left and
                    // fades out over the first 25%; popping mirrors both.
                    enterTransition = {
                        slideInHorizontally(
                            initialOffsetX = { it / 4 },
                            animationSpec = tween(FADE_FORWARDS_MS, easing = EmphasizedEasing),
                        ) + fadeIn(animationSpec = tween(337, easing = LinearEasing))
                    },
                    exitTransition = {
                        slideOutHorizontally(
                            targetOffsetX = { -it / 4 },
                            animationSpec = tween(FADE_FORWARDS_MS, easing = EmphasizedEasing),
                        ) + fadeOut(animationSpec = tween(112, easing = LinearEasing))
                    },
                    popEnterTransition = {
                        slideInHorizontally(
                            initialOffsetX = { -it / 4 },
                            animationSpec = tween(FADE_FORWARDS_MS, easing = EmphasizedEasing),
                        ) + fadeIn(animationSpec = tween(337, easing = LinearEasing))
                    },
                    popExitTransition = {
                        slideOutHorizontally(
                            targetOffsetX = { it / 4 },
                            animationSpec = tween(FADE_FORWARDS_MS, easing = EmphasizedEasing),
                        ) + fadeOut(animationSpec = tween(112, easing = LinearEasing))
                    },
                ) {
                    composable("home") {
                        HomeScreen(
                            container = container,
                            modifier = Modifier.fillMaxSize(),
                            onOpenSettings = { navController.navigate("settings") },
                            onOpenBackup = { navController.navigate("backup") },
                            onOpenHistory = { navController.navigate("chat_history") },
                            onOpenProviders = { navController.navigate("providers") },
                            onOpenSearchServices = { navController.navigate("search_services") },
                            onOpenWorldBookPage = { navController.navigate("world_book") },
                            onOpenSkills = { navController.navigate("skills") },
                            onOpenWorkspaces = { navController.navigate("workspaces") },
                            // + 面板生成选择器末尾的「管理生成服务」出口。
                            onOpenGenerationServices = { kind ->
                                navController.navigate(
                                    if (kind == com.psyche.memo.data.model.GenerationKind.VIDEO) {
                                        "generation_services_video"
                                    } else {
                                        "generation_services_image"
                                    },
                                )
                            },
                            onOpenTranslate = { navController.navigate("translate") },
                            onEditAssistant = { id -> navController.navigate("assistant_settings_edit/$id") },
                            onManageTags = { id -> navController.navigate("tags_manager/$id") },
                            pendingOpenConversation = pendingOpenConversation,
                        )
                    }
                    composable("chat_history") {
                        ChatHistoryScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                            onOpenConversation = { id ->
                                pendingOpenConversation.value = id
                                navController.popBackStack()
                            },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            container = container,
                            appLocale = appLocale,
                            onLocaleChange = onLocaleChange,
                            onOpenAssistants = { navController.navigate("assistant_settings") },
                            onOpenDisplay = { navController.navigate("display") },
                            onOpenProviders = { navController.navigate("providers") },
                            onOpenSearchServices = { navController.navigate("search_services") },
                            onOpenImageGeneration = { navController.navigate("generation_services_image") },
                            onOpenVideoGeneration = { navController.navigate("generation_services_video") },
                            onOpenDefaultModel = { navController.navigate("default_model") },
                            onOpenStats = { navController.navigate("stats") },
                            onOpenAbout = { navController.navigate("about") },
                            onOpenStorage = { navController.navigate("storage") },
                            onOpenMemory = { navController.navigate("memory_settings") },
                            onOpenNetworkProxy = { navController.navigate("network_proxy") },
                            onOpenToolSchema = { navController.navigate("tool_schema_settings") },
                            onOpenMcp = { navController.navigate("mcp") },
                            onOpenSkills = { navController.navigate("skills") },
                            onOpenWorkspaces = { navController.navigate("workspaces") },
                            onOpenQuickPhrases = { navController.navigate("quick_phrases") },
                            onOpenInstructionInjection = { navController.navigate("instruction_injection") },
                            onOpenWorldBook = { navController.navigate("world_book") },
                            onOpenTtsServices = { navController.navigate("tts_services") },
                            onOpenLogs = { navController.navigate("log_viewer/0") },
                            onOpenBackup = { navController.navigate("backup") },
                            onOpenSponsor = { navController.navigate("sponsor") },
                            onBack = { navController.popBackStack() },
                        )
                    }
                    // Backup & Restore main page (tts_services_page.dart pattern
                    // is "toolbar + scrollable sections"; the Flutter source is
                    // lib/features/backup/pages/backup_page.dart BackupPage).
                    composable("backup") {
                        BackupScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                            onOpenLocalSnapshots = { navController.navigate("local_snapshots") },
                            onOpenWebDavSettings = { navController.navigate("webdav_settings") },
                            onOpenS3Settings = { navController.navigate("s3_settings") },
                        )
                    }
                    composable("webdav_settings") {
                        WebDavSettingsScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("s3_settings") {
                        S3SettingsScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("local_snapshots") {
                        LocalSnapshotsScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    // Sponsor (settings_page.dart L411-421). Re-added as a thin
                    // shell in the all-UI pass.
                    composable("sponsor") {
                        SponsorScreen(
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("workspaces") {
                        WorkspaceScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                            onOpenDetail = { id -> navController.navigate("workspace_detail/$id") },
                        )
                    }
                    composable("workspace_detail/{id}") { entry ->
                        WorkspaceDetailScreen(
                            container = container,
                            workspaceId = entry.arguments?.getString("id").orEmpty(),
                            onBack = { navController.popBackStack() },
                            onOpenTerminal = { id -> navController.navigate("workspace_terminal/$id") },
                        )
                    }
                    composable("workspace_terminal/{id}") { entry ->
                        WorkspaceTerminalScreen(
                            container = container,
                            workspaceId = entry.arguments?.getString("id").orEmpty(),
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("skills") {                        SkillsScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                            onOpenDetail = { name ->
                                navController.navigate("skill_detail/" + android.net.Uri.encode(name))
                            },
                        )
                    }
                    composable("skill_detail/{name}") { entry ->
                        SkillDetailScreen(
                            container = container,
                            skillName = android.net.Uri.decode(entry.arguments?.getString("name").orEmpty()),
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("theme_settings") {                        ThemeSettingsScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                            onOpenAdvanced = { navController.navigate("theme_advanced") },
                        )
                    }
                    composable("theme_advanced") {
                        ThemeAdvancedScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("user_profile") {
                        UserProfileScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("about") {
                        AboutScreen(
                            container = container,
                            onOpenDebug = { navController.navigate("debug") },
                            onOpenLogs = { tab -> navController.navigate("log_viewer/$tab") },
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("more") {
                        MoreScreen(onBack = { navController.popBackStack() })
                    }
                    composable("storage") {
                        StorageSpaceScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                            onOpenCategory = { key ->
                                navController.navigate("storage_category/${key.name}")
                            },
                        )
                    }
                    composable("storage_category/{key}") { entry ->
                        val key = entry.arguments?.getString("key")
                        val category = key?.let { runCatching { StorageCategoryKey.valueOf(it) }.getOrNull() }
                        if (category == null) {
                            navController.popBackStack()
                        } else {
                            StorageCategoryScreen(
                                container = container,
                                categoryKey = category,
                                onBack = { navController.popBackStack() },
                                onOpenLogs = { navController.navigate("log_viewer/0") },
                                onOpenSnapshots = { navController.navigate("local_snapshots") },
                            )
                        }
                    }
                    composable("chat_item_display") {
                        ChatItemDisplaySettingsScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("rendering") {
                        RenderingSettingsScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("behavior") {
                        BehaviorStartupSettingsScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("image") {
                        ImageSettingsScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("message_style") {
                        MessageStyleSettingsScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("auto_retry") {
                        AutoRetrySettingsScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("haptics") {
                        HapticsSettingsScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("providers") {
                        ProvidersScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                            onOpenProvider = { pid ->
                                navController.navigate(if (pid == null) "provider_edit" else "provider_edit?pid=$pid")
                            },
                        )
                    }
                    composable("provider_edit?pid={pid}") { entry ->
                        val pid = entry.arguments?.getString("pid")
                        if (pid == null) {
                            // No id: legacy edit entry — open the add sheet from the list instead.
                            navController.popBackStack()
                        } else {
                            ProviderDetailScreen(
                                container = container,
                                providerId = pid,
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                    composable("instruction_injection") {
                        InstructionInjectionScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("quick_phrases") {
                        QuickPhrasesScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("world_book") {
                        WorldBookScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("memory_about") {
                        MemoryAboutScreen(onBack = { navController.popBackStack() })
                    }
                    composable("tags_manager/{assistantId}") { entry ->
                        TagsManagerScreen(
                            container = container,
                            assistantId = entry.arguments?.getString("assistantId").orEmpty(),
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("translate") {
                        TranslateScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("mcp") {
                        McpServersScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("search_services") {
                        SearchServicesScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    // 生成服务（自研功能）：两个入口共用同一个页面，靠 kind 区分。
                    composable("generation_services_image") {
                        GenerationServicesScreen(
                            container = container,
                            kind = com.psyche.memo.data.model.GenerationKind.IMAGE,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("generation_services_video") {
                        GenerationServicesScreen(
                            container = container,
                            kind = com.psyche.memo.data.model.GenerationKind.VIDEO,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("display") {
                        DisplaySettingsScreen(
                            container = container,
                            appLocale = appLocale,
                            onLocaleChange = onLocaleChange,
                            onOpenChatItemDisplay = { navController.navigate("chat_item_display") },
                            onOpenRendering = { navController.navigate("rendering") },
                            onOpenBehavior = { navController.navigate("behavior") },
                            onOpenImage = { navController.navigate("image") },
                            onOpenMessageStyle = { navController.navigate("message_style") },
                            onOpenAutoRetry = { navController.navigate("auto_retry") },
                            onOpenHaptics = { navController.navigate("haptics") },
                            onOpenTheme = { navController.navigate("theme_settings") },
                            onBack = { navController.popBackStack() },
                        )
                    }

                    // ── Wave-2 settings pages (task #16) ────────────────────
                    composable("memory_settings") {
                        MemorySettingsScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                            onOpenMemoryTrace = { navController.navigate("memory_trace") },
                            onOpenMemoryAbout = { navController.navigate("memory_about") },
                            onOpenMemoryEntries = { navController.navigate("memory_entries") },
                            onOpenMemoryProfile = { navController.navigate("user_profile") },
                        )
                    }
                    composable("memory_entries") {
                        MemoryEntriesScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("memory_trace") {
                        MemoryTraceScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("default_model") {
                        DefaultModelScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("stats") {
                        StatsScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("assistant_settings") {
                        AssistantSettingsScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                            onOpenEdit = { id ->
                                navController.navigate("assistant_settings_edit/$id")
                            },
                        )
                    }
                    composable("assistant_settings_edit/{assistantId}") { entry ->
                        AssistantSettingsEditScreen(
                            container = container,
                            assistantId = entry.arguments?.getString("assistantId").orEmpty(),
                            onBack = { navController.popBackStack() },
                            onOpenMemorySettings = { navController.navigate("memory_settings") },
                            onOpenTabLayout = { navController.navigate("assistant_tab_layout") },
                            onOpenTabSection = { tabId ->
                                val id = entry.arguments?.getString("assistantId").orEmpty()
                                navController.navigate("assistant_section/$id/$tabId")
                            },
                        )
                    }
                    // assistant_settings_edit_page.dart _AssistantTabLayoutPage
                    // (pushed from the edit page's Settings2 action).
                    composable("assistant_tab_layout") {
                        AssistantTabLayoutScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    // _AssistantDetailSectionPage — one tab's body, reached from
                    // the outline list when outline mode is on.
                    composable("assistant_section/{assistantId}/{tabId}") { entry ->
                        AssistantDetailSectionScreen(
                            container = container,
                            assistantId = entry.arguments?.getString("assistantId").orEmpty(),
                            tabId = entry.arguments?.getString("tabId").orEmpty(),
                            onBack = { navController.popBackStack() },
                            onOpenMemorySettings = { navController.navigate("memory_settings") },
                        )
                    }
                    composable("network_proxy") {
                        NetworkProxyScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("tool_schema_settings") {
                        ToolSchemaSettingsScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                            onOpenEditor = { entry, _ ->
                                navController.navigate("tool_schema_editor/${android.net.Uri.encode(entry.name)}")
                            },
                        )
                    }
                    composable("tool_schema_editor/{toolName}") { entry ->
                        ToolSchemaEditorScreen(
                            container = container,
                            toolName = android.net.Uri.decode(entry.arguments?.getString("toolName") ?: ""),
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("log_viewer/{tab}") { entry ->
                        LogViewerScreen(
                            container = container,
                            initialTab = entry.arguments?.getString("tab")?.toIntOrNull() ?: 0,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("debug") {
                        DebugScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    // tts_settings route registered; the settings TTS row stays
                    // TTS pages (task #25 batch); settings row wires tts_services.
                    composable("tts_settings") {
                        TtsSettingsScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("tts_services") {
                        TtsServicesScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                            onOpenSettings = { navController.navigate("tts_settings") },
                            onOpenTtsEditor = { id ->
                                navController.navigate(if (id == null) "tts_editor" else "tts_editor?id=$id")
                            },
                            onOpenAsrEditor = { id ->
                                navController.navigate(if (id == null) "asr_editor" else "asr_editor?id=$id")
                            },
                        )
                    }
                    // TTS add/edit page — 1:1 with tts_services_page.dart
                    // _NetworkTtsEditorPage (pushed via Navigator.push, full
                    // Scaffold, not a bottom sheet).
                    composable("tts_editor?id={id}") { entry ->
                        val id = entry.arguments?.getString("id")
                        TtsServicesEditorScreen(
                            container = container,
                            serviceId = id,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    // ASR add/edit page — 1:1 with asr_services_section.dart
                    // _showAsrEditor mobile branch (Navigator.push, full
                    // Scaffold with "Add Speech Recognition" / "Edit Speech
                    // Recognition" title, not a bottom sheet).
                    composable("asr_editor?id={id}") { entry ->
                        val id = entry.arguments?.getString("id")
                        AsrServicesEditorScreen(
                            container = container,
                            serviceId = id,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                }

                // 全局悬浮语音播放器（app_overlays.dart 的 TtsFloatingPlayer）：
                // 挂在根 Box 上，浮在任何页面之上；播放结束仍停留，便于重播。
                com.psyche.memo.ui.chat.TtsFloatingPlayer()
                androidx.compose.runtime.DisposableEffect(Unit) {
                    onDispose { com.psyche.memo.ui.chat.TtsPlayer.shutdown() }
                }
            }
        }
        }
        }
    }
}

private fun rememberAppContainer(context: android.content.Context): AppContainerImpl {
    // App-level singleton so DB/HTTP are shared across the activity lifecycle.
    val app = context.applicationContext as MemoApplication
    return app.container
}

/** FadeForwardsPageTransitionsBuilder.kTransitionMilliseconds. */
private const val FADE_FORWARDS_MS = 450

/**
 * `Curves.easeInOutCubicEmphasized` — Flutter's ThreePointCubic
 * (0.05,0) (0.133333,0.06) mid (0.166666,0.4) (0.208333,0.82) (0.25,1),
 * evaluated as two cubic segments joined at the midpoint.
 */
private val EmphasizedEasing = Easing { t ->
    val midX = 0.166666f
    val midY = 0.4f
    if (t < midX) {
        CubicBezierEasing(0.05f, 0f, 0.133333f, 0.06f).transform(t / midX) * midY
    } else {
        CubicBezierEasing(0.208333f, 0.82f, 0.25f, 1f).transform((t - midX) / (1f - midX)) * (1f - midY) + midY
    }
}

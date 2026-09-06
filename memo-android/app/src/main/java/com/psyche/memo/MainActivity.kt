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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.psyche.memo.common.AppLocale
import com.psyche.memo.ui.AssistantSettingsScreen
import com.psyche.memo.ui.DefaultModelScreen
import com.psyche.memo.ui.StatsScreen
import com.psyche.memo.ui.DisplaySettingsScreen
import com.psyche.memo.ui.HomeScreen
import com.psyche.memo.ui.ProviderDetailScreen
import com.psyche.memo.ui.ProvidersScreen
import com.psyche.memo.ui.ProviderGroupsScreen
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
import com.psyche.memo.ui.DebugScreen
import com.psyche.memo.ui.LegacyMemoryScreen
import com.psyche.memo.ui.LogViewerScreen
import com.psyche.memo.ui.MemoryEntriesScreen
import com.psyche.memo.ui.MemorySettingsScreen
import com.psyche.memo.ui.MemoryTraceScreen
import com.psyche.memo.ui.NetworkProxyScreen
import com.psyche.memo.ui.ToolSchemaEditorScreen
import com.psyche.memo.ui.ToolSchemaSettingsScreen
import com.psyche.memo.ui.TtsServicesScreen
import com.psyche.memo.ui.TtsSettingsScreen
import com.psyche.memo.ui.locale.withAppLocale
import com.psyche.memo.ui.theme.MemoTheme
import com.psyche.memo.ui.theme.ProvideSemanticColors

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
        setContent {
            MemoApp()
        }
    }
}

@Composable
fun MemoApp() {
    val context = LocalContext.current
    val container = rememberAppContainer(context)
    // Mirrors Flutter's MaterialApp(locale: settings.appLocaleForMaterialApp):
    // SYSTEM leaves the platform's language alone, anything else overrides it.
    var appLocale by remember { mutableStateOf(container.appLocaleStore.read()) }
    val localizedContext = remember(context, appLocale) { context.withAppLocale(appLocale) }
    CompositionLocalProvider(LocalContext provides localizedContext) {
        AppThemeAndContent(
            container = container,
            appLocale = appLocale,
            onLocaleChange = { locale ->
                container.appLocaleStore.write(locale)
                appLocale = locale
            },
        )
    }
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
    LaunchedEffect(Unit) { ThemeState.load(container) }
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
    } else {
        MemoTheme.colorScheme(
            resolvedPalette,
            dark,
            ThemeState.usePureBackground,
            ThemeState.useLayeredSurfaces,
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
    ) {
        // memo's iOS-style controls don't paint a Material ripple on tap.
        CompositionLocalProvider(LocalRippleConfiguration provides null) {
        // Memo paints through semantic tokens (surfaceCard / hairline), not
        // raw Material roles — see lib/theme/app_semantic_colors.dart.
        ProvideSemanticColors(scheme = colorScheme, dark = dark) {
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
                NavHost(
                    navController = navController,
                    startDestination = "home",
                    modifier = Modifier.fillMaxSize(),
                ) {
                    composable("home") {
                        HomeScreen(
                            container = container,
                            modifier = Modifier.fillMaxSize(),
                            onOpenSettings = { navController.navigate("settings") },
                            onOpenHistory = { navController.navigate("chat_history") },
                            onOpenProviders = { navController.navigate("providers") },
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
                            onOpenDefaultModel = { navController.navigate("default_model") },
                            onOpenStats = { navController.navigate("stats") },
                            onOpenAbout = { navController.navigate("about") },
                            onOpenStorage = { navController.navigate("storage") },
                            onOpenMemory = { navController.navigate("memory_settings") },
                            onOpenNetworkProxy = { navController.navigate("network_proxy") },
                            onOpenToolSchema = { navController.navigate("tool_schema_settings") },
                            onOpenTtsServices = { navController.navigate("tts_services") },
                            onOpenLogs = { navController.navigate("log_viewer") },
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("theme_settings") {
                        ThemeSettingsScreen(
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
                            onOpenGroups = { navController.navigate("provider_groups") },
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
                                onOpenGroups = { navController.navigate("provider_groups") },
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                    composable("provider_groups") {
                        ProviderGroupsScreen(
                            container = container,
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
                            onOpenLegacyMemory = { navController.navigate("legacy_memory") },
                            onOpenMemoryTrace = { navController.navigate("memory_trace") },
                            onOpenMemoryAbout = { /* memory_about_page.dart — later batch */ },
                            onOpenMemoryEntries = { navController.navigate("memory_entries") },
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
                    composable("legacy_memory") {
                        LegacyMemoryScreen(
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
                    composable("log_viewer") {
                        LogViewerScreen(
                            container = container,
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

private fun rememberAppContainer(context: android.content.Context): AppContainerImpl {
    // App-level singleton so DB/HTTP are shared across the activity lifecycle.
    val app = context.applicationContext as MemoApplication
    return app.container
}


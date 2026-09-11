package com.psyche.memo

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder

class MemoApplication : Application(), ImageLoaderFactory {

    lateinit var container: AppContainerImpl
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainerImpl(this)
        // Startup provider seeding (Flutter SettingsProvider.ensureProviderConfig
        // per-key fill-missing pass): builtin keys without a provider_rows row
        // get their pristine default config (baseUrl/label/enabled verbatim
        // from ProviderConfig.defaultsFor). Idempotent.
        // The migration runs first so a hand-typed key ("zhipu ai") is folded
        // onto its canonical spelling before the fill-missing pass would seed a
        // second row for the same provider.
        container.preferenceRepository.migrateLegacyLocalSettings()
        container.providerRepository.migrateNonCanonicalBuiltinKeys()
        container.providerRepository.ensureBuiltinDefaultsSeeded()
        // PDFBox needs its resource loader before the first PDF extraction.
        com.psyche.memo.provider.DocumentTextExtractor.init(this)
        // McpProvider.initConnectedServers：启动时连接已启用的 MCP 服务器。
        container.mcpConnections.connectEnabled()
        // Wire request/flutter/context log writers to <filesDir>/logs and apply
        // the per-source enable prefs + install the uncaught-exception hook.
        com.psyche.memo.logging.LogBootstrap.init(this, container.preferenceRepository)
        // 后台聊天生成：通知渠道 + app 前后台观察（ChatBackgroundController）。
        com.psyche.memo.service.ChatBackgroundController.init(this)
        // Live Update 进度通知管理器（RikkaHub ChatNotificationManager）：
        // 注入 context + 偏好读取函数（RikkaHub 构造注入等价）。
        com.psyche.memo.service.ChatNotificationManager.init(this) { key ->
            container.preferenceRepository.readLocal(key)
        }
    }

    /**
     * Most icons under assets/icons are SVG, which Coil cannot decode without an
     * explicit decoder; every AsyncImage in the app resolves through this factory.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(SvgDecoder.Factory())
            }
            .build()

    companion object {
        /**
         * Readable from Activity.attachBaseContext: Application.onCreate always
         * runs before the first activity attaches, so the container (and thus
         * the stored UI language) is ready when resources get configured.
         */
        @Volatile
        var instance: MemoApplication? = null
            private set
    }
}

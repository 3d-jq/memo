package com.psyche.memo

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import java.io.File
import kotlinx.coroutines.launch

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
        // 旧的自创资产目录（assistant_avatars / user_avatars / assistant_backgrounds）
        // 搬进原版目录名，否则备份与存储页都找不到头像/背景文件。
        AssetDirMigration.run(this, container.database, container.preferenceRepository)
        container.providerRepository.migrateNonCanonicalBuiltinKeys()
        container.providerRepository.ensureBuiltinDefaultsSeeded()
        // 内置技能（skill-creator 等）：首次运行从 assets 播种进 <filesDir>/skills，
        // 只播一次 —— 用户删掉后不再复活，新版本新增的会补种。
        com.psyche.memo.provider.BundledSkills.seedIfNeeded(
            this,
            container.skillStore,
            container.preferenceRepository,
        )
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
            container.preferenceRepository.readJson(key)
        }
        // 诊断用（debug 构建）：把 core:ui 的 Markdown 冷解析耗时接到 PerfProbe。
        PerfProbe.init(applicationInfo)
        if (PerfProbe.isEnabled()) {
            com.psyche.memo.ui.markdown.MarkdownPerf.sink = { line -> PerfProbe.mark(line) }
        }
        // 供应商/助手配置缓存预热：组合期多处 `remember { providerConfig(key) }`、
        // `remember { assistantStore.get(id) }`（含列表行级的 ProviderAvatar、抽屉当前助手、
        // 消息头归属助手）首次要查库 + 解 JSON，落在跑组合的那一帧上。IO 上预热一次即可
        // （写入侧仍会失效，见 AppContainerImpl.prewarmConfigCaches）。
        container.appScope.launch { container.prewarmConfigCaches() }
        // 本机副本：启动时按调度决定要不要存一份（不阻塞启动，指纹/计数自己判定）。
        container.maybeRunLocalSnapshot()
        // 备份提醒：读五键调度 + 启动分钟计时（到期驱动抽屉横幅）。
        container.backupReminder.initialize()
        // TTS 播放器：用 application context 建一次，UI 收的 flow 身份保持稳定。
        // 传入 OkHttp 与 TTS 服务仓库后，选中网络服务时会走网络合成（悬浮播放器
        // 同时解锁「保存音频」）；两者为 null 时退化为纯系统引擎。
        com.psyche.memo.ui.chat.TtsPlayer.init(
            this,
            container.httpClient,
            container.ttsServicesStore,
        )
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
            // 磁盘缓存放到原版放头像缓存的目录（`cache/avatars`）：远程头像与聊天
            // 图片都落在这里，存储页的「缓存 → 头像缓存」子项因此是真实数据，
            // 而不是此前那个永远为 0 的死项（Coil 默认写在系统 cacheDir，存储页
            // 只统计 filesDir，看不到）。
            .diskCache {
                DiskCache.Builder()
                    .directory(File(filesDir, "cache/avatars"))
                    .maxSizeBytes(COIL_DISK_CACHE_BYTES)
                    .build()
            }
            .build()

    companion object {
        /** Coil 磁盘缓存上限（与原版头像缓存量级相当，够放头像和聊天图）。 */
        private const val COIL_DISK_CACHE_BYTES = 64L * 1024 * 1024

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

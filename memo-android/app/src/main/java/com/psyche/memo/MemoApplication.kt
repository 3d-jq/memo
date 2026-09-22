package com.psyche.memo

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import java.io.File
import kotlinx.coroutines.Dispatchers
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
        // 走 IO：资产读 + filesDir 拷贝，跟 Application.onCreate 的索引/基建抢
        // main 没意义；UI 第一次点技能页时若没建好会再失败一次（skillStore 是
        // 纯文件系统访问，没有 DB 锁竞争，UI 重试一遍即可）。
        container.appScope.launch(Dispatchers.IO) {
            runCatching {
                com.psyche.memo.provider.BundledSkills.seedIfNeeded(
                    this@MemoApplication,
                    container.skillStore,
                    container.preferenceRepository,
                )
            }
        }
        // PDFBox needs its resource loader before the first PDF extraction.
        // 走 IO：纯资源加载，跟 main 抢时间没收益，对 IM 动画没用。
        container.appScope.launch(Dispatchers.IO) {
            runCatching { com.psyche.memo.provider.DocumentTextExtractor.init(this@MemoApplication) }
        }
        // McpProvider.initConnectedServers：启动时连接已启用的 MCP 服务器。
        // 走 IO：[McpConnectionManager.connectEnabled] 内部走 `repository.enabledServers()`
        // + JSON 解析，放在 main 等同于把 SQLite 读拽上 UI 线程——冷启同时点输入框时
        // 会跟 IME inset 动画撞帧。改为 IO 起飞，fallback 仍是同一个方法。
        container.appScope.launch(Dispatchers.IO) {
            runCatching { container.mcpConnections.connectEnabled() }
        }
        // Wire request/flutter/context log writers to <filesDir>/logs and apply
        // the per-source enable prefs + install the uncaught-exception hook.
        // 走 IO：crash handler + 日志目录写，主线程抢不到也无所谓。
        container.appScope.launch(Dispatchers.IO) {
            runCatching {
                com.psyche.memo.logging.LogBootstrap.init(this@MemoApplication, container.preferenceRepository)
            }
        }
        // 原版 settings_provider.dart:1156 —— 启动后按「自动删除 / 体积上限」清一次日志。
        container.maybeCleanupLogs()
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
        // 推迟到 main MessageQueue idle：之前是 `appScope.launch { ... }` 立刻
        // 起飞，与第一帧构图抢 IO、间接绑在 IME inset 动画的 CPU 上。Android
        // 的 idle queue 在冷启第一帧画完之后才第一次回 true —— 此时用户已可
        // 看画面、IME 抬起的窗口走完，预热 IO 静静地补缓存。
        container.prewarmConfigCachesOnMainIdle()
        // 搜索服务连通性：设置→搜索 里「启动时自动测试连接」开关打开时，把每个
        // 非本地搜索服务各探一次（原版 settings_provider.dart:1567-1570 的
        // `_initSearchConnectivityTests`）。结果进容器级共享表，列表页行右侧的
        // 「已连接 / 失败」胶囊直接读它。
        container.appScope.launch { container.maybeRunSearchConnectivityTests() }
        // 应用更新：启动时静默查一次（开关关掉就不查），有新版本由 HomeScreen 弹对话框。
        container.checkForAppUpdatesOnStartup()
        // 本机副本：启动时按调度决定要不要存一份（不阻塞启动，指纹/计数自己判定）。
        container.maybeRunLocalSnapshot()
        // 备份提醒：读五键调度 + 启动分钟计时（到期驱动抽屉横幅）。
        container.backupReminder.initialize()
        // TTS 播放器：用 application context 建一次，UI 收的 flow 身份保持稳定。
        // 传入 OkHttp 与 TTS 服务仓库后，选中网络服务时会走网络合成（悬浮播放器
        // 同时解锁「保存音频」）；两者为 null 时退化为纯系统引擎。
        // 走 IO（最大热点）：[TextToSpeech] 构造函数走 IPC 绑系统服务，冷启时
        // 200–500ms 阻塞调用线程。趁这条 IPC 之前 `super.onCreate` 已经把
        // Activity/Compose 早一步跑上，IME inset 动画不再被这次 IPC 打断。
        // `controller(context)` 内部已经有兜底初始化，自动播放路径
        // ([ChatViewModel] 的 `tts_auto_play_assistant_replies_v1`) 也已
        // 改成传 `container.appContext` 走懒 init 双保险。
        container.appScope.launch(Dispatchers.IO) {
            runCatching {
                com.psyche.memo.ui.chat.TtsPlayer.init(
                    this@MemoApplication,
                    container.httpClient,
                    container.ttsServicesStore,
                )
            }
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

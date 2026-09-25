package com.psyche.memo

import android.content.Context
import com.psyche.memo.data.assistant.AssistantStore
import com.psyche.memo.data.db.ConversationDao
import com.psyche.memo.data.db.MemoDatabase
import com.psyche.memo.data.db.MessageDao
import com.psyche.memo.data.db.PayloadEntityDao
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.data.settings.AppLocaleStore
import com.psyche.memo.data.settings.PreferenceRepository
import com.psyche.memo.llm.client.LlmClient
import com.psyche.memo.llm.core.CancellationRegistry
import com.psyche.memo.llm.provider.ClaudeClient
import com.psyche.memo.llm.provider.GeminiClient
import com.psyche.memo.llm.provider.OpenAiChatCompletionsClient
import com.psyche.memo.llm.retry.AutoRetryOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Production container. Wiring holder for the whole app; kept manual so tests
 * can substitute fakes.
 */
class AppContainerImpl(context: Context) : com.psyche.memo.common.AppContainer {

    /** App context for tool executors (clipboard / TTS / usage stats). */
    val appContext: Context = context.applicationContext

    override val appName: String = "Memo"
    override val platform: com.psyche.memo.common.Platform = com.psyche.memo.common.Platform.ANDROID

    val database: MemoDatabase by lazy { MemoDatabase(appContext) }
    val preferenceRepository: PreferenceRepository by lazy {
        PreferenceRepository(
            database,
            appContext.getSharedPreferences("memo_preferences", Context.MODE_PRIVATE),
        )
    }

    /**
     * Shared OkHttpClient — built on first network call, not at app construction.
     *
     * `[AppContainerImpl]` 自身是 lazy-all，但 `httpClient` 之前是 **eager**：
     * `proxySelector(GlobalProxy.selector(preferenceRepository))` 立刻读 `preferenceRepository` →
     * 它又是 `by lazy { ... PreferenceRepository(database, ...) }` → 首次解引就把 SQLite cold-open
     * 拽进 `Application.onCreate` 的主线程。冷启时用户点输入框那一刻恰好赶上那一波读，IME
     * inset 动画就跟着打嗝。改成 `by lazy` 后，`AppContainerImpl(this)` 构造只 set self-ref，
     * 真正的 OkHttp 构造推迟到第一次 `OkHttpClient.newCall(...)` 时——已在 IO 调度上。
     */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS) // long SSE reads
            .writeTimeout(30, TimeUnit.SECONDS)
            // 全局网络代理（network_proxy_page 的 global_proxy_*_v1 键）：selector
            // 每次连接都读当前配置 ⇒ 设置改动立即生效，无需重建客户端。绕过规则
            // （localhost/127.0.0.1/网段）在这里生效——上游 dio 没消费 bypass，但
            // 本地模型服务器挂代理时必须直连。
            .proxySelector(GlobalProxy.selector(preferenceRepository))
            .proxyAuthenticator { _, response ->
                val credentials = GlobalProxy.credentialsFor(preferenceRepository)
                if (credentials == null) {
                    null
                } else {
                    response.request.newBuilder()
                        .header("Proxy-Authorization", credentials)
                        .build()
                }
            }
            // RequestLogInterceptor is a no-op when com.psyche.memo.common.logging.RequestLogger
            // is disabled; safe to keep installed regardless of the toggle.
            .addInterceptor(com.psyche.memo.llm.logging.RequestLogInterceptor())
            .build()
    }

    val appLocaleStore: AppLocaleStore by lazy { AppLocaleStore(preferenceRepository) }

    /** Conversations with an active LLM stream — drives the drawer loading dot. */
    val streamingConversationIds = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    val conversationDao: ConversationDao by lazy { ConversationDao(database.readableDatabase) }
    val messageDao: MessageDao by lazy { MessageDao(database.readableDatabase) }

    /** Provider data layer (provider_rows + ordering + groups). */
    val providerRepository: com.psyche.memo.data.repo.ProviderRepository by lazy {
        com.psyche.memo.data.repo.ProviderRepository(database.writableDatabase, preferenceRepository)
    }

    val assistantStore: AssistantStore by lazy { AssistantStore(database.writableDatabase) }

    /**
     * Agent Skills 仓库 —— 技能本体放 `<filesDir>/skills/<技能名>/SKILL.md`
     * （照 RikkaHub 的 `FileFolders.SKILLS`）。纯文件操作，实现在 core:common。
     */
    val skillStore: com.psyche.memo.common.skill.SkillStore by lazy {
        com.psyche.memo.common.skill.SkillStore(java.io.File(appContext.filesDir, SKILLS_DIR))
    }

    /**
     * 沙箱工作区（照 RikkaHub 的 `RepositoryModule`）：目录在 `<filesDir>/workspaces/`，
     * rootfs 用 PRoot 跑，bind mount 把 `/skills` 与 `/upload` 挂进沙箱。
     *
     * **同一份挂载表既给 PRoot 的 `-b` 参数、也给文件工具的路径解析** —— 两处各写一份
     * 就会漂移（上游注释点名过）。`useLegacyPackaging = true`（app 模块）必须开着，
     * 否则 `nativeLibraryDir` 是空的、proot 找不到。
     */
    val workspaceManager: com.psyche.memo.workspace.WorkspaceManager by lazy {
        com.psyche.memo.workspace.WorkspaceManager(
            baseDir = java.io.File(appContext.filesDir, WORKSPACES_DIR),
            shellRunner = prootShellRunner,
            bindMounts = listOf(
                com.psyche.memo.workspace.WorkspaceBindMount(
                    source = java.io.File(appContext.filesDir, SKILLS_DIR).apply { mkdirs() },
                    target = "/skills",
                ),
                com.psyche.memo.workspace.WorkspaceBindMount(
                    source = java.io.File(appContext.filesDir, UPLOAD_DIR).apply { mkdirs() },
                    target = "/upload",
                ),
            ),
        )
    }

    /**
     * PRoot 执行器。单独暴露是给**交互式终端页**用：它要拿同一份挂载表
     * （`WorkspaceManager.bindMounts()`）拼 PTY 的 argv、以及同一个 loader 环境。
     */
    val prootShellRunner: com.psyche.memo.workspace.ProotShellRunner by lazy {
        com.psyche.memo.workspace.ProotShellRunner(
            nativeLibraryDir = java.io.File(appContext.applicationInfo.nativeLibraryDir),
        )
    }

    val workspaceRepository: com.psyche.memo.provider.workspace.WorkspaceRepository by lazy {
        com.psyche.memo.provider.workspace.WorkspaceRepository(
            store = com.psyche.memo.data.workspace.WorkspaceStore(database.writableDatabase),
            manager = workspaceManager,
            rootfsInstaller = com.psyche.memo.workspace.RootfsInstaller(workspaceManager),
            assistants = assistantStore,
        )
    }

    /**
     * 交互式终端的会话表（照上游 `WorkspaceTerminalSessionManager`）：容器级单例，
     * 退出终端页不结束 shell —— 只有显式关 tab、shell 自己退出、或工作区被删/换 rootfs
     * 时才结束。
     */
    val workspaceTerminalSessions: com.psyche.memo.provider.workspace.WorkspaceTerminalSessionManager by lazy {
        com.psyche.memo.provider.workspace.WorkspaceTerminalSessionManager(
            container = this,
            appScope = appScope,
        )
    }

    /**
     * Agent 浏览器：按会话持有的离屏 WebView。**同时只一个活动实例**，
     * 因为 cookie / WebStorage 是 app 全局的（见 BrowserSessionStore 注释）。
     */
    val browserSessions: com.psyche.memo.provider.browser.BrowserSessionStore by lazy {
        com.psyche.memo.provider.browser.BrowserSessionStore(appContext)
    }

    /** 长期记忆数据层（memory_entry_rows 表 + payload 投影，见 MemoryEntryRowDao）。 */
    val memoryProviderV2: com.psyche.memo.ui.MemoryProviderV2 by lazy {
        com.psyche.memo.ui.MemoryProviderV2(database.writableDatabase)
    }

    /** 用户资料（user_provider.dart：user_name / avatar_type / avatar_value）。 */
    val userProfileStore: com.psyche.memo.ui.UserProfileStore by lazy {
        com.psyche.memo.ui.UserProfileStore(preferenceRepository)
    }

    /**
     * 助手编辑页 tab 布局（mobile_assistant_edit_tab_order_v1 /
     * mobile_assistant_edit_tab_hidden_v1 / mobile_assistant_detail_outline_enabled_v1）。
     * 容器级共享，编辑页与布局页读同一份可变状态。
     */
    val assistantTabLayout: com.psyche.memo.ui.AssistantTabLayoutState by lazy {
        com.psyche.memo.ui.AssistantTabLayoutState(preferenceRepository)
    }

    /** Search service settings (search_service_rows + preference keys). */
    val searchSettingsRepository: com.psyche.memo.data.repo.SearchSettingsRepository by lazy {
        com.psyche.memo.data.repo.SearchSettingsRepository(database.writableDatabase, preferenceRepository)
    }

    /** MCP server storage + runtime connections. */
    val mcpRepository: com.psyche.memo.data.repo.McpRepository by lazy {
        com.psyche.memo.data.repo.McpRepository(database.writableDatabase)
    }

    /**
     * 生成服务（图片 / 视频）—— 自研功能，上游 kelivo 没有。设置里的两个入口
     * （「生成图片」「生成视频」）与助手编辑页的两个 tab 共用这一份：
     * 记录存 `extension_entity_rows`（kind = `generation_service`），变更走 [version]。
     */
    val generationServices: com.psyche.memo.provider.generation.GenerationServiceRepository by lazy {
        com.psyche.memo.provider.generation.GenerationServiceRepository(
            store = com.psyche.memo.data.generation.GenerationServiceStore(database.writableDatabase),
            assistants = assistantStore,
        )
    }

    /** 生成结果的落盘（图片进 `filesDir/images`、视频进 `filesDir/videos`）。 */
    val generatedMediaStore: com.psyche.memo.provider.generation.GeneratedMediaStore by lazy {
        com.psyche.memo.provider.generation.GeneratedMediaStore(appContext)
    }

    /** World book (lorebook) data layer. */
    val worldBookRepository: com.psyche.memo.data.repo.WorldBookRepository by lazy {
        com.psyche.memo.data.repo.WorldBookRepository(database.writableDatabase, preferenceRepository)
    }

    /**
     * Voice service stores (TTS + ASR). Container-scoped so the list page
     * and the add/edit pages share the same instance; the editor's
     * `upsert` / `add` / `remove` bump `version` here, and the list page
     * re-renders the section without any nav-result plumbing.
     */
    val ttsServicesStore: com.psyche.memo.ui.TtsServicesStore by lazy {
        com.psyche.memo.ui.TtsServicesStore(database.writableDatabase, preferenceRepository)
    }
    val asrServicesStore: com.psyche.memo.ui.AsrServicesStore by lazy {
        com.psyche.memo.ui.AsrServicesStore(preferenceRepository)
    }
    val mcpConnections: com.psyche.memo.provider.mcp.McpConnectionManager by lazy {
        // 「请求超时」是设置页可改的（TIMEOUT_KEY 存裸毫秒数），每次连接现读。
        com.psyche.memo.provider.mcp.McpConnectionManager(
            mcpRepository,
            httpClient,
            requestTimeoutMsProvider = {
                preferenceRepository.readJson(
                    com.psyche.memo.ui.TIMEOUT_KEY,
                )?.trim()?.removeSurrounding("\"")?.toLongOrNull()
                    ?: com.psyche.memo.provider.mcp.McpClient.DEFAULT_RESPONSE_TIMEOUT_MS
            },
            imageSaver = ::saveMcpToolImage,
        )
    }

    /**
     * MCP 工具结果里的 base64 图片落盘（上游 `AppDirectories.saveBase64Image`，
     * prefix `mcp_img`；standard 与 URL-safe 两种 base64 都吃）。目录沿用工具图片的
     * `filesDir/tool_images` —— 图片要随工具结果持久化，重开对话还在。
     */
    private fun saveMcpToolImage(mime: String, base64Data: String): String? = runCatching {
        val cleaned = base64Data.replace(Regex("\\s"), "")
        val flags = android.util.Base64.NO_WRAP or
            if (cleaned.contains('-') || cleaned.contains('_')) android.util.Base64.URL_SAFE else 0
        val bytes = android.util.Base64.decode(cleaned, flags)
        val ext = when (mime.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "png"
        }
        val dir = java.io.File(appContext.filesDir, "tool_images").apply { mkdirs() }
        // 一次结果里可能有多张图，毫秒会撞名，补上亚毫秒位。
        val micros = System.currentTimeMillis() * 1000L + (System.nanoTime() % 1000L)
        val file = java.io.File(dir, "mcp_img_$micros.$ext")
        file.writeBytes(bytes)
        file.absolutePath
    }.getOrNull()

    /** HTTP search dispatch (ported provider subset). */
    val searchEngine: com.psyche.memo.provider.search.SearchEngine by lazy {
        com.psyche.memo.provider.search.HttpSearchEngine(httpClient)
    }

    /**
     * 搜索服务连通性共享表（原版 `SettingsProvider._searchConnection`）：
     * 启动时自动测试的结果与手动「测试连接」的结果都落在这里，所以离开搜索服务页
     * 再回来，行右侧那枚「已连接 / 失败」胶囊还在。见 [com.psyche.memo.provider.search.SearchConnectivityService]。
     */
    val searchConnectivity: com.psyche.memo.provider.search.SearchConnectivityService by lazy {
        com.psyche.memo.provider.search.SearchConnectivityService(
            scope = appScope,
            engine = searchEngine,
            services = { searchSettingsRepository.services() },
            common = { searchSettingsRepository.commonOptions() },
        )
    }

    /**
     * 启动钩子（原版 `settings_provider.dart:1567-1570` 在设置加载完后
     * `if (_searchAutoTestOnLaunch) _initSearchConnectivityTests()`）：
     * 开关打开才探，探测本身不阻塞启动。放在 IO 的 appScope 里跑 ——
     * 它要读服务列表（DB）和公共选项。
     */
    fun maybeRunSearchConnectivityTests() {
        runCatching {
            if (searchSettingsRepository.autoTestOnLaunch()) searchConnectivity.testAllOnLaunch()
        }
    }

    /**
     * Backup / restore orchestration (archive build, local-file restore).
     * appVersion is read from the package manager so the manifest records the
     * real build instead of a hardcoded string.
     */
    val backupService: com.psyche.memo.data.backup.MemoBackupService by lazy {
        com.psyche.memo.data.backup.MemoBackupService(
            context = appContext,
            database = database,
            preferenceRepository = preferenceRepository,
            appVersion = appVersionString(),
            httpClient = httpClient,
        )
    }

    /**
     * 本机副本（backup 子块 3）：`<filesDir>/snapshots/` 下的归档 + 保留策略。
     * 自动/手动都走这里，`runIfDue` 由启动与回前台触发。
     */
    val localSnapshots: com.psyche.memo.data.backup.LocalSnapshotService by lazy {
        com.psyche.memo.data.backup.LocalSnapshotService(
            // Copies live beside the app's files; the change fingerprint reads
            // the real database path (`/data/data/<pkg>/databases/memo.db`),
            // which is *not* under filesDir on Android.
            appDataDirectory = appContext.filesDir,
            databaseFile = appContext.getDatabasePath(com.psyche.memo.data.db.MemoSchema.DB_NAME),
            backupService = backupService,
            preferences = com.psyche.memo.data.backup.LocalSnapshotPreferences(preferenceRepository),
        )
    }

    /**
     * 备份提醒（backup 子块 4）：五键调度 + 到期判定 + 会话内 snooze，
     * 容器级单例（横幅与备份页读同一份状态），`initialize()` 启动分钟计时。
     */
    val backupReminder: BackupReminder by lazy { BackupReminder(preferenceRepository) }

    /**
     * 应用更新：启动时静默查一次，结果放这里给 UI 用（HomeScreen 弹对话框、关于页读同一份）。
     * 受 `display_show_app_updates_v1` 控制（缺省开，照上游默认值）；`releases/latest` 的 404
     * 在 [com.psyche.memo.update.UpdateService] 里已按"已是最新"处理。
     */
    val updateOutcome = kotlinx.coroutines.flow.MutableStateFlow<com.psyche.memo.update.UpdateService.Outcome?>(null)

    /** 启动钩子：不阻塞启动，失败只是不提示。 */
    fun checkForAppUpdatesOnStartup() {
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (preferenceRepository.readJson("display_show_app_updates_v1") == "0") return@launch
            val current = appVersionString().substringBefore('+')
            updateOutcome.value = com.psyche.memo.update.UpdateService.check(httpClient, current)
        }
    }

    /**
     * Launch + resume hook: takes a copy when the schedule says one is due.
     * Cheap to call — the counters and the change fingerprint decide — and it
     * never blocks the caller.
     */
    fun maybeRunLocalSnapshot() {
        appScope.launch { runCatching { localSnapshots.runIfDue() } }
    }

    /**
     * 日志清理（原版 `settings_provider.dart:1156` 在设置加载完后调
     * `RequestLogger.cleanupLogs(autoDeleteDays, maxSizeMB)`）：删掉超过保留期的
     * 轮转日志，再把总量压到体积上限。
     *
     * 此前 `LogBootstrap.cleanup` 写好了却**没有任何调用点** —— 日志设置里的
     * 「自动删除」「日志大小上限」两项是死的（用户 2026-09-16「日志设置里面的功能
     * 做了没有呀」）。启动时跑一次、改设置时再跑一次，与上游两处调用对齐。
     */
    fun maybeCleanupLogs() {
        appScope.launch {
            runCatching { com.psyche.memo.logging.LogBootstrap.cleanup(preferenceRepository) }
        }
    }

    /**
     * 会话切换的**准备阶段** —— 照原版 `home_page_controller.switchConversationAnimated`
     * 的「fetch-then-commit」：`prepareConversationSwitch` 在列表**淡出（opacity 0）**期间
     * 把目标会话的首屏数据读出来，并把即将可见的 Markdown 在 Default 线程预热好；
     * 之后才提交（切 `selectedConversationId`）、落底、淡入。
     *
     * 为什么有效：原版把「取数 + 首帧渲染 + 落底」全部藏在透明度 0 后面，用户看到的是
     * 一次平滑的交叉淡入；我们原来是同一帧里直接提交，重活全压在抽屉关闭动画上
     * （用户 2026-09-15「从侧边栏到主界面 内容多的就会很卡」）。
     */
    fun prepareConversationSwitch(conversationId: String) {
        val rows = runCatching { messageDao.getTail(conversationId) }.getOrDefault(emptyList())
        if (rows.isEmpty()) return
        // 与 ChatViewModel.prewarmWindowMarkdown 同一套有界预算（条数 + 字符）。
        val marks = ArrayList<Pair<String, Boolean>>(8)
        var budget = 40_000
        for (row in rows.takeLast(8).asReversed()) {
            val text = row.content
            if (text.isEmpty()) continue
            if (text.length > budget) break
            budget -= text.length
            marks += text to true
        }
        if (marks.isNotEmpty()) {
            runCatching { com.psyche.memo.ui.markdown.preloadMarkdown(marks) }
        }
    }

    /**
     * 预热两个解码缓存：在 **IO** 上把 provider_rows / assistant_rows 全表读一遍。
     *
     * 组合期有多处 `remember { providerConfig(key) }` / `remember { assistantStore.get(id) }`
     * —— 供应商头像 `ProviderAvatar` 是**列表行级**、抽屉的当前助手、消息头归属助手、
     * 各种选择 sheet、顶栏助手头像 —— 首次调用要查库 + 解 JSON，而它落在跑组合的那一帧
     * 上。启动预热一次之后这些都是内存命中；写入仍走 `PayloadEntityDao`（写时整体失效），
     * 新建/编辑供应商或助手后下一次读补一次库。见 PORTING §5.13。
     */
    fun prewarmConfigCaches() {
        val providerRows = runCatching {
            PayloadEntityDao(database.readableDatabase, "provider_rows", primaryKey = "provider_key").getAll()
        }.getOrDefault(emptyList())
        for (row in providerRows) {
            runCatching {
                com.psyche.memo.data.db.ProviderConfigCache.put(
                    row.id,
                    com.psyche.memo.data.model.ProviderConfig.fromJsonString(providerPreWarmJson, row.payload),
                )
            }
        }
        // assistantStore.getAll() 会把每行写进 AssistantCache（见 AssistantStore.getAll）。
        runCatching { assistantStore.getAll() }
    }

    /**
     * Single-instance `Json` reused by [prewarmConfigCaches]; mirrors the
     * `[AssistantRegexApplier.decodeJson]` / `[AskUserCard.askUserDecodeJson]`
     * pattern from the warning-cleanup batch. The earlier `[kotlinx.serialization.json.Json { ignoreUnknownKeys = true }]`
     * literal inside the for-loop rebuilt per provider row and would also fire
     * the redundant-format warning under a recompile.
     */
    private val providerPreWarmJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /**
     * Calls [prewarmConfigCaches] **after** Android's main-thread [android.os.MessageQueue]
     * reports idle — i.e. once the first `Application.onCreate` work, the activity's
     * first composition, and the IME-rise animation (if any user taps input right
     * after launch) have all settled. Cold-start paths on the previous version
     * fired `prewarmConfigCaches` immediately inside `appScope.launch` so the IO
     * thread was already shuttling the provider rows while the IME inset animation
     * was trying to drive the chat-input-bar layout. The idle queue gives the
     * launcher frame a clear runway, only then does the warm-up IO start.
     *
     * Returned bool=false: the handler runs exactly once, then removes itself.
     */
    fun prewarmConfigCachesOnMainIdle() {
        android.os.Looper.myQueue().addIdleHandler {
            appScope.launch { prewarmConfigCaches() }
            false
        }
    }

    /** `"1.0.5+6"` — versionName + versionCode, matching Flutter's appVersion. */
    private fun appVersionString(): String = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val versionName = info.versionName ?: "0"
        @Suppress("DEPRECATION")
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        "$versionName+$versionCode"
    }.getOrDefault("0+0")

    /** ask_user_interaction_service.dart（同族的 tool_approval_service 已整块拆除）。 */
    val askUserInteractionService: com.psyche.memo.ui.chat.AskUserInteractionService by lazy {
        com.psyche.memo.ui.chat.AskUserInteractionService()
    }
    /** 定位工具执行期的「挂起等系统权限弹窗结果」通道（本工程新增，上游 iOS 走原生申请）。 */
    val locationPermissionService: com.psyche.memo.ui.chat.LocationPermissionService by lazy {
        com.psyche.memo.ui.chat.LocationPermissionService()
    }
    /**
     * 工作区「读过才许改」的账本（照 deepseek-harness 的 B10）：容器级一份，
     * 按 (会话, 工作区, 路径) 记读取时的字节数 —— 生成可能跑在页面之外，账本不能跟着
     * ViewModel 一起没了。见 [com.psyche.memo.provider.workspace.WorkspaceReadLedger]。
     */
    val workspaceReadLedger: com.psyche.memo.provider.workspace.WorkspaceReadLedger by lazy {
        com.psyche.memo.provider.workspace.WorkspaceReadLedger()
    }

    /**
     * 「冷启动那一次窗口加载」是否还没结束 —— 原版 `home_page_controller.dart:311`
     * `_startupConversationPending` 的等价物。
     *
     * **必须放在容器里、不能放页面级 `remember`**：进设置页再返回时聊天页会重建，
     * 页面级状态会把标记重置成 true，于是返回时又露一次骨架 + 重新读整窗
     * （用户 2026-09-15「点击设置 返回 又会加载对话 又会卡一下」）。
     */
    @Volatile
    var startupConversationPending: Boolean = true

    /** App-wide IO scope for one-shot persistence (assistant selection writes). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 活着的会话页 VM（`viewModel(key = conversationId)` 每条会话一个）。
     *
     * `ViewModelStore` **不会**因为 key 变化移除旧 VM，所以由容器在这里管回收：注册新
     * VM 时把**不忙**的旧 VM 释放成空壳（[ChatViewModel.releaseForReuse]）。不这么做的话，
     * 每点开一条会话就留下 40 条消息 + parts + 一个 `viewModelScope`（可能还有在途生成），
     * 真机表现就是「点击对话多了越来越卡」（用户 2026-09-15「从侧边栏到主页这个会很卡」；
     * 同期 `dumpsys meminfo` 对比：我们 RSS 259MB / 原版 120MB）。
     */
    private val liveChatViewModels = java.util.concurrent.CopyOnWriteArrayList<ChatViewModel>()

    /** 会话页组合时登记自己；顺手回收不忙的旧 VM。 */
    fun registerChatViewModel(vm: ChatViewModel) {
        if (!liveChatViewModels.contains(vm)) liveChatViewModels.add(vm)
        reapIdleChatViewModels(keep = vm)
    }

    /**
     * 会话的**所有者**：按 conversationId 取，没有就建。
     *
     * 这是「生成不再跟着页面死」的那块地基（PORTING §5.62）：以前会话对象住在
     * `ViewModelStore` 里，Activity 一销毁就被清掉、`viewModelScope` 连带取消正在
     * 输出的回答；现在它住在容器里，页面只是订阅者 —— 回到会话拿到的是同一个对象，
     * 流式状态直接接上。RikkaHub 是同一个形状（`ChatService` 单例 + `AppScope` +
     * 按会话的 `ConversationSession`，`ChatVM.kt:65` 只是 `getConversationFlow`）。
     *
     * 上限 [chatSessionCap]：超出就销毁最旧的不忙会话（`destroy()` 取消作用域），
     * 免得注册表本身变成新的泄漏。生成中的永不被挤。
     */
    fun chatSession(conversationId: String, injectPresets: Boolean = false): ChatViewModel {
        chatSessions[conversationId]?.let { return it }
        val vm = ChatViewModel(this, conversationId, injectPresets)
        chatSessions[conversationId] = vm
        evictOverflowChatSessions(keep = conversationId)
        return vm
    }

    /** 删会话：连容器里的会话对象一起销毁（不然它带着消息窗口赖到被上限挤出去）。 */
    fun deleteConversation(conversationId: String) {
        conversationDao.delete(conversationId)
        dropChatSession(conversationId)
    }

    /** 会话被删掉时连对象一起销毁（不只是清状态）。 */
    fun dropChatSession(conversationId: String) {
        val vm = chatSessions.remove(conversationId) ?: return
        liveChatViewModels.remove(vm)
        vm.destroy()
    }

    private fun evictOverflowChatSessions(keep: String) {
        while (chatSessions.size > chatSessionCap) {
            val victim = chatSessions.entries.firstOrNull {
                it.key != keep && !it.value.isBusy
            } ?: return
            chatSessions.remove(victim.key)
            liveChatViewModels.remove(victim.value)
            victim.value.destroy()
        }
    }

    private val chatSessions = LinkedHashMap<String, ChatViewModel>()

    /** 同时留活的会话数上限（每条会话 = 一个作用域 + 一页消息窗口）。 */
    private val chatSessionCap = 12

    /**
     * 把不忙（[ChatViewModel.isBusy] 为假）且还握着重状态的 VM 释放成空壳。
     * [keep] 传当前屏上的那个（离开会话页时可以不传）。
     */
    fun reapIdleChatViewModels(keep: ChatViewModel? = null) {
        for (vm in liveChatViewModels) {
            if (vm === keep || vm.isBusy || !vm.hasLoadedContent) continue
            vm.releaseForReuse()
        }
    }

    /**
     * 记忆流程追踪（memory_trace.dart）：内存环形缓冲（24 条，不落盘），
     * 后台整理与记忆工具调用都会写一份，供「流程追踪」页查看。
     */
    val memoryTraceRecorder: com.psyche.memo.provider.MemoryTraceRecorder
        get() = com.psyche.memo.provider.MemoryTraceRecorder

    /** `memory_trace_enabled_v1` → recorder（默认开）。 */
    fun syncMemoryTraceEnabled() {
        val raw = preferenceRepository.readJson("memory_trace_enabled_v1")
        val enabled = raw == null || raw == "true"
        memoryTraceRecorder.enabled = enabled
    }

    /**
     * 后台记忆整理（memory_pipeline.dart）：Gatekeeper → Extract → Smart Add →
     * Profile Distiller，单并发队列。跟着进程活，聊天侧只负责 schedule。
     */
    val memoryPipeline: com.psyche.memo.provider.MemoryPipelineService by lazy {
        com.psyche.memo.provider.MemoryPipelineService(this, appScope)
    }

    /**
     * assistant_provider.dart currentAssistantId — the globally selected
     * assistant that the drawer scopes conversations to. Exposed as a
     * StateFlow so the drawer card + conversation list recompose on switch.
     */
    private val _currentAssistantId = MutableStateFlow<String?>(null)
    val currentAssistantId: StateFlow<String?> = _currentAssistantId

    fun currentAssistant(): Assistant? = _currentAssistantId.value?.let { assistantStore.get(it) }

    /**
     * assistant_provider.load() 94-100: restore the persisted id only while it
     * still exists in assistant_rows. Reads prefs + the store — call on IO.
     */
    fun refreshCurrentAssistant() {
        val savedId = preferenceRepository.readJson(currentAssistantKey())
            ?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() }
        _currentAssistantId.value = resolveCurrentAssistantId(
            savedId = savedId,
            existingIds = assistantStore.getAll().map { it.id },
        )
    }

    /**
     * The conversation the chat screen is showing (`ChatService.currentConversationId`).
     * The manual "organize memory" action needs it: it extracts from the chat
     * the user is actually looking at.
     */
    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId

    fun setCurrentConversation(id: String?) {
        if (_currentConversationId.value == id) return
        _currentConversationId.value = id
    }

    /** assistant_provider.dart setCurrentAssistant 278-284 — no-op when unchanged. */
    fun setCurrentAssistant(id: String) {
        if (_currentAssistantId.value == id) return
        _currentAssistantId.value = id
        appScope.launch { preferenceRepository.writeJson(currentAssistantKey(), "\"$id\"") }
    }

    /** assistant_provider.dart setSearchEnabledForCurrentAssistant 460-464. */
    fun setAssistantSearchEnabled(enabled: Boolean) {
        val current = currentAssistant() ?: return
        if (current.searchEnabled == enabled) return
        assistantStore.update(current.copy(searchEnabled = enabled))
    }

    private fun currentAssistantKey(): String = "current_assistant_id_v1"

    val cancellations: CancellationRegistry = CancellationRegistry()

    /**
     * auto_retry_options（自动重试设置页）——按请求实时读取，设置页改动
     * 立即作用于下一次生成（此前是硬编码默认值，整页设置无效）。
     */
    private fun currentRetryOptions(): AutoRetryOptions {
        val raw = preferenceRepository.readJson("auto_retry_options") ?: return AutoRetryOptions()
        val obj = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return AutoRetryOptions()
        fun boolOf(k: String, d: Boolean) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.toBooleanStrictOrNull() ?: d
        fun intOf(k: String, d: Int) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: d
        fun longOf(k: String, d: Long) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: d
        fun dblOf(k: String, d: Double) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: d
        // 键**缺失**时回落到工厂默认（与上面的 intSet 同款）；键存在但是空数组 =
        // 用户自己清空了词表，照旧尊重。词表为空 ⇒ 关键词触发永不命中（2026-09-15
        // 用户报的那个 bug），所以这里的回落必须是 DEFAULT_* 而不是 emptyList()。
        fun strList(k: String, d: List<String>) = (obj[k] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?: d
        fun intSet(k: String) = (obj[k] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
            ?.toSet()
            ?: AutoRetryOptions().retryStatusCodes
        return AutoRetryOptions(
            enabled = boolOf("enabled", true),
            maxRetries = intOf("maxRetries", AutoRetryOptions.DEFAULT_MAX_RETRIES),
            initialDelayMs = longOf("initialDelayMs", AutoRetryOptions.DEFAULT_INITIAL_DELAY_MS),
            maxDelayMs = longOf("maxDelayMs", AutoRetryOptions.DEFAULT_MAX_DELAY_MS),
            multiplier = AutoRetryOptions.clampMultiplier(dblOf("multiplier", AutoRetryOptions.DEFAULT_MULTIPLIER)),
            jitter = boolOf("jitter", true),
            retryOnNetworkError = boolOf("retryOnNetworkError", true),
            retryStatusCodes = intSet("retryStatusCodes"),
            retryKeywords = strList("retryKeywords", AutoRetryOptions.DEFAULT_RETRY_KEYWORDS),
            stopKeywords = strList("stopKeywords", AutoRetryOptions.DEFAULT_STOP_KEYWORDS),
        )
    }

    val llmClients: List<LlmClient> = listOf(
        OpenAiChatCompletionsClient(httpClient, { currentRetryOptions() }, cancellations),
        ClaudeClient(httpClient, { currentRetryOptions() }, cancellations),
        GeminiClient(httpClient, { currentRetryOptions() }, cancellations),
    )

    fun clientFor(providerId: String): LlmClient {
        val base = llmClients.firstOrNull { it.supports(providerId) } ?: llmClients.first()
        // chat_api_helpers.dart:44-53 `apiModelId(cfg, modelId)`：模型覆盖里的
        // apiModelId 才是**出网**的模型 id，而且厂商启发式（Claude thinking、
        // Gemini 判定、GLM OCR…）也用它。包一层客户端在这里统一改写，覆盖聊天 /
        // 标题 / 摘要 / 记忆 / 翻译 / OCR / 测试连接全部请求路径。
        return WireModelIdClient(
            delegate = base,
            promptCaching = {
                val config = providerConfig(providerId)
                val caching = config?.claudePromptCachingEnabled == true
                val ttl = com.psyche.memo.data.model.ProviderConfig.resolveClaudePromptCachingTtl(
                    config?.claudePromptCachingTtl,
                )
                caching to ttl
            },
            wireModelId = { modelId -> wireModelId(providerId, modelId) },
        )
    }

    /**
     * 逻辑模型 id → 上游模型 id（`model_overrides[modelId].apiModelId`，
     * 没配就原样返回）。与列表页显示用的 `resolveModelIdentity(modelId, …).baseId`
     * 同一个来源。
     */
    fun wireModelId(providerId: String, modelId: String): String {
        val override = providerConfig(providerId)?.modelOverrides?.get(modelId)
            as? kotlinx.serialization.json.JsonObject
            ?: return modelId
        return com.psyche.memo.ui.resolveModelIdentity(modelId, override).baseId
    }

    /**
     * Provider API key from the provider_rows payload (multi-key aware).
     *
     * The key is folded onto the canonical built-in spelling first: a stored
     * model selection can still carry an older spelling (`"zhipu ai"`) while the
     * seeded row is `"Zhipu AI"`, and an exact lookup would then read as "no
     * such provider" — which is how a memory-model call ends up unable to reach
     * the model at all.
     */
    fun providerConfig(providerId: String): com.psyche.memo.data.model.ProviderConfig? {
        // 组合期/请求组装期都会被高频调用（单次请求 4 次），每次查库 + 解 JSON 会
        // 把 SQLite 往返打到主线程上。缓存由 `PayloadEntityDao` 在写入时整体失效。
        com.psyche.memo.data.db.ProviderConfigCache.get(providerId)?.let { return it }
        val dao = PayloadEntityDao(database.readableDatabase, "provider_rows", primaryKey = "provider_key")
        val row = dao.get(providerId)
            ?: dao.get(com.psyche.memo.data.repo.ProviderRepository.canonicalizeKey(providerId))
            ?: return null
        val config = com.psyche.memo.data.model.ProviderConfig.fromJsonString(
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
            row.payload,
        )
        com.psyche.memo.data.db.ProviderConfigCache.put(providerId, config)
        return config
    }

    fun apiKeyFor(providerId: String): String? =
        providerConfig(providerId)?.effectiveApiKey()
            ?: appContext.getSharedPreferences("memo_providers", Context.MODE_PRIVATE)
                .let { prefs ->
                    prefs.getString("api_key_$providerId", null)
                        ?: prefs.getString(
                            "api_key_" + com.psyche.memo.data.repo.ProviderRepository.canonicalizeKey(providerId),
                            null,
                        )
                }

    /**
     * OpenAI **Responses API** 开关（provider_rows 的 `useResponseApi`）。
     * 与 chatPath 保持同步（`ProviderSheets.useResponseApiFor`），请求侧读它决定
     * 端点、请求体与解码器（`ResponsesApi` / `ResponsesDecoder`）。
     */
    fun usesResponseApi(providerId: String): Boolean =
        providerConfig(providerId)?.useResponseApi == true

    fun baseUrlFor(providerId: String): String {
        providerConfig(providerId)?.baseUrl?.takeIf { it.isNotEmpty() }?.let { return it }
        val canonical = com.psyche.memo.data.repo.ProviderRepository.canonicalizeKey(providerId)
        val prefs = appContext.getSharedPreferences("memo_providers", Context.MODE_PRIVATE)
        return prefs.getString("base_url_$providerId", null)
            ?: prefs.getString("base_url_$canonical", null)
            ?: com.psyche.memo.llm.client.LlmDefaults.baseUrlFor(canonical)
    }

    /**
     * Drops every reference to the given models of [providerKey].
     *
     * Port of `_clearAssistantSelectionsForModels` (provider_detail_page.dart
     * L3083-3118) plus the deletion path's own cleanup (L1651-1662). Deleting a
     * model from a provider must not leave an assistant, a conversation or the
     * pinned list pointing at a model that no longer exists — otherwise the
     * chat silently falls back or errors on send.
     *
     * Three stores are swept:
     * 1. `assistant_rows` — an assistant whose `chatModelProvider`/`chatModelId`
     *    match loses its chat-model binding (back to "follow default").
     * 2. `conversation_rows` — a conversation pinning the model is reset to null.
     * 3. `pinned_models_v1` — the "providerKey::modelId" favourites list.
     */
    fun clearModelReferences(providerKey: String, modelIds: List<String>) {
        if (modelIds.isEmpty()) return
        val wanted = modelIds.toSet()

        runCatching {
            val store = assistantStore
            store.getAll()
                .filter { it.chatModelProvider == providerKey && it.chatModelId in wanted }
                .forEach { store.update(it.copy(chatModelProvider = null, chatModelId = null)) }
        }

        runCatching {
            conversationDao.getAll()
                .filter { it.chatModelProvider == providerKey && it.chatModelId in wanted }
                .forEach { conversationDao.setChatModel(it.id, null, null) }
        }

        runCatching {
            val pinned = com.psyche.memo.ui.readPinnedModels(this)
            val kept = pinned.filterNot { entry ->
                val separator = entry.indexOf("::")
                separator > 0 &&
                    entry.substring(0, separator) == providerKey &&
                    entry.substring(separator + 2) in wanted
            }.toSet()
            if (kept.size != pinned.size) {
                com.psyche.memo.ui.writePinnedModels(this, kept)
            }
        }
    }

    /**
     * Provider-wide sweep — `settings_provider.removeProviderConfig` +
     * `chatService.clearConversationModelOverrides(providerKey)`
     * (provider_detail_page.dart L283-299): deleting a provider must drop **every**
     * reference to it, not only the ids currently listed on it (a conversation can
     * still pin a model that was already removed from the provider).
     */
    fun clearProviderReferences(providerKey: String) {
        runCatching {
            val store = assistantStore
            store.getAll()
                .filter { it.chatModelProvider == providerKey }
                .forEach { store.update(it.copy(chatModelProvider = null, chatModelId = null)) }
        }
        runCatching {
            conversationDao.getAll()
                .filter { it.chatModelProvider == providerKey }
                .forEach { conversationDao.setChatModel(it.id, null, null) }
        }
        runCatching {
            val pinned = com.psyche.memo.ui.readPinnedModels(this)
            val kept = pinned.filterNot { entry ->
                val separator = entry.indexOf("::")
                separator > 0 && entry.substring(0, separator) == providerKey
            }.toSet()
            if (kept.size != pinned.size) com.psyche.memo.ui.writePinnedModels(this, kept)
        }
    }

    companion object {
        /** Agent Skills 的技能目录名（`<filesDir>/skills/`，照 RikkaHub）。 */
        const val SKILLS_DIR = "skills"

        /** 沙箱工作区目录（`<filesDir>/workspaces/`，照 RikkaHub）。 */
        const val WORKSPACES_DIR = "workspaces"

        /** 附件目录 —— 也是沙箱里的 `/upload`（与 AttachmentStore 用的是同一个）。 */
        const val UPLOAD_DIR = "upload"
    }
}

/**
 * assistant_provider.load() 94-100 — the persisted id is restored only while it
 * still exists among the assistant ids; a stale/deleted id falls back to null.
 */
internal fun resolveCurrentAssistantId(savedId: String?, existingIds: List<String>): String? =
    if (savedId != null && existingIds.contains(savedId)) savedId else null

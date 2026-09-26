package com.psyche.memo.provider.browser

import android.content.Context
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.net.Uri
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 一次会话 = **N 个标签（[BrowserTab]），其中恰有一个是活动标签**（spec §12.2）。
 *
 * 会话负责的事，标签自己不管：
 * - **发号器**（[nextEpoch]）：会话内所有标签共用一个单调递增计数器。代次不在各标签里自增，
 *   否则「A 标签的第 5 号」和「B 标签的第 5 号」会在切标签之后撞车，模型拿着旧 index 点到
 *   另一个页面上去 —— 那正是 index+代次 这套契约存在的理由所反对的事。换活动标签必然发新号，
 *   所以模型手上下发过的任何号在切换之后一律失效（只会多一次「请重新 find」，不会错点）。
 * - **动作串行**（[actionLock]）：一颗 `Mutex` 罩住整会话，含标签的开/关/切（spec §8）。
 * - **凭据什么时候清**：只有 [close]（换会话 / 清空并关闭）。关掉单个标签**绝不**清 cookie，
 *   理由写在 [BrowserTab.destroy]。
 * - **接管态**（[userControls] / [isMounted]）与那两笔「交给用户」的请求（JS 弹窗、文件选择）。
 *
 * 为什么按会话而不是全局单例：登录态与「模型正在操作哪个页面」都是会话语境，换会话必须干净；
 * 而原生侧只有一份全局 cookie jar，所以**同时只允许一个活动实例**（见 [BrowserSessionStore]）。
 *
 * 三条硬边界照 v1（spec §2）：只允许 https、不注入 JS 桥、不渲染文件与 content URI。
 */
class BrowserSession private constructor(private val appContext: Context) : BrowserGateway {

    companion object {
        const val VIEWPORT_WIDTH = 1280
        const val VIEWPORT_HEIGHT = 1600
        const val NAV_TIMEOUT_MS = 25_000L
        const val SCRIPT_TIMEOUT_MS = 8_000L
        const val SHOT_TIMEOUT_MS = 5_000L
        const val MAX_PNG_BYTES = 4 * 1024 * 1024

        /**
         * 标签上界。每一枚是一个真 WebView（几十 MB 级），模型若被网页诱导着「每个链接都开一个」
         * 就会把手机按死 —— 到顶之后 `browser_open(new_tab=true)` 回 `TAB_LIMIT`，
         * `window.open` 被拒并记账，**都不静默**。取 5：够「原来那页 + 两三个候选」，又远不到
         * 能让低内存机型崩掉的量。
         */
        const val MAX_TABS = 5

        // notice 累积的上界（条数与总长都要封）：只有 drainNotice() 会清，而 drain 是每颗
        // 工具调用一次 —— 接管期间页面循环弹 alert / 反复 window.open 时没人清，不封顶这串
        // 就按每条 ≤160 无上界增长、最后整串进信封给模型。超出丢最旧，丢的条数折进串尾
        // `…(+N)` 计数（各来源的条目在调用点已被 take(120/160) 封过，单条不可能顶破总长）。
        private const val NOTICE_MAX_ENTRIES = 3
        private const val NOTICE_MAX_CHARS = 480

        /**
         * WebView 必须在**主线程**创建 —— 下面的 `check` 判的就是 Main，不是泛泛的
         * 「有 Looper 的线程」（后台线程即使有 Looper 也不行）。会话在构造的那一刻就建第一枚
         * 标签的 WebView，所以这道闸管的是整个会话。
         *
         * 两个入口分开是**必需的**：suspend 那版内部 `withContext(Dispatchers.Main)`，而
         * Robolectric 的测试线程就是主 Looper 线程 —— Compose UI 测试（不能用
         * `MainDispatcherRule`，会和 Compose 规则抢调度器）里 `runBlocking { create() }`
         * 会当场自锁死（PORTING §5.40 那个坑的另一种形态）。所以主线程调用方直接用
         * [createOnMain]，后台调用方用 [create]。
         */
        fun createOnMain(appContext: Context): BrowserSession {
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "WebView 必须在主线程创建"
            }
            return BrowserSession(appContext)
        }

        suspend fun create(appContext: Context): BrowserSession =
            withContext(Dispatchers.Main) { createOnMain(appContext) }
    }

    private val tabs = ArrayList<BrowserTab>()
    private var activeIndex = 0

    /** 会话级发号器：见类头那条「代次不在各标签里自增」。只在主线程读写（所有 WebView 动作都在 Main）。 */
    private var epoch = 0

    private var userControlsState = false
    private var hostContainer: ViewGroup? = null

    /**
     * notice 累积本体 + 被上界挤掉（丢最旧）的条数，见 NOTICE_MAX_* 两个常量。
     * 写入全在 WebView 回调（主线程，各标签都会往这一份里记），drain 由工具层取走。
     * **按会话一份**，不是每标签一份：否则封顶变成 N×480 字符一起进信封。
     */
    private val notices = ArrayList<String>()
    private var noticesDropped = 0

    // @Volatile：isClosed 承诺任意线程可读（见属性注释），写发生在 Main，读方有非 Main 的。
    @Volatile private var closed = false

    /** 整会话一颗锁：动作（含标签增删切）串行是 spec §8 的承诺，模型并行发两颗调用时不许互相踩。 */
    private val actionLock = Mutex()

    private val tabsState = MutableStateFlow<List<BrowserTabInfo>>(emptyList())

    /**
     * 标签清单的**可观察**版本，给接管遮罩的标签条。写入点全在主线程（WebView 回调与标签操作），
     * 所以不需要额外的线程处理。
     */
    val tabsSnapshot: StateFlow<List<BrowserTabInfo>> = tabsState.asStateFlow()

    private val jsDialogState = MutableStateFlow<BrowserJsDialog?>(null)

    /** 接管态正等用户回答的 JS 弹窗（spec §12.4）；无头时永远是 null（那一支直接 cancel 掉）。 */
    val jsDialog: StateFlow<BrowserJsDialog?> = jsDialogState.asStateFlow()

    private val fileRequestState = MutableStateFlow<BrowserFileRequest?>(null)

    /** 接管态正等用户挑文件的请求（spec §12.4）；**模型永远拿不到这一支**，无头直接被拒。 */
    val fileRequest: StateFlow<BrowserFileRequest?> = fileRequestState.asStateFlow()

    init {
        tabs.add(BrowserTab(this, appContext))
        tabsState.value = renderTabs()
    }

    private val activeTab: BrowserTab get() = tabs[activeIndex]

    /** 界面诊断与单测用：活动标签那个 WebView。**界面挂视图请走 [attachTo]**，别直接拿它。 */
    internal val activeWebView: WebView get() = activeTab.view

    /**
     * 「有没有挂在界面上」：JS 弹窗与文件选择要不要交给用户的判据。
     *
     * 用挂载而不是 [userControls] 是因为遮罩正在拆的那一拍旗标还是 true、却已经没人收集请求了 ——
     * 那时候把 `JsResult` / 文件回调交出去，就是一次没人应答的永久卡死（两个对象内部都只有一次
     * 生效的保护，见 [BrowserJsDialog] 与 [BrowserFileRequest]）。
     */
    internal val isMounted: Boolean get() = hostContainer != null

    /** 会话内唯一发号：见类头。 */
    internal fun nextEpoch(): Int = ++epoch

    override val generation: Int get() = activeTab.generation
    override val url: String get() = activeTab.url
    override val title: String get() = activeTab.title
    override val loading: Boolean get() = !closed && activeTab.loading
    override val userControls: Boolean get() = userControlsState
    override val snapshot: BrowserPageSnapshot? get() = activeTab.snapshot

    /**
     * 纯读字段，任意线程安全：[closed] 标了 @Volatile，非 Main 的读方（store 的 `peek`、
     * 界面判活）拿到的都是某一刻的真值；「判活之后它被关掉」这种竞态由 store 的锁兜住。
     */
    override val isClosed: Boolean get() = closed

    // ------------------------------------------------------------------ 标签清单

    override fun tabInfos(): List<BrowserTabInfo> = renderTabs()

    private fun renderTabs(): List<BrowserTabInfo> =
        tabs.mapIndexed { i, tab -> BrowserTabInfo(i, tab.title, tab.url, i == activeIndex) }

    /** 标签的 url/标题落地、标签增删切之后都要让界面看见新清单（主线程调用）。 */
    internal fun tabsChanged() {
        if (!closed) tabsState.value = renderTabs()
    }

    private fun requireMain() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "WebView 操作必须在主线程" }
    }

    /** 建一枚新标签并设为活动。**主线程**、**不加锁**：调用方自己决定走 [actionLock] 还是界面点击。 */
    private fun addTabLocked(): BrowserTab {
        requireMain()
        val tab = BrowserTab(this, appContext)
        tabs.add(tab)
        activate(tabs.lastIndex)
        return tab
    }

    /**
     * 把第 [index] 标签设为活动：新活动标签挂到当前宿主（没宿主就只是「活动但离屏」），
     * 其余标签一律摘下来并还原离屏视口。
     *
     * **必然换代次并作废旧快照** —— 这是跨标签 index 别名的唯一解，见类头的发号器一段。
     *
     * 「摘旧」按**是不是当前挂着的那一枚**判，不按旧 `activeIndex` 判：[closeTabLocked] 删掉
     * 一枚之后再进来时，旧下标可能已经指向另一枚标签（前面删了一格）甚至越界（关的就是最后一枚）。
     * 层级上永远只挂着一枚，按身份判既简单又不依赖调用方把下标修对。
     */
    private fun activate(index: Int) {
        requireMain()
        check(index in tabs.indices) { "活动标签编号越界" }
        activeIndex = index
        tabs[index].bumpGenerationAndDropSnapshot()
        val container = hostContainer
        if (container != null) {
            tabs.forEach { if (it !== tabs[index]) it.detach() }
            tabs[index].attachTo(container)
        }
        tabsChanged()
    }

    /**
     * 关第 [index] 标签。主线程。
     *
     * **不清凭据**（见 [BrowserTab.destroy]）。关掉最后一个标签时补一枚空白标签：会话「恰有一个
     * 活动标签」这条不变量是整个网关委托的前提，`activeTab` 一旦可能越界，14 颗动作的入口都要
     * 各写一遍判空 —— 那才是真事故的来源。
     */
    private fun closeTabLocked(index: Int): Boolean {
        requireMain()
        if (closed || index !in tabs.indices) return false
        val activeBefore = tabs[activeIndex]
        val dying = tabs.removeAt(index)
        dying.destroy()
        if (tabs.isEmpty()) tabs.add(BrowserTab(this, appContext))
        if (dying === activeBefore) {
            // 活动的那枚被关了：继任者取同位的后一枚，没有就前一枚。走 activate（换代次 + 重挂），
            // 因为用户和模型看到的页面确实换了一页。
            activate(index.coerceAtMost(tabs.lastIndex))
        } else {
            // 活动标签还是**同一枚**，只是前面删掉一格让它左移了。这里只修下标，绝不走
            // activate：页面根本没变，换代次等于把模型手上那把 index 无端作废。
            activeIndex = tabs.indexOf(activeBefore)
            tabsChanged()
        }
        return true
    }

    override suspend fun openTab(url: String): Result<Unit> = actionLock.withLock {
        withContext(Dispatchers.Main) {
            if (closed) return@withContext Result.failure(IllegalStateException("RENDERER_GONE"))
            if (tabs.size >= MAX_TABS) return@withContext Result.failure(IllegalStateException("TAB_LIMIT"))
            addTabLocked().navigate(url)
        }
    }

    override suspend fun selectTab(index: Int): Result<Unit> = actionLock.withLock {
        withContext(Dispatchers.Main) {
            if (closed) return@withContext Result.failure(IllegalStateException("RENDERER_GONE"))
            if (index !in tabs.indices) return@withContext Result.failure(IllegalStateException("TAB_INDEX_INVALID"))
            if (index != activeIndex) activate(index)
            Result.success(Unit)
        }
    }

    override suspend fun closeTab(index: Int): Result<Unit> = actionLock.withLock {
        withContext(Dispatchers.Main) {
            if (closed) return@withContext Result.failure(IllegalStateException("RENDERER_GONE"))
            if (index !in tabs.indices) return@withContext Result.failure(IllegalStateException("TAB_INDEX_INVALID"))
            if (!closeTabLocked(index)) return@withContext Result.failure(IllegalStateException("RENDERER_GONE"))
            Result.success(Unit)
        }
    }

    // ------------------------------------------------------------------ 界面入口（主线程、不取锁）

    /**
     * 标签条点第 [index] 个。
     *
     * **不取 [actionLock]**：点击回调是主线程同步代码，它不可能在两个挂起点之间插进来，
     * 而会话里所有会改页面的动作都必须在 Main 才动手 —— 于是它天然排他。唯一能并发的是
     * 「模型那一次 `evaluateJavascript` 还挂着、用户切了标签」：那一回的回调落到已经离屏的
     * 标签上，之后工具侧那次换代次换代的是新活动标签，结果是**多作废一次把手**（安全方向），
     * 不是把动作点到错的页面上。
     */
    fun selectTabFromUi(index: Int): Boolean {
        requireMain()
        if (closed || index !in tabs.indices || index == activeIndex) return false
        activate(index)
        return true
    }

    /** 标签条那颗 ×。 */
    fun closeTabFromUi(index: Int): Boolean {
        requireMain()
        return !closed && closeTabLocked(index)
    }

    /** 标签条那颗 +（空白标签；地址栏在空白标签上输入即可）。到上界回 false。 */
    fun newTabFromUi(): Boolean {
        requireMain()
        if (closed || tabs.size >= MAX_TABS) return false
        addTabLocked()
        return true
    }

    /**
     * 地址栏回车 / 「前往」：在**活动标签**上导航（[isAllowedUrl] 那道闸照用，界面已经补过
     * `https://`，这里不二次猜用户意图）。
     */
    suspend fun navigateFromUi(raw: String): Result<Unit> = actionLock.withLock {
        withContext(Dispatchers.Main) {
            if (closed) return@withContext Result.failure(IllegalStateException("RENDERER_GONE"))
            activeTab.navigate(raw)
        }
    }

    // ------------------------------------------------------------------ 接管态交给用户的两笔

    internal fun takeJsDialog(
        kind: BrowserJsKind,
        message: String?,
        result: JsResult?,
        defaultValue: String? = null,
    ): Boolean {
        if (result == null) return true
        val shown = !closed && isMounted
        if (shown) {
            jsDialogState.value = BrowserJsDialog(kind, message.orEmpty(), defaultValue, result)
        } else {
            result.cancel()
        }
        appendNotice(
            "JS_" + kind.name + "_" + (if (shown) "SHOWN" else "SUPPRESSED") + ":" +
                message.orEmpty().take(160),
        )
        return true
    }

    /** 用户按了「确定 / 取消」。`text` 只有 prompt 用得上。**主线程**（`JsResult` 的要求）。 */
    fun answerJsDialog(accept: Boolean, text: String? = null) {
        requireMain()
        jsDialogState.value?.answer(accept, text)
        jsDialogState.value = null
    }

    /** 遮罩销毁时兜底：还挂着没人回答的弹窗就 cancel 掉，否则那一页的 JS 永远卡在弹窗上。 */
    internal fun dropPendingJsDialog() {
        jsDialogState.value?.answer(false, null)
        jsDialogState.value = null
    }

    internal fun takeFileChooser(
        callback: ValueCallback<Array<Uri>>?,
        params: WebChromeClient.FileChooserParams?,
    ): Boolean {
        if (callback == null) return true
        val types = filePickerMimeTypes(params?.acceptTypes?.toList().orEmpty())
        if (closed || !isMounted) {
            callback.onReceiveValue(null)
            appendNotice("FILE_CHOOSER_NEEDS_USER:" + types.joinToString().take(80))
            return true
        }
        fileRequestState.value = BrowserFileRequest(callback, types, params?.title?.toString())
        return true
    }

    /** 系统选择器回来了（空列表 = 用户取消）。**主线程**（回调本体是 WebView 的）。 */
    fun submitFiles(uris: List<Uri>) {
        requireMain()
        fileRequestState.value?.submit(uris)
        fileRequestState.value = null
    }

    /** 遮罩销毁 / 用户取消：必须把回调了结，否则那个 `<input type=file>` 永远按不动。 */
    internal fun dropPendingFileRequest() {
        fileRequestState.value?.cancel()
        fileRequestState.value = null
    }

    /**
     * 遮罩离开组合时的**一处**兜底：把两笔「等用户应答」的请求一次了结。
     *
     * 合成一颗给界面调用而不是让它连着调两个内部方法 —— 将来再加第三笔（比如地理授权请求）时，
     * 漏掉兜底的那一支就是「页面卡死且无声」，而这条路径单测很难自然覆盖到。
     */
    fun dropPendingInteractions() {
        dropPendingJsDialog()
        dropPendingFileRequest()
    }

    /**
     * `target=_blank` / `window.open` 开成新标签（spec §12.2）。拿不到 transport 或已到上界就拒，
     * 并记账让模型/用户知道（spec §7.3）。
     */
    internal fun openTabForWindow(resultMsg: Message?): Boolean {
        if (closed) return false
        val transport = resultMsg?.obj as? WebView.WebViewTransport
        if (transport == null) {
            appendNotice("NEW_WINDOW_REFUSED")
            return false
        }
        if (tabs.size >= MAX_TABS) {
            appendNotice("NEW_WINDOW_REFUSED:tab_limit")
            return false
        }
        val tab = addTabLocked()
        transport.webView = tab.view
        resultMsg?.sendToTarget()
        appendNotice("NEW_TAB_OPENED")
        return true
    }

    // ------------------------------------------------------------------ 动作委托（全部经活动标签 + 串行锁）

    fun takeOver() { userControlsState = true }

    fun release() { userControlsState = false }

    override fun publishSnapshot(snapshot: BrowserPageSnapshot?) { activeTab.publishSnapshot(snapshot) }

    override fun bumpGenerationAndDropSnapshot() = activeTab.bumpGenerationAndDropSnapshot()

    /**
     * 累积一笔「本机挡掉了什么」：单槽会让五种来源互相覆盖 —— 一轮里先弹 alert
     * 又要摄像头权限时，模型只看得见最后一笔，前一种被静默吞掉（spec §7.3）。
     *
     * 上界（NOTICE_MAX_*）：条数超限或**渲染后总长**超限都丢最旧，丢的条数折进串尾
     * `…(+N)`；至少留最新一条（每条在来源处已 take(≤160)，单条顶不穿总长）。
     */
    internal fun appendNotice(raw: String) {
        notices.add(raw)
        while (notices.size > NOTICE_MAX_ENTRIES ||
            (notices.size > 1 && renderedNoticeLength() > NOTICE_MAX_CHARS)
        ) {
            notices.removeAt(0)
            noticesDropped++
        }
    }

    /** 当前队列若立刻 drainNotice() 会渲染出的长度（含 `…(+N)` 尾计数），封顶判据用它。 */
    private fun renderedNoticeLength(): Int {
        val body = notices.joinToString("; ").length
        return if (noticesDropped == 0) body else body + "…(+$noticesDropped)".length
    }

    override fun drainNotice(): String? {
        if (notices.isEmpty()) return null
        val text = notices.joinToString("; ") +
            (if (noticesDropped == 0) "" else "…(+$noticesDropped)")
        notices.clear()
        noticesDropped = 0
        return text
    }

    /**
     * 把**活动标签**挂到界面上（先从别处摘下来）。必须在主线程（Compose 里就是）。
     *
     * 记住宿主是必需的：标签条上换标签时 `activate` 要把新活动标签挂进**同一个**容器，
     * 否则用户点第二个标签会看到一片空白（视图还挂在旧容器上）。
     *
     * **closed 闸**：已关的会话一律不挂。销毁后的 WebView 再 `addView` 回层级真机必炸，
     * 而这条路径是真实存在的 —— 遮罩还挂着的时候本会话实例可能被别的会话 `sessionFor`
     * 抢走并销毁（见 [BrowserSessionStore]），那一拍的重组就会走到这里。
     */
    fun attachTo(container: ViewGroup) {
        requireMain()
        if (closed) return
        hostContainer = container
        activeTab.attachTo(container)
    }

    /**
     * 只在这个容器**就是当前宿主**时落宿主并摘视图。给 [androidx.compose.ui.viewinterop.AndroidView]
     * 的 `onRelease` 用：换标签会让那个节点整枚重建（见遮罩里的 `key(session, 活动下标)`），
     * 而 Compose 不保证「旧节点释放」与「新节点建立」的先后 —— 无条件 `detach()` 的那一种顺序会把
     * 刚挂上去的新标签又摘下来、并把宿主清成 null，之后每次切标签都只剩一片白。
     * 按容器身份判，两种顺序都收敛到「宿主 = 新容器，活动标签挂在上面」。
     */
    fun detachFrom(container: ViewGroup) {
        requireMain()
        if (hostContainer === container) {
            hostContainer = null
            tabs.getOrNull(activeIndex)?.detach()
        }
    }

    /**
     * 摘下来之后必须把布局尺寸**还原回离屏视口**（还原在 [BrowserTab.detach] 里）：接管期间真
     * 布局把 WebView 重排成了手机尺寸，而 removeView 不会自己弹回去 —— 不还原的话
     * [userControls] 已交还、模型以为世界照旧，但 `screenshotPng` 截到手机尺寸、
     * `window.innerWidth` 变了、`find` 的 bounds 与模型回传的 x/y 全体错位。
     *
     * **closed 闸**：close() 之后仍会有 detach 进来 —— 换会话时是 `sessionFor` 关掉上一枚，
     * 而 `close()` 里 `detach()` 之前已经置了 `closed`；另一条是界面被拆掉时
     * `onDispose`/`onRelease` 补发 detach，与销毁没有先后保证。平台契约是「destroy() 之后不得
     * 再调用 WebView 的任何其它方法」，measure 在无 provider 时直接 IllegalStateException。
     * 闸放在 [BrowserTab.applyOffscreenViewport] 开头而不是这里：close() 自己调的 detach
     * **必须**保留 removeView（destroy 前必须先把视图从层级里摘掉），要拦的只是视口还原那两发。
     *
     * 这里对**每一枚**标签都 detach 一次：非活动标签本来就没挂着（removeView 对无父视图是
     * no-op），但它们的离屏视口要在「曾经挂过又被切走」的路径上被还原，全量走一遍最省心。
     */
    fun detach() {
        requireMain()
        hostContainer = null
        tabs.forEach { it.detach() }
    }

    override suspend fun navigate(url: String): Result<Unit> = actionLock.withLock {
        withContext(Dispatchers.Main) { activeTab.navigate(url) }
    }

    override suspend fun goBack(): Boolean = actionLock.withLock {
        withContext(Dispatchers.Main) { activeTab.goBack() }
    }

    override suspend fun goForward(): Boolean = actionLock.withLock {
        withContext(Dispatchers.Main) { activeTab.goForward() }
    }

    override suspend fun reload(): Result<Unit> = actionLock.withLock {
        withContext(Dispatchers.Main) { activeTab.reload() }
    }

    override suspend fun run(script: String, timeoutMs: Long): Pair<Boolean, String> =
        actionLock.withLock { withContext(Dispatchers.Main) { activeTab.run(script, timeoutMs) } }

    override suspend fun screenshotPng(): ByteArray = actionLock.withLock {
        withContext(Dispatchers.Main) { activeTab.screenshotPng() }
    }

    /**
     * 关整会话：只销毁每一枚标签。**不动凭据**（2026-09-26 改）。
     *
     * 原来这里跟着清 `removeAllCookies` + `WebStorage.deleteAllData`，理由是 spec §2 的
     * 「每会话隔离靠关时就地清」。真机反馈把这条设计打穿了：换到别的对话再回来 ⇒ 站点登录态
     * 全没了，用户让助手「给 DeepSeek 发条消息」永远被弹回登录页（「他说老是返回登录状态」）。
     * 参照实现 Eta 的做法是**登录态全局持久**，清只有一处：用户手动「清空浏览器数据」。
     * 现在照它 —— 清数据挪到 [BrowserSessionStore.closeAll] 的 `clearSiteData` 参数上，
     * 只有「清空并关闭」与「关掉全局开关」会传到那里。
     *
     * 单标签关闭同样从不清（见 [BrowserTab.destroy]）。
     */
    suspend fun close() = withContext(Dispatchers.Main) {
        if (closed) return@withContext
        closed = true
        // 两枚在飞的 deferred 都由 destroyTab 以**值**解套，而不是异常完成：
        // completeExceptionally 会一路抛到 ToolRunner 被归成 tool_crashed，RENDERER_GONE/CANCELLED
        // 这两个码就到不了信封；以带原因的值完成，await 侧走正常失败通道，也不会留未处理的异常完成。
        // 不解套的话被抢会话的 navigate 要干等满 NAV_TIMEOUT_MS（25 s，几乎吃光这族工具那颗 30 s
        // 的外层 deadline，见 BrowserTools.TIMEOUT_MS）。
        dropPendingJsDialog()
        dropPendingFileRequest()
        tabs.forEach { it.destroy() }
        // **不清空 `tabs`**：close 之后仍会有 `detach()` / 读 `activeWebView` 进来（AndroidView 的
        // onRelease 与 DisposableEffect 的 onDispose 都不与销毁排序），留一枚已销毁的标签在列表里
        // 是「所有入口都被 closed 闸挡住」；清空它则那些入口直接 IndexOutOfBounds —— 后者是崩溃，
        // 前者是被闸掉。钉在 BrowserSessionStoreTest.detachingAfterCloseMustNotResetTheViewportAgain。
        hostContainer = null
        tabsState.value = emptyList()
    }
}

/**
 * 一条地址能不能进 WebView。
 *
 * **2026-09-26 改（spec §15）**：原来只收 https（"明文 http 也拒：升级是站点的事"），但真机结果
 * 是内网/老站/纯明文站全都开不了、自动化直接废掉，用户据此拍板「不要弄很高的安全」。现在
 * **http 与 https 都收**；仍然拒的是 `file://` / `content://` / `javascript:` / `data:` /
 * `about:` 与任何自定义协议 —— 那几样炸的不是网页，是**用户手机里的文件和自己 app 的入口**，
 * 放开它对自动化一点用没有（页面自己跳不到那里面）。
 */
fun isAllowedUrl(raw: String): Boolean {
    val v = raw.trim().lowercase()
    return v.startsWith("https://") || v.startsWith("http://")
}

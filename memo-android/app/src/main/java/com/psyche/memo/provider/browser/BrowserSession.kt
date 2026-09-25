package com.psyche.memo.provider.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一次会话 = 一个离屏 WebView。
 *
 * 为什么按会话而不是全局单例：登录态与「模型正在操作哪个页面」都是会话语境，换会话
 * 必须干净；而原生侧只有一份全局 cookie jar，所以**同时只允许一个活动实例**
 * （见 [BrowserSessionStore]），关的时候就地清干净。
 *
 * 三条硬边界（spec §2）：只允许 https、不注入 JS 桥、不渲染文件与 content URI。
 */
// 注解放类上才有覆盖力：javaScriptEnabled = true 在 init 块里，属性级 @SuppressLint
// 罩不住它（lint 实测如此）。
@SuppressLint("SetJavaScriptEnabled")
class BrowserSession private constructor(private val appContext: Context) : BrowserGateway {

    companion object {
        const val VIEWPORT_WIDTH = 1280
        const val VIEWPORT_HEIGHT = 1600
        const val NAV_TIMEOUT_MS = 25_000L
        const val SCRIPT_TIMEOUT_MS = 8_000L
        const val SHOT_TIMEOUT_MS = 5_000L
        const val MAX_PNG_BYTES = 4 * 1024 * 1024

        // close() 解 pendingScript 的哨兵：evaluateJavascript 的回调永远给 JSON（字符串结果
        // 至少带一层引号），这种裸词不可能与真返回值撞车。
        private const val SCRIPT_CLOSED = "MEMO_SESSION_CLOSED"

        /**
         * notice 累积的上界（条数与总长都要封）：只有 drainNotice() 会清，而 drain 是每颗
         * 工具调用一次 —— 接管期间页面循环弹 alert / 反复 window.open 时没人清，不封顶这串
         * 就按每条 ≤160 无上界增长、最后整串进信封给模型。超出丢最旧，丢的条数折进串尾
         * `…(+N)` 计数（各来源的条目在调用点已被 take(120/160) 封过，单条不可能顶破总长）。
         */
        private const val NOTICE_MAX_ENTRIES = 3
        private const val NOTICE_MAX_CHARS = 480

        /**
         * WebView 必须在**主线程**创建 —— 下面的 `check` 判的就是 Main，不是泛泛的
         * 「有 Looper 的线程」（后台线程即使有 Looper 也不行）。
         *
         * 两个入口分开是**必需的**：suspend 那版内部 `withContext(Dispatchers.Main)`，而
         * Robolectric 的测试线程就是主 Looper 线程 —— Compose UI 测试（不能用
         * `MainDispatcherRule`，会和 Compose 规则抢调度器）里 `runBlocking { create() }`
         * 会当场自锁死（PORTING §5.40 那个坑的另一种形态）。所以主线程调用方直接用
         * [createOnMain]，后台调用方用 [create]。
         */
        fun createOnMain(appContext: Context): BrowserSession {
            check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                "WebView 必须在主线程创建"
            }
            return BrowserSession(appContext)
        }

        suspend fun create(appContext: Context): BrowserSession =
            withContext(Dispatchers.Main) { createOnMain(appContext) }
    }

    val view: WebView = WebView(
        // 裸 application context 没有主题，WebView 内部要读 attr。
        android.view.ContextThemeWrapper(
            appContext,
            android.R.style.Theme_DeviceDefault_Light_NoActionBar,
        ),
    )

    private var generationState = 0
    private var snapshotState: BrowserPageSnapshot? = null
    private var userControlsState = false
    private var urlState = ""
    private var navigation: CompletableDeferred<String>? = null
    private var pendingScript: CompletableDeferred<String>? = null
    /**
     * notice 的累积本体 + 被上界挤掉（丢最旧）的条数，见 NOTICE_MAX_* 两个常量。
     * 写入全在 WebView 回调（主线程），drain 由工具层取走 —— 与改造前的单槽 `notice` 同一
     * 线程模型，没有新增共享状态。
     */
    private val notices = ArrayList<String>()
    private var noticesDropped = 0
    // @Volatile：isClosed 承诺任意线程可读（见属性注释），写发生在 Main，读方有非 Main 的。
    @Volatile private var closed = false

    /** 同一会话的动作串行（spec §8）：模型并行发两颗调用时不许互相踩。 */
    private val actionLock = Mutex()

    override val generation: Int get() = generationState
    override val url: String get() = urlState
    override val userControls: Boolean get() = userControlsState
    override val snapshot: BrowserPageSnapshot? get() = snapshotState

    /**
     * 纯读字段，任意线程安全：[closed] 标了 @Volatile，非 Main 的读方（store 的 `peek`、
     * 界面判活）拿到的都是某一刻的真值；「判活之后它被关掉」这种竞态由 store 的锁兜住。
     */
    override val isClosed: Boolean get() = closed

    init {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            // 两条 FileURLs 旗标自 API 30 起废弃（平台恒 false），显式关闭 + 注释掉
            // 废弃告警：万一低版本 WebView 内核仍读它们，这道防线还在（spec §2）。
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        view.setBackgroundColor(Color.WHITE)
        // 离屏也要有确定尺寸：否则 1280 宽的桌面版页面按手机宽度渲染。
        applyOffscreenViewport()
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean =
                !isAllowedUrl(request.url.toString())

            override fun onPageFinished(webView: WebView, finishedUrl: String?) {
                urlState = finishedUrl.orEmpty()
                // 页面落地：之前的 index 全部作废。
                bumpGenerationAndDropSnapshot()
                navigation?.complete("ok")
            }

            // 用旧的 4 参回调是为了它的触发条件：只在**主框架**加载失败时回调，
            // 免掉新回调（onReceivedError(request, error)）还要自己过 request.isForMainFrame。
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onReceivedError(
                webView: WebView,
                errorCode: Int,
                description: String?,
                failingUrl: String?,
            ) {
                navigation?.complete("NAV_FAILED:$errorCode")
            }
        }
        /**
         * 无头执行必须**自己吃掉**弹窗与权限请求。`onJsAlert` 返回 false 会让 WebView 去弹
         * 它自己的对话框 —— 那正是不该出现的；所以这里是「取消 + 记一笔 + 返回 true」，
         * 再由 [drainNotice] 把「本机挡掉了什么」交给工具层写进信封。spec §7.3 要的是
         * 「拒绝要说明」这件事，不是字面上的 return false。
         */
        view.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(
                webView: WebView, url: String?, message: String?, result: JsResult?,
            ): Boolean {
                appendNotice("JS_ALERT_SUPPRESSED:" + message.orEmpty().take(160))
                result?.cancel()
                return true
            }

            override fun onJsConfirm(
                webView: WebView, url: String?, message: String?, result: JsResult?,
            ): Boolean {
                appendNotice("JS_CONFIRM_SUPPRESSED:" + message.orEmpty().take(160))
                result?.cancel()
                return true
            }

            override fun onJsPrompt(
                webView: WebView,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?,
            ): Boolean {
                appendNotice("JS_PROMPT_SUPPRESSED:" + message.orEmpty().take(160))
                result?.cancel()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                appendNotice("PERMISSION_DENIED:" + request.resources.joinToString().take(120))
                request.deny()
            }

            /**
             * 新窗口一律不开：Memo 没有多标签（spec §1 的非目标），开了就是丢页面。
             * 拒绝也要在信封里说明（spec §7.3），否则模型收到 `ok` 会以为页面还在。
             */
            override fun onCreateWindow(
                webView: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?,
            ): Boolean {
                // 回调签名里没有目标 URL；hit test 是唯一能顺到链接地址的地方，拿不到就只报码。
                val target = view.hitTestResult?.extra
                appendNotice(if (target == null) "NEW_WINDOW_REFUSED" else "NEW_WINDOW_REFUSED:" + target.take(120))
                return false
            }
        }
        view.setDownloadListener { url, _, contentDisposition, _, _ ->
            // contentDisposition 是服务端任意串：这里必须自己封一道（其余来源在各自回调点已 take），
            // 否则 NOTICE_MAX_CHARS 的「每条有界」前提在这条来源上不成立。
            appendNotice("DOWNLOAD_REFUSED:" + url.take(120) +
                (if (contentDisposition.isNullOrBlank()) "" else "|" + contentDisposition.take(80)))
        }
    }

    /**
     * 累积一笔「本机挡掉了什么」：单槽会让五种来源互相覆盖 —— 一轮里先弹 alert
     * 又要摄像头权限时，模型只看得见最后一笔，前一种被静默吞掉（spec §7.3）。
     *
     * 上界（NOTICE_MAX_*）：条数超限或**渲染后总长**超限都丢最旧，丢的条数折进串尾
     * `…(+N)`；至少留最新一条（每条在来源处已 take(≤160)，单条顶不穿总长）。
     */
    private fun appendNotice(raw: String) {
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

    fun takeOver() { userControlsState = true }

    fun release() { userControlsState = false }

    override fun publishSnapshot(snapshot: BrowserPageSnapshot?) { snapshotState = snapshot }

    override fun bumpGenerationAndDropSnapshot() {
        generationState++
        snapshotState = null
    }

    /** 把同一个实例挂到界面上（先从别处摘下来）。必须在主线程（Compose 里就是）。 */
    fun attachTo(container: ViewGroup) {
        (view.parent as? ViewGroup)?.removeView(view)
        if (view.parent == null) {
            // 尺寸契约写明白：铺满宿主。之前靠「默认 LayoutParams 恰好是 MATCH_PARENT」的隐式约定。
            container.addView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    /**
     * 摘下来之后必须把布局尺寸**还原回离屏视口**：接管期间真布局把 WebView 重排成了手机
     * 尺寸，而 removeView 不会自己弹回去 —— 不还原的话 [userControls] 已交还、模型以为
     * 世界照旧，但 `screenshotPng` 截到手机尺寸、`window.innerWidth` 变了、`find` 的
     * bounds 与模型回传的 x/y 全体错位。主线程调用（measure/layout 的要求）。
     *
     * **closed 闸**：close() 之后仍会有 detach 进来 —— 换会话时是 `sessionFor` 关掉上一枚，
     * 而 `close()` 里 `detach()` 之前已经置了 `closed`，那条路径由 store 的 `mutex` 排在
     * `withContext(Main)` 之后；另一条是界面被拆掉时 `onDispose`/`onRelease` 补发 detach，
     * 与销毁没有先后保证。平台契约是「destroy() 之后不得再调用本类的任何其它方法」，
     * measure 在无 provider 时直接 IllegalStateException。闸放这里而不是 detach() 开头：
     * close() 自己调的 detach **必须**保留 removeView（destroy 前必须先把视图从层级里摘掉），
     * 要拦的只是视口还原那两发 —— 且此刻实例已弃用，还原本身毫无意义。
     */
    private fun applyOffscreenViewport() {
        if (closed) return
        view.measure(
            View.MeasureSpec.makeMeasureSpec(VIEWPORT_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(VIEWPORT_HEIGHT, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
    }

    /**
     * 解套路径上的 stopLoading（超时支与取消支）。`withContext` 会把这块**重新 post 回
     * Main**，中间 close() 可能插进来（它自己就带 stopLoading+destroy）—— destroy 之后
     * 再调 view 的任何方法都违反平台契约（Finding 1 的同源两处），而加载本来就该停的
     * 也已经停了，跳过就是正确行为。
     */
    private suspend fun stopLoadingUnlessClosed() {
        withContext(NonCancellable) { if (!closed) view.stopLoading() }
    }

    fun detach() {
        (view.parent as? ViewGroup)?.removeView(view)
        applyOffscreenViewport()
    }

    override suspend fun navigate(url: String): Result<Unit> = actionLock.withLock {
        withContext(Dispatchers.Main) {
            if (closed) return@withContext Result.failure(IllegalStateException("RENDERER_GONE"))
            if (!isAllowedUrl(url)) return@withContext Result.failure(IllegalStateException("BLOCKED_SCHEME"))
            val done = CompletableDeferred<String>()
            navigation = done
            view.loadUrl(url)
            // 用「带原因的完成」而不是异常完成：`await()` 就不会抛，外层取消（用户点停止）
            // 仍是唯一能让它抛的东西 —— 那必须透传，不能被读成「导航失败」（spec §8）。
            // try/finally 统一归还 deferred：六条路径各写一遍清线迟早漏一条，
            // 挂在外面的那枚会被下一次导航的 onPageFinished「串台」完成掉。
            val verdict = try {
                withTimeoutOrNull(NAV_TIMEOUT_MS) { done.await() }
            } catch (e: CancellationException) {
                // 取消那次加载还在飞：不 stopLoading，它照样 onPageFinished，而那时
                // 字段里很可能已是下一颗 navigate 的 deferred —— 下一次导航会被提前报成功。
                stopLoadingUnlessClosed()
                throw e
            } finally {
                if (navigation === done) navigation = null
            }
            when (verdict) {
                null -> {
                    stopLoadingUnlessClosed()
                    Result.failure(IllegalStateException("NAV_TIMEOUT"))
                }
                "ok" -> {
                    // await 排回 Main 的窗口里 close() 可能插队（deferred 已被真 onPageFinished
                    // 解套成 "ok"，destroy 却先落地）：view.url 同属「destroy 后不得再调」的
                    // 其它方法（Finding 1 的同源第三处）。这个结果不再可信，报 RENDERER_GONE。
                    if (closed) Result.failure(IllegalStateException("RENDERER_GONE"))
                    else {
                        urlState = view.url.orEmpty()
                        Result.success(Unit)
                    }
                }
                else -> Result.failure(IllegalStateException(verdict))
            }
        }
    }

    override suspend fun goBack(): Boolean = actionLock.withLock {
        withContext(Dispatchers.Main) {
            if (closed || !view.canGoBack()) return@withContext false
            view.goBack()
            bumpGenerationAndDropSnapshot()
            true
        }
    }

    override suspend fun reload(): Result<Unit> = actionLock.withLock {
        withContext(Dispatchers.Main) {
            if (closed) return@withContext Result.failure(IllegalStateException("RENDERER_GONE"))
            val done = CompletableDeferred<String>()
            navigation = done
            view.reload()
            val verdict = try {
                withTimeoutOrNull(NAV_TIMEOUT_MS) { done.await() }
            } catch (e: CancellationException) {
                // 同 navigate：被放弃的加载照样会 onPageFinished，必须停掉，
                // 否则它去 complete 下一颗动作的 deferred。
                stopLoadingUnlessClosed()
                throw e
            } finally {
                if (navigation === done) navigation = null
            }
            bumpGenerationAndDropSnapshot()
            when (verdict) {
                null -> {
                    stopLoadingUnlessClosed()
                    Result.failure(IllegalStateException("NAV_TIMEOUT"))
                }
                // 与 navigate 同口径：**只有 "ok" 算成功**（Finding 3，fix round 2）。
                // close() 注入的 "RENDERER_GONE" 与 onReceivedError 的 "NAV_FAILED:<code>"
                // 过去走 else 被读成「重载成功」——被放弃/失败的加载替下一次动作报成功，
                // 正是裁决 3 要消灭的那类口径。
                "ok" -> Result.success(Unit)
                else -> Result.failure(IllegalStateException(verdict))
            }
        }
    }

    override suspend fun run(script: String, timeoutMs: Long): Pair<Boolean, String> =
        actionLock.withLock { withContext(Dispatchers.Main) {
            if (closed) return@withContext false to "RENDERER_GONE"
            val deferred = CompletableDeferred<String>()
            pendingScript = deferred
            view.evaluateJavascript(script) { value -> deferred.complete(value ?: "null") }
            // 取消（用户点停止）= **结果未知**：点了的鼠标事件可能已经生效。停掉加载、
            // 原样上抛，让 ToolRunner 去回那句「不要假设成功、不要盲目重试」（spec §8）。
            val raw = try {
                withTimeoutOrNull(timeoutMs) { deferred.await() }
            } catch (e: CancellationException) {
                stopLoadingUnlessClosed()
                throw e
            } finally {
                pendingScript = null
            }
            if (raw == null) {
                stopLoadingUnlessClosed()
                return@withContext false to "SCRIPT_TIMEOUT"
            }
            // close() 以值解套的那次唤醒：把码原样交出去，别把哨兵喂给 unwrap 变成 BAD_JSON。
            if (raw == SCRIPT_CLOSED) return@withContext false to "CANCELLED"
            BrowserScripts.unwrap(raw)
        } }

    /** 原尺寸截图（不缩放：缩放会让小字不可读）。主线程画位图。上锁同 navigate：串行是 spec §8 的承诺。 */
    override suspend fun screenshotPng(): ByteArray = actionLock.withLock {
        withContext(Dispatchers.Main) {
            if (closed) return@withContext ByteArray(0)
            val out = ByteArrayOutputStream()
            val bmp = Bitmap.createBitmap(
                maxOf(1, view.width),
                maxOf(1, view.height),
                Bitmap.Config.ARGB_8888,
            )
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            view.draw(canvas)
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    /** 不在 [BrowserGateway] 上：只有 [BrowserSessionStore]（换会话 / closeAll）需要关它。 */
    suspend fun close() = withContext(Dispatchers.Main) {
        if (closed) return@withContext
        closed = true
        // 两枚在飞的 deferred 都**以值解套**而不是异常：completeExceptionally 会一路抛到
        // ToolRunner 被归成 tool_crashed，RENDERER_GONE/CANCELLED 这两个码就到不了信封；
        // 以带原因的值完成，await 侧走正常失败通道，也不会留未处理的异常完成。
        // 不解锁的话被抢会话的 navigate 要干等满 NAV_TIMEOUT_MS（25 s，几乎吃光 browser_use 的 30 s）。
        navigation?.complete("RENDERER_GONE")
        navigation = null
        pendingScript?.complete(SCRIPT_CLOSED)
        pendingScript = null
        detach()
        view.stopLoading()
        view.destroy()
        // 凭据是 app 全局的：会话结束只能靠「清」来隔离。
        // `WebStorage.getInstance()` 就是唯一入口：公开 android.jar（35/36/37）里
        // 从来没有带 Context 的重载（那是 CookieManager 的 API 形状），无从分流。
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        WebStorage.getInstance().deleteAllData()
    }
}

/** 只允许 https（明文 http 也拒：升级是站点的事，不是替模型开洞）。 */
fun isAllowedUrl(raw: String): Boolean = raw.trim().lowercase().startsWith("https://")

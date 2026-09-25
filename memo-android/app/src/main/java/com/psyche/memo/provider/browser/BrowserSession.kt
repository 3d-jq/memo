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

        /**
         * WebView 只能在有 Looper 的线程创建。
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
    private var notice: String? = null
    private var closed = false

    /** 同一会话的动作串行（spec §8）：模型并行发两颗调用时不许互相踩。 */
    private val actionLock = Mutex()

    override val generation: Int get() = generationState
    override val url: String get() = urlState
    override val userControls: Boolean get() = userControlsState
    override val snapshot: BrowserPageSnapshot? get() = snapshotState
    override val canGoBack: Boolean get() = view.canGoBack()

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
        view.measure(
            View.MeasureSpec.makeMeasureSpec(VIEWPORT_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(VIEWPORT_HEIGHT, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean =
                !isAllowedUrl(request.url.toString())

            override fun onPageFinished(webView: WebView, finishedUrl: String?) {
                urlState = finishedUrl.orEmpty()
                // 页面落地：之前的 index 全部作废。
                bumpGenerationAndDropSnapshot()
                navigation?.complete("ok")
            }

            // 页面级旧签名能直接拿到 failingUrl 拼成 NAV_FAILED 原因；新版回调反而给不了。
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
                notice = "JS_ALERT_SUPPRESSED:" + message.orEmpty().take(160)
                result?.cancel()
                return true
            }

            override fun onJsConfirm(
                webView: WebView, url: String?, message: String?, result: JsResult?,
            ): Boolean {
                notice = "JS_CONFIRM_SUPPRESSED:" + message.orEmpty().take(160)
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
                notice = "JS_PROMPT_SUPPRESSED:" + message.orEmpty().take(160)
                result?.cancel()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                notice = "PERMISSION_DENIED:" + request.resources.joinToString().take(120)
                request.deny()
            }

            /** 新窗口一律不开：Memo 没有多标签（spec §1 的非目标），开了就是丢页面。 */
            override fun onCreateWindow(
                webView: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?,
            ): Boolean = false
        }
        view.setDownloadListener { url, _, contentDisposition, _, _ ->
            notice = "DOWNLOAD_REFUSED:" + url.take(120) +
                (if (contentDisposition.isNullOrBlank()) "" else "|" + contentDisposition)
        }
    }

    override fun drainNotice(): String? = notice.also { notice = null }

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
        if (view.parent == null) container.addView(view)
    }

    fun detach() { (view.parent as? ViewGroup)?.removeView(view) }

    override suspend fun navigate(url: String): Result<Unit> = actionLock.withLock {
        withContext(Dispatchers.Main) {
            if (closed) return@withContext Result.failure(IllegalStateException("RENDERER_GONE"))
            if (!isAllowedUrl(url)) return@withContext Result.failure(IllegalStateException("BLOCKED_SCHEME"))
            val done = CompletableDeferred<String>()
            navigation = done
            view.loadUrl(url)
            // 用「带原因的完成」而不是异常完成：`await()` 就不会抛，外层取消（用户点停止）
            // 仍是唯一能让它抛的东西 —— 那必须透传，不能被读成「导航失败」（spec §8）。
            val verdict = try {
                withTimeoutOrNull(NAV_TIMEOUT_MS) { done.await() }
            } catch (e: CancellationException) {
                if (navigation === done) navigation = null
                withContext(NonCancellable) { view.stopLoading() }
                throw e
            }
            when (verdict) {
                null -> {
                    if (navigation === done) navigation = null
                    withContext(NonCancellable) { view.stopLoading() }
                    return@withContext Result.failure(IllegalStateException("NAV_TIMEOUT"))
                }
                "ok" -> Unit
                else -> {
                    if (navigation === done) navigation = null
                    return@withContext Result.failure(IllegalStateException(verdict))
                }
            }
            if (navigation === done) navigation = null
            urlState = view.url.orEmpty()
            Result.success(Unit)
        }
    }

    override suspend fun goBack(): Boolean = withContext(Dispatchers.Main) {
        if (closed || !view.canGoBack()) return@withContext false
        view.goBack()
        bumpGenerationAndDropSnapshot()
        true
    }

    override suspend fun reload(): Result<Unit> = withContext(Dispatchers.Main) {
        if (closed) return@withContext Result.failure(IllegalStateException("RENDERER_GONE"))
        val done = CompletableDeferred<String>()
        navigation = done
        view.reload()
        // finally：取消路径（外层协程被点停止）也要先把这枚 deferred 交还，
        // 否则迟到的 onPageFinished 会去 complete 一枚已经没人等的 deferred。
        val verdict = try {
            withTimeoutOrNull(NAV_TIMEOUT_MS) { done.await() }
        } finally {
            if (navigation === done) navigation = null
        }
        bumpGenerationAndDropSnapshot()
        if (verdict == null) Result.failure(IllegalStateException("NAV_TIMEOUT"))
        else Result.success(Unit)
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
                withContext(NonCancellable) { view.stopLoading() }
                throw e
            } finally {
                pendingScript = null
            }
            if (raw == null) {
                withContext(NonCancellable) { view.stopLoading() }
                return@withContext false to "SCRIPT_TIMEOUT"
            }
            BrowserScripts.unwrap(raw)
        } }

    /** 原尺寸截图（不缩放：缩放会让小字不可读）。主线程画位图。 */
    override suspend fun screenshotPng(): ByteArray = withContext(Dispatchers.Main) {
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

    /** 不在 [BrowserGateway] 上：只有 [BrowserSessionStore]（换会话 / closeAll）需要关它。 */
    suspend fun close() = withContext(Dispatchers.Main) {
        if (closed) return@withContext
        closed = true
        pendingScript?.completeExceptionally(IllegalStateException("CANCELLED"))
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

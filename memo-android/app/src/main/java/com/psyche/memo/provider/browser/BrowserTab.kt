package com.psyche.memo.provider.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一个标签 = **一个 WebView + 它自己的页面状态**（url / title / 代次 / 快照 / 在飞的导航与脚本）。
 *
 * 它是 [BrowserSession] 的内部构件：会话负责「哪一标签是活动的」「动作串行」「凭据什么时候清」，
 * 标签只管自己那一页。2026-09-26 之前本类的内容就是 `BrowserSession` 的本体（一个会话一个
 * WebView），拆标签基座时整块搬过来，**一条边界都没松**：只允许 https、不注入 JS 桥、
 * 不渲染文件与 content URI（spec §2）。
 *
 * **代次由会话分配**（[BrowserSession.nextEpoch]），不在本类里自增：会话内所有标签共用一个
 * 单调递增的发号器，于是「模型手上那个号」在全会话唯一 —— 切标签时新活动标签也会拿到一个新号，
 * 跨标签的 index 别名（A 标签的第 3 号撞上 B 标签的第 3 号）因此不可能发生。
 *
 * **不加锁**：串行是 [BrowserSession] 的职责（那颗 `actionLock` 覆盖整会话，含标签的增删改）。
 * 本类的方法只假定**主线程**执行（`withContext(Dispatchers.Main)`），从会话的加锁入口进来。
 *
 * @param owner 所属会话。弹窗与「本机挡掉了什么」都要记到**会话**那一份账上（`appendNotice` 的
 *   上界是全局的，按标签各存一份会变成 5×480 字符），JS 弹窗/文件选择还要读 `userControls` 与
 *   「有没有挂在界面上」这两个会话级事实。
 */
@SuppressLint("SetJavaScriptEnabled")
internal class BrowserTab(
    private val owner: BrowserSession,
    appContext: Context,
) {

    companion object {
        // destroyTab()/close() 解 pendingScript 的哨兵：evaluateJavascript 的回调永远给 JSON
        // （字符串结果至少带一层引号），这种裸词不可能与真返回值撞车。
        internal const val SCRIPT_CLOSED = "MEMO_TAB_CLOSED"
    }

    val view: WebView = WebView(
        // 裸 application context 没有主题，WebView 内部要读 attr。
        android.view.ContextThemeWrapper(
            appContext,
            android.R.style.Theme_DeviceDefault_Light_NoActionBar,
        ),
    )

    /** 会话发号器给的当前代次；页面落地/动作生效时由 [bumpGenerationAndDropSnapshot] 换新号。 */
    var generation: Int = owner.nextEpoch()
        private set

    var snapshot: BrowserPageSnapshot? = null
        private set

    var url: String = ""
        private set

    /** 标签条要显示标题：`onReceivedTitle` 落地，比 onPageFinished 早，也覆盖 SPA 改标题。 */
    var title: String = ""
        private set

    /**
     * 主框架还在加载（`onProgressChanged` 的 progress<100）。给工具侧「动作之后等一等」用：
     * `onPageFinished` 只代表这一跳的骨架落地，SPA 的第二跳（列表、聊天窗）常常还在飞。
     */
    var loading: Boolean = false
        private set

    /**
     * 本标签是否已销毁。**只有会话级 close() 才清凭据**，销毁单个标签不动 cookie（否则关掉
     * 一个登录态标签，会把别的标签的登录态一起干掉 —— spec §12.2 点名要写死这条）。
     */
    var isDestroyed: Boolean = false
        private set

    /** 会话已关 或 本标签已毁：两条都算「这页已经不能碰了」。 */
    private val dead: Boolean get() = isDestroyed || owner.isClosed

    private var navigation: CompletableDeferred<String>? = null
    private var pendingScript: CompletableDeferred<String>? = null

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
            // 多标签之后 `target=_blank` / `window.open` 有了去处（onCreateWindow → 新标签），
            // 关掉它等于永远开不出新标签 —— 代价见 onCreateWindow 那段注释。
            setSupportMultipleWindows(true)
        }
        view.setBackgroundColor(Color.WHITE)
        // 离屏也要有确定尺寸：否则 1280 宽的桌面版页面按手机宽度渲染。
        applyOffscreenViewport()
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean =
                !isAllowedUrl(request.url.toString())

            override fun onPageFinished(webView: WebView, finishedUrl: String?) {
                loading = false
                url = finishedUrl.orEmpty()
                // 页面落地：这个标签之前的 index 全部作废。
                bumpGenerationAndDropSnapshot()
                navigation?.complete("ok")
                owner.tabsChanged()
            }

            // 用旧的 4 参回调是为了它的触发条件：只在**主框架**加载失败时回调，
            // 免掉新回调（onReceivedError(request, error)）还要自己判 request.isForMainFrame。
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
         * 它自己的对话框 —— 那正是不该出现的；所以这里是「记账 + 交给用户或 cancel + 返回 true」，
         * 再由会话的 `drainNotice` 把「本机处理了什么」交给工具层写进信封。spec §7.3 要的是
         * 「拒绝要说明」，不是字面上的 return false。
         *
         * 接管态（用户正看着这一页）多问用户一次：alert/confirm/prompt 弹成 Memo 自己的对话框
         * 并回填结果（spec §12.4）。判据是**「有没有挂在界面上」**（`isMounted`）而不是
         * `userControls` —— 遮罩正在拆的那一拍，旗标还是 true 却已经没人收集弹窗了，
         * 那时候把 JsResult 交出去就是让这一页的 JS 永远卡在弹窗上。
         */
        view.webChromeClient = object : WebChromeClient() {
            /**
             * 标题落在**标签**上（不是只落在 find 快照里）：标签条要在用户一眼就看到「第二个标签
             * 是什么」，而 `browser_find` 是模型的动作 —— 用户接管时也许根本没人 find 过。
             * SPA 改 `document.title` 也会回调这里，所以列表是跟着实时变的。
             */
            /** 加载进度：<100 就是"这页还在动"，动作后的等待判据读它（照参照实现的口径）。 */
            override fun onProgressChanged(webView: WebView, newProgress: Int) {
                loading = newProgress < 100
            }

            override fun onReceivedTitle(webView: WebView, pageTitle: String?) {
                title = pageTitle.orEmpty()
                owner.tabsChanged()
            }

            override fun onJsAlert(
                webView: WebView, url: String?, message: String?, result: JsResult?,
            ): Boolean = owner.takeJsDialog(BrowserJsKind.ALERT, message, result)

            override fun onJsConfirm(
                webView: WebView, url: String?, message: String?, result: JsResult?,
            ): Boolean = owner.takeJsDialog(BrowserJsKind.CONFIRM, message, result)

            override fun onJsPrompt(
                webView: WebView,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?,
            ): Boolean = owner.takeJsDialog(BrowserJsKind.PROMPT, message, result, defaultValue)

            override fun onPermissionRequest(request: PermissionRequest) {
                owner.appendNotice("PERMISSION_DENIED:" + request.resources.joinToString().take(120))
                request.deny()
            }

            /**
             * `target=_blank` / `window.open` 开成**新标签**并设为活动（spec §12.2）。
             * 到了 [BrowserSession.MAX_TABS] 就拒 —— 但拒也要在信封里说明（spec §7.3），
             * 否则模型收到 `ok` 会以为页面还在。
             *
             * ⚠️ 副作用：`supportMultipleWindows = true` 之后，模型用 `browser_click` 点的
             * 链接如果带 `_blank`，也可能真开出一个标签并把活动标签换掉（换标签必然换代次，
             * 于是模型手上那把 index 全部作废）。这是**安全的方向**：它只会多一次「请重新 find」，
             * 不会把动作点到另一个页面上，而记账句 `NEW_TAB_OPENED:` 会随信封告诉它发生了什么。
             */
            override fun onCreateWindow(
                webView: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?,
            ): Boolean = owner.openTabForWindow(resultMsg)

            /**
             * 文件上传**只给用户手动**（spec §12.4）：挂在界面上时把选择器交给遮罩，无头一律
             * `onReceiveValue(null)` 拒掉并记 `FILE_CHOOSER_NEEDS_USER` —— **模型不许上传文件**，
             * 这条不放开。
             */
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?,
            ): Boolean = owner.takeFileChooser(filePathCallback, params)
        }
        view.setDownloadListener { url, _, contentDisposition, _, _ ->
            // contentDisposition 是服务端任意串：这里必须自己封一道（其余来源在各自回调点已 take），
            // 否则 NOTICE_MAX_CHARS 的「每条有界」前提在这条来源上不成立。
            owner.appendNotice("DOWNLOAD_REFUSED:" + url.take(120) +
                (if (contentDisposition.isNullOrBlank()) "" else "|" + contentDisposition.take(80)))
        }
    }

    /** 见 [BrowserSession.applyOffscreenViewport]：摘下来必须把 1280×1600 的视口还原回去。 */
    private fun applyOffscreenViewport() {
        if (dead) return
        view.measure(
            View.MeasureSpec.makeMeasureSpec(BrowserSession.VIEWPORT_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(BrowserSession.VIEWPORT_HEIGHT, View.MeasureSpec.EXACTLY),
        )
        view.layout(
            0, 0,
            BrowserSession.VIEWPORT_WIDTH,
            BrowserSession.VIEWPORT_HEIGHT,
        )
    }

    /** 把这一标签挂到界面上（先从别处摘下来）。必须在主线程（Compose 里就是）。 */
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

    fun detach() {
        (view.parent as? ViewGroup)?.removeView(view)
        applyOffscreenViewport()
    }

    fun bumpGenerationAndDropSnapshot() {
        generation = owner.nextEpoch()
        snapshot = null
    }

    fun publishSnapshot(next: BrowserPageSnapshot?) {
        snapshot = next
    }

    /**
     * 解套路径上的 stopLoading（超时支与取消支）。`withContext` 会把这块**重新 post 回
     * Main**，中间 close()/destroy 可能插进来 —— destroy 之后再调 view 的任何方法都违反
     * 平台契约，而加载本来就该停的也已经停了，跳过就是正确行为。
     */
    private suspend fun stopLoadingUnlessDead() {
        withContext(NonCancellable) { if (!dead) view.stopLoading() }
    }

    suspend fun navigate(target: String): Result<Unit> = withContext(Dispatchers.Main) {
        if (dead) return@withContext Result.failure(IllegalStateException("RENDERER_GONE"))
        if (!isAllowedUrl(target)) return@withContext Result.failure(IllegalStateException("BLOCKED_SCHEME"))
        val done = CompletableDeferred<String>()
        navigation = done
        view.loadUrl(target)
        // 用「带原因的完成」而不是异常完成：`await()` 就不会抛，外层取消（用户点停止）
        // 仍是唯一能让它抛的东西 —— 那必须透传，不能被读成「导航失败」（spec §8）。
        // try/finally 统一归还 deferred：六条路径各写一遍清线迟早漏一条，
        // 挂在外面的那枚会被下一次导航的 onPageFinished「串台」完成掉。
        val verdict = try {
            withTimeoutOrNull(BrowserSession.NAV_TIMEOUT_MS) { done.await() }
        } catch (e: CancellationException) {
            // 取消那次加载还在飞：不 stopLoading，它照样 onPageFinished，而那时
            // 字段里很可能已是下一颗 navigate 的 deferred —— 下一次导航会被提前报成功。
            stopLoadingUnlessDead()
            throw e
        } finally {
            if (navigation === done) navigation = null
        }
        when (verdict) {
            null -> {
                stopLoadingUnlessDead()
                Result.failure(IllegalStateException("NAV_TIMEOUT"))
            }
            "ok" -> {
                // await 排回 Main 的窗口里 close() 可能插队（deferred 已被真 onPageFinished
                // 解套成 "ok"，destroy 却先落地）：view.url 同属「destroy 后不得再调」的方法。
                // 这个结果不再可信，报 RENDERER_GONE。
                if (dead) Result.failure(IllegalStateException("RENDERER_GONE"))
                else {
                    url = view.url.orEmpty()
                    Result.success(Unit)
                }
            }
            else -> Result.failure(IllegalStateException(verdict))
        }
    }

    suspend fun goBack(): Boolean = withContext(Dispatchers.Main) {
        if (dead || !view.canGoBack()) return@withContext false
        view.goBack()
        bumpGenerationAndDropSnapshot()
        true
    }

    /**
     * 历史前进：与 [goBack] **逐字同形状**（判 `canGoForward` → 走 → 换代次并作废快照），
     * 差别只有方向。同样**不等加载完成** —— 前进过去之后 `onPageFinished` 自己会再 bump 一次，
     * 工具侧再叠一层就是每跳两颗（spec §3 的代次契约）。
     */
    suspend fun goForward(): Boolean = withContext(Dispatchers.Main) {
        if (dead || !view.canGoForward()) return@withContext false
        view.goForward()
        bumpGenerationAndDropSnapshot()
        true
    }

    suspend fun reload(): Result<Unit> = withContext(Dispatchers.Main) {
        if (dead) return@withContext Result.failure(IllegalStateException("RENDERER_GONE"))
        val done = CompletableDeferred<String>()
        navigation = done
        view.reload()
        val verdict = try {
            withTimeoutOrNull(BrowserSession.NAV_TIMEOUT_MS) { done.await() }
        } catch (e: CancellationException) {
            // 同 navigate：被放弃的加载照样会 onPageFinished，必须停掉，
            // 否则它去 complete 下一颗动作的 deferred。
            stopLoadingUnlessDead()
            throw e
        } finally {
            if (navigation === done) navigation = null
        }
        bumpGenerationAndDropSnapshot()
        when (verdict) {
            null -> {
                stopLoadingUnlessDead()
                Result.failure(IllegalStateException("NAV_TIMEOUT"))
            }
            // 与 navigate 同口径：**只有 "ok" 算成功**。close() 注入的 "RENDERER_GONE" 与
            // onReceivedError 的 "NAV_FAILED:<code>" 若走 else 会被读成「重载成功」——
            // 被放弃/失败的加载替下一次动作报成功，正是那条代次契约要消灭的口径。
            "ok" -> Result.success(Unit)
            else -> Result.failure(IllegalStateException(verdict))
        }
    }

    suspend fun run(script: String, timeoutMs: Long): Pair<Boolean, String> =
        withContext(Dispatchers.Main) {
            if (dead) return@withContext false to "RENDERER_GONE"
            val deferred = CompletableDeferred<String>()
            pendingScript = deferred
            view.evaluateJavascript(script) { value -> deferred.complete(value ?: "null") }
            // 取消（用户点停止）= **结果未知**：点了的鼠标事件可能已经生效。停掉加载、
            // 原样上抛，让 ToolRunner 去回那句「不要假设成功、不要盲目重试」（spec §8）。
            val raw = try {
                withTimeoutOrNull(timeoutMs) { deferred.await() }
            } catch (e: CancellationException) {
                stopLoadingUnlessDead()
                throw e
            } finally {
                pendingScript = null
            }
            if (raw == null) {
                stopLoadingUnlessDead()
                return@withContext false to "SCRIPT_TIMEOUT"
            }
            // destroy 以值解套的那次唤醒：把码原样交出去，别把哨兵喂给 unwrap 变成 BAD_JSON。
            if (raw == SCRIPT_CLOSED) return@withContext false to "CANCELLED"
            BrowserScripts.unwrap(raw)
        }

    /** 原尺寸截图（不缩放：缩放会让小字不可读）。主线程画位图。上锁同 navigate：串行是 spec §8 的承诺。 */
    suspend fun screenshotPng(): ByteArray = withContext(Dispatchers.Main) {
        if (dead) return@withContext ByteArray(0)
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

    /**
     * 销毁**这一个标签**：解套两枚在飞的 deferred（以值解套，理由见
     * [BrowserSession.destroy] 的同款说明），再把 WebView 摘掉、停加载、destroy。
     *
     * **不清 cookie / WebStorage**：原生侧那份 jar 是 app 全局的，关掉一个标签就去清它，
     * 等于把其它标签的登录态一起蒸发 —— 用户关一个标签页，别的标签全退登，那是真事故
     * （spec §12.2 点名的这条要在实现里写死）。清凭据只发生在会话级 [BrowserSession.destroy]。
     */
    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        navigation?.complete("RENDERER_GONE")
        navigation = null
        pendingScript?.complete(SCRIPT_CLOSED)
        pendingScript = null
        detach()
        view.stopLoading()
        view.destroy()
    }
}


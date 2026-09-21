package com.psyche.memo.provider.chart

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Mermaid 文本 → **PNG 位图**（用户 2026-09-18：「加个 Mermaid，我们这个 svg 工具就是一个
 * 大模型画板」）。
 *
 * 为什么是「内置 mermaid.js + 离屏 WebView」：
 *  1. **模型本来就会写 Mermaid 语法**（流程图/时序图/状态图/ER/类图/甘特/思维导图/时间线…）
 *     —— 不用教它一套自研 DSL，覆盖面还比我们自己实现的多得多；
 *  2. Mermaid 是 JS 库，Android 端没有原生实现，而在 WebView 里渲染是**唯一 100% 忠实**的做法
 *     （它的 SVG 靠 `<style>` 里的 CSS 类上色，AndroidSVG 的 CSS 支持不全，画出来会缺色少线）；
 *  3. 直接在 WebView 里 SVG → canvas → **PNG**，拿到的是「它自己渲染的结果」，Kotlin 侧只负责
 *     落盘 —— 产物走**已有图片通道**（等比卡片、点开全屏、保存到相册、存储/导出/备份全都白拿）。
 *
 * 线程：WebView 只能在主线程创建与调用 → 整个渲染过程 `withContext(Dispatchers.Main)`，
 * 执行链本身是 `suspend`（ToolHandler.handle 就是 suspend）。
 */
object MermaidRenderer {

    const val TIMEOUT_MS = 25_000L

    /** 渲染宽度（像素）：够清晰又不至于让位图过大。 */
    const val TARGET_WIDTH_PX = 1080

    /** 离屏 WebView 的预布局高度（页面自己会撑开，这里只是给个量得出来的初始尺寸）。 */
    private const val PREVIEW_HEIGHT_PX = 2400

    sealed interface Result {
        data class Ok(val png: ByteArray, val width: Int, val height: Int) : Result
        data class Error(val message: String) : Result
    }

    /** 渲染入口：失败/超时一律回 [Result.Error]（文案给模型看，它会改了重试）。 */
    suspend fun render(
        context: Context,
        code: String,
        palette: ChartPalette,
    ): Result {
        if (code.isBlank()) return Result.Error("mermaid code must not be empty")
        if (code.length > 100_000) return Result.Error("mermaid code is too long (keep it under 100KB)")
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(TIMEOUT_MS) { renderOnMain(context, code, palette) }
                ?: Result.Error("mermaid rendering timed out after ${TIMEOUT_MS / 1000}s")
        }
    }

    private suspend fun renderOnMain(
        context: Context,
        code: String,
        palette: ChartPalette,
    ): Result = suspendCancellableCoroutine { continuation ->
        val webView = createWebView(context)
        val bridge = Bridge()
        var finished = false

        fun finish(result: Result) {
            if (finished) return
            finished = true
            runCatching { webView.stopLoading() }
            runCatching { webView.destroy() }
            if (continuation.isActive) continuation.resume(result)
        }

        bridge.onDone = { dataUrl, _, _ ->
            val png = decodePngDataUrl(dataUrl)
            finish(
                if (png == null) {
                    Result.Error("mermaid 渲染结果无法解码")
                } else {
                    Result.Ok(png = png, width = 0, height = 0)
                },
            )
        }
        bridge.onError = { message -> finish(Result.Error(message)) }
        webView.addJavascriptInterface(bridge, BRIDGE_NAME)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val script = buildRenderScript(code, palette)
                view?.evaluateJavascript(script, null)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                finish(Result.Error("mermaid 页面加载失败：${error?.description ?: "unknown"}"))
            }
        }
        continuation.invokeOnCancellation { finish(Result.Error("cancelled")) }
        // 离屏 WebView 不量/layout 的话，页面可能不布局 → 量出来是 0（PNG 就只有几个像素）。
        webView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(
                TARGET_WIDTH_PX,
                android.view.View.MeasureSpec.EXACTLY,
            ),
            android.view.View.MeasureSpec.makeMeasureSpec(
                PREVIEW_HEIGHT_PX,
                android.view.View.MeasureSpec.AT_MOST,
            ),
        )
        webView.layout(0, 0, TARGET_WIDTH_PX, PREVIEW_HEIGHT_PX)
        webView.loadUrl(WRAPPER_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.allowFileAccess = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.blockNetworkLoads = true // 纯本地渲染：不联网
        setBackgroundColor(Color.TRANSPARENT)
    }

    /** 生成调用页面的脚本（参数都走 JSON 引号转义，中文标签不会炸）。 */
    internal fun buildRenderScript(code: String, palette: ChartPalette): String {
        val themeJson = JSONObject.quote(themeVariablesJson(palette))
        val codeJson = JSONObject.quote(code)
        return "window.renderMermaid($codeJson, $themeJson, $TARGET_WIDTH_PX);"
    }

    /**
     * 注入给 mermaid 的主题变量：底色 = 当前主题卡色，文字/线条/主色都取我们的调色板
     * —— 这样图表跟 App 界面是一套颜色（生成时按主题取色，与结构化图同一条口径）。
     */
    internal fun themeVariablesJson(palette: ChartPalette): String {
        val primary = palette.seriesColor(0)
        return JSONObject(
            mapOf(
                "background" to palette.background.hexOf(),
                "primaryColor" to primary.hexOf(),
                "primaryTextColor" to palette.text.hexOf(),
                "primaryBorderColor" to palette.axis.hexOf(),
                "secondaryColor" to palette.seriesColor(1).hexOf(),
                "tertiaryColor" to palette.seriesColor(2).hexOf(),
                "lineColor" to palette.muted.hexOf(),
                "textColor" to palette.text.hexOf(),
                "mainBkg" to palette.background.hexOf(),
                "nodeBorder" to palette.axis.hexOf(),
                "clusterBkg" to palette.background.hexOf(),
                "clusterBorder" to palette.axis.hexOf(),
                "fontFamily" to "sans-serif",
                "fontSize" to "16px",
            ),
        ).toString()
    }

    /** `data:image/png;base64,xxxx` → 字节。 */
    internal fun decodePngDataUrl(dataUrl: String): ByteArray? = runCatching {
        val marker = "base64,"
        val index = dataUrl.indexOf(marker)
        if (index < 0) return null
        Base64.decode(dataUrl.substring(index + marker.length), Base64.DEFAULT)
    }.getOrNull()

    private fun Long.hexOf(): String = "#%06X".format(this and 0xFFFFFF)

    private const val BRIDGE_NAME = "MemoBridge"
    private const val WRAPPER_URL = "file:///android_asset/mermaid_wrapper.html"

    /** 页面 → Kotlin 的回调（`@JavascriptInterface` 必须在主线程被调，WebView 保证）。 */
    private class Bridge {
        var onDone: ((dataUrl: String, width: Int, height: Int) -> Unit)? = null
        var onError: ((message: String) -> Unit)? = null
        private val main = Handler(Looper.getMainLooper())

        @JavascriptInterface
        fun onDone(dataUrl: String, width: Int, height: Int) {
            main.post { onDone?.invoke(dataUrl, width, height) }
        }

        @JavascriptInterface
        fun onError(message: String) {
            main.post { onError?.invoke(message) }
        }
    }
}

package com.psyche.memo.ui.chat

import android.content.Context
import android.util.Base64
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.psyche.memo.ui.MemoTopBar
import java.util.Locale

/**
 * 预览请求。[rawHtml] 区分两条原版路径：
 * - `false`：消息「Render WebView」——正文当 **Markdown**，走 `assets/html/mark.html`
 *   模板（markdown-it/katex/highlight.js/mermaid，与 `markdown_preview_html.dart`
 *   逐字一致）。
 * - `true`：代码块上的「预览」（```html）——正文是**原始 HTML**，走原版
 *   `HtmlPreviewPage._wrapIfNeeded`（完整文档原样加载，否则套一层主题容器）。
 *   此前两条路径共用 markdown 模板，HTML 会被 markdown-it 当正文处理（缩进还会被
 *   当成代码块），且整页依赖 CDN 才渲染 —— 用户报"HTML 预览有问题"。
 */
data class HtmlPreviewRequest(val content: String, val rawHtml: Boolean)

/**
 * Port of html_preview_page.dart + markdown_preview_html.dart: renders the payload
 * inside a WebView with the theme colors substituted into the CSS placeholders.
 */
@Composable
fun HtmlPreviewScreen(
    request: HtmlPreviewRequest,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    // 同屏二级页：从对话里打开的预览，系统返回要回到对话，而不是 pop 掉对话路由。
    com.psyche.memo.ui.OverlayBackHandler(onBack)
    val html = remember(request, cs) {
        if (request.rawHtml) {
            MarkdownPreviewHtml.wrapHtml(cs, request.content)
        } else {
            MarkdownPreviewHtml.build(context, cs, request.content)
        }
    }

    androidx.compose.foundation.layout.Column(
        // 整页不透明（原版是独立路由的 Scaffold），否则会透出后面的聊天页。
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .statusBarsPadding(),
    ) {
        MemoTopBar(
            title = androidx.compose.ui.res.stringResource(com.psyche.memo.ui.R.string.assistant_edit_preview_title),
            onBack = onBack,
        )
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    // 页面 body 自带主题底色，但加载期间 WebView 自身也要不透明，
                    // 否则会透出后面的聊天页（用户实测报障）。
                    setBackgroundColor(cs.surface.toArgb())
                }
            },
            update = { webView ->
                // update 每次重组都会跑；只在 HTML 变化时重新加载，否则页面
                // 会被反复重载而一直空白（表现为"背景透明"）。
                if (webView.tag != html) {
                    webView.tag = html
                    webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                }
            },
        )
    }
}

/** MarkdownPreviewHtmlBuilder — placeholder substitution on assets/html/mark.html. */
object MarkdownPreviewHtml {

    fun build(context: Context, cs: androidx.compose.material3.ColorScheme, markdown: String): String {
        val template = runCatching {
            context.assets.open("html/mark.html").bufferedReader().use { it.readText() }
        }.getOrDefault(FALLBACK_TEMPLATE)
        return template
            .replace("{{MARKDOWN_BASE64}}", base64(markdown))
            .replace("{{BACKGROUND_COLOR}}", cssHex(cs.surface))
            .replace("{{ON_BACKGROUND_COLOR}}", cssHex(cs.onSurface))
            .replace("{{SURFACE_COLOR}}", cssHex(cs.surface))
            .replace("{{ON_SURFACE_COLOR}}", cssHex(cs.onSurface))
            .replace("{{SURFACE_VARIANT_COLOR}}", cssHex(cs.surfaceContainerHighest))
            .replace("{{ON_SURFACE_VARIANT_COLOR}}", cssHex(cs.onSurfaceVariant))
            .replace("{{PRIMARY_COLOR}}", cssHex(cs.primary))
            .replace("{{OUTLINE_COLOR}}", cssHex(cs.outline))
            .replace("{{OUTLINE_VARIANT_COLOR}}", cssHex(cs.outlineVariant))
    }

    /**
     * html_preview_page.dart `_wrapIfNeeded` —— 已经是完整文档（含 `<html` 与
     * `<body`）就原样交给 WebView，否则套一层主题底/前景色的容器。原版用固定的
     * #111111/#eaeaea 与 #ffffff/#222222；这里改用主题 surface/onSurface，跟随
     * 应用配色（其余（padding/媒体自适应/等宽字体）与原文一致）。
     */
    fun wrapHtml(cs: androidx.compose.material3.ColorScheme, html: String): String {
        val lower = html.lowercase(Locale.US)
        if (lower.contains("<html") && lower.contains("<body")) return html
        return """<!doctype html>
<html>
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <style>
      html, body { background: ${cssHex(cs.surface)}; color: ${cssHex(cs.onSurface)}; margin: 0; padding: 0; }
      .container { padding: 12px; }
      img, video, canvas, iframe { max-width: 100%; height: auto; }
      pre, code { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace; }
    </style>
  </head>
  <body>
    <div class="container">
      $html
    </div>
  </body>
</html>"""
    }

    private fun base64(text: String): String =
        Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    /** _toCssHex — #RRGGBBAA like the Dart original. */
    internal fun cssHex(color: Color): String {
        val a = ((color.alpha * 255f).toInt().coerceIn(0, 255))
        val r = ((color.red * 255f).toInt().coerceIn(0, 255))
        val g = ((color.green * 255f).toInt().coerceIn(0, 255))
        val b = ((color.blue * 255f).toInt().coerceIn(0, 255))
        return String.format(Locale.US, "#%02X%02X%02X%02X", r, g, b, a)
    }

    private const val FALLBACK_TEMPLATE = """<!doctype html>
<html><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width, initial-scale=1"/>
<style>html,body{background:{{BACKGROUND_COLOR}};color:{{ON_BACKGROUND_COLOR}};margin:0;padding:0}
.container{padding:12px}img,video,canvas,iframe{max-width:100%;height:auto}</style></head>
<body><div class="container"><pre id="out"></pre></div>
<script>document.getElementById('out').textContent = atob('{{MARKDOWN_BASE64}}');</script></body></html>"""
}

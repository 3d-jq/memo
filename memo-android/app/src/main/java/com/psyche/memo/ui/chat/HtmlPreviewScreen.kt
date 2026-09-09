package com.psyche.memo.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.webkit.WebView
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
 * Port of html_preview_page.dart + markdown_preview_html.dart: renders markdown
 * through the bundled assets/html/mark.html template (marked.js) inside a
 * WebView, with the theme colors substituted into the CSS placeholders.
 */
@Composable
fun HtmlPreviewScreen(
    markdown: String,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val html = remember(markdown, cs) { MarkdownPreviewHtml.build(context, cs, markdown) }

    androidx.compose.foundation.layout.Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
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
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            update = { webView ->
                webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
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

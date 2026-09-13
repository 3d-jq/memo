package com.psyche.memo.ui.chat

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * html_preview_page.dart `_wrapIfNeeded` 的移植覆盖：完整文档原样放行，片段套主题容器
 * （原始 HTML 不再被 markdown-it 处理 —— 用户报「HTML 预览有问题」的根因）。
 */
class HtmlPreviewHtmlTest {

    private val light = lightColorScheme(surface = Color(0xFFFFFFFF), onSurface = Color(0xFF222222))

    @Test
    fun `complete documents pass through untouched`() {
        val document = "<!DOCTYPE html><html><body><h1>hi</h1></body></html>"
        assertEquals(document, MarkdownPreviewHtml.wrapHtml(light, document))
        // 大小写不敏感（原版 toLowerCase().contains）。
        val upper = "<HTML><BODY>x</BODY></HTML>"
        assertEquals(upper, MarkdownPreviewHtml.wrapHtml(light, upper))
    }

    @Test
    fun `fragments are wrapped with the theme colours`() {
        val wrapped = MarkdownPreviewHtml.wrapHtml(light, "<div class=\"card\">hi</div>")
        assertTrue(wrapped.startsWith("<!doctype html>"))
        assertTrue(wrapped.contains("background: #FFFFFFFF;"))
        assertTrue(wrapped.contains("color: #222222FF;"))
        assertTrue(wrapped.contains("<div class=\"card\">hi</div>"))
        // 原版的容器/媒体/等宽样式都在。
        assertTrue(wrapped.contains(".container { padding: 12px; }"))
        assertTrue(wrapped.contains("img, video, canvas, iframe { max-width: 100%; height: auto; }"))
    }

    @Test
    fun `indented fragments keep their source verbatim`() {
        // markdown 模板会把这四空格缩进当代码块；原始 HTML 预览必须原样保留。
        val fragment = "    <div>\n        <span>indented</span>\n    </div>"
        val wrapped = MarkdownPreviewHtml.wrapHtml(light, fragment)
        assertTrue(wrapped.contains(fragment))
    }

    @Test
    fun `dark schemes use their own colours`() {
        val dark = darkColorScheme(surface = Color(0xFF111111), onSurface = Color(0xFFEAEAEA))
        val wrapped = MarkdownPreviewHtml.wrapHtml(dark, "<p>x</p>")
        assertTrue(wrapped.contains("background: #111111FF;"))
        assertTrue(wrapped.contains("color: #EAEAEAFF;"))
    }

    @Test
    fun `css hex keeps the alpha channel like the dart original`() {
        assertEquals("#FF000080", MarkdownPreviewHtml.cssHex(Color(0x80FF0000)))
    }
}

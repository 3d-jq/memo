package com.psyche.memo

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 多选导出图片的回归守卫：离屏渲染是**装进不可见 Dialog** 的（`ChatExportImage.render`），
 * 而 Dialog 只有 Activity 上下文才有 window token —— 传 `applicationContext` 会在
 * `Dialog.show()` 抛 `BadTokenException` 直接闪退（2026-09-19 真机实测）。
 *
 * 这条按源码扫描钉死（和 `ToolTranscriptContentTest` 同一手法）：渲染调用拿的必须是
 * 向上剥出的 Activity，不许退回 appContext。
 */
class ChatExportImageWindowTokenTest {

    @Test
    fun `the offscreen export dialog is hosted by an activity context`() {
        val src = File("src/main/java/com/psyche/memo/ui/chat/ChatContent.kt")
        assertTrue("expected ${src.absolutePath} to exist", src.isFile)

        val source = src.readText()
        val call = source.substringAfter("ChatExportImage.render(").substringBefore("\n    ) {")
        assertTrue(
            "导出渲染要用 Activity 上下文起 Dialog，不能用 appContext：\n$call",
            call.contains("context = host"),
        )
        assertTrue(
            "要先 findActivity() 再渲染，否则 Dialog.show() 直接崩进程",
            source.contains("windowContext.findActivity()"),
        )
    }
}

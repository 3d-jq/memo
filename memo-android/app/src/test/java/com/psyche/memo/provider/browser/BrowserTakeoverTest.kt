package com.psyche.memo.provider.browser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 接管态的两条纯逻辑（spec §12.3/§12.4）：地址栏输入怎么变成一条地址、`<input accept>` 怎么
 * 变成系统选择器能吃的 MIME 表。两支都是纯函数，所以能把「用户会踩到的形状」逐个钉死。
 */
class BrowserTakeoverTest {

    @Test
    fun bareHostGetsHttpsPrefixedButAnExplicitSchemeIsRespected() {
        assertEquals("https://example.com", addressInputToUrl("  example.com "))
        assertEquals(
            "补完必须是 [isAllowedUrl] 认的形状",
            true,
            isAllowedUrl(addressInputToUrl("example.com/a?b=1")!!),
        )
        // 用户明确写了协议 ⇒ 不替他改：把 http 悄悄升级成 https 会静默打开**另一个**站点。
        // （http 本身从 §15 起是允许的；这里只钉"不替他改写"。）
        assertEquals("http://example.com", addressInputToUrl("http://example.com"))
        assertEquals("javascript:alert(1)", addressInputToUrl("javascript:alert(1)"))
        assertNull("空输入没什么可提交", addressInputToUrl("   "))
    }

    @Test
    fun blockedSchemesStayBlockedAfterThePrefix() {
        listOf("file:///sdcard/a", "content://m/text", "javascript:alert(1)", "data:text/html,hi")
            .forEach { assertEquals("$it 不该放行", false, isAllowedUrl(addressInputToUrl(it)!!)) }
        // §15（2026-09-26）：明文 http 收 —— 内网与老站开不了的话，自动化就是空谈。
        listOf("http://x", "https://x").forEach {
            assertEquals("$it 该放行", true, isAllowedUrl(addressInputToUrl(it)!!))
        }
    }

    @Test
    fun acceptTokensBecomeMimeTypesOrFallBackToEverything() {
        // 网页写的是裸扩展名（现实中很常见）：原样丢给选择器会过滤成空列表，
        // 用户看到「什么都选不了」就会当成上传坏了。
        assertArrayEquals(
            arrayOf("application/pdf"),
            filePickerMimeTypes(listOf(".pdf")),
        )
        assertArrayEquals(
            arrayOf("image/png", "image/jpeg"),
            filePickerMimeTypes(listOf("image/png", ".jpg", "IMAGE/JPEG")),
        )
        assertArrayEquals(arrayOf("image/*"), filePickerMimeTypes(listOf("image/*")))
        assertArrayEquals(
            "一个都认不出来 ⇒ */*：宁可让用户从全部文件里挑，也不给他一个空选择器",
            arrayOf("*/*"),
            filePickerMimeTypes(listOf(".qqq", "nonsense")),
        )
        assertArrayEquals(arrayOf("*/*"), filePickerMimeTypes(emptyList()))
    }
}

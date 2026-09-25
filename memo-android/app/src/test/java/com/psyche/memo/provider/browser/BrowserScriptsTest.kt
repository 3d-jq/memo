package com.psyche.memo.provider.browser

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 不跑 WebView，只验两件事：**生成的 JS 形状对**（参数转义、上限、不出现危险构造），
 * 以及**解析对**（尤其 evaluateJavascript 那层额外的 JSON 字符串编码）。
 */
class BrowserScriptsTest {

    private val findResult = """{"ok":true,"value":{"url":"https://a","title":"T","elements":[
        |{"index":1,"tag":"a","role":"link","text":"下一页","placeholder":null,"type":null,
        | "href":"https://a/next","selector":"body > a:nth-of-type(3)",
        | "bounds":{"x":0,"y":10,"width":80,"height":20}}]}}""".trimMargin()

    @Test
    fun scriptNeverUsesJavascriptInterfaceOrInnerHtml() {
        val all = listOf(
            BrowserScripts.findScript(),
            BrowserScripts.readScript(6000, 0),
            BrowserScripts.clickScript("""{"selector":"body > a"}"""),
            BrowserScripts.typeScript("""{"selector":"#q","text":"hi"}"""),
            BrowserScripts.scrollScript("down", 800),
        ).joinToString("\n")
        assertTrue("只做求值，不注入桥", !all.contains("addJavascriptInterface"))
        assertTrue("绝不把模型给的东西当 HTML 写进页面", !all.contains("innerHTML"))
        assertTrue("不弹 JS 对话框（无头执行会挂死）", !all.contains("alert(") && !all.contains("confirm("))
        assertTrue("find 的上限与 Kotlin 侧同一个常量", all.contains("var LIMIT = ${FIND_LIMIT};"))
    }

    @Test
    fun textIsCarriedAsJsonSoQuotesCannotBreakTheScript() {
        val js = BrowserScripts.typeScript(
            BrowserScripts.targetJsonWithText("#q", "a\"b\\c\n d"),
        )
        assertTrue("双引号/反斜杠/换行都要在 JSON 层转义掉", js.contains("\\\"b\\\\c\\nd") || js.contains("\\\"b\\\\c"))
        assertFalse("文本不许被拼成 JS 字符串字面量", js.contains("var want = '"))
    }

    /** evaluateJavascript 会把 JS 的字符串返回值再编码一层 —— 忘了剥就是处处 BAD_JSON。 */
    @Test
    fun unwrapPeelsTheOuterStringEncoding() {
        // 脚本 `return JSON.stringify({...})` ⇒ 回调拿到的是「一个 JSON 字符串字面量」。
        val doubleEncoded = JsonPrimitive(findResult).toString()
        val (ok, payload) = BrowserScripts.unwrap(doubleEncoded)
        assertTrue(ok)
        val snap = BrowserScripts.parseElements(payload, generation = 7)
        assertEquals(7, snap.generation)
        assertEquals("https://a", snap.url)
        assertEquals("body > a:nth-of-type(3)", snap.find(1)?.selector)
        assertEquals(20, snap.find(1)!!.bounds.height)
    }

    @Test
    fun errorEnvelopeAndCode() {
        val (ok, payload) = BrowserScripts.unwrap("""{"ok":false,"error":"TARGET_NOT_FOUND"}""")
        assertFalse(ok)
        assertEquals("TARGET_NOT_FOUND", payload)
        assertEquals(BrowserJsError.TARGET_NOT_FOUND, BrowserScripts.parseJsErrorCode(payload))
        assertNull("真·JS 异常不能被当成已知错误码", BrowserScripts.parseJsErrorCode("Cannot read x"))
        val broken = BrowserScripts.unwrap("null")
        assertFalse(broken.first)
        assertEquals("BAD_JSON", broken.second)
    }

    @Test
    fun readCarriesTruncationFlag() {
        val (ok, payload) = BrowserScripts.unwrap(
            """{"ok":true,"value":{"text":"正文","truncated":true,"offset":0,"nextOffset":600}}""",
        )
        assertTrue(ok)
        assertEquals("正文" to true, BrowserScripts.parseRead(payload))
        assertEquals(600, BrowserScripts.readNextOffset(payload))
    }

    /**
     * 纵深防御：JS 侧的 `clean()` 折叠空白只是第一道闸 —— 渲染出的清单**一行一条**，
     * 字段里夹一个换行就能把一行劈成两行、甚至伪造出一条假条目。解析端必须自己再折一次。
     */
    @Test
    fun parsedStringsAreCollapsedToLineSoNewlinesCannotForgeEntries() {
        val injected = """{"ok":true,"value":{"url":"https://a","title":"T","elements":[
            |{"index":1,"tag":"a","role":null,"text":"  next\n\n page  ",
            | "placeholder":"a\n b","type":null,"href":"https://a/x\ny",
            | "selector":"body > a","bounds":{"x":0,"y":0,"width":1,"height":1}},
            |{"index":2,"tag":"button","role":null,"text":"下一页","placeholder":null,"type":null,
            | "href":null,"selector":"body > button","bounds":{"x":0,"y":0,"width":1,"height":1}}]}}""".trimMargin()
        val (ok, payload) = BrowserScripts.unwrap(injected)
        assertTrue(ok)
        val snap = BrowserScripts.parseElements(payload, generation = 1)
        val forged = snap.find(1)!!
        assertEquals("next page", forged.text)
        assertFalse("不许残留换行", forged.text.contains('\n'))
        assertEquals(forged.text, forged.text.trim())
        assertEquals("a b", forged.placeholder)
        assertEquals("https://a/x y", forged.href)
        assertEquals("折叠不能吃掉正常条目", 2, snap.elements.size)
        assertEquals("下一页", snap.find(2)?.text)
        // read 的正文同样折叠：模型侧的「一行一条」契约不靠 JS 兑现
        assertEquals("首行 次行" to false, BrowserScripts.parseRead(
            """{"text":"  首行\n\n  次行  ","truncated":false,"offset":0,"nextOffset":0}""",
        ))
    }
}

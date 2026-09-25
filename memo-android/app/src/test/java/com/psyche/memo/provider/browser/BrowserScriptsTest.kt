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
     * 审查 1：候选循环过去只对「已收到几条」设限，可**每个**候选都要付一次
     * `getBoundingClientRect` + `getComputedStyle`（两者都强制布局/样式）。几千个不可见的
     * `[role]`/`[tabindex]` 就是一趟无上限的重排扫射 —— 正是「遍历页面必须带节点预算 +
     * 截止时间双闸」这条纪律在本文件里唯一的漏口（`readScript` 是照做了的）。
     */
    @Test
    fun findScanIsGatedByTheSameBudgetAndDeadlineReadUses() {
        val find = BrowserScripts.findScript()
        val read = BrowserScripts.readScript(6000, 0)
        val findBudget = Regex("""i > (\d+)""").find(find)
        val readBudget = Regex("""nodes > (\d+)""").find(read)
        assertTrue("find 必须有「已扫候选数」这道闸", findBudget != null && readBudget != null)
        assertEquals(
            "闸按已扫候选数计，不是按已收条目数（不可见候选一样白付重排）",
            readBudget!!.groupValues[1],
            findBudget!!.groupValues[1],
        )
        assertEquals(
            "截止时间与 read 共用同一组常量，不许开出第二组数字",
            Regex("""Date\.now\(\) \+ (\d+)""").find(read)!!.groupValues[1],
            Regex("""Date\.now\(\) \+ (\d+)""").find(find)!!.groupValues[1],
        )
        val gate = find.indexOf("capped = true")
        val measure = find.indexOf("if (!vis(el)) continue")
        assertTrue("超预算/超时那一次不许再去量盒子", gate in 0 until measure)
        assertTrue("停扫描要能被调用方说成「清单可能不完整」", find.contains("capped: capped"))
        assertTrue(
            "截停要读得出来",
            BrowserScripts.findCapped("""{"url":"https://a","title":"T","elements":[],"capped":true}"""),
        )
        assertFalse(
            "没截停（含 capped 缺键）就是 false",
            BrowserScripts.findCapped("""{"url":"https://a","title":"T","elements":[]}"""),
        )
    }

    /**
     * 审查 2：游标必须落在**实际交付的字符数**上。旧形状（正文事后 `slice`、`nextOffset` 却按
     * 整段计）会让横跨截断点那一段的剩余字符永远读不到，而调用方被告知「从 next_offset 继续」。
     * 本类不引 WebView，所以两半一起钉：前半按 `readScript` 的契约连读两页验「拼起来＝整篇、
     * 不重不漏」，后半把生成侧那三处算术钉死（少了后半，前半只是在验测试自己的模型）。
     */
    @Test
    fun readPagesCoverTheWholeStreamWithoutOverlap() {
        // 审查里那个可复现推演的形状：3 段 × 10 字符、limit 25 —— 截断点正落在最后一段中间。
        val stream = listOf("aaaaaaaaaa", "bbbbbbbbbb", "cccccccccc").joinToString("\n", postfix = "\n")
        val limit = 25

        val first = readPageValue(stream, offset = 0, limit = limit)
        val (head, headTruncated) = BrowserScripts.parseRead(first)
        assertEquals(stream.substring(0, limit).replace("\n", " "), head)
        assertTrue(headTruncated)
        assertEquals(
            "游标只能走到交付末尾（旧实现在这里报 33 = 整段计数，于是丢掉最后 7 个字符）",
            limit,
            BrowserScripts.readNextOffset(first),
        )

        val second = readPageValue(stream, offset = BrowserScripts.readNextOffset(first), limit = limit)
        val (tail, tailTruncated) = BrowserScripts.parseRead(second)
        assertFalse("走到流尾就不许再喊截断", tailTruncated)
        assertEquals(
            "两页拼起来必须正好是整篇正文",
            BrowserScripts.parseRead(readPageValue(stream, offset = 0, limit = stream.length)).first,
            head + tail,
        )

        val js = BrowserScripts.readScript(limit, 0)
        assertTrue("nextOffset 要跟着交付的字符数走", js.contains("nextOffset: off + text.length"))
        assertFalse("不许再有「整段长度」那个计数器", js.contains("emitted"))
        assertTrue("窗口要在流坐标上裁，不是拼完再 slice", js.contains("off + limit - cursor"))
    }

    /**
     * 审查 3：`var dy = ` 后面必须是**一个**良构数字。旧写法把符号和数值分两处插值，
     * `up` + 负数得到 `var dy = --800;` —— Chromium 读成前缀自减 ⇒ SyntaxError，整条脚本作废。
     */
    @Test
    fun scrollOffsetIsOneWellFormedNumberWhateverTheSign() {
        assertEquals(800, scrollDy(BrowserScripts.scrollScript("down", 800)))
        assertEquals(-800, scrollDy(BrowserScripts.scrollScript("up", 800)))
        assertEquals("负数输入不能被拼成两个减号", 800, scrollDy(BrowserScripts.scrollScript("up", -800)))
        assertEquals(-800, scrollDy(BrowserScripts.scrollScript("down", -800)))
    }

    /** 按 `readScript` 的契约产一页的 value：正文＝流的 `[off, off+limit)` 窗口，游标只走到交付末尾。 */
    private fun readPageValue(stream: String, offset: Int, limit: Int): String {
        val text = stream.substring(offset, minOf(offset + limit, stream.length))
        val truncated = offset + text.length < stream.length
        return """{"text":${JsonPrimitive(text)},"truncated":$truncated,"offset":$offset,"nextOffset":${offset + text.length}}"""
    }

    private fun scrollDy(js: String): Int =
        Regex("""var dy = (-?\d+);""").find(js)!!.groupValues[1].toInt()

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

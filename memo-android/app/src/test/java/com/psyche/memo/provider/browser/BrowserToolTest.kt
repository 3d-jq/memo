package com.psyche.memo.provider.browser

import com.psyche.memo.provider.tool.ToolImageBytes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具面：门控、错误形状、代次、scheme。全部走替身 `FakeGateway`（不碰 WebView）。
 *
 * 断言里最要紧的两条：**错误必带 instruction**（模型只会照着它下一步动），
 * 和**给模型的文本里没有 CSS selector**。
 */
class BrowserToolTest {

    private class FakeGateway(
        override val generation: Int = 4,
        override val userControls: Boolean = false,
        private val elements: BrowserPageSnapshot? = null,
        var runResult: Pair<Boolean, String> = false to "NOT_IN_TESTS",
    ) : BrowserGateway {
        override val url = "https://example.com"
        override val isClosed = false
        override val snapshot: BrowserPageSnapshot? get() = elements
        var published: BrowserPageSnapshot? = null
        var lastScript = ""
        var navigatedTo: String? = null
        var bumps = 0
        override suspend fun navigate(url: String): Result<Unit> =
            if (url.startsWith("https://")) {
                navigatedTo = url
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("BLOCKED_SCHEME"))
            }
        override suspend fun run(script: String, timeoutMs: Long): Pair<Boolean, String> {
            lastScript = script
            return runResult
        }
        override suspend fun screenshotPng(): ByteArray = ByteArray(8)
        override suspend fun goBack(): Boolean = false
        override suspend fun reload(): Result<Unit> = Result.success(Unit)
        override fun publishSnapshot(snapshot: BrowserPageSnapshot?) { published = snapshot }
        override fun bumpGenerationAndDropSnapshot() { bumps++ }
        var notice: String? = null
        override fun drainNotice(): String? = notice.also { notice = null }
    }

    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private fun argsOf(vararg fields: String): JsonObject =
        obj("{${fields.joinToString(",")}}")

    private fun elementSnapshot() = BrowserPageSnapshot(
        generation = 4,
        url = "https://example.com",
        title = "示例",
        elements = listOf(
            BrowserElement(1, "a", "link", "下一页", null, null, "https://example.com/p2",
                "body > a:nth-of-type(2)", Bounds(0, 0, 40, 16)),
            BrowserElement(2, "input", null, "", "搜索", "text", null,
                "#q", Bounds(0, 20, 200, 30)),
        ),
    )

    @Test
    fun definitionAndDescriptionStayInLockstep() {
        val enum = BrowserTool.DEFINITION["properties"]!!.jsonObject["action"]!!
            .jsonObject["enum"]!!.jsonArray
        assertEquals(BrowserTool.ACTIONS.size, enum.size)
        assertEquals("navigate", enum.firstOrNull()!!.jsonPrimitive.content)
        assertEquals(
            listOf("navigate", "read", "find", "click", "type", "scroll", "screenshot", "back", "reload"),
            BrowserTool.ACTIONS,
        )
        assertEquals(
            listOf("action"),
            BrowserTool.DEFINITION["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val description = BrowserTool.DESCRIPTION
        assertTrue("必须写清「填完就停」", description.contains("never submit"))
        assertTrue("必须声明这是共享的浏览器", description.contains("browser"))
    }

    @Test
    fun unknownActionIsAnInvalidArgumentsErrorWithViolations() = runBlocking {
        val result = obj(BrowserTool.execute(FakeGateway(), argsOf("\"action\":\"teleport\"")) {})
        assertEquals("tool_error", result["type"]!!.jsonPrimitive.content)
        assertEquals("invalid_arguments", result["error"]!!.jsonPrimitive.content)
        assertNotNull("必须给模型一份能照着改的清单", result["violations"]?.jsonArray)
        assertTrue(
            "每条错误都要交代下一步",
            result["instruction"]!!.jsonPrimitive.contentOrNull!!.isNotBlank(),
        )
        assertTrue("补救话里要列出可用动作", result["instruction"]!!.jsonPrimitive.content.contains("navigate"))
    }

    @Test
    fun nonHttpsNeverReachesTheWebView() = runBlocking {
        val gateway = FakeGateway()
        val result = obj(
            BrowserTool.execute(gateway, argsOf("\"action\":\"navigate\"", "\"url\":\"http://e.com\"")) {},
        )
        assertEquals("BLOCKED_SCHEME", result["error"]!!.jsonPrimitive.content)
        assertEquals("https 之外不许 loadUrl", null, gateway.navigatedTo)
    }

    @Test
    fun clickRequiresTheCurrentGeneration() = runBlocking {
        val withIndex = obj(
            BrowserTool.execute(FakeGateway(), argsOf("\"action\":\"click\"", "\"index\":\"1\"")) {},
        )
        assertEquals("STALE_GENERATION", withIndex["error"]!!.jsonPrimitive.content)
        assertTrue(
            "没传 generation 的补救话要指向先 find",
            withIndex["instruction"]!!.jsonPrimitive.content.contains("find"),
        )

        val stale = obj(
            BrowserTool.execute(
                FakeGateway(),
                argsOf("\"action\":\"click\"", "\"index\":\"1\"", "\"generation\":\"3\""),
            ) {},
        )
        assertEquals("STALE_GENERATION", stale["error"]!!.jsonPrimitive.content)
        assertTrue("旧代次要点名现在是第几代", stale["message"]!!.jsonPrimitive.content.contains("4"))
    }

    @Test
    fun staleSnapshotIndexIsRefusedEvenWithRightGeneration() = runBlocking {
        val gateway = FakeGateway()
        val result = obj(
            BrowserTool.execute(
                gateway,
                argsOf("\"action\":\"click\"", "\"index\":\"1\"", "\"generation\":\"4\""),
            ) {},
        )
        assertEquals("TARGET_NOT_FOUND", result["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun findPublishesSnapshotAndHidesSelectors() = runBlocking {
        val payload = """{"url":"https://example.com","title":"示例","elements":[
            |{"index":1,"tag":"a","role":"link","text":"下一页","placeholder":null,"type":null,
            | "href":"https://example.com/p2","selector":"body > a:nth-of-type(2)",
            | "bounds":{"x":0,"y":0,"width":40,"height":16}}]}""".trimMargin()
        val gateway = FakeGateway(runResult = true to payload)
        val result = obj(
            BrowserTool.execute(gateway, argsOf("\"action\":\"find\"", "\"query\":\"下\"")) {},
        )
        assertEquals("tool_result", result["type"]!!.jsonPrimitive.content)
        assertEquals("4", result["generation"]!!.jsonPrimitive.content)
        val block = result["block"]!!.jsonPrimitive.content
        assertTrue("命中过滤要生效", block.contains("下一页"))
        assertTrue("绝不把 selector 漏给模型", !block.contains("nth-of-type"))
        assertEquals("没被扫描闸截停就不许吓唬模型", "false", result["capped"]!!.jsonPrimitive.content)
        assertTrue("未截停时不许出现那句提醒", !block.contains("清单可能不完整"))
        assertNotNull("快照必须存进会话，供 click 用 index 换 selector", gateway.published)
    }

    /**
     * Task 4 给 `findScript` 加的节点预算/截止时间双闸被撞到时，**必须让模型知道清单可能
     * 不完整** —— 否则它会以为「页面上就这 20 个」，然后因为找不到目标而放弃或瞎点。
     */
    @Test
    fun aCappedScanTellsTheModelTheListMayBeIncomplete() = runBlocking {
        val payload = """{"url":"https://example.com","title":"示例","capped":true,"elements":[
            |{"index":1,"tag":"a","role":"link","text":"下一页","placeholder":null,"type":null,
            | "href":null,"selector":"a","bounds":{"x":0,"y":0,"width":40,"height":16}}]}""".trimMargin()
        val gateway = FakeGateway(runResult = true to payload)
        val result = obj(BrowserTool.execute(gateway, argsOf("\"action\":\"find\"")) {})
        assertEquals("true", result["capped"]!!.jsonPrimitive.content)
        assertTrue(
            "给模型的文本里要有那句「可能不完整」",
            result["block"]!!.jsonPrimitive.content.contains("清单可能不完整"),
        )
    }

    @Test
    fun clickResolvesIndexToTheSelectorInternally() = runBlocking {
        val gateway = FakeGateway(
            elements = elementSnapshot(),
            runResult = true to """{"clicked":"#q"}""",
        )
        val result = obj(
            BrowserTool.execute(
                gateway,
                argsOf("\"action\":\"click\"", "\"index\":\"1\"", "\"generation\":\"4\""),
            ) {},
        )
        assertEquals("tool_result", result["type"]!!.jsonPrimitive.content)
        assertTrue("内联的目标必须是快照里的 selector", gateway.lastScript.contains("body > a:nth-of-type(2)"))
        assertEquals("点完要把代次推进、旧 index 作废（只推进一次）", 1, gateway.bumps)
    }

    @Test
    fun typeCarriesTheTextAndBumpsOnce() = runBlocking {
        val gateway = FakeGateway(
            elements = elementSnapshot(),
            runResult = true to """{"typed":3}""",
        )
        val result = obj(
            BrowserTool.execute(
                gateway,
                argsOf("\"action\":\"type\"", "\"index\":\"2\"", "\"generation\":\"4\"", "\"text\":\"关键词\""),
            ) {},
        )
        assertEquals("tool_result", result["type"]!!.jsonPrimitive.content)
        assertTrue("文本要作为 JSON 字面量内联（转义交给 kotlinx）", gateway.lastScript.contains("\"text\":\"关键词\""))
        assertTrue("目标是快照里那条 input 的 selector", gateway.lastScript.contains("#q"))
        assertEquals(1, gateway.bumps)
    }

    /**
     * `navigate`/`back`/`reload` 的代次由 [BrowserSession] 自己在 `onPageFinished`（以及
     * `goBack`）里推进 —— 工具再 bump 一次等于每跳两颗，模型看到的 generation 与页面
     * 落地那次不再是同一个数。
     */
    @Test
    fun navigationActionsNeverBumpTheGenerationThemselves() = runBlocking {
        val gateway = FakeGateway()
        obj(BrowserTool.execute(gateway, argsOf("\"action\":\"navigate\"", "\"url\":\"https://example.com\"")) {})
        assertEquals("navigate 不许叠一层 bump", 0, gateway.bumps)
        obj(BrowserTool.execute(gateway, argsOf("\"action\":\"reload\"")) {})
        assertEquals("reload 不许叠一层 bump", 0, gateway.bumps)
    }

    @Test
    fun userTakeoverBlocksEveryAction() = runBlocking {
        val result = obj(
            BrowserTool.execute(FakeGateway(userControls = true), argsOf("\"action\":\"read\"")) {},
        )
        assertEquals("USER_CONTROLS_PAGE", result["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun readReportsNextOffsetWhenTruncated() = runBlocking {
        val gateway = FakeGateway(
            runResult = true to """{"text":"一长段正文","truncated":true,"offset":0,"nextOffset":12}""",
        )
        val result = obj(BrowserTool.execute(gateway, argsOf("\"action\":\"read\"")) {})
        assertEquals("true", result["truncated"]!!.jsonPrimitive.content)
        assertEquals("12", result["next_offset"]!!.jsonPrimitive.content)
        assertEquals("一长段正文", result["text"]!!.jsonPrimitive.content)
    }

    /**
     * 一字未交付的截停（预算/超时正好落在窗口之前）会给出 `nextOffset == offset`。
     * 那条支**不许**报成功并递一个不会前进的续读把手 —— 模型照着它续读就是死循环。
     */
    @Test
    fun readStalledWithoutDeliveringAnythingIsReportedAsAFailure() = runBlocking {
        val gateway = FakeGateway(
            runResult = true to """{"text":"","truncated":true,"offset":0,"nextOffset":0}""",
        )
        val result = obj(BrowserTool.execute(gateway, argsOf("\"action\":\"read\"")) {})
        assertEquals("tool_error", result["type"]!!.jsonPrimitive.content)
        assertEquals("READ_STALLED", result["error"]!!.jsonPrimitive.content)
        assertNull("不许给出不会前进的续读把手", result["next_offset"])
        assertTrue(
            "补救话要指向另一条读法",
            result["instruction"]!!.jsonPrimitive.content.contains("find"),
        )
    }

    /** spec §7.3：挡掉弹窗不是「无事发生」，信封里必须说，且取走即清空。 */
    @Test
    fun suppressedJsDialogRidesAlongInTheResult() = runBlocking {
        val gateway = FakeGateway(
            runResult = true to """{"y":800,"atTop":false,"atBottom":false}""",
        )
        gateway.notice = "JS_ALERT_SUPPRESSED:please enter email"
        val result = obj(BrowserTool.execute(gateway, argsOf("\"action\":\"scroll\"")) {})
        assertEquals("JS_ALERT_SUPPRESSED:please enter email", result["page_notice"]!!.jsonPrimitive.content)
        assertNull("取走即清空，不许下一颗调用还带着它", gateway.drainNotice())
    }

    @Test
    fun screenshotAttachesBytesOnlyUnderTheCap() = runBlocking {
        val images = mutableListOf<ToolImageBytes>()
        val result = obj(BrowserTool.execute(FakeGateway(), argsOf("\"action\":\"screenshot\"")) { images.add(it) })
        assertEquals("tool_result", result["type"]!!.jsonPrimitive.content)
        assertEquals(1, images.size)
        assertTrue(images[0].name.endsWith(".png"))
    }
}

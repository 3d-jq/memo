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
        initialGeneration: Int = 4,
        override val userControls: Boolean = false,
        elements: BrowserPageSnapshot? = null,
        var runResult: Pair<Boolean, String> = false to "NOT_IN_TESTS",
        private val shotBytes: Int = 8,
    ) : BrowserGateway {
        override val url = "https://example.com"
        override val isClosed = false
        /**
         * 替身必须遵守它所替接口的**文档语义**，且要忠实到它替的**整个**方法：
         * `BrowserGateway.bumpGenerationAndDropSnapshot` 的真身（BrowserSession.kt
         * `bumpGenerationAndDropSnapshot()`）做**两件**事 —— `generationState++` **且**
         * `snapshotState = null`。
         *
         * 旧替身曾两度不忠实，各自掩护过一类真回归：
         * ① 「snapshot 永不作废」⇒ 回显把手排在作废之后，真会话上 index 类动作全被回成
         *    `x=null,y=null`（第二轮抓到的那条）；
         * ② 「generation 是构造期定死的 val」⇒ `ok()` 回给模型的 `generation` 到底是
         *    推进前还是推进后，测试面全程隐形（`BrowserTool` 构信封时读的是
         *    `gateway.generation`）。
         */
        override var generation: Int = initialGeneration
            private set
        private var snapshotState: BrowserPageSnapshot? = elements
        override val snapshot: BrowserPageSnapshot? get() = snapshotState
        var published: BrowserPageSnapshot? = null
        var lastScript = ""
        var navigatedTo: String? = null
        var bumps = 0
        var reloads = 0
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
        override suspend fun screenshotPng(): ByteArray = ByteArray(shotBytes)
        override suspend fun goBack(): Boolean = false
        override suspend fun reload(): Result<Unit> {
            reloads++
            return Result.success(Unit)
        }
        override fun publishSnapshot(snapshot: BrowserPageSnapshot?) {
            published = snapshot
            snapshotState = snapshot
        }
        override fun bumpGenerationAndDropSnapshot() {
            bumps++
            generation++
            snapshotState = null
        }
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

    /**
     * 「递给模型的整条结果里不许出现 CSS selector」—— 对着**字符串**断言，不挑字段：
     * 泄漏的形状以后可能是 detail、可能是 message，只钉一个键名等于没钉。
     */
    private fun assertNoSelectorLeak(result: String) {
        assertTrue("结果里出现了结构路径 selector（模型唯一的把手是 index）：$result", !result.contains("nth-of-type"))
        assertTrue("结果里出现了快照自己页面的 id：$result", !result.contains("#q"))
        assertTrue("结果里出现了 `body >` 这种 CSS 链：$result", !result.contains("body >"))
    }

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
        // 信封里的 `generation` 是模型下一颗 click/type 的**入场券**（`ok()` 在
        // `settleAfterAction()` 之后读 `gateway.generation`）：作废已发生，回给它的就必须
        // 是推进后的那一代。替身以前 `generation` 是定死的 val，这条整类回归（比如有人把
        // 读数挪到 bump 之前、回一个已经作废的 4）在测试面全程隐形。
        assertEquals(
            "回给模型的必须是推进后代次（推进前=4，推进后=5，断言要能区分这两者）",
            "5", result["generation"]!!.jsonPrimitive.content,
        )
        assertEquals("替身自己的代次同步推进（与真身逐字同语义）", 5, gateway.generation)
    }

    /**
     * **本设计的地基**：index 是模型唯一的把手（`BrowserPageSnapshot` 头部那条纪律 +
     * spec §3「模型永远看不到 CSS selector」）。`clickScript` 的 value 恰恰就是
     * `{clicked: selFor(el)}` —— 回显它等于教模型「selector 也可寻址」，而 index+代次
     * 这套契约存在的理由正是杀掉「同一枚 selector 在页面变化后静默点到另一个元素」；
     * 顺带还把页面自己控制的 id 文本未经过滤地递进了上下文。
     *
     * 所以成功时回给模型的是**它自己传进来的那枚把手**。
     */
    @Test
    fun clickSuccessEchoesTheHandleTheModelItselfPassed() = runBlocking {
        val byIndex = FakeGateway(
            elements = elementSnapshot(),
            runResult = true to """{"clicked":"#q"}""",
        )
        val indexResult = BrowserTool.execute(
            byIndex,
            argsOf("\"action\":\"click\"", "\"index\":\"2\"", "\"generation\":\"4\""),
        ) {}
        assertEquals("index=2", obj(indexResult)["target"]!!.jsonPrimitive.content)
        assertNoSelectorLeak(indexResult)

        // 坐标那支同理：JS 回的是「实际点到的那个元素」的 selector，一样不许出口。
        val byPoint = FakeGateway(runResult = true to """{"clicked":"body > div:nth-of-type(9) > #q"}""")
        val pointResult = BrowserTool.execute(
            byPoint,
            argsOf("\"action\":\"click\"", "\"generation\":\"4\"", "\"x\":\"12\"", "\"y\":\"30\""),
        ) {}
        assertEquals("x=12,y=30", obj(pointResult)["target"]!!.jsonPrimitive.content)
        assertNoSelectorLeak(pointResult)
    }

    /**
     * `ACTIONS` 是声明侧的真值来源，而 `definitionAndDescriptionStayInLockstep` 只钉得住
     * 「ACTIONS ↔ DEFINITION 的 enum」—— 钉不住「加了动作、忘了写分支」。过去末支是
     * `else -> reload`，那种遗忘会被**当成页面重载执行**（错执行，不是拒执行）。
     * 现在末支是 `NOT_IMPLEMENTED`，这条测试就是它真红的地方。
     */
    @Test
    fun everyDeclaredActionIsImplemented() = runBlocking {
        val offenders = BrowserTool.ACTIONS.filter { action ->
            val result = BrowserTool.execute(
                FakeGateway(elements = elementSnapshot(), runResult = true to """{"ok":1}"""),
                argsOf(
                    "\"action\":\"$action\"",
                    "\"url\":\"https://example.com\"",
                    "\"index\":\"1\"",
                    "\"generation\":\"4\"",
                    "\"text\":\"关键词\"",
                    "\"x\":\"10\"",
                    "\"y\":\"20\"",
                    "\"direction\":\"down\"",
                    "\"amount\":\"100\"",
                    "\"offset\":\"0\"",
                    "\"max_chars\":\"100\"",
                ),
            ) {}
            "NOT_IMPLEMENTED" in result
        }
        assertTrue("这些动作在 ACTIONS 里却没有自己的分支，会被末支吞掉：$offenders", offenders.isEmpty())

        // reload 与 navigate 各走各的门：末支写死之后仍要证明没被混成一次 loadUrl。
        val reloaded = FakeGateway()
        BrowserTool.execute(reloaded, argsOf("\"action\":\"reload\"")) {}
        assertEquals("reload 要真的走 reload()", 1, reloaded.reloads)
        assertEquals("reload 不许被读成 navigate", null, reloaded.navigatedTo)
    }

    /** 裁决 2：全局开关在执行侧的复查 —— 是一句**拒绝**，不是审批（PORTING §5.68）。 */
    @Test
    fun disabledSwitchRefusesTheToolWithoutAskingAnyone() {
        val refusal = BrowserTool.rejectIfDisabled(false)
        assertNull("开关开 = 放行", BrowserTool.rejectIfDisabled(true))
        val result = obj(refusal!!)
        assertEquals("tool_error", result["type"]!!.jsonPrimitive.content)
        assertEquals("browser_disabled", result["error"]!!.jsonPrimitive.content)
        val instruction = result["instruction"]!!.jsonPrimitive.content
        assertTrue("要说清是用户关掉的、去设置里开", instruction.contains("browser"))
        assertTrue("要叫它别再敲这颗工具", instruction.contains("Do not call"))
        assertTrue("每条错误都要有下一步", instruction.isNotBlank())
        assertNull("不许挂起等人：没有 pending 这种东西", result["pending"])
    }

    /** 参数错误 ≠ scheme 拒绝（B8：缺哪个参数就点名哪个）。 */
    @Test
    fun navigateWithoutUrlIsAnArgumentErrorNotASchemeRefusal() = runBlocking {
        val gateway = FakeGateway()
        val result = obj(BrowserTool.execute(gateway, argsOf("\"action\":\"navigate\"")) {})
        assertEquals("invalid_arguments", result["error"]!!.jsonPrimitive.content)
        assertTrue(
            "violation 必须点名 url",
            result["violations"]!!.jsonArray.any { it.jsonObject["param"]!!.jsonPrimitive.content == "url" },
        )
        assertTrue("不许把没给地址报成「地址被挡」", !result.toString().contains("BLOCKED_SCHEME"))
        assertEquals("什么都没给，就不许 loadUrl", null, gateway.navigatedTo)
    }

    /**
     * spec §4「填完就停」的另一半：**「没给 text」不等于「要把字段清空」**。
     * 旧形状会照着空串往下写，等于一次谁都没要求的清空。
     */
    @Test
    fun typeWithoutTextIsRefusedBeforeTouchingThePage() = runBlocking {
        val gateway = FakeGateway(elements = elementSnapshot(), runResult = true to """{"typed":0}""")
        val result = obj(
            BrowserTool.execute(
                gateway,
                argsOf("\"action\":\"type\"", "\"index\":\"2\"", "\"generation\":\"4\""),
            ) {},
        )
        assertEquals("invalid_arguments", result["error"]!!.jsonPrimitive.content)
        assertTrue(
            "violation 必须点名 text",
            result["violations"]!!.jsonArray.any { it.jsonObject["param"]!!.jsonPrimitive.content == "text" },
        )
        assertEquals("参数不全之前不许把脚本递进页面", "", gateway.lastScript)
        assertEquals("更不许推进代次", 0, gateway.bumps)
    }

    @Test
    fun typeCarriesTheTextAndBumpsOnce() = runBlocking {
        val gateway = FakeGateway(
            elements = elementSnapshot(),
            runResult = true to """{"typed":3,"where":"#q"}""",
        )
        val content = BrowserTool.execute(
            gateway,
            argsOf("\"action\":\"type\"", "\"index\":\"2\"", "\"generation\":\"4\"", "\"text\":\"关键词\""),
        ) {}
        val result = obj(content)
        assertEquals("tool_result", result["type"]!!.jsonPrimitive.content)
        assertTrue("文本要作为 JSON 字面量内联（转义交给 kotlinx）", gateway.lastScript.contains("\"text\":\"关键词\""))
        assertTrue("目标是快照里那条 input 的 selector", gateway.lastScript.contains("#q"))
        assertEquals("index=2", result["target"]!!.jsonPrimitive.content)
        assertEquals("3", result["chars"]!!.jsonPrimitive.content)
        assertNoSelectorLeak(content)
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

    /**
     * spec §5：图超限不发给模型，但**必须点名下一步**（「页面过大，请用 read 分段取正文」）。
     * 只回 `status:"ok"` + `omitted` 等于告诉模型「一切正常，只是没图」，它就只会再敲一次
     * screenshot。
     */
    @Test
    fun oversizedScreenshotPointsAtReadInsteadOfJustShrugging() = runBlocking {
        val images = mutableListOf<ToolImageBytes>()
        val result = obj(
            BrowserTool.execute(
                FakeGateway(shotBytes = BrowserSession.MAX_PNG_BYTES + 1),
                argsOf("\"action\":\"screenshot\""),
            ) { images.add(it) },
        )
        assertEquals("tool_result", result["type"]!!.jsonPrimitive.content)
        assertTrue("超限的图不许发给模型", images.isEmpty())
        assertNotNull("要说明为什么没图", result["omitted"])
        val instruction = result["instruction"]!!.jsonPrimitive.content
        assertTrue("下一步必须是另一条读法：$instruction", instruction.contains("read"))
    }
}

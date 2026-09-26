package com.psyche.memo.provider.browser

import com.psyche.memo.common.logging.TokenEstimator
import com.psyche.memo.provider.tool.ToolImageBytes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具面（spec §12.1 那 13 颗）：门控、错误形状、代次、scheme、四个新动作。全部走替身
 * `FakeGateway`（不碰 WebView）。
 *
 * 断言里最要紧的两条：**错误必带 instruction**（模型只会照着它下一步动），
 * 和**给模型的文本里没有 CSS selector**。
 */
class BrowserToolsTest {

    /**
     * 替身必须遵守它所替接口的**文档语义**，且要忠实到它替的**整个**方法：
     * `BrowserGateway.bumpGenerationAndDropSnapshot` 的真身（BrowserSession.kt
     * `bumpGenerationAndDropSnapshot()`）做**两件**事 —— `generationState++` **且**
     * `snapshotState = null`。
     *
     * v1 曾两度不忠实，各自掩护过一类真回归：
     * ① 「snapshot 永不作废」⇒ 回显把手排在作废之后，真会话上 index 类动作全被回成
     *    `x=null,y=null`（v1 第二轮抓到的那条）；
     * ② 「generation 是构造期定死的 val」⇒ `ok()` 回给模型的 `generation` 到底是
     *    推进前还是推进后，测试面全程隐形。
     * 新增的 `goForward` 同理：真身前进成功那一次**自己换代次**，替身不许只回个 true。
     */
    private class FakeGateway(
        initialGeneration: Int = 4,
        override val userControls: Boolean = false,
        elements: BrowserPageSnapshot? = null,
        var runResult: Pair<Boolean, String> = false to "NOT_IN_TESTS",
        private val shotBytes: Int = 8,
        private val pageTitle: String = "示例页",
        private val forwardAvailable: Boolean = false,
        private val backAvailable: Boolean = false,
        private val initialTabs: List<BrowserTabInfo> = listOf(
            BrowserTabInfo(0, "示例", "https://example.com", active = true),
        ),
    ) : BrowserGateway {
        override val url = "https://example.com"

        /** 信封里那条 `title` 的来源。真身是 `onReceivedTitle` 维护的，替身给一个常量就够。 */
        override val title: String = pageTitle

        /**
         * 「页面还在加载吗」的替身。**用 var 暴露**：`still_loading` 那条断言要能把
         * 「等满上限还在加载」这个状态摆出来（真身读的是 `onProgressChanged`）。
         */
        override var loading: Boolean = false
        override val isClosed = false
        override var generation: Int = initialGeneration
            private set
        private var snapshotState: BrowserPageSnapshot? = elements
        override val snapshot: BrowserPageSnapshot? get() = snapshotState
        var published: BrowserPageSnapshot? = null
        var lastScript = ""

        /** `browser_wait` 每 [300] ms 重跑一次同一支脚本，所以要按**次序**发结果。 */
        val scripts = mutableListOf<String>()
        val queued = ArrayDeque<Pair<Boolean, String>>()
        var navigatedTo: String? = null

        /** **工具侧**显式调用 `bumpGenerationAndDropSnapshot()` 的次数（`click`/`type`/`select` 才该碰它）。 */
        var bumps = 0

        /** **会话自己**推进代次的次数（真身：goBack/goForward 那一跳、navigate/reload 落地）。 */
        var sessionBumps = 0
        var reloads = 0
        var backs = 0
        var forwards = 0

        // scheme 判据**调生产那条** `isAllowedUrl`：替身自己抄一份 `startsWith("https://")`
        // 的话，§15 放开 http 之后这条闸门在测试里就是假的。
        override suspend fun navigate(url: String): Result<Unit> =
            if (isAllowedUrl(url)) {
                navigatedTo = url
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("BLOCKED_SCHEME"))
            }

        override suspend fun run(script: String, timeoutMs: Long): Pair<Boolean, String> {
            lastScript = script
            scripts.add(script)
            return queued.removeFirstOrNull() ?: runResult
        }

        override suspend fun screenshotPng(): ByteArray = ByteArray(shotBytes)
        override suspend fun goBack(): Boolean {
            if (!backAvailable) return false
            backs++
            bumpBySession()
            return true
        }

        override suspend fun goForward(): Boolean {
            if (!forwardAvailable) return false
            forwards++
            bumpBySession()
            return true
        }

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
            bumpBySession()
        }

        /**
         * 推进代次 + 作废快照（与真身同一对动作）。
         *
         * 单独一支函数是为了把**计数**分开：`bumps` 只数「工具侧显式调用接口方法」，
         * `sessionBumps` 只数「会话自己那一跳」。两支混在一个计数里，
         * `navigationActionsNeverBumpTheGenerationThemselves` 就没法判「工具叠了第二层」——
         * 那正是它存在的理由（替身自己那次 bump 是真身行为，不许为了绿而拿掉）。
         */
        private fun bumpBySession() {
            sessionBumps++
            generation++
            snapshotState = null
        }

        var notice: String? = null
        override fun drainNotice(): String? = notice.also { notice = null }

        // ------------------------------------------------------------ 标签（spec §12.2）
        //
        // 替身照 `BrowserSession` 那三处的**真语义**写，不图省事：
        // - 切到另一枚标签**必然换代次**（真身在 `activate` 里发新号，这是跨标签 index 别名的
        //   唯一解）；切到**已经是活动的那一枚**不换代次（什么都没变）；
        // - 关掉活动标签 → 继任者被 activate（换代次）；关掉非活动的那枚 → 活动标签还是同一枚，
        //   只是下标左移，**不许**换代次；
        // - 关到空 → 补一枚空白标签（会话恰有一个活动标签这条不变量）。
        var tabCap: Int = BrowserSession.MAX_TABS
        val tabList = initialTabs.toMutableList()
        var openedTabs: List<String> = emptyList()
        var closedTabs: List<Int> = emptyList()

        private val activeTabIndex: Int get() = tabList.indexOfFirst { it.active }

        override fun tabInfos(): List<BrowserTabInfo> = tabList.toList()

        override suspend fun openTab(url: String): Result<Unit> {
            if (tabList.size >= tabCap) return Result.failure(IllegalStateException("TAB_LIMIT"))
            if (!isAllowedUrl(url)) return Result.failure(IllegalStateException("BLOCKED_SCHEME"))
            openedTabs = openedTabs + url
            tabList.replaceAll { it.copy(active = false) }
            tabList.add(BrowserTabInfo(tabList.size, "第 ${tabList.size + 1} 页", url, active = true))
            bumpBySession()
            return Result.success(Unit)
        }

        override suspend fun selectTab(index: Int): Result<Unit> {
            if (index !in tabList.indices) {
                return Result.failure(IllegalStateException("TAB_INDEX_INVALID"))
            }
            if (index == activeTabIndex) return Result.success(Unit)
            tabList.replaceAll { it.copy(active = it.index == index) }
            bumpBySession()
            return Result.success(Unit)
        }

        override suspend fun closeTab(index: Int): Result<Unit> {
            if (index !in tabList.indices) {
                return Result.failure(IllegalStateException("TAB_INDEX_INVALID"))
            }
            closedTabs = closedTabs + index
            val wasActive = tabList[index].active
            tabList.removeAt(index)
            if (tabList.isEmpty()) {
                tabList.add(BrowserTabInfo(0, "空白标签", "", active = true))
                bumpBySession()
                return Result.success(Unit)
            }
            if (wasActive) {
                val next = index.coerceAtMost(tabList.lastIndex)
                tabList.replaceAll { it.copy(active = it.index == next) }
                bumpBySession()
            } else {
                // 活动的那一枚还在，只是被挤了一格：按下标重建，代次**不动**（页面根本没换）。
                val rebuilt = tabList.mapIndexed { i, tab -> tab.copy(index = i) }
                tabList.clear()
                tabList.addAll(rebuilt)
            }
            return Result.success(Unit)
        }
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

    /** 一支**什么都不匹配也算成功**的 find 回包，给「每颗都实现了」那条循环用。 */
    private val anyFindPayload = """{"url":"https://example.com","title":"示例","elements":[
        |{"index":1,"tag":"a","role":"link","text":"下一页","placeholder":null,"type":null,
        | "href":null,"selector":"a","options":null,"optionTotal":0,
        | "bounds":{"x":0,"y":0,"width":40,"height":16}}]}""".trimMargin()

    private fun findPayloadOf(json: String): Pair<Boolean, String> = true to json

    /**
     * 「递给模型的整条结果里不许出现 CSS selector」—— 对着**字符串**断言，不挑字段：
     * 泄漏的形状以后可能是 detail、可能是 message，只钉一个键名等于没钉。
     */
    private fun assertNoSelectorLeak(result: String) {
        assertTrue("结果里出现了结构路径 selector（模型唯一的把手是 index）：$result", !result.contains("nth-of-type"))
        assertTrue("结果里出现了快照自己页面的 id：$result", !result.contains("#q"))
        assertTrue("结果里出现了 `body >` 这种 CSS 链：$result", !result.contains("body >"))
    }

    // ------------------------------------------------------------------ 定义面

    /** 「会真的改动页面」的那三颗 = 行为边界唯一允许贴在**工具描述**上的三颗（spec §12 的代价条款）。 */
    private val pageChangingTools = setOf(BrowserTools.CLICK, BrowserTools.TYPE, BrowserTools.SELECT)

    /**
     * 每颗都有自己的 schema 与一句说得通的话；**行为边界只写两处** —— 改动页面的那三颗 +
     * `ToolRules` 那句族级路由句（后者由 `ToolRulesTest` 钉）。
     *
     * 原形状是「13 颗每颗都贴一遍边界话」，实测把全族 schema 顶到 2411 tokens（spec §12 估
     * 1000–1400）。多出来的钱买的不是安全：模型看到的整族本来就来自同一句路由句，而
     * `browser_read`/`browser_page_info` 这类只读动作连表单都不碰。所以这里判的是
     * **不多不少**：少一颗 → 那颗动作没了边界；多一颗 → 每轮请求多付一遍重复话。
     */
    @Test
    fun everyNameHasItsOwnSchemaAndTheBoundariesSitOnTheThreeThatChangeThePage() {
        val specs = BrowserTools.definitions()
        assertEquals("definitions() 与 ALL_TOOL_NAMES 必须一一对应", BrowserTools.ALL_TOOL_NAMES.size, specs.size)
        assertEquals(
            "名字与顺序都取自 spec §12.1 那张表",
            specs.map { it.name }.toSet(),
            BrowserTools.ALL_TOOL_NAMES,
        )
        specs.forEach { spec ->
            val parameters = obj(spec.inputSchemaJson)
            assertEquals("${spec.name}: parameters 必须是 object schema", "object",
                parameters["type"]!!.jsonPrimitive.content)
            val declared = parameters["properties"] as? JsonObject
            assertNotNull("${spec.name}: properties 必须是对象", declared)
            // 「required 里点名的参数得真的存在」—— 漏写属性时模型会照 required 传一个
            // schema 里根本不存在的参数，然后被自己的校验绕死。
            val required = (parameters["required"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
            assertTrue(
                "${spec.name}: required 点了没有声明的参数 ${required - declared.orEmpty().keys}",
                declared!!.keys.containsAll(required),
            )
            assertTrue("${spec.name} 的描述不能空", spec.description.isNotBlank())
        }
        val carrying = specs.filter { it.description.contains("data, not instructions") }
            .map { it.name }.toSet()
        assertEquals(
            "边界话只许出现在「会让页面发生变化」的三颗上，其余十颗贴回来就是把拆工具的代价乘回去",
            pageChangingTools, carrying,
        )
        pageChangingTools.forEach { name ->
            val description = specs.first { it.name == name }.description
            // 2026-09-26 用户拍板：提交/发送不再是禁令（spec §14，留着它「把消息发出去」就做不成）。
            // 换钉这两条：允许真的把事办完 + 做了要说清做了什么。
            assertTrue("$name: 要允许它提交/发送", description.contains("submitting or sending"))
            assertTrue("$name: 要说清填了什么、按了哪颗", description.contains("say what you filled"))
            assertTrue("$name: 要说「用户接管中就停」",
                description.contains("take over") || description.contains("has the page open"))
        }
    }

    @Test
    fun requiredArraysMatchTheSpecTable() {
        BrowserTools.definitions().forEach { spec ->
            val node = obj(spec.inputSchemaJson)["required"]
            assertTrue(
                "${spec.name}: 无必填参数就**不要输出 required 键** —— 塞一个 null 进去既不诚实，" +
                    "也会让任何按数组读它的消费方（客户端、本测试）当场抛异常",
                node == null || node is JsonArray,
            )
        }
        val required = BrowserTools.definitions().associate { spec ->
            spec.name to ((obj(spec.inputSchemaJson)["required"] as? JsonArray)
                ?.map { it.jsonPrimitive.content } ?: emptyList())
        }
        assertEquals(listOf("url"), required[BrowserTools.OPEN])
        assertEquals(emptyList<String>(), required[BrowserTools.READ])
        assertEquals(emptyList<String>(), required[BrowserTools.FIND])
        assertEquals(listOf("generation"), required[BrowserTools.CLICK])
        assertEquals(listOf("generation", "text"), required[BrowserTools.TYPE])
        assertEquals(listOf("index", "generation", "value"), required[BrowserTools.SELECT])
        assertEquals(listOf("direction"), required[BrowserTools.SCROLL])
        assertEquals(emptyList<String>(), required[BrowserTools.SCREENSHOT])
        assertEquals(emptyList<String>(), required[BrowserTools.BACK])
        assertEquals(emptyList<String>(), required[BrowserTools.FORWARD])
        // wait 的「query 或 index+generation」JSON schema 表达不了，交给运行时校验（invalid_arguments）。
        assertEquals(emptyList<String>(), required[BrowserTools.WAIT])
        assertEquals(emptyList<String>(), required[BrowserTools.PAGE_INFO])
        assertEquals(emptyList<String>(), required[BrowserTools.RELOAD])
        assertTrue(
            "browser_wait 不许吃 CSS selector（v1 的地基，spec §12.1 明写）",
            obj(BrowserTools.definitions().first { it.name == BrowserTools.WAIT }
                .inputSchemaJson)["properties"]!!.jsonObject.keys == setOf("query", "index", "generation", "timeout_ms"),
        )
    }

    /**
     * **用户对价的凭据**：spec §12 写「13 份 schema 每轮都进请求（估 ~1000–1400 token，
     * 比单工具的 ~180 token 贵）」。这条把这份开销钉成可回归的数字 —— 描述再往上堆就是
     * 在给每一轮请求加钱，而那份钱是用户点头才花的。
     *
     * 首版实测 **2411 tokens / 9641 字符**（超估算近一倍），根因是「同一段边界话贴 13 遍」；
     * 按「边界只留三颗 + 族级一句」砍到 **1323 tokens / 5291 字符**（拆分前单工具是 ~180）。
     * 天花板取 spec §12 估算区间的**上沿 1400**：实测在它下面，且不是「刚好过」的数 ——
     * 上限也不许改成下一次实测值蒙过去，要涨得先在 PORTING 里点名让知情的人签字。
     */
    @Test
    fun theWholeFamilyStaysWithinTheSchemaBudget() {
        val rendered = BrowserTools.definitions().joinToString("") {
            it.name + it.description + it.inputSchemaJson
        }
        val tokens = TokenEstimator.estimate(rendered)
        assertTrue(
            "浏览器族的 schema = $tokens tokens（${rendered.length} 字符）。spec §12 的估算区间是 " +
                "1000–1400、天花板给在上沿 1400（首版重复边界话时是 2411）：超了说明描述又在膨胀，" +
                "要么删话、要么把这句话记进 PORTING 让知情的人签字。",
            tokens <= 1_400,
        )
    }

    // ------------------------------------------------------------------ 门控与错误形状

    /** 裁决 2：全局开关在执行侧的复查 —— 是一句**拒绝**，不是审批（PORTING §5.68）。 */
    @Test
    fun disabledSwitchRefusesTheFamilyWithoutAskingAnyone() {
        val refusal = BrowserTools.rejectIfDisabled(false)
        assertNull("开关开 = 放行", BrowserTools.rejectIfDisabled(true))
        val result = obj(refusal!!)
        assertEquals("tool_error", result["type"]!!.jsonPrimitive.content)
        assertEquals("browser_disabled", result["error"]!!.jsonPrimitive.content)
        val instruction = result["instruction"]!!.jsonPrimitive.content
        assertTrue("要说清是用户关掉的、去设置里开", instruction.contains("browser"))
        assertTrue("要叫它别再敲这族工具", instruction.contains("Do not call"))
        assertTrue("每条错误都要有下一步", instruction.isNotBlank())
        assertNull("不许挂起等人：没有 pending 这种东西", result["pending"])
    }

    @Test
    fun unknownToolNameIsAnInvalidArgumentsErrorWithViolations() = runBlocking {
        val result = obj(BrowserTools.execute("browser_teleport", FakeGateway(), argsOf()) {})
        assertEquals("tool_error", result["type"]!!.jsonPrimitive.content)
        assertEquals("invalid_arguments", result["error"]!!.jsonPrimitive.content)
        assertNotNull("必须给模型一份能照着改的清单", result["violations"]?.jsonArray)
        assertTrue("每条错误都要交代下一步", result["instruction"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(
            "补救话里要列出可用的名字",
            result["instruction"]!!.jsonPrimitive.content.contains(BrowserTools.OPEN),
        )
    }

    @Test
    fun userTakeoverBlocksEveryAction() = runBlocking {
        BrowserTools.ALL_TOOL_NAMES.forEach { name ->
            val result = obj(
                BrowserTools.execute(
                    name,
                    FakeGateway(userControls = true),
                    argsOf("\"url\":\"https://example.com\"", "\"index\":\"1\"", "\"generation\":\"4\""),
                ) {},
            )
            assertEquals("$name 在用户接管时不许执行", "USER_CONTROLS_PAGE", result["error"]!!.jsonPrimitive.content)
        }
    }

    // ------------------------------------------------------------------ open / read / find

    /**
     * §15（2026-09-26 用户「不要弄很高的安全，你看看人家 Eta」）之后，被挡的不再是明文 http，
     * 而是**本地与代码类 scheme**：那几样炸的是用户手机里的文件，放开对自动化一点用也没有。
     */
    @Test
    fun localAndCodeSchemesNeverReachTheWebView() = runBlocking {
        listOf("file:///sdcard/a", "content://m/text", "javascript:alert(1)", "data:text/html,hi", "memo://x")
            .forEach { url ->
                val gateway = FakeGateway()
                val result = obj(
                    BrowserTools.execute(BrowserTools.OPEN, gateway, argsOf("\"url\":\"$url\"")) {},
                )
                assertEquals("$url 必须是 BLOCKED_SCHEME", "BLOCKED_SCHEME", result["error"]!!.jsonPrimitive.content)
                assertEquals("$url 不许 loadUrl", null, gateway.navigatedTo)
            }
    }

    /** 明文 http 现在收（内网/老站/纯明文站都要能被自动化打开）。 */
    @Test
    fun plainHttpNowReachesTheWebView() = runBlocking {
        val gateway = FakeGateway()
        val result = obj(
            BrowserTools.execute(BrowserTools.OPEN, gateway, argsOf("\"url\":\"http://e.com\"")) {},
        )
        assertEquals("http://e.com", gateway.navigatedTo)
        assertNull("不许再报 BLOCKED_SCHEME", result["error"])
    }

    /** 参数错误 ≠ scheme 拒绝（B8：缺哪个参数就点名哪个）。 */
    @Test
    fun openWithoutUrlIsAnArgumentErrorNotASchemeRefusal() = runBlocking {
        val gateway = FakeGateway()
        val result = obj(BrowserTools.execute(BrowserTools.OPEN, gateway, argsOf()) {})
        assertEquals("invalid_arguments", result["error"]!!.jsonPrimitive.content)
        assertTrue(
            "violation 必须点名 url",
            result["violations"]!!.jsonArray.any { it.jsonObject["param"]!!.jsonPrimitive.content == "url" },
        )
        assertTrue("不许把没给地址报成「地址被挡」", !result.toString().contains("BLOCKED_SCHEME"))
        assertEquals("什么都没给，就不许 loadUrl", null, gateway.navigatedTo)
    }

    @Test
    fun findPublishesSnapshotAndHidesSelectors() = runBlocking {
        val payload = """{"url":"https://example.com","title":"示例","elements":[
            |{"index":1,"tag":"a","role":"link","text":"下一页","placeholder":null,"type":null,
            | "href":"https://example.com/p2","selector":"body > a:nth-of-type(2)",
            | "bounds":{"x":0,"y":0,"width":40,"height":16}}]}""".trimMargin()
        val gateway = FakeGateway(runResult = findPayloadOf(payload))
        val result = obj(BrowserTools.execute(BrowserTools.FIND, gateway, argsOf("\"query\":\"下\"")) {})
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
        val gateway = FakeGateway(runResult = findPayloadOf(payload))
        val result = obj(BrowserTools.execute(BrowserTools.FIND, gateway, argsOf()) {})
        assertEquals("true", result["capped"]!!.jsonPrimitive.content)
        assertTrue(
            "给模型的文本里要有那句「可能不完整」",
            result["block"]!!.jsonPrimitive.content.contains("清单可能不完整"),
        )
    }

    /**
     * `browser_select` 要有东西可选，`find` 就得把 `<select>` 的可选项带出来；但**一条 select
     * 最多列 12 个**（spec §12.1），否则几百项的下拉框会把 20 条元素的清单冲掉。
     * 这里同时钉 JS 侧那个上限与解析端的兜底（页面给更多也只留 12）。
     */
    @Test
    fun findListsSelectableOptionsButCapsTheListAtTwelve() = runBlocking {
        val options = (1..15).joinToString(",") {
            """{"value":"v$it","text":"项 $it","selected":${it == 1}}"""
        }
        val payload = """{"url":"https://example.com","title":"示例","elements":[
            |{"index":1,"tag":"select","role":null,"text":"项 1","placeholder":null,"type":null,
            | "href":null,"selector":"select#city","options":[$options],"optionTotal":15,
            | "bounds":{"x":0,"y":0,"width":80,"height":20}}]}""".trimMargin()
        val gateway = FakeGateway(runResult = findPayloadOf(payload))
        val result = obj(BrowserTools.execute(BrowserTools.FIND, gateway, argsOf()) {})
        val block = result["block"]!!.jsonPrimitive.content

        assertEquals("递给模型的 option 最多 12 条", 12, gateway.published!!.find(1)!!.options.size)
        assertEquals("总数要照页面的真实值报出来", 15, gateway.published!!.find(1)!!.optionTotal)
        assertTrue("清单里要带可选值：$block", block.contains("可选:"))
        assertTrue("模型看得懂「项 12」", block.contains("项 12"))
        assertTrue(
            "第 13 条不许出现在文本里（截到了 12 就必须说明总数）",
            !block.contains("项 13") && block.contains("…共 15 项"),
        )
        assertNoSelectorLeak(result.toString())
    }

    @Test
    fun readReportsNextOffsetWhenTruncated() = runBlocking {
        val gateway = FakeGateway(
            runResult = findPayloadOf("""{"text":"一长段正文","truncated":true,"offset":0,"nextOffset":12}"""),
        )
        val result = obj(BrowserTools.execute(BrowserTools.READ, gateway, argsOf()) {})
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
            runResult = findPayloadOf("""{"text":"","truncated":true,"offset":0,"nextOffset":0}"""),
        )
        val result = obj(BrowserTools.execute(BrowserTools.READ, gateway, argsOf()) {})
        assertEquals("tool_error", result["type"]!!.jsonPrimitive.content)
        assertEquals("READ_STALLED", result["error"]!!.jsonPrimitive.content)
        assertNull("不许给出不会前进的续读把手", result["next_offset"])
        assertTrue(
            "补救话要指向另一条读法",
            result["instruction"]!!.jsonPrimitive.content.contains("browser_find"),
        )
    }

    // ------------------------------------------------------------------ click / type / select

    @Test
    fun clickRequiresTheCurrentGeneration() = runBlocking {
        val withIndex = obj(
            BrowserTools.execute(BrowserTools.CLICK, FakeGateway(), argsOf("\"index\":\"1\"")) {},
        )
        assertEquals("STALE_GENERATION", withIndex["error"]!!.jsonPrimitive.content)
        assertTrue(
            "没传 generation 的补救话要指向先 find",
            withIndex["instruction"]!!.jsonPrimitive.content.contains("find"),
        )

        val stale = obj(
            BrowserTools.execute(
                BrowserTools.CLICK,
                FakeGateway(),
                argsOf("\"index\":\"1\"", "\"generation\":\"3\""),
            ) {},
        )
        assertEquals("STALE_GENERATION", stale["error"]!!.jsonPrimitive.content)
        assertTrue("旧代次要点名现在是第几代", stale["message"]!!.jsonPrimitive.content.contains("4"))
    }

    @Test
    fun staleSnapshotIndexIsRefusedEvenWithRightGeneration() = runBlocking {
        val result = obj(
            BrowserTools.execute(
                BrowserTools.CLICK,
                FakeGateway(),
                argsOf("\"index\":\"1\"", "\"generation\":\"4\""),
            ) {},
        )
        assertEquals("TARGET_NOT_FOUND", result["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun clickResolvesIndexToTheSelectorInternally() = runBlocking {
        val gateway = FakeGateway(
            elements = elementSnapshot(),
            runResult = findPayloadOf("""{"clicked":"#q"}"""),
        )
        val result = obj(
            BrowserTools.execute(
                BrowserTools.CLICK,
                gateway,
                argsOf("\"index\":\"1\"", "\"generation\":\"4\""),
            ) {},
        )
        assertEquals("tool_result", result["type"]!!.jsonPrimitive.content)
        // 点击之后还会再跑两发**取证**脚本（页面指纹），所以 lastScript 不是点击那一发 ——
        // 断言改成「整串里有一发带着快照里的 selector」，并顺手钉住前后各取了一次指纹。
        assertTrue(
            "内联的目标必须是快照里的 selector",
            gateway.scripts.any { it.contains("body > a:nth-of-type(2)") },
        )
        assertEquals(
            "点击要留下「能不能观察到变化」的证据 ⇒ 点前点后各一发指纹",
            2,
            gateway.scripts.count { it.contains("childElementCount") },
        )
        assertEquals("点完要把代次推进、旧 index 作废（只推进一次）", 1, gateway.bumps)
        // 信封里的 `generation` 是模型下一颗动作的**入场券**（`ok()` 在 `settleAfterAction()`
        // 之后读 `gateway.generation`）：作废已发生，回给它的就必须是推进后的那一代。
        // 替身以前 `generation` 是定死的 val，这条整类回归在测试面全程隐形。
        assertEquals(
            "回给模型的必须是推进后代次（推进前=4，推进后=5，断言要能区分这两者）",
            "5", result["generation"]!!.jsonPrimitive.content,
        )
        assertEquals("替身自己的代次同步推进（与真身逐字同语义）", 5, gateway.generation)
    }

    /**
     * **本设计的地基**：index 是模型唯一的把手（`BrowserPageSnapshot` 头部那条纪律 +
     * spec §3「模型永远看不到 CSS selector」）。JS 回的是「实际点到的那个元素」的 selector，
     * 回显它等于教模型「selector 也可寻址」。所以成功时回给模型的是**它自己传进来的那枚把手**。
     */
    @Test
    fun clickSuccessEchoesTheHandleTheModelItselfPassed() = runBlocking {
        val byIndex = FakeGateway(
            elements = elementSnapshot(),
            runResult = findPayloadOf("""{"clicked":"#q"}"""),
        )
        val indexResult = BrowserTools.execute(
            BrowserTools.CLICK, byIndex, argsOf("\"index\":\"2\"", "\"generation\":\"4\""),
        ) {}
        assertEquals("index=2", obj(indexResult)["target"]!!.jsonPrimitive.content)
        assertNoSelectorLeak(indexResult)

        val byPoint = FakeGateway(runResult = findPayloadOf("""{"clicked":"body > div:nth-of-type(9) > #q"}"""))
        val pointResult = BrowserTools.execute(
            BrowserTools.CLICK, byPoint, argsOf("\"generation\":\"4\"", "\"x\":\"12\"", "\"y\":\"30\""),
        ) {}
        assertEquals("x=12,y=30", obj(pointResult)["target"]!!.jsonPrimitive.content)
        assertNoSelectorLeak(pointResult)
    }

    /** 参数错误 ≠ scheme 拒绝（B8）：`type` 少了 text 不许被读成「清空字段」。 */
    @Test
    fun typeWithoutTextIsRefusedBeforeTouchingThePage() = runBlocking {
        val gateway = FakeGateway(elements = elementSnapshot(), runResult = findPayloadOf("""{"typed":0}"""))
        val result = obj(
            BrowserTools.execute(
                BrowserTools.TYPE, gateway, argsOf("\"index\":\"2\"", "\"generation\":\"4\""),
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
            runResult = findPayloadOf("""{"typed":3,"where":"#q"}"""),
        )
        val content = BrowserTools.execute(
            BrowserTools.TYPE, gateway,
            argsOf("\"index\":\"2\"", "\"generation\":\"4\"", "\"text\":\"关键词\""),
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
     * `browser_select`（spec §12.1 的新动作）与 `type` 同一条纪律：**派发 change，绝不提交**。
     * 很多站点的下拉框 change 会自动搜/自动买，那道口子必须在这儿就堵死。
     */
    @Test
    fun selectChangesTheValueButNeverSubmitsTheForm() = runBlocking {
        val gateway = FakeGateway(
            elements = elementSnapshot().copy(
                elements = elementSnapshot().elements + BrowserElement(
                    index = 3, tag = "select", role = null, text = "北京", placeholder = null,
                    type = null, href = null, selector = "select#city",
                    bounds = Bounds(0, 40, 80, 20),
                    options = listOf(SelectOption("bj", "北京", true), SelectOption("sh", "上海", false)),
                    optionTotal = 2,
                ),
            ),
            runResult = findPayloadOf("""{"selectedIndex":1,"options":2}"""),
        )
        val content = BrowserTools.execute(
            BrowserTools.SELECT, gateway,
            argsOf("\"index\":\"3\"", "\"generation\":\"4\"", "\"value\":\"上海\""),
        ) {}
        val result = obj(content)
        assertEquals("tool_result", result["type"]!!.jsonPrimitive.content)
        assertTrue("要给 select 元素真赋值", gateway.lastScript.contains("el.selectedIndex = idx"))
        assertTrue("要派发 input/change（站点靠它联动）", gateway.lastScript.contains("new Event('change'"))
        assertTrue(
            "绝不调 requestSubmit/派发 submit —— 那是替用户按下回车",
            !gateway.lastScript.contains("requestSubmit") && !gateway.lastScript.contains("'submit'"),
        )
        assertTrue("匹配的是模型自己给的 value", gateway.lastScript.contains("\"value\":\"上海\""))
        assertEquals("index=3", result["target"]!!.jsonPrimitive.content)
        assertNoSelectorLeak(content)
        assertEquals("选完页面可能联动，旧 index 一律作废", 1, gateway.bumps)
    }

    /** 非 `<select>`：JS 抛 NOT_SELECTABLE（本批**新增的错误码**，不是复用 NOT_EDITABLE）。 */
    @Test
    fun selectOnANonSelectElementReportsNotSelectable() = runBlocking {
        val gateway = FakeGateway(elements = elementSnapshot(), runResult = false to "NOT_SELECTABLE")
        val result = obj(
            BrowserTools.execute(
                BrowserTools.SELECT, gateway,
                argsOf("\"index\":\"2\"", "\"generation\":\"4\"", "\"value\":\"上海\""),
            ) {},
        )
        assertEquals("tool_error", result["type"]!!.jsonPrimitive.content)
        assertEquals("NOT_SELECTABLE", result["error"]!!.jsonPrimitive.content)
        assertTrue(
            "补救话要把它指回 select 元素或 type",
            result["instruction"]!!.jsonPrimitive.content.contains("select"),
        )
        assertEquals("失败那次不许作废 index", 0, gateway.bumps)
    }

    /** 选项值对不上：JS 抛 OPTION_NOT_FOUND，补救话要指回 find 列出来的那批。 */
    @Test
    fun unknownOptionValueIsRefusedWithTheFindPointer() = runBlocking {
        val gateway = FakeGateway(elements = elementSnapshot(), runResult = false to "OPTION_NOT_FOUND")
        val result = obj(
            BrowserTools.execute(
                BrowserTools.SELECT, gateway,
                argsOf("\"index\":\"2\"", "\"generation\":\"4\"", "\"value\":\"火星\""),
            ) {},
        )
        assertEquals("OPTION_NOT_FOUND", result["error"]!!.jsonPrimitive.content)
        assertTrue(result["instruction"]!!.jsonPrimitive.content.contains("browser_find"))
    }

    /** `select` 少 value / 少 index 都是参数错误，不许退化成坐标点击或空选。 */
    @Test
    fun selectWithoutItsArgumentsNeverTouchesThePage() = runBlocking {
        val gateway = FakeGateway(elements = elementSnapshot(), runResult = findPayloadOf("""{"selectedIndex":0}"""))
        val noValue = obj(
            BrowserTools.execute(BrowserTools.SELECT, gateway, argsOf("\"index\":\"2\"", "\"generation\":\"4\"")) {},
        )
        assertEquals("invalid_arguments", noValue["error"]!!.jsonPrimitive.content)
        val noIndex = obj(
            BrowserTools.execute(BrowserTools.SELECT, gateway, argsOf("\"generation\":\"4\"", "\"value\":\"上海\"")) {},
        )
        assertEquals("invalid_arguments", noIndex["error"]!!.jsonPrimitive.content)
        assertEquals("两次都不许把脚本递进页面", "", gateway.lastScript)
        assertEquals(0, gateway.bumps)
    }

    // ------------------------------------------------------------------ 历史 / 几何 / 等待

    /**
     * 跳转类动作（open/back/forward/reload）的代次由 [BrowserSession] 自己在
     * `onPageFinished`（以及 goBack/goForward）里推进 —— 工具再 bump 一次等于每跳两颗，
     * 模型看到的 generation 与页面落地那次不再是同一个数。
     *
     * 两个计数**分开判**：`bumps` 只数工具侧那一次显式调用（必须恒为 0），
     * `sessionBumps` 数会话自己那一跳（back/forward 各 1，替身照真身做）。只数一个数的话
     * 「替身忠实」与「工具叠了一层」会长得一模一样，这条守卫就成了摆设。
     */
    @Test
    fun navigationActionsNeverBumpTheGenerationThemselves() = runBlocking {
        val gateway = FakeGateway(backAvailable = true, forwardAvailable = true)
        obj(BrowserTools.execute(BrowserTools.OPEN, gateway, argsOf("\"url\":\"https://example.com\"")) {})
        assertEquals("open 不许叠一层 bump", 0, gateway.bumps)
        obj(BrowserTools.execute(BrowserTools.RELOAD, gateway, argsOf()) {})
        assertEquals("reload 不许叠一层 bump", 0, gateway.bumps)
        obj(BrowserTools.execute(BrowserTools.BACK, gateway, argsOf()) {})
        obj(BrowserTools.execute(BrowserTools.FORWARD, gateway, argsOf()) {})
        assertEquals(
            "back/forward 由会话自己换代次（替身忠实到这一步），工具侧一次都不叠",
            0, gateway.bumps,
        )
        assertEquals(
            "会话那一跳各推进一次：back 1 + forward 1，工具侧没有第二层",
            2, gateway.sessionBumps,
        )
        assertEquals(
            "回给模型的代次就是会话推进后的那一代（4 → 5 → 6，多推一次这里就红）",
            6, gateway.generation,
        )
        assertEquals(1, gateway.backs)
        assertEquals(1, gateway.forwards)
    }

    @Test
    fun forwardRefusesWhenThereIsNoNextPage() = runBlocking {
        val gateway = FakeGateway()
        val result = obj(BrowserTools.execute(BrowserTools.FORWARD, gateway, argsOf()) {})
        assertEquals("NO_HISTORY", result["error"]!!.jsonPrimitive.content)
        assertEquals("没有下一页就不许动 WebView", 0, gateway.forwards)
        assertEquals("更不许换代次", 0, gateway.bumps)
        assertTrue(
            "补救话要叫它别假设页面动了",
            result["instruction"]!!.jsonPrimitive.content.contains("Do not assume"),
        )
    }

    @Test
    fun waitReportsTimeoutAndNeverActs() = runBlocking {
        val payload = """{"url":"https://example.com","title":"示例","elements":[
            |{"index":1,"tag":"a","role":"link","text":"下一页","placeholder":null,"type":null,
            | "href":null,"selector":"a","bounds":{"x":0,"y":0,"width":1,"height":1}}]}""".trimMargin()
        val gateway = FakeGateway(runResult = findPayloadOf(payload))
        val result = obj(
            BrowserTools.execute(BrowserTools.WAIT, gateway, argsOf("\"query\":\"付款成功\"", "\"timeout_ms\":\"300\"")) {},
        )
        assertEquals("tool_error", result["type"]!!.jsonPrimitive.content)
        assertEquals("WAIT_TIMEOUT", result["error"]!!.jsonPrimitive.content)
        val instruction = result["instruction"]!!.jsonPrimitive.content
        assertTrue("要说清没等到、别假设页面变了：$instruction", instruction.contains("did not show"))
        assertTrue("要给它下一步（find/read），而不是催它重试动作", instruction.contains("browser_find"))

        // **轮询期间不许有任何副作用**：只有那支只读的 findScript 被反复调用，
        // 不作废快照、不换代次、不导航、不 publish。
        assertTrue("等了就得真的轮询过", gateway.scripts.size >= 2)
        gateway.scripts.forEach {
            assertTrue("wait 只许跑只读的 find 扫描：$it", !it.contains("dispatchEvent"))
            assertTrue("不许滚动", !it.contains("scrollBy"))
            assertTrue("不许提交", !it.contains("submit"))
        }
        assertEquals("不换代次", 0, gateway.bumps)
        assertEquals("不覆盖会话的快照", null, gateway.published)
        assertEquals("不导航", null, gateway.navigatedTo)
    }

    /** index 一支：把手是模型自己给的编号；判据是本机存的 selector（绝不出口）。 */
    @Test
    fun waitByIndexReturnsWhenTheSameElementComesBack() = runBlocking {
        val gone = """{"url":"https://example.com","title":"示例","elements":[]}"""
        val back = """{"url":"https://example.com","title":"示例","elements":[
            |{"index":1,"tag":"button","role":null,"text":"提交","placeholder":null,"type":null,
            | "href":null,"selector":"body > a:nth-of-type(2)","bounds":{"x":0,"y":0,"width":1,"height":1}}]}"""
            .trimMargin()
        val gateway = FakeGateway(elements = elementSnapshot())
        gateway.queued.addLast(true to gone)
        gateway.queued.addLast(true to back)
        val result = obj(
            BrowserTools.execute(
                BrowserTools.WAIT, gateway,
                argsOf("\"index\":\"1\"", "\"generation\":\"4\"", "\"timeout_ms\":\"3000\""),
            ) {},
        )
        assertEquals("tool_result", result["type"]!!.jsonPrimitive.content)
        assertEquals("true", result["appeared"]!!.jsonPrimitive.content)
        assertEquals("回给模型的只有它自己给的编号", "index=1", result["matched"]!!.jsonPrimitive.content)
        assertNoSelectorLeak(result.toString())
        assertEquals("wait 不换代次", 0, gateway.bumps)
    }

    /** wait 两条寻址都没给 = 参数错误，一次脚本都不许递。 */
    @Test
    fun waitWithoutAnyAddressingIsAnArgumentError() = runBlocking {
        val gateway = FakeGateway(runResult = findPayloadOf("""{"elements":[]}"""))
        val result = obj(BrowserTools.execute(BrowserTools.WAIT, gateway, argsOf()) {})
        assertEquals("invalid_arguments", result["error"]!!.jsonPrimitive.content)
        assertEquals("", gateway.lastScript)
    }

    /**
     * `browser_page_info`（新动作）：滚动位置来自一支**只做四次属性读取**的脚本 ——
     * 遍历页面是 `find` 的活，这里出现遍历就等于每问一次「我在哪」付一次全页扫描。
     */
    @Test
    fun pageInfoReportsGeometryWithoutScanningThePage() = runBlocking {
        val gateway = FakeGateway(
            runResult = findPayloadOf(
                """{"url":"https://example.com","title":"示例页","scrollY":800,"scrollHeight":5000,""" +
                    """"viewportHeight":1600,"atTop":false,"atBottom":false}""",
            ),
        )
        val result = obj(BrowserTools.execute(BrowserTools.PAGE_INFO, gateway, argsOf()) {})
        assertEquals("tool_result", result["type"]!!.jsonPrimitive.content)
        assertEquals("示例页", result["title"]!!.jsonPrimitive.content)
        assertEquals("800", result["scroll_y"]!!.jsonPrimitive.content)
        assertEquals("false", result["at_bottom"]!!.jsonPrimitive.content)
        assertEquals("generation 照样回传（下一颗动作的入场券）", "4", result["generation"]!!.jsonPrimitive.content)

        val js = gateway.lastScript
        assertTrue("不许有 querySelectorAll 遍历", !js.contains("querySelectorAll"))
        assertTrue("不许有 TreeWalker 遍历", !js.contains("TreeWalker"))
        assertTrue("不许有 getComputedStyle（强制样式解析，最贵的那种遍历）", !js.contains("getComputedStyle"))
        assertTrue("只读几何那几项", js.contains("window.scrollY") && js.contains("scrollHeight"))
    }

    /** spec §7.3：挡掉弹窗不是「无事发生」，信封里必须说，且取走即清空。 */
    @Test
    fun suppressedJsDialogRidesAlongInTheResult() = runBlocking {
        val gateway = FakeGateway(
            runResult = findPayloadOf("""{"y":800,"atTop":false,"atBottom":false}"""),
        )
        gateway.notice = "JS_ALERT_SUPPRESSED:please enter email"
        val result = obj(BrowserTools.execute(BrowserTools.SCROLL, gateway, argsOf("\"direction\":\"down\"")) {})
        assertEquals("JS_ALERT_SUPPRESSED:please enter email", result["page_notice"]!!.jsonPrimitive.content)
        assertNull("取走即清空，不许下一颗调用还带着它", gateway.drainNotice())
    }

    // ------------------------------------------------------------------ 动作后的证据（2026-09-26 真机反馈）

    /** 每颗调用的信封都带 `title`：模型看不到页面状态时，它就只会凭想象写下一步。 */
    @Test
    fun everyResultCarriesTheCurrentPageTitle() = runBlocking {
        val gateway = FakeGateway(
            elements = elementSnapshot(),
            runResult = findPayloadOf("""{"text":"正文","truncated":false}"""),
        )
        val open = obj(
            BrowserTools.execute(BrowserTools.OPEN, gateway, argsOf("\"url\":\"https://x\"")) {},
        )
        assertEquals("示例页", open["title"]!!.jsonPrimitive.content)
        val read = obj(BrowserTools.execute(BrowserTools.READ, gateway, argsOf()) {})
        assertEquals("示例页", read["title"]!!.jsonPrimitive.content)
    }

    /**
     * 点击什么都没变 ⇒ 必须**明说什么都没变**，并禁止它向用户报成功。
     *
     * 这条对着的就是用户那句「他怎么老是出现自己说做了」。派发的是合成事件（`isTrusted=false`），
     * 挂着"只认真实手势"判断的按钮会安静地什么都不做 —— 那时候协议上一句 `ok` 等于替模型把谎圆了。
     */
    @Test
    fun clickThatChangesNothingOnThePageSaysSoAndForbidsClaimingSuccess() = runBlocking {
        val sig = """{"sig":"https://x|登录|0|900|3"}"""
        val gateway = FakeGateway(elements = elementSnapshot())
        gateway.queued.add(true to sig)
        gateway.queued.add(true to """{"clicked":true}""")
        gateway.queued.add(true to sig)
        val result = obj(
            BrowserTools.execute(
                BrowserTools.CLICK, gateway, argsOf("\"index\":1", "\"generation\":4"),
            ) {},
        )
        assertEquals("页面什么都没变，就得说什么都没变", "false", result["page_changed"]!!.jsonPrimitive.content)
        val note = result["note"]!!.jsonPrimitive.content
        assertTrue("必须禁止它把这下当成成功：$note", note.contains("Do NOT tell the user"))
        assertTrue("要给出下一步（先看清页面，别猜）：$note", note.contains(BrowserTools.FIND))
    }

    /** 页面真的变了 ⇒ 报 true，且不许带上那句"别报成功"。 */
    @Test
    fun clickThatChangesThePageReportsTheChange() = runBlocking {
        val gateway = FakeGateway(elements = elementSnapshot())
        gateway.queued.add(true to """{"sig":"https://x|登录|0|900|3"}""")
        gateway.queued.add(true to """{"clicked":true}""")
        gateway.queued.add(true to """{"sig":"https://x|登录|0|900|7"}""")
        val result = obj(
            BrowserTools.execute(
                BrowserTools.CLICK, gateway, argsOf("\"index\":1", "\"generation\":4"),
            ) {},
        )
        assertEquals("true", result["page_changed"]!!.jsonPrimitive.content)
        assertNull("页面变了就不该再教育一句", result["note"])
    }

    /** 取不到指纹（脚本超时 / 结构不对）⇒ **不写这个键**：宁可少一张收据，也不许把"不知道"说成"没变"。 */
    @Test
    fun unavailablePageSignatureOmitsTheJudgementInsteadOfLying() = runBlocking {
        val gateway = FakeGateway(
            elements = elementSnapshot(),
            // find 的形状当返回值：里面没有 `sig` 这个键，等于"取不到指纹"。
            runResult = findPayloadOf("""{"url":"https://x","title":"t","elements":[]}"""),
        )
        val result = obj(
            BrowserTools.execute(
                BrowserTools.CLICK, gateway, argsOf("\"index\":1", "\"generation\":4"),
            ) {},
        )
        assertNull("判不了就别判", result["page_changed"])
        assertEquals("取证失败不许把动作本身报成失败", "ok", result["status"]!!.jsonPrimitive.content)
    }

    /** 等满上限还在加载 ⇒ 老实带 `still_loading`，别让模型把半张页读成完整页。 */
    @Test
    fun aPageThatNeverSettlesIsReportedAsStillLoading() = runBlocking {
        val gateway = FakeGateway(runResult = findPayloadOf("""{"url":"https://x","title":"t","elements":[]}"""))
        gateway.loading = true
        val result = obj(
            BrowserTools.execute(BrowserTools.OPEN, gateway, argsOf("\"url\":\"https://x\"")) {},
        )
        assertEquals("true", result["still_loading"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------ 标签（spec §12.2）

    /**
     * `new_tab=true` 走的是 [BrowserGateway.openTab]，**不是** `navigate`：后者在当前活动标签里
     * 换页，前者建新标签并把它设为活动。两条路走错一条，用户看到的「原页面」就被顶掉了 ——
     * 而模型以为它开了个新标签。
     */
    @Test
    fun openInANewTabGoesThroughTheTabPathNotTheCurrentPage() = runBlocking {
        val gateway = FakeGateway()
        val result = obj(
            BrowserTools.execute(
                BrowserTools.OPEN, gateway,
                argsOf("\"url\":\"https://b.example\"", "\"new_tab\":true"),
            ) {},
        )
        assertNull("活动标签里换页那条路一步都不许走", gateway.navigatedTo)
        assertEquals(listOf("https://b.example"), gateway.openedTabs)
        assertEquals("2", result["tabs"]!!.jsonPrimitive.content)
        assertTrue("信封照样带 generation（新标签是新号）", result["generation"] != null)
    }

    @Test
    fun plainOpenStillReplacesTheCurrentPageWithoutTouchingTabs() = runBlocking {
        val gateway = FakeGateway()
        BrowserTools.execute(BrowserTools.OPEN, gateway, argsOf("\"url\":\"https://b.example\"")) {}
        assertEquals("https://b.example", gateway.navigatedTo)
        assertTrue("没要求新标签就不许多造一枚", gateway.openedTabs.isEmpty())
    }

    @Test
    fun tabLimitRefusesWithoutTouchingAnyPage() = runBlocking {
        val gateway = FakeGateway()
        gateway.tabCap = 1
        val result = obj(
            BrowserTools.execute(
                BrowserTools.OPEN, gateway,
                argsOf("\"url\":\"https://b.example\"", "\"new_tab\":\"true\""),
            ) {},
        )
        assertEquals("TAB_LIMIT", result["error"]!!.jsonPrimitive.content)
        assertNull("被拒的动作不许半执行：既没导航，也没新标签", gateway.navigatedTo)
        assertTrue("必须告诉下一步（换一枚标签还是就地打开）", result["instruction"] != null)
    }

    @Test
    fun tabsListReportsEveryTabAndMarksTheActiveOne() = runBlocking {
        val gateway = FakeGateway(
            initialTabs = listOf(
                BrowserTabInfo(0, "第一页", "https://a", active = true),
                BrowserTabInfo(1, "第二页", "https://b", active = false),
            ),
        )
        val result = obj(
            BrowserTools.execute(BrowserTools.TABS, gateway, argsOf("\"action\":\"list\"")) {},
        )
        val block = result["tabs"]!!.jsonPrimitive.content
        assertTrue("两枚都要列出来：$block", block.contains("第一页") && block.contains("第二页"))
        assertTrue("活动的那一枚要标出来：$block", block.contains("►"))
        assertEquals("0", result["active"]!!.jsonPrimitive.content)
    }

    /**
     * 切标签**必然**换代次：模型拿着 A 标签的第 5 号去点 B 标签，是这套 index 契约唯一
     * 还没防住的别名（各标签自己 `++` 时两枚迟早同号）。真身的解法是会话级发号器
     * （`BrowserSession.nextEpoch`，证据在 `BrowserTabsTest`），替身这边必须同样表现，
     * 否则这条回归在工具面是隐形的。
     */
    @Test
    fun switchingTabsIssuesAFreshGeneration() = runBlocking {
        val gateway = FakeGateway(
            initialTabs = listOf(
                BrowserTabInfo(0, "第一页", "https://a", active = true),
                BrowserTabInfo(1, "第二页", "https://b", active = false),
            ),
        )
        val before = gateway.generation
        val switched = obj(
            BrowserTools.execute(BrowserTools.TABS, gateway, argsOf("\"action\":\"select\"", "\"index\":1")) {},
        )
        assertEquals("1", switched["active"]!!.jsonPrimitive.content)
        assertTrue(
            "切完的代次必须是新号（切之前是 $before）",
            switched["generation"]!!.jsonPrimitive.content != "$before",
        )
        assertNull("活动标签换人了 ⇒ 交给模型的清单必须作废", gateway.snapshot)
    }

    @Test
    fun closingTheIdleTabKeepsTheGenerationAndOutOfRangeChangesNothing() = runBlocking {
        val gateway = FakeGateway(
            initialTabs = listOf(
                BrowserTabInfo(0, "第一页", "https://a", active = false),
                BrowserTabInfo(1, "第二页", "https://b", active = true),
            ),
        )
        val epoch = gateway.generation
        val closed = obj(
            BrowserTools.execute(BrowserTools.TABS, gateway, argsOf("\"action\":\"close\"", "\"index\":0")) {},
        )
        assertEquals("关的是没在看的那一枚 ⇒ 页面没换 ⇒ 代次不动", "$epoch", closed["generation"]!!.jsonPrimitive.content)
        assertEquals(listOf(0), gateway.closedTabs)

        val outOfRange = obj(
            BrowserTools.execute(BrowserTools.TABS, gateway, argsOf("\"action\":\"close\"", "\"index\":7")) {},
        )
        assertEquals("TAB_INDEX_INVALID", outOfRange["error"]!!.jsonPrimitive.content)
        assertEquals("越界那一次不许真的关掉任何东西", listOf(0), gateway.closedTabs)
        assertTrue(outOfRange["instruction"] != null)
    }

    @Test
    fun tabActionsAreTheThreeDeclaredOnesOnly() = runBlocking {
        val gateway = FakeGateway()
        val unknown = obj(
            BrowserTools.execute(BrowserTools.TABS, gateway, argsOf("\"action\":\"merge\"", "\"index\":0")) {},
        )
        assertEquals("invalid_arguments", unknown["error"]!!.jsonPrimitive.content)
        assertTrue("越界动作不落任何状态", gateway.closedTabs.isEmpty() && gateway.openedTabs.isEmpty())

        val noIndex = obj(BrowserTools.execute(BrowserTools.TABS, gateway, argsOf("\"action\":\"select\"")) {})
        assertEquals("invalid_arguments", noIndex["error"]!!.jsonPrimitive.content)
        assertEquals(
            "缺 index 也必须点名是哪个参数",
            "index",
            ((noIndex["violations"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("param"))
                ?.jsonPrimitive?.content,
        )
    }

    // ------------------------------------------------------------------ 截图

    @Test
    fun screenshotAttachesBytesOnlyUnderTheCap() = runBlocking {
        val images = mutableListOf<ToolImageBytes>()
        val result = obj(BrowserTools.execute(BrowserTools.SCREENSHOT, FakeGateway(), argsOf()) { images.add(it) })
        assertEquals("tool_result", result["type"]!!.jsonPrimitive.content)
        assertEquals(1, images.size)
        assertTrue(images[0].name.endsWith(".png"))
    }

    /**
     * spec §5：图超限不发给模型，但**必须点名下一步**（「页面过大，请用 read 分段取正文」）。
     * 只回 `status:"ok"` + `omitted` 等于告诉模型「一切正常，只是没图」，它就只会再敲一次。
     */
    @Test
    fun oversizedScreenshotPointsAtReadInsteadOfJustShrugging() = runBlocking {
        val images = mutableListOf<ToolImageBytes>()
        val result = obj(
            BrowserTools.execute(
                BrowserTools.SCREENSHOT,
                FakeGateway(shotBytes = BrowserSession.MAX_PNG_BYTES + 1),
                argsOf(),
            ) { images.add(it) },
        )
        assertEquals("tool_result", result["type"]!!.jsonPrimitive.content)
        assertTrue("超限的图不许发给模型", images.isEmpty())
        assertNotNull("要说明为什么没图", result["omitted"])
        val instruction = result["instruction"]!!.jsonPrimitive.content
        assertTrue("下一步必须是另一条读法：$instruction", instruction.contains("browser_read"))
    }

    // ------------------------------------------------------------------ 声明 ↔ 实现

    /**
     * `ALL_TOOL_NAMES` 是声明侧的真值来源，而 `everyNameHasItsOwnSchemaAndTheBoundariesSitOn…`
     * 只钉得住「名字 ↔ schema」—— 钉不住「加了第 14 颗、忘了写执行分支」。v1 的末支是
     * `else -> reload`，那种遗忘会被**当成页面重载执行**（错执行，不是拒执行）。
     * 现在末支是 `NOT_IMPLEMENTED`，这条测试就是它真红的地方。
     *
     * 参数给满 + 回包取真实形状（`options: null` 是 `findScript` 对每个非 select 元素的回法）：
     * 这条循环因此同时是「解析端被页面的 null 炸掉」的守卫 —— 它第一次红就是这样。
     */
    @Test
    fun everyDeclaredToolIsImplemented() = runBlocking {
        val offenders = BrowserTools.ALL_TOOL_NAMES.filter { name ->
            val result = BrowserTools.execute(
                name,
                FakeGateway(elements = elementSnapshot(), runResult = true to anyFindPayload),
                argsOf(
                    "\"url\":\"https://example.com\"",
                    "\"index\":\"1\"",
                    "\"generation\":\"4\"",
                    "\"text\":\"关键词\"",
                    "\"value\":\"上海\"",
                    "\"x\":\"10\"",
                    "\"y\":\"20\"",
                    "\"direction\":\"down\"",
                    "\"amount\":\"100\"",
                    "\"offset\":\"0\"",
                    "\"max_chars\":\"100\"",
                    "\"query\":\"下\"",
                    "\"timeout_ms\":\"300\"",
                ),
            ) {}
            "NOT_IMPLEMENTED" in result
        }
        assertTrue("这些工具名在 ALL_TOOL_NAMES 里却没有自己的分支，会被末支吞掉：$offenders", offenders.isEmpty())

        // reload 与 open 各走各的门：末支写死之后仍要证明没被混成一次 loadUrl。
        val reloaded = FakeGateway()
        BrowserTools.execute(BrowserTools.RELOAD, reloaded, argsOf()) {}
        assertEquals("reload 要真的走 reload()", 1, reloaded.reloads)
        assertEquals("reload 不许被读成 navigate", null, reloaded.navigatedTo)
    }

    /** 这族里**不许再有** `browser_use`（spec §12.1「不留兼容」；旧会话按未知工具名回落默认样式）。 */
    @Test
    fun theV1SingleToolNameIsGone() {
        assertTrue(
            "browser_use 不许留在门控集合里",
            "browser_use" !in BrowserTools.ALL_TOOL_NAMES,
        )
        assertNull(
            "definitions() 里也不许有它",
            BrowserTools.definitions().firstOrNull { it.name == "browser_use" },
        )
    }
}

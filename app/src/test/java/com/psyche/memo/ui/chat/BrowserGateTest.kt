package com.psyche.memo.ui.chat

import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.MainDispatcherRule
import com.psyche.memo.provider.browser.BrowserTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 全局开关在**执行侧**的那道复查（裁决 2），对浏览器那一族 13 颗工具整体生效。
 *
 * `ChatViewModel.offeredTools()` 只负责「不再递这族工具」；模型照旧发它们是最现实的一条路径
 * （被污染的网页正文指挥它调 `browser_open` —— spec §4/§9 记的正是这个残余风险）。光靠不递，
 * 用户明确关掉开关之后这族工具**照样能拿到一个真 WebView、用户的 cookie jar 和出网能力**。
 *
 * 拦的地方必须在 `sessionFor` **之前** —— 那一句才是建实例的地方，所以这里断言的是
 * 「回了拒绝 + `peek()` 仍然是 null」，不只是回了个错误码。
 *
 * 与同族的搜索那颗工具一个口径（`ToolHandler.handle` 的搜索分支复查
 * `assistant?.searchEnabled == true`）。这**不是恢复审批**：一句拒绝，不弹确认、不挂起等人
 * （用户 2026-09-25 明令拆掉审批，PORTING §5.68）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowserGateTest {

    /**
     * 为什么测试体能直接 `runBlocking`（上一轮这里是「后台线程 + `Thread.join(20s)`」）：
     * `MainDispatcherRule` 把 `Dispatchers.Main` 换成 unconfined 测试调度器后，
     * `sessionFor` 的 `withContext(Dispatchers.Main)` 在测试线程上**原地跑完** —— 真造出
     * shadow WebView 实例这条路同包 `BrowserSessionStoreTest` 一直就是这么走的，从未挂死。
     *
     * 上一轮那套「不丢后台线程就必然挂死（`withTimeout` 也救不了）」的论证只在**不装这条
     * 规则**时成立；挂 11 分钟是写法自己选出来的，不是 `sessionFor` 逼出来的。这也正面撞上
     * AGENTS / PORTING §5.40 的硬纪律：Robolectric 测试不许拿真时钟轮询/有界等待替代断言
     * （GitHub 2 核 runner 上会偶发挂到超时、本地多核永远复现不了）。**红要红在断言上，
     * 不许红在计时器上** —— 装好规则后两条路径都是毫秒级断言：门控在 ⇒ 绿；哪天有人删掉
     * 门控 ⇒ `sessionFor` 原地建出实例（`peek` 非空 —— 本测试这条 `assertNull` 与下一条
     * `assertNotNull` 观测的正是同一件事，互为镜像），`execute` 毫秒级回 `invalid_arguments`，
     * 先红的是 `assertEquals(browser_disabled)`（它排在 `assertNull(peek)` 之前，方法当场
     * 失败；两条断言都在这条回归的判据路径上）。探针因此用少了 url 的 `browser_open` 而不是
     * `browser_read`（与下一条测试同源）：换之前删门控的红实测要 **30.2 s** 才轮到断言 —— `read`
     * 的脚本递进 shadow 后永不回 callback（那颗 8 秒脚本超时落在测试调度器的虚拟时钟上
     * 没人拨），要等 `ToolRunner` 外层 30 秒 cap 放行、清场异常被归一成 `tool_crashed`。
     * 换探针后那 30 秒彻底消失（scratch 删门控实测红 0.314 s，见 task-6-report 的
     * Fix round 3）。
     */
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private lateinit var container: AppContainerImpl

    @Before
    fun setUp() {
        container = AppContainerImpl(ApplicationProvider.getApplicationContext())
    }

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun handler(conversationId: String) = ToolHandler(
        askUserService = null,
        conversationId = conversationId,
        assistant = null,
        container = container,
    )

    @Test
    fun offSwitchRefusesWithoutOpeningABrowserSession() = runBlocking {
        container.preferenceRepository.writeJson(BrowserTools.PREFERENCE_KEY, "0")
        // 探针 = `browser_open` 少 url：绿路径上它根本走不到执行侧（门控先回拒绝，断言一字不变）；
        // 红路径（哪天门控被删）它在任何脚本递进页面之前就回 invalid_arguments —— 毫秒级，
        // 不像 `browser_read` 那样在 shadow 里干等 `ToolRunner` 的 30 秒 cap（见类注释）。
        val content = handler("conv-off").handle(BrowserTools.OPEN, obj("{}"), "call-1")
        val result = obj(content)
        assertEquals("tool_error", result["type"]!!.jsonPrimitive.content)
        assertEquals("browser_disabled", result["error"]!!.jsonPrimitive.content)
        assertTrue("每条错误都要有下一步", result["instruction"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(
            "这是一句拒绝，不是审批（PORTING §5.68）：结果串里不许出现任何等用户的键",
            "pending" !in content,
        )
        assertNull(
            "关掉开关不许建出浏览器实例（那是一个真 WebView + 用户的 cookie jar）",
            container.browserSessions.peek("conv-off"),
        )
    }

    /**
     * 产品事实：**偏好行不存在（全新安装、用户从没碰过开关）时，`handle` 不许拒绝这族工具**。
     *
     * 旧形状 `missingPreferenceDefaultsToOn` 是 `assertEquals(true, readBool(container, KEY,
     * default = true))` —— `default` 是测试自己传进去的，行缺失时 readBool 原样返回它，
     * 于是任何实现都过：把 ToolHandler 派发点里的 `default = true` 翻成 `false`，旧测试
     * 照样绿。恒真断言当不了覆盖（第二轮审查点名的那条）。现在从派发点进，
     * `default` 不再出现在测试里，它是被测对象的一部分（scratch 翻成 false 实测会红，
     * 见 task-6-report 的 Fix round 2）。
     *
     * 探针 = `browser_open` 少 url：它照样过门控、过 `sessionFor`（「放行」这件事由 `peek`
     * 非空观测到，与上一条测试的 assertNull 正好镜像），但在任何脚本递进页面之前就以
     * `invalid_arguments` 返回 —— 毫秒级，不等 8 秒脚本超时、不碰网络。
     */
    @Test
    fun missingPreferenceRowDoesNotRefuseTheTool() = runBlocking {
        val content = handler("conv-default").handle(BrowserTools.OPEN, obj("{}"), "call-2")
        val result = obj(content)
        assertEquals(
            "开关缺失 = 默认开：派发点不许回 browser_disabled，要一路放行到执行侧的参数校验",
            "invalid_arguments",
            result["error"]!!.jsonPrimitive.content,
        )
        assertNotNull(
            "门控放行后会话应当真被建出来（默认开的可观测面）",
            container.browserSessions.peek("conv-default"),
        )
    }
}

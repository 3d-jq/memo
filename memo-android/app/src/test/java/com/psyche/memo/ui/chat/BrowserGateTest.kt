package com.psyche.memo.ui.chat

import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.provider.browser.BrowserTool
import com.psyche.memo.ui.DisplayPrefs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

/**
 * 全局开关在**执行侧**的那道复查（裁决 2）。
 *
 * `ChatViewModel.offeredTools()` 只负责「不再递这颗工具」；模型照旧发它是最现实的一条路径
 * （被污染的网页正文指挥它调 `browser_use` —— spec §4/§9 记的正是这个残余风险）。光靠不递，
 * 用户明确关掉开关之后那颗工具**照样能拿到一个真 WebView、用户的 cookie jar 和出网能力**。
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

    /**
     * 为什么把 `handle` 丢到另一根线程上、用 `Thread.join(超时)` 等：Robolectric 的测试体就
     * 跑在「主线程」上，而 `BrowserSessionStore.sessionFor` 要 `withContext(Dispatchers.Main)`
     * ——门控一旦丢，那条消息会永远排在被测试体占着的主 Looper 上，**整个测试任务挂死**
     * （`withTimeout` 也救不了：取消要经同一个调度器交付）。丢到后台线程 + 有界 join，
     * 就把「以后有人把这道复查删了」变成一次 20 秒后的红，而不是一根挂到 CI 超时的进程。
     */
    @Test
    fun offSwitchRefusesWithoutOpeningABrowserSession() {
        container.preferenceRepository.writeJson(BrowserTool.PREFERENCE_KEY, "0")
        val content = AtomicReference<String>()
        val worker = Thread {
            content.set(
                runBlocking {
                    handler("conv-off").handle(BrowserTool.TOOL_NAME, obj("""{"action":"read"}"""), "call-1")
                },
            )
        }.apply {
            isDaemon = true
            name = "browser-gate-probe"
            start()
        }
        worker.join(20_000)
        assertFalse(
            "browser_use 分支没有在执行侧复查全局开关：调用卡在 sessionFor 的主线程调度上" +
                "（这正是「用户关了开关却照样建出 WebView」那个洞）",
            worker.isAlive,
        )
        val result = obj(content.get())
        assertEquals("tool_error", result["type"]!!.jsonPrimitive.content)
        assertEquals("browser_disabled", result["error"]!!.jsonPrimitive.content)
        assertTrue("每条错误都要有下一步", result["instruction"]!!.jsonPrimitive.content.isNotBlank())
        assertNull(
            "关掉开关不许建出浏览器实例（那是一个真 WebView + 用户的 cookie jar）",
            container.browserSessions.peek("conv-off"),
        )
    }

    /** 默认值必须是「开」：偏好行不存在（全新安装）时不许把浏览器锁死。 */
    @Test
    fun missingPreferenceDefaultsToOn() {
        assertEquals(
            true,
            DisplayPrefs.readBool(container, BrowserTool.PREFERENCE_KEY, default = true),
        )
    }
}

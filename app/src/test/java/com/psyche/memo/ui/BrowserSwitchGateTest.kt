package com.psyche.memo.ui

import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.MainDispatcherRule
import com.psyche.memo.provider.browser.BrowserTools
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「设置 → 模型与服务 → 浏览器功能」那颗开关的默认开：**`agent_browser_enabled_v1` 没写过键就是开**
 * （用户 2026-09-25「改成默认开着的」）。
 *
 * 钉的是新入口自身，不是 `decodeBool` —— 旧形状四条断言全打在纯函数上、`default` 由测试
 * 自己传参，与 `DisplayPrefsTest` 逐字重复且恒真：把生产侧 `DisplayPrefs.kt` 的
 * `default = true` 翻成 `false`（正是这条产品事实被改坏）它照样绿。现在从
 * `DisplayPrefs.browserEnabled` 进，`default` 不在测试里出现，它是被测对象的一部分
 * （scratch 翻成 false 后 `missingKeyMeansOn` 实测会红，见 task-7-report 的 Fix round 1）。
 *
 * 第二条从键名 `BrowserTools.PREFERENCE_KEY` 直接写、从入口读 —— 钉「读写落在同一个键」：
 * 生产写入已收口进 `DisplayPrefs.writeBrowserEnabled`，哪天读侧换键名，这条立刻红。
 * 写值用 `"0"`（裸串）：与 `DisplayPrefs.writeBool` 的落库字节逐字一致（同
 * `BrowserGateTest` 的写法），不是 JSON 引号形态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowserSwitchGateTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private lateinit var container: AppContainerImpl

    @Before
    fun setUp() {
        container = AppContainerImpl(ApplicationProvider.getApplicationContext())
    }

    /** 偏好表为空（全新安装、用户从没碰过开关）⇒ 开。 */
    @Test
    fun missingKeyMeansOn() {
        assertTrue(DisplayPrefs.browserEnabled(container))
    }

    /** 键上落了生产写入形态的 `"0"` ⇒ 入口必须认成关。 */
    @Test
    fun writtenOffReadsBackOff() {
        container.preferenceRepository.writeJson(BrowserTools.PREFERENCE_KEY, "0")
        assertFalse(DisplayPrefs.browserEnabled(container))
    }
}

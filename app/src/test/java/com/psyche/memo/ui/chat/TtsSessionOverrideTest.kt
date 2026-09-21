package com.psyche.memo.ui.chat

import com.psyche.memo.ui.OpenAiTtsOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「听测试」用哪个引擎（用户 2026-09-16「语音点击听测试那个没有我们那个胶囊呀」+「语音
 * 这个部分功能做完了吗」）。
 *
 * 原版 `tts_provider.dart` 有两个入口：`speakSystem(text)`（networkService = null，
 * 强制系统引擎）与 `speakWithNetworkService(service, text)`（用指定服务，不看当前选中
 * 项）。我们把「本次会话用哪个服务」的判定抽成纯函数 [resolveSessionService]，这里锁住
 * 三个分支的优先级。
 */
class TtsSessionOverrideTest {

    private fun service(id: String, enabled: Boolean = true) = OpenAiTtsOptions(
        id = id,
        enabled = enabled,
        name = id,
        apiKey = "k",
        baseUrl = "https://example.invalid",
        model = "m",
        voice = "v",
    )

    @Test
    fun `without an override the selected service wins`() {
        val a = service("a")
        val b = service("b")
        assertEquals(a, resolveSessionService(null, "a", listOf(a, b)))
        assertNull(resolveSessionService(null, null, listOf(a, b)))
        assertNull(resolveSessionService(null, "missing", listOf(a, b)))
    }

    @Test
    fun `a disabled selected service falls back to the system engine`() {
        val off = service("a", enabled = false)
        assertNull(resolveSessionService(null, "a", listOf(off)))
    }

    @Test
    fun `forcing the system engine ignores the selection`() {
        // 系统行的「听测试」：即使选中了网络服务也必须走系统引擎。
        val selected = service("a")
        assertNull(
            resolveSessionService(
                TtsSessionOverride.System,
                selectedServiceId = "a",
                services = listOf(selected),
            ),
        )
    }

    @Test
    fun `an explicit service wins over the selection`() {
        // 服务行的「听测试」：用**这一行**的服务试播，哪怕当前选中的是另一个。
        val selected = service("selected")
        val mine = service("mine")
        assertEquals(
            mine,
            resolveSessionService(
                TtsSessionOverride.Service(mine),
                selectedServiceId = "selected",
                services = listOf(selected, mine),
            ),
        )
    }

    @Test
    fun `the displayed speed reaches Android's speech rate unchanged`() {
        // 用户 2026-09-16「在对话界面点击 速度这么慢呀」：内部轴照 flutter_tts 存
        // 「显示倍速 / 2」，而 Android 的 setSpeechRate 是 1.0=正常 —— 之前把 0.5 原样
        // 传下去，结果所有系统语音都是**半速**。这里锁住还原。
        assertEquals(1.0f, TtsPlaybackSpeed.toAndroidSpeechRate(0.5), 0.0001f)
        assertEquals(2.0f, TtsPlaybackSpeed.toAndroidSpeechRate(1.0), 0.0001f)
        assertEquals(0.8f, TtsPlaybackSpeed.toAndroidSpeechRate(0.4), 0.0001f)
        // 与网络引擎那条路一致（MediaPlayer 倍速）。
        assertEquals(
            TtsPlaybackSpeed.toAndroidSpeechRate(0.6),
            mediaPlayerSpeed(0.6f),
            0.0001f,
        )
    }

    @Test
    fun `an explicit service is used even when it is not in the store list`() {
        // 服务刚新建还没落到 store 列表里时，点了测试也要能播。
        val fresh = service("fresh")
        assertEquals(
            fresh,
            resolveSessionService(
                TtsSessionOverride.Service(fresh),
                selectedServiceId = null,
                services = emptyList(),
            ),
        )
    }
}

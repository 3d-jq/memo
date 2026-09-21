package com.psyche.memo.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 系统语音偏好的纯逻辑（`tts_provider.dart` 的 `_init` / `_applyConfig` /
 * `_selectEngine` / `_localeToTag` 四段）。
 *
 * 这四个键写了很久没人读：语速、音调、引擎、语言全被 `speak()` 里那句
 * `tts.language = Locale.getDefault()` 盖掉，所以判定规则在这里钉死。
 */
class SystemTtsConfigTest {

    // ---- 偏好 JSON → 配置（_init L122-131）----------------------------------

    @Test
    fun `missing preferences fall back to the upstream defaults`() {
        val config = parseSystemTtsConfig(null, null, null, null)
        assertEquals(0.5, config.speechRate, 0.0001)
        assertEquals(1.0, config.pitch, 0.0001)
        assertNull(config.engineId)
        assertNull(config.languageTag)
    }

    @Test
    fun `values are clamped to the upstream ranges`() {
        val config = parseSystemTtsConfig("2.5", "0.1", null, null)
        assertEquals(1.0, config.speechRate, 0.0001)
        assertEquals(0.5, config.pitch, 0.0001)

        val low = parseSystemTtsConfig("0.0", "3.0", null, null)
        assertEquals(0.1, low.speechRate, 0.0001)
        assertEquals(2.0, low.pitch, 0.0001)
    }

    @Test
    fun `quoted payloads and empty strings read like the Dart provider does`() {
        val config = parseSystemTtsConfig("\"0.6\"", "\"1.4\"", "\"com.google.android.tts\"", "\"\"")
        assertEquals(0.6, config.speechRate, 0.0001)
        assertEquals(1.4, config.pitch, 0.0001)
        assertEquals("com.google.android.tts", config.engineId)
        // 空串 = 没设（`_languageTag` 为 null，走设备语言）。
        assertNull(config.languageTag)
    }

    @Test
    fun `the flutter_tts axis maps to the displayed speed by doubling`() {
        assertEquals(1.0, SystemTtsConfig(speechRate = 0.5).displayedSpeed, 0.0001)
        assertEquals(2.0, SystemTtsConfig(speechRate = 1.0).displayedSpeed, 0.0001)
        // 0.1×2 = 0.2 落在档位表下沿之外 → 夹到 0.8。
        assertEquals(0.8, SystemTtsConfig(speechRate = 0.1).displayedSpeed, 0.0001)
    }

    // ---- 语言（_localeToTag + _applyConfig L251-264）------------------------

    @Test
    fun `a device locale becomes a language tag`() {
        assertEquals("zh-CN", localeToTag("zh", "CN"))
        assertEquals("en", localeToTag("en", null))
        assertEquals("en", localeToTag("en", ""))
    }

    @Test
    fun `the preferred tag wins when the engine can speak it`() {
        val config = SystemTtsConfig(languageTag = "fr-FR")
        val tag = resolveSystemLanguage(config, deviceTag = "zh-CN", deviceLanguage = "zh") { it == "fr-FR" }
        assertEquals("fr-FR", tag)
    }

    @Test
    fun `an unavailable preference tag skips the device language`() {
        // 上游 `_applyConfig` L257-264：偏好标签说不通就直接跳 zh-CN/en-US 兜底，
        // 设备语言只在**没有偏好**时参与。
        val tag = resolveSystemLanguage(SystemTtsConfig(languageTag = "fr-FR"), "ja-JP", "ja") {
            it == "ja-JP" || it == "en-US"
        }
        assertEquals("en-US", tag)
    }

    @Test
    fun `without a preference the device language is used when available`() {
        val tag = resolveSystemLanguage(SystemTtsConfig(), "ja-JP", "ja") { it == "ja-JP" }
        assertEquals("ja-JP", tag)
    }

    @Test
    fun `both unavailable falls back to zh-CN for Chinese and en-US otherwise`() {
        // 只有回落标签可用：设备语言与偏好都说不通时，中文设备走 zh-CN，其余走 en-US。
        assertEquals(
            "zh-CN",
            resolveSystemLanguage(SystemTtsConfig(), "th-TH", "zh") { it == "zh-CN" },
        )
        assertEquals(
            "en-US",
            resolveSystemLanguage(SystemTtsConfig(), "th-TH", "th") { it == "en-US" },
        )
    }

    @Test
    fun `nothing is set when even the fallback is unavailable`() {
        assertNull(resolveSystemLanguage(SystemTtsConfig(), "th-TH", "th", available = { false }))
    }

    // ---- 引擎（_applyConfig L246-250 + _selectEngine L315-333）-------------

    @Test
    fun `a chosen engine wins over the automatic pick`() {
        val engines = listOf("com.samsung.voiceservice", "com.google.android.tts")
        assertEquals("com.samsung.voiceservice", preferredSystemEngine(engines, "com.samsung.voiceservice"))
    }

    @Test
    fun `without a choice google is preferred, then the first engine`() {
        assertEquals(
            "com.google.android.tts",
            preferredSystemEngine(listOf("com.samsung.voiceservice", "com.google.android.tts"), null),
        )
        assertEquals(
            "com.sonymobile.cta",
            preferredSystemEngine(listOf("com.sonymobile.cta", "com.samsung.voiceservice"), null),
        )
        assertNull(preferredSystemEngine(emptyList(), null))
    }

    @Test
    fun `an empty stored engine id counts as no choice`() {
        assertEquals("com.google.android.tts", preferredSystemEngine(listOf("com.google.android.tts"), ""))
    }
}

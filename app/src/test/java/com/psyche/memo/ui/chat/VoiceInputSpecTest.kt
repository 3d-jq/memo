package com.psyche.memo.ui.chat

import com.psyche.memo.ui.ChatStyleSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * 语音输入 + 翻译接线规格测试。
 *
 * 波形/电平公式对应 chat_input_bar.dart `_VoiceWaveformPainter`(3400-3459)
 * 与 `onRmsChanged` 归一化；翻译常量对应 translation_service.dart:110。
 */
class VoiceInputSpecTest {

    // ---- mic level normalization (VoiceInputController.onRmsChanged) ----

    @Test
    fun micLevelNormalization() {
        // Android rmsdB 典型区间 [-2, 10] 线性归一到 [0, 1]。
        assertEquals(0f, VoiceInputController.normalizeLevel(-2f), 1e-6f)
        assertEquals(1f, VoiceInputController.normalizeLevel(10f), 1e-6f)
        // 区间外钳制。
        assertEquals(0f, VoiceInputController.normalizeLevel(-8f), 1e-6f)
        assertEquals(1f, VoiceInputController.normalizeLevel(14f), 1e-6f)
        // 中点：(-2 + 10) / 2 = 4 → 0.5。
        assertEquals(0.5f, VoiceInputController.normalizeLevel(4f), 1e-6f)
    }

    // ---- capsule envelope (CMW/CIB _VoiceWaveformPainter:3433-3446) ----

    @Test
    fun waveformEnvelope() {
        val maxH = 29.44f // 32dp * 0.92
        val r = maxH / 2f
        // 条中心距边缘 >= r：无收缩。
        assertEquals(1f, ChatStyleSpec.voiceWaveformEnvelope(r, maxH), 1e-6f)
        assertEquals(1f, ChatStyleSpec.voiceWaveformEnvelope(maxH, maxH), 1e-6f)
        // d=0：位于端帽极点 → 0。
        assertEquals(0f, ChatStyleSpec.voiceWaveformEnvelope(0f, maxH), 1e-6f)
        // d=r/2：sqrt(1 − (1/2)²) = √3/2。
        assertEquals(sqrt(3f) / 2f, ChatStyleSpec.voiceWaveformEnvelope(r / 2f, maxH), 1e-5f)
    }

    @Test
    fun waveformBarHeight() {
        val maxH = 29.44f
        val minH = 2f
        // 静音样本仍保留最小高度。
        assertEquals(minH, ChatStyleSpec.voiceWaveformBarHeight(0f, maxH, maxH, minH), 1e-6f)
        // 满电平且不在端帽 → maxH。
        assertEquals(maxH, ChatStyleSpec.voiceWaveformBarHeight(1f, maxH, maxH, minH), 1e-6f)
        // 满电平在端帽极点（d=0）→ 被最小高度托底。
        assertEquals(minH, ChatStyleSpec.voiceWaveformBarHeight(1f, 0f, maxH, minH), 1e-6f)
        // 溢出电平钳制到 1。
        assertEquals(maxH, ChatStyleSpec.voiceWaveformBarHeight(9f, maxH, maxH, minH), 1e-6f)
    }

    // ---- waveform geometry constants (CIB:3407-3408/3417) ----

    @Test
    fun waveformGeometrySpec() {
        assertEquals(3f, ChatStyleSpec.WAVE_BAR_WIDTH_DP, 0f)
        assertEquals(3.5f, ChatStyleSpec.WAVE_BAR_GAP_DP, 0f)
        assertEquals(0.92f, ChatStyleSpec.WAVE_MAX_HEIGHT_RATIO, 0f)
        assertEquals(2f, ChatStyleSpec.WAVE_MIN_BAR_HEIGHT_DP, 0f)
        assertEquals(28, VoiceInputController.WAVE_BAR_COUNT)
    }

    // ---- translation language sheet (language_select_sheet.dart:28-82) ----

    @Test
    fun translationLanguageListMatchesSource() {
        // 9 种语言 + 顺序与 Flutter 原版一致。
        val expected = listOf("zh-CN", "en", "zh-TW", "ja", "ko", "fr", "de", "it", "es")
        assertEquals(expected, supportedLanguages.map { it.code })
    }

    @Test
    fun clearTranslationMarkerMatchesSource() {
        // translation_service.dart:110 —— `__clear__` 清除翻译。
        assertEquals("__clear__", TranslateLanguage.CLEAR_TRANSLATION)
        assertTrue(supportedLanguages.none { it.code == TranslateLanguage.CLEAR_TRANSLATION })
    }
}

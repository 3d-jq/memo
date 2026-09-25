package com.psyche.memo.ui.chat

import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.ui.MimoAsrOptions
import com.psyche.memo.ui.SystemAsrOptions
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 云端 ASR 接线的可测部分：采集电平归一化（波形）、系统/云端分派判定。
 *
 * 会话与 AudioRecord 本身要真机（或影子实现）才有意义，这里只钉住「决策」与「数学」。
 *
 * 走 Robolectric 只为最后一组麦克风可见性用例要一个 Context（`canUse()` 里
 * 系统识别器那一支查的是真平台 API，其余判定都靠注入的 lambda）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceInputDispatchTest {

    // ---- 分派：选了服务 + 有 client 才走云端 ----

    @Test
    fun cloudIsUsedOnlyWithAServiceAndAClient() {
        val options = MimoAsrOptions(apiKey = "k")
        val client = OkHttpClient()
        assertTrue(shouldUseCloudAsr(options, client))
        // 没选服务 → 系统识别。
        assertFalse(shouldUseCloudAsr(null, client))
        // 没有 OkHttp（降级 / 测试）→ 也回系统识别，而不是直接失败。
        assertFalse(shouldUseCloudAsr(options, null))
        assertFalse(shouldUseCloudAsr(null, null))
        // `system` 那一类没有 HTTP 形状：isConfigured 恒真，只有这里能挡住。
        assertFalse(shouldUseCloudAsr(SystemAsrOptions(), client))
    }

    // ---- 麦克风可见性：上游要求「先选中一个 ASR 服务」----

    @Test
    fun micIsHiddenUntilAnAsrServiceIsSelected() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // 什么都没配：即使本机有识别器也不显示（用户 2026-09-25「我都没有设置呀」）。
        assertFalse(
            VoiceInputController(
                context = context,
                cloudOptions = { null },
                systemRecognizerAvailable = { true },
            ).canUse(),
        )
        // 选的是系统那一类：可用性 = 本机识别器在不在。
        assertTrue(
            VoiceInputController(
                context = context,
                cloudOptions = { SystemAsrOptions() },
                systemRecognizerAvailable = { true },
            ).canUse(),
        )
        assertFalse(
            VoiceInputController(
                context = context,
                cloudOptions = { SystemAsrOptions() },
                systemRecognizerAvailable = { false },
            ).canUse(),
        )
        // 选的是云端且已配置（selectedCloudAsrService 只回 isConfigured 的）：显示。
        assertTrue(
            VoiceInputController(
                context = context,
                cloudOptions = { MimoAsrOptions(apiKey = "k") },
                systemRecognizerAvailable = { false },
            ).canUse(),
        )
    }

    // ---- 波形电平 ----

    @Test
    fun pcmLevelIsZeroForSilenceAndOneForFullScale() {
        assertEquals(0f, AsrRecorder.levelOf(ByteArray(0)), 0.001f)
        assertEquals(0f, AsrRecorder.levelOf(ByteArray(8)), 0.001f)

        // 满量程方波：32767 与 -32768 → RMS ≈ 32767.5 → 归一化 ≈ 1。
        val loud = ByteArray(8)
        for (i in 0 until 4) {
            // 32767 = 0x7FFF（小端 FF 7F）
            loud[i * 2] = 0xFF.toByte()
            loud[i * 2 + 1] = 0x7F
        }
        assertEquals(1f, AsrRecorder.levelOf(loud), 0.01f)

        // 一半振幅 → -6dB → (60-6.02)/60 ≈ 0.90（dB 归一化，不是线性的 0.5）。
        val half = ByteArray(8)
        for (i in 0 until 4) {
            half[i * 2] = 0x00
            half[i * 2 + 1] = 0x40 // 16384
        }
        assertEquals(0.90f, AsrRecorder.levelOf(half), 0.02f)
    }

    @Test
    fun pcmLevelReadsLittleEndianSamples() {
        // 0x0100 小端是 00 01 → 样本 256 → -42dB → (60-42.14)/60 ≈ 0.30（dB 刻度，不是线性的 <0.02）。
        val pcm = byteArrayOf(0x00, 0x01, 0x00, 0x01)
        val level = AsrRecorder.levelOf(pcm)
        assertTrue("expected a small positive level, got $level", level > 0.2f && level < 0.4f)
    }

    // ---- 系统识别的电平换算保持不变 ----

    @Test
    fun systemRmsMappingIsUnchanged() {
        assertEquals(0f, VoiceInputController.normalizeLevel(-2f), 0.001f)
        assertEquals(0.5f, VoiceInputController.normalizeLevel(4f), 0.001f)
        assertEquals(1f, VoiceInputController.normalizeLevel(10f), 0.001f)
        assertEquals(1f, VoiceInputController.normalizeLevel(999f), 0.001f)
    }
}

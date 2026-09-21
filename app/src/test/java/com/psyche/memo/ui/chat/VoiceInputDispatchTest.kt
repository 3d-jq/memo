package com.psyche.memo.ui.chat

import com.psyche.memo.ui.MimoAsrOptions
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云端 ASR 接线的可测部分：采集电平归一化（波形）、系统/云端分派判定。
 *
 * 会话与 AudioRecord 本身要真机（或影子实现）才有意义，这里只钉住「决策」与「数学」。
 */
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

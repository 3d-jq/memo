package com.psyche.memo.ui.chat

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 波形电平的归一化（用户 2026-09-16「这个波形怎么不动呀」）。
 *
 * 上游的 `soundLevel` 来自 `record` 包的**分贝刻度** 0..1（正常说话 ≈0.7），而我们最初
 * 用线性 RMS/32767 —— 正常说话只有 0.02~0.2，条子几乎贴着最小高度，看起来就是「不动」。
 * 现在按 `20·log10(rms/满量程)` 折 dB 再线性抬回 0..1，这里锁住几个典型档位。
 */
class AsrRecorderLevelTest {

    /** 生成指定 RMS 满量程占比的正弦 PCM16。 */
    private fun pcm(samples: Int, amplitudeRatio: Float): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < samples) {
            val v = (amplitudeRatio * Short.MAX_VALUE * Math.sin(2.0 * Math.PI * i / 32.0)).toInt()
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            i++
        }
        return out.toByteArray()
    }

    @Test
    fun `silence stays at zero`() {
        assertEquals(0f, AsrRecorder.levelOf(pcm(320, 0f)), 0.0001f)
    }

    @Test
    fun `normal speech maps into the visible range`() {
        // 线性 RMS ≈ 0.1（正常说话距离）在 dB 刻度上 ≈ -20dB → 归一化 ≈ 0.67，
        // 条子有明显的起伏，而不是贴着最小高度。
        val level = AsrRecorder.levelOf(pcm(3200, 0.1f))
        assertTrue("期望 0.5~0.85 之间，实际 $level", level in 0.5f..0.85f)
    }

    @Test
    fun `loud input saturates near full scale`() {
        val level = AsrRecorder.levelOf(pcm(3200, 0.9f))
        assertTrue("期望 ≥0.9，实际 $level", level >= 0.9f)
    }

    @Test
    fun `monotonic in amplitude`() {
        val quiet = AsrRecorder.levelOf(pcm(3200, 0.02f))
        val loud = AsrRecorder.levelOf(pcm(3200, 0.3f))
        assertTrue(quiet < loud)
    }
}

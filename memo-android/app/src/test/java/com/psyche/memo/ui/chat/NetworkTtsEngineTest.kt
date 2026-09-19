package com.psyche.memo.ui.chat

import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.provider.NetworkTtsResult
import com.psyche.memo.ui.NetworkTtsKind
import com.psyche.memo.ui.OpenAiTtsOptions
import com.psyche.memo.ui.QwenAudioTtsOptions
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 网络 TTS 播放层的可测部分：音频缓存（命中复用 / 超量清理 / 键随文本与服务变化）、
 * 倍速换算、以及「该不该走网络引擎」的判定（qwenAudio 未接 → 退回系统引擎）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkTtsEngineTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val openAi = OpenAiTtsOptions(
        id = "s1",
        enabled = true,
        name = "OpenAI",
        apiKey = "k",
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4o-mini-tts",
        voice = "alloy",
    )

    // ---- 引擎选择 ----

    @Test
    fun networkEngineIsUsedOnlyForSupportedServices() {
        assertFalse(shouldUseNetworkEngine(null))
        assertTrue(shouldUseNetworkEngine(openAi))
        // qwenAudio 的 DashScope WebSocket 已接线（2026-09-19），同样走网络引擎。
        val qwenAudio = QwenAudioTtsOptions(
            "q", true, "QwenAudio", "k", "", "cn-beijing", "m", "v", "pcm", 24000,
        )
        assertTrue(shouldUseNetworkEngine(qwenAudio))
        assertEquals(NetworkTtsKind.qwenAudio, qwenAudio.kind)
    }

    // ---- 倍速换算（引擎 rate = 显示倍速 / 2） ----

    @Test
    fun engineRateMapsBackToTheDisplayedSpeed() {
        TtsPlaybackSpeed.values.forEach { speed ->
            val rate = TtsPlaybackSpeed.toSystemRate(speed).toFloat()
            assertEquals(speed.toFloat(), mediaPlayerSpeed(rate), 0.001f)
        }
        // 越界值夹到 MediaPlayer 的合法区间。
        assertEquals(0.5f, mediaPlayerSpeed(0f), 0.001f)
        assertEquals(3.0f, mediaPlayerSpeed(5f), 0.001f)
    }

    // ---- 音频缓存 ----

    @Test
    fun cacheWritesOnceAndReusesTheFile() {
        val dir = File(context.filesDir, TtsAudioCache.DIR)
        dir.deleteRecursively()
        val cache = TtsAudioCache(context)
        var synthCalls = 0
        fun synth(): NetworkTtsResult {
            synthCalls++
            return NetworkTtsResult(byteArrayOf(1, 2, 3), "audio/mpeg")
        }

        val first = cache.fileFor(openAi, "你好", synth = ::synth)
        val second = cache.fileFor(openAi, "你好", synth = ::synth)

        assertEquals(first.absolutePath, second.absolutePath)
        assertEquals(1, synthCalls) // 第二次命中缓存，不再合成
        assertTrue(first.exists())
        assertEquals("mp3", first.extension)
    }

    /** 「使用缓存复播」关掉时重播要真的重新请求服务（readCache = false）。 */
    @Test
    fun cacheBypassOnReplayWithoutTheFlagResynthesizes() {
        val dir = File(context.filesDir, TtsAudioCache.DIR)
        dir.deleteRecursively()
        val cache = TtsAudioCache(context)
        var synthCalls = 0
        fun synth(): NetworkTtsResult {
            synthCalls++
            return NetworkTtsResult(byteArrayOf(1, 2, 3), "audio/mpeg")
        }
        cache.fileFor(openAi, "再来一次", synth = ::synth)
        assertEquals(1, synthCalls)

        cache.fileFor(openAi, "再来一次", readCache = false, synth = ::synth)
        assertEquals(2, synthCalls)
    }

    @Test
    fun cacheKeyDependsOnTextAndServiceOptions() {
        val cache = TtsAudioCache(context)
        val a = cache.keyFor(openAi, "hello")
        val b = cache.keyFor(openAi, "hello!")
        val c = cache.keyFor(openAi.copy(voice = "verse"), "hello")
        assertEquals(a, cache.keyFor(openAi, "hello"))
        assertFalse(a == b)
        assertFalse(a == c)
    }

    @Test
    fun cachePrunesOldFilesBeyondTheCap() {
        val dir = File(context.filesDir, TtsAudioCache.DIR)
        dir.deleteRecursively()
        val cache = TtsAudioCache(context, maxFiles = 2)
        val texts = listOf("a", "b", "c", "d")
        for (text in texts) {
            cache.fileFor(openAi, text) { NetworkTtsResult(byteArrayOf(1), "audio/mpeg") }
            // 时间戳拉开，prune 按 lastModified 排序才有确定结果。
            Thread.sleep(10)
        }
        val remaining = dir.listFiles()?.size ?: 0
        assertTrue("expected at most 2 files, found $remaining", remaining <= 2)
    }
}

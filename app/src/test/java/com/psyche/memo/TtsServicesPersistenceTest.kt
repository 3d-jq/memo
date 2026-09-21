package com.psyche.memo

import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.ui.TtsServiceOptions
import com.psyche.memo.ui.TtsServicesStore
import com.psyche.memo.ui.chat.SystemTtsConfig
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The TTS service list is the `tts_services_v1` entity, so it must round-trip
 * through `tts_service_rows` — storing it via PreferenceRepository dropped
 * every write.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TtsServicesPersistenceTest {

    private lateinit var container: AppContainerImpl

    @Before
    fun setUp() {
        container = AppContainerImpl(ApplicationProvider.getApplicationContext())
        container.database.writableDatabase.execSQL("DELETE FROM tts_service_rows")
    }

    private fun store() = TtsServicesStore(container.database.writableDatabase, container.preferenceRepository)

    @Test
    fun `configured services survive a store restart`() {
        val service = requireNotNull(
            TtsServiceOptions.fromJson(
                """{"id":"tts-1","enabled":true,"name":"My OpenAI voice","kind":"openai",""" +
                    """"apiKey":"sk-test","baseUrl":"https://api.openai.com/v1","model":"tts-1"}""",
            ),
        )
        val first = store()
        first.setServices(listOf(service))

        val reloaded = store()
        reloaded.load()

        assertEquals(1, reloaded.services.size)
        val restored = reloaded.services.single()
        assertEquals("tts-1", restored.id)
        assertEquals("My OpenAI voice", restored.name)
        assertEquals("sk-test", restored.apiKey)
    }

    @Test
    fun `removing a service clears its row`() {
        val service = requireNotNull(
            TtsServiceOptions.fromJson(
                """{"id":"tts-1","enabled":true,"name":"Voice","kind":"openai","apiKey":"k"}""",
            ),
        )
        val first = store()
        first.setServices(listOf(service))
        first.setServices(emptyList())

        val reloaded = store()
        reloaded.load()

        assertEquals(emptyList<TtsServiceOptions>(), reloaded.services)
    }

    /**
     * 系统语音的四个键（语速/音调/引擎/语言）是普通 PREFERENCE 键，写了要能读回来 ——
     * 原版 `TtsProvider._init` 就是从这些键播种的，读不到就等于设置没生效。
     */
    @Test
    fun `system tts preferences round-trip through the store`() {
        val store = store()
        store.setSystemTtsConfig(
            SystemTtsConfig(
                speechRate = 0.75,
                pitch = 1.3,
                engineId = "com.google.android.tts",
                languageTag = "ja-JP",
            ),
        )

        val read = store.systemTtsConfig()
        assertEquals(0.75, read.speechRate, 0.0001)
        assertEquals(1.3, read.pitch, 0.0001)
        assertEquals("com.google.android.tts", read.engineId)
        assertEquals("ja-JP", read.languageTag)

        store.cacheNetworkAudioForReplay = true
        org.junit.Assert.assertTrue(store.cacheNetworkAudioForReplay)
    }

    @Test
    fun `clearing engine and language falls back to auto`() {
        val store = store()
        store.setSystemTtsConfig(SystemTtsConfig(engineId = "com.x.tts", languageTag = "zh-CN"))

        store.setSystemTtsConfig(SystemTtsConfig())

        val read = store.systemTtsConfig()
        org.junit.Assert.assertNull("清掉后是「没设」，不是空串", read.engineId)
        org.junit.Assert.assertNull(read.languageTag)
        assertEquals(0.5, read.speechRate, 0.0001)
    }
}

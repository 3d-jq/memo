package com.psyche.memo

import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.ui.TtsServiceOptions
import com.psyche.memo.ui.TtsServicesStore
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
}

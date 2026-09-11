package com.psyche.memo

import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.data.model.Conversation
import com.psyche.memo.data.model.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A provider row renamed to its canonical spelling used to leave every stored
 * model selection pointing at the old name (`"zhipu ai"` while the row is
 * `"Zhipu AI"`). That made the memory-model request fail with "cannot reach the
 * memory model" even though the provider had an API key.
 *
 * Two layers cover it: lookups fold the key (AppContainer.providerConfig) and
 * the migration rewrites the stored values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderKeyMigrationTest {

    private lateinit var container: AppContainerImpl

    @Before
    fun setUp() {
        container = AppContainerImpl(ApplicationProvider.getApplicationContext())
        val db = container.database.writableDatabase
        db.execSQL("DELETE FROM provider_rows")
        db.execSQL("DELETE FROM conversation_rows")
        db.execSQL("DELETE FROM assistant_rows")
        db.execSQL("DELETE FROM preference_rows")
    }

    private fun seedZhipu() = container.providerRepository.saveConfig(
        ProviderConfig(
            id = "Zhipu AI",
            name = "Zhipu AI",
            apiKey = "test-key",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            models = listOf("glm-5.3-flash"),
        ),
    )

    private fun seedConversation(provider: String): Conversation {
        val conversation = Conversation(id = "c1", title = "T", createdAt = 1L, updatedAt = 2L)
        conversation.chatModelProvider = provider
        conversation.chatModelId = "glm-5.3-flash"
        container.conversationDao.insert(conversation)
        return conversation
    }

    @Test
    fun `a stale spelling still resolves to the provider row`() {
        seedZhipu()

        // The row is canonical; the caller carries the old spelling.
        val config = container.providerConfig("zhipu ai")
        assertNotNull(config)
        assertEquals("test-key", container.apiKeyFor("zhipu ai"))
        assertEquals("https://open.bigmodel.cn/api/paas/v4", container.baseUrlFor("zhipu ai"))
    }

    @Test
    fun `the migration folds stored model selections`() {
        seedZhipu()
        container.preferenceRepository.writeJson("memory_model_v1", "\"zhipu ai::glm-5.3-flash\"")
        container.preferenceRepository.writeJson("selected_model_v1", "\"zhipu ai::glm-5.3-flash\"")
        val conversation = seedConversation("zhipu ai")
        container.assistantStore.update(
            Assistant(id = "a1", name = "A", chatModelProvider = "zhipu ai", chatModelId = "glm-5.3-flash"),
        )

        container.providerRepository.migrateNonCanonicalBuiltinKeys()

        assertEquals("\"Zhipu AI::glm-5.3-flash\"", container.preferenceRepository.readJson("memory_model_v1"))
        assertEquals("\"Zhipu AI::glm-5.3-flash\"", container.preferenceRepository.readJson("selected_model_v1"))
        assertEquals("Zhipu AI", container.conversationDao.get(conversation.id)!!.chatModelProvider)
        assertEquals("Zhipu AI", container.assistantStore.get("a1")!!.chatModelProvider)
    }

    @Test
    fun `the migration leaves a correct selection and custom providers alone`() {
        seedZhipu()
        seedConversation("My Relay")
        container.preferenceRepository.writeJson("memory_model_v1", "\"My Relay::custom-model\"")
        container.assistantStore.update(
            Assistant(id = "a2", name = "B", chatModelProvider = "Zhipu AI", chatModelId = "glm-5.3-flash"),
        )

        container.providerRepository.migrateNonCanonicalBuiltinKeys()

        assertEquals("\"My Relay::custom-model\"", container.preferenceRepository.readJson("memory_model_v1"))
        assertEquals("My Relay", container.conversationDao.get("c1")!!.chatModelProvider)
        assertEquals("Zhipu AI", container.assistantStore.get("a2")!!.chatModelProvider)
    }
}

package com.psyche.memo.provider

import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.data.model.ChatMessage
import com.psyche.memo.data.model.Conversation
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.ui.MemoryPromptLang
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Port coverage of memory_tools chat_search + its definition gating. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryChatSearchTest {

    private lateinit var container: AppContainerImpl

    @Before
    fun setUp() {
        container = AppContainerImpl(ApplicationProvider.getApplicationContext())
    }

    private fun assistant(recall: Boolean = true) = Assistant(
        id = "a1",
        name = "T",
        enableMemory = true,
        allowPastConversationRecall = recall,
    )

    private fun seedConversation(id: String, title: String, assistantId: String?, text: String, timestamp: Long) {
        container.conversationDao.insert(
            Conversation(id = id, title = title, assistantId = assistantId, createdAt = timestamp, updatedAt = timestamp),
        )
        container.messageDao.insert(
            ChatMessage(
                id = "msg_${id}_$timestamp",
                role = "user",
                parts = listOf(TextPart(text)),
                timestamp = timestamp,
                conversationId = id,
                groupId = "g_$id",
                messageOrder = 0,
            ),
        )
    }

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun `chat_search is offered only with past-recall enabled`() {
        val without = MemoryTools.buildDefinitions(assistant(recall = false), MemoryPromptLang.en).map { it.name }
        assertTrue(MemoryTools.CHAT_SEARCH !in without)

        val with = MemoryTools.buildDefinitions(assistant(recall = true), MemoryPromptLang.en).map { it.name }
        assertTrue(MemoryTools.CHAT_SEARCH in with)
    }

    @Test
    fun `definition carries the upstream schema`() {
        val spec = MemoryTools.buildDefinitions(assistant(), MemoryPromptLang.en)
            .first { it.name == MemoryTools.CHAT_SEARCH }
        assertTrue(spec.inputSchemaJson.contains("\"conversation_id\""))
        assertTrue(spec.description.contains("past conversations"))
    }

    @Test
    fun `search finds messages in the assistant scope and skips the current chat`() {
        seedConversation("c1", "Trip planning", "a1", "we discussed the kyoto trip last time", 1_700_000_000_000L)
        seedConversation("c2", "Other", "a2", "kyoto is in japan", 1_700_000_001_000L)
        seedConversation("c3", "Current", "a1", "kyoto here", 1_700_000_002_000L)

        val payload = obj(
            handle(
                container, assistant(), "c3", false,
                MemoryTools.CHAT_SEARCH, Json.parseToJsonElement("""{"query":"kyoto"}""") as JsonObject,
            )!!,
        )
        val results = payload["results"] as JsonArray
        assertEquals(1, results.size)
        val hit = results[0] as JsonObject
        assertEquals("c1", (hit["conversationId"] as JsonPrimitive).content)
        assertEquals("Trip planning", (hit["title"] as JsonPrimitive).content)
        assertEquals("user", (hit["role"] as JsonPrimitive).content)
        assertTrue((hit["snippet"] as JsonPrimitive).content.contains("kyoto"))
    }

    @Test
    fun `conversation_id scopes the search to one conversation`() {
        seedConversation("c1", "A", "a1", "alpha topic", 1_700_000_000_000L)
        seedConversation("c2", "B", "a1", "alpha again", 1_700_000_001_000L)
        val payload = obj(
            handle(
                container, assistant(), "c9", false,
                MemoryTools.CHAT_SEARCH,
                Json.parseToJsonElement("""{"query":"alpha","conversation_id":"c2"}""") as JsonObject,
            )!!,
        )
        val results = payload["results"] as JsonArray
        assertEquals(1, results.size)
        assertEquals("c2", ((results[0] as JsonObject)["conversationId"] as JsonPrimitive).content)
    }

    @Test
    fun `empty query errors and disabled recall falls through`() {
        val error = handle(
            container, assistant(), "c1", false,
            MemoryTools.CHAT_SEARCH, Json.parseToJsonElement("""{"query":"  "}""") as JsonObject,
        )!!
        assertTrue(error.contains("invalid_query"))

        assertNull(
            handle(
                container, assistant(recall = false), "c1", false,
                MemoryTools.CHAT_SEARCH, Json.parseToJsonElement("""{"query":"x"}""") as JsonObject,
            ),
        )
    }

    /** [MemoryTools.handle] is suspend: Smart Add may call the memory model. */
    private fun handle(
        container: AppContainerImpl,
        assistant: Assistant?,
        conversationId: String?,
        isTemporary: Boolean,
        name: String,
        args: JsonObject,
    ): String? = kotlinx.coroutines.runBlocking {
        MemoryTools.handle(container, assistant, conversationId, isTemporary, name, args)
    }
}

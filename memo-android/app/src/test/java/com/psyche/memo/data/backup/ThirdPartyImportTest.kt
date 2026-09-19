package com.psyche.memo.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.backup.cherry.CherryImporter
import com.psyche.memo.data.backup.chatbox.ChatboxImportRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * End-to-end third-party imports (backup sub-block 8): a Cherry Studio
 * whole-file JSON export and a Chatbox legacy JSON export both land as
 * providers / assistants / conversations / messages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThirdPartyImportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val cherryJson = """
    {
      "version": 2,
      "localStorage": {
        "persist:cherry-studio": "{\"assistants\":{\"assistants\":[{\"id\":\"a1\",\"name\":\"Helper\",\"prompt\":\"be nice\",\"settings\":{\"temperature\":0.5},\"model\":{\"provider\":\"openai\",\"id\":\"gpt-x\"},\"topics\":[{\"id\":\"t1\",\"name\":\"Topic\",\"assistantId\":\"a1\",\"createdAt\":\"2026-01-01T00:00:00.000Z\",\"updatedAt\":\"2026-01-02T00:00:00.000Z\"}]}]},\"llm\":{\"providers\":[{\"id\":\"anthropic\",\"type\":\"anthropic\",\"name\":\"Claude\",\"apiKey\":\"sk-1,sk-2\",\"apiHost\":\"api.anthropic.com\",\"models\":[{\"id\":\"claude-x\"}],\"enabled\":true}]}}"
      },
      "indexedDB": {
        "files": [],
        "topics": [
          {
            "id": "t1",
            "messages": [
              {"id":"m1","role":"user","content":"hi","createdAt":"2026-01-01T01:00:00.000Z"},
              {"id":"m2","role":"assistant","content":"","createdAt":"2026-01-01T02:00:00.000Z","model":{"id":"claude-x","provider":"anthropic"},"_blocks":[{"type":"code","content":"println(1)","language":"kotlin"}]}
            ]
          }
        ],
        "message_blocks": [
          {"id":"b1","messageId":"m2","type":"code","content":"println(1)","language":"kotlin"}
        ]
      }
    }
    """.trimIndent()

    private val chatboxJson = """
    {
      "__exported_at": "2026-01-01T00:00:00.000Z",
      "chat-sessions-list": [{"id":"s1","name":"Boxer","picUrl":""}],
      "session:s1": {
        "settings": {"provider":"deepseek","modelId":"deepseek-chat","temperature":0.7},
        "messages": [
          {"id":"sm0","role":"system","content":"you are boxy"},
          {"id":"sm1","role":"user","content":"hello","timestamp":1767225600000},
          {"id":"sm2","role":"assistant","content":"world","timestamp":1767225660000,"tokenCount":12}
        ],
        "threads": []
      },
      "settings": {"providers": {"deepseek": {"apiKey":"ds-key","apiHost":"api.deepseek.com","models":[{"modelId":"deepseek-chat"}]}}}
    }
    """.trimIndent()

    @Test
    fun `cherry whole-file json imports providers assistants and topics`() {
        val container = AppContainerImpl(context)
        val file = File(context.cacheDir, "cherry_${System.nanoTime()}.json").apply { writeText(cherryJson) }
        val result = CherryImporter.importFromCherryStudio(
            file = file,
            mode = RestoreMode.OVERWRITE,
            database = container.database,
            preferenceRepository = container.preferenceRepository,
            uploadDir = File(context.filesDir, "upload"),
        )

        assertEquals(1, result.providers)
        assertEquals(1, result.assistants)
        assertEquals(1, result.conversations)
        assertEquals(2, result.messages)

        // Provider: anthropic → claude kind, host gets /v1 appended, multi-key
        // split into two api keys.
        val providerPayload = container.database.writableDatabase
            .rawQuery("SELECT payload FROM provider_rows WHERE provider_key = 'anthropic'", null)
            .use { c -> c.moveToFirst(); c.getString(0) }
        assertTrue("api.anthropic.com/v1" in providerPayload)
        assertTrue("\"providerType\":\"claude\"" in providerPayload)
        assertTrue("sk-2" in providerPayload)

        // Assistant prompt came through.
        val assistantPayload = container.database.writableDatabase
            .rawQuery("SELECT payload FROM assistant_rows WHERE id = 'a1'", null)
            .use { c -> c.moveToFirst(); c.getString(0) }
        assertTrue("be nice" in assistantPayload)

        // The assistant message's code block became a fenced TextPart.
        val messageDao = com.psyche.memo.data.db.MessageDao(container.database.writableDatabase)
        val messages = messageDao.getAllForConversation("t1")
        assertEquals(2, messages.size)
        val assistant = messages.first { it.role == "assistant" }
        assertTrue("```kotlin" in assistant.content)
        file.delete()
    }

    @Test
    fun `chatbox legacy json imports providers assistants and sessions`() {
        val container = AppContainerImpl(context)
        val file = File(context.cacheDir, "chatbox_${System.nanoTime()}.json").apply { writeText(chatboxJson) }
        val result = ChatboxImportRunner.importFromChatbox(
            file = file,
            mode = RestoreMode.MERGE,
            database = container.database,
            preferenceRepository = container.preferenceRepository,
            uploadDir = File(context.filesDir, "upload"),
        )

        assertEquals(1, result.providers)
        assertEquals(1, result.assistants)
        assertEquals(1, result.conversations)
        // The first system message is consumed as the assistant prompt.
        assertEquals(2, result.messages)

        val providerPayload = container.database.writableDatabase
            .rawQuery("SELECT payload FROM provider_rows WHERE provider_key = 'deepseek'", null)
            .use { c -> c.moveToFirst(); c.getString(0) }
        assertTrue("\"baseUrl\":\"https://api.deepseek.com/v1\"" in providerPayload)

        val messageDao = com.psyche.memo.data.db.MessageDao(container.database.writableDatabase)
        val messages = messageDao.getAllForConversation("chatbox_default_s1")
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("assistant", messages[1].role)
        assertEquals(12, messages[1].totalTokens)

        // Merge into the same conversation appends without duplicating.
        val second = ChatboxImportRunner.importFromChatbox(
            file = file,
            mode = RestoreMode.MERGE,
            database = container.database,
            preferenceRepository = container.preferenceRepository,
            uploadDir = File(context.filesDir, "upload"),
        )
        assertEquals(0, second.conversations)
        assertEquals(0, second.messages)
        assertNotNull(container.database.writableDatabase)
        file.delete()
    }

    @Test
    fun `cherry rejects an unsupported direct backup version`() {
        val container = AppContainerImpl(context)
        val dir = File(context.cacheDir, "cherry_v7_${System.nanoTime()}").apply { mkdirs() }
        // Minimal zip with metadata.json at version 7 → refused.
        val zip = File(dir, "backup.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("metadata.json"))
            out.write("{\"version\":7}".toByteArray())
            out.closeEntry()
        }
        var thrown: com.psyche.memo.data.backup.cherry.CherryUnsupportedBackupVersionException? = null
        try {
            CherryImporter.importFromCherryStudio(
                file = zip,
                mode = RestoreMode.OVERWRITE,
                database = container.database,
                preferenceRepository = container.preferenceRepository,
                uploadDir = File(context.filesDir, "upload"),
            )
        } catch (e: com.psyche.memo.data.backup.cherry.CherryUnsupportedBackupVersionException) {
            thrown = e
        }
        assertEquals(7, thrown?.version)
        dir.deleteRecursively()
    }
}

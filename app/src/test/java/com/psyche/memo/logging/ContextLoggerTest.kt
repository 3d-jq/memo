package com.psyche.memo.logging

import com.psyche.memo.common.logging.ContextLogMessage
import com.psyche.memo.common.logging.ContextLogSnapshot
import com.psyche.memo.common.logging.ContextSegment
import com.psyche.memo.common.logging.ContextSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ContextLoggerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var logsDir: File

    @Before
    fun setUp() {
        logsDir = tmp.newFolder("logs")
        ContextLogger.initialize(logsDir)
    }

    @After
    fun tearDown() {
        ContextLogger.setEnabled(false)
        runBlocking { delay(50) }
    }

    @Test
    fun `logSnapshot writes one JSON line`() = runBlocking {
        ContextLogger.setEnabled(true)
        val snap = newSnapshot()
        ContextLogger.logSnapshot(snap)
        delay(150)
        val text = File(logsDir, ContextLogger.ACTIVE_FILE_NAME).readText()
        val lines = text.split('\n').filter { it.isNotEmpty() }
        assertEquals(1, lines.size)
        // Should be valid JSON.
        val obj = Json.parseToJsonElement(lines.single()).jsonObject
        assertEquals("conv-1", obj["conversationId"]?.jsonPrimitive?.content)
        assertEquals("gpt-4", obj["model"]?.jsonPrimitive?.content)
        assertTrue("Should have a messages array", obj["messages"] is kotlinx.serialization.json.JsonArray)
    }

    @Test
    fun `logSnapshot redacts secret patterns in the line`() = runBlocking {
        ContextLogger.setEnabled(true)
        val snap = newSnapshot(extraText = "connecting with sk-abcdefghijklmnop")
        ContextLogger.logSnapshot(snap)
        delay(150)
        val text = File(logsDir, ContextLogger.ACTIVE_FILE_NAME).readText()
        assertFalse("Secret should not appear, got: $text", text.contains("sk-abcdefghijklmnop"))
        assertTrue(text.contains("***"))
    }

    @Test
    fun `logSnapshot is no-op when disabled`() = runBlocking {
        ContextLogger.setEnabled(false)
        ContextLogger.logSnapshot(newSnapshot())
        delay(150)
        val text = File(logsDir, ContextLogger.ACTIVE_FILE_NAME).let {
            if (it.exists()) it.readText() else ""
        }
        assertEquals("", text)
    }

    @Test
    fun `multiple logSnapshot writes produce one line each`() = runBlocking {
        ContextLogger.setEnabled(true)
        repeat(3) { ContextLogger.logSnapshot(newSnapshot(conversationId = "c$it")) }
        delay(200)
        val text = File(logsDir, ContextLogger.ACTIVE_FILE_NAME).readText()
        val lines = text.split('\n').filter { it.isNotEmpty() }
        assertEquals(3, lines.size)
        // Each line should parse independently and carry its own conversationId.
        for ((i, line) in lines.withIndex()) {
            val obj = Json.parseToJsonElement(line).jsonObject
            assertEquals("c$i", obj["conversationId"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `messages round-trip through JSONL`() = runBlocking {
        ContextLogger.setEnabled(true)
        val snap = newSnapshot(
            extraText = null,
            messages = listOf(
                ContextLogMessage(
                    role = "system",
                    segments = listOf(
                        ContextSegment(source = ContextSource.systemPrompt, text = "you are helpful", tokens = 3),
                    ),
                ),
                ContextLogMessage(
                    role = "user",
                    segments = listOf(
                        ContextSegment(source = ContextSource.chatHistory, text = "hello", tokens = 1),
                    ),
                ),
            ),
        )
        ContextLogger.logSnapshot(snap)
        delay(150)
        val text = File(logsDir, ContextLogger.ACTIVE_FILE_NAME).readText()
        val obj = Json.parseToJsonElement(text.trim()).jsonObject
        val messages = obj["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        val first = messages[0].jsonObject
        assertEquals("system", first["role"]!!.jsonPrimitive.content)
        val firstSegs = first["segments"]!!.jsonArray
        assertEquals("systemPrompt", firstSegs[0].jsonObject["source"]!!.jsonPrimitive.content)
    }

    private fun newSnapshot(
        conversationId: String = "conv-1",
        extraText: String? = null,
        messages: List<ContextLogMessage> = listOf(
            ContextLogMessage(
                role = "user",
                segments = listOf(
                    ContextSegment(
                        source = ContextSource.chatHistory,
                        text = extraText ?: "hello",
                        tokens = 1,
                    ),
                ),
            ),
        ),
    ): ContextLogSnapshot = ContextLogSnapshot(
        timestamp = 1_700_000_000_000L,
        conversationId = conversationId,
        assistantName = "Test",
        provider = "openai",
        model = "gpt-4",
        messages = messages,
        totalTokens = messages.sumOf { it.segments.sumOf { s -> s.tokens } },
    )
}

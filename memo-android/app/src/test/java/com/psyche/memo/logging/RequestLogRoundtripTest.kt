package com.psyche.memo.logging

import com.psyche.memo.common.logging.LogRedactor
import com.psyche.memo.common.logging.RequestLogger
import com.psyche.memo.ui.RequestLogParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Verifies the format produced by [RequestLogger] is parseable by the
 * existing [RequestLogParser] in `com.psyche.memo.ui.LogData`. This is the
 * contract the Log Viewer depends on.
 */
class RequestLogRoundtripTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var logsDir: File

    @Before
    fun setUp() {
        logsDir = tmp.newFolder("logs")
        RequestLogger.initialize(logsDir)
    }

    @After
    fun tearDown() {
        RequestLogger.setEnabled(false)
        runBlocking { delay(50) }
    }

    @Test
    fun `written REQ RES lines parse back to the same id`() = runBlocking {
        RequestLogger.setEnabled(true)
        val id = RequestLogger.nextRequestId()
        RequestLogger.logLine("[REQ $id] POST https://api.example.com/v1/chat")
        RequestLogger.logLine(
            "[REQ $id] headers=" + LogRedactor.redactHeaders(
                mapOf("Authorization" to "Bearer verylongbearertoken12345", "Content-Type" to "application/json")
            ).let { headers ->
                val obj = kotlinx.serialization.json.JsonObject(
                    headers.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }
                )
                kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj)
            }
        )
        RequestLogger.logLine(
            "[REQ $id] body=" + RequestLogger.escape("""{"model":"gpt-4","api_key":"sk-abcdefghijklmnop"}""")
        )
        RequestLogger.logLine("[RES $id] status=200")
        RequestLogger.logLine("[RES $id] done")
        delay(200)

        val text = File(logsDir, RequestLogger.ACTIVE_FILE_NAME).readText()
        val entries = RequestLogParser.parse(text)
        assertEquals(1, entries.size)
        val e = entries.single()
        assertEquals(id, e.id)
        assertEquals("POST", e.method)
        assertEquals("https://api.example.com/v1/chat", e.rawUrl)
        assertEquals(200, e.statusCode)
        assertTrue("Body should have survived roundtrip, got: ${e.requestBody}",
            (e.requestBody ?: "").contains("model"))
    }

    @Test
    fun `escape unescape roundtrip via parser`() = runBlocking {
        RequestLogger.setEnabled(true)
        val id = RequestLogger.nextRequestId()
        val tricky = "line with \\ backslash and \n newline and \r CR and \t tab"
        RequestLogger.logLine("[REQ $id] body=" + RequestLogger.escape(tricky))
        delay(150)

        val text = File(logsDir, RequestLogger.ACTIVE_FILE_NAME).readText()
        val entries = RequestLogParser.parse(text)
        val e = entries.single()
        // The parser unescapes the body back to the original.
        assertEquals(tricky, e.requestBody)
    }

    @Test
    fun `chunks get reassembled by parser`() = runBlocking {
        RequestLogger.setEnabled(true)
        val id = RequestLogger.nextRequestId()
        RequestLogger.logLine("[REQ $id] GET https://api.example.com/v1/models")
        RequestLogger.logLine("[RES $id] status=200")
        RequestLogger.logLine("[RES $id] chunk=" + RequestLogger.escape("data: {\"x\":1}\n\n"))
        RequestLogger.logLine("[RES $id] chunk=" + RequestLogger.escape("data: {\"x\":2}\n\n"))
        RequestLogger.logLine("[RES $id] done")
        delay(200)

        val text = File(logsDir, RequestLogger.ACTIVE_FILE_NAME).readText()
        val entries = RequestLogParser.parse(text)
        val e = entries.single()
        // Reassembled streaming body — both chunks concatenated.
        assertEquals("data: {\"x\":1}\n\ndata: {\"x\":2}\n\n", e.responseBody)
    }
}

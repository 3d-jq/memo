package com.psyche.memo.llm.logging

import com.psyche.memo.common.logging.RequestLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class RequestLogInterceptorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var logsDir: File
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        logsDir = tmp.newFolder("logs")
        RequestLogger.initialize(logsDir)
        RequestLogger.saveOutput = true
        client = OkHttpClient.Builder()
            .addInterceptor(RequestLogInterceptor())
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
        RequestLogger.setEnabled(false)
        runBlocking { delay(50) }
    }

    @Test
    fun `request logs REQ line method and url`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))
        RequestLogger.setEnabled(true)
        val req = Request.Builder()
            .url(server.url("/v1/chat/completions"))
            .post("""{"model":"gpt-4"}""".toRequestBody())
            .build()
        client.newCall(req).execute().use { it.body?.string() }
        waitForFlush()
        val text = readLog()
        assertTrue("Expected REQ line, got: $text",
            Regex("""\[REQ \d+] POST http://127\.0\.0\.1:\d+/v1/chat/completions""").containsMatchIn(text))
    }

    @Test
    fun `request logs headers as json with redaction`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))
        RequestLogger.setEnabled(true)
        val req = Request.Builder()
            .url(server.url("/test"))
            .header("Authorization", "Bearer sk-abcdefghijklmnop")
            .header("X-Custom", "value")
            .get()
            .build()
        client.newCall(req).execute().use { it.body?.string() }
        waitForFlush()
        val text = readLog()
        val headersLine = text.lineSequence().first { it.contains("[REQ") && it.contains("headers=") }
        assertTrue("Authorization should be masked, got: $headersLine",
            headersLine.contains("***") && !headersLine.contains("sk-abcdefghijklmnop"))
        assertTrue("Custom header value should pass through, got: $headersLine",
            headersLine.contains("\"X-Custom\":\"value\""))
    }

    @Test
    fun `request body is logged when saveOutput is true`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        RequestLogger.setEnabled(true)
        val req = Request.Builder()
            .url(server.url("/test"))
            .post("""{"api_key":"sk-abcdefghijklmnop","model":"gpt-4"}""".toRequestBody())
            .build()
        client.newCall(req).execute().use { it.body?.string() }
        waitForFlush()
        val text = readLog()
        val bodyLine = text.lineSequence().firstOrNull { it.contains("[REQ") && it.contains("body=") }
        assertTrue("Expected body line, got: $text", bodyLine != null)
        assertTrue("api_key should be masked, got: $bodyLine",
            bodyLine!!.contains("***") && !bodyLine.contains("sk-abcdefghijklmnop"))
    }

    @Test
    fun `request body is logged even when saveOutput is false`() = runBlocking {
        // Mirrors `dio_http_client.dart`: request body is gated only on
        // RequestLogger.enabled, not on saveOutput (which controls streaming
        // chunks). Default settings (request log on, saveOutput off) should
        // still show the JSON body of every request.
        RequestLogger.saveOutput = false
        server.enqueue(MockResponse().setBody("{}"))
        RequestLogger.setEnabled(true)
        val req = Request.Builder()
            .url(server.url("/test"))
            .post("""{"api_key":"sk-abcdefghijklmnop","model":"gpt-4"}""".toRequestBody())
            .build()
        client.newCall(req).execute().use { it.body?.string() }
        waitForFlush()
        val text = readLog()
        val bodyLine = text.lineSequence().firstOrNull { it.contains("[REQ") && it.contains("body=") }
        assertTrue("Body line should be written even with saveOutput off, got: $text",
            bodyLine != null)
        assertTrue("api_key should be masked, got: $bodyLine",
            bodyLine!!.contains("***") && !bodyLine.contains("sk-abcdefghijklmnop"))
    }

    @Test
    fun `large request body writes placeholder instead of full content`() = runBlocking {
        // Mirrors `dio_http_client.dart` `_loggedBodyLimit` (4 MiB): bodies
        // larger than the cap get `<N bytes, not logged>` so the file is
        // always openable.
        RequestLogger.saveOutput = false
        server.enqueue(MockResponse().setBody("{}"))
        RequestLogger.setEnabled(true)
        val big = "x".repeat(5 * 1024 * 1024) // 5 MiB
        val req = Request.Builder()
            .url(server.url("/upload"))
            .post(big.toRequestBody())
            .build()
        client.newCall(req).execute().use { it.body?.string() }
        waitForFlush()
        val text = readLog()
        val bodyLine = text.lineSequence().firstOrNull { it.contains("[REQ") && it.contains("body=") }
        assertTrue("Expected body placeholder, got: $text", bodyLine != null)
        assertTrue("Should be a placeholder, got: $bodyLine",
            bodyLine!!.contains("bytes, not logged"))
    }

    @Test
    fun `response logs status and headers`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .addHeader("X-Trace-Id", "abc-verylongtracerid")
        )
        RequestLogger.setEnabled(true)
        val req = Request.Builder().url(server.url("/test")).get().build()
        client.newCall(req).execute().use { it.body?.string() }
        waitForFlush()
        val text = readLog()
        assertTrue("Expected [RES n] status=201, got: $text",
            Regex("""\[RES \d+] status=201""").containsMatchIn(text))
        val resHeadersLine = text.lineSequence().first { it.contains("[RES") && it.contains("headers=") }
        assertTrue("Trace header should pass through, got: $resHeadersLine",
            resHeadersLine.contains("abc-verylongtracerid"))
    }

    @Test
    fun `streaming response logs chunks`() = runBlocking {
        val body = "data: {\"x\":1}\n\ndata: {\"x\":2}\n\n"
        server.enqueue(
            MockResponse()
                .setBody(body)
                .addHeader("Content-Type", "text/event-stream")
        )
        RequestLogger.setEnabled(true)
        val req = Request.Builder().url(server.url("/stream")).get().build()
        client.newCall(req).execute().use { it.body?.string() }
        waitForFlush()
        val text = readLog()
        val chunkLines = text.lineSequence().filter { it.contains("chunk=") }.toList()
        assertTrue("Expected at least one chunk line, got: ${chunkLines.size}",
            chunkLines.isNotEmpty())
    }

    @Test
    fun `chunk lines are elided and redacted before writing`() = runBlocking {
        // `dio_http_client.dart` L286: each chunk goes through
        // `escape(redactBody(elidePayloads(chunk)))` so a 200 with a base64
        // image in the stream doesn't bloat logs.txt and embedded secrets are
        // not stored in cleartext.
        val imageB64 = "A".repeat(5000)
        val body = """data: {"api_key":"sk-abcdefghijklmnop","image":"data:image/png;base64,$imageB64"}""" + "\n\n"
        server.enqueue(
            MockResponse()
                .setBody(body)
                .addHeader("Content-Type", "text/event-stream")
        )
        RequestLogger.setEnabled(true)
        val req = Request.Builder().url(server.url("/stream")).get().build()
        client.newCall(req).execute().use { it.body?.string() }
        waitForFlush()
        val text = readLog()
        val chunkLines = text.lineSequence().filter { it.contains("chunk=") }.toList()
        assertTrue("Expected chunk lines, got: $text", chunkLines.isNotEmpty())
        val all = chunkLines.joinToString("\n")
        assertFalse("Secret should not appear, got: $all", all.contains("sk-abcdefghijklmnop"))
        assertTrue("Redaction should fire, got: $all", all.contains("***"))
        // 5000 'A' base64 would be ~5000 bytes raw; after elide the line must
        // be substantially shorter (placeholder for the data: URI is small).
        val maxLine = chunkLines.maxOf { it.length }
        assertTrue("Elided chunk line should be short, got len=$maxLine", maxLine < 500)
    }

    @Test
    fun `4xx response body is captured as RES body line`() = runBlocking {
        // `dio_http_client.dart` L254-261: on a 4xx, the body is read up
        // to 256 KiB and written as `[RES n] body=` so the viewer can show
        // API error messages even when streaming is off.
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":{"message":"Invalid API key","api_key":"sk-abcdefghijklmnop"}}""")
                .addHeader("Content-Type", "application/json")
        )
        RequestLogger.saveOutput = false
        RequestLogger.setEnabled(true)
        val req = Request.Builder().url(server.url("/test")).get().build()
        client.newCall(req).execute().use { it.body?.string() }
        waitForFlush()
        val text = readLog()
        val bodyLine = text.lineSequence()
            .firstOrNull { it.contains("[RES") && it.contains("body=") && !it.contains("chunk=") }
        assertTrue("Expected [RES n] body= line for 4xx, got: $text", bodyLine != null)
        assertTrue("Error message should pass through, got: $bodyLine",
            bodyLine!!.contains("Invalid API key"))
        assertTrue("api_key should be masked, got: $bodyLine",
            bodyLine.contains("***") && !bodyLine.contains("sk-abcdefghijklmnop"))
    }

    @Test
    fun `4xx body is captured even when saveOutput is false`() = runBlocking {
        // Specifically the default case the user flagged: the LogViewer used
        // to be empty for 400/401 because saveOutput=false and there was no
        // body capture. With this fix, the body line shows up regardless of
        // saveOutput, because it's the error path (status >= 400).
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"msg":"unauthorized"}"""))
        RequestLogger.saveOutput = false
        RequestLogger.setEnabled(true)
        val req = Request.Builder().url(server.url("/test")).get().build()
        client.newCall(req).execute().use { it.body?.string() }
        waitForFlush()
        val text = readLog()
        val bodyLine = text.lineSequence()
            .firstOrNull { it.contains("[RES") && it.contains("body=") && !it.contains("chunk=") }
        assertTrue("4xx body should be logged even with saveOutput off, got: $text",
            bodyLine != null)
        assertTrue("Body should contain the error message, got: $bodyLine",
            bodyLine!!.contains("unauthorized"))
    }

    @Test
    fun `error path logs RES n error`() = runBlocking {
        // Don't enqueue a response → connection error.
        RequestLogger.setEnabled(true)
        val req = Request.Builder()
            .url(server.url("/will-fail"))
            .get()
            .build()
        try {
            client.newCall(req).execute().use { it.body?.string() }
        } catch (_: Exception) {
            // expected
        }
        waitForFlush()
        val text = readLog()
        assertTrue("Expected [RES n] error=, got: $text",
            Regex("""\[RES \d+] error=""").containsMatchIn(text))
    }

    @Test
    fun `disabled logger is a no-op passthrough`() = runBlocking {
        RequestLogger.setEnabled(false)
        server.enqueue(MockResponse().setBody("ok"))
        val req = Request.Builder().url(server.url("/test")).get().build()
        client.newCall(req).execute().use { it.body?.string() }
        waitForFlush()
        val text = File(logsDir, RequestLogger.ACTIVE_FILE_NAME).let {
            if (it.exists()) it.readText() else ""
        }
        assertEquals("", text)
    }

    @Test
    fun requestLogContextTagCarriesIdForStreamingCode(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("ok"))
        RequestLogger.setEnabled(true)
        val req = Request.Builder().url(server.url("/test")).get().build()
        client.newCall(req).execute().use { resp ->
            val ctx = resp.request.tag(RequestLogContext::class.java)
            assertTrue("Tag should be set, resp=${resp.request}", ctx != null)
            assertTrue("id should be positive, got ${ctx!!.id}", ctx.id > 0)
            // intentionally discard body
        }
    }

    private suspend fun waitForFlush() {
        delay(200)
    }

    private fun readLog(): String = File(logsDir, RequestLogger.ACTIVE_FILE_NAME).readText()
}

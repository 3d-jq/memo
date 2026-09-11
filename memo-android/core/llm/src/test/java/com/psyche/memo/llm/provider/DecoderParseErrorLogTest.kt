package com.psyche.memo.llm.provider

import com.psyche.memo.common.logging.FlutterLogger
import com.psyche.memo.llm.stream.SseEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Malformed provider payloads never kill the stream, but they must leave a line
 * in `flutter_logs.txt` (chat_api_helpers.dart L839-845, tag DecoderParseError);
 * otherwise a silently dropped chunk looks like the model answering badly.
 */
class DecoderParseErrorLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var logsDir: File

    @Before
    fun setUp() {
        logsDir = folder.newFolder("logs")
        FlutterLogger.initialize(logsDir)
        FlutterLogger.setEnabled(true)
    }

    @After
    fun tearDown() {
        FlutterLogger.setEnabled(false)
    }

    private fun logged(): String {
        val file = File(logsDir, FlutterLogger.ACTIVE_FILE_NAME)
        return if (file.exists()) file.readText() else ""
    }

    @Test
    fun `unparseable event data is logged with the provider and event type`() = runBlocking {
        val decoder = ChatCompletionsDecoder(providerLabel = "Zhipu AI")
        decoder.accept(SseEvent(id = null, event = "message", data = "{not json", retryMillis = null))
        delay(200)

        val text = logged()
        assertTrue("missing decoder log, got: '$text'",
            text.contains("[DecoderParseError] provider=Zhipu AI eventType=message"))
    }

    @Test
    fun `well-formed events log nothing`() = runBlocking {
        val decoder = ChatCompletionsDecoder(providerLabel = "Zhipu AI")
        decoder.accept(SseEvent(null, "message", "{\"choices\":[]}", null))
        delay(200)

        assertTrue("no log expected, got: '${logged()}'", logged().isBlank())
    }
}

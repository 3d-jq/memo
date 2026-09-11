package com.psyche.memo.llm.stream

import com.psyche.memo.common.logging.FlutterLogger
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
 * The app-log wiring of the SSE framer (sse_framing.dart L235-240): a stream
 * that had to be recovered leaves one line in `flutter_logs.txt`. Without this
 * the recovery is invisible, which is how a provider silently eating a token
 * can go unnoticed for weeks.
 */
class SseFramingRecoveryLogTest {

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

    @Test
    fun `adjacent json recovery writes a tagged app log line`() = runBlocking {
        val parser = SseEventParser(recoverAdjacentJsonDataRecords = true)
        parser.add("data: {\"a\":1}\ndata: {\"b\":2}\n\n")
        delay(200)

        val text = File(logsDir, FlutterLogger.ACTIVE_FILE_NAME).readText()
        assertTrue("missing recovery log, got: '$text'",
            text.contains("[SseFramingRecovery] recoveredAdjacentJsonDataRecords count=1"))
    }

    @Test
    fun `a parser with recovery disabled never logs`() = runBlocking {
        val parser = SseEventParser(recoverAdjacentJsonDataRecords = false)
        parser.add("data: {\"a\":1}\ndata: {\"b\":2}\n\n")
        delay(200)

        val file = File(logsDir, FlutterLogger.ACTIVE_FILE_NAME)
        assertTrue("no log expected, got: '${if (file.exists()) file.readText() else ""}'",
            !file.exists() || file.readText().isBlank())
    }
}

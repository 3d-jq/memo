package com.psyche.memo.common.logging

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FlutterLoggerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var logsDir: File

    @Before
    fun setUp() {
        logsDir = tmp.newFolder("logs")
        FlutterLogger.initialize(logsDir)
    }

    @After
    fun tearDown() {
        FlutterLogger.setEnabled(false)
        runBlocking { delay(50) }
    }

    @Test
    fun `log writes timestamped line`() = runBlocking {
        FlutterLogger.setEnabled(true)
        FlutterLogger.log("hello world")
        delay(150)
        val text = File(logsDir, FlutterLogger.ACTIVE_FILE_NAME).readText().trim()
        val re = Regex("""^\[\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}] hello world$""")
        assertTrue("Line did not match: '$text'", re.matches(text))
    }

    @Test
    fun `log with tag includes tag in prefix`() = runBlocking {
        FlutterLogger.setEnabled(true)
        FlutterLogger.log("network error", tag = "Net")
        delay(150)
        val text = File(logsDir, FlutterLogger.ACTIVE_FILE_NAME).readText().trim()
        val re = Regex("""^\[\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}] \[Net] network error$""")
        assertTrue("Line did not match: '$text'", re.matches(text))
    }

    @Test
    fun `multiline message gets prefix on every line`() = runBlocking {
        FlutterLogger.setEnabled(true)
        FlutterLogger.log("first\nsecond\nthird", tag = "T")
        delay(150)
        val text = File(logsDir, FlutterLogger.ACTIVE_FILE_NAME).readText()
        val lines = text.split('\n').filter { it.isNotEmpty() }
        assertEquals(3, lines.size)
        val re = Regex("""^\[\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}] \[T] (first|second|third)$""")
        for (l in lines) assertTrue("Bad line: '$l'", re.matches(l))
        assertTrue(lines[0].endsWith("first"))
        assertTrue(lines[1].endsWith("second"))
        assertTrue(lines[2].endsWith("third"))
    }

    @Test
    fun `log redacts known secret prefixes before writing`() = runBlocking {
        FlutterLogger.setEnabled(true)
        FlutterLogger.log("connecting with sk-abcdefghijklmnop")
        delay(150)
        val text = File(logsDir, FlutterLogger.ACTIVE_FILE_NAME).readText()
        assertFalse("Secret must not appear, got: $text", text.contains("sk-abcdefghijklmnop"))
        assertTrue(text.contains("***"))
    }

    @Test
    fun `log is no-op when disabled`() = runBlocking {
        FlutterLogger.setEnabled(false)
        FlutterLogger.log("ignored")
        delay(150)
        val text = File(logsDir, FlutterLogger.ACTIVE_FILE_NAME).let {
            if (it.exists()) it.readText() else ""
        }
        assertEquals("", text)
    }

    @Test
    fun `logPrint uses print tag`() = runBlocking {
        FlutterLogger.setEnabled(true)
        FlutterLogger.logPrint("hello")
        delay(150)
        val text = File(logsDir, FlutterLogger.ACTIVE_FILE_NAME).readText().trim()
        val re = Regex("""^\[\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}] \[print] hello$""")
        assertTrue(re.matches(text))
    }
}

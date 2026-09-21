package com.psyche.memo.common.logging

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RequestLoggerTest {

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
        // give the close coroutine a moment to settle
        runBlocking { delay(50) }
    }

    // —— line format ————————————————————————————————————————————————

    @Test
    fun `logLine writes timestamped single line`() = runBlocking {
        RequestLogger.setEnabled(true)
        RequestLogger.logLine("hello world")
        waitForFlush()
        val text = File(logsDir, RequestLogger.ACTIVE_FILE_NAME).readText()
        val line = text.trim()
        // Format: [YYYY-MM-DD HH:MM:SS.mmm] hello world
        val re = Regex("""^\[\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}] hello world$""")
        assertTrue("Line did not match expected format: '$line'", re.matches(line))
    }

    @Test
    fun `logLine is a no-op when disabled`() = runBlocking {
        RequestLogger.setEnabled(false)
        RequestLogger.logLine("ignored")
        waitForFlush()
        val text = File(logsDir, RequestLogger.ACTIVE_FILE_NAME).let {
            if (it.exists()) it.readText() else ""
        }
        assertEquals("", text)
    }

    // —— escape ——————————————————————————————————————————————————

    @Test
    fun `escape replaces backslash and control chars`() {
        val out = RequestLogger.escape("a\\b\nc\rd\te")
        assertEquals("a\\\\b\\nc\\rd\\te", out)
    }

    @Test
    fun `escape leaves plain text alone`() {
        val out = RequestLogger.escape("just a string")
        assertEquals("just a string", out)
    }

    // —— nextRequestId ——————————————————————————————————————————————

    @Test
    fun `nextRequestId is monotonic and unique`() {
        val first = RequestLogger.nextRequestId()
        val second = RequestLogger.nextRequestId()
        val third = RequestLogger.nextRequestId()
        assertTrue(second > first)
        assertTrue(third > second)
    }

    // —— elidePayloads ————————————————————————————————————————————————

    @Test
    fun `elidePayloads delegates to LogPayloadElider when enabled`() {
        RequestLogger.elideLargePayloads = true
        val huge = "x".repeat(5000)
        val input = """{"data":"$huge"}"""
        val out = RequestLogger.elidePayloads(input)
        assertTrue(out.contains("<omitted"))
        assertFalse(out.contains(huge))
    }

    @Test
    fun `elidePayloads is a passthrough when disabled`() {
        RequestLogger.elideLargePayloads = false
        val huge = "x".repeat(5000)
        val input = """{"data":"$huge"}"""
        val out = RequestLogger.elidePayloads(input)
        assertEquals(input, out)
    }

    // —— safeDecodeUtf8 ——————————————————————————————————————————————

    @Test
    fun `safeDecodeUtf8 decodes valid utf8`() {
        val out = RequestLogger.safeDecodeUtf8("héllo".toByteArray(Charsets.UTF_8))
        assertEquals("héllo", out)
    }

    @Test
    fun `safeDecodeUtf8 replaces malformed bytes`() {
        // 0xC0 0x80 is a non-canonical overlong NUL — invalid UTF-8.
        val bad = byteArrayOf(0x68, 0x69, 0xC0.toByte(), 0x80.toByte(), 0x21)
        val out = RequestLogger.safeDecodeUtf8(bad)
        // Should not throw; should contain "hi" and "!" with replacement chars between.
        assertTrue("Expected non-empty output, got: '$out'", out.isNotEmpty())
        assertTrue(out.startsWith("hi"))
        assertTrue(out.endsWith("!"))
    }

    // —— daily rotation ——————————————————————————————————————————————

    @Test
    fun `daily rotation renames previous days file`() = runBlocking {
        RequestLogger.setEnabled(true)
        RequestLogger.logLine("today's line")
        waitForFlush()

        // Simulate yesterday: rewrite the active file's mtime to 25h ago.
        val active = File(logsDir, RequestLogger.ACTIVE_FILE_NAME)
        assertTrue(active.exists())
        val yesterday = System.currentTimeMillis() - 25L * 3600 * 1000
        assertTrue(active.setLastModified(yesterday))

        // Disable + re-enable to force ensureSinkLocked to run.
        RequestLogger.setEnabled(false)
        waitForFlush()
        RequestLogger.setEnabled(true)
        RequestLogger.logLine("new day's line")
        waitForFlush()

        // Active file should now contain only the new line.
        val newActive = File(logsDir, RequestLogger.ACTIVE_FILE_NAME)
        assertTrue(newActive.exists())
        val newText = newActive.readText()
        assertTrue("Active file should contain new day's line, got: $newText",
            newText.contains("new day's line"))

        // A rotated file should exist (logs_YYYY-MM-DD.txt or similar).
        val rotated = logsDir.listFiles()!!.filter { it.name.startsWith("logs_") }
        assertTrue("Expected at least one rotated file, got: ${logsDir.listFiles()?.map { it.name }}",
            rotated.isNotEmpty())
    }

    @Test
    fun `daily rotation does not collide with existing suffix`() = runBlocking {
        // Pre-create both logs.txt (old mtime) and the rotated name for the same day.
        val active = File(logsDir, RequestLogger.ACTIVE_FILE_NAME)
        active.writeText("old")
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getDefault()
        }.format(java.util.Date(System.currentTimeMillis() - 25L * 3600 * 1000))
        val firstRotated = File(logsDir, "logs_$day.txt")
        firstRotated.writeText("existing-day")
        val dayMs = System.currentTimeMillis() - 25L * 3600 * 1000
        active.setLastModified(dayMs)
        firstRotated.setLastModified(dayMs)

        RequestLogger.setEnabled(true)
        RequestLogger.logLine("today")
        waitForFlush()

        val rotated = logsDir.listFiles()!!.filter { it.name.startsWith("logs_$day") }
        assertTrue("Expected a deduplicated rotated name, got: ${rotated.map { it.name }}",
            rotated.size >= 2)
        // The active file should now have "today".
        assertTrue(File(logsDir, RequestLogger.ACTIVE_FILE_NAME).readText().contains("today"))
    }

    // —— cleanupLogs ———————————————————————————————————————————————

    @Test
    fun `cleanupLogs deletes old files`() = runBlocking {
        // Create three rotated files with different mtimes.
        val now = System.currentTimeMillis()
        listOf(
            "logs_2024-01-01.txt" to (now - 30L * 86_400_000),
            "logs_2024-06-01.txt" to (now - 3L * 86_400_000),
            "logs_2024-12-01.txt" to (now - 1L * 86_400_000),
        ).forEach { (name, mtime) ->
            val f = File(logsDir, name)
            f.writeText("x")
            f.setLastModified(mtime)
        }
        // The active file should never be touched.
        File(logsDir, RequestLogger.ACTIVE_FILE_NAME).writeText("active")
        File(logsDir, RequestLogger.ACTIVE_FILE_NAME).setLastModified(now)

        RequestLogger.cleanupLogs(autoDeleteDays = 7, maxSizeMB = 0)

        val names = logsDir.listFiles()!!.map { it.name }
        assertFalse("logs_2024-01-01.txt (30d) should have been deleted",
            names.contains("logs_2024-01-01.txt"))
        assertTrue("logs_2024-06-01.txt (3d) should remain (within 7d cutoff)",
            names.contains("logs_2024-06-01.txt"))
        assertTrue("logs_2024-12-01.txt (1d) should remain",
            names.contains("logs_2024-12-01.txt"))
        assertTrue("active file should never be deleted",
            names.contains(RequestLogger.ACTIVE_FILE_NAME))
    }

    @Test
    fun `cleanupLogs enforces max size by deleting oldest first`() = runBlocking {
        val now = System.currentTimeMillis()
        // 3 rotated files, each 1MB. maxSizeMB=2 should drop at least one.
        listOf(
            "logs_2024-01-01.txt" to (now - 30L * 86_400_000),
            "logs_2024-06-01.txt" to (now - 10L * 86_400_000),
            "logs_2024-12-01.txt" to (now - 2L * 86_400_000),
        ).forEach { (name, mtime) ->
            val f = File(logsDir, name)
            f.writeBytes(ByteArray(1024 * 1024))
            f.setLastModified(mtime)
        }

        RequestLogger.cleanupLogs(autoDeleteDays = 0, maxSizeMB = 2)

        val names = logsDir.listFiles()!!.map { it.name }
        // Oldest (2024-01-01) should be gone; newest (2024-12-01) should remain.
        assertFalse("Oldest should be deleted", names.contains("logs_2024-01-01.txt"))
        assertTrue("Newest should remain", names.contains("logs_2024-12-01.txt"))
    }

    @Test
    fun `cleanupLogs noop when no rotated files`() = runBlocking {
        // No rotated files; should not throw.
        RequestLogger.cleanupLogs(autoDeleteDays = 7, maxSizeMB = 50)
        // Just assert we got here.
        assertNotNull(logsDir)
    }

    // —— multiple writes serialize ———————————————————————————————————

    @Test
    fun `multiple logLines write in order without corruption`() = runBlocking {
        RequestLogger.setEnabled(true)
        repeat(50) { i ->
            RequestLogger.logLine("entry $i")
        }
        // 50 async writes behind a single Mutex on Dispatchers.IO — wait
        // longer than the per-write IO roundtrip to drain.
        waitForFlush(long = true)
        val text = File(logsDir, RequestLogger.ACTIVE_FILE_NAME).readText()
        val lines = text.split('\n').filter { it.isNotEmpty() }
        assertEquals(50, lines.size)
        // First and last sanity-check.
        assertTrue(lines.first().endsWith("entry 0"))
        assertTrue(lines.last().endsWith("entry 49"))
    }

    private suspend fun waitForFlush(long: Boolean = false) {
        delay(if (long) 1500 else 150)
    }
}

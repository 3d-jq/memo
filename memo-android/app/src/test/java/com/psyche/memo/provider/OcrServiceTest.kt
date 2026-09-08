package com.psyche.memo.provider

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Port coverage of ocr_service.dart's pure parts (prompt, wrap, cache, hash). */
class OcrServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() = OcrService.clearCache()

    @Test
    fun `prompt-less configuration skips the system turn`() {
        val withPrompt = OcrService.buildMessages("do ocr")
        assertEquals(2, withPrompt.size)
        assertEquals("system", withPrompt[0].role)
        assertEquals("do ocr", withPrompt[0].content)
        assertEquals(OcrService.DEFAULT_USER_PROMPT, withPrompt[1].content)

        val without = OcrService.buildMessages("   ")
        assertEquals(1, without.size)
        assertEquals("user", without[0].role)
    }

    @Test
    fun `ocr block wrapper matches the upstream text`() {
        val block = OcrService.wrapBlock("  hello world  ")
        assertTrue(block.startsWith("The image_file_ocr tag contains a description"))
        assertTrue(block.contains("<image_file_ocr>\nhello world\n</image_file_ocr>"))
        assertTrue(block.endsWith("\n\n"))
    }

    @Test
    fun `content hash is stable and null for missing files`() {
        val file = tmp.newFile("img.png")
        file.writeBytes(byteArrayOf(1, 2, 3))
        val first = OcrService.contentHash(file.absolutePath)
        val second = OcrService.contentHash(file.absolutePath)
        assertEquals(first, second)
        assertEquals(64, first!!.length)
        assertNull(OcrService.contentHash("/nope/missing.png"))
    }

    @Test
    fun `cache is lru bounded and bumps on hit`() {
        OcrService.cacheText("a", "1")
        OcrService.cacheText("b", "2")
        assertEquals("1", OcrService.cached("a")) // bump a to most-recent
        // Insert past the cap: the least recently used entry (b) is evicted.
        for (i in 0 until 31) OcrService.cacheText("k$i", "v$i")
        assertEquals("1", OcrService.cached("a"))
        assertNull(OcrService.cached("b"))
        assertNull(OcrService.cached("missing"))
    }
}

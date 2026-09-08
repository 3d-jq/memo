package com.psyche.memo.provider

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Port coverage of document_text_extractor.dart (text / DOCX / mime routing). */
class DocumentTextExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() = DocumentTextExtractor.clearCache()

    @Test
    fun `mime routing by extension`() {
        assertEquals(DocumentTextExtractor.PDF_MIME, DocumentTextExtractor.mimeForName("a.PDF"))
        assertEquals(DocumentTextExtractor.DOC_MIME, DocumentTextExtractor.mimeForName("a.doc"))
        assertEquals(DocumentTextExtractor.DOCX_MIME, DocumentTextExtractor.mimeForName("a.docx"))
        assertEquals("text/plain", DocumentTextExtractor.mimeForName("notes.md"))
        assertEquals("text/plain", DocumentTextExtractor.mimeForName("noextension"))
    }

    @Test
    fun `plain text fallback reads utf8 and repairs malformed bytes`() {
        val file = tmp.newFile("notes.txt")
        file.writeText("hello 世界")
        assertEquals("hello 世界", DocumentTextExtractor.extract(file.absolutePath, "text/plain"))

        val bad = tmp.newFile("bad.txt")
        bad.writeBytes(byteArrayOf(0x61, 0xFF.toByte(), 0x62))
        val text = DocumentTextExtractor.extract(bad.absolutePath, "text/plain")
        assertTrue(text.startsWith("a"))
        assertTrue(text.endsWith("b"))
    }

    @Test
    fun `docx extraction joins runs and keeps paragraph breaks`() {
        val docx = File(tmp.root, "doc.docx")
        ZipOutputStream(docx.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            val xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>Hello </w:t></w:r><w:r><w:t>world</w:t></w:r></w:p>
                    <w:p/>
                    <w:p><w:r><w:t>Second line</w:t></w:r></w:p>
                  </w:body>
                </w:document>
            """.trimIndent()
            zip.write(xml.toByteArray())
            zip.closeEntry()
        }
        val text = DocumentTextExtractor.extract(
            docx.absolutePath,
            DocumentTextExtractor.DOCX_MIME,
        )
        assertTrue(text.contains("Hello world"))
        assertTrue(text.contains("Second line"))
        assertTrue(text.lines().size >= 3)
    }

    @Test
    fun `docx without document xml reports the upstream message`() {
        val docx = File(tmp.root, "empty.docx")
        ZipOutputStream(docx.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("other.xml"))
            zip.write("<x/>".toByteArray())
            zip.closeEntry()
        }
        assertEquals(
            "[DOCX] document.xml not found",
            DocumentTextExtractor.extract(docx.absolutePath, DocumentTextExtractor.DOCX_MIME),
        )
    }

    @Test
    fun `legacy doc format is unsupported`() {
        val doc = tmp.newFile("old.doc")
        assertEquals(
            "[[DOC format (.doc) not supported for text extraction]]",
            DocumentTextExtractor.extract(doc.absolutePath, DocumentTextExtractor.DOC_MIME),
        )
    }

    @Test
    fun `missing files report the upstream marker`() {
        assertEquals(
            "[[File not found: /nope/missing.txt]]",
            DocumentTextExtractor.extract("/nope/missing.txt", "text/plain"),
        )
    }

    @Test
    fun `cache returns the same text until the file changes`() {
        val file = tmp.newFile("cached.txt")
        file.writeText("first")
        assertEquals("first", DocumentTextExtractor.extractCached(file.absolutePath, "text/plain"))
        // Same size + mtime: cached value wins (upstream stat-based cache).
        file.writeText("other")
        file.setLastModified(System.currentTimeMillis() + 5_000)
        assertEquals("other", DocumentTextExtractor.extractCached(file.absolutePath, "text/plain"))
    }
}

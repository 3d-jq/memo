package com.psyche.memo.provider

import android.content.Context
import com.psyche.memo.common.UnicodeSanitizer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.zip.ZipFile

/**
 * Port of document_text_extractor.dart: PDF / DOCX / plain-text extraction for
 * chat attachments. Results are cached by path + mtime + size exactly like
 * message_builder_service.readDocument.
 */
object DocumentTextExtractor {

    const val DOC_MIME = "application/msword"
    const val DOCX_MIME =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val PDF_MIME = "application/pdf"

    private data class CacheEntry(val text: String?, val modifiedMs: Long, val size: Long)

    private val cache = LinkedHashMap<String, CacheEntry>()

    /** PDFBox needs its resource loader initialized once per process. */
    fun init(context: Context) {
        runCatching { PDFBoxResourceLoader.init(context.applicationContext) }
    }

    /** Cached extraction; [mime] empty falls back to plain text. */
    fun extractCached(path: String, mime: String): String? {
        val file = File(path)
        if (!file.isFile) return "[[File not found: $path]]"
        val modified = file.lastModified()
        val size = file.length()
        cache[path]?.let { if (it.modifiedMs == modified && it.size == size) return it.text }
        val text = runCatching { extract(path, mime) }.getOrNull()
        cache[path] = CacheEntry(text, modified, size)
        return text
    }

    fun extract(path: String, mime: String): String = try {
        when (mime) {
            PDF_MIME -> extractPdf(path)
            DOC_MIME -> "[[DOC format (.doc) not supported for text extraction]]"
            DOCX_MIME -> extractDocx(path)
            else -> readText(path)
        }
    } catch (e: Exception) {
        "[[Failed to read file: $e]]"
    }

    private fun extractPdf(path: String): String {
        val file = File(path)
        if (!file.isFile) return "[[File not found: $path]]"
        return try {
            PDDocument.load(file).use { document ->
                val extracted = PDFTextStripper().getText(document)
                val text = UnicodeSanitizer.sanitize(extracted)
                if (text.trim().isNotEmpty()) text else "[PDF] Unable to extract text from file."
            }
        } catch (e: Exception) {
            "[[Failed to read PDF: $e]]"
        }
    }

    private fun extractDocx(path: String): String {
        val file = File(path)
        if (!file.isFile) return "[DOCX] file not found"
        return try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("word/document.xml") ?: return "[DOCX] document.xml not found"
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                factory.isNamespaceAware = false
                val doc = factory.newDocumentBuilder()
                    .parse(java.io.ByteArrayInputStream(bytes))
                val buffer = StringBuilder()
                val paragraphs = doc.getElementsByTagName("w:p")
                for (i in 0 until paragraphs.length) {
                    val texts = (paragraphs.item(i) as? org.w3c.dom.Element)
                        ?.getElementsByTagName("w:t")
                    if (texts == null || texts.length == 0) {
                        buffer.append('\n')
                        continue
                    }
                    for (j in 0 until texts.length) {
                        buffer.append(texts.item(j).textContent)
                    }
                    buffer.append('\n')
                }
                UnicodeSanitizer.sanitize(buffer.toString())
            }
        } catch (e: Exception) {
            "[[Failed to parse DOCX: $e]]"
        }
    }

    private fun readText(path: String): String {
        val file = File(path)
        if (!file.isFile) return "[[File not found: $path]]"
        return UnicodeSanitizer.sanitize(String(file.readBytes(), Charsets.UTF_8))
    }

    /** Mime by file name when the stored part has none. */
    fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> PDF_MIME
        "doc" -> DOC_MIME
        "docx" -> DOCX_MIME
        else -> "text/plain"
    }

    /** Test hook. */
    internal fun clearCache() = cache.clear()
}

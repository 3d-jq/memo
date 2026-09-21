package com.psyche.memo.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 上传目录命名/去重（file_import_helper.dart `copyXFile` + `upload_dedupe.dart`）：
 * 落盘用**原始文件名**、撞名才 `name(1).ext`、同字节复用已有文件。此前写成
 * `att_<millis>_<uuid>_<name>`，存储页与附件卡显示的就是这串内部名。
 */
class AttachmentStoreTest {

    private fun dir(): File = Files.createTempDirectory("memo-upload-test").toFile()

    @Test
    fun `stores under the original file name`() {
        val dir = dir()
        val saved = AttachmentStore.store(dir, "hello".toByteArray(), "report.pdf")!!
        assertEquals("report.pdf", saved.name)
        assertEquals("hello", saved.readText())
    }

    @Test
    fun `identical bytes with the same name reuse the stored file`() {
        val dir = dir()
        val first = AttachmentStore.store(dir, "same".toByteArray(), "notes.txt")!!
        val second = AttachmentStore.store(dir, "same".toByteArray(), "notes.txt")!!
        assertEquals(first.absolutePath, second.absolutePath)
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test
    fun `different bytes never overwrite - they take a versioned name`() {
        val dir = dir()
        AttachmentStore.store(dir, "v1".toByteArray(), "notes.txt")!!
        val second = AttachmentStore.store(dir, "v2".toByteArray(), "notes.txt")!!
        assertEquals("notes(1).txt", second.name)
        assertEquals("v2", second.readText())
        val third = AttachmentStore.store(dir, "v3".toByteArray(), "notes.txt")!!
        assertEquals("notes(2).txt", third.name)
    }

    @Test
    fun `versioned families dedupe too`() {
        val dir = dir()
        val original = AttachmentStore.store(dir, "A".toByteArray(), "notes.txt")!!
        AttachmentStore.store(dir, "B".toByteArray(), "notes.txt")!!
        // 「A」在 notes.txt 里（不是 notes(1).txt）——原版按「同名族 + 同字节」找。
        assertEquals(original.absolutePath, AttachmentStore.store(dir, "A".toByteArray(), "notes.txt")!!.absolutePath)
    }

    @Test
    fun `names without an extension keep the version suffix clean`() {
        val dir = dir()
        assertEquals("notes", AttachmentStore.store(dir, "a".toByteArray(), "notes")!!.name)
        assertEquals("notes(1)", AttachmentStore.store(dir, "b".toByteArray(), "notes")!!.name)
    }

    @Test
    fun `dotfiles are not split into base and extension`() {
        val dir = dir()
        val saved = AttachmentStore.store(dir, "x".toByteArray(), ".env")!!
        assertEquals(".env", saved.name)
        assertEquals(".env(1)", AttachmentStore.store(dir, "y".toByteArray(), ".env")!!.name)
    }

    @Test
    fun `a same-named file with different content is not treated as a version`() {
        val dir = dir()
        // 用户自己就有一个 notes(1).txt，但我们从没写过 notes.txt：不该被当成同族而复用。
        File(dir, "notes(1).txt").writeText("mine")
        val saved = AttachmentStore.store(dir, "mine".toByteArray(), "notes.txt")!!
        assertEquals("notes.txt", saved.name)
        assertTrue(File(dir, "notes(1).txt").exists())
    }

    @Test
    fun `empty directory starts clean`() {
        val dir = dir()
        val saved = AttachmentStore.store(dir, ByteArray(0), "empty.bin")
        assertNotNull(saved)
        assertFalse(saved!!.readBytes().isNotEmpty())
    }

    @Test
    fun `non-ascii names survive intact`() {
        // 用户实测：文档名里的中文被清洗成了下划线（Qt-C______.docx）。
        val dir = dir()
        val saved = AttachmentStore.store(dir, "x".toByteArray(), "Qt-C学习笔记（第1版）.docx")!!
        assertEquals("Qt-C学习笔记（第1版）.docx", saved.name)
        assertEquals("学习笔记", AttachmentStore.safeFileName("Qt-C学习笔记（第1版）.docx").substringAfter("Qt-C").substringBefore("（"))
    }

    @Test
    fun `only path separators and control characters are neutralised`() {
        assertEquals("a_b", AttachmentStore.safeFileName("a/b"))
        assertEquals("a_b", AttachmentStore.safeFileName("a\\b"))
        assertEquals("attachment", AttachmentStore.safeFileName("   "))
        // 空格 / '#' / '&' / emoji 都保留（原版直接沿用文件名）。
        assertEquals("我的 报告 #1 😀.pdf", AttachmentStore.safeFileName("我的 报告 #1 😀.pdf"))
    }

    @Test
    fun `overlong names are truncated without losing the extension`() {
        val name = "很长的名字".repeat(60) + ".txt"
        val safe = AttachmentStore.safeFileName(name)
        assertTrue(safe.endsWith(".txt"))
        assertTrue(safe.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue(safe.startsWith("很长的名字"))
    }
}

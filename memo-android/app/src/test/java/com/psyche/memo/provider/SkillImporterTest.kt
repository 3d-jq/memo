package com.psyche.memo.provider

import com.psyche.memo.common.skill.SkillStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 技能导入（RikkaHub `SkillsVM` 的 import 分支）。zip 的条目规范化与「取最外层技能」
 * 是安全相关的纯逻辑，用临时目录 + 内存 zip 覆盖。
 */
class SkillImporterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = SkillStore(File(temp.root, "skills"))

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun skillMd(name: String, description: String = "$name desc", body: String = "Body") =
        "---\nname: $name\ndescription: $description\n---\n\n$body\n"

    // ---- 纯逻辑 ----

    @Test
    fun normalizeRejectsTraversalAndStripsPrefixes() {
        assertEquals("a/b.md", SkillImporter.normalizeZipEntryPath("/a/b.md"))
        assertEquals("a/b.md", SkillImporter.normalizeZipEntryPath("a\\b.md"))
        assertEquals("a/b.md", SkillImporter.normalizeZipEntryPath("./a/./b.md"))
        assertNull(SkillImporter.normalizeZipEntryPath("../evil.md"))
        assertNull(SkillImporter.normalizeZipEntryPath("a/../../evil.md"))
        assertNull(SkillImporter.normalizeZipEntryPath("/"))
    }

    /** zip 里嵌套技能时只取最外层（上游 isInsideNestedSkill）。 */
    @Test
    fun nestedSkillsKeepOnlyTheOutermost() {
        assertEquals(listOf("a"), SkillImporter.selectSkillBases(listOf("a/SKILL.md", "a/b/SKILL.md")))
        assertEquals(
            listOf("a", "c"),
            SkillImporter.selectSkillBases(listOf("a/SKILL.md", "a/b/SKILL.md", "c/SKILL.md")),
        )
        assertEquals(listOf(""), SkillImporter.selectSkillBases(listOf("SKILL.md")))
    }

    @Test
    fun zipDetectionUsesExtensionOrMagic() {
        assertTrue(SkillImporter.isZip("skills.zip", ByteArray(0)))
        assertTrue(SkillImporter.isZip("noext", byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0)))
        assertFalse(SkillImporter.isZip("SKILL.md", "---\n".toByteArray()))
    }

    // ---- 真实落盘 ----

    @Test
    fun importsASingleMarkdownFile() {
        val store = store()
        val names = SkillImporter.import(store, "SKILL.md", skillMd("pdf-tools").toByteArray())
        assertEquals(listOf("pdf-tools"), names)
        assertEquals(listOf("pdf-tools"), store.listSkills().map { it.name })
    }

    @Test
    fun markdownWithoutNameOrDescriptionIsRejected() {
        val store = store()
        listOf(
            "---\ndescription: no name\n---\nbody",
            "---\nname: no-desc\n---\nbody",
            "# no frontmatter",
        ).forEach { content ->
            val failed = runCatching { SkillImporter.import(store, "SKILL.md", content.toByteArray()) }
            assertTrue("应当抛 SkillImportException: $content", failed.exceptionOrNull() is SkillImportException)
        }
        assertTrue(store.listSkills().isEmpty())
    }

    @Test
    fun importsEverySkillInAZipWithItsCompanionFiles() {
        val store = store()
        val bytes = zipOf(
            "a/SKILL.md" to skillMd("skill-a"),
            "a/examples/basic.md" to "example a",
            "b/SKILL.md" to skillMd("skill-b"),
        )
        assertEquals(listOf("skill-a", "skill-b"), SkillImporter.import(store, "pack.zip", bytes))
        assertEquals("example a", store.resolveSkillFile("skill-a", "examples/basic.md")!!.readText())
        assertEquals(
            listOf("SKILL.md", "examples/basic.md"),
            store.listFiles("skill-a").map { it.relativePath },
        )
    }

    /** 外层技能不该把嵌套技能的文件吞进来。 */
    @Test
    fun nestedSkillFilesStayWithTheirOwnSkill() {
        val store = store()
        val bytes = zipOf(
            "outer/SKILL.md" to skillMd("outer"),
            "outer/inner/SKILL.md" to skillMd("inner"),
            "outer/inner/notes.md" to "inner only",
        )
        assertEquals(listOf("outer"), SkillImporter.import(store, "p.zip", bytes))
        assertEquals(listOf("SKILL.md"), store.listFiles("outer").map { it.relativePath })
    }

    @Test
    fun zipWithoutSkillMdIsRejected() {
        val store = store()
        val bytes = zipOf("readme.md" to "nothing here")
        val failed = runCatching { SkillImporter.import(store, "p.zip", bytes) }
        assertTrue(failed.exceptionOrNull() is SkillImportException)
    }

    /** zip 条目里的 `..` 不能把文件写到技能目录之外。 */
    @Test
    fun zipEntriesCannotEscapeTheSkillsRoot() {
        val store = store()
        val bytes = zipOf(
            "a/SKILL.md" to skillMd("safe"),
            "../evil.md" to "escaped",
        )
        assertEquals(listOf("safe"), SkillImporter.import(store, "p.zip", bytes))
        assertFalse(File(temp.root, "evil.md").exists())
    }
}

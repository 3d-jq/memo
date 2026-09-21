package com.psyche.memo.common.skill

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 技能仓库的完整文件行为（RikkaHub `SkillManager` 文件部分的 1:1 移植）。
 * 用临时目录跑，所以不需要 Robolectric —— 这也是把本类从 `Context` 里拆出来的原因。
 */
class SkillStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = SkillStore(File(temp.root, "skills"))

    private fun skillMd(name: String, description: String = "does $name things", body: String = "Body") =
        "---\nname: $name\ndescription: $description\n---\n\n$body\n"

    @Test
    fun emptyRootListsNothingAndCreatesTheDirectory() {
        val store = store()
        assertTrue(store.listSkills().isEmpty())
        assertTrue(File(temp.root, "skills").isDirectory)
    }

    @Test
    fun saveThenList() {
        val store = store()
        val saved = store.saveSkill("pdf-tools", skillMd("pdf-tools"))
        assertNotNull(saved)
        assertEquals("pdf-tools", saved!!.name)
        assertEquals("does pdf-tools things", saved.description)
        assertEquals(listOf("pdf-tools"), store.listSkills().map { it.name })
    }

    /** 上游「先写盘再解析」会在磁盘留下永远解析不出来的目录；我们改成先校验。 */
    @Test
    fun saveRejectsInvalidContentWithoutLeavingJunkOnDisk() {
        val store = store()
        assertNull(store.saveSkill("no-name", "---\ndescription: only description\n---\nbody"))
        assertNull(store.saveSkill("no-description", "---\nname: no-description\n---\nbody"))
        assertNull(store.saveSkill("no-frontmatter", "# just markdown"))
        assertNull(store.saveSkill("../escape", skillMd("escape")))

        assertTrue(store.listSkills().isEmpty())
        assertFalse(File(temp.root, "skills/no-name").exists())
        assertFalse(File(temp.root, "skills/no-description").exists())
        assertFalse(File(temp.root, "skills/no-frontmatter").exists())
        assertFalse(File(temp.root, "escape").exists())
    }

    @Test
    fun saveOverwritesExistingSkill() {
        val store = store()
        store.saveSkill("a", skillMd("a", description = "first"))
        store.saveSkill("a", skillMd("a", description = "second", body = "New body"))
        assertEquals("second", store.listSkills().single().description)
        assertEquals("New body", store.readSkillBody("a")?.trim())
    }

    /** 覆盖保存时不能留下 staging/backup 临时目录。 */
    @Test
    fun overwriteLeavesNoTemporaryDirectories() {
        val store = store()
        store.saveSkill("a", skillMd("a"))
        store.saveSkill("a", skillMd("a", body = "again"))
        val leftovers = store.skillsDir().listFiles()!!.filter { it.name.startsWith(".") }
        assertTrue("临时目录应被清干净，实际: ${leftovers.map { it.name }}", leftovers.isEmpty())
    }

    @Test
    fun readBodyStripsFrontmatterAndReadContentKeepsIt() {
        val store = store()
        store.saveSkill("a", skillMd("a", body = "# Heading\ntext"))
        assertEquals("# Heading\ntext", store.readSkillBody("a")?.trim())
        assertTrue(store.readSkillContent("a")!!.startsWith("---"))
    }

    @Test
    fun readOfMissingSkillIsNull() {
        val store = store()
        assertNull(store.readSkillBody("nope"))
        assertNull(store.readSkillContent("nope"))
    }

    @Test
    fun atomicSaveWritesCompanionFilesAndRequiresSkillMd() {
        val store = store()
        val ok = store.saveSkillFilesAtomically(
            "multi",
            mapOf(
                "SKILL.md" to skillMd("multi"),
                "examples/basic.md" to "example",
            ),
        )
        assertTrue(ok)
        assertEquals("example", store.readSkillFileText("multi", "examples/basic.md"))

        // 没有 SKILL.md 的一批文件整体不落盘。
        assertFalse(store.saveSkillFilesAtomically("broken", mapOf("notes.md" to "x")))
        // skillDir 是字典序解析出来的路径，不存在也不为 null —— 判据要看 exists()。
        assertFalse(store.skillDir("broken")!!.exists())
    }

    @Test
    fun listsAreSortedByNameRegardlessOfCreationOrder() {
        val store = store()
        listOf("zeta", "alpha", "mid").forEach { store.saveSkill(it, skillMd(it)) }
        assertEquals(listOf("alpha", "mid", "zeta"), store.listSkills().map { it.name })
    }

    @Test
    fun listSkipsDirectoriesWithoutOrWithBrokenSkillMd() {
        val store = store()
        store.saveSkill("good", skillMd("good"))
        store.skillsDir().resolve("no-skill-md").mkdirs()
        store.skillsDir().resolve("broken").apply { mkdirs() }
            .resolve("SKILL.md").writeText("---\ndescription: no name\n---\n")

        assertEquals(listOf("good"), store.listSkills().map { it.name })
    }

    @Test
    fun deleteSkillRemovesTheWholeDirectory() {
        val store = store()
        store.saveSkill("a", skillMd("a"))
        assertTrue(store.deleteSkill("a"))
        assertTrue(store.listSkills().isEmpty())
        assertFalse(store.skillDir("a")!!.exists())
        assertFalse(store.deleteSkill("a"))
    }

    @Test
    fun saveAndDeleteNestedFileInsideSkill() {
        val store = store()
        store.saveSkill("a", skillMd("a"))
        assertTrue(store.saveSkillFile("a", "examples/basic.md", "hello"))
        assertEquals("hello", store.readSkillFileText("a", "examples/basic.md"))
        assertTrue(store.deleteSkillFile("a", "examples/basic.md"))
        assertNull(store.readSkillFileText("a", "examples/basic.md"))
    }

    @Test
    fun nestedFileWritesCannotEscapeTheSkillDirectory() {
        val store = store()
        store.saveSkill("a", skillMd("a"))
        assertFalse(store.saveSkillFile("a", "../outside.md", "nope"))
        assertFalse(store.deleteSkillFile("a", "../outside.md"))
        assertNull(store.resolveSkillFile("a", "../outside.md"))
        assertFalse(File(temp.root, "skills/outside.md").exists())
    }

    /** 详情页的文件表：相对路径 + 字节数 + 缩进层级，按路径排序。 */
    @Test
    fun listFilesReturnsSortedRelativePathsWithDepth() {
        val store = store()
        store.saveSkillFilesAtomically(
            "a",
            mapOf(
                "SKILL.md" to skillMd("a"),
                "examples/basic.md" to "hi",
                "examples/deep/nested.md" to "deep",
            ),
        )
        val files = store.listFiles("a")
        assertEquals(
            listOf("SKILL.md", "examples/basic.md", "examples/deep/nested.md"),
            files.map { it.relativePath },
        )
        assertEquals(0, files[0].depth)
        assertEquals(1, files[1].depth)
        assertEquals(2, files[2].depth)
        assertEquals(2L, files[1].sizeBytes)
    }

    @Test
    fun listFilesOfAMissingSkillIsEmpty() {
        assertTrue(store().listFiles("nope").isEmpty())
    }

    private fun SkillStore.readSkillFileText(name: String, relativePath: String): String? =
        resolveSkillFile(name, relativePath)?.takeIf { it.isFile }?.readText()
}

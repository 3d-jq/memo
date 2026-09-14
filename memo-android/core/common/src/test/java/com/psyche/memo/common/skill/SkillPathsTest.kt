package com.psyche.memo.common.skill

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 技能名与技能内相对路径的边界检查。技能名来自第三方 frontmatter，相对路径来自**模型**
 * （`use_skill` 的 `path`），两者都是不可信输入 —— 这里锁住「必须落在根之内」。
 */
class SkillPathsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val root: File get() = temp.root

    @Test
    fun resolvesPlainSkillName() {
        val dir = SkillPaths.resolveSkillDir(root, "pdf-tools")
        assertNotNull(dir)
        assertEquals(File(root.canonicalFile, "pdf-tools"), dir)
    }

    @Test
    fun rejectsTraversalAndSeparators() {
        assertNull(SkillPaths.resolveSkillDir(root, ""))
        assertNull(SkillPaths.resolveSkillDir(root, "   "))
        assertNull(SkillPaths.resolveSkillDir(root, "."))
        assertNull(SkillPaths.resolveSkillDir(root, ".."))
        assertNull(SkillPaths.resolveSkillDir(root, "../evil"))
        assertNull(SkillPaths.resolveSkillDir(root, "a/b"))
        assertNull(SkillPaths.resolveSkillDir(root, "a\\b"))
        assertNull(SkillPaths.resolveSkillDir(root, "/abs"))
    }

    @Test
    fun resolvesFileInsideSkillDir() {
        val dir = File(root, "skill").apply { mkdirs() }
        assertNotNull(SkillPaths.resolveSkillFile(dir, "SKILL.md"))
        assertNotNull(SkillPaths.resolveSkillFile(dir, "examples/basic.md"))
    }

    @Test
    fun rejectsFileEscapingSkillDir() {
        val dir = File(root, "skill").apply { mkdirs() }
        assertNull(SkillPaths.resolveSkillFile(dir, ""))
        assertNull(SkillPaths.resolveSkillFile(dir, "../outside.md"))
        assertNull(SkillPaths.resolveSkillFile(dir, "examples/../../outside.md"))
    }
}

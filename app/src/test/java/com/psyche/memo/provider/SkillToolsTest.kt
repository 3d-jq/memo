package com.psyche.memo.provider

import com.psyche.memo.common.skill.SkillMetadata
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `use_skill` 的工具面（RikkaHub `SkillsTools.kt` 的移植）：暴露门控、系统提示词块、
 * 以及「模型只能读到助手启用且真实存在的技能」这条边界。
 */
class SkillToolsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun skill(name: String, body: String = "Do $name things", description: String = "$name desc"): SkillMetadata {
        val dir = File(temp.root, name).apply { mkdirs() }
        dir.resolve("SKILL.md").writeText("---\nname: $name\ndescription: $description\n---\n\n$body\n")
        return SkillMetadata(name = name, description = description, skillDir = dir)
    }

    @Test
    fun noEnabledSkillsMeansNoToolAtAll() {
        val all = listOf(skill("a"), skill("b"))
        assertTrue(SkillTools.buildDefinitions(emptyList(), all).isEmpty())
        assertNull(SkillTools.systemPromptBlock(emptyList(), all))
    }

    /** 助手启用了但磁盘上不存在的名字（幽灵名）不能换来一颗工具。 */
    @Test
    fun enabledButMissingSkillDoesNotExposeTheTool() {
        val all = listOf(skill("a"))
        assertTrue(SkillTools.buildDefinitions(listOf("ghost"), all).isEmpty())
    }

    @Test
    fun enabledSkillExposesUseSkillWithItsSchema() {
        val all = listOf(skill("a"), skill("b"))
        val defs = SkillTools.buildDefinitions(listOf("b"), all)
        assertEquals(1, defs.size)
        assertEquals("use_skill", defs.single().name)
        assertTrue(defs.single().description.contains("Load and apply a skill"))
        assertTrue(defs.single().inputSchemaJson.contains("\"required\":[\"name\"]"))
        assertTrue(defs.single().inputSchemaJson.contains("\"path\""))
    }

    @Test
    fun systemPromptBlockListsOnlyAvailableSkills() {
        val all = listOf(skill("a"), skill("b"))
        val block = SkillTools.systemPromptBlock(listOf("a", "ghost"), all)!!
        assertTrue(block.contains("<available_skills>"))
        assertTrue(block.contains("<name>a</name>"))
        assertFalse(block.contains("<name>b</name>"))
        assertFalse(block.contains("ghost"))
    }

    @Test
    fun executeWithoutPathReturnsTheBodyNotTheFrontmatter() {
        val a = skill("a", body = "# Heading\ntext")
        val outcome = SkillTools.execute(a, null)
        assertTrue(outcome is SkillTools.Outcome.Success)
        assertEquals("# Heading\ntext", (outcome as SkillTools.Outcome.Success).content.trim())
    }

    @Test
    fun executeWithPathReturnsThatFile() {
        val a = skill("a")
        File(a.skillDir, "examples").mkdirs()
        File(a.skillDir, "examples/basic.md").writeText("example body")
        val outcome = SkillTools.execute(a, "examples/basic.md")
        assertEquals("example body", (outcome as SkillTools.Outcome.Success).content)
    }

    /** 模型给的路由不能逃出技能目录。 */
    @Test
    fun executeRejectsPathsOutsideTheSkillDirectory() {
        val a = skill("a")
        File(temp.root, "outside.md").writeText("secret")
        val escaped = SkillTools.execute(a, "../outside.md")
        assertTrue(escaped is SkillTools.Outcome.Failure)
        assertEquals("path_outside_skill", (escaped as SkillTools.Outcome.Failure).error)
    }

    @Test
    fun executeReportsAMissingFileInsideTheSkill() {
        val a = skill("a")
        val outcome = SkillTools.execute(a, "examples/nope.md")
        assertTrue(outcome is SkillTools.Outcome.Failure)
        assertEquals("file_not_found", (outcome as SkillTools.Outcome.Failure).error)
    }

    @Test
    fun executeReportsAMissingSkillFile() {
        val dir = File(temp.root, "gone").apply { mkdirs() }
        val outcome = SkillTools.execute(SkillMetadata("gone", "d", null, dir), null)
        assertTrue(outcome is SkillTools.Outcome.Failure)
        assertEquals("skill_not_found", (outcome as SkillTools.Outcome.Failure).error)
    }
}

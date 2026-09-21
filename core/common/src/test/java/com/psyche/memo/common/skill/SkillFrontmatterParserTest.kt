package com.psyche.memo.common.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SKILL.md frontmatter 解析（RikkaHub `SkillFrontmatterParser` 的 1:1 移植）。
 * 技能文件是第三方内容，所以重点覆盖「脏输入不能抛、也不能解出半个技能」。
 */
class SkillFrontmatterParserTest {

    @Test
    fun parsesNameDescriptionAndCompatibility() {
        val content = """
            ---
            name: pdf-tools
            description: Extract text from PDFs
            compatibility: android
            ---

            # Body
        """.trimIndent()

        val fm = SkillFrontmatterParser.parse(content)
        assertEquals("pdf-tools", fm["name"])
        assertEquals("Extract text from PDFs", fm["description"])
        assertEquals("android", fm["compatibility"])
    }

    @Test
    fun missingFrontmatterYieldsEmptyAndKeepsContentAsBody() {
        val content = "# Just markdown\nno frontmatter here"
        assertEquals(null, SkillFrontmatterParser.parse(content)["name"])
        assertEquals(content, SkillFrontmatterParser.extractBody(content))
    }

    @Test
    fun unclosedFrontmatterIsEmpty() {
        val content = "---\nname: broken\ndescription: never closed\n"
        assertEquals(null, SkillFrontmatterParser.parse(content)["name"])
        // 正文抽取同样回落原样 —— 不能把整份文件当正文吞掉再截断。
        assertEquals(content, SkillFrontmatterParser.extractBody(content))
    }

    @Test
    fun emptyFrontmatterBlockIsEmpty() {
        assertEquals(null, SkillFrontmatterParser.parse("---\n---\nbody")["name"])
    }

    @Test
    fun handlesCrlfLineEndings() {
        val fm = SkillFrontmatterParser.parse("---\r\nname: win\r\ndescription: crlf\r\n---\r\nbody")
        assertEquals("win", fm["name"])
        assertEquals("crlf", fm["description"])
    }

    /** 描述里带冒号必须靠引号保住（真实技能里很常见）。 */
    @Test
    fun quotedValueKeepsColon() {
        val fm = SkillFrontmatterParser.parse(
            "---\nname: x\ndescription: \"Use when: the user asks\"\n---\nbody",
        )
        assertEquals("Use when: the user asks", fm["description"])
    }

    /** 折叠块标量（长描述写成多行）—— 手撸的 key:value 解析器会在这里翻车。 */
    @Test
    fun foldedBlockScalarDescription() {
        val content = """
            ---
            name: long
            description: >-
              First line
              second line
            ---
            body
        """.trimIndent()

        assertEquals("First line second line", SkillFrontmatterParser.parse(content)["description"])
    }

    /** 重复键在构造时就该被拒（LoaderOptions.isAllowDuplicateKeys = false）。 */
    @Test
    fun duplicateKeysDoNotParse() {
        assertNull(SkillFrontmatterParser.parse("---\nname: a\nname: b\n---\nbody")["name"])
    }

    @Test
    fun invalidYamlDoesNotThrow() {
        assertNull(SkillFrontmatterParser.parse("---\nname: [unclosed\n---\nbody")["name"])
        assertNull(SkillFrontmatterParser.parse("---\n\t- bad indent\n---\nbody")["name"])
    }

    /** 非字符串值（数字/布尔/列表）按取不到处理，与上游 `as? String` 一致。 */
    @Test
    fun nonStringValuesAreTreatedAsMissing() {
        val fm = SkillFrontmatterParser.parse("---\nname: 42\ndescription: true\n---\nbody")
        assertNull(fm["name"])
        assertNull(fm["description"])
    }

    @Test
    fun extractBodyDropsFrontmatterAndLeadingBlankLines() {
        val body = SkillFrontmatterParser.extractBody(
            "---\nname: x\ndescription: y\n---\n\n\n# Title\ntext",
        )
        assertEquals("# Title\ntext", body)
    }

    @Test
    fun extractBodyOnEmptyFrontmatterStillReturnsBody() {
        assertTrue(SkillFrontmatterParser.extractBody("---\nname: x\ndescription: y\n---\n").isEmpty())
    }
}

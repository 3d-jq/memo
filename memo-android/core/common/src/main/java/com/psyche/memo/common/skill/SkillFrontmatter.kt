package com.psyche.memo.common.skill

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * Agent Skills 的 `SKILL.md` frontmatter 解析 —— 1:1 移植 RikkaHub 的
 * `data/files/SkillFrontmatterParser.kt`（同 AGPL-3.0）。
 *
 * 格式：文件以 `---` 开头，到下一行 `---` 为止是 YAML frontmatter，其余是正文。
 *
 * 用 snakeyaml 的 [SafeConstructor] + 收紧的 [LoaderOptions]（禁止重复键、限制别名数、
 * 嵌套深度与码点数）—— 与上游逐项一致。技能文件是**第三方内容**（用户从 GitHub/文件
 * 导入），不设这些闸门就会被 YAML 别名膨胀打爆。
 */
object SkillFrontmatterParser {

    /** 与上游同款：只认行首的 `---`（兼容 CRLF）。 */
    private val frontmatterEndRegex = Regex("""\r?\n---(?:\r?\n|$)""")

    fun parse(content: String): SkillFrontmatter {
        if (!content.startsWith("---")) return SkillFrontmatter.Empty
        val endRange = findFrontmatterEndRange(content) ?: return SkillFrontmatter.Empty
        val yamlContent = content.substring(3, endRange.first).trim()
        if (yamlContent.isEmpty()) return SkillFrontmatter.Empty

        return runCatching {
            val values = createYaml().load<Any?>(yamlContent) as? Map<*, *>
                ?: return SkillFrontmatter.Empty
            SkillFrontmatter(
                values.entries.mapNotNull { (key, value) ->
                    (key as? String)?.let { it to value }
                }.toMap(),
            )
        }.getOrDefault(SkillFrontmatter.Empty)
    }

    /** frontmatter 之后的正文 —— `use_skill` 交给模型的就是它。 */
    fun extractBody(content: String): String {
        if (!content.startsWith("---")) return content
        val endRange = findFrontmatterEndRange(content) ?: return content
        return content.substring(endRange.last + 1).trimStart('\r', '\n')
    }

    private fun findFrontmatterEndRange(content: String): IntRange? {
        if (!content.startsWith("---")) return null
        return frontmatterEndRegex.find(content, startIndex = 3)?.range
    }

    private fun createYaml(): Yaml {
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 50
            nestingDepthLimit = 50
            codePointLimit = 1_000_000
        }
        return Yaml(SafeConstructor(options))
    }
}

/** frontmatter 的只读视图；非字符串值按「取不到」处理（与上游一致）。 */
class SkillFrontmatter internal constructor(
    private val values: Map<String, Any?>,
) {
    operator fun get(key: String): String? = values[key] as? String

    companion object {
        internal val Empty = SkillFrontmatter(emptyMap())
    }
}

package com.psyche.memo.provider

import com.psyche.memo.common.skill.SkillFrontmatterParser
import com.psyche.memo.common.skill.SkillMetadata
import com.psyche.memo.common.skill.SkillPaths
import com.psyche.memo.llm.client.LlmToolSpec
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Agent Skills 的工具面 —— 1:1 移植 RikkaHub `data/ai/tools/SkillsTools.kt`
 * （`createSkillTools`）。
 *
 * 与上游的三段结构一致：
 *  1. **工具定义** [buildDefinitions]：助手启用的技能里、磁盘上确实存在的那些才暴露
 *     `use_skill`；一个都没有就整颗不提供（不占模型的工具预算）。
 *  2. **系统提示词块** [systemPromptBlock]：把可用技能的名字+描述以 `<available_skills>`
 *     注入系统提示词（上游 `Tool.systemPrompt` 的等价物 —— Memo 的 `LlmToolSpec` 没有
 *     那个字段，所以挂在 `ChatViewModel.buildSystemPromptParts` 里）。
 *  3. **执行** [execute]：默认返回 `SKILL.md` 的正文（frontmatter 之后），带 `path` 时
 *     返回技能目录内的那个文件。路径一律过 [SkillPaths] 的边界检查。
 */
object SkillTools {

    const val USE_SKILL = "use_skill"

    val ALL_TOOL_NAMES = setOf(USE_SKILL)

    /** 与上游逐字一致。 */
    private val DESCRIPTION = """
        Load and apply a skill to get specialized instructions or capabilities.
        Call this tool when the user's request matches one of the available skills.
    """.trimIndent()

    /** 助手启用 ∩ 磁盘存在 —— 顺序跟磁盘列表走，保证给模型的清单稳定。 */
    fun availableSkills(enabledSkills: Collection<String>, allSkills: List<SkillMetadata>): List<SkillMetadata> =
        allSkills.filter { it.name in enabledSkills }

    fun buildDefinitions(
        enabledSkills: Collection<String>,
        allSkills: List<SkillMetadata>,
    ): List<LlmToolSpec> {
        if (availableSkills(enabledSkills, allSkills).isEmpty()) return emptyList()
        return catalogDefinitions()
    }

    /**
     * 不依赖「助手开了哪些技能 / 磁盘上有没有」的定义副本 ——
     * 设置 →「工具描述」的工具目录要用它列条目（那边是全局一份，不该跟着某个助手变）。
     */
    fun catalogDefinitions(): List<LlmToolSpec> = listOf(
        LlmToolSpec(
            name = USE_SKILL,
            description = DESCRIPTION,
            inputSchemaJson = parametersJson(),
        ),
    )

    /** 上游 `Tool.systemPrompt` 的等价物；没有可用技能时返回 null（不注入空块）。 */
    fun systemPromptBlock(
        enabledSkills: Collection<String>,
        allSkills: List<SkillMetadata>,
    ): String? {
        val available = availableSkills(enabledSkills, allSkills)
        if (available.isEmpty()) return null
        return buildString {
            appendLine("**Skills**")
            appendLine(
                "You have access to the following skills. Use the `use_skill` tool to load " +
                    "a skill's instructions when the user's request matches.",
            )
            appendLine("<available_skills>")
            available.forEach { skill ->
                appendLine("  <skill>")
                appendLine("    <name>${skill.name}</name>")
                appendLine("    <description>${skill.description}</description>")
                appendLine("  </skill>")
            }
            append("</available_skills>")
            appendLine()
        }
    }

    /** `use_skill` 的参数 schema（上游 `InputSchema.Obj`）。 */
    fun parametersJson(): String = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("name", buildJsonObject {
                put("type", "string")
                put("description", "The name of the skill to use")
            })
            put("path", buildJsonObject {
                put("type", "string")
                put(
                    "description",
                    "Optional relative path to a file inside the skill directory. Omit to read " +
                        "the default SKILL.md instructions. Only use paths extracted from " +
                        "Markdown links in the SKILL.md content. Do NOT guess or infer paths.",
                )
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("name")) })
    }.toString()

    /**
     * 执行一次 `use_skill`。失败不抛异常 —— 调用方（`ToolHandler`）要把错误原样讲给模型，
     * 让它自己改参数或换路。
     */
    fun execute(skill: SkillMetadata, path: String?): Outcome {
        if (path.isNullOrBlank()) {
            val skillFile = skill.skillFile
            if (!skillFile.isFile) return Outcome.Failure("skill_not_found", "Skill '${skill.name}' not found")
            return Outcome.Success(SkillFrontmatterParser.extractBody(skillFile.readText()))
        }
        val target = SkillPaths.resolveSkillFile(skill.skillDir, path)
            ?: return Outcome.Failure("path_outside_skill", "Path '$path' is outside the skill directory")
        if (!target.isFile) return Outcome.Failure("file_not_found", "File '$path' not found in skill '${skill.name}'")
        return Outcome.Success(target.readText())
    }

    sealed interface Outcome {
        data class Success(val content: String) : Outcome
        data class Failure(val error: String, val message: String) : Outcome
    }
}

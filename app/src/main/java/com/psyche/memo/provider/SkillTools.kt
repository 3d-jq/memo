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

    /**
     * 空清单的「墓碑」句 —— 系统提示词的目录块与 `skill_not_available` 报错**共用**这一份，
     * 抄成两份就会漂移（本工程不止一次栽在手抄第二份表上）。
     *
     * 出处：deepseek-harness `packages/skill/tool-skill/src/index.ts` 的
     * `renderCatalogUpdate`（空清单分支）。
     */
    internal const val NO_SKILLS_TOMBSTONE =
        "No skills are currently available through the `$USE_SKILL` tool. " +
            "Do not use names from earlier skill catalogs."

    /** 与上游逐字一致。 */
    /**
     * 调用引导 —— RikkaHub 原文只有一句（"Call this tool when the user's request matches
     * one of the available skills."），实测模型（DeepSeek）经常**不加载技能就直接自己编**，
     * 用户 2026-09-23「每次让他安装 skill，他都不会按照这个 create skill 的方法走」。
     * 这里按 deepseek-harness 的 skill 目录文案（`packages/skill/tool-skill/src/index.ts`
     * 渲染 `<available_skills>` 那段）补上：**动手前**先加载、命中多个全加载、
     * 只有摘要时别照着猜。
     */
    private val DESCRIPTION = """
        Load and apply a skill to get specialized instructions or capabilities.
        Call this tool when the user's request matches one of the available skills, or when the user names a skill, before doing the task work.
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

    /**
     * 上游 `Tool.systemPrompt` 的等价物。
     *
     * 清单空着时**默认什么都不递**（多数助手根本没有技能，白占上下文）。但 [catalogWasUsed]
     * 为真 —— 这段对话里模型曾经成功调用过 `use_skill` —— 就必须立一块**墓碑**：它自己
     * 历史里那次成功的名字还在，用户中途删掉/关掉技能后它照着旧名字接着调，每轮都撞
     * `skill_not_available`。没有这句，它无从知道清单已经空了。
     */
    fun systemPromptBlock(
        enabledSkills: Collection<String>,
        allSkills: List<SkillMetadata>,
        catalogWasUsed: Boolean = false,
    ): String? {
        val available = availableSkills(enabledSkills, allSkills)
        if (available.isEmpty()) {
            if (!catalogWasUsed) return null
            return "**Skills**\n$NO_SKILLS_TOMBSTONE"
        }
        return buildString {
            appendLine("**Skills**")
            // 三句硬要求照 deepseek-harness（`tool-skill/src/index.ts` 渲染目录那段的收尾语）：
            // 「动手前先加载」「命中的全加载」「只有摘要、没加载就别照着猜」。
            // 原来只有一句 "when the user's request matches"，模型（DeepSeek）基本不触发 ——
            // 用户 2026-09-23「每次让他安装 skill，他都不会按照这个 create skill 的方法走」。
            appendLine(
                "If the user names a skill, or the task clearly matches a skill's description, " +
                    "call the `use_skill` tool with the exact skill name **before taking task actions**. " +
                    "Load all applicable skills, then follow their full instructions.",
            )
            appendLine(
                "This catalog contains summaries only: do not infer or follow a skill's instructions " +
                    "until it has been loaded.",
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

package com.psyche.memo.common.skill

import java.io.File

/**
 * 一个已安装技能的元数据（移植 RikkaHub `data/files/SkillManager.kt` 的 `SkillMetadata`）。
 *
 * `name` / `description` 必填、来自 `SKILL.md` 的 frontmatter；`compatibility` 可选。
 * 正文不进这个对象 —— `use_skill` 触发时才按需读 [skillFile]。
 */
data class SkillMetadata(
    val name: String,
    val description: String,
    val compatibility: String? = null,
    val skillDir: File,
) {
    val skillFile: File get() = skillDir.resolve("SKILL.md")
}

/** 技能目录内的一个文件（相对技能目录的路径 + 字节数）。 */
data class SkillFileEntry(
    val relativePath: String,
    val sizeBytes: Long,
) {
    /** 用于界面缩进的层级：`examples/basic.md` → 1。 */
    val depth: Int get() = relativePath.count { it == '/' }
}

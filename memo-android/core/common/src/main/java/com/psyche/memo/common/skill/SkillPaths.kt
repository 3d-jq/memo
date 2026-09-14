package com.psyche.memo.common.skill

import java.io.File

/**
 * 技能目录/文件的安全解析 —— 1:1 移植 RikkaHub 的 `data/files/SkillPaths.kt`。
 *
 * 两个入口的输入都不可信：技能名来自 frontmatter（第三方文件），相对路径来自**模型**
 * （`use_skill` 的 `path` 参数）。判据是「解析后的 canonical path 必须落在根之内」，
 * 而不是简单查 `..` —— 后者挡不住符号链接。
 */
object SkillPaths {

    /** 技能名 → 技能目录。不允许空、`.`、`..` 与路径分隔符，且解析后必须正好在 root 下。 */
    fun resolveSkillDir(skillsRoot: File, skillName: String): File? {
        if (skillName.isBlank()) return null
        if (skillName == "." || skillName == "..") return null
        if (skillName.contains('/') || skillName.contains('\\')) return null

        val canonicalRoot = skillsRoot.canonicalFile
        val canonicalDir = canonicalRoot.resolve(skillName).canonicalFile
        val parent = canonicalDir.parentFile ?: return null

        if (parent != canonicalRoot) return null
        if (!canonicalDir.isSameOrInside(canonicalRoot)) return null

        return canonicalDir
    }

    /** 技能目录内的相对路径 → 文件；解析后必须仍在技能目录内。 */
    fun resolveSkillFile(skillDir: File, relativePath: String): File? {
        if (relativePath.isBlank()) return null

        val canonicalSkillDir = skillDir.canonicalFile
        val canonicalTarget = canonicalSkillDir.resolve(relativePath).canonicalFile

        return canonicalTarget.takeIf { it.isSameOrInside(canonicalSkillDir) }
    }

    private fun File.isSameOrInside(root: File): Boolean {
        val rootPath = root.canonicalFile.path
        val currentPath = canonicalFile.path
        return currentPath == rootPath || currentPath.startsWith(rootPath + File.separator)
    }
}

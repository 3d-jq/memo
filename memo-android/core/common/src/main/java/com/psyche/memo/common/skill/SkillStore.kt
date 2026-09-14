package com.psyche.memo.common.skill

import java.io.File

/**
 * 技能文件仓库 —— 移植 RikkaHub `data/files/SkillManager.kt` 的**文件部分**。
 *
 * 与上游唯一的结构性差异：本类**只依赖一个根目录**，不碰 `Context`、也不碰助手设置，
 * 所以能在 JVM 单测里用临时目录把列表/读写/原子保存/删除/路径穿越全部覆盖；
 * 「删除技能后清理助手 `enabledSkills`」「清理幽灵技能名」这类跨域逻辑留在 app 层
 * （那里同时握有 [com.psyche.memo.data.assistant.AssistantStore]）。
 *
 * 与上游一致的行为：`SKILL.md` 必填；保存走 staging + rename 的原子路径（避免写一半
 * 留下半个技能）；技能名与相对路径都经 [SkillPaths] 做 canonical 边界检查。
 *
 * 一处有意的小偏差：列表**按名字排序**（上游用 `listFiles()` 的目录序，不确定），
 * 否则同一批技能在不同设备上顺序会跳。
 */
class SkillStore(private val skillsRoot: File) {

    /** 技能根目录（不存在则建）。 */
    fun skillsDir(): File {
        if (!skillsRoot.exists()) skillsRoot.mkdirs()
        return skillsRoot
    }

    /** 扫描根目录下的技能（每个子目录一个 `SKILL.md`），解析失败或字段缺失的跳过。 */
    fun listSkills(): List<SkillMetadata> {
        val dirs = skillsDir().listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs
            .mapNotNull { dir ->
                val skillFile = dir.resolve("SKILL.md")
                if (!skillFile.isFile) null else parseSkillFile(skillFile, dir)
            }
            .sortedBy { it.name }
    }

    /** `use_skill` 不带 path 时返回给模型的内容（frontmatter 之后的正文）。 */
    fun readSkillBody(name: String): String? {
        val skillFile = skillDir(name)?.resolve("SKILL.md") ?: return null
        if (!skillFile.isFile) return null
        return SkillFrontmatterParser.extractBody(skillFile.readText())
    }

    /** `SKILL.md` 全文（详情页编辑用）。 */
    fun readSkillContent(name: String): String? {
        val skillFile = skillDir(name)?.resolve("SKILL.md") ?: return null
        if (!skillFile.isFile) return null
        return skillFile.readText()
    }

    /**
     * 用一段 `SKILL.md` 内容覆盖式保存一个技能，返回解析后的元数据（失败为 null）。
     *
     * 与上游的一处有意差异：**先校验再落盘**。上游是「先写盘、再解析」，frontmatter 少
     * `name`/`description` 时会在磁盘上留下一个永远解析不出来、列表里也看不到的目录。
     */
    fun saveSkill(name: String, content: String): SkillMetadata? {
        val target = skillDir(name) ?: return null
        if (parseContent(content, target) == null) return null
        if (!saveSkillFilesAtomically(name, mapOf(SKILL_MD to content))) return null
        return parseContent(content, target)
    }

    fun saveSkillFilesAtomically(name: String, files: Map<String, String>): Boolean =
        saveSkillFileBytesAtomically(name, files.mapValues { it.value.toByteArray() })

    /**
     * staging 目录写全 → 现有目录改名为 backup → staging 改名为正式目录 → 删 backup。
     * 任何一步失败都把 backup 还原回去，保证不会出现「技能被写坏且原样也没了」。
     */
    fun saveSkillFileBytesAtomically(name: String, files: Map<String, ByteArray>): Boolean {
        val root = skillsDir()
        val targetDir = skillDir(name) ?: return false
        val stagingDir = createTempSkillDir(root, name, "staging") ?: return false
        var backupDir: File? = null

        try {
            for ((relativePath, content) in files) {
                val target = SkillPaths.resolveSkillFile(stagingDir, relativePath) ?: return false
                target.parentFile?.mkdirs()
                target.writeBytes(content)
            }

            if (!stagingDir.resolve(SKILL_MD).isFile) return false

            if (targetDir.exists()) {
                // 目标是「还不存在的唯一路径」，**不能**先 mkdirs 再 rename：
                // 目标已存在时 renameTo 在 Windows 上直接失败（POSIX 才允许覆盖空目录），
                // 而且失败时那个先建出来的目录还会留在磁盘上。
                backupDir = uniqueTempPath(root, name, "backup") ?: return false
                if (!targetDir.renameTo(backupDir)) return false
            }

            if (!stagingDir.renameTo(targetDir)) {
                if (backupDir != null && !targetDir.exists()) backupDir.renameTo(targetDir)
                return false
            }

            backupDir?.deleteRecursively()
            return true
        } catch (e: Exception) {
            if (backupDir != null && !targetDir.exists()) backupDir.renameTo(targetDir)
            return false
        } finally {
            if (stagingDir.exists()) stagingDir.deleteRecursively()
            if (backupDir?.exists() == true && targetDir.exists()) backupDir.deleteRecursively()
        }
    }

    /**
     * 删除整个技能目录（调用方负责同步清掉助手上引用的技能名）。
     *
     * 明确挡掉「不存在」：Kotlin 的 `deleteRecursively()` 对**不存在的路径也返回 true**，
     * 直接用会让 UI 对一个本来就没有的技能谎报删除成功。
     */
    fun deleteSkill(name: String): Boolean {
        val dir = skillDir(name) ?: return false
        if (!dir.isDirectory) return false
        return dir.deleteRecursively()
    }

    fun skillDir(name: String): File? = SkillPaths.resolveSkillDir(skillsDir(), name)

    /**
     * 技能目录内的文件清单（相对路径 + 字节数），按相对路径排序。
     *
     * 上游 `SkillDetailVM` 建的是可折叠的目录树；Memo 的详情页按相对路径缩进成一张平表
     * （技能里通常只有 `SKILL.md` 加几个示例文件），所以这里给平表、缩进交给界面层。
     */
    fun listFiles(name: String): List<SkillFileEntry> {
        val dir = skillDir(name) ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        val rootPath = dir.path
        return dir.walkTopDown()
            .filter { it.isFile }
            .map { file ->
                SkillFileEntry(
                    // 相对路径一律用 '/'：它是 API 层的路径（还会回喂给
                    // SkillPaths.resolveSkillFile 与界面展示），不能跟着宿主的
                    // File.separatorChar 变（Windows 上会变成反斜杠）。
                    relativePath = file.path
                        .removePrefix(rootPath)
                        .trimStart(File.separatorChar)
                        .replace(File.separatorChar, '/'),
                    sizeBytes = file.length(),
                )
            }
            .sortedBy { it.relativePath }
            .toList()
    }

    fun resolveSkillFile(name: String, relativePath: String): File? {
        val dir = skillDir(name) ?: return null
        return SkillPaths.resolveSkillFile(dir, relativePath)
    }

    /** 写技能目录内的单个文件（详情页新建/编辑文件）。 */
    fun saveSkillFile(name: String, relativePath: String, content: String): Boolean {
        val dir = skillDir(name) ?: return false
        val target = SkillPaths.resolveSkillFile(dir, relativePath) ?: return false
        target.parentFile?.mkdirs()
        target.writeText(content)
        return true
    }

    fun deleteSkillFile(name: String, relativePath: String): Boolean {
        val target = resolveSkillFile(name, relativePath) ?: return false
        return target.delete()
    }

    /** 建一个唯一的新目录（staging 要往里写文件，所以必须先建出来）。 */
    private fun createTempSkillDir(root: File, skillName: String, suffix: String): File? {
        repeat(MAX_TEMP_ATTEMPTS) { attempt ->
            val candidate = root.resolve(".$skillName.$suffix.$attempt.tmp")
            if (!candidate.exists() && candidate.mkdirs()) return candidate
        }
        return null
    }

    /** 只要一个不存在的唯一路径（backup 是 rename 的目标，不能先建出来）。 */
    private fun uniqueTempPath(root: File, skillName: String, suffix: String): File? {
        repeat(MAX_TEMP_ATTEMPTS) { attempt ->
            val candidate = root.resolve(".$skillName.$suffix.$attempt.tmp")
            if (!candidate.exists()) return candidate
        }
        return null
    }

    private fun parseSkillFile(skillFile: File, skillDir: File): SkillMetadata? = runCatching {
        parseContent(skillFile.readText(), skillDir)
    }.getOrNull()

    /** frontmatter 是 `name`/`description` 双必填；任一缺失或非字符串都算解析失败。 */
    private fun parseContent(content: String, skillDir: File): SkillMetadata? = runCatching {
        val frontmatter = SkillFrontmatterParser.parse(content)
        val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: return null
        val description = frontmatter["description"]?.takeIf { it.isNotBlank() } ?: return null
        SkillMetadata(
            name = name,
            description = description,
            compatibility = frontmatter["compatibility"],
            skillDir = skillDir,
        )
    }.getOrNull()

    companion object {
        const val SKILL_MD = "SKILL.md"

        /** 上游同样是 100 次尝试（同名 staging/backup 残留时退避）。 */
        private const val MAX_TEMP_ATTEMPTS = 100
    }
}

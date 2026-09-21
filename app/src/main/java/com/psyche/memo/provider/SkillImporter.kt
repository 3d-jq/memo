package com.psyche.memo.provider

import com.psyche.memo.common.skill.SkillFrontmatterParser
import com.psyche.memo.common.skill.SkillStore
import java.io.ByteArrayInputStream
import java.util.LinkedHashMap
import java.util.zip.ZipInputStream

/** 导入失败的原因（文案由界面层映射成 `skills_page_import_failed` 的 %1$s）。 */
class SkillImportException(message: String) : Exception(message)

/**
 * 从文件导入技能 —— 移植 RikkaHub `SkillsVM` 的 `importSkillFromFile` /
 * `importSkillMarkdown` / `importSkillsFromZip`。
 *
 * 两种输入：单个 `SKILL.md`（.md），或一个打包了多个技能目录的 .zip。
 * zip 里可能嵌套技能（`a/SKILL.md` 与 `a/b/SKILL.md` 同时存在），上游的做法是
 * 「取最外层」——[selectSkillBases] 就是那条规则，单独抽出来是为了能直接单测。
 *
 * 从 GitHub 仓库导入是另一条路径，见 [SkillGitHubImporter]。
 */
object SkillImporter {

    private val ZIP_MAGIC = listOf(
        byteArrayOf(0x50, 0x4B, 0x03, 0x04),
        byteArrayOf(0x50, 0x4B, 0x05, 0x06),
        byteArrayOf(0x50, 0x4B, 0x07, 0x08),
    )

    /** 按扩展名或魔数判定 zip（上游 `isZipFile`）。 */
    fun isZip(fileName: String, bytes: ByteArray): Boolean =
        fileName.endsWith(".zip", ignoreCase = true) || ZIP_MAGIC.any { bytes.startsWith(it) }

    /** 导入一个文件，返回写入成功的技能名；失败抛 [SkillImportException]。 */
    fun import(store: SkillStore, fileName: String, bytes: ByteArray): List<String> =
        if (isZip(fileName, bytes)) importZip(store, bytes) else listOf(importMarkdown(store, bytes))

    private fun importMarkdown(store: SkillStore, bytes: ByteArray): String {
        val content = bytes.toString(Charsets.UTF_8)
        val frontmatter = SkillFrontmatterParser.parse(content)
        val name = frontmatter["name"]?.trim().orEmpty()
        if (name.isBlank()) throw SkillImportException("SKILL.md: missing name field")
        if (frontmatter["description"].isNullOrBlank()) {
            throw SkillImportException("SKILL.md: missing description field")
        }
        return store.saveSkill(name, content)?.name
            ?: throw SkillImportException("Failed to save '$name'")
    }

    private fun importZip(store: SkillStore, bytes: ByteArray): List<String> {
        val files = readZipEntries(bytes)
        if (files.isEmpty()) throw SkillImportException("Empty archive")

        val skillMdPaths = files.keys
            .filter { it.substringAfterLast('/').equals(SkillStore.SKILL_MD, ignoreCase = true) }
            .sorted()
        if (skillMdPaths.isEmpty()) throw SkillImportException("No SKILL.md found in the archive")

        val bases = selectSkillBases(skillMdPaths)
        // 判断「这个文件属于谁」要用**全部**技能目录，而不是筛选后的外层列表 ——
        // 否则嵌套技能的文件会被外层吞掉（上游 isInsideNestedSkill 收的也是全量）。
        val allBases = skillMdPaths.map { baseOf(it) }.distinct()
        val imported = mutableListOf<String>()
        for (skillMdPath in skillMdPaths) {
            val base = baseOf(skillMdPath)
            // 被外层技能包住的嵌套技能不再单独导入（上游 isInsideNestedSkill）。
            if (bases.none { it == base }) continue

            val content = files[skillMdPath]?.toString(Charsets.UTF_8)
                ?: throw SkillImportException("Failed to read $skillMdPath")
            val frontmatter = SkillFrontmatterParser.parse(content)
            val name = frontmatter["name"]?.trim().orEmpty()
            if (name.isBlank()) throw SkillImportException("$skillMdPath: missing name field")
            if (frontmatter["description"].isNullOrBlank()) {
                throw SkillImportException("$skillMdPath: missing description field")
            }

            val payload = LinkedHashMap<String, ByteArray>()
            for ((path, data) in files) {
                if (!isUnder(path, base)) continue
                val relative = if (base.isEmpty()) path else path.removePrefix("$base/")
                if (relative.isBlank()) continue
                // 属于更深的那个嵌套技能的条目归它自己，不塞进外层。
                if (allBases.any { it != base && it.isNotEmpty() && isUnder(path, it) }) continue
                payload[if (relative.equals(SkillStore.SKILL_MD, ignoreCase = true)) SkillStore.SKILL_MD else relative] = data
            }

            if (!store.saveSkillFileBytesAtomically(name, payload)) {
                throw SkillImportException("Failed to save '$name'")
            }
            imported += name
        }
        return imported.distinct()
    }

    private fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                try {
                    if (!entry.isDirectory) {
                        normalizeZipEntryPath(entry.name)?.let { files[it] = zip.readBytes() }
                    }
                } finally {
                    zip.closeEntry()
                }
            }
        }
        return files
    }

    /** 条目路径规范化：统一分隔符、去掉前导 `/` 与 `.`，含 `..` 的整条丢弃。 */
    fun normalizeZipEntryPath(path: String): String? {
        val parts = path.replace('\\', '/')
            .trimStart('/')
            .split('/')
            .filter { it.isNotBlank() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /**
     * 「取最外层」：只保留没有被另一个技能目录包住的 SKILL.md 所在目录。
     * 例：`a/SKILL.md` + `a/b/SKILL.md` → 只留 `a`。
     */
    fun selectSkillBases(skillMdPaths: List<String>): List<String> {
        val bases = skillMdPaths.map { baseOf(it) }.distinct()
        return bases.filter { candidate ->
            !bases.any { other ->
                other != candidate && candidate.isNotEmpty() && isUnder(candidate, other)
            }
        }
    }

    private fun baseOf(skillMdPath: String): String =
        skillMdPath.substringBeforeLast('/', missingDelimiterValue = "")

    private fun isUnder(path: String, base: String): Boolean =
        base.isEmpty() || path == base || path.startsWith("$base/")

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { (this[it].toInt() and 0xFF) == (prefix[it].toInt() and 0xFF) }
    }
}

package com.psyche.memo.provider

import com.psyche.memo.common.skill.SkillFrontmatterParser
import com.psyche.memo.common.skill.SkillStore
import java.util.LinkedHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 从 GitHub 仓库导入技能 —— 移植 RikkaHub `SkillsVM.importSkillFromGitHub`。
 *
 * 走 GitHub Contents API 递归列出目录，找到根部的 `SKILL.md`，把它的 frontmatter
 * `name` 当技能名，然后把**仓库里该目录下的所有文件**一起存进技能目录
 * （技能常带 `examples/`、脚本等附属文件）。
 *
 * 与上游的两处实现差异，都是为了可测与正确：
 *  1. **拉取做成注入的 [fetch]**（上游直接开 `HttpURLConnection`）。生产用
 *     [okHttpFetcher]（复用容器的 OkHttp，**全局代理设置因此生效**），单测塞一个
 *     返回固定 JSON/文本的 lambda 即可，不需要 MockWebServer 或真网络。
 *  2. **JSON 用 kotlinx.serialization 解**（上游用 `org.json`）——`org.json` 在纯
 *     JVM 单测里是 stub（调用即抛），换成项目已有的 kotlinx 后解析逻辑也能被测到。
 *
 * 安全边界：[SkillStore.saveSkillFilesAtomically] 内部仍走 `SkillPaths` 的 canonical
 * 检查，API 返回的路径不可能写到技能目录之外。
 */
class SkillGitHubImporter(
    private val store: SkillStore,
    private val fetch: (String) -> String?,
) {

    /** `https://github.com/owner/repo[/tree/branch[/sub/path]]`。 */
    data class RepoRef(
        val owner: String,
        val repo: String,
        val branch: String,
        val path: String,
    )

    /** 导入并返回写入成功的技能名；失败抛 [SkillImportException]。 */
    fun import(url: String): List<String> {
        val ref = parseUrl(url) ?: throw SkillImportException("Invalid GitHub repository URL")

        // relativePath -> downloadUrl
        val files = LinkedHashMap<String, String>()
        if (!listFiles(ref, ref.path, files)) {
            throw SkillImportException("Failed to read the repository contents")
        }

        val skillMdUrl = files[SKILL_MD]
            ?: throw SkillImportException("No $SKILL_MD found at the repository root")
        val skillMd = fetch(skillMdUrl) ?: throw SkillImportException("Failed to download $SKILL_MD")

        val frontmatter = SkillFrontmatterParser.parse(skillMd)
        val name = frontmatter["name"]?.trim().orEmpty()
        if (name.isBlank()) throw SkillImportException("$SKILL_MD: missing name field")
        if (frontmatter["description"].isNullOrBlank()) {
            throw SkillImportException("$SKILL_MD: missing description field")
        }

        val payload = LinkedHashMap<String, String>()
        for ((relativePath, downloadUrl) in files) {
            payload[relativePath] = fetch(downloadUrl)
                ?: throw SkillImportException("Failed to download $relativePath")
        }

        if (!store.saveSkillFilesAtomically(name, payload)) {
            throw SkillImportException("Failed to save '$name'")
        }
        return listOf(name)
    }

    /**
     * 递归列目录。GitHub Contents API 对目录返回 JSON 数组、对单个文件返回对象
     * （只有仓库根的 `path` 可能指向文件，这里按数组处理，不是数组就报失败）。
     */
    private fun listFiles(
        ref: RepoRef,
        dirPath: String,
        out: MutableMap<String, String>,
    ): Boolean {
        val apiUrl = "$API/repos/${ref.owner}/${ref.repo}/contents/$dirPath?ref=${ref.branch}"
        val body = fetch(apiUrl) ?: return false
        val entries = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull() ?: return false

        for (entry in entries) {
            val obj = entry.jsonObject
            val itemPath = obj["path"]?.jsonPrimitive?.contentOrNull ?: continue
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "file" -> {
                    val downloadUrl = obj["download_url"]?.jsonPrimitive?.contentOrNull
                    if (downloadUrl.isNullOrBlank()) return false
                    out[relativeTo(ref.path, itemPath)] = downloadUrl
                }
                "dir" -> if (!listFiles(ref, itemPath, out)) return false
            }
        }
        return true
    }

    /** 上游 `itemPath.removePrefix("$basePath/").removePrefix(basePath)`。 */
    private fun relativeTo(basePath: String, itemPath: String): String =
        itemPath.removePrefix("$basePath/").removePrefix(basePath)

    companion object {
        private const val API = "https://api.github.com"
        private const val SKILL_MD = "SKILL.md"

        private val json = Json { ignoreUnknownKeys = true }

        /** 上游 `parseGitHubUrl` 的正则，逐字对齐。 */
        private val URL_REGEX =
            Regex("""https://github\.com/([^/]+)/([^/]+)(?:/tree/([^/]+)(/.*)?)?""")

        fun parseUrl(url: String): RepoRef? {
            val trimmed = url.trim().trimEnd('/')
            val match = URL_REGEX.matchEntire(trimmed) ?: return null
            return RepoRef(
                owner = match.groupValues[1],
                repo = match.groupValues[2],
                branch = match.groupValues[3].ifBlank { "HEAD" },
                path = match.groupValues[4].trimStart('/'),
            )
        }

        /**
         * 生产用的拉取器：复用容器的 OkHttp，所以**全局网络代理对 GitHub 导入同样生效**。
         * GitHub API 要求 User-Agent；API 与 raw 下载分别用各自的 Accept。
         */
        fun okHttpFetcher(client: OkHttpClient): (String) -> String? = { url ->
            runCatching {
                val isApi = url.startsWith(API)
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Memo")
                    .apply { if (isApi) header("Accept", "application/vnd.github+json") }
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.code == 200) response.body?.string() else null
                }
            }.getOrNull()
        }
    }
}

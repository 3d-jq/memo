package com.psyche.memo.provider

import com.psyche.memo.common.skill.SkillStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * GitHub 导入（RikkaHub `SkillsVM.importSkillFromGitHub` 的移植）。
 *
 * 网络拉取是注入的，所以这里用一张 URL→响应 的假表把**解析与递归逻辑**完整覆盖，
 * 不需要 MockWebServer 也不需要真网络。
 */
class SkillGitHubImporterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = SkillStore(File(temp.root, "skills"))

    private fun skillMd(name: String, description: String = "$name desc", body: String = "Body") =
        "---\nname: $name\ndescription: $description\n---\n\n$body\n"

    private fun fileEntry(path: String, downloadUrl: String) =
        """{"type":"file","path":"$path","download_url":"$downloadUrl"}"""

    private fun dirEntry(path: String) = """{"type":"dir","path":"$path"}"""

    /** Contents API 的目录 URL。 */
    private fun contents(owner: String, repo: String, dir: String, branch: String) =
        "https://api.github.com/repos/$owner/$repo/contents/$dir?ref=$branch"

    private fun listing(owner: String, repo: String, dir: String, branch: String, vararg entries: String) =
        contents(owner, repo, dir, branch) to "[${entries.joinToString(",")}]"

    private fun importer(store: SkillStore, vararg responses: Pair<String, String>) =
        SkillGitHubImporter(store) { url -> responses.toMap()[url] }

    // ---- URL 解析 ----

    @Test
    fun parsesRepositoryUrlVariants() {
        assertEquals(
            SkillGitHubImporter.RepoRef("owner", "repo", "HEAD", ""),
            SkillGitHubImporter.parseUrl("https://github.com/owner/repo"),
        )
        assertEquals(
            SkillGitHubImporter.RepoRef("owner", "repo", "HEAD", ""),
            SkillGitHubImporter.parseUrl("https://github.com/owner/repo/"),
        )
        assertEquals(
            SkillGitHubImporter.RepoRef("owner", "repo", "main", ""),
            SkillGitHubImporter.parseUrl("https://github.com/owner/repo/tree/main"),
        )
        assertEquals(
            SkillGitHubImporter.RepoRef("owner", "repo", "dev", "skills/foo"),
            SkillGitHubImporter.parseUrl("https://github.com/owner/repo/tree/dev/skills/foo"),
        )
    }

    @Test
    fun rejectsNonRepositoryUrls() {
        assertNull(SkillGitHubImporter.parseUrl(""))
        assertNull(SkillGitHubImporter.parseUrl("https://gitlab.com/owner/repo"))
        assertNull(SkillGitHubImporter.parseUrl("github.com/owner/repo"))
        assertNull(SkillGitHubImporter.parseUrl("https://github.com/owner"))
    }

    // ---- 导入 ----

    @Test
    fun importsRootSkillWithItsCompanionFiles() {
        val store = store()
        val importer = importer(
            store,
            listing(
                "o", "r", "", "HEAD",
                fileEntry("SKILL.md", "https://raw/SKILL.md"),
                fileEntry("notes.md", "https://raw/notes.md"),
            ),
            "https://raw/SKILL.md" to skillMd("pdf-tools"),
            "https://raw/notes.md" to "notes",
        )

        assertEquals(listOf("pdf-tools"), importer.import("https://github.com/o/r"))
        assertEquals(listOf("pdf-tools"), store.listSkills().map { it.name })
        assertEquals("notes", store.resolveSkillFile("pdf-tools", "notes.md")!!.readText())
        assertEquals(
            listOf("SKILL.md", "notes.md"),
            store.listFiles("pdf-tools").map { it.relativePath },
        )
    }

    /** 目录要递归展开，相对路径按仓库根的 base 算。 */
    @Test
    fun recursesIntoSubdirectories() {
        val store = store()
        val importer = importer(
            store,
            listing(
                "o", "r", "", "HEAD",
                fileEntry("SKILL.md", "https://raw/SKILL.md"),
                dirEntry("examples"),
            ),
            listing("o", "r", "examples", "HEAD", fileEntry("examples/basic.md", "https://raw/basic.md")),
            "https://raw/SKILL.md" to skillMd("multi"),
            "https://raw/basic.md" to "example",
        )

        assertEquals(listOf("multi"), importer.import("https://github.com/o/r"))
        assertEquals(
            listOf("SKILL.md", "examples/basic.md"),
            store.listFiles("multi").map { it.relativePath },
        )
    }

    /** `/tree/branch/sub/path` 时，相对路径要从**子目录**算，不是仓库根。 */
    @Test
    fun subdirectoryUrlMakesPathsRelativeToThatSubdirectory() {
        val store = store()
        val importer = importer(
            store,
            listing(
                "o", "r", "skills/foo", "main",
                fileEntry("skills/foo/SKILL.md", "https://raw/SKILL.md"),
                fileEntry("skills/foo/extra.md", "https://raw/extra.md"),
            ),
            "https://raw/SKILL.md" to skillMd("foo"),
            "https://raw/extra.md" to "extra",
        )

        val names = importer.import("https://github.com/o/r/tree/main/skills/foo")
        assertEquals(listOf("foo"), names)
        assertEquals(listOf("SKILL.md", "extra.md"), store.listFiles("foo").map { it.relativePath })
    }

    @Test
    fun failsWhenRepositoryHasNoSkillMd() {
        val importer = importer(
            store(),
            listing("o", "r", "", "HEAD", fileEntry("README.md", "https://raw/README.md")),
            "https://raw/README.md" to "hi",
        )

        val error = assertThrows(SkillImportException::class.java) {
            importer.import("https://github.com/o/r")
        }
        assertTrue(error.message!!.contains("SKILL.md"))
    }

    @Test
    fun failsWhenListingIsUnreachable() {
        val importer = importer(store()) // 空表：任何 URL 都拉不到
        assertThrows(SkillImportException::class.java) {
            importer.import("https://github.com/o/r")
        }
    }

    @Test
    fun failsWhenFrontmatterIsIncomplete() {
        val importer = importer(
            store(),
            listing("o", "r", "", "HEAD", fileEntry("SKILL.md", "https://raw/SKILL.md")),
            "https://raw/SKILL.md" to "---\ndescription: no name\n---\nbody",
        )
        assertThrows(SkillImportException::class.java) { importer.import("https://github.com/o/r") }
    }

    @Test
    fun failsWhenACompanionFileCannotBeDownloadedAndLeavesNoHalfWrittenSkill() {
        val store = store()
        val importer = importer(
            store,
            listing(
                "o", "r", "", "HEAD",
                fileEntry("SKILL.md", "https://raw/SKILL.md"),
                fileEntry("missing.md", "https://raw/missing.md"),
            ),
            "https://raw/SKILL.md" to skillMd("partial"),
            // missing.md 故意缺席 —— 整次导入必须失败且不留半个技能
        )

        assertThrows(SkillImportException::class.java) { importer.import("https://github.com/o/r") }
        assertTrue(store.listSkills().isEmpty())
    }

    @Test
    fun failsOnAnInvalidUrl() {
        assertThrows(SkillImportException::class.java) {
            importer(store()).import("https://gitlab.com/o/r")
        }
    }
}

package com.psyche.memo.provider

import com.psyche.memo.common.skill.SkillStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 内置技能播种。
 *
 * 用**真实的 assets** 跑（Robolectric 提供 `AssetManager`），所以这条同时是
 * 「打包进来的 skill-creator 真的能被我们的 frontmatter 解析器解出来」的端到端校验 ——
 * 上游技能更新后如果格式变了，这里会红，而不是等用户导入时才发现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BundledSkillsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun assets() = RuntimeEnvironment.getApplication().assets

    private fun store() = SkillStore(File(temp.root, "skills"))

    @Test
    fun skillCreatorIsBundledAndParsesWithOurFrontmatterParser() {
        val store = store()
        val bundled = BundledSkills.listBundled(assets())
        assertTrue("assets 里应当有 skill-creator，实际: $bundled", "skill-creator" in bundled)

        val installed = BundledSkills.install(assets(), store, bundled)
        assertTrue("skill-creator 应当装上，实际: $installed", "skill-creator" in installed)

        val skill = store.listSkills().firstOrNull { it.skillDir.name == "skill-creator" }
        assertTrue("SKILL.md 的 frontmatter 必须能被解析出来（name + description）", skill != null)
        assertTrue(skill!!.description.isNotBlank())

        // 附属文件（agents/references/scripts）也要一起落地 —— 技能常用它们。
        val files = store.listFiles("skill-creator").map { it.relativePath }
        assertTrue("应当带上附属文件，实际: $files", files.size > 5)
        assertTrue(files.contains("SKILL.md"))
        assertTrue(files.any { it.startsWith("references/") })
    }

    @Test
    fun pendingSkipsAlreadySeededSkills() {
        assertEquals(
            listOf("b", "c"),
            BundledSkills.pending(listOf("a", "b", "c"), listOf("a")),
        )
        assertTrue(BundledSkills.pending(listOf("a"), listOf("a", "b")).isEmpty())
        assertEquals(listOf("a"), BundledSkills.pending(listOf("a"), emptyList()))
    }

    /** 播种是复制不是移动：第二次调用应当无害（文件已在那）。 */
    @Test
    fun installingTwiceIsHarmless() {
        val store = store()
        val bundled = BundledSkills.listBundled(assets())
        BundledSkills.install(assets(), store, bundled)
        val second = BundledSkills.install(assets(), store, bundled)
        assertEquals(bundled.sorted(), second.sorted())
        assertTrue(store.listSkills().any { it.skillDir.name == "skill-creator" })
    }

    /** 清单文件不能被当成技能。 */
    @Test
    fun manifestIsNotTreatedAsASkill() {
        assertFalse("BUNDLED.json" in BundledSkills.listBundled(assets()))
    }
}

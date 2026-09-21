package com.psyche.memo.provider

import android.content.Context
import android.content.res.AssetManager
import com.psyche.memo.common.skill.SkillStore
import com.psyche.memo.data.settings.PreferenceRepository
import java.io.File

/**
 * 内置技能：随 APK 打包（`assets/skills/<名>/...`），首次运行播种进 `<filesDir>/skills`。
 *
 * 上游 RikkaHub **不做内置技能**（它的技能全靠用户导入）—— 这是 Memo 自己的加法：
 * 用户 2026-09-14 点名 skill-creator「这个 skill 很好用」，希望装完就能用，不用自己去
 * GitHub 导一遍。抓取是 `tools/fetch_bundled_skills.py`（把上游 commit 记进
 * `assets/skills/BUNDLED.json`，可审计）。
 *
 * **只播种一次**：播过的名字记在本地键 [SEEDED_KEY]（`readLocal/writeLocal`，属设备态、
 * 不进备份）。这样用户手动删掉内置技能后不会被下次启动复活，而以后版本新增的内置技能
 * 仍会补种。播种失败的名字不记为已播种，下次启动会重试（本地复制失败基本是瞬时的）。
 */
object BundledSkills {

    const val SEEDED_KEY = "bundled_skills_seeded_v1"

    /** assets 下的根目录：`assets/skills/<名>/...`。 */
    private const val ASSET_ROOT = "skills"

    /** 上游 commit 清单不是技能。 */
    private const val MANIFEST = "BUNDLED.json"

    /**
     * assets 里打包了哪些技能。
     *
     * **只取第一段路径**：`AssetManager.list()` 的语义不统一 —— 真机返回直接子项
     * （目录名），Robolectric 返回的是递归文件路径（`skill-creator/SKILL.md`）。
     * 取 `substringBefore('/')` 两边都对，直接拿条目当目录名则两边都会错。
     */
    fun listBundled(assets: AssetManager): List<String> =
        (assets.list(ASSET_ROOT) ?: emptyArray())
            .map { it.substringBefore('/') }
            .filter { it.isNotEmpty() && it != MANIFEST }
            .distinct()
            .sorted()

    /** 还没播种过的 = 内置清单 − 已播种。 */
    fun pending(bundled: Collection<String>, seeded: Collection<String>): List<String> =
        bundled.filter { it !in seeded }.sorted()

    /** 一个技能包里的全部文件（相对技能目录的路径）。 */
    fun listFiles(assets: AssetManager, name: String): List<String> =
        collectFiles(assets, "$ASSET_ROOT/$name").sorted()

    /** 把 [names] 从 assets 复制进技能目录，返回确实装好（`SKILL.md` 在位）的名字。 */
    fun install(assets: AssetManager, store: SkillStore, names: Collection<String>): List<String> {
        val installed = mutableListOf<String>()
        for (name in names) {
            val target = store.skillDir(name) ?: continue
            for (relative in listFiles(assets, name)) {
                val destination = File(target, relative)
                destination.parentFile?.mkdirs()
                runCatching {
                    assets.open("$ASSET_ROOT/$name/$relative").use { input ->
                        destination.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            if (target.resolve(SkillStore.SKILL_MD).isFile) installed += name
        }
        return installed
    }

    /** 启动时调用：补种还没装过的内置技能。返回本次装上的名字。 */
    fun seedIfNeeded(
        context: Context,
        store: SkillStore,
        preferences: PreferenceRepository,
    ): List<String> {
        val seeded = preferences.readLocal(SEEDED_KEY)
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
        val todo = pending(listBundled(context.assets), seeded)
        if (todo.isEmpty()) return emptyList()

        val installed = install(context.assets, store, todo)
        if (installed.isNotEmpty()) {
            preferences.writeLocal(SEEDED_KEY, (seeded + installed).joinToString(","))
        }
        return installed
    }

    /**
     * 递归展开成一个技能包里的**文件**相对路径。
     *
     * 每层都用 `list()` 再探一次：条目本身可能已经是嵌套路径（Robolectric），也可能是
     * 目录名（真机）。探不到更深层的就是文件，探得到就继续往下走 —— 两种语义都对。
     */
    private fun collectFiles(
        assets: AssetManager,
        root: String,
        prefix: String = "",
    ): List<String> {
        val here = if (prefix.isEmpty()) root else "$root/$prefix"
        val children = assets.list(here) ?: return emptyList()
        if (children.isEmpty()) return if (prefix.isEmpty()) emptyList() else listOf(prefix)

        val out = mutableListOf<String>()
        for (child in children) {
            val path = if (prefix.isEmpty()) child else "$prefix/$child"
            out += collectFiles(assets, root, path)
        }
        return out
    }
}

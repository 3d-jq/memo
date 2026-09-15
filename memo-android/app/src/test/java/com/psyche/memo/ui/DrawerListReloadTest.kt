package com.psyche.memo.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抽屉列表**只在打开时**重读，不许在「选中会话」时重读。
 *
 * 用户 2026-09-15：「点击对话历史 这个侧边栏到主界面 内容多的 就会很卡」。根因是
 * `SideDrawerContent` 的 `LaunchedEffect(selectedId, open) { withContext(IO) { reload() } }`
 * —— `selectedId` 每次点会话都变 ⇒ 整表 `conversationDao.getAll()` + 逐条解 payload JSON，
 * 而 `open`（`presenting` 驱动）在**抽屉收起时**还会再触发一次，正好压在「侧边栏 → 主界面」
 * 那一下，历史越多越慢。
 *
 * 参考实现都不会这么做：原版是内存 `_conversationsCache` + `notifyListeners`，RikkaHub 是
 * Room 的 `Flow<List<Conversation>>`。抽屉内部的增删改本来就显式调用 `reload()`（12 处），
 * 每次重新打开抽屉也必定刷新 —— 所以 key 里出现 `selectedId` 就是纯浪费。
 */
class DrawerListReloadTest {

    private val drawerFile = File("src/main/java/com/psyche/memo/ui/SideDrawerContent.kt")

    @Test
    fun `drawer reloads its conversation list on open only, never on selection`() {
        assertTrue(
            "expected ${drawerFile.absolutePath} to exist — check the Gradle test working dir",
            drawerFile.isFile,
        )

        val lines = blankComments(drawerFile.readText()).lines()
        val offenders = mutableListOf<String>()
        lines.forEachIndexed { index, line ->
            if (!line.contains("LaunchedEffect(")) return@forEachIndexed
            // 看这个 effect 开头几行里有没有 reload()。
            val window = lines.subList(index, minOf(index + 5, lines.size)).joinToString("\n")
            if (!window.contains("reload()")) return@forEachIndexed
            if (window.contains("selectedId")) {
                offenders += "${index + 1}: ${line.trim()}"
            }
        }

        assertTrue(
            "抽屉列表的刷新不能以 selectedId 为 key（每次点会话都整表重读，历史越多越卡）；" +
                "用 `LaunchedEffect(open) { if (open) reload() }`：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
    /**
     * 页面销毁**不许**回收当前会话的 VM。
     *
     * 用户 2026-09-15「点击设置 返回 又会加载对话 又会卡一下」：`ChatContent` 的
     * `onDispose` 里调了 `reapIdleChatViewModels(keep = null)`，进设置页时把当前会话的窗口
     * 清空，返回时 `ensureLoaded()` 又整窗重读 + 重渲染。原版 controller 活在页面之上
     * （内存缓存），返回是瞬时的 —— 所以回收只该发生在「切到另一条会话」时
     * （`registerChatViewModel` 内部 keep 当前那个）。
     */
    @Test
    fun `chat page disposal does not release the active conversation view model`() {
        val src = File("src/main/java/com/psyche/memo/ui/HomeScreen.kt")
        val text = blankComments(src.readText())
        assertTrue(
            "HomeScreen 里不许出现「页面销毁即回收会话 VM」（keep = null）；" +
                "回收只由 registerChatViewModel 在切会话时做",
            !text.contains("reapIdleChatViewModels(keep = null)"),
        )
    }
}
package com.psyche.memo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「存储空间」子页的两个实测修复（用户 2026-09-14）：
 *  1. 上传文档/图片删除只弹一条提示 —— 动作自己报了「已删除 N 个」之后，
 *     确认层不能再补一条通用「已完成」（原版 storage_space_page.dart:1882 只有一条）；
 *  2. 子页顶栏必须复用统一的 [MemoTopBar]，不许手搓返回行（与父页同款）。
 */
class StorageCategoryUiTest {

    @Test
    fun `an action that reports its own result suppresses the generic done toast`() {
        // Uploads 删除：动作里弹「已删除 N 个」⇒ 确认层静默。
        assertNull(confirmDoneMessage(silent = true, doneTemplate = "%s 已完成", target = "图片"))
        // 其它类别（清缓存/日志/快照）：动作不说话，由确认层报「已完成」。
        assertEquals(
            "缓存 已完成",
            confirmDoneMessage(silent = false, doneTemplate = "%s 已完成", target = "缓存"),
        )
    }

    @Test
    fun `the storage sub-page reuses the shared top bar`() {
        // 源码级守卫：StorageCategoryScreen 必须用 MemoTopBar（父页/其余页面同款），
        // 一旦有人改回手搓的返回行，这条会红。
        val src = java.io.File(
            "src/main/java/com/psyche/memo/ui/StorageSpace.kt",
        ).readText()
        val body = src.substringAfter("fun StorageCategoryScreen(")
            .substringBefore("\n@Composable")
        assertEquals(
            "StorageCategoryScreen must use the shared MemoTopBar",
            true,
            body.contains("MemoTopBar("),
        )
        assertEquals(
            "StorageCategoryScreen must not hand-roll a back row",
            false,
            body.contains("Lucide.ArrowLeft"),
        )
    }
}

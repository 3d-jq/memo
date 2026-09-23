package com.psyche.memo.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「对话里的工作区文件 sheet 不许再套一层 sheet」的**机器守卫**（PORTING §5.44②）。
 *
 * 这个 sheet 本身已经叠在 `WorkspaceSelectorSheet` 之上，文本预览曾经又是它里面的一层
 * `ModalBottomSheet`（`FileEditorSheet`）—— **三层窗口嵌套下预览根本不显示**，用户
 * 2026-09-22 看到的现象就是「md 点开没反应」。内容体（`FileEditorBody`）现在内联在这里，
 * 窗口层数不变；文档里写「别改回去」是不够的，得让它改回去就红。
 */
class WorkspaceFilesSheetGuardTest {

    private val source = File("src/main/java/com/psyche/memo/ui/WorkspaceFilesSheet.kt")

    @Test
    fun previewIsInlineInsteadOfAnotherNestedSheet() {
        assertTrue("找不到 ${source.path}（工作目录应当是 app 模块）", source.isFile)
        val text = source.readText()
        assertTrue(
            "预览应当内联 FileEditorBody —— 嵌套 FileEditorSheet 就是三层窗口，预览不显示",
            text.contains("FileEditorBody("),
        )
        assertFalse(
            "WorkspaceFilesSheet 里不能再出现 FileEditorSheet（嵌套 sheet，预览不显示）",
            text.contains("FileEditorSheet("),
        )
    }
}

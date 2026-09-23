package com.psyche.memo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.psyche.memo.ui.markdown.MarkdownText

/**
 * 更新日志正文（release notes）—— 启动时的更新对话框和「关于 → 检查更新」点开的
 * 对话框共用这一份。
 *
 * 以前两处都是 `Text(notes.flattened.take(600))`：GitHub release 的 body 是 **markdown**
 * （标题、列表、`code`），拍平之后显示成一坨，用户 2026-09-23「弹窗里面 markdown
 * 没有渲染」。这里交给聊天用的同一个 [MarkdownText]（`core:ui` 的 markdown 渲染器），
 * 用它的默认字号，不接聊天显示设置（关于页没有那套设置对象）。
 *
 * 限高 + 滚动：日志可以很长，对话框不能撑出屏幕。
 */
@Composable
fun UpdateNotesBody(notes: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        MarkdownText(markdown = notes.trim())
    }
}

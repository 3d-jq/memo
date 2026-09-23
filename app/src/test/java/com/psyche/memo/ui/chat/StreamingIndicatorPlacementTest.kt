package com.psyche.memo.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「流式等待提示必须是列表末尾的**独立一项**」的机器守卫。
 *
 * RikkaHub `ChatList.kt:381-402` 把等待提示放在 `item(LoadingIndicatorKey)`（独立项，
 * 整个生成期间都在）；我们第一版渲染在**助手消息内部**，消息 parts 一变那一行就跟着
 * 消息的块布局重排 —— 工具卡一次性换态时看起来就是在跳，用户 2026-09-23
 * 「跟 rikkhub 效果不一样…在工具调用这个有点跳动」。挪出来之后要防止有人再挪回去。
 */
class StreamingIndicatorPlacementTest {

    private val chatDir = File("src/main/java/com/psyche/memo/ui/chat")

    @Test
    fun theIndicatorIsAListItemNotPartOfTheMessage() {
        val content = File(chatDir, "ChatContent.kt")
        assertTrue("找不到 ${content.path}（工作目录应当是 app 模块）", content.isFile)
        assertTrue(
            "等待提示应当在 ChatContent 的列表里以独立 key 出现",
            content.readText().contains("STREAMING_INDICATOR_ITEM_KEY"),
        )

        val row = File(chatDir, "MessageRow.kt").readText()
        assertFalse(
            "消息行里不该再渲染扫光提示（回到消息内部就会在工具调用时跳动）",
            row.contains("ThinkingShimmerText("),
        )
        assertFalse(
            "重试倒计时同理——它和扫光提示是同一个位置的两种形态",
            row.contains("RetryCountdownHint("),
        )
    }
}

package com.psyche.memo.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生成中提示的位置守卫（2026-09-24 改版：**照 Agora 长在助手消息尾部**）。
 *
 * 历史：第一版渲染在助手消息内部（工具卡换态时跳 ✗）→ 挪成列表末尾独立项（RikkaHub 同款）；
 * 2026-09-24 用户「这个呼吸球在输出没有每次在输入框上面呀」→ 照 Agora
 * `AssistantMessageContent.kt:768` 改成**长在助手消息尾部**（操作行常驻：流式放圆点、
 * 结束放复制那排按钮，行不折叠 ⇒ 无跳变），列表末尾的独立项随之删除。
 */
class StreamingIndicatorPlacementTest {

    private val chatDir = File("src/main/java/com/psyche/memo/ui/chat")

    @Test
    fun tailDotLivesInMessageRowAndStandaloneItemIsGone() {
        val content = File(chatDir, "ChatContent.kt")
        assertTrue("找不到 ${content.path}（工作目录应当是 app 模块）", content.isFile)
        assertFalse(
            "列表末尾的独立指示项应当已删除（指示器已改长在助手消息尾部）",
            content.readText().contains("STREAMING_INDICATOR_ITEM_KEY"),
        )

        val row = File(chatDir, "MessageRow.kt").readText()
        // 2026-10-02 定稿：生成中指示 = Memo 自己的呼吸放射线，位置在助手消息尾部
        // 动作栏槽位（ZCode 同位置是 16px 旋转 loader）；「工作中 N秒」阶段头在零块
        // 空窗期也要立刻渲染（MessageRow 里以空 steps 的 AgentTraceBlock 兜底）。
        assertTrue(
            "助手消息尾部应当渲染呼吸放射线（GenerationActivityBurst）",
            row.contains("GenerationActivityBurst("),
        )
        assertTrue(
            "等首个 token 的空窗期也要立刻出「工作中」阶段头",
            row.contains("AgentTraceBlock("),
        )
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

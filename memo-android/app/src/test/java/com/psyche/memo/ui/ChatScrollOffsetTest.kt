package com.psyche.memo.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import com.psyche.memo.ui.chat.SCROLL_BOTTOM_ITEM_KEY

/**
 * 滚动命令**不许**带越界偏移。
 *
 * 用户 2026-09-15：「点击到底部这个按钮 对话界面会闪」。原因是
 * `animateScrollToItem(size - 1, Int.MAX_VALUE)`：那个偏移会被原样写进滚动位置
 * （真机日志 `firstVisible=3 offset=2147483647`），下一帧 LazyColumn 再夹一次 ——
 * 看起来就是「点一下整个对话界面闪一下」。
 *
 * 「到底」的正确写法是**有界越界下标**：`requestScrollToItem(size + 5)`
 * （上游 ChatPage.kt:179 同款），由 LazyColumn 把下标夹到末条。这条守卫扫
 * 所有 `*ScrollToItem(` 调用点，凡是**紧随其后的几行里**出现 `Int.MAX_VALUE` 的
 * 都算违规（跨行调用也算）。
 */
class ChatScrollOffsetTest {

    private val srcDir = File("src/main/java")

    @Test
    fun `no scroll command passes an unbounded offset`() {
        assertTrue(
            "expected ${srcDir.absolutePath} to exist — check the Gradle test working dir",
            srcDir.isDirectory,
        )

        val offenders = mutableListOf<String>()
        srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.invariantSeparatorsPath }
            .forEach { file ->
                // 注释里写的反例（「以前是 animateScrollToItem(…, Int.MAX_VALUE)」）不算违规。
                val lines = blankComments(file.readText()).lines()
                lines.forEachIndexed { index, line ->
                    if (!line.contains("ScrollToItem(")) return@forEachIndexed
                    // 调用可能跨行：往后看 4 行。
                    val window = lines.subList(index, minOf(index + 5, lines.size))
                    if (window.any { it.contains("Int.MAX_VALUE") }) {
                        offenders += "${file.name}:${index + 1}: ${line.trim()}"
                    }
                }
            }

        assertTrue(
            "滚动命令不许用 Int.MAX_VALUE 当偏移（会被原样写进滚动位置 → 界面闪一下）；" +
                "到底请滚末尾哨兵项（`SCROLL_BOTTOM_ITEM_KEY` / `scrollTimelineToBottom()`）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}

package com.psyche.memo.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Port coverage of opencode `packages/core/src/session/compaction.ts`
 * (+ `util/token.ts`, `runner/to-llm-message.ts`)。
 */
class SessionCompactionTest {

    private fun user(text: String) = SessionCompaction.Entry("user", listOf(SessionCompaction.Part.Text(text)))

    private fun assistant(text: String) =
        SessionCompaction.Entry("assistant", listOf(SessionCompaction.Part.Text(text)))

    // ---- Token.estimate ----

    @Test
    fun `estimate is characters over four`() {
        assertEquals(0, SessionCompaction.estimate(""))
        assertEquals(1, SessionCompaction.estimate("abc"))
        assertEquals(1, SessionCompaction.estimate("abcd"))
        assertEquals(3, SessionCompaction.estimate("a".repeat(10)))
    }

    @Test
    fun `request estimate wraps system messages and tools`() {
        val tokens = SessionCompaction.estimateRequest(
            system = "sys",
            messages = listOf("user" to "hello", "assistant" to "hi"),
            toolsJson = "[]",
        )
        assertTrue(tokens > 0)
        // JSON 文本长度 / 4 —— 与手算的字符串长度一致。
        val json = """{"system":"sys","messages":[{"role":"user","content":"hello"},""" +
            """{"role":"assistant","content":"hi"}],"tools":"[]"}"""
        assertEquals((json.length / 4.0).roundToInt(), tokens)
    }

    // ---- truncate / serialize ----

    @Test
    fun `tool output is truncated at 2000 chars`() {
        val short = "x".repeat(10)
        assertEquals(short, SessionCompaction.truncate(short))
        val long = SessionCompaction.truncate("x".repeat(2500))
        assertEquals(2000 + "\n[truncated]".length, long.length)
        assertTrue(long.endsWith("\n[truncated]"))
    }

    @Test
    fun `serialize mirrors the opencode message shapes`() {
        assertEquals(
            "[User]: hi\n[Attached image/png: pixel.png]",
            SessionCompaction.serialize(
                SessionCompaction.Entry(
                    "user",
                    listOf(SessionCompaction.Part.Text("hi")),
                    attachments = listOf("image/png: pixel.png"),
                ),
            ),
        )
        assertEquals("[System update]: rules", SessionCompaction.serialize(SessionCompaction.Entry("system", listOf(SessionCompaction.Part.Text("rules")))))
        assertEquals(
            "[Assistant]: ok\n[Assistant reasoning]: think\n[Assistant tool call]: read(path=a)\n[Tool result]: file",
            SessionCompaction.serialize(
                SessionCompaction.Entry(
                    "assistant",
                    listOf(
                        SessionCompaction.Part.Text("ok"),
                        SessionCompaction.Part.Reasoning("think"),
                        SessionCompaction.Part.ToolCall("read", "path=a", result = "file"),
                    ),
                ),
            ),
        )
        // 未完成的工具调用只有调用行；出错则是 [Tool error]。
        assertEquals(
            "[Assistant tool call]: read(path=a)",
            SessionCompaction.serialize(
                SessionCompaction.Entry("assistant", listOf(SessionCompaction.Part.ToolCall("read", "path=a"))),
            ),
        )
        assertEquals(
            "[Assistant tool call]: read(path=a)\n[Tool error]: boom",
            SessionCompaction.serialize(
                SessionCompaction.Entry(
                    "assistant",
                    listOf(SessionCompaction.Part.ToolCall("read", "path=a", error = "boom")),
                ),
            ),
        )
    }

    @Test
    fun `serialize drops empty reasoning parts`() {
        assertEquals(
            "[Assistant]: ok",
            SessionCompaction.serialize(
                SessionCompaction.Entry(
                    "assistant",
                    listOf(SessionCompaction.Part.Reasoning(""), SessionCompaction.Part.Text("ok")),
                ),
            ),
        )
    }

    // ---- select ----

    @Test
    fun `select keeps the tail within the token budget`() {
        val conversation = (1..10).map { "[User]: ${"a".repeat(40 * it)}" }
        val selection = SessionCompaction.select(conversation, tokens = 50)!!
        // 预算 50 tokens ≈ 200 字符：只装得下最后一条（400 字符）的尾巴。
        assertTrue(selection.recent.endsWith("a"))
        assertTrue(selection.head.contains("[User]: a"))
        assertTrue(SessionCompaction.estimate(selection.recent) <= 60)
    }

    @Test
    fun `select splits the boundary message by characters`() {
        // 单条 40 字符 = 10 tokens；预算 5 tokens → 保留末尾 20 字符。
        // 注意：opencode 的 `split = index + 1` 会把边界消息**整条留在 head**、再把它
        // 的前半段追加一次（上游如此），所以 head 是 40 + "\n\n" + 20 = 62 字符。
        val selection = SessionCompaction.select(listOf("[User]: " + "b".repeat(32)), tokens = 5)!!
        assertEquals(20, selection.recent.length)
        assertEquals(62, selection.head.length)
        assertEquals("[User]: " + "b".repeat(12), selection.head.takeLast(20))
        assertTrue(selection.recent.all { it == 'b' })
    }

    @Test
    fun `select returns everything as recent when it fits the budget`() {
        val conversation = listOf("[User]: one", "[Assistant]: two")
        val selection = SessionCompaction.select(conversation, tokens = 1_000)!!
        assertEquals("", selection.head)
        assertEquals(conversation.joinToString("\n\n"), selection.recent)
    }

    @Test
    fun `select has nothing to do without conversation`() {
        assertNull(SessionCompaction.select(emptyList(), 100))
    }

    @Test
    fun `select does not tear a surrogate pair`() {
        // "ab😀cd" 的 UTF-16 长度是 6；预算 1 token → 保留末尾 4 个单元（😀 + "cd"）。
        val conversation = listOf("ab\uD83D\uDE00cd")
        val selection = SessionCompaction.select(conversation, tokens = 1)!!
        assertEquals("ab\uD83D\uDE00cd\n\nab", selection.head)
        assertEquals("\uD83D\uDE00cd", selection.recent)
        // 切点没有落在代理对上（recent 以完整 emoji 开头，不是孤立低位代理）。
        assertTrue(selection.recent.startsWith("\uD83D\uDE00"))
    }

    // ---- buildPrompt ----

    @Test
    fun `buildPrompt creates an anchored summary`() {
        val prompt = SessionCompaction.buildPrompt(null, listOf("[User]: hi"), "English")
        assertTrue(prompt.startsWith("Create a new anchored summary from the conversation history."))
        assertTrue(prompt.contains("## Goal"))
        assertTrue(prompt.contains("## Relevant Files"))
        assertTrue(prompt.contains("- Write in English language"))
        assertTrue(prompt.endsWith("[User]: hi"))
        assertFalse(prompt.contains("{content}"))
        assertFalse(prompt.contains("{locale}"))
    }

    @Test
    fun `buildPrompt updates a previous summary`() {
        val prompt = SessionCompaction.buildPrompt("old summary", listOf("[User]: new"), "中文")
        assertTrue(prompt.contains("<previous-summary>\nold summary\n</previous-summary>"))
        assertTrue(prompt.contains("- Write in 中文 language"))
        assertTrue(prompt.endsWith("[User]: new"))
    }

    // ---- threshold ----

    @Test
    fun `shouldCompact follows opencode's threshold`() {
        // 128000 − max(4096, 20000) = 108000
        assertFalse(SessionCompaction.shouldCompact(108_000, 128_000, 4_096, 20_000))
        assertTrue(SessionCompaction.shouldCompact(108_001, 128_000, 4_096, 20_000))
        // 输出预算大于 buffer 时以输出预算为准。
        assertTrue(SessionCompaction.shouldCompact(90_000, 128_000, 40_000, 20_000))
        // 窗口未知 → 不自动压缩（opencode `context === undefined` 分支）。
        assertFalse(SessionCompaction.shouldCompact(999_999, 0, 0, 20_000))
    }

    @Test
    fun `settings default to opencode's values`() {
        val settings = SessionCompaction.Settings()
        assertTrue(settings.auto)
        assertEquals(20_000, settings.buffer)
        assertEquals(8_000, settings.keepTokens)
        assertEquals(128_000, settings.contextWindow)
    }

    @Test
    fun `threshold uses the larger of output budget and buffer`() {
        // 128000 − max(4096, 20000) = 108000
        assertEquals(108_000, SessionCompaction.thresholdTokens(128_000, 4_096, 20_000))
        // 输出预算更大时以它为准。
        assertEquals(88_000, SessionCompaction.thresholdTokens(128_000, 40_000, 20_000))
        // 兜底 ≥1（窗口比缓冲还小时进度条不能除以 0）。
        assertEquals(1, SessionCompaction.thresholdTokens(10_000, 0, 20_000))
    }

    // ---- checkpoint ----

    @Test
    fun `checkpoint text wraps summary and recent context`() {
        val text = SessionCompaction.checkpointText("SUM", "RECENT")
        assertTrue(text.startsWith("<conversation-checkpoint>"))
        assertTrue(text.endsWith("</conversation-checkpoint>"))
        assertTrue(text.contains("<summary>\nSUM\n</summary>"))
        assertTrue(text.contains("<recent-context>\nRECENT\n</recent-context>"))
    }

    // ---- history window ----

    @Test
    fun `window keeps the last checkpoint plus everything after its boundary`() {
        val entries = listOf(
            SessionCompaction.WindowEntry("m0", 0, null),
            SessionCompaction.WindowEntry("m1", 1, null),
            SessionCompaction.WindowEntry("cp", 2, SessionCompaction.Checkpoint("s", "r", 1)),
            SessionCompaction.WindowEntry("m2", 3, null),
            SessionCompaction.WindowEntry("m3", 4, null),
        )
        assertEquals(listOf("cp", "m2", "m3"), SessionCompaction.windowIds(entries))
    }

    @Test
    fun `window without a checkpoint is the whole history`() {
        val entries = listOf(
            SessionCompaction.WindowEntry("m0", 0, null),
            SessionCompaction.WindowEntry("m1", 1, null),
        )
        assertEquals(listOf("m0", "m1"), SessionCompaction.windowIds(entries))
    }

    @Test
    fun `window uses the newest checkpoint after repeated compactions`() {
        val entries = listOf(
            SessionCompaction.WindowEntry("cp1", 2, SessionCompaction.Checkpoint("s1", "r1", 1)),
            SessionCompaction.WindowEntry("m2", 3, null),
            SessionCompaction.WindowEntry("cp2", 4, SessionCompaction.Checkpoint("s2", "r2", 3)),
            SessionCompaction.WindowEntry("m3", 5, null),
        )
        assertEquals(listOf("cp2", "m3"), SessionCompaction.windowIds(entries))
    }

    @Test
    fun `user and assistant entries round trip through serialize`() {
        assertEquals("[User]: hi", SessionCompaction.serialize(user("hi")))
        assertEquals("[Assistant]: yo", SessionCompaction.serialize(assistant("yo")))
        assertEquals("", SessionCompaction.serialize(SessionCompaction.Entry("compaction")))
    }
}

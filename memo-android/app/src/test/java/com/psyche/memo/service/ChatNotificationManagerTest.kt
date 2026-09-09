package com.psyche.memo.service

import com.psyche.memo.data.model.MessagePart
import com.psyche.memo.data.model.ReasoningPart
import com.psyche.memo.data.model.ReasoningSegment
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.data.model.ToolCallPart
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for [determineContent] — the RikkaHub
 * ChatNotificationManager.determineNotificationContent port. Pin the
 * decision order (running tool > thinking > writing > idle), the MCP-name
 * strip (substringAfterLast "__"), and the RikkaHub truncation amounts
 * (tool input take(100), reasoning/text takeLast(200)).
 */
class ChatNotificationManagerTest {

    private fun toolPart(name: String, input: String, content: String? = null): ToolCallPart =
        ToolCallPart.encode(
            id = "call_1",
            name = name,
            arguments = JsonPrimitive(input),
            content = content?.let { JsonPrimitive(it) },
            server = false,
        )

    private fun seg(finishedAt: Long? = null) = ReasoningSegment(startAt = 1L, finishedAt = finishedAt)

    // ---- decision order -------------------------------------------------

    @Test
    fun `running tool wins over everything`() {
        val parts: List<MessagePart> = listOf(
            TextPart("earlier reply"),
            ReasoningPart("thinking text"),
            toolPart("search_web", "{\"q\":\"x\"}"),
        )
        val c = determineContent(parts, listOf(seg(9L)))
        assertEquals(LiveUpdateState.TOOL, c.state)
        assertEquals("search_web", c.toolName)
        assertEquals("{\"q\":\"x\"}", c.text)
    }

    @Test
    fun `unfinished reasoning wins over text`() {
        val parts: List<MessagePart> = listOf(
            TextPart("older text"),
            ReasoningPart("let me think"),
            TextPart("partial answer"),
        )
        // One reasoning part pairs with segments[0] (positional zip); it is
        // unfinished, so the notification must report thinking.
        val c = determineContent(parts, listOf(seg(null)))
        assertEquals(LiveUpdateState.THINKING, c.state)
        assertEquals("let me think", c.text)
    }

    @Test
    fun `finished reasoning falls through to writing`() {
        val parts: List<MessagePart> = listOf(
            ReasoningPart("done thinking"),
            TextPart("the answer"),
        )
        val c = determineContent(parts, listOf(seg(9L)))
        assertEquals(LiveUpdateState.WRITING, c.state)
        assertEquals("the answer", c.text)
    }

    @Test
    fun `no parts is idle`() {
        val c = determineContent(emptyList(), emptyList())
        assertEquals(LiveUpdateState.IDLE, c.state)
        assertEquals("", c.text)
    }

    // ---- tool specifics -------------------------------------------------

    @Test
    fun `finished tool does not count as running`() {
        val parts: List<MessagePart> = listOf(
            toolPart("search_web", "{}", content = "result json"),
            TextPart("final answer"),
        )
        val c = determineContent(parts, emptyList())
        assertEquals(LiveUpdateState.WRITING, c.state)
    }

    @Test
    fun `mcp double underscore prefix is stripped`() {
        val parts: List<MessagePart> = listOf(
            toolPart("mcp__github__create_issue", "{}"),
        )
        val c = determineContent(parts, emptyList())
        assertEquals("create_issue", c.toolName)
    }

    @Test
    fun `tool input truncated to first 100 chars`() {
        val input = "x".repeat(250)
        val c = determineContent(listOf(toolPart("tool", input)), emptyList())
        assertEquals(100, c.text.length)
    }

    // ---- truncation amounts ---------------------------------------------

    @Test
    fun `reasoning text keeps last 200 chars`() {
        val parts: List<MessagePart> = listOf(ReasoningPart("a".repeat(300)))
        val c = determineContent(parts, listOf(seg(null)))
        assertEquals(200, c.text.length)
        assertEquals("a".repeat(200), c.text)
    }

    @Test
    fun `writing text keeps last 200 chars`() {
        val parts: List<MessagePart> = listOf(TextPart("b".repeat(300)))
        val c = determineContent(parts, emptyList())
        assertEquals(200, c.text.length)
        assertEquals("b".repeat(200), c.text)
    }

    // ---- reasoning/segment positional zip -------------------------------

    @Test
    fun `last reasoning part pairs with its own segment`() {
        // Two reasoning parts: the first finished, the second still running —
        // notification must reflect the LAST one (positional zip contract).
        val parts: List<MessagePart> = listOf(
            ReasoningPart("first"),
            ReasoningPart("second"),
        )
        val c = determineContent(parts, listOf(seg(9L), seg(null)))
        assertEquals(LiveUpdateState.THINKING, c.state)
        assertEquals("second", c.text)
    }

    @Test
    fun `reasoning without segment entry counts as running`() {
        // Defensive: fewer segments than reasoning parts (segment list not
        // yet extended) — getOrNull returns null, finishedAt != present.
        val parts: List<MessagePart> = listOf(ReasoningPart("solo"))
        val c = determineContent(parts, emptyList())
        assertEquals(LiveUpdateState.THINKING, c.state)
    }
}

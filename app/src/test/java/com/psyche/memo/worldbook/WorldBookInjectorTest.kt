package com.psyche.memo.worldbook

import com.psyche.memo.common.logging.ContextSource
import com.psyche.memo.common.logging.ContextTag
import com.psyche.memo.data.model.WorldBook
import com.psyche.memo.data.model.WorldBookEntry
import com.psyche.memo.data.model.WorldBookInjectionPosition
import com.psyche.memo.data.model.WorldBookInjectionRole
import com.psyche.memo.llm.client.LlmMessage
import com.psyche.memo.logging.ContextLogAssembler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

/**
 * Pure-JVM tests for [WorldBookInjector].
 *
 * Mirrors the Dart `message_builder_service.dart` L1776-2106 contract: scan
 * depth, keyword/regex/constantActive trigger logic, the 5 injection
 * positions, role-based wrapping, and the tool-message safe-insert index.
 */
class WorldBookInjectorTest {

    // —— helpers ——

    private fun entry(
        id: String = "e",
        name: String = id,
        enabled: Boolean = true,
        priority: Int = 0,
        position: WorldBookInjectionPosition = WorldBookInjectionPosition.AFTER_SYSTEM_PROMPT,
        content: String = id,
        injectDepth: Int = 4,
        role: WorldBookInjectionRole = WorldBookInjectionRole.USER,
        keywords: List<String> = emptyList(),
        useRegex: Boolean = false,
        caseSensitive: Boolean = false,
        scanDepth: Int = 4,
        constantActive: Boolean = false,
    ) = WorldBookEntry(
        id = id, name = name, enabled = enabled, priority = priority,
        position = position, content = content, injectDepth = injectDepth,
        role = role, keywords = keywords, useRegex = useRegex,
        caseSensitive = caseSensitive, scanDepth = scanDepth,
        constantActive = constantActive,
    )

    /** Shortcut: an entry that always triggers (no keyword needed). */
    private fun always(
        content: String,
        position: WorldBookInjectionPosition = WorldBookInjectionPosition.AFTER_SYSTEM_PROMPT,
        role: WorldBookInjectionRole = WorldBookInjectionRole.USER,
        priority: Int = 0,
        injectDepth: Int = 4,
    ) = entry(
        id = content, content = content, position = position, role = role,
        priority = priority, injectDepth = injectDepth, constantActive = true,
    )

    private fun book(
        id: String,
        enabled: Boolean = true,
        vararg entries: WorldBookEntry,
    ) = WorldBook(id = id, name = id, description = "", enabled = enabled, entries = entries.toList())

    private fun msgs(vararg m: LlmMessage) = m.toList()

    // —— guards ——

    @Test
    fun `empty messages are returned as-is`() {
        val out = WorldBookInjector.inject(emptyList(), listOf(book("b", entries = arrayOf(always("x")))), listOf("b"))
        assertEquals(emptyList<LlmMessage>(), out)
    }

    @Test
    fun `empty books list returns messages as-is`() {
        val out = WorldBookInjector.inject(msgs(LlmMessage("user", content = "hi")), emptyList(), listOf("b"))
        assertEquals(1, out.size)
        assertEquals("hi", out[0].content)
    }

    @Test
    fun `empty activeIds returns messages as-is`() {
        val out = WorldBookInjector.inject(
            msgs(LlmMessage("user", content = "hi")),
            listOf(book("b", entries = arrayOf(always("x")))),
            emptyList(),
        )
        assertEquals(1, out.size)
    }

    @Test
    fun `disabled books are ignored`() {
        val out = WorldBookInjector.inject(
            msgs(LlmMessage("user", content = "hi")),
            listOf(book("b", enabled = false, entries = arrayOf(always("x")))),
            listOf("b"),
        )
        assertEquals(1, out.size)
        assertFalse("Should not contain lore text", out.joinToString { it.content ?: "" }.contains("x"))
    }

    @Test
    fun `books not in activeIds are ignored`() {
        val out = WorldBookInjector.inject(
            msgs(LlmMessage("user", content = "hi")),
            listOf(book("b", entries = arrayOf(always("x")))),
            listOf("other"),
        )
        assertEquals(1, out.size)
    }

    // —— trigger logic ——

    @Test
    fun `plain keyword match triggers entry`() {
        val out = WorldBookInjector.inject(
            msgs(LlmMessage("user", content = "the dragon wakes")),
            listOf(book("b", entries = arrayOf(entry(content = "Dragon lore", keywords = listOf("dragon"))))),
            listOf("b"),
        )
        assertEquals(2, out.size)
        assertTrue(out[0].content!!.contains("Dragon lore"))
    }

    @Test
    fun `case-insensitive keyword match is the default`() {
        val out = WorldBookInjector.inject(
            msgs(LlmMessage("user", content = "the DRAGON wakes")),
            listOf(book("b", entries = arrayOf(entry(content = "X", keywords = listOf("dragon"))))),
            listOf("b"),
        )
        assertEquals(2, out.size)
    }

    @Test
    fun `case-sensitive keyword does not match different case`() {
        val out = WorldBookInjector.inject(
            msgs(LlmMessage("user", content = "the DRAGON wakes")),
            listOf(book("b", entries = arrayOf(entry(
                content = "X", keywords = listOf("dragon"), caseSensitive = true,
            )))),
            listOf("b"),
        )
        assertEquals(1, out.size)
    }

    @Test
    fun `regex keyword match triggers entry`() {
        val out = WorldBookInjector.inject(
            msgs(LlmMessage("user", content = "issue #42 closed")),
            listOf(book("b", entries = arrayOf(entry(
                content = "X", keywords = listOf("issue #\\d+"), useRegex = true,
            )))),
            listOf("b"),
        )
        assertEquals(2, out.size)
    }

    @Test
    fun `invalid regex keyword is silently ignored`() {
        val out = WorldBookInjector.inject(
            msgs(LlmMessage("user", content = "hello world")),
            listOf(book("b", entries = arrayOf(entry(
                content = "X", keywords = listOf("[bad("), useRegex = true,
            )))),
            listOf("b"),
        )
        assertEquals(1, out.size)
    }

    @Test
    fun `constantActive triggers even with no keyword match`() {
        val out = WorldBookInjector.inject(
            msgs(LlmMessage("user", content = "totally unrelated")),
            listOf(book("b", entries = arrayOf(entry(content = "always", constantActive = true)))),
            listOf("b"),
        )
        assertEquals(2, out.size)
    }

    @Test
    fun `disabled entry never triggers`() {
        val out = WorldBookInjector.inject(
            msgs(LlmMessage("user", content = "the dragon wakes")),
            listOf(book("b", entries = arrayOf(entry(
                content = "X", keywords = listOf("dragon"), enabled = false,
            )))),
            listOf("b"),
        )
        assertEquals(1, out.size)
    }

    @Test
    fun `multiple keywords any match triggers`() {
        val out = WorldBookInjector.inject(
            msgs(LlmMessage("user", content = "cat sat")),
            listOf(book("b", entries = arrayOf(entry(
                content = "X", keywords = listOf("dog", "cat", "fish"),
            )))),
            listOf("b"),
        )
        assertEquals(2, out.size)
    }

    @Test
    fun `empty keywords and not constantActive never triggers`() {
        val out = WorldBookInjector.inject(
            msgs(LlmMessage("user", content = "hello world")),
            listOf(book("b", entries = arrayOf(entry(content = "X", keywords = emptyList())))),
            listOf("b"),
        )
        assertEquals(1, out.size)
    }

    // —— priority & file order ——

    @Test
    fun `higher priority wins tie-breaks by sequence asc`() {
        // Both trigger, priorities differ, position=AFTER → merged into
        // system message in priority order (ALPHA first, then beta).
        val a = entry(id = "a", content = "ALPHA", keywords = listOf("magic"), priority = 10)
        val b = entry(id = "b", content = "beta", keywords = listOf("magic"), priority = 1)
        val out = WorldBookInjector.inject(
            msgs(LlmMessage("system", content = "S"), LlmMessage("user", content = "magic happens")),
            listOf(book("b1", entries = arrayOf(a)), book("b2", entries = arrayOf(b))),
            listOf("b1", "b2"),
        )
        assertEquals(2, out.size)
        assertEquals("system", out[0].role)
        val sys = out[0].content!!
        val alphaIdx = sys.indexOf("ALPHA")
        val betaIdx = sys.indexOf("beta")
        assertTrue("ALPHA should appear in system content, got: $sys", alphaIdx >= 0)
        assertTrue("beta should appear in system content, got: $sys", betaIdx >= 0)
        assertTrue("priority 10 should come before priority 1, got sys='$sys'",
            alphaIdx < betaIdx)
    }

    @Test
    fun `equal priority orders by file sequence asc`() {
        val first = entry(id = "first", content = "FIRST", keywords = listOf("magic"), priority = 5)
        val second = entry(id = "second", content = "SECOND", keywords = listOf("magic"), priority = 5)
        val out = WorldBookInjector.inject(
            msgs(LlmMessage("system", content = "S"), LlmMessage("user", content = "magic")),
            listOf(book("b", entries = arrayOf(first, second))),
            listOf("b"),
        )
        assertEquals(2, out.size)
        val sys = out[0].content!!
        val firstIdx = sys.indexOf("FIRST")
        val secondIdx = sys.indexOf("SECOND")
        assertTrue("FIRST and SECOND both in system, got: $sys", firstIdx >= 0 && secondIdx >= 0)
        assertTrue("file-order asc, got sys='$sys'", firstIdx < secondIdx)
    }

    // —— position: BEFORE / AFTER system ——

    @Test
    fun `beforeSystemPrompt prepends to existing system message`() {
        val out = WorldBookInjector.inject(
            msgs(
                LlmMessage("system", content = "ORIG"),
                LlmMessage("user", content = "hi"),
            ),
            listOf(book("b", entries = arrayOf(always(
                "BEFORE", position = WorldBookInjectionPosition.BEFORE_SYSTEM_PROMPT,
            )))),
            listOf("b"),
        )
        assertEquals(2, out.size)
        assertTrue("system msg should start with BEFORE then ORIG, got: ${out[0].content}",
            out[0].content!!.startsWith("BEFORE\nORIG"))
    }

    @Test
    fun `afterSystemPrompt appends to existing system message`() {
        val out = WorldBookInjector.inject(
            msgs(
                LlmMessage("system", content = "ORIG"),
                LlmMessage("user", content = "hi"),
            ),
            listOf(book("b", entries = arrayOf(always(
                "AFTER", position = WorldBookInjectionPosition.AFTER_SYSTEM_PROMPT,
            )))),
            listOf("b"),
        )
        assertEquals(2, out.size)
        assertTrue("system msg should be ORIG then AFTER, got: ${out[0].content}",
            out[0].content!!.endsWith("ORIG\nAFTER"))
    }

    @Test
    fun `before and after merge around system msg`() {
        val out = WorldBookInjector.inject(
            msgs(LlmMessage("system", content = "ORIG")),
            listOf(book("b", entries = arrayOf(
                always("BEFORE", position = WorldBookInjectionPosition.BEFORE_SYSTEM_PROMPT),
                always("AFTER", position = WorldBookInjectionPosition.AFTER_SYSTEM_PROMPT),
            ))),
            listOf("b"),
        )
        assertEquals(1, out.size)
        assertEquals("BEFORE\nORIG\nAFTER", out[0].content)
    }

    @Test
    fun `no system msg creates one at top with before-after content`() {
        val out = WorldBookInjector.inject(
            msgs(LlmMessage("user", content = "hi")),
            listOf(book("b", entries = arrayOf(
                always("BEFORE", position = WorldBookInjectionPosition.BEFORE_SYSTEM_PROMPT),
                always("AFTER", position = WorldBookInjectionPosition.AFTER_SYSTEM_PROMPT),
            ))),
            listOf("b"),
        )
        assertEquals(2, out.size)
        assertEquals("system", out[0].role)
        assertEquals("BEFORE\nAFTER", out[0].content)
        assertEquals("user", out[1].role)
    }

    // —— position: TOP_OF_CHAT / BOTTOM_OF_CHAT ——

    @Test
    fun `topOfChat inserts before first user message`() {
        val out = WorldBookInjector.inject(
            msgs(
                LlmMessage("system", content = "sys"),
                LlmMessage("user", content = "hi"),
                LlmMessage("assistant", content = "hello"),
            ),
            listOf(book("b", entries = arrayOf(always(
                "TOP", position = WorldBookInjectionPosition.TOP_OF_CHAT,
            )))),
            listOf("b"),
        )
        // expect: system, [TOP,user], assistant
        assertEquals(4, out.size)
        assertEquals("system", out[0].role)
        assertTrue("TOP injected just before first user, got: $out", out[1].content!!.contains("TOP"))
        assertEquals("user", out[2].role)
        assertEquals("assistant", out[3].role)
    }

    @Test
    fun `topOfChat appends at end when no user message exists`() {
        val out = WorldBookInjector.inject(
            msgs(LlmMessage("system", content = "sys")),
            listOf(book("b", entries = arrayOf(always(
                "TOP", position = WorldBookInjectionPosition.TOP_OF_CHAT,
            )))),
            listOf("b"),
        )
        // expect: system, [TOP]
        assertEquals(2, out.size)
        assertTrue(out[1].content!!.contains("TOP"))
    }

    @Test
    fun `bottomOfChat inserts before last message`() {
        val out = WorldBookInjector.inject(
            msgs(
                LlmMessage("system", content = "sys"),
                LlmMessage("user", content = "hi"),
                LlmMessage("assistant", content = "hello"),
            ),
            listOf(book("b", entries = arrayOf(always(
                "BOTTOM", position = WorldBookInjectionPosition.BOTTOM_OF_CHAT,
            )))),
            listOf("b"),
        )
        // expect: system, user, [BOTTOM], assistant
        assertEquals(4, out.size)
        assertTrue("BOTTOM before assistant, got: $out", out[2].content!!.contains("BOTTOM"))
        assertEquals("assistant", out[3].role)
    }

    // —— position: AT_DEPTH ——

    @Test
    fun `atDepth injects at length-minus-depth`() {
        // 4 messages (system, user1, assistant1, user2); depth=1 → before last
        val out = WorldBookInjector.inject(
            msgs(
                LlmMessage("system", content = "sys"),
                LlmMessage("user", content = "u1"),
                LlmMessage("assistant", content = "a1"),
                LlmMessage("user", content = "u2"),
            ),
            listOf(book("b", entries = arrayOf(always(
                "AT1", position = WorldBookInjectionPosition.AT_DEPTH, injectDepth = 1,
            )))),
            listOf("b"),
        )
        // expected index for injection: 4 - 1 = 3 (before u2)
        // result: system, user1, assistant1, [AT1], user2
        assertEquals(5, out.size)
        assertTrue("AT1 should be at index 3, got: $out", out[3].content!!.contains("AT1"))
        assertEquals("user", out[4].role)
        assertEquals("u2", out[4].content)
    }

    @Test
    fun `atDepth clamps negative depth to 1`() {
        val out = WorldBookInjector.inject(
            msgs(
                LlmMessage("user", content = "u1"),
                LlmMessage("user", content = "u2"),
            ),
            listOf(book("b", entries = arrayOf(always(
                "AT0", position = WorldBookInjectionPosition.AT_DEPTH, injectDepth = 0,
            )))),
            listOf("b"),
        )
        // depth clamped to 1 → insert at length-1 (before last)
        assertEquals(3, out.size)
        assertTrue("AT0 should be at index 1, got: $out", out[1].content!!.contains("AT0"))
    }

    // —— role ——

    @Test
    fun `user role wraps content in system tags`() {
        val out = WorldBookInjector.inject(
            msgs(LlmMessage("user", content = "hi")),
            listOf(book("b", entries = arrayOf(always(
                "WRAPPED", role = WorldBookInjectionRole.USER,
                position = WorldBookInjectionPosition.TOP_OF_CHAT,
            )))),
            listOf("b"),
        )
        // should have a user message with <system>...</system>
        val injected = out.firstOrNull { it.role == "user" && it.content != "hi" }
        assertNotNull("Expected a user-rendered injection, got: $out", injected)
        assertEquals("<system>\nWRAPPED\n</system>", injected!!.content)
    }

    @Test
    fun `assistant role emits plain text`() {
        val out = WorldBookInjector.inject(
            msgs(LlmMessage("user", content = "hi")),
            listOf(book("b", entries = arrayOf(always(
                "PLAIN", role = WorldBookInjectionRole.ASSISTANT,
                position = WorldBookInjectionPosition.TOP_OF_CHAT,
            )))),
            listOf("b"),
        )
        val injected = out.firstOrNull { it.role == "assistant" }
        assertNotNull(injected)
        assertEquals("PLAIN", injected!!.content)
    }

    // —— safe-insert index ——

    @Test
    fun `topOfChat skips over tool messages to land on a non-tool slot`() {
        // user → assistant → tool → assistant; top injection should land
        // before the first user (index 0).
        val out = WorldBookInjector.inject(
            msgs(
                LlmMessage("user", content = "u1"),
                LlmMessage("assistant", content = "a1"),
                LlmMessage("tool", content = "tool1", toolCallId = "1"),
                LlmMessage("assistant", content = "a2"),
            ),
            listOf(book("b", entries = arrayOf(always(
                "TOP", position = WorldBookInjectionPosition.TOP_OF_CHAT,
            )))),
            listOf("b"),
        )
        // expect: [TOP], user, assistant, tool, assistant
        assertEquals(5, out.size)
        assertTrue("TOP inserted at index 0, got: $out", out[0].content!!.contains("TOP"))
        assertEquals("user", out[1].role)
    }

    // —— scan depth context ——

    @Test
    fun `scanDepth only inspects the last N user-assistant messages`() {
        // depth=2: only the most recent 2 user/assistant contents are
        // searched; an older occurrence should not trigger.
        val out = WorldBookInjector.inject(
            msgs(
                LlmMessage("user", content = "ancient dragon story"),
                LlmMessage("assistant", content = "okay"),
                LlmMessage("user", content = "what about cats?"),
                LlmMessage("assistant", content = "cats are nice"),
            ),
            listOf(book("b", entries = arrayOf(entry(
                content = "CAT-LORE", keywords = listOf("dragon"),
                scanDepth = 2, // only last 2 messages
            )))),
            listOf("b"),
        )
        // dragon is only in the first user msg → outside the depth=2 window
        assertEquals(4, out.size)
        assertFalse("Should not trigger on dragon outside scan depth", out.joinToString { it.content ?: "" }.contains("CAT-LORE"))
    }

    @Test
    fun `scanDepth includes older message when depth covers it`() {
        val out = WorldBookInjector.inject(
            msgs(
                LlmMessage("user", content = "ancient dragon story"),
                LlmMessage("assistant", content = "okay"),
                LlmMessage("user", content = "what about cats?"),
            ),
            listOf(book("b", entries = arrayOf(entry(
                content = "DRAGON-LORE", keywords = listOf("dragon"),
                scanDepth = 3,
            )))),
            listOf("b"),
        )
        // depth=3 covers all 3 user/assistant msgs → dragon in oldest triggers
        assertEquals(4, out.size)
        assertTrue(out.joinToString { it.content ?: "" }.contains("DRAGON-LORE"))
    }

    // —— activeIds precedence ——

    @Test
    fun `multiple books merged into one system message block`() {
        val out = WorldBookInjector.inject(
            msgs(LlmMessage("system", content = "S")),
            listOf(
                book("b1", entries = arrayOf(always(
                    "ONE", position = WorldBookInjectionPosition.AFTER_SYSTEM_PROMPT,
                ))),
                book("b2", entries = arrayOf(always(
                    "TWO", position = WorldBookInjectionPosition.AFTER_SYSTEM_PROMPT,
                ))),
            ),
            listOf("b1", "b2"),
        )
        assertEquals(1, out.size)
        assertEquals("S\nONE\nTWO", out[0].content)
    }

    // —— context-log tagging ——

    /** Slices a message the way the context-log reader does. */
    private fun segments(message: LlmMessage) =
        ContextLogAssembler.segmentsFromTaggedMessage(message)

    @Test
    fun `tagging is off by default`() {
        val out = WorldBookInjector.inject(
            msgs(LlmMessage("user", content = "hi")),
            listOf(book("b", entries = arrayOf(always("TOP", position = WorldBookInjectionPosition.TOP_OF_CHAT)))),
            listOf("b"),
        )
        assertEquals(emptyList<ContextTag>(), out[0].contextTags)
    }

    @Test
    fun `topOfChat injection carries the worldBook tag and position`() {
        val out = WorldBookInjector.inject(
            msgs(LlmMessage("user", content = "hi")),
            listOf(book("b", entries = arrayOf(always("TOP", position = WorldBookInjectionPosition.TOP_OF_CHAT)))),
            listOf("b"),
            tagContextLog = true,
        )
        val injected = out.first { it.contextTags.isNotEmpty() }
        val segment = segments(injected).single()
        assertEquals(ContextSource.worldBook, segment.source)
        assertEquals(injected.content, segment.text)
        assertEquals(
            "TOP_OF_CHAT",
            (segment.meta?.get("position") as? JsonPrimitive)?.content,
        )
    }

    @Test
    fun `afterSystemPrompt keeps the system tags and appends a worldBook segment`() {
        val system = LlmMessage(
            role = "system",
            content = "PROMPT",
            contextTags = listOf(ContextTag(ContextSource.systemPrompt, "PROMPT".length)),
        )
        val out = WorldBookInjector.inject(
            msgs(system, LlmMessage("user", content = "hi")),
            listOf(book("b", entries = arrayOf(always(
                "AFTER", position = WorldBookInjectionPosition.AFTER_SYSTEM_PROMPT,
            )))),
            listOf("b"),
            tagContextLog = true,
        )
        val sys = out.first { it.role == "system" }
        assertEquals("PROMPT\nAFTER", sys.content)
        val parts = segments(sys)
        assertEquals(listOf(ContextSource.systemPrompt, ContextSource.worldBook), parts.map { it.source })
        assertEquals(listOf("PROMPT", "AFTER"), parts.map { it.text.trimStart('\n') })
    }

    @Test
    fun `beforeSystemPrompt owns the separator and keeps the original untouched`() {
        val system = LlmMessage(
            role = "system",
            content = "PROMPT",
            contextTags = listOf(ContextTag(ContextSource.systemPrompt, "PROMPT".length)),
        )
        val out = WorldBookInjector.inject(
            msgs(system),
            listOf(book("b", entries = arrayOf(always(
                "BEFORE", position = WorldBookInjectionPosition.BEFORE_SYSTEM_PROMPT,
            )))),
            listOf("b"),
            tagContextLog = true,
        )
        val sys = out.single()
        val parts = segments(sys)
        assertEquals(listOf(ContextSource.worldBook, ContextSource.systemPrompt), parts.map { it.source })
        // The world-book block owns the newline it inserted.
        assertEquals("BEFORE\n", parts[0].text)
        assertEquals("PROMPT", parts[1].text)
    }

    @Test
    fun `untagged system message gets a systemPrompt tag before the worldBook block`() {
        val out = WorldBookInjector.inject(
            msgs(LlmMessage("system", content = "LEGACY")),
            listOf(book("b", entries = arrayOf(always(
                "AFTER", position = WorldBookInjectionPosition.AFTER_SYSTEM_PROMPT,
            )))),
            listOf("b"),
            tagContextLog = true,
        )
        val parts = segments(out.single())
        assertEquals(listOf(ContextSource.systemPrompt, ContextSource.worldBook), parts.map { it.source })
        assertEquals("LEGACY", parts[0].text)
    }
}

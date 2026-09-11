package com.psyche.memo.provider

import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.data.model.ChatMessage
import com.psyche.memo.data.model.Conversation
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.ui.MemoryPromptLang
import com.psyche.memo.ui.MemoryProviderV2
import com.psyche.memo.ui.MemoryType
import com.psyche.memo.ui.UserProfileRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * memory_pipeline.dart — the pure steps (gatekeeper / extractor / distiller
 * parsing, window text, version collapse) and the Gatekeeper → Extract →
 * Smart Add → Distiller run against a scripted model.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryPipelineTest {

    private lateinit var container: AppContainerImpl

    @Before
    fun setUp() {
        container = AppContainerImpl(ApplicationProvider.getApplicationContext())
        container.database.writableDatabase.execSQL("DELETE FROM memory_entry_rows")
        container.database.writableDatabase.execSQL("DELETE FROM user_profile_field_rows")
        container.database.writableDatabase.execSQL("DELETE FROM conversation_rows")
    }

    // ---- gatekeeper ----------------------------------------------------------

    @Test
    fun `gatekeeper reads the tag and tolerates prose`() {
        assertEquals(
            MemoryGateParseResult.WORTH_REMEMBERING,
            MemoryGatekeeper.parse("<user_memory>true</user_memory>"),
        )
        assertEquals(
            MemoryGateParseResult.SKIP,
            MemoryGatekeeper.parse("Sure!\n<USER_MEMORY> false </USER_MEMORY>\nhope that helps"),
        )
        assertEquals(MemoryGateParseResult.MALFORMED, MemoryGatekeeper.parse("no tag here"))
    }

    @Test
    fun `gatekeeper prompt prefers the override and fills the conversation`() {
        val prompt = MemoryGatekeeper.buildPrompt(
            lang = MemoryPromptLang.en,
            conversation = "User: hi",
            overrideEn = "CHECK<<{{conversation}}>>",
        )
        assertEquals("CHECK<<User: hi>>", prompt)
        // An empty override falls back to the built-in template.
        assertTrue(MemoryGatekeeper.buildPrompt(MemoryPromptLang.zh, "对话").contains("对话"))
    }

    // ---- extractor -----------------------------------------------------------

    @Test
    fun `extractor requires the extracted tag`() {
        assertFalse(MemoryExtractor.parse("<item type=\"identity\">x</item>").ok)
        assertTrue(MemoryExtractor.parse("<extracted></extracted>").ok)
    }

    @Test
    fun `extractor keeps valid items and drops the rest`() {
        val parsed = MemoryExtractor.parse(
            """
            <extracted>
              <item type="identity" scope="assistant">prefers tea</item>
              <item type="nonsense">dropped</item>
              <item type="workflow"></item>
              <item type="voice" scope="global">speaks briefly</item>
              <item type="voice">no scope</item>
            </extracted>
            """.trimIndent(),
        )
        assertTrue(parsed.ok)
        assertEquals(listOf("prefers tea", "speaks briefly", "no scope"), parsed.items.map { it.content })
        assertEquals(MemoryType.identity, parsed.items[0].type)
        assertEquals("assistant", parsed.items[0].scopeAttr)
        assertEquals("global", parsed.items[1].scopeAttr)
        assertNull(parsed.items[2].scopeAttr)
    }

    @Test
    fun `extractor caps at ten items`() {
        val body = (1..14).joinToString("\n") { "<item type=\"identity\">m$it</item>" }
        val parsed = MemoryExtractor.parse("<extracted>$body</extracted>")
        assertEquals(10, parsed.items.size)
    }

    @Test
    fun `extractor injects the scope rule for tool default policies`() {
        val zh = MemoryExtractor.buildPrompt(
            lang = MemoryPromptLang.zh,
            conversation = "C",
            existingMemory = "E",
            writeScope = "toolDefaultGlobal",
        )
        assertTrue(zh.contains("## 已有记忆"))
        assertTrue(zh.contains("{{conversation}}").not() || zh.contains("C"))
        assertTrue(zh.contains("E"))

        val global = MemoryExtractor.buildPrompt(
            lang = MemoryPromptLang.zh,
            conversation = "C",
            existingMemory = "E",
            writeScope = "alwaysGlobal",
        )
        // The rule is only added for the toolDefault* policies (this one has no
        // scope attribute in its built-in template).
        assertTrue(global.contains("E"))
    }

    // ---- distiller -----------------------------------------------------------

    @Test
    fun `distiller parses fields and drops invalid keys`() {
        val parsed = MemoryProfileDistiller(container, container.memoryProviderV2).parse(
            """Sure: {"fields":[{"key":"preferred_name","value":" Dee "},""" +
                """{"key":"nope","value":"x"},{"key":"location","value":"  "}]}""",
        )
        assertTrue(parsed.ok)
        assertEquals(listOf("preferred_name" to "Dee"), parsed.fields.map { it.key to it.value })

        assertFalse(MemoryProfileDistiller(container, container.memoryProviderV2).parse("no json").ok)
    }

    // ---- window helpers ------------------------------------------------------

    @Test
    fun `collapse keeps the newest version unless another is selected`() {
        val messages = listOf(
            message("m1", "user", "hi", group = "g1", version = 0, order = 0),
            message("m2", "assistant", "old", group = "g2", version = 0, order = 1),
            message("m3", "assistant", "new", group = "g2", version = 1, order = 2),
            message("m4", "user", "again", group = "g3", version = 0, order = 3),
        )
        assertEquals(
            listOf("hi", "new", "again"),
            MemoryPipelineService.collapseSelectedVersions(messages, emptyMap()).map { it.content },
        )
        assertEquals(
            listOf("hi", "old", "again"),
            MemoryPipelineService.collapseSelectedVersions(messages, mapOf("g2" to 0)).map { it.content },
        )
    }

    @Test
    fun `conversation text uses role prefixes and skips empty turns`() {
        val text = MemoryPipelineService.buildConversationText(
            listOf(
                message("m1", "user", "hi", group = "g1", version = 0, order = 0),
                message("m2", "system", "ignored", group = "g2", version = 0, order = 1),
                message("m3", "assistant", "", group = "g3", version = 0, order = 2),
                message("m4", "assistant", "hello", group = "g4", version = 0, order = 3),
            ),
            MemoryPromptLang.en,
        )
        assertEquals("User: hi\n\nAssistant: hello", text)
        assertTrue(
            MemoryPipelineService.buildConversationText(
                listOf(message("m1", "user", "hi", group = "g1", version = 0, order = 0)),
                MemoryPromptLang.zh,
            ).startsWith("用户："),
        )
    }

    // ---- the run -------------------------------------------------------------

    private fun seedConversation(id: String = "c1", assistantId: String = "a1"): Conversation {
        val conversation = Conversation(id = id, title = "T", createdAt = 1L, updatedAt = 2L)
        conversation.assistantId = assistantId
        container.conversationDao.insert(conversation)
        return conversation
    }

    private fun assistant(
        autoOrganize: Boolean = true,
        smartAddMode: String = "batched",
    ) = Assistant(
        id = "a1",
        name = "Test",
        enableMemory = true,
        autoOrganizeMemory = autoOrganize,
        memoryOrganizeEveryNTurns = 1,
        memorySmartAddMode = smartAddMode,
        memoryWriteScope = "alwaysGlobal",
    )

    private fun message(
        id: String,
        role: String,
        text: String,
        group: String,
        version: Int,
        order: Int,
    ) = ChatMessage(
        id = id,
        role = role,
        parts = listOf(TextPart(text)),
        timestamp = order.toLong(),
        conversationId = "c1",
        groupId = group,
        version = version,
        messageOrder = order,
    )

    private fun settings(lang: MemoryPromptLang = MemoryPromptLang.en) = MemoryPipelineSettings(
        lang = lang,
        injectionMaxItems = 10,
        gateZh = "GATE-ZH", gateEn = "GATE-EN",
        extractZh = "EXTRACT-ZH", extractEn = "EXTRACT-EN",
        smartAddZh = "ADD-ZH", smartAddEn = "ADD-EN",
        smartAddBatchZh = "BATCH-ZH", smartAddBatchEn = "BATCH-EN",
        distillZh = "DISTILL-ZH", distillEn = "DISTILL-EN",
    )

    private fun window() = listOf(
        message("m1", "user", "remember I like tea", group = "g1", version = 0, order = 0) to 0,
        message("m2", "assistant", "noted", group = "g2", version = 0, order = 1) to 1,
    )

    /** Answers each pipeline step by looking at what the prompt asks for. */
    private fun scriptedLlm(
        gate: String = "<user_memory>true</user_memory>",
        extract: String = "<extracted><item type=\"identity\">Likes tea</item></extracted>",
        smartAdd: String = """{"results":[{"index":1,"action":"NEW"}]}""",
        distill: String = """{"fields":[{"key":"preferred_name","value":"Dee"}]}""",
        onCall: (String) -> Unit = {},
    ): suspend (String) -> String = { prompt ->
        onCall(prompt)
        when {
            prompt.contains("GATE-EN") -> gate
            prompt.contains("EXTRACT-EN") -> extract
            prompt.contains("BATCH-EN") -> smartAdd
            prompt.contains("DISTILL-EN") -> distill
            else -> ""
        }
    }

    @Test
    fun `a gate skip completes the run and advances the watermark`() = kotlinx.coroutines.runBlocking {
        val conversation = seedConversation()
        val pipeline = container.memoryPipeline

        val result = pipeline.processWindow(
            conversationId = conversation.id,
            assistant = assistant(),
            settings = settings(),
            watermark = -1,
            window = window(),
            llmCall = scriptedLlm(gate = "<user_memory>false</user_memory>"),
        )

        assertTrue(result.advanced)
        assertEquals(MemoryGateParseResult.SKIP, result.gate)
        assertEquals(0, result.extractedCount)
        assertEquals(1, container.conversationDao.get(conversation.id)!!.lastMemoryExtractedOrder)
    }

    @Test
    fun `a malformed gate advances only after repeated failures`() = kotlinx.coroutines.runBlocking {
        val conversation = seedConversation()
        val pipeline = container.memoryPipeline
        val llm = scriptedLlm(gate = "I cannot tell")

        val first = pipeline.processWindow(
            conversation.id, assistant(), settings(), -1, window(), llm,
        )
        assertFalse(first.advanced)
        assertEquals("gate_parse_failed", first.error)

        pipeline.processWindow(conversation.id, assistant(), settings(), -1, window(), llm)
        val third = pipeline.processWindow(conversation.id, assistant(), settings(), -1, window(), llm)

        // MAX_WINDOW_FAILURES = 3 → the third failure advances anyway.
        assertTrue(third.advanced)
        assertTrue(third.forcedAdvance)
        assertEquals(1, container.conversationDao.get(conversation.id)!!.lastMemoryExtractedOrder)
    }

    @Test
    fun `an empty extract result advances without writing`() = kotlinx.coroutines.runBlocking {
        val conversation = seedConversation()
        val result = container.memoryPipeline.processWindow(
            conversationId = conversation.id,
            assistant = assistant(),
            settings = settings(),
            watermark = -1,
            window = window(),
            llmCall = scriptedLlm(extract = "<extracted></extracted>"),
        )

        assertTrue(result.advanced)
        assertEquals(0, result.extractedCount)
        assertEquals(0, MemoryProviderV2(container.database.writableDatabase).also { it.initialize() }.entries.size)
    }

    @Test
    fun `a full run extracts, stores and distills`() = kotlinx.coroutines.runBlocking {
        val conversation = seedConversation()
        val prompts = mutableListOf<String>()

        val result = container.memoryPipeline.processWindow(
            conversationId = conversation.id,
            assistant = assistant(),
            settings = settings(),
            watermark = -1,
            window = window(),
            llmCall = scriptedLlm(onCall = { prompts.add(it) }),
        )

        assertTrue(result.advanced)
        assertEquals(MemoryGateParseResult.WORTH_REMEMBERING, result.gate)
        assertEquals(1, result.extractedCount)
        assertEquals(1, container.conversationDao.get(conversation.id)!!.lastMemoryExtractedOrder)

        // Gatekeeper → Extract → Smart Add → Distiller, in that order.
        assertTrue(prompts[0].contains("GATE-EN"))
        assertTrue(prompts[1].contains("EXTRACT-EN"))
        assertTrue(prompts[2].contains("BATCH-EN"))
        assertTrue(prompts.any { it.contains("DISTILL-EN") })

        val entries = MemoryProviderV2(container.database.writableDatabase).also { it.initialize() }.entries
        assertEquals(listOf("Likes tea"), entries.map { it.content })
        // The identity entry triggered the distiller, which wrote the profile field.
        assertEquals(
            listOf("preferred_name" to "Dee"),
            UserProfileRepository.fields(container).map { it.key to it.value },
        )
    }

    @Test
    fun `per item mode judges every extracted item`() = kotlinx.coroutines.runBlocking {
        val conversation = seedConversation()
        val prompts = mutableListOf<String>()

        container.memoryPipeline.processWindow(
            conversationId = conversation.id,
            assistant = assistant(smartAddMode = "perItem"),
            settings = settings(),
            watermark = -1,
            window = window(),
            llmCall = scriptedLlm(
                extract = "<extracted><item type=\"workflow\">A</item><item type=\"workflow\">B</item></extracted>",
                onCall = { prompts.add(it) },
            ),
        )

        val entries = MemoryProviderV2(container.database.writableDatabase).also { it.initialize() }.entries
        assertEquals(setOf("A", "B"), entries.map { it.content }.toSet())
        // One judge call per item, no batch prompt.
        assertEquals(2, prompts.count { it.contains("ADD-EN") })
        assertEquals(0, prompts.count { it.contains("BATCH-EN") })
    }
}

package com.psyche.memo.provider

import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.ui.MemoryPromptLang
import com.psyche.memo.ui.MemoryScope
import com.psyche.memo.ui.MemoryType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Port coverage of memory_tools.dart: definitions, gates and handlers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryToolsTest {

    private lateinit var container: AppContainerImpl

    @Before
    fun setUp() {
        container = AppContainerImpl(ApplicationProvider.getApplicationContext())
    }

    private fun assistant(
        enableMemory: Boolean = true,
        writeScope: String = "alwaysGlobal",
    ) = Assistant(
        id = "a1",
        name = "Test",
        enableMemory = enableMemory,
        memoryWriteScope = writeScope,
    )

    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    private fun str(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.content

    // ---- definitions ----

    @Test
    fun `definitions are gated by enableMemory`() {
        assertEquals(0, MemoryTools.buildDefinitions(assistant(enableMemory = false), MemoryPromptLang.en).size)
        val names = MemoryTools.buildDefinitions(assistant(), MemoryPromptLang.en).map { it.name }
        assertEquals(
            listOf(
                MemoryTools.MEMORY_READ,
                MemoryTools.MEMORY_SEARCH_PROFILE,
                MemoryTools.MEMORY_UPDATE,
                MemoryTools.MEMORY_EDIT,
                MemoryTools.MEMORY_DELETE,
                MemoryTools.UPDATE_USER_PROFILE,
            ),
            names,
        )
    }

    @Test
    fun `temporary conversations keep reads but drop writes`() {
        val names = MemoryTools.buildDefinitions(
            assistant(),
            MemoryPromptLang.en,
            allowMemoryWrites = false,
        ).map { it.name }
        assertEquals(
            listOf(MemoryTools.MEMORY_READ, MemoryTools.MEMORY_SEARCH_PROFILE),
            names,
        )
    }

    @Test
    fun `update schema exposes scope only for tool-default scopes`() {
        val global = MemoryTools.buildDefinitions(assistant(writeScope = "alwaysGlobal"), MemoryPromptLang.en)
            .first { it.name == MemoryTools.MEMORY_UPDATE }
        assertTrue(!global.inputSchemaJson.contains("\"scope\""))

        val scoped = MemoryTools.buildDefinitions(assistant(writeScope = "toolDefaultGlobal"), MemoryPromptLang.en)
            .first { it.name == MemoryTools.MEMORY_UPDATE }
        assertTrue(scoped.inputSchemaJson.contains("\"scope\""))
    }

    // ---- helpers ----

    @Test
    fun `resolveWriteScope follows the assistant policy`() {
        assertEquals(MemoryScope.global, MemoryTools.resolveWriteScope("alwaysGlobal", "assistant"))
        assertEquals(MemoryScope.assistant, MemoryTools.resolveWriteScope("alwaysAssistant", "global"))
        assertEquals(MemoryScope.assistant, MemoryTools.resolveWriteScope("toolDefaultGlobal", "assistant"))
        assertEquals(MemoryScope.global, MemoryTools.resolveWriteScope("toolDefaultGlobal", "global"))
        assertEquals(MemoryScope.global, MemoryTools.resolveWriteScope("toolDefaultAssistant", "global"))
        assertEquals(MemoryScope.assistant, MemoryTools.resolveWriteScope("toolDefaultAssistant", null))
    }

    @Test
    fun `search tokens lowercase split and escape like wildcards`() {
        assertEquals(listOf("a", "b\\%", "c\\_", "d\\\\e"), MemoryTools.searchTokens("A B% C_ d\\e"))
        assertEquals(emptyList<String>(), MemoryTools.searchTokens("   "))
    }

    @Test
    fun `fmtDate renders the local yyyy-mm-dd`() {
        val micros = 1_700_000_000_000_000L // 2023-11-14 UTC
        assertTrue(MemoryTools.fmtDate(micros).startsWith("2023-11-"))
    }

    // ---- handlers ----

    @Test
    fun `update creates a new entry and duplicate update skips`() {
        val create = handle(
            container, assistant(), "c1", false,
            MemoryTools.MEMORY_UPDATE,
            json("""{"type":"identity","content":"The user prefers concise answers."}"""),
        )!!
        val createObj = obj(create)
        assertEquals("NEW", str(createObj, "action"))

        val duplicate = handle(
            container, assistant(), "c1", false,
            MemoryTools.MEMORY_UPDATE,
            json("""{"type":"identity","content":"  the USER prefers concise answers.  "}"""),
        )!!
        assertEquals("SKIP", str(obj(duplicate), "action"))
    }

    @Test
    fun `update validates type and content`() {
        val badType = handle(
            container, assistant(), "c1", false,
            MemoryTools.MEMORY_UPDATE, json("""{"type":"nope","content":"x"}"""),
        )!!
        assertTrue(badType.contains("invalid_memory_type"))

        val empty = handle(
            container, assistant(), "c1", false,
            MemoryTools.MEMORY_UPDATE, json("""{"type":"identity","content":"  "}"""),
        )!!
        assertTrue(empty.contains("invalid_memory_content"))
    }

    @Test
    fun `temporary conversation rejects writes`() {
        val result = handle(
            container, assistant(), "c1", true,
            MemoryTools.MEMORY_UPDATE, json("""{"type":"identity","content":"x"}"""),
        )!!
        assertTrue(result.contains("temporary_conversation"))
    }

    @Test
    fun `read returns visible entries and filters by type`() {
        val provider = container.memoryProviderV2
        provider.create(MemoryScope.global, null, MemoryType.identity, "likes tea", com.psyche.memo.ui.MemorySource.manual)
        provider.create(MemoryScope.global, null, MemoryType.workflow, "uses vim", com.psyche.memo.ui.MemorySource.manual)

        val all = obj(handle(container, assistant(), "c1", false, MemoryTools.MEMORY_READ, json("{}"))!!)
        assertEquals(2, (all["total"] as JsonPrimitive).content.toInt())

        val identityOnly = obj(
            handle(
                container, assistant(), "c1", false,
                MemoryTools.MEMORY_READ, json("""{"type":"identity"}"""),
            )!!,
        )
        assertEquals(1, (identityOnly["total"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun `edit and delete resolve the entry and report missing ids`() {
        val provider = container.memoryProviderV2
        val entry = provider.create(MemoryScope.global, null, MemoryType.voice, "old", com.psyche.memo.ui.MemorySource.manual)

        val edit = obj(
            handle(
                container, assistant(), "c1", false,
                MemoryTools.MEMORY_EDIT, json("""{"id":"${entry.id}","content":"new"}"""),
            )!!,
        )
        assertEquals("EDIT", str(edit, "action"))

        val delete = obj(
            handle(
                container, assistant(), "c1", false,
                MemoryTools.MEMORY_DELETE, json("""{"id":"${entry.id}"}"""),
            )!!,
        )
        assertEquals("DELETE", str(delete, "action"))

        val missing = handle(
            container, assistant(), "c1", false,
            MemoryTools.MEMORY_EDIT, json("""{"id":"mem_deadbeef","content":"x"}"""),
        )!!
        assertTrue(missing.contains("memory_not_found"))
    }

    @Test
    fun `unknown tool names fall through`() {
        assertNull(
            handle(container, assistant(), "c1", false, "search_web", json("{}")),
        )
        assertNull(
            handle(container, assistant(enableMemory = false), "c1", false, MemoryTools.MEMORY_READ, json("{}")),
        )
    }

    @Test
    fun `update user profile validates keys and clears on empty value`() {
        val result = obj(
            handle(
                container, assistant(), "c1", false,
                MemoryTools.UPDATE_USER_PROFILE,
                json("""{"fields":[{"key":"preferred_name","value":"Ada"},{"key":"nope","value":"x"},{"key":"location","value":""}]}"""),
            )!!,
        )
        assertEquals("PROFILE_UPDATE", str(result, "action"))
        assertEquals(1, (result["updated"] as kotlinx.serialization.json.JsonArray).size)
        assertEquals(1, (result["cleared"] as kotlinx.serialization.json.JsonArray).size)
        assertEquals(1, (result["rejected"] as kotlinx.serialization.json.JsonArray).size)
    }

    /** [MemoryTools.handle] is suspend: Smart Add may call the memory model. */
    private fun handle(
        container: AppContainerImpl,
        assistant: Assistant?,
        conversationId: String?,
        isTemporary: Boolean,
        name: String,
        args: JsonObject,
    ): String? = kotlinx.coroutines.runBlocking {
        MemoryTools.handle(container, assistant, conversationId, isTemporary, name, args)
    }
}

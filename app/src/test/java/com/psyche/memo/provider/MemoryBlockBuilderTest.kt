package com.psyche.memo.provider

import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.ui.MemoryEntry
import com.psyche.memo.ui.MemoryPromptLang
import com.psyche.memo.ui.MemoryScope
import com.psyche.memo.ui.MemorySource
import com.psyche.memo.ui.MemoryType
import com.psyche.memo.ui.ProfileField
import com.psyche.memo.ui.UserProfileRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Port coverage of memory_block_builder.dart. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryBlockBuilderTest {

    private lateinit var container: AppContainerImpl

    @Before
    fun setUp() {
        container = AppContainerImpl(ApplicationProvider.getApplicationContext())
    }

    private fun entry(
        id: String,
        type: MemoryType,
        content: String,
        scope: MemoryScope = MemoryScope.global,
        updatedAt: Long = 1_700_000_000_000_000L,
        createdAt: Long = updatedAt,
    ) = MemoryEntry(
        id = id,
        scope = scope,
        type = type,
        content = content,
        source = MemorySource.manual,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    @Test
    fun `empty types render self-closing tags in order`() {
        val block = MemoryBlockBuilder.buildMemoryBlock(emptyList(), emptyMap(), MemoryPromptLang.en, 10)
        assertEquals(
            "<user_memory type=\"identity\"/>\n" +
                "<user_memory type=\"workflow\"/>\n" +
                "<user_memory type=\"voice\"/>\n" +
                "<user_memory type=\"instruction\"/>\n",
            block,
        )
    }

    @Test
    fun `entries render date marker and content`() {
        val block = MemoryBlockBuilder.buildMemoryBlock(
            listOf(
                entry("mem_1", MemoryType.identity, "likes tea"),
                entry("mem_2", MemoryType.identity, "works at Acme", scope = MemoryScope.assistant),
            ),
            mapOf(MemoryType.identity to 2),
            MemoryPromptLang.en,
            10,
        )
        val date = MemoryBlockBuilder.fmtDate(1_700_000_000_000_000L)
        assertEquals(
            "<user_memory type=\"identity\">\n" +
                "- [$date] likes tea\n" +
                "- [$date] (assistant) works at Acme\n" +
                "</user_memory>\n" +
                "<user_memory type=\"workflow\"/>\n" +
                "<user_memory type=\"voice\"/>\n" +
                "<user_memory type=\"instruction\"/>\n",
            block,
        )
    }

    @Test
    fun `summary mode caps entries and adds the hint`() {
        val entries = (1..5).map {
            entry("mem_$it", MemoryType.voice, "v$it", updatedAt = 1_700_000_000_000_000L + it)
        }
        val block = MemoryBlockBuilder.buildMemoryBlock(
            entries,
            mapOf(MemoryType.voice to 5),
            MemoryPromptLang.zh,
            2,
        )
        assertTrue(block.contains("mode=\"summary\" total=\"5\" shown=\"2\""))
        assertTrue(block.contains("更多内容请使用 memory_search_profile 查询"))
        assertTrue(block.contains("v5"))
        assertTrue(block.contains("v4"))
        assertTrue(!block.contains("v1"))
    }

    @Test
    fun `global entries sort before assistant ones`() {
        val block = MemoryBlockBuilder.buildMemoryBlock(
            listOf(
                entry("mem_b", MemoryType.workflow, "assistant first", scope = MemoryScope.assistant, createdAt = 1),
                entry("mem_a", MemoryType.workflow, "global second", createdAt = 2),
            ),
            mapOf(MemoryType.workflow to 2),
            MemoryPromptLang.en,
            10,
        )
        val globalAt = block.indexOf("global second")
        val assistantAt = block.indexOf("assistant first")
        assertTrue(globalAt in 0 until assistantAt)
    }

    @Test
    fun `profile block lists known keys then sorted custom keys`() {
        val block = MemoryBlockBuilder.buildProfileBlock(
            listOf(
                ProfileField("custom.zeta", "z"),
                ProfileField("preferred_name", "Ada"),
                ProfileField("custom.alpha", "a"),
                ProfileField("location", ""),
            ),
            MemoryPromptLang.en,
        )
        assertEquals(
            "<user_profile>\n<preferred_name>Ada</preferred_name>\n" +
                "<custom name=\"alpha\">a</custom>\n" +
                "<custom name=\"zeta\">z</custom>\n</user_profile>\n",
            block,
        )
    }

    @Test
    fun `empty profile renders the self-closing tag`() {
        assertEquals(
            "<user_profile/>\n",
            MemoryBlockBuilder.buildProfileBlock(listOf(ProfileField("location", "  ")), MemoryPromptLang.en),
        )
    }

    @Test
    fun `escape and flatten follow the upstream rules`() {
        assertEquals("a &amp; b &lt;c&gt;", MemoryBlockBuilder.escape("a & b <c>"))
        assertEquals("say &quot;hi&quot;", MemoryBlockBuilder.escapeAttr("say \"hi\""))
        assertEquals("a b c", MemoryBlockBuilder.flatten("  a\n\n b\t c  "))
    }

    @Test
    fun `hash is stable and 16 chars`() {
        val hash = MemoryBlockBuilder.hashBlocks("p", "m")
        assertEquals(hash, MemoryBlockBuilder.hashBlocks("p", "m"))
        assertEquals(16, hash.length)
        assertTrue(MemoryBlockBuilder.hashBlocks("p", "m") != MemoryBlockBuilder.hashBlocks("m", "p"))
    }

    @Test
    fun `buildPrefix combines profile and memory for the assistant`() {
        UserProfileRepository.put(container, "preferred_name", "Ada")
        container.memoryProviderV2.create(
            MemoryScope.global, null, MemoryType.identity, "likes tea", MemorySource.manual,
        )
        val prefix = MemoryBlockBuilder.buildPrefix(container, Assistant(id = "a1", name = "T", enableMemory = true))
        assertTrue(prefix.contains("<user_profile>"))
        assertTrue(prefix.contains("<preferred_name>Ada</preferred_name>"))
        assertTrue(prefix.contains("likes tea"))
    }

    @Test
    fun `buildPrefix is empty without memory enabled or content`() {
        assertEquals("", MemoryBlockBuilder.buildPrefix(container, Assistant(id = "a1", name = "T", enableMemory = false)))
        assertEquals("", MemoryBlockBuilder.buildPrefix(container, Assistant(id = "a1", name = "T", enableMemory = true)))
    }
}

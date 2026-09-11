package com.psyche.memo.provider

import com.psyche.memo.ui.MemoryEntry
import com.psyche.memo.ui.MemoryPromptLang
import com.psyche.memo.ui.MemoryScope
import com.psyche.memo.ui.MemorySource
import com.psyche.memo.ui.MemoryStatus
import com.psyche.memo.ui.MemoryType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * memory_smart_add.dart §12.6 — tokenizer, prompt building, JSON parsing,
 * decision normalisation and the NEW/MERGE/CONFLICT/SKIP flows, driven by a
 * fake store and a scripted judge.
 */
class MemorySmartAddTest {

    // ---- fakes ----

    /** In-memory [SmartAddRepository]: the same visibility and ranking rules as the real one. */
    private class FakeRepository(initial: List<MemoryEntry> = emptyList()) : SmartAddRepository {
        val entries = initial.toMutableList()
        private var counter = initial.size

        private fun visible(assistantId: String?) = entries.filter {
            it.status == MemoryStatus.active &&
                (it.scope == MemoryScope.global ||
                    (it.scope == MemoryScope.assistant && it.assistantId == assistantId))
        }

        override fun searchAny(
            assistantId: String?,
            tokens: List<String>,
            type: MemoryType,
            limit: Int,
        ): List<MemoryEntry> = visible(assistantId)
            .filter { it.type == type }
            .map { entry ->
                val hits = tokens.count { MemoryEntry.normalizeContent(entry.content).contains(it) }
                entry to hits
            }
            .filter { it.second >= 1 }
            .sortedWith(
                compareByDescending<Pair<MemoryEntry, Int>> { it.second }
                    .thenByDescending { it.first.updatedAt }
                    .thenBy { it.first.id },
            )
            .take(limit)
            .map { it.first }

        override fun visibleByType(assistantId: String?, type: MemoryType): List<MemoryEntry> =
            visible(assistantId).filter { it.type == type }

        override fun findExact(
            assistantId: String?,
            type: MemoryType,
            contentNormalized: String,
        ): MemoryEntry? = visible(assistantId).firstOrNull {
            it.type == type && MemoryEntry.normalizeContent(it.content) == contentNormalized
        }

        override fun byIds(ids: List<String>): List<MemoryEntry> =
            entries.filter { it.id in ids.toSet() }

        override fun create(
            scope: MemoryScope,
            assistantId: String?,
            type: MemoryType,
            content: String,
            source: MemorySource,
        ): MemoryEntry {
            counter += 1
            val now = 1_000L + counter
            val entry = MemoryEntry(
                id = "mem_new_$counter",
                scope = scope,
                assistantId = if (scope == MemoryScope.assistant) assistantId else null,
                type = type,
                content = content,
                source = source,
                createdAt = now,
                updatedAt = now,
            )
            entries.add(entry)
            return entry
        }

        override fun updateContent(id: String, content: String): MemoryEntry? {
            val index = entries.indexOfFirst { it.id == id }
            if (index < 0) return null
            val updated = entries[index].copy(content = content, updatedAt = entries[index].updatedAt + 1)
            entries[index] = updated
            return updated
        }

        override fun archive(id: String): Boolean {
            val index = entries.indexOfFirst { it.id == id }
            if (index < 0) return false
            entries[index] = entries[index].copy(status = MemoryStatus.archived)
            return true
        }

        override fun linkBidirectional(a: String, b: String) {
            if (a == b) return
            for ((first, second) in listOf(a to b, b to a)) {
                val index = entries.indexOfFirst { it.id == first }
                if (index < 0) continue
                val entry = entries[index]
                if (second in entry.relatedIds) continue
                entries[index] = entry.copy(relatedIds = entry.relatedIds + second)
            }
        }
    }

    private fun entry(
        id: String,
        content: String,
        type: MemoryType = MemoryType.identity,
        scope: MemoryScope = MemoryScope.global,
        assistantId: String? = null,
        status: MemoryStatus = MemoryStatus.active,
        updatedAt: Long = 1_000L,
    ) = MemoryEntry(
        id = id,
        scope = scope,
        assistantId = assistantId,
        type = type,
        status = status,
        content = content,
        createdAt = 100L,
        updatedAt = updatedAt,
    )

    private fun smartAdd(repo: FakeRepository) = MemorySmartAdd(repo)

    // ---- tokenizer ----

    @Test
    fun `tokenizer keeps latin words and drops stopwords`() {
        assertEquals(
            listOf("coffee", "morning"),
            MemoryTokenizer.tokenize("The user prefers COFFEE in the morning"),
        )
    }

    @Test
    fun `tokenizer builds cjk bigrams without stopword grams`() {
        // The stopword 用户 kills that exact gram, but not 户喜 — the Dart
        // `gram.contains(stopword)` check is per bigram, not per character.
        assertEquals(listOf("户喜", "喜欢", "欢咖", "咖啡"), MemoryTokenizer.tokenize("用户喜欢咖啡"))
        // Nothing survives the filter for a run made only of stopword grams.
        assertEquals(emptyList<String>(), MemoryTokenizer.tokenize("是的了在"))
    }

    @Test
    fun `tokenizer caps at eight unique tokens`() {
        val tokens = MemoryTokenizer.tokenize("a1 a2 a3 a4 a5 a6 a7 a8 a9 a10")
        assertEquals(8, tokens.size)
        assertEquals(listOf("a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8"), tokens)
    }

    @Test
    fun `escapeLike escapes wildcards`() {
        assertEquals("50\\%\\_a\\\\b", MemoryTokenizer.escapeLike("50%_a\\b"))
    }

    // ---- prompts ----

    @Test
    fun `per item prompt fills the placeholders`() {
        val prompt = smartAdd(FakeRepository()).buildPerItemPrompt(
            lang = MemoryPromptLang.en,
            type = MemoryType.workflow,
            newInfo = "likes tables",
            entriesText = "mem_1 something",
            overrideZh = null,
            overrideEn = "T=[[{{type}}]] N=[[{{newInfo}}]] E=[[{{entriesText}}]]",
        )
        assertEquals("T=[[workflow]] N=[[likes tables]] E=[[mem_1 something]]", prompt)
    }

    @Test
    fun `an empty override falls back to the built-in template`() {
        val prompt = smartAdd(FakeRepository()).buildBatchPrompt(
            lang = MemoryPromptLang.zh,
            itemsText = "[1] identity x",
            entriesText = "mem_1 y",
            overrideZh = "   ",
        )
        assertTrue(prompt.contains("[1] identity x"))
        assertTrue(prompt.contains("mem_1 y"))
        assertTrue(prompt.contains("{{itemsText}}").not())
    }

    @Test
    fun `entry and item formatting matches the dart layout`() {
        val entries = listOf(entry("mem_1", "likes tea", MemoryType.workflow))
        assertEquals("mem_1 likes tea", MemorySmartAdd.formatEntriesPerItem(entries))
        assertEquals("mem_1 (workflow) likes tea", MemorySmartAdd.formatEntriesBatched(entries))
        assertEquals(
            "[1] identity a\n[2] workflow b",
            MemorySmartAdd.formatItemsText(
                listOf(
                    SmartAddItem(MemoryType.identity, "a", MemoryScope.global),
                    SmartAddItem(MemoryType.workflow, "b", MemoryScope.global),
                ),
            ),
        )
    }

    // ---- parsing ----

    @Test
    fun `extractJson unwraps fences and surrounding prose`() {
        assertEquals(
            "NEW",
            ((MemorySmartAdd.extractJson("```json\n{\"action\":\"NEW\"}\n```") as kotlinx.serialization.json.JsonObject)["action"] as kotlinx.serialization.json.JsonPrimitive).content,
        )
        assertEquals(
            "MERGE",
            ((MemorySmartAdd.extractJson("Sure! {\"action\": \"MERGE\"} hope that helps") as kotlinx.serialization.json.JsonObject)["action"] as kotlinx.serialization.json.JsonPrimitive).content,
        )
        assertNull(MemorySmartAdd.extractJson("no json at all"))
        assertNull(MemorySmartAdd.extractJson("{"))
    }

    @Test
    fun `parsePerItem reads action target merged content and related ids`() {
        val decision = MemorySmartAdd.parsePerItem(
            """{"action":"merge","targetId":"mem_1","relatedIds":["mem_2",""],"mergedContent":"  x  "}""",
        )!!
        assertEquals(SmartAddAction.MERGE, decision.action)
        assertEquals("mem_1", decision.targetId)
        assertEquals("  x  ", decision.mergedContent)
        assertEquals(listOf("mem_2"), decision.relatedIds)

        assertNull(MemorySmartAdd.parsePerItem("""{"action":"EXPLODE"}"""))
        assertNull(MemorySmartAdd.parsePerItem("not json"))
        // "null" and "" both mean "no target".
        assertNull(MemorySmartAdd.parsePerItem("""{"action":"NEW","targetId":"null"}""")!!.targetId)
    }

    @Test
    fun `parseBatch maps one-based indexes and leaves gaps null`() {
        val parsed = MemorySmartAdd.parseBatch(
            """{"results":[{"index":2,"action":"SKIP"},{"index":9,"action":"NEW"},{"action":"NEW"}]}""",
            count = 3,
        )!!
        assertEquals(3, parsed.size)
        assertNull(parsed[0])
        assertEquals(SmartAddAction.SKIP, parsed[1]!!.action)
        assertNull(parsed[2])
    }

    // ---- decision normalisation ----

    @Test
    fun `merge or conflict with an unknown target degrades to new`() {
        val decision = SmartAddDecision(SmartAddAction.MERGE, targetId = "gone", mergedContent = "x")
        val normalized = MemorySmartAdd.normalizeDecision(decision, setOf("mem_1"))
        assertEquals(SmartAddAction.NEW, normalized.action)
        assertNull(normalized.targetId)
        assertNull(normalized.mergedContent)
    }

    @Test
    fun `merge without content skips as degraded`() {
        val normalized = MemorySmartAdd.normalizeDecision(
            SmartAddDecision(SmartAddAction.MERGE, targetId = "mem_1", mergedContent = "   "),
            setOf("mem_1"),
        )
        assertEquals(SmartAddAction.SKIP, normalized.action)
        assertTrue(normalized.degraded)
    }

    @Test
    fun `related ids outside the candidate set are dropped`() {
        val normalized = MemorySmartAdd.normalizeDecision(
            SmartAddDecision(SmartAddAction.NEW, relatedIds = listOf("mem_1", "stranger")),
            setOf("mem_1"),
        )
        assertEquals(listOf("mem_1"), normalized.relatedIds)
    }

    @Test
    fun `a target outside mergeable ids cannot be rewritten`() {
        // Assistant-scoped item, global candidate: MERGE must not touch it.
        val normalized = MemorySmartAdd.normalizeDecision(
            SmartAddDecision(SmartAddAction.CONFLICT, targetId = "global_1", mergedContent = "x"),
            candidateIds = setOf("global_1"),
            mergeableIds = emptySet(),
        )
        assertEquals(SmartAddAction.NEW, normalized.action)
    }

    @Test
    fun `degrade skips an exact duplicate and creates otherwise`() {
        val repo = FakeRepository(listOf(entry("mem_1", "Prefers dark mode")))
        val adder = smartAdd(repo)
        val duplicate = adder.degradeDecision("a1", MemoryType.identity, "  prefers DARK mode ")
        assertEquals(SmartAddAction.SKIP, duplicate.action)
        assertEquals("mem_1", duplicate.targetId)
        assertTrue(duplicate.degraded)

        val fresh = adder.degradeDecision("a1", MemoryType.identity, "Prefers light mode")
        assertEquals(SmartAddAction.NEW, fresh.action)
        assertTrue(fresh.degraded)
    }

    // ---- addOne ----

    @Test
    fun `addOne merges into the judged candidate`() = runBlocking {
        val repo = FakeRepository(listOf(entry("mem_1", "Likes tea")))
        val adder = smartAdd(repo)
        val result = adder.addOne(
            item = SmartAddItem(MemoryType.identity, "Likes tea and coffee", MemoryScope.global),
            visibilityAssistantId = "a1",
            source = MemorySource.tool,
            lang = MemoryPromptLang.en,
            llmCall = { """{"action":"MERGE","targetId":"mem_1","mergedContent":"Likes tea and coffee"}""" },
        )
        assertEquals(SmartAddAction.MERGE, result.action)
        assertEquals("mem_1", result.id)
        assertEquals("Likes tea and coffee", result.content)
        assertEquals("Likes tea and coffee", repo.entries.single().content)
        assertEquals(1, repo.entries.size)
    }

    @Test
    fun `addOne creates and links related entries`() = runBlocking {
        val repo = FakeRepository(listOf(entry("mem_1", "Likes tea")))
        val result = smartAdd(repo).addOne(
            item = SmartAddItem(MemoryType.identity, "Likes tea in the morning", MemoryScope.global),
            visibilityAssistantId = "a1",
            source = MemorySource.tool,
            lang = MemoryPromptLang.en,
            llmCall = { """{"action":"NEW","relatedIds":["mem_1"],"targetId":"null"}""" },
        )
        assertEquals(SmartAddAction.NEW, result.action)
        val created = repo.entries.single { it.id == result.id }
        assertEquals(listOf("mem_1"), created.relatedIds)
        assertEquals(listOf(created.id), repo.entries.single { it.id == "mem_1" }.relatedIds)
    }

    @Test
    fun `addOne conflict archives the old entry and links the replacement`() = runBlocking {
        val repo = FakeRepository(listOf(entry("mem_1", "Likes tea")))
        val result = smartAdd(repo).addOne(
            item = SmartAddItem(MemoryType.identity, "Hates tea", MemoryScope.global),
            visibilityAssistantId = "a1",
            source = MemorySource.tool,
            lang = MemoryPromptLang.en,
            llmCall = { """{"action":"CONFLICT","targetId":"mem_1"}""" },
        )
        assertEquals(SmartAddAction.CONFLICT, result.action)
        assertEquals("mem_1", result.reason)
        assertEquals(MemoryStatus.archived, repo.entries.single { it.id == "mem_1" }.status)
        val created = repo.entries.single { it.id == result.id }
        assertEquals("Hates tea", created.content)
        assertEquals(listOf("mem_1"), created.relatedIds)
        assertEquals(listOf(created.id), repo.entries.single { it.id == "mem_1" }.relatedIds)
    }

    @Test
    fun `addOne skip writes nothing`() = runBlocking {
        val repo = FakeRepository(listOf(entry("mem_1", "Likes tea")))
        val result = smartAdd(repo).addOne(
            item = SmartAddItem(MemoryType.identity, "Likes tea a lot", MemoryScope.global),
            visibilityAssistantId = "a1",
            source = MemorySource.tool,
            lang = MemoryPromptLang.en,
            llmCall = { """{"action":"SKIP","targetId":"mem_1","reason":"covered"}""" },
        )
        assertEquals(SmartAddAction.SKIP, result.action)
        assertEquals("mem_1", result.id)
        assertNull(result.reason)
        assertEquals(1, repo.entries.size)
        assertEquals("Likes tea", repo.entries.single().content)
    }

    @Test
    fun `addOne skips an exact duplicate before calling the judge`() = runBlocking {
        val repo = FakeRepository(listOf(entry("mem_1", "Likes tea")))
        var called = false
        val result = smartAdd(repo).addOne(
            item = SmartAddItem(MemoryType.identity, " likes   TEA ", MemoryScope.global),
            visibilityAssistantId = "a1",
            source = MemorySource.tool,
            lang = MemoryPromptLang.en,
            llmCall = { called = true; """{"action":"NEW"}""" },
        )
        assertEquals(SmartAddAction.SKIP, result.action)
        assertEquals("duplicate", result.reason)
        assertEquals(false, called)
    }

    @Test
    fun `an unusable judge answer degrades to a plain add`() = runBlocking {
        val repo = FakeRepository()
        val result = smartAdd(repo).addOne(
            item = SmartAddItem(MemoryType.identity, "Likes tea", MemoryScope.global),
            visibilityAssistantId = "a1",
            source = MemorySource.tool,
            lang = MemoryPromptLang.en,
            llmCall = { "I could not decide." },
        )
        assertEquals(SmartAddAction.NEW, result.action)
        assertEquals("Likes tea", repo.entries.single().content)
    }

    @Test
    fun `a failing judge call still stores the entry`() = runBlocking {
        val repo = FakeRepository()
        val result = smartAdd(repo).addOne(
            item = SmartAddItem(MemoryType.voice, "Speaks briefly", MemoryScope.global),
            visibilityAssistantId = "a1",
            source = MemorySource.tool,
            lang = MemoryPromptLang.en,
            llmCall = { error("boom") },
        )
        assertEquals(SmartAddAction.NEW, result.action)
        assertEquals(1, repo.entries.size)
    }

    @Test
    fun `no judge configured falls back to the duplicate check`() = runBlocking {
        val repo = FakeRepository(listOf(entry("mem_1", "Likes tea")))
        val adder = smartAdd(repo)
        val duplicate = adder.addOne(
            item = SmartAddItem(MemoryType.identity, "Likes tea", MemoryScope.global),
            visibilityAssistantId = "a1",
            source = MemorySource.tool,
            lang = MemoryPromptLang.en,
        )
        assertEquals(SmartAddAction.SKIP, duplicate.action)
        assertEquals("duplicate", duplicate.reason)

        val fresh = adder.addOne(
            item = SmartAddItem(MemoryType.identity, "Likes coffee", MemoryScope.global),
            visibilityAssistantId = "a1",
            source = MemorySource.tool,
            lang = MemoryPromptLang.en,
        )
        assertEquals(SmartAddAction.NEW, fresh.action)
    }

    // ---- addMany ----

    @Test
    fun `addMany per item mode judges one by one`() = runBlocking {
        val repo = FakeRepository()
        var calls = 0
        val batch = smartAdd(repo).addMany(
            items = listOf(
                SmartAddItem(MemoryType.identity, "Likes tea", MemoryScope.global),
                SmartAddItem(MemoryType.workflow, "Wants short answers", MemoryScope.global),
            ),
            visibilityAssistantId = "a1",
            source = MemorySource.extracted,
            lang = MemoryPromptLang.en,
            mode = "perItem",
            llmCall = { calls += 1; """{"action":"NEW"}""" },
        )
        assertEquals(2, calls)
        assertEquals(listOf(SmartAddAction.NEW, SmartAddAction.NEW), batch.results.map { it.action })
        assertTrue(batch.identityChanged)
        assertEquals(2, repo.entries.size)
    }

    @Test
    fun `addMany batched mode resolves every pending item`() = runBlocking {
        val repo = FakeRepository(listOf(entry("mem_1", "Likes tea")))
        var calls = 0
        val batch = smartAdd(repo).addMany(
            items = listOf(
                SmartAddItem(MemoryType.identity, "Likes tea", MemoryScope.global), // exact duplicate
                SmartAddItem(MemoryType.identity, "Likes tea and coffee", MemoryScope.global),
            ),
            visibilityAssistantId = "a1",
            source = MemorySource.extracted,
            lang = MemoryPromptLang.en,
            mode = "batched",
            llmCall = { calls += 1; """{"results":[{"index":1,"action":"MERGE","targetId":"mem_1","mergedContent":"Likes tea and coffee"}]}""" },
        )
        assertEquals(1, calls)
        assertEquals(SmartAddAction.SKIP, batch.results[0].action)
        assertEquals(SmartAddAction.MERGE, batch.results[1].action)
        assertTrue(batch.identityChanged)
        assertEquals("Likes tea and coffee", repo.entries.single { it.id == "mem_1" }.content)
    }

    @Test
    fun `addMany batched mode degrades a missing index`() = runBlocking {
        val repo = FakeRepository(listOf(entry("mem_1", "Likes tea")))
        val batch = smartAdd(repo).addMany(
            items = listOf(
                SmartAddItem(MemoryType.identity, "Likes tea and coffee", MemoryScope.global),
                SmartAddItem(MemoryType.identity, "Likes cocoa", MemoryScope.global),
            ),
            visibilityAssistantId = "a1",
            source = MemorySource.extracted,
            lang = MemoryPromptLang.en,
            mode = "batched",
            llmCall = { """{"results":[{"index":2,"action":"NEW"}]}""" },
        )
        // Item 1 has no decision → degraded add; item 2 honoured the judge.
        assertEquals(listOf(SmartAddAction.NEW, SmartAddAction.NEW), batch.results.map { it.action })
        assertTrue(repo.entries.any { it.content == "Likes tea and coffee" })
        assertTrue(repo.entries.any { it.content == "Likes cocoa" })
    }

    @Test
    fun `addMany with no items is a no-op`() = runBlocking {
        val batch = smartAdd(FakeRepository()).addMany(
            items = emptyList(),
            visibilityAssistantId = "a1",
            source = MemorySource.extracted,
            lang = MemoryPromptLang.en,
            mode = "batched",
            llmCall = { error("must not be called") },
        )
        assertEquals(emptyList<SmartAddResult>(), batch.results)
        assertEquals(false, batch.identityChanged)
    }

    // ---- tool payload ----

    @Test
    fun `tool payloads match the dart keys`() {
        assertEquals(
            """{"action":"NEW","id":"mem_1","content":"x"}""",
            SmartAddResult(SmartAddAction.NEW, "mem_1", "x").toToolJson().toString(),
        )
        assertEquals(
            """{"action":"SKIP","reason":"duplicate","id":"mem_1"}""",
            SmartAddResult(SmartAddAction.SKIP, "mem_1", reason = "duplicate").toToolJson().toString(),
        )
        assertEquals(
            """{"action":"CONFLICT","id":"mem_2","content":"y","archivedId":"mem_1"}""",
            SmartAddResult(SmartAddAction.CONFLICT, "mem_2", "y", "mem_1").toToolJson().toString(),
        )
    }
}

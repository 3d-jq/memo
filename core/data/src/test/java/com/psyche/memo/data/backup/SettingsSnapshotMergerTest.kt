package com.psyche.memo.data.backup

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `business_settings_merger.dart` semantics: shared rows update in place,
 * backup-only rows append, and (unlike overwrite) a local value almost always
 * wins over an imported one.
 */
class SettingsSnapshotMergerTest {

    private fun row(id: String, sortOrder: Int = 0, payload: String = """{"id":"$id"}""") =
        SettingsSnapshotMerger.Row(id, sortOrder, BackupJson.parse(payload) as kotlinx.serialization.json.JsonObject)

    // ── rows by id ────────────────────────────────────────────────────────────

    @Test
    fun `local-only rows survive and backup-only rows append`() {
        val merged = SettingsSnapshotMerger.mergeRowsById(
            existing = listOf(row("a", 0), row("b", 1)),
            incoming = listOf(row("b", 0), row("c", 1)),
        )
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
        assertEquals(listOf(0, 1, 2), merged.map { it.sortOrder })
    }

    @Test
    fun `duplicate ids keep the first occurrence`() {
        val merged = SettingsSnapshotMerger.mergeRowsById(
            existing = listOf(row("a", 0, """{"id":"a","v":1}""")),
            incoming = listOf(row("a", 0, """{"id":"a","v":2}""")),
        )
        assertEquals(1, merged.size)
        assertTrue(merged.single().payload.toString().contains("\"v\":1"))
    }

    // ── assistants ────────────────────────────────────────────────────────────

    @Test
    fun `assistant merge keeps the local avatar and background`() {
        val merged = SettingsSnapshotMerger.mergeAssistants(
            existing = listOf(
                row("a1", 0, """{"id":"a1","name":"Local","avatar":"file:///avatars/a.png","background":"file:///bg/a.png"}"""),
            ),
            incoming = listOf(
                row("a1", 0, """{"id":"a1","name":"Backup","avatar":"","background":"file:///other.png","prompt":"hi"}"""),
            ),
        )
        val payload = merged.single().payload.toString()
        assertTrue("avatar must stay local, got: $payload", payload.contains("file:///avatars/a.png"))
        assertTrue("background must stay local, got: $payload", payload.contains("file:///bg/a.png"))
        assertTrue("incoming fields still merge, got: $payload", payload.contains("\"prompt\":\"hi\""))
    }

    @Test
    fun `assistant with an empty local avatar takes the backup avatar`() {
        val merged = SettingsSnapshotMerger.mergeAssistants(
            existing = listOf(row("a1", 0, """{"id":"a1","avatar":""}""")),
            incoming = listOf(row("a1", 0, """{"id":"a1","avatar":"file:///new.png"}""")),
        )
        assertTrue(merged.single().payload.toString().contains("file:///new.png"))
    }

    // ── providers ─────────────────────────────────────────────────────────────

    @Test
    fun `provider order prefers the local sequence by default`() {
        val merged = SettingsSnapshotMerger.mergeProviders(
            existing = listOf(row("p1", 0), row("p2", 1)),
            incoming = listOf(row("p2", 0), row("p1", 1), row("p3", 2)),
            preferIncomingOrder = false,
        )
        assertEquals(listOf("p1", "p2", "p3"), merged.map { it.id })
    }

    @Test
    fun `provider order prefers the backup sequence when the order key came along`() {
        val merged = SettingsSnapshotMerger.mergeProviders(
            existing = listOf(row("p1", 0), row("p2", 1)),
            incoming = listOf(row("p2", 0), row("p1", 1), row("p3", 2)),
            preferIncomingOrder = true,
        )
        assertEquals(listOf("p2", "p1", "p3"), merged.map { it.id })
    }

    @Test
    fun `an order-only placeholder never displaces a real local provider`() {
        val orderOnly = SettingsSnapshotMerger.PROVIDER_ORDER_ONLY_ENABLED
        val merged = SettingsSnapshotMerger.mergeProviders(
            existing = listOf(row("p1", 0, """{"id":"p1","enabled":true,"name":"Real"}""")),
            incoming = listOf(row("p1", 0, """{"enabled":"$orderOnly"}""")),
            preferIncomingOrder = true,
        )
        assertEquals(1, merged.size)
        assertTrue(merged.single().payload.toString().contains("\"name\":\"Real\""))
    }

    // ── memory entries ────────────────────────────────────────────────────────

    private fun entry(
        id: String,
        scope: String = "global",
        type: String = "identity",
        content: String,
        related: List<String>? = null,
        migrationIds: List<String>? = null,
    ): SettingsSnapshotMerger.Row {
        val fields = mutableListOf(
            "\"id\":\"$id\"",
            "\"scope\":\"$scope\"",
            "\"type\":\"$type\"",
            "\"content\":\"$content\"",
        )
        related?.let { fields.add("\"relatedIds\":${it.joinToString(",", "[", "]") { v -> "\"$v\"" }}") }
        migrationIds?.let { fields.add("\"migrationIds\":${it.joinToString(",", "[", "]") { v -> "\"$v\"" }}") }
        return row(id, 0, "{${fields.joinToString(",")}}")
    }

    @Test
    fun `memory entries dedupe on scope assistant type and normalized content`() {
        val merged = SettingsSnapshotMerger.mergeMemoryEntries(
            existing = listOf(entry("m1", content = "Prefers dark mode")),
            incoming = listOf(entry("m2", content = "  prefers   DARK mode ")),
        )
        assertEquals(listOf("m1"), merged.map { it.id })
    }

    @Test
    fun `a colliding incoming id is renumbered and relatedIds follow`() {
        val merged = SettingsSnapshotMerger.mergeMemoryEntries(
            existing = listOf(entry("m1", content = "one")),
            incoming = listOf(
                entry("m1", content = "two"),
                entry("m2", content = "three", related = listOf("m1")),
            ),
        )
        val ids = merged.map { it.id }
        assertEquals(3, ids.size)
        val renumbered = ids.single { it != "m1" && it != "m2" }
        val referencing = merged.first { it.id == "m2" }
        assertTrue(
            "relatedIds must point at the renumbered id, got: ${referencing.payload}",
            referencing.payload.toString().contains("\"$renumbered\""),
        )
    }

    @Test
    fun `migration ids union into the kept entry`() {
        val merged = SettingsSnapshotMerger.mergeMemoryEntries(
            existing = listOf(entry("m1", content = "one", migrationIds = listOf("h1"))),
            incoming = listOf(entry("m9", content = "one", migrationIds = listOf("h1", "h2"))),
        )
        assertEquals(listOf("m1"), merged.map { it.id })
        assertTrue(merged.single().payload.toString().contains("\"h2\""))
    }

    @Test
    fun `dangling relatedIds are dropped from the merge result`() {
        val merged = SettingsSnapshotMerger.mergeMemoryEntries(
            existing = listOf(entry("m1", content = "one", related = listOf("missing"))),
            incoming = emptyList(),
        )
        val payload = merged.single().payload.toString()
        assertTrue(!payload.contains("missing"))
    }

    // ── assistant memories ────────────────────────────────────────────────────

    @Test
    fun `assistant memories dedupe on owner and content and renumber colliding ids`() {
        val merged = SettingsSnapshotMerger.mergeAssistantMemories(
            existing = listOf(
                row("1", 0, """{"id":1,"assistantId":"a1","content":"likes tea"}"""),
                row("5", 1, """{"id":5,"assistantId":"a1","content":"likes coffee"}"""),
            ),
            incoming = listOf(
                row("5", 0, """{"id":5,"assistantId":"a1","content":"likes matcha"}"""),
                row("9", 1, """{"id":9,"assistantId":"a1","content":"likes tea"}"""),
            ),
        )
        // "likes tea" dedupes; the incoming id 5 collides and is renumbered to 6.
        assertEquals(listOf("1", "5", "6"), merged.map { it.id })
        assertTrue(merged.last().payload.toString().contains("\"id\":6"))
    }

    // ── preferences ───────────────────────────────────────────────────────────

    private fun element(json: String) = BackupJson.parse(json)

    @Test
    fun `plain preferences only fill gaps`() {
        val written = SettingsSnapshotMerger.mergePreferences(
            existing = mapOf(
                "local_only_v1" to null,
                "theme_seed_v1" to "\"local\"",
                "brand_new_v1" to null,
            ),
            incoming = mapOf(
                "theme_seed_v1" to element("\"backup\""),
                "brand_new_v1" to element("\"from-backup\""),
                "local_only_v1" to element("\"value\""),
            ),
            incomingKeys = setOf("theme_seed_v1", "brand_new_v1", "local_only_v1"),
        )
        // `theme_seed_v1` keeps its local value; the two absent keys are filled.
        assertEquals(
            mapOf("brand_new_v1" to "\"from-backup\"", "local_only_v1" to "\"value\""),
            written,
        )
    }

    @Test
    fun `pinned models union in order without duplicates`() {
        val written = SettingsSnapshotMerger.mergePreferences(
            existing = mapOf("pinned_models_v1" to "[\"m1\",\"m2\"]"),
            incoming = mapOf("pinned_models_v1" to element("[\"m2\",\"m3\"]")),
            incomingKeys = setOf("pinned_models_v1"),
        )
        assertEquals("[\"m1\",\"m2\",\"m3\"]", written["pinned_models_v1"])
    }

    @Test
    fun `tag relationship maps keep the existing value on conflicts`() {
        val written = SettingsSnapshotMerger.mergePreferences(
            existing = mapOf("assistant_tag_map_v1" to "{\"a1\":\"t1\"}"),
            incoming = mapOf(
                "assistant_tag_map_v1" to element("{\"a1\":\"t9\",\"a2\":\"t2\"}"),
            ),
            incomingKeys = setOf("assistant_tag_map_v1"),
        )
        val merged = BackupJson.parse(written["assistant_tag_map_v1"]!!).jsonObject
        assertEquals("t1", (merged["a1"] as JsonPrimitive).content)
        assertEquals("t2", (merged["a2"] as JsonPrimitive).content)
    }

    @Test
    fun `entity and provider-order keys never reach the preference writer`() {
        val written = SettingsSnapshotMerger.mergePreferences(
            existing = emptyMap(),
            incoming = mapOf(
                "assistants_v1" to element("[]"),
                "providers_order_v1" to element("[]"),
            ),
            incomingKeys = setOf("assistants_v1", "providers_order_v1"),
        )
        assertTrue(written.isEmpty())
    }
}

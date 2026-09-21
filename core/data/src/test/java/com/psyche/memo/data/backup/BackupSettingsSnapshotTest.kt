package com.psyche.memo.data.backup

import com.psyche.memo.data.db.PayloadEntityDao
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the `settings.json` shape. These assertions describe the contract the
 * Flutter import path routes on: source keys (not table names), provider rows
 * as a map plus an order array, and every other entity as an ordered array.
 * A regression here produces a backup that restores "successfully" into an
 * empty app, which is the worst possible failure mode.
 */
class BackupSettingsSnapshotTest {

    private fun row(id: String, payload: String, sortOrder: Int = 0) =
        PayloadEntityDao.Row(id = id, sortOrder = sortOrder, payload = payload, updatedAt = 0L)

    /** The snapshot stores elements in a map; tests always expect the key present. */
    private fun Map<String, kotlinx.serialization.json.JsonElement>.at(key: String) =
        requireNotNull(this[key]) { "missing key $key in settings.json" }

    @Test
    fun `provider_rows is exported as a map plus an order array`() {
        val snapshot = build(
            entities = mapOf(
                "provider_rows" to listOf(
                    row("p2", """{"name":"Second"}""", sortOrder = 1),
                    row("p1", """{"name":"First"}""", sortOrder = 0),
                ),
            ),
        )
        val providers = snapshot.settings.at("provider_configs_v1") as JsonObject
        assertEquals(setOf("p1", "p2"), providers.keys)
        assertEquals("First", providers["p1"]!!.let { (it as JsonObject)["name"]!!.jsonPrimitive.content })

        // Order array follows (sort_order, id), not insertion order.
        val order = snapshot.settings.at("providers_order_v1") as JsonArray
        assertEquals(listOf("p1", "p2"), order.map { it.jsonPrimitive.content })
        // Providers deliberately contribute no row-id projection.
        assertNull(snapshot.entityRowIds["provider_configs_v1"])
    }

    @Test
    fun `order-only provider rows are excluded from the map but keep their position`() {
        val snapshot = build(
            entities = mapOf(
                "provider_rows" to listOf(
                    row("keep", """{"name":"Real"}""", sortOrder = 0),
                    row(
                        "ghost",
                        BackupSettingsSnapshot.PROVIDER_ORDER_ONLY_PAYLOAD,
                        sortOrder = 1,
                    ),
                ),
            ),
        )
        val providers = snapshot.settings.at("provider_configs_v1") as JsonObject
        assertEquals(setOf("keep"), providers.keys)
        // The sentinel row still owns its slot in the ordering.
        val order = snapshot.settings.at("providers_order_v1") as JsonArray
        assertEquals(listOf("keep", "ghost"), order.map { it.jsonPrimitive.content })
    }

    @Test
    fun `ordinary entities are exported as arrays ordered by sort_order then id`() {
        val snapshot = build(
            entities = mapOf(
                "assistant_rows" to listOf(
                    row("b", """{"name":"B"}""", sortOrder = 1),
                    row("a", """{"name":"A"}""", sortOrder = 0),
                    row("c", """{"name":"C"}""", sortOrder = 0),
                ),
            ),
        )
        val assistants = snapshot.settings.at("assistants_v1") as JsonArray
        // Ordered by (sort_order, id): a and c both sit at sort_order 0, so the
        // id tie-break puts a first; b follows on sort_order 1.
        assertEquals(listOf("A", "C", "B"), assistants.map {
            (it as JsonObject).getValue("name").jsonPrimitive.content
        })
        assertEquals(listOf("a", "c", "b"), snapshot.entityRowIds["assistants_v1"])
    }

    @Test
    fun `every declared entity kind appears in the snapshot even when empty`() {
        val snapshot = build(entities = emptyMap())
        val expected = listOf(
            "assistants_v1", "provider_configs_v1",
            "mcp_servers_v1", "world_books_v1", "assistant_memories_v1",
            "quick_phrases_v1", "search_services_v1", "tts_services_v1",
            "instruction_injections_v1", "assistant_tags_v1",
            "memory_entries_v1", "user_profile_fields_v1",
        )
        for (key in expected) {
            assertNotNull("missing entity key $key", snapshot.settings[key])
        }
        // The order key exists alongside the provider map.
        assertNotNull(snapshot.settings["providers_order_v1"])
    }

    @Test
    fun `a malformed payload degrades to a JSON string instead of aborting the backup`() {
        val snapshot = build(
            entities = mapOf(
                "assistant_rows" to listOf(row("bad", "this is not json")),
            ),
        )
        val assistants = snapshot.settings.at("assistants_v1") as JsonArray
        assertEquals(1, assistants.size)
        // The raw text survives verbatim so nothing is silently lost.
        assertEquals("this is not json", assistants[0].jsonPrimitive.content)
    }

    @Test
    fun `nested payload objects survive round-trip intact`() {
        val payload = """{"id":"a1","models":["m1","m2"],"override":{"temp":0.7,"nested":{"x":[1,2]}}}"""
        val snapshot = build(entities = mapOf("assistant_rows" to listOf(row("a1", payload))))
        val assistants = snapshot.settings.at("assistants_v1") as JsonArray
        val restored = assistants[0].toBackupJsonString()
        assertEquals(BackupJson.parse(payload), BackupJson.parse(restored))
    }

    @Test
    fun `payload values are not re-escaped into strings`() {
        val snapshot = build(
            entities = mapOf("assistant_rows" to listOf(row("a", """{"n":42,"b":true,"s":"x"}"""))),
        )
        val first = (snapshot.settings.at("assistants_v1") as JsonArray)[0] as JsonObject
        assertEquals("42", first.getValue("n").jsonPrimitive.content)
        assertEquals("true", first.getValue("b").jsonPrimitive.content)
        assertTrue(first.getValue("n") is JsonPrimitive)
    }

    @Test
    fun `entity source keys are the router registry minus the dropped provider groups`() {
        val snapshot = BackupSettingsSnapshot { emptyMap() }
        // Must line up with SettingsKeyRegistry.ENTITY_SOURCE_KEYS — a mismatch
        // means an import would classify a real entity as an unknown passthrough.
        // **例外**：Memo 是独立项目，供应商分组功能整体删除后 provider_groups_v1
        // 不再写入归档（生成的 registry 来自 Flutter 源的分类表，仍列着这个键）。
        val registry = com.psyche.memo.data.settings.SettingsKeyRegistry.ENTITY_SOURCE_KEYS
        assertEquals(registry - "provider_groups_v1", snapshot.entitySourceKeys().toSet())
    }

    private fun build(
        entities: Map<String, List<PayloadEntityDao.Row>>,
        preferences: Map<String, String> = emptyMap(),
    ): BackupSettingsSnapshot.SnapshotExport {
        val snapshot = BackupSettingsSnapshot { preferences }
        return snapshot.export { table, _ -> entities[table] ?: emptyList() }
    }

    @Test
    fun `preference rows are merged in verbatim`() {
        val snapshot = build(
            entities = emptyMap(),
            preferences = mapOf(
                "theme_mode_v1" to "\"dark\"",
                "selected_model_v1" to """{"provider":"p","model":"m"}""",
            ),
        )
        assertEquals("\"dark\"", snapshot.settings.at("theme_mode_v1").toBackupJsonString())
        assertEquals(
            """{"provider":"p","model":"m"}""",
            snapshot.settings.at("selected_model_v1").toBackupJsonString(),
        )
    }

    @Test
    fun `local-only and discarded keys never travel in a backup`() {
        val snapshot = build(
            entities = emptyMap(),
            preferences = mapOf(
                "theme_mode_v1" to "\"dark\"",
                // device-scoped: window geometry, desktop hotkeys, font scale
                "window_pos_x_v1" to "100",
                "desktop_hotkeys_enabled_v1" to "true",
                "display_chat_font_scale_v1" to "1.2",
                // discarded: superseded legacy keys
                "chat_titles_map" to "{}",
                "pinned_chat_ids" to "[]",
                "provider_configs_backup_v1" to "{}",
                // restore_* is local-only by prefix
                "restore_workspace_v1" to "{\"a\":1}",
            ),
        )
        assertEquals("\"dark\"", snapshot.settings.at("theme_mode_v1").toBackupJsonString())
        for (excluded in listOf(
            "window_pos_x_v1", "desktop_hotkeys_enabled_v1", "display_chat_font_scale_v1",
            "chat_titles_map", "pinned_chat_ids", "provider_configs_backup_v1",
            "restore_workspace_v1",
        )) {
            assertNull("$excluded must not be backed up", snapshot.settings[excluded])
        }
    }

    @Test
    fun `unknown keys are passed through so a newer build's settings survive`() {
        val snapshot = build(
            entities = emptyMap(),
            preferences = mapOf("some_future_setting_v9" to "\"keep me\""),
        )
        assertEquals("\"keep me\"", snapshot.settings.at("some_future_setting_v9").toBackupJsonString())
    }

    @Test
    fun `entity source keys are never duplicated as preference keys`() {
        // An entity's source key is not a preference row; if one ever leaked in
        // it would overwrite the array payload with a scalar.
        val snapshot = build(
            entities = mapOf("assistant_rows" to listOf(row("a", """{"name":"A"}"""))),
            preferences = mapOf("assistants_v1" to "\"should not win\""),
        )
        assertTrue(snapshot.settings["assistants_v1"] is JsonArray)
    }
}

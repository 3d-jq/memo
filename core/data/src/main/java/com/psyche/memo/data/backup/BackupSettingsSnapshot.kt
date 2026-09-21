package com.psyche.memo.data.backup

import com.psyche.memo.data.db.PayloadEntityDao
import com.psyche.memo.data.settings.KeyDisposition
import com.psyche.memo.data.settings.PreferenceRepository
import com.psyche.memo.data.settings.SettingsKeyRegistry
import com.psyche.memo.data.settings.classifyBusinessKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds and applies the `settings.json` payload of a backup archive.
 *
 * Ported from `BusinessSettingsRouter.exportSnapshot` /
 * `exportSnapshotWithRowIds` (`business_settings_router.dart` L269-306). The
 * payload is a flat JSON object keyed by *source key* rather than table name —
 * `assistants_v1`, not `assistant_rows` — because that is the shape the Flutter
 * import path routes on. Getting this wrong produces an archive that restores
 * silently-empty.
 *
 * Two entity kinds are special:
 *
 *  - `provider_rows` is exported as a **map** keyed by provider key, plus a
 *    separate `providers_order_v1` array, and rows carrying the
 *    order-only sentinel payload are excluded from the map.
 *  - every other entity kind is exported as an **array** of decoded payloads,
 *    ordered by `(sort_order, id)`.
 *
 * Preference keys in the snapshot's `preference_rows` are then merged in
 * verbatim; the router's `LOCAL_ONLY` / `DISCARDED` keys are excluded because
 * they are device-local by definition and must not travel in a backup.
 */
internal class BackupSettingsSnapshot(
    private val readPreferenceRows: () -> Map<String, String>,
) {

    /** Maps a `BusinessEntityKind` to its backup source key and payload column. */
    private data class EntitySpec(
        val sourceKey: String,
        val tableName: String,
        val payloadKey: String = "id",
        val isProvider: Boolean = false,
    )

    private val specs: List<EntitySpec> = listOf(
        EntitySpec("assistants_v1", "assistant_rows"),
        EntitySpec("provider_configs_v1", "provider_rows", payloadKey = "provider_key", isProvider = true),
        EntitySpec("mcp_servers_v1", "mcp_server_rows"),
        EntitySpec("world_books_v1", "world_book_rows"),
        EntitySpec("assistant_memories_v1", "assistant_memory_rows"),
        EntitySpec("quick_phrases_v1", "quick_phrase_rows"),
        EntitySpec("search_services_v1", "search_service_rows"),
        EntitySpec("tts_services_v1", "tts_service_rows"),
        EntitySpec("instruction_injections_v1", "instruction_injection_rows"),
        EntitySpec("assistant_tags_v1", "assistant_tag_rows"),
        EntitySpec("memory_entries_v1", "memory_entry_rows"),
        EntitySpec("user_profile_fields_v1", "user_profile_field_rows"),
    )

    companion object {
        /** `_providerOrderOnlyPayload` — a provider row existing only for ordering. */
        const val PROVIDER_ORDER_ONLY_PAYLOAD = """{"enabled":"__kelivo_provider_order_only__"}"""

        const val KEY_PROVIDER_ORDER = "providers_order_v1"

        /** Production wiring: reads preference rows straight from the repository. */
        fun from(repository: PreferenceRepository): BackupSettingsSnapshot =
            BackupSettingsSnapshot { repository.readAllRows() }
    }

    /**
     * Reads the current on-device state into the `settings.json` object.
     *
     * @param readEntities opens a `PayloadEntityDao` for a given table.
     * @param readProviders supplies rows in `(id, sortOrder, payload)` form for
     *   the provider table, which uses a different primary-key column.
     */
    fun export(
        readEntities: (table: String, payloadKey: String) -> List<PayloadEntityDao.Row>,
    ): SnapshotExport {
        val settings = LinkedHashMap<String, JsonElement>()
        val entityRowIds = LinkedHashMap<String, List<String>>()

        for (spec in specs) {
            val rows = readEntities(spec.tableName, spec.payloadKey)
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))

            if (spec.isProvider) {
                val providers = buildJsonObject {
                    for (row in rows) {
                        if (row.payload == PROVIDER_ORDER_ONLY_PAYLOAD) continue
                        put(row.id, decodePayload(row.payload))
                    }
                }
                settings[spec.sourceKey] = providers
                settings[KEY_PROVIDER_ORDER] = buildJsonArray { rows.forEach { add(JsonPrimitive(it.id)) } }
                // `exportSnapshotWithRowIds` deliberately skips providers: the
                // order array already carries their identity.
                continue
            }

            settings[spec.sourceKey] = buildJsonArray {
                rows.forEach { add(decodePayload(it.payload)) }
            }
            entityRowIds[spec.sourceKey] = rows.map { it.id }
        }

        // Preference rows travel verbatim; local-only and discarded keys are
        // device-scoped and must not be restored onto another device.
        for ((key, value) in readPreferenceRows()) {
            if (classifyBusinessKey(key) != KeyDisposition.PREFERENCE &&
                classifyBusinessKey(key) != KeyDisposition.UNKNOWN
            ) {
                continue
            }
            settings[key] = runCatching { BackupJson.parse(value) }
                .getOrElse { JsonPrimitive(value) }
        }

        return SnapshotExport(settings = settings, entityRowIds = entityRowIds)
    }

    data class SnapshotExport(
        val settings: Map<String, JsonElement>,
        val entityRowIds: Map<String, List<String>>,
    )

    /**
     * Decodes a stored payload column. Payloads are JSON text; a malformed one
     * is surfaced as a JSON string rather than aborting the whole backup, so a
     * single bad row cannot make a user's entire backup impossible to take.
     */
    private fun decodePayload(raw: String): JsonElement =
        runCatching { BackupJson.parse(raw) }.getOrElse { JsonPrimitive(raw) }

    /** Entity source keys, in export order — used to validate an import. */
    fun entitySourceKeys(): List<String> = specs.map { it.sourceKey }

    /** All preference keys the registry knows about (for coverage assertions). */
    fun knownPreferenceKeys(): Set<String> = SettingsKeyRegistry.PREFERENCE_KEYS
}

internal object BackupJson {
    val codec = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }

    fun parse(text: String): JsonElement = codec.parseToJsonElement(text)
}

/** Renders an arbitrary JSON element back to compact text. */
internal fun JsonElement.toBackupJsonString(): String = BackupJson.codec.encodeToString(JsonElement.serializer(), this)

/** Convenience for tests and callers that need the element as an object. */
internal fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

/** Convenience for tests and callers that need the element as an array. */
internal fun JsonElement.asArrayOrNull(): JsonArray? = this as? JsonArray

package com.psyche.memo.data.backup

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import com.psyche.memo.data.db.normalizeMemoryContent
import com.psyche.memo.data.settings.KeyDisposition
import com.psyche.memo.data.settings.classifyBusinessKey
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.security.SecureRandom

/**
 * Settings-side merge semantics (sub-block 2), a 1:1 port of
 * `business_settings_merger.dart` operating on generic rows instead of the
 * Dart router's typed snapshot.
 *
 * Entity merges keep the local database identity: rows only the backup has are
 * appended, rows both sides have are updated in place (per-kind rules below),
 * and the merged list is re-numbered `(0..n)`. Preference merges are far more
 * conservative than overwrite — most keys only fill gaps (a local value always
 * wins), with three special-cased union keys.
 *
 * The chat database itself is merged by [DatabaseSnapshotMerger].
 */
internal object SettingsSnapshotMerger {

    /** A row as stored in the entity tables (`sort_order` + JSON `payload`). */
    data class Row(val id: String, val sortOrder: Int, val payload: JsonObject)

    const val PINNED_MODELS_KEY = "pinned_models_v1"
    const val ASR_SERVICES_KEY = "asr_services_v1"
    const val PROVIDER_ORDER_KEY = "providers_order_v1"
    const val PROVIDER_CONFIGS_KEY = "provider_configs_v1"
    const val ACTIVE_IDS_BY_ASSISTANT_KEY = "instruction_injections_active_ids_by_assistant_v1"

    /** Existing-keeps-value JSON maps (tag relationship/collapse state). */
    val RELATIONSHIP_MAP_KEYS = setOf(
        "assistant_tag_map_v1",
        "assistant_tag_collapsed_v1",
    )

    /** `BusinessSettingsRouter._providerOrderOnlyPayload` — an ordering placeholder. */
    const val PROVIDER_ORDER_ONLY_ENABLED = "__kelivo_provider_order_only__"

    fun isProviderOrderOnlyRow(payload: JsonObject): Boolean =
        payload.keys == setOf("enabled") &&
            (payload["enabled"] as? JsonPrimitive)?.content == PROVIDER_ORDER_ONLY_ENABLED

    // ── entities ──────────────────────────────────────────────────────────────

    fun mergeEntities(sourceKey: String, existing: List<Row>, incoming: List<Row>): List<Row> =
        when (sourceKey) {
            "assistants_v1" -> mergeAssistants(existing, incoming)
            PROVIDER_CONFIGS_KEY -> mergeProviders(existing, incoming, preferIncomingOrder = false)
            "memory_entries_v1" -> mergeMemoryEntries(existing, incoming)
            "assistant_memories_v1" -> mergeAssistantMemories(existing, incoming)
            else -> mergeRowsById(existing, incoming)
        }

    /**
     * Assistants: shared ids get `{...local, ...incoming}` with the local
     * avatar/background kept when non-empty (L85-114) — re-downloading a
     * backup must not wipe a locally-chosen avatar the backup never had.
     */
    fun mergeAssistants(existing: List<Row>, incoming: List<Row>): List<Row> {
        val mergedRows = mutableListOf<Row>()
        val indexById = HashMap<String, Int>()
        for (row in orderedRows(existing)) {
            if (indexById.containsKey(row.id)) continue
            indexById[row.id] = mergedRows.size
            mergedRows.add(row)
        }
        for (row in orderedRows(incoming)) {
            val localIndex = indexById[row.id]
            if (localIndex == null) {
                indexById[row.id] = mergedRows.size
                mergedRows.add(row)
                continue
            }
            val local = mergedRows[localIndex].payload
            val merged = LinkedHashMap(local)
            for ((field, value) in row.payload) merged[field] = value
            for (key in listOf("avatar", "background")) {
                val localValue = local.stringOf(key)?.trim().orEmpty()
                if (localValue.isNotEmpty()) {
                    merged[key] = JsonPrimitive(localValue)
                } else {
                    val incomingValue = row.payload.stringOf(key)?.trim().orEmpty()
                    merged[key] = if (incomingValue.isEmpty()) JsonNull else JsonPrimitive(incomingValue)
                }
            }
            mergedRows[localIndex] = mergedRows[localIndex].copy(payload = JsonObject(merged))
        }
        return assignSortOrders(mergedRows)
    }

    /**
     * Providers: incoming payload wins per id, order-only placeholders never
     * displace a real local row (L117-146). [preferIncomingOrder] puts the
     * backup's `providers_order_v1` sequence first.
     */
    fun mergeProviders(
        existing: List<Row>,
        incoming: List<Row>,
        preferIncomingOrder: Boolean,
    ): List<Row> {
        val localRows = orderedRows(existing)
        val importedRows = orderedRows(incoming)
        val selected = LinkedHashMap<String, Row>()
        for (row in localRows) selected[row.id] = row
        for (row in importedRows) {
            val local = selected[row.id]
            if (isProviderOrderOnlyRow(row.payload) && local != null &&
                !isProviderOrderOnlyRow(local.payload)
            ) {
                continue
            }
            selected[row.id] = row
        }
        val orderedIds = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val primary = if (preferIncomingOrder) importedRows else localRows
        val secondary = if (preferIncomingOrder) localRows else importedRows
        for (row in primary + secondary) {
            if (seen.add(row.id)) orderedIds.add(row.id)
        }
        // Android never materialises order-only placeholders (its snapshot
        // builder filters them on export, and a sentinel payload would decode
        // into a phantom provider elsewhere) — they only contribute ordering.
        val out = orderedIds.mapNotNull { id ->
            selected[id]?.takeIf { !isProviderOrderOnlyRow(it.payload) }
        }
        return assignSortOrders(out)
    }

    /** Grouping/collapse/services: first occurrence of an id wins (L221-232). */
    fun mergeRowsById(existing: List<Row>, incoming: List<Row>): List<Row> {
        val merged = mutableListOf<Row>()
        val seen = mutableSetOf<String>()
        for (row in orderedRows(existing) + orderedRows(incoming)) {
            if (seen.add(row.id)) merged.add(row)
        }
        return assignSortOrders(merged)
    }

    /**
     * Assistant memories (legacy int ids in the payload): dedupe on
     * `assistantId + content`; colliding ids are renumbered past the max (L332-365).
     */
    fun mergeAssistantMemories(existing: List<Row>, incoming: List<Row>): List<Row> {
        val merged = mutableListOf<Row>()
        val contentKeys = mutableSetOf<String>()
        val usedIds = mutableSetOf<Int>()
        var maxId = 0
        for (row in orderedRows(existing)) {
            val item = row.payload
            val id = item.intOf("id") ?: 0
            if (id > 0) usedIds.add(id)
            if (id > maxId) maxId = id
            memoryContentKey(item)?.let { contentKeys.add(it) }
            merged.add(row)
        }
        for (row in orderedRows(incoming)) {
            val item = row.payload
            val contentKey = memoryContentKey(item)
            if (contentKey != null && contentKeys.contains(contentKey)) continue
            var id = item.intOf("id") ?: 0
            var selected = row
            if (id <= 0 || usedIds.contains(id)) {
                do {
                    maxId++
                } while (usedIds.contains(maxId))
                id = maxId
                val next = LinkedHashMap(item)
                next["id"] = JsonPrimitive(id)
                selected = row.copy(id = id.toString(), payload = JsonObject(next))
            } else if (id > maxId) {
                maxId = id
            }
            usedIds.add(id)
            contentKey?.let { contentKeys.add(it) }
            merged.add(selected)
        }
        return assignSortOrders(merged)
    }

    /**
     * Memory entries: dedupe on `(scope, assistantId, type, normalized content)`;
     * colliding incoming ids get fresh random ids, relatedIds on incoming rows
     * are rewritten through that remap, then dangling ones are dropped
     * (L368-447).
     */
    fun mergeMemoryEntries(existing: List<Row>, incoming: List<Row>): List<Row> {
        val out = mutableListOf<Row>()
        val indexByKey = HashMap<String, Int>()
        val seenIds = mutableSetOf<String>()
        val idRemap = HashMap<String, String>()

        for (row in orderedRows(existing)) {
            val item = row.payload
            memoryEntryDedupeKey(item)?.let { key -> indexByKey.putIfAbsent(key, out.size) }
            seenIds.add(row.id)
            out.add(row)
        }
        val localCount = out.size

        for (row in orderedRows(incoming)) {
            val item = row.payload
            val key = memoryEntryDedupeKey(item)
            val duplicateIndex = key?.let { indexByKey[it] }
            if (duplicateIndex != null) {
                out[duplicateIndex] = mergeMigrationIds(out[duplicateIndex], item)
                continue
            }
            var selected = row
            var id = row.id
            if (seenIds.contains(id)) {
                val newId = newMemoryEntryId(seenIds)
                idRemap[id] = newId
                val next = LinkedHashMap(item)
                next["id"] = JsonPrimitive(newId)
                id = newId
                selected = row.copy(id = newId, payload = JsonObject(next))
            }
            key?.let { indexByKey[it] = out.size }
            out.add(selected)
            seenIds.add(id)
        }

        if (idRemap.isNotEmpty()) {
            for (index in localCount until out.size) {
                val row = out[index]
                val related = row.payload["relatedIds"] as? kotlinx.serialization.json.JsonArray ?: continue
                val rewritten = related.mapNotNull { entry ->
                    (entry as? JsonPrimitive)?.content?.let { idRemap[it] ?: it }
                }
                val original = related.mapNotNull { (it as? JsonPrimitive)?.content }
                if (original != rewritten) {
                    val next = LinkedHashMap(row.payload)
                    next["relatedIds"] = kotlinx.serialization.json.JsonArray(
                        rewritten.map { JsonPrimitive(it) },
                    )
                    out[index] = row.copy(payload = JsonObject(next))
                }
            }
        }

        val knownIds = out.mapTo(mutableSetOf()) { it.id }
        for (index in out.indices) {
            val row = out[index]
            val related = row.payload["relatedIds"] as? kotlinx.serialization.json.JsonArray ?: continue
            val kept = related.mapNotNull { entry ->
                (entry as? JsonPrimitive)?.content?.takeIf { it in knownIds }
            }
            val original = related.mapNotNull { (it as? JsonPrimitive)?.content }
            if (original == kept) continue
            val next = LinkedHashMap(row.payload)
            next["relatedIds"] = kotlinx.serialization.json.JsonArray(kept.map { JsonPrimitive(it) })
            out[index] = row.copy(payload = JsonObject(next))
        }

        return assignSortOrders(out)
    }

    private fun mergeMigrationIds(kept: Row, incoming: JsonObject): Row {
        val incomingIds = incoming["migrationIds"] as? kotlinx.serialization.json.JsonArray ?: return kept
        val keptItem = kept.payload
        val seen = mutableSetOf<String>()
        val mergedIds = mutableListOf<String>()
        (keptItem["migrationIds"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?.forEach { if (seen.add(it)) mergedIds.add(it) }
        var changed = false
        incomingIds.mapNotNull { (it as? JsonPrimitive)?.content }.forEach { id ->
            if (seen.add(id)) {
                mergedIds.add(id)
                changed = true
            }
        }
        if (!changed) return kept
        val next = LinkedHashMap(keptItem)
        next["migrationIds"] = kotlinx.serialization.json.JsonArray(mergedIds.map { JsonPrimitive(it) })
        return kept.copy(payload = JsonObject(next))
    }

    private fun memoryContentKey(item: JsonObject): String? {
        val assistantId = item.stringOf("assistantId")?.trim().orEmpty()
        val content = item.stringOf("content")?.trim().orEmpty()
        if (assistantId.isEmpty() || content.isEmpty()) return null
        return "$assistantId\n$content"
    }

    private fun memoryEntryDedupeKey(item: JsonObject): String? {
        val scope = item.stringOf("scope").orEmpty()
        val type = item.stringOf("type").orEmpty()
        val content = item.stringOf("content") ?: return null
        if (scope.isEmpty() || type.isEmpty()) return null
        val assistantId = item.stringOf("assistantId").orEmpty()
        return "$scope\u0000$assistantId\u0000$type\u0000${normalizeMemoryContent(content)}"
    }

    private val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    private fun newMemoryEntryId(seenIds: MutableSet<String>): String {
        val random = SecureRandom()
        while (true) {
            val id = "mem_" + buildString {
                repeat(8) { append(ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]) }
            }
            if (seenIds.add(id)) return id
        }
    }

    private fun orderedRows(rows: List<Row>): List<Row> =
        rows.sortedWith(compareBy<Row> { it.sortOrder }.thenBy { it.id })

    private fun assignSortOrders(rows: List<Row>): List<Row> =
        rows.mapIndexed { index, row -> if (row.sortOrder == index) row else row.copy(sortOrder = index) }

    private fun JsonObject.stringOf(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.intOf(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toIntOrNull()

    // ── preferences ───────────────────────────────────────────────────────────

    /**
     * Merge-mode preference rules (L131-153): entity/order/local-only keys are
     * routed elsewhere, [PINNED_MODELS_KEY] unions, [ASR_SERVICES_KEY] unions
     * by id preferring existing, relationship maps keep the existing value on
     * conflicts, and every other key only fills a gap (a local value always
     * wins). Returns `key -> encoded value` for the keys to write.
     */
    fun mergePreferences(
        existing: Map<String, String?>,
        incoming: Map<String, kotlinx.serialization.json.JsonElement>,
        incomingKeys: Set<String>,
    ): Map<String, String> {
        val effectiveIncomingKeys = buildSet {
            addAll(incomingKeys)
            if (incoming.containsKey(ACTIVE_IDS_BY_ASSISTANT_KEY)) add(ACTIVE_IDS_BY_ASSISTANT_KEY)
        }
        val out = LinkedHashMap<String, String>()
        for (key in effectiveIncomingKeys) {
            val disposition = classifyBusinessKey(key)
            if (disposition == KeyDisposition.ENTITY ||
                disposition == KeyDisposition.PROVIDER_ORDER ||
                disposition == KeyDisposition.LOCAL_ONLY ||
                disposition == KeyDisposition.DISCARDED
            ) {
                continue
            }
            val imported = incoming[key] ?: continue
            val existingRaw = existing[key]?.takeIf { it.isNotEmpty() }
            when {
                key == PINNED_MODELS_KEY -> out[key] = mergeStringLists(existingRaw, imported)
                key == ASR_SERVICES_KEY ->
                    out[key] = mergeJsonObjectListsByIdPreferExisting(existingRaw, imported)
                key in RELATIONSHIP_MAP_KEYS -> {
                    val importedObject = imported as? JsonObject
                        ?: throw IllegalArgumentException(key)
                    out[key] = mergeJsonMapsPreferExisting(existingRaw, importedObject)
                }
                else -> if (existingRaw == null) out[key] = imported.toBackupJsonString()
            }
        }
        return out
    }

    private fun mergeStringLists(existingRaw: String?, incoming: kotlinx.serialization.json.JsonElement): String {
        val existing = parseList(existingRaw).map { it.jsonStringOf() }
        val imported = parseList(incoming.toBackupJsonString()).map { it.jsonStringOf() }
        val seen = mutableSetOf<String>()
        return kotlinx.serialization.json.JsonArray(
            (existing + imported).filter { seen.add(it) }.map { JsonPrimitive(it) },
        ).compact()
    }

    private fun mergeJsonObjectListsByIdPreferExisting(existingRaw: String?, incoming: kotlinx.serialization.json.JsonElement): String {
        val existing = parseList(existingRaw).mapNotNull { it as? JsonObject }
        val imported = parseList(incoming.toBackupJsonString()).mapNotNull { it as? JsonObject }
        val seenIds = existing.mapNotNullTo(mutableSetOf()) { it.stringOf("id") }
        val merged = existing + imported.filter { service ->
            val id = service.stringOf("id")
            id == null || seenIds.add(id)
        }
        return kotlinx.serialization.json.JsonArray(merged.map { it }).compact()
    }

    /** Parses a JSON array; a null/blank/invalid input is an empty list. */
    private fun parseList(raw: String?): List<kotlinx.serialization.json.JsonElement> {
        if (raw.isNullOrEmpty()) return emptyList()
        val element = runCatching { BackupJson.parse(raw) }.getOrNull() ?: return emptyList()
        return element as? kotlinx.serialization.json.JsonArray ?: emptyList()
    }

    private fun mergeJsonMapsPreferExisting(existingRaw: String?, incoming: kotlinx.serialization.json.JsonObject): String {
        val existing = existingRaw
            ?.let { BackupJson.parse(it) as? JsonObject }
            ?: JsonObject(emptyMap())
        val merged = LinkedHashMap(incoming)
        for ((key, value) in existing) merged[key] = value
        return JsonObject(merged).compact()
    }


    private fun kotlinx.serialization.json.JsonArray.compact(): String =
        BackupJson.codec.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), this)

    private fun kotlinx.serialization.json.JsonObject.compact(): String =
        BackupJson.codec.encodeToString(JsonObject.serializer(), this)

    private fun kotlinx.serialization.json.JsonElement.jsonStringOf(): String = when (this) {
        is JsonPrimitive -> content
        else -> throw IllegalArgumentException("pinned_models_v1")
    }
}

package com.psyche.memo.data.repo

import com.psyche.memo.data.model.SearchCommonOptions
import com.psyche.memo.data.model.SearchServiceOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure search-settings codec — the parts of settings_provider.dart's search
 * block (search_services_v1 / search_selected_v1 / search_common_v1) that are
 * worth testing without a database. Invalid input falls back to upstream
 * defaults instead of throwing.
 */
object SearchSettingsLogic {

    private val json = Json { ignoreUnknownKeys = true }

    /** settings_provider.dart L1399-1409: decode the JSON array, tolerate junk. */
    fun parseServices(raw: String?): List<SearchServiceOptions> {
        if (raw.isNullOrEmpty()) return listOf(SearchServiceOptions.defaultOption)
        return runCatching {
            val arr = json.parseToJsonElement(raw) as? JsonArray ?: return@runCatching emptyList()
            arr.mapNotNull { el ->
                (el as? JsonObject)?.let { SearchServiceOptions.fromJson(it) }
            }
        }.getOrDefault(emptyList()).ifEmpty { listOf(SearchServiceOptions.defaultOption) }
    }

    fun encodeServices(services: List<SearchServiceOptions>): String =
        JsonArray(services.map { it.toJson() }).toString()

    fun parseCommon(raw: String?): SearchCommonOptions {
        if (raw.isNullOrEmpty()) return SearchCommonOptions()
        return runCatching {
            SearchCommonOptions.fromJson(json.parseToJsonElement(raw) as? JsonObject)
        }.getOrDefault(SearchCommonOptions())
    }

    fun encodeCommon(options: SearchCommonOptions): String = options.toJson().toString()

    /** setSearchServices clamps the selection into the new list. */
    fun clampSelected(index: Int, size: Int): Int = when {
        size <= 0 -> 0
        index >= size -> size - 1
        index < 0 -> 0
        else -> index
    }

    /** Preference ints are stored as JSON text (`5` or `"5"`); tolerate both. */
    fun parseInt(raw: String?, fallback: Int): Int {
        if (raw.isNullOrEmpty()) return fallback
        val text = raw.trim().removeSurrounding("\"")
        return text.toIntOrNull() ?: fallback
    }
}

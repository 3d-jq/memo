package com.psyche.memo.data.repo

import android.database.sqlite.SQLiteDatabase
import com.psyche.memo.data.db.PayloadEntityDao
import com.psyche.memo.data.model.SearchCommonOptions
import com.psyche.memo.data.model.SearchServiceOptions
import com.psyche.memo.data.settings.PreferenceRepository
import kotlinx.serialization.json.JsonPrimitive

/**
 * Search service settings — mirrors settings_provider.dart's search block with
 * the upstream storage routing:
 *
 * - `search_services_v1` is an ENTITY source key → search_service_rows
 *   (payload = the options JSON, sort_order = list order),
 * - `search_selected_v1` / `search_common_v1` / `search_enabled_v1` are
 *   PREFERENCE keys → preference_rows as JSON text.
 */
class SearchSettingsRepository(
    db: SQLiteDatabase,
    private val prefs: PreferenceRepository,
) {
    private val dao = PayloadEntityDao(db, "search_service_rows", primaryKey = "id")

    fun services(): List<SearchServiceOptions> {
        val rows = dao.getAll()
        if (rows.isEmpty()) return listOf(SearchServiceOptions.defaultOption)
        val parsed = rows.mapNotNull { row ->
            runCatching {
                SearchServiceOptions.fromJson(
                    kotlinx.serialization.json.Json.parseToJsonElement(row.payload)
                        as? kotlinx.serialization.json.JsonObject ?: return@runCatching null,
                )
            }.getOrNull()
        }
        return parsed.ifEmpty { listOf(SearchServiceOptions.defaultOption) }
    }

    /** Replaces the whole list (order = sort_order 0..n-1). */
    fun setServices(services: List<SearchServiceOptions>) {
        val keep = services.map { it.id }.toSet()
        for (row in dao.getAll()) {
            if (row.id !in keep) dao.delete(row.id)
        }
        services.forEachIndexed { index, service ->
            dao.upsert(service.id, service.toJson().toString(), index)
        }
        val selected = SearchSettingsLogic.clampSelected(selectedIndex(), services.size)
        setSelectedIndex(selected)
    }

    fun addService(service: SearchServiceOptions) {
        val next = services().toMutableList()
        val existing = next.indexOfFirst { it.id == service.id }
        if (existing >= 0) next[existing] = service else next.add(service)
        setServices(next)
    }

    fun updateService(service: SearchServiceOptions) {
        val next = services().toMutableList()
        val idx = next.indexOfFirst { it.id == service.id }
        if (idx < 0) return
        next[idx] = service
        setServices(next)
    }

    fun deleteService(id: String) {
        val next = services().filterNot { it.id == id }
        setServices(next)
    }

    fun selectedIndex(): Int = SearchSettingsLogic.parseInt(prefs.readJson(SELECTED_KEY), 0)

    fun setSelectedIndex(index: Int) {
        prefs.writeJson(SELECTED_KEY, JsonPrimitive(SearchSettingsLogic.clampSelected(index, services().size)).toString())
    }

    fun selectedService(): SearchServiceOptions? {
        val list = services()
        if (list.isEmpty()) return null
        return list[SearchSettingsLogic.clampSelected(selectedIndex(), list.size)]
    }

    fun commonOptions(): SearchCommonOptions = SearchSettingsLogic.parseCommon(prefs.readJson(COMMON_KEY))

    fun setCommonOptions(options: SearchCommonOptions) {
        prefs.writeJson(COMMON_KEY, SearchSettingsLogic.encodeCommon(options))
    }

    /** Global search master switch (search_enabled_v1). */
    fun searchEnabled(): Boolean =
        SearchSettingsLogic.parseInt(prefs.readJson(ENABLED_KEY), 0) == 1

    fun setSearchEnabled(enabled: Boolean) {
        prefs.writeJson(ENABLED_KEY, JsonPrimitive(if (enabled) 1 else 0).toString())
    }

    fun autoTestOnLaunch(): Boolean =
        SearchSettingsLogic.parseInt(prefs.readJson(AUTO_TEST_KEY), 0) == 1

    fun setAutoTestOnLaunch(enabled: Boolean) {
        prefs.writeJson(AUTO_TEST_KEY, JsonPrimitive(if (enabled) 1 else 0).toString())
    }

    companion object {
        const val SELECTED_KEY = "search_selected_v1"
        const val COMMON_KEY = "search_common_v1"
        const val ENABLED_KEY = "search_enabled_v1"
        const val AUTO_TEST_KEY = "search_auto_test_on_launch_v1"
    }
}

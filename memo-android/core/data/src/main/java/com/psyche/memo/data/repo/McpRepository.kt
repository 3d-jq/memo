package com.psyche.memo.data.repo

import android.database.sqlite.SQLiteDatabase
import com.psyche.memo.data.db.PayloadEntityDao
import com.psyche.memo.data.model.McpServerConfig
import kotlinx.serialization.json.Json

/**
 * MCP server storage — mcp_server_rows (payload = McpServerConfig JSON).
 * Mirrors McpProvider's persisted list semantics: order is the row sort_order.
 */
class McpRepository(db: SQLiteDatabase) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dao = PayloadEntityDao(db, "mcp_server_rows")

    fun servers(): List<McpServerConfig> =
        dao.getAll().mapNotNull { row ->
            runCatching { json.decodeFromString(McpServerConfig.serializer(), row.payload) }.getOrNull()
        }

    fun server(id: String): McpServerConfig? =
        dao.get(id)?.let { row ->
            runCatching { json.decodeFromString(McpServerConfig.serializer(), row.payload) }.getOrNull()
        }

    fun save(server: McpServerConfig) {
        val isNew = dao.get(server.id) == null
        val sortOrder = if (isNew) dao.nextSortOrder() else dao.get(server.id)!!.sortOrder
        dao.upsert(server.id, json.encodeToString(McpServerConfig.serializer(), server), sortOrder)
    }

    fun delete(id: String) = dao.delete(id)

    fun enabledServers(): List<McpServerConfig> = servers().filter { it.enabled }
}

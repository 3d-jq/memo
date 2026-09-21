package com.psyche.memo.data.workspace

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 工作区记录（移植 RikkaHub `data/db/entity/WorkspaceEntity.kt`）。
 *
 * Memo 没有 Room，记录存在 drift v3 的通用表 `extension_entity_rows`
 * （`kind = "workspace"`），本类就是那条记录的 payload。字段名与上游逐一对齐，
 * 便于以后与上游对拍。
 */
@Serializable
data class WorkspaceEntity(
    val id: String,
    val name: String,
    /** 工作区目录名（当前等于 [id]）。 */
    val root: String,
    /** `WorkspaceShellStatus` 的名字；core:data 不依赖 core:workspace，所以存字符串。 */
    val shellStatus: String = "DISABLED",
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessAt: Long? = null,
    /** 工具审批的用户覆盖（toolName -> needsApproval）；未覆盖的工具沿用默认值。 */
    val toolApprovals: String = "{}",
) {
    /** `tool_approvals` 的解析结果；坏 JSON 一律当「没有覆盖」。 */
    fun toolApprovalOverrides(): Map<String, Boolean> = runCatching {
        json.decodeFromString<Map<String, Boolean>>(toolApprovals)
    }.getOrDefault(emptyMap())

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(entity: WorkspaceEntity): String = json.encodeToString(serializer(), entity)

        fun decode(payload: String): WorkspaceEntity? =
            runCatching { json.decodeFromString(serializer(), payload) }.getOrNull()
    }
}

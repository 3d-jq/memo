package com.psyche.memo.data.workspace

import android.database.sqlite.SQLiteDatabase
import com.psyche.memo.data.db.ExtensionEntityDao
import java.util.UUID
import kotlinx.serialization.json.Json

/** 工具审批覆盖表的编解码（与 [WorkspaceEntity] 里的解码对称）。 */
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * 工作区记录的持久化（RikkaHub `WorkspaceDAO` + `WorkspaceRepository` 的数据半边）。
 *
 * 只碰记录；目录/rootfs/命令执行在 core:workspace 的 `WorkspaceManager`，两者的编排在
 * app 层的 `WorkspaceRepository`。
 */
class WorkspaceStore(db: SQLiteDatabase) {

    private val dao = ExtensionEntityDao(db, KIND)

    fun getAll(): List<WorkspaceEntity> = dao.getAll().mapNotNull { WorkspaceEntity.decode(it.payload) }

    fun get(id: String): WorkspaceEntity? = dao.get(id)?.let { WorkspaceEntity.decode(it.payload) }

    /** 名字（trim 后精确匹配）是否已被别的记录占用。 */
    fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return getAll().any { it.id != excludeId && it.name.trim() == target }
    }

    /** 与上游一致：名字空白时回落 "Workspace"，重名直接抛。 */
    fun create(name: String): WorkspaceEntity {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val finalName = name.trim().ifBlank { DEFAULT_NAME }
        require(!isNameTaken(finalName, excludeId = null)) {
            "Workspace name already exists: $finalName"
        }
        val entity = WorkspaceEntity(id = id, name = finalName, root = id, createdAt = now, updatedAt = now)
        put(entity)
        return entity
    }

    fun rename(id: String, name: String): Boolean {
        val entity = get(id) ?: return false
        val finalName = name.trim().ifBlank { entity.name }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        put(entity.copy(name = finalName, updatedAt = System.currentTimeMillis()))
        return true
    }

    fun setShellStatus(id: String, shellStatus: String): Boolean {
        val entity = get(id) ?: return false
        put(entity.copy(shellStatus = shellStatus, updatedAt = System.currentTimeMillis()))
        return true
    }

    fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean {
        val entity = get(id) ?: return false
        val overrides = entity.toolApprovalOverrides() + (toolName to needsApproval)
        put(
            entity.copy(
                toolApprovals = json.encodeToString(overrides),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return true
    }

    /** 标记一次访问（`last_access_at`）。 */
    fun touch(id: String, at: Long = System.currentTimeMillis()): Boolean {
        val entity = get(id) ?: return false
        put(entity.copy(lastAccessAt = at, updatedAt = at))
        return true
    }

    fun delete(id: String): Boolean {
        if (get(id) == null) return false
        dao.delete(id)
        return true
    }

    private fun put(entity: WorkspaceEntity) {
        val existing = dao.get(entity.id)
        dao.upsert(
            id = entity.id,
            payload = WorkspaceEntity.encode(entity),
            sortOrder = existing?.sortOrder ?: dao.nextSortOrder(),
            ownerId = existing?.ownerId,
        )
    }

    companion object {
        const val KIND = "workspace"
        const val DEFAULT_NAME = "Workspace"
    }
}

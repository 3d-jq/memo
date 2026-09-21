package com.psyche.memo.data.generation

import android.database.sqlite.SQLiteDatabase
import com.psyche.memo.data.db.ExtensionEntityDao
import com.psyche.memo.data.model.GenerationKind
import com.psyche.memo.data.model.GenerationService
import com.psyche.memo.data.model.GenerationTestState
import java.util.UUID

/**
 * 生成服务记录的持久化（自研功能）。
 *
 * 与 [com.psyche.memo.data.workspace.WorkspaceStore] 同一套做法：记录存 drift v3 的
 * 通用表 `extension_entity_rows`（`kind = "generation_service"`），payload 是
 * [GenerationService] 的 JSON。**不要给它单独建表** —— schema 由 drift 生成且门禁
 * 校验零 diff。
 */
class GenerationServiceStore(db: SQLiteDatabase) {

    private val dao = ExtensionEntityDao(db, KIND)

    /** 某个类型的全部服务（按 sort_order）；[kind] null = 两类都要。 */
    fun getAll(kind: String? = null): List<GenerationService> =
        dao.getAll()
            .mapNotNull { GenerationService.decode(it.payload) }
            .filter { kind == null || it.kind == kind }

    fun get(id: String): GenerationService? =
        dao.get(id)?.let { GenerationService.decode(it.payload) }

    /** 新建（id 由调用方给；空 id 时自动生成）。返回落库后的记录。 */
    fun create(service: GenerationService): GenerationService {
        val id = service.id.trim().ifEmpty { UUID.randomUUID().toString() }
        val normalized = service.copy(id = id).normalized()
        dao.upsert(id, GenerationService.encode(normalized), dao.nextSortOrder())
        return normalized
    }

    /** 更新（保留原 sort_order）。记录不存在时返回 null。 */
    fun update(service: GenerationService): GenerationService? {
        val existing = get(service.id) ?: return null
        val row = dao.get(service.id)
        val normalized = service.copy(createdAt = existing.createdAt).normalized()
        dao.upsert(
            normalized.id,
            GenerationService.encode(normalized),
            row?.sortOrder ?: dao.nextSortOrder(),
            row?.ownerId,
        )
        return normalized
    }

    fun delete(id: String): Boolean {
        if (get(id) == null) return false
        dao.delete(id)
        return true
    }

    /** 「测试连接」结果落库（[state] = [GenerationTestState] 之一；null 清成「没测过」）。 */
    fun setTestState(id: String, state: String?, at: Long = System.currentTimeMillis()): GenerationService? {
        val existing = get(id) ?: return null
        val next = existing.copy(
            lastTestState = GenerationTestState.normalize(state),
            lastTestAt = at,
        )
        val row = dao.get(id)
        dao.upsert(id, GenerationService.encode(next), row?.sortOrder ?: 0, row?.ownerId)
        return next
    }

    companion object {
        const val KIND = "generation_service"

        /** 两类都列出来时的顺序（设置页两个入口都读这一份）。 */
        val ALL_KINDS = listOf(GenerationKind.IMAGE, GenerationKind.VIDEO)
    }
}

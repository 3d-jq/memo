package com.psyche.memo.data.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作区记录（存在 `extension_entity_rows` 的 payload）。
 *
 * 这里只覆盖纯逻辑：记录的 JSON 往返。DAO/仓储那半边要
 * `SQLiteDatabase`，core:data 没有 Robolectric，放到 app 的接线测试里覆盖。
 */
class WorkspaceEntityTest {

    private fun entity(
        id: String = "ws-1",
        name: String = "Scratch",
        shellStatus: String = "DISABLED",
    ) = WorkspaceEntity(
        id = id,
        name = name,
        root = id,
        shellStatus = shellStatus,
        createdAt = 1_700_000_000_000,
        updatedAt = 1_700_000_001_000,
        lastAccessAt = null,
    )

    @Test
    fun payloadRoundTripKeepsEveryField() {
        val original = entity(shellStatus = "READY")
        val decoded = WorkspaceEntity.decode(WorkspaceEntity.encode(original))
        assertEquals(original, decoded)
    }

    /** 缺字段的旧 payload 要能解出来（默认值兜底），不能整条丢。 */
    @Test
    fun decodingToleratesMissingOptionalFields() {
        val decoded = WorkspaceEntity.decode(
            """{"id":"a","name":"A","root":"a","createdAt":1,"updatedAt":2}""",
        )
        assertEquals("DISABLED", decoded!!.shellStatus)
        assertNull(decoded.lastAccessAt)
    }

    @Test
    fun encodingWritesDefaultsSoRoundTripIsStable() {
        val json = WorkspaceEntity.encode(entity())
        assertTrue(json.contains("\"shellStatus\":\"DISABLED\""))
    }

    @Test
    fun brokenPayloadDecodesToNullInsteadOfThrowing() {
        assertNull(WorkspaceEntity.decode("not json at all"))
        assertNull(WorkspaceEntity.decode("""{"name":"missing id"}"""))
    }

    /**
     * 工具审批体系已整块拆除（用户 2026-09-25），字段也删了 —— 但**已存在的 payload 里还
     * 带着 `toolApprovals`**，必须照样解得出来（`ignoreUnknownKeys`），否则老工作区会凭空
     * 消失。这条就是那个兼容性的网。
     */
    @Test
    fun decodingIgnoresTheRemovedToolApprovalsKey() {
        val decoded = WorkspaceEntity.decode(
            WorkspaceEntity.encode(entity()).replace(
                "\"shellStatus\":\"DISABLED\"",
                "\"shellStatus\":\"DISABLED\",\"toolApprovals\":\"{\\\"workspace_shell\\\":true}\"",
            ),
        )
        assertEquals(entity(), decoded)
    }
}

package com.psyche.memo.provider.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「读过才许改」的账本（B10）。
 *
 * 判错的代价是双向的：漏判 = 模型没读过就能把用户手改的内容盖回去（工具还会报成功）；
 * 误判 = 正常改完一次再改第二次被自己的写入卡住，工作区工具直接不可用。
 * 所以「按会话分键」「写完刷新」「LRU 限长」「删会话清账」这四条都要钉住。
 */
class WorkspaceReadLedgerTest {

    private val workspace = "ws-1"

    @Test
    fun `editing before reading is refused`() {
        val ledger = WorkspaceReadLedger()
        assertEquals(
            WorkspaceReadLedger.EditCheck.NotRead,
            ledger.check("conv-1", workspace, "/workspace/a.md", currentSizeBytes = 128),
        )
    }

    @Test
    fun `a read at the same size allows the edit`() {
        val ledger = WorkspaceReadLedger()
        ledger.record("conv-1", workspace, "/workspace/a.md", 128)
        assertEquals(
            WorkspaceReadLedger.EditCheck.Ok,
            ledger.check("conv-1", workspace, "/workspace/a.md", currentSizeBytes = 128),
        )
    }

    /** 读的时候 128 字节，现在 512 —— 中间有人（用户或另一颗工具）改过。 */
    @Test
    fun `a file that changed since the read is stale`() {
        val ledger = WorkspaceReadLedger()
        ledger.record("conv-1", workspace, "/workspace/a.md", 128)
        val check = ledger.check("conv-1", workspace, "/workspace/a.md", currentSizeBytes = 512)
        assertEquals(WorkspaceReadLedger.EditCheck.Stale(128, 512), check)
        assertTrue(
            "报错句要带上两个字节数，模型才知道真变了",
            WorkspaceEditGuards.stale("/workspace/a.md", 128, 512).contains("128 -> 512"),
        )
    }

    @Test
    fun `another conversation does not inherit the read`() {
        val ledger = WorkspaceReadLedger()
        ledger.record("conv-1", workspace, "/workspace/a.md", 128)
        assertEquals(
            WorkspaceReadLedger.EditCheck.NotRead,
            ledger.check("conv-2", workspace, "/workspace/a.md", currentSizeBytes = 128),
        )
    }

    @Test
    fun `another workspace path is a different file`() {
        val ledger = WorkspaceReadLedger()
        ledger.record("conv-1", "ws-1", "/workspace/a.md", 128)
        assertEquals(
            WorkspaceReadLedger.EditCheck.NotRead,
            ledger.check("conv-1", "ws-2", "/workspace/a.md", currentSizeBytes = 128),
        )
    }

    @Test
    fun `writing refreshes the record so the next edit is allowed`() {
        val ledger = WorkspaceReadLedger()
        ledger.record("conv-1", workspace, "/workspace/a.md", 128)
        // 编辑成功后 writeFile 会按新大小再记一次。
        ledger.record("conv-1", workspace, "/workspace/a.md", 200)
        assertEquals(
            WorkspaceReadLedger.EditCheck.Ok,
            ledger.check("conv-1", workspace, "/workspace/a.md", currentSizeBytes = 200),
        )
    }

    @Test
    fun `forget drops the claim`() {
        val ledger = WorkspaceReadLedger()
        ledger.record("conv-1", workspace, "/workspace/a.md", 128)
        ledger.forget("conv-1", workspace, "/workspace/a.md")
        assertEquals(
            WorkspaceReadLedger.EditCheck.NotRead,
            ledger.check("conv-1", workspace, "/workspace/a.md", currentSizeBytes = 128),
        )
    }

    @Test
    fun `dropping a conversation clears only its own rows`() {
        val ledger = WorkspaceReadLedger()
        ledger.record("conv-1", workspace, "/workspace/a.md", 10)
        ledger.record("conv-2", workspace, "/workspace/a.md", 10)
        ledger.dropConversation("conv-1")
        assertEquals(1, ledger.size)
        assertEquals(
            WorkspaceReadLedger.EditCheck.NotRead,
            ledger.check("conv-1", workspace, "/workspace/a.md", currentSizeBytes = 10),
        )
        assertEquals(
            WorkspaceReadLedger.EditCheck.Ok,
            ledger.check("conv-2", workspace, "/workspace/a.md", currentSizeBytes = 10),
        )
    }

    /** 表是容器级的，会话越开越多 —— 不设上限就是内存泄漏；设了就必须真的淘汰最旧的。 */
    @Test
    fun `capacity evicts the least recently used entry`() {
        val ledger = WorkspaceReadLedger(capacity = 3)
        ledger.record("conv-1", workspace, "/a", 1)
        ledger.record("conv-1", workspace, "/b", 1)
        // 碰一下 /a，让 /b 成为最久未用的那个。
        ledger.check("conv-1", workspace, "/a", currentSizeBytes = 1)
        ledger.record("conv-1", workspace, "/c", 1)
        ledger.record("conv-1", workspace, "/d", 1)
        assertEquals(3, ledger.size)
        assertEquals(
            WorkspaceReadLedger.EditCheck.NotRead,
            ledger.check("conv-1", workspace, "/b", currentSizeBytes = 1),
        )
        assertEquals(
            WorkspaceReadLedger.EditCheck.Ok,
            ledger.check("conv-1", workspace, "/a", currentSizeBytes = 1),
        )
    }
}

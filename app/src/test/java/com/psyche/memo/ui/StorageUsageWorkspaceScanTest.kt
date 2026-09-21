package com.psyche.memo.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「聊天记录存储一直显示统计中」（用户 2026-09-15）的真机根因：`filesDir` 里
 * `workspaces` 有 34,342 个文件（占全应用 34,615 的 99.2%）—— 全是沙箱工作区的
 * Linux rootfs。逐文件 stat 它要十几秒，数字永远填不上。
 *
 * 判据只认 `workspaces/<助手 id>/linux` 这一层，避免误伤别处同名的 linux 目录。
 */
class StorageUsageWorkspaceScanTest {

    private fun dir(path: String) = File(path.replace('/', File.separatorChar))

    @Test
    fun `the sandbox rootfs is skipped`() {
        assertTrue(
            StorageUsage.isWorkspaceRootfs(
                dir("files/workspaces/7beb11d3-01c5-4531-a2e6-20f0b9254809/linux"),
            ),
        )
    }

    @Test
    fun `the user-visible workspace areas are still counted`() {
        val workspace = "files/workspaces/7beb11d3-01c5-4531-a2e6-20f0b9254809"
        assertFalse(StorageUsage.isWorkspaceRootfs(dir("$workspace/files")))
        assertFalse(StorageUsage.isWorkspaceRootfs(dir("$workspace")))
        assertFalse(StorageUsage.isWorkspaceRootfs(dir("files/workspaces")))
    }

    @Test
    fun `a linux directory elsewhere is untouched`() {
        assertFalse(StorageUsage.isWorkspaceRootfs(dir("files/linux")))
        assertFalse(StorageUsage.isWorkspaceRootfs(dir("files/snapshots/linux")))
    }
}

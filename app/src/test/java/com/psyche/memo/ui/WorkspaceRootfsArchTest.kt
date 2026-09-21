package com.psyche.memo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 工作区管理页里唯一值得单测的纯逻辑：**Rootfs 源按设备 ABI 选**。
 *
 * 上游 RikkaHub 写死 arm64；我们的 proot 二进制同时提供 arm64-v8a 与 x86_64，
 * 所以模拟器（x86_64）必须拿 amd64 那份，否则 rootfs 装上了也跑不起来。
 */
class WorkspaceRootfsArchTest {

    @Test
    fun `x86 devices get the amd64 rootfs`() {
        assertEquals("amd64", rootfsArchFor("x86_64"))
        assertEquals("amd64", rootfsArchFor("x86"))
    }

    @Test
    fun `arm devices get the arm64 rootfs`() {
        assertEquals("arm64", rootfsArchFor("arm64-v8a"))
        assertEquals("arm64", rootfsArchFor("armeabi-v7a"))
    }

    /** 拿不到 ABI 时回落到 arm64 —— 与上游写死的默认一致，真机绝大多数是 arm64。 */
    @Test
    fun `unknown abi falls back to arm64`() {
        assertEquals("arm64", rootfsArchFor(null))
        assertEquals("arm64", rootfsArchFor(""))
    }
}

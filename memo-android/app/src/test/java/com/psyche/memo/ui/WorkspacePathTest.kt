package com.psyche.memo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 工作区详情页的路径纯逻辑：面包屑与「上一级」。
 *
 * 这两条决定用户能不能从子目录退回根 —— 退不回去就只能杀了页面重进，所以单独钉住。
 */
class WorkspacePathTest {

    @Test
    fun `root path shows only the workspace name`() {
        assertEquals("Scratch", breadcrumb("Scratch", ""))
        assertEquals("Scratch", breadcrumb("Scratch", "   "))
    }

    @Test
    fun `nested path is split into segments`() {
        assertEquals("Scratch / src", breadcrumb("Scratch", "src"))
        assertEquals("Scratch / src / lib", breadcrumb("Scratch", "src/lib"))
    }

    @Test
    fun `parent walks one level up and stops at the root`() {
        assertEquals("src", parentOf("src/lib"))
        assertEquals("src/lib", parentOf("src/lib/deep"))
        // 根再上一级还是根（空串），页面据此隐藏「..」行。
        assertEquals("", parentOf("src"))
        assertEquals("", parentOf(""))
    }

    /** 尾斜杠不能让上一级退两级。 */
    @Test
    fun `trailing slash is ignored`() {
        assertEquals("src", parentOf("src/lib/"))
    }
}

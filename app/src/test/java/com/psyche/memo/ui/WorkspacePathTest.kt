package com.psyche.memo.ui

import com.psyche.memo.workspace.WorkspaceFileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 工作区详情页的纯逻辑：路径「上一级」、文件类型分流、字节数格式化。
 *
 * 「上一级」决定用户能不能从子目录退回根（退不回去只能杀了页面重进）；
 * 文件类型决定点文件时走编辑器 / 图片查看器 / 系统应用三条路；
 * 大小文案在文件行上直接显示，量级判错会让 1 GB 的文件写成 "1024.00 MB"。
 */
class WorkspacePathTest {

    @Test
    fun `parent walks one level up and stops at the root`() {
        assertEquals("src", parentOf("src/lib"))
        assertEquals("src/lib", parentOf("src/lib/deep"))
        // 根再上一级还是根（空串），页面据此禁用后退按钮。
        assertEquals("", parentOf("src"))
        assertEquals("", parentOf(""))
    }

    /** 尾斜杠不能让上一级退两级。 */
    @Test
    fun `trailing slash is ignored`() {
        assertEquals("src", parentOf("src/lib/"))
    }

    /** rootfs 区是绝对路径，同样要能一级一级往上退。 */
    @Test
    fun `absolute rootfs path walks up to the filesystem root`() {
        assertEquals("/etc/nginx", parentOf("/etc/nginx/conf.d"))
        assertEquals("/etc", parentOf("/etc/nginx"))
        assertEquals("", parentOf("/etc"))
    }

    @Test
    fun `file type is detected from the extension`() {
        assertEquals(WorkspaceFileType.TEXT, entry("notes.md").detectFileType())
        assertEquals(WorkspaceFileType.TEXT, entry("main.KT").detectFileType())
        assertEquals(WorkspaceFileType.IMAGE, entry("shot.PNG").detectFileType())
        assertEquals(WorkspaceFileType.OTHER, entry("archive.zip").detectFileType())
        // 没有扩展名的名字（Makefile 这类）交给系统应用打开，
        // 免得把二进制当文本读进编辑器。
        assertEquals(WorkspaceFileType.OTHER, entry("Makefile").detectFileType())
    }

    /**
     * 可渲染的那两类点开是**渲染**，不是源码（用户 2026-09-25「工作区像 html 有些文件
     * 点击跟没有渲染显示呀」）。其余一律 null —— 判错会让 css/js 这类文件点开变成
     * 一坨被浏览器模板包起来的乱码。
     */
    @Test
    fun `html and markdown render, everything else stays source`() {
        assertEquals(WorkspaceRenderKind.HTML, entry("page.html").renderKind())
        assertEquals(WorkspaceRenderKind.HTML, entry("PAGE.HTM").renderKind())
        assertEquals(WorkspaceRenderKind.MARKDOWN, entry("notes.md").renderKind())
        assertEquals(WorkspaceRenderKind.MARKDOWN, entry("README.markdown").renderKind())
        assertNull("css 不是整篇渲染的文档", entry("site.css").renderKind())
        assertNull(entry("main.kt").renderKind())
        assertNull(entry("index").renderKind())
    }

    @Test
    fun `file size switches unit and precision like upstream`() {
        assertEquals("0 B", 0L.fileSizeToString())
        assertEquals("1023 B", 1023L.fileSizeToString())
        assertEquals("1.00 KB", 1024L.fileSizeToString())
        assertEquals("10.0 KB", (10 * 1024L).fileSizeToString())
        assertEquals("100 KB", (100 * 1024L).fileSizeToString())
        assertEquals("1.00 MB", (1024L * 1024).fileSizeToString())
        assertEquals("1.00 GB", (1024L * 1024 * 1024).fileSizeToString())
        assertEquals("2.00 TB", (2L * 1024 * 1024 * 1024 * 1024).fileSizeToString())
    }

    private fun entry(name: String) = WorkspaceFileEntry(
        path = name,
        name = name,
        isDirectory = false,
        sizeBytes = 0,
        updatedAt = 0,
    )
}

package com.psyche.memo.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 存储页「图片」档的判定：**必须认 `.svg`**。
 *
 * 自研可视化工具（`render_visual`）的图表/自由绘制产物就是 SVG，漏了它存储页里
 * 就看不到这些文件（用户 2026-09-18「存储数据的图片里面怎么也没有记录呀」）。
 */
class StorageImageCategoryTest {

    @Test
    fun `svg counts as an image`() {
        assertTrue(isImageFileName("gen_1789733193770_3419da.svg"))
        assertTrue(isImageFileName("CHART.SVG"))
    }

    @Test
    fun `the usual raster formats still count`() {
        listOf("a.png", "a.jpg", "a.jpeg", "a.gif", "a.webp", "a.heic", "a.heif", "a.bmp", "a.ico")
            .forEach { assertTrue(it, isImageFileName(it)) }
    }

    @Test
    fun `everything else stays out`() {
        listOf("memo.db", "notes.txt", "bundle.zip", "clip.mp4", "song.mp3", "vector.svgz")
            .forEach { assertFalse(it, isImageFileName(it)) }
    }
}

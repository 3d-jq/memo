package com.psyche.memo.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SVG 产物要能「保存/分享」——走的是位图通道（MediaStore 按 image/png 写、微信等只认
 * 常见位图），所以必须先按 SVG 判定、栅格化成 PNG。
 *
 * 用户 2026-09-18：「生成的这个 svg 点击下载不了」。
 */
class SvgMediaSaveTest {

    @Test
    fun `svg urls are recognised`() {
        assertTrue(isSvgUrl("/data/user/0/com.psyche.memo.dev/files/images/gen_1.svg"))
        assertTrue(isSvgUrl("file:///x/gen_1.svg"))
        assertTrue(isSvgUrl("data:image/svg+xml;base64,PHN2Zy8+"))
        assertTrue(isSvgUrl("/x/gen.svg?v=2"))
    }

    @Test
    fun `raster images are not treated as svg`() {
        assertFalse(isSvgUrl("/x/gen_1.png"))
        assertFalse(isSvgUrl("https://example.com/a.jpg"))
        assertFalse(isSvgUrl(""))
    }

    @Test
    fun `raster size scales the longest edge up to the cap`() {
        // 900×560 的图表画布 → 长边 2048，等比
        assertEquals(2048 to 1274, svgPngSize(900f, 560f))
        // 又高又窄的流程图：长边仍是 2048
        val (w, h) = svgPngSize(400f, 1600f)
        assertEquals(2048, h)
        assertTrue(w in 1..2047)
        // 尺寸缺失（拿不到 documentWidth）时按默认画布走
        assertEquals(2048 to 1274, svgPngSize(0f, 0f))
        // 超大画布不放大（缩放下限 1），避免内存爆
        assertEquals(4000 to 2500, svgPngSize(4000f, 2500f))
    }
}

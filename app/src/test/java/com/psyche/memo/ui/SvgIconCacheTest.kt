package com.psyche.memo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SVG 解码缓存的两颗判据（缓存本身走 Coil 管线，外观一致；这里只钉"什么才进缓存"）。
 *
 * 只接管 asset 里的 .svg：远程/文件头像与 PNG 仍旧交给 Coil 的 `AsyncImage`，
 * 免得把非 SVG 的解码语义（缩放、采样、EXIF）搅进这份按像素尺寸分键的缓存。
 */
class SvgIconCacheTest {

    @Test
    fun cacheKeySeparatesSizesOfTheSameAsset() {
        val asset = "file:///android_asset/icons/brand_deepseek.svg"
        assertEquals("$asset@84", svgIconCacheKey(asset, 84))
        org.junit.Assert.assertNotEquals(svgIconCacheKey(asset, 84), svgIconCacheKey(asset, 56))
    }

    @Test
    fun onlyAssetSvgIsTakenOver() {
        assertTrue(usesCachedSvgDecoding("file:///android_asset/icons/brand.svg"))
        assertTrue(usesCachedSvgDecoding("file:///android_asset/ICONS/Brand.SVG"))
        // 非 SVG / 非 asset / 空路径都不接管。
        assertFalse(usesCachedSvgDecoding("file:///android_asset/icons/avatar.png"))
        assertFalse(usesCachedSvgDecoding("https://cdn.example.com/brand.svg"))
        assertFalse(usesCachedSvgDecoding("file:///data/user/0/com.psyche.memo/files/upload/a.svg"))
        assertFalse(usesCachedSvgDecoding(""))
    }
}

package com.psyche.memo.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Translations of `lib/utils/image_compressor.dart` + settings_provider.dart
 * 5249-5282. The format sniffing and the transparency gate decide whether an
 * image is re-encoded at all, so an off-by-one on the 64 KB floor or a wrong
 * PNG color type would silently flatten transparency or skip compression.
 */
class ImageCompressorTest {

    // ---- 档位映射（settings_provider.dart:5251-5281） ----

    @Test
    fun tierMappingMatchesTheOriginal() {
        val off = ImageCompressConfig.configFor("original", 85, false)
        assertEquals(ImageCompressConfig(false, 100, 1568, false), off)

        assertEquals(ImageCompressConfig(true, 90, 2048, false), ImageCompressConfig.configFor("high", 85, false))
        assertEquals(ImageCompressConfig(true, 85, 1568, false), ImageCompressConfig.configFor("balanced", 85, false))
        assertEquals(ImageCompressConfig(true, 70, 1024, false), ImageCompressConfig.configFor("saver", 85, false))
        assertEquals(ImageCompressConfig(true, 73, 1568, false), ImageCompressConfig.configFor("custom", 73, false))
        // 未知档位回退 balanced（默认值）。
        assertEquals(ImageCompressConfig(true, 85, 1568, false), ImageCompressConfig.configFor("nonsense", 85, false))
    }

    @Test
    fun transparentFlagTravelsThroughEveryTier() {
        ImageCompressConfig.configFor("saver", 85, true).let { assertTrue(it.includeTransparent) }
    }

    @Test
    fun prefsDefaultsAndClamps() {
        val defaults = ImageCompressConfig.fromPrefs { null }
        assertEquals(ImageCompressConfig(true, 85, 1568, false), defaults)

        val store = mapOf(
            ImageCompressConfig.TIER_KEY to "custom",
            ImageCompressConfig.CUSTOM_QUALITY_KEY to "200",
            ImageCompressConfig.TRANSPARENT_KEY to "1",
        )
        val custom = ImageCompressConfig.fromPrefs { store[it] }
        assertEquals(100, custom.quality)
        assertTrue(custom.includeTransparent)

        val tooLow = ImageCompressConfig.fromPrefs { key ->
            when (key) {
                ImageCompressConfig.TIER_KEY -> "custom"
                ImageCompressConfig.CUSTOM_QUALITY_KEY -> "1"
                else -> null
            }
        }
        assertEquals(ImageCompressConfig.MIN_CUSTOM_QUALITY, tooLow.quality)
    }

    // ---- 格式嗅探（image_compressor.dart:176-204） ----

    @Test
    fun detectsJpegPngAndGifHeaders() {
        assertEquals(ImageCompressor.Format.JPEG, ImageCompressor.detectFormat(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(
            ImageCompressor.Format.PNG,
            ImageCompressor.detectFormat(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
        )
        assertEquals(
            ImageCompressor.Format.GIF,
            ImageCompressor.detectFormat(bytes(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)),
        )
        assertEquals(ImageCompressor.Format.OTHER, ImageCompressor.detectFormat(bytes(0x52, 0x49, 0x46, 0x46)))
        assertEquals(ImageCompressor.Format.OTHER, ImageCompressor.detectFormat(ByteArray(0)))
    }

    // ---- 透明闸门（image_compressor.dart:143-160 + 206-238） ----

    @Test
    fun pngWithAlphaChannelNeedsOptIn() {
        assertTrue(ImageCompressor.pngNeedsOptIn(pngHeader(colorType = 6)))
        assertTrue(ImageCompressor.pngNeedsOptIn(pngHeader(colorType = 4)))
        assertFalse(ImageCompressor.pngNeedsOptIn(pngHeader(colorType = 2)))
    }

    @Test
    fun pngWithTransparencyChunkNeedsOptIn() {
        // IHDR(colorType 2) 之后跟一个 tRNS 块。
        val bytes = pngHeader(colorType = 2) + chunk("tRNS") + chunk("IDAT")
        assertTrue(ImageCompressor.pngNeedsOptIn(bytes))
    }

    @Test
    fun plainPngDoesNotNeedOptIn() {
        val bytes = pngHeader(colorType = 2) + chunk("IDAT")
        assertFalse(ImageCompressor.pngNeedsOptIn(bytes))
    }

    @Test
    fun malformedShortPngIsNotTreatedAsTransparent() {
        assertFalse(ImageCompressor.pngNeedsOptIn(bytes(0x89, 0x50, 0x4E, 0x47)))
    }

    // ---- 跳过判定 ----

    @Test
    fun smallAndDisabledInputsAreSkipped() {
        val jpegSmall = bytes(0xFF, 0xD8, 0xFF) + ByteArray(1024)
        assertTrue(ImageCompressor.shouldSkip(jpegSmall, ImageCompressConfig(true, 85, 1568, false)))
        assertTrue(
            ImageCompressor.shouldSkip(
                bigJpeg(),
                ImageCompressConfig(false, 100, 1568, false),
            ),
        )
    }

    @Test
    fun gifAndUnknownFormatsOnlyCompressWithTheTransparentToggle() {
        val gif = bytes(0x47, 0x49, 0x46, 0x38, 0x39, 0x61) + ByteArray(ImageCompressor.MIN_BYTES_TO_COMPRESS)
        assertTrue(ImageCompressor.shouldSkip(gif, ImageCompressConfig(true, 85, 1568, false)))
        assertFalse(ImageCompressor.shouldSkip(gif, ImageCompressConfig(true, 85, 1568, true)))
    }

    @Test
    fun transparentPngOnlyCompressesWithTheTransparentToggle() {
        val png = pngHeader(colorType = 6) + ByteArray(ImageCompressor.MIN_BYTES_TO_COMPRESS)
        assertTrue(ImageCompressor.shouldSkip(png, ImageCompressConfig(true, 85, 1568, false)))
        assertFalse(ImageCompressor.shouldSkip(png, ImageCompressConfig(true, 85, 1568, true)))
    }

    @Test
    fun bigJpegIsNeverSkipped() {
        assertFalse(ImageCompressor.shouldSkip(bigJpeg(), ImageCompressConfig(true, 85, 1568, false)))
    }

    /**
     * HEIC/HEIF 判定（ISO BMFF：偏移 4 是 `ftyp`，兼容品牌从偏移 12 起每 4 字节一组）。
     * 画质管线本来就不碰它（`detectFormat` 归 OTHER），`AttachmentStore` 靠这个判定决定
     * 要不要转码 —— 相册 HEIC 原字节发给厂商必被 400（用户 2026-09-20 实测）。
     */
    @Test
    fun detectsHeicBrandsInTheFtypBox() {
        // 布局：[size 4][ftyp][主品牌][minor version 4][兼容品牌…]
        fun ftyp(major: String, vararg compatible: String): ByteArray =
            byteArrayOf(0, 0, 0, 0x18) + "ftyp".toByteArray(Charsets.ISO_8859_1) +
                major.toByteArray(Charsets.ISO_8859_1) +
                byteArrayOf(0, 0, 0, 0) +
                compatible.joinToString("").toByteArray(Charsets.ISO_8859_1)

        assertTrue(ImageCompressor.isHeic(ftyp("heic")))
        assertTrue(ImageCompressor.isHeic(ftyp("hevc")))
        // 截图常见：主品牌 mif1，兼容品牌里才有 heic。
        assertTrue(ImageCompressor.isHeic(ftyp("mif1", "heic")))
        assertFalse(ImageCompressor.isHeic(ftyp("isom", "mp41", "avc1")))
        assertFalse(ImageCompressor.isHeic(ftyp("avif")))
        assertFalse(ImageCompressor.isHeic(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertFalse(ImageCompressor.isHeic(ByteArray(8)))
    }

    /** 认不出格式（含 HEIC）时 `transcodeToJpeg` 直接返回 null，不去解码浪费内存。 */
    @Test
    fun transcodeOnlyAcceptsHeicInput() {
        assertEquals(null, ImageCompressor.transcodeToJpeg(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(null, ImageCompressor.transcodeToJpeg(ByteArray(0)))
    }

    // ---- 缩放/采样 ----

    @Test
    fun longEdgeIsScaledDownProportionallyAndNeverUp() {
        assertEquals(1568 to 784, ImageCompressor.scaleToMaxLongEdge(3136, 1568, 1568))
        assertEquals(784 to 1568, ImageCompressor.scaleToMaxLongEdge(1568, 3136, 1568))
        assertEquals(800 to 600, ImageCompressor.scaleToMaxLongEdge(800, 600, 1568))
        assertEquals(100 to 100, ImageCompressor.scaleToMaxLongEdge(100, 100, 2048))
    }

    @Test
    fun sampleSizeIsTheLargestPowerOfTwoThatStaysAboveTheTarget() {
        assertEquals(1, ImageCompressor.decodeSampleSize(1000, 800, 1568))
        assertEquals(2, ImageCompressor.decodeSampleSize(4096, 3072, 1568))
        assertEquals(4, ImageCompressor.decodeSampleSize(8192, 6144, 1568))
    }

    // ---- helpers ----

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun bigJpeg(): ByteArray =
        bytes(0xFF, 0xD8, 0xFF, 0xE0) + ByteArray(ImageCompressor.MIN_BYTES_TO_COMPRESS)

    /** 最小合法 PNG 头：签名 + IHDR(len13) + colorType。 */
    private fun pngHeader(colorType: Int): ByteArray {
        val out = ByteArray(33)
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        sig.copyInto(out)
        // length = 13 at offset 8
        out[11] = 13
        "IHDR".forEachIndexed { i, c -> out[12 + i] = c.code.toByte() }
        out[25] = colorType.toByte()
        return out
    }

    /** 一个 0 长度数据块，只关心 chunk type。 */
    private fun chunk(type: String): ByteArray {
        val out = ByteArray(12)
        "ABCD".forEachIndexed { i, c -> out[4 + i] = c.code.toByte() }
        type.forEachIndexed { i, c -> out[4 + i] = c.code.toByte() }
        return out
    }
}

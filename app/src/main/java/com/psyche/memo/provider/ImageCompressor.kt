package com.psyche.memo.provider

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 图片画质档位（settings_provider.dart:5249-5282 `resolveImageCompressConfig`
 * + image_settings_page.dart 的五行 UI）。
 */
data class ImageCompressConfig(
    val enabled: Boolean,
    val quality: Int,
    val maxLongEdge: Int,
    val includeTransparent: Boolean,
) {
    companion object {
        const val TIER_KEY = "image_upload_quality_v1"
        const val CUSTOM_QUALITY_KEY = "image_compress_custom_quality_v1"
        const val TRANSPARENT_KEY = "image_compress_transparent_enabled_v1"
        const val DEFAULT_TIER = "balanced"
        const val DEFAULT_CUSTOM_QUALITY = 85
        const val MIN_CUSTOM_QUALITY = 10
        const val MAX_CUSTOM_QUALITY = 100

        fun fromPrefs(read: (String) -> String?): ImageCompressConfig {
            val tier = read(TIER_KEY)?.trim()?.trim('"')?.takeIf { it.isNotEmpty() } ?: DEFAULT_TIER
            val includeTransparent = read(TRANSPARENT_KEY)?.trim()?.let { it == "1" || it == "true" } ?: false
            return configFor(tier, customQuality(read), includeTransparent)
        }

        internal fun customQuality(read: (String) -> String?): Int =
            (read(CUSTOM_QUALITY_KEY)?.trim()?.trim('"')?.toIntOrNull() ?: DEFAULT_CUSTOM_QUALITY)
                .coerceIn(MIN_CUSTOM_QUALITY, MAX_CUSTOM_QUALITY)

        /** settings_provider.dart:5251-5281 —— 五档 → {enabled, quality, maxLongEdge}。 */
        internal fun configFor(
            tier: String,
            customQuality: Int,
            includeTransparent: Boolean,
        ): ImageCompressConfig = when (tier) {
            "original" -> ImageCompressConfig(false, 100, 1568, includeTransparent)
            "high" -> ImageCompressConfig(true, 90, 2048, includeTransparent)
            "saver" -> ImageCompressConfig(true, 70, 1024, includeTransparent)
            "custom" -> ImageCompressConfig(true, customQuality, 1568, includeTransparent)
            // balanced（默认，settings_provider.dart:1229-1235）
            else -> ImageCompressConfig(true, 85, 1568, includeTransparent)
        }
    }
}

/**
 * 1:1 port of `lib/utils/image_compressor.dart` —— 上传前把图片压成更小的 JPEG。
 *
 * 规则（原版逐条对应）：
 * - `kMinBytesToCompress = 64KB`：小于它的原样保留（:24 / :72）。
 * - 格式嗅探（:176-204）：JPEG / PNG / GIF / 其他。
 * - 透明闸门（:143-160 + :206-238）：**没开**透明压缩时，带 alpha 的 PNG（colorType
 *   4/6 或出现 tRNS/`acTL`）与 GIF/其他格式一律不压（压成 JPEG 会把透明压成黑、
 *   把动图压成静帧）；开了就压。
 * - 输出必须**严格更小**才采用（:169-172），否则保留原字节。
 * - 压缩结果统一重编码为 JPEG（:105-107），所以调用方的 mime 要跟着变成 image/jpeg。
 * - 解码失败 / 异常 → 返回 null（调用方保留原字节，:46-64）。
 *
 * Android 侧的两个等价替换：Downsize 的 `quality`/`maxLongEdge` 用
 * `BitmapFactory` + `createScaledBitmap` + `Bitmap.compress` 实现（透明像素按原版
 * 注释的口径铺白底再编码）；重编码会丢掉 EXIF，所以按 EXIF 方向先把像素转正。
 */
object ImageCompressor {

    const val MIN_BYTES_TO_COMPRESS = 64 * 1024

    /** 转码（非压缩）时的 JPEG 质量与长边上限：只求格式对，不追求变小。 */
    const val TRANSCODE_QUALITY = 92
    const val MAX_TRANSCODE_LONG_EDGE = 4096

    /** ISO BMFF compatible brand：HEIF 静图家族（视频/通用 isom 不收）。 */
    private val HEIC_BRANDS = setOf(
        "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs",
        "hesv", "mif1", "msf1", "hif1", "hif2",
    )

    enum class Format { JPEG, PNG, GIF, OTHER }

    /** 该图是否跳过压缩（原版 `compressBytes` 的提前返回 + `_compressTask` 的 switch）。 */
    internal fun shouldSkip(bytes: ByteArray, config: ImageCompressConfig): Boolean {
        if (!config.enabled || bytes.size < MIN_BYTES_TO_COMPRESS) return true
        return when (detectFormat(bytes)) {
            Format.JPEG -> false
            Format.PNG -> !config.includeTransparent && pngNeedsOptIn(bytes)
            Format.GIF, Format.OTHER -> !config.includeTransparent
        }
    }

    /** 压缩成 JPEG；跳过 / 失败 / 没变小都返回 null。 */
    fun compress(bytes: ByteArray, config: ImageCompressConfig): ByteArray? {
        if (shouldSkip(bytes, config)) return null
        val result = encodeJpeg(bytes, config.maxLongEdge, config.quality) ?: return null
        // 原版 :169-172：输出必须严格更小才采用。
        return if (result.size >= bytes.size) null else result
    }

    /**
     * HEIC/HEIF → JPEG 的**格式转换**（不受画质闸门管）：解码成功就重编码，解不动返回
     * null。上游没有这一步，是因为 `image_picker` 插件默认 `heicToJpg: true`（Android
     * 侧选图就已经是 JPEG）；Memo 用 SAF/PhotoPicker 选图绕过了它，相册里的 HEIC 会
     * 原字节落盘，再按 `image/heic` 发给厂商 → 400「unsupported image」。
     */
    fun transcodeToJpeg(bytes: ByteArray, maxLongEdge: Int = MAX_TRANSCODE_LONG_EDGE): ByteArray? {
        if (!isHeic(bytes)) return null
        return encodeJpeg(bytes, maxLongEdge, TRANSCODE_QUALITY)
    }

    /**
     * ISO BMFF 盒式判定：`ftyp` 在第 4..8 字节，**第 8..12 是主品牌**，第 12..16 是
     * minor version（不是品牌），兼容品牌从第 16 字节起每 4 个一组。iPhone 的 `.HEIC`
     * 照片主品牌就是 `heic`/`hevc`，截图常见主品牌 `mif1` + 兼容品牌 `heic`，所以两段都查。
     */
    internal fun isHeic(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        if (!isChunkType(bytes, 4, "ftyp")) return false
        fun brandAt(offset: Int): Boolean =
            offset + 4 <= bytes.size &&
                String(bytes, offset, 4, Charsets.ISO_8859_1).lowercase() in HEIC_BRANDS
        if (brandAt(8)) return true
        var offset = 16
        while (offset + 4 <= bytes.size) {
            if (brandAt(offset)) return true
            offset += 4
        }
        return false
    }

    /** 解字节 → 转正 → 长边收缩 → JPEG。尺寸/画质由调用方给。 */
    private fun encodeJpeg(bytes: ByteArray, maxLongEdge: Int, quality: Int): ByteArray? =
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val options = BitmapFactory.Options().apply {
                inSampleSize = decodeSampleSize(bounds.outWidth, bounds.outHeight, maxLongEdge)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@runCatching null
            bitmap = bitmap.rotatedByExif(bytes)
            val (targetW, targetH) = scaleToMaxLongEdge(bitmap.width, bitmap.height, maxLongEdge)
            if (targetW != bitmap.width || targetH != bitmap.height) {
                val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                if (scaled != bitmap) bitmap.recycle()
                bitmap = scaled
            }
            // JPEG 没有 alpha 通道：透明区域铺白底（原版「透明压缩」打开时的口径）。
            val out = ByteArrayOutputStream()
            val flat = bitmap.flattenOnWhite()
            flat.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
            if (flat != bitmap) flat.recycle()
            bitmap.recycle()
            out.toByteArray().takeIf { it.isNotEmpty() }
        }.getOrNull()

    /** image_compressor.dart:176-204 `_detectFormat`。 */
    internal fun detectFormat(bytes: ByteArray): Format {
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        ) {
            return Format.JPEG
        }
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte()
        ) {
            return Format.PNG
        }
        if (bytes.size >= 6 &&
            bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte() &&
            (bytes[4] == 0x37.toByte() || bytes[4] == 0x39.toByte()) &&
            bytes[5] == 0x61.toByte()
        ) {
            return Format.GIF
        }
        return Format.OTHER
    }

    /** image_compressor.dart:206-238 `_pngNeedsOptIn`。 */
    internal fun pngNeedsOptIn(bytes: ByteArray): Boolean {
        val ihdrChunkEnd = 33
        if (bytes.size < ihdrChunkEnd || readUint32(bytes, 8) != 13 || !isChunkType(bytes, 12, "IHDR")) {
            return false
        }
        // 保守判定：colorType 4/6 与 tRNS/acTL 都算「可能含透明/动画」。
        val colorType = bytes[25].toInt()
        if (colorType == 4 || colorType == 6) return true

        var offset = ihdrChunkEnd
        while (offset + 12 <= bytes.size) {
            val dataLength = readUint32(bytes, offset)
            if (dataLength > bytes.size - offset - 12) return false
            val typeOffset = offset + 4
            if (isChunkType(bytes, typeOffset, "tRNS") || isChunkType(bytes, typeOffset, "acTL")) return true
            if (isChunkType(bytes, typeOffset, "IDAT") || isChunkType(bytes, typeOffset, "IEND")) return false
            offset += dataLength + 12
        }
        return false
    }

    /** 只缩不放：长边超过 [maxLongEdge] 时按比例缩到它。 */
    internal fun scaleToMaxLongEdge(width: Int, height: Int, maxLongEdge: Int): Pair<Int, Int> {
        val longEdge = maxOf(width, height)
        if (longEdge <= maxLongEdge) return width to height
        val scale = maxLongEdge.toDouble() / longEdge
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }

    /** 解码时的 2 的幂采样，避免先解出整张原图。 */
    internal fun decodeSampleSize(width: Int, height: Int, maxLongEdge: Int): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxLongEdge) sample *= 2
        return sample
    }

    private fun readUint32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF shl 24) or
            (bytes[offset + 1].toInt() and 0xFF shl 16) or
            (bytes[offset + 2].toInt() and 0xFF shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun isChunkType(bytes: ByteArray, offset: Int, type: String): Boolean {
        if (offset + 4 > bytes.size) return false
        for (i in 0 until 4) {
            if (bytes[offset + i].toInt() != type[i].code) return false
        }
        return true
    }

    /** 按 EXIF 方向把像素转正（重编码成 JPEG 后 EXIF 不再保留）。 */
    private fun Bitmap.rotatedByExif(source: ByteArray): Bitmap {
        val orientation = runCatching {
            android.media.ExifInterface(ByteArrayInputStream(source))
                .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            android.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f)
            }
            android.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.postScale(-1f, 1f)
            }
            else -> return this
        }
        val rotated = runCatching { Bitmap.createBitmap(this, 0, 0, width, height, matrix, true) }.getOrNull()
        if (rotated == null || rotated == this) return this
        recycle()
        return rotated
    }

    /** 透明像素铺白底（JPEG 无 alpha）。没有 alpha 时原样返回。 */
    private fun Bitmap.flattenOnWhite(): Bitmap {
        if (!hasAlpha()) return this
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(this@flattenOnWhite, 0f, 0f, null)
        }
        return out
    }
}

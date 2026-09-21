package com.psyche.memo.provider.generation

import android.content.Context
import java.io.File
import java.util.UUID

/** 生成结果落盘后的一句话：绝对路径 + MIME。 */
data class GeneratedMedia(
    val path: String,
    val mimeType: String,
    /** 视频封面（缩略图）路径；没有就是 null，UI 退化成通用播放卡。 */
    val thumbnailPath: String? = null,
) {
    val file: File get() = File(path)
    val thumbnailFile: File? get() = thumbnailPath?.let { File(it) }
}

/**
 * 生成结果的落地目录（自研功能）。
 *
 * 图片放 `<filesDir>/images/`（原版 `getImagesDirectory`，存储页按「助手图片」归类，
 * 也随备份一起走），视频放 `<filesDir>/videos/`（新增目录，存储页与备份的归类在
 * UI 批次里补）。文件名带时间戳 + 短随机串，避免同秒覆盖。
 */
class GeneratedMediaStore(context: Context) {

    private val appContext = context.applicationContext

    fun imagesDir(): File = File(appContext.filesDir, "images").apply { mkdirs() }

    fun videosDir(): File = File(appContext.filesDir, "videos").apply { mkdirs() }

    /** 把生成出来的图片字节写成文件；返回绝对路径。 */
    fun saveImage(bytes: ByteArray, mimeType: String, stamp: Long = System.currentTimeMillis()): GeneratedMedia {
        val file = File(imagesDir(), "gen_${stamp}_${shortId()}.${extensionFor(mimeType)}")
        file.writeBytes(bytes)
        return GeneratedMedia(path = file.absolutePath, mimeType = mimeType)
    }

    /** 视频：内容 + 可选封面。 */
    fun saveVideo(
        bytes: ByteArray,
        stamp: Long = System.currentTimeMillis(),
        thumbnail: ByteArray? = null,
    ): GeneratedMedia {
        val base = "vid_${stamp}_${shortId()}"
        val file = File(videosDir(), "$base.mp4")
        file.writeBytes(bytes)
        val cover = thumbnail?.let { data ->
            File(videosDir(), "$base.jpg").apply { writeBytes(data) }
        }
        return GeneratedMedia(
            path = file.absolutePath,
            mimeType = "video/mp4",
            thumbnailPath = cover?.absolutePath,
        )
    }

    private fun shortId(): String = UUID.randomUUID().toString().take(6)

    companion object {
        /** 上游图片接口的 output_format → 文件扩展名（默认 png）。 */
        fun extensionFor(mimeType: String): String = when (mimeType.lowercase()) {
            // 图表（render_chart）也是走这条通道的 SVG 产物。
            "image/svg+xml", "svg" -> "svg"
            "image/jpeg", "image/jpg", "jpeg", "jpg" -> "jpg"
            "image/webp", "webp" -> "webp"
            else -> "png"
        }
    }
}

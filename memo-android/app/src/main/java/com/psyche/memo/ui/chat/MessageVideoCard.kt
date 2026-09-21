package com.psyche.memo.ui.chat

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ImageOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Video
import com.psyche.memo.data.model.FilePart
import com.psyche.memo.ui.rememberLoaded
import com.psyche.memo.ui.theme.MemoRadius
import java.io.File

/**
 * 消息里的**视频**附件卡（自研生成视频功能的产物）。
 *
 * 为什么要单开一张卡：视频原先走 [MessageDocCard]（文件图标 + 文件名），生成出来的东西
 * 看着像个附件而不是作品（用户 2026-09-21「这个视频在对话里面的显示不好看」）。现在按
 * 视频的形状渲染：第一帧做缩略图 + 中间播放角标 + 右下角时长，点击仍交给系统播放器
 * （和文件卡同一套 FileProvider 逻辑，见 [openDocument]）。
 *
 * 首帧解码走 [rememberLoaded]（produceState + Dispatchers.IO）—— 组合期不许碰文件，
 * 这条有 `CompositionThreadingTest` 守着。解码失败只显示占位图标，不影响点击。
 */
@Composable
fun MessageVideoCard(
    part: FilePart,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val path = part.uri.trim()
    val missing = part.unavailable == true || path.isEmpty() || !File(path).exists()
    val meta = rememberLoaded(VideoMeta(), path) { readVideoMeta(path) }

    val ratio = if (meta.width > 0 && meta.height > 0) {
        meta.width.toFloat() / meta.height.toFloat()
    } else {
        16f / 9f
    }

    Box(
        modifier = modifier
            .width(220.dp)
            .aspectRatio(ratio.coerceIn(0.6f, 2.2f))
            .clip(RoundedCornerShape(MemoRadius.SMALL_DP.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.5f))
            .clickable(enabled = !missing) { openDocument(context, path, part.mime ?: "video/mp4") },
        contentAlignment = Alignment.Center,
    ) {
        val frame = meta.frame
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Lucide.ImageOff.takeIf { missing } ?: Lucide.Video,
                contentDescription = null,
                tint = cs.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(28.dp),
            )
        }
        if (!missing) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.34f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Lucide.Play,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        if (meta.durationMs > 0) {
            Text(
                text = formatVideoDuration(meta.durationMs),
                style = TextStyle(fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Medium),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(MemoRadius.SMALL_DP.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

internal data class VideoMeta(
    val frame: Bitmap? = null,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
)

/** 秒级时长 → `m:ss`（超过一小时给 `h:mm:ss`）。负数与 0 都返回 `0:00`。 */
internal fun formatVideoDuration(durationMs: Long): String {
    val total = (durationMs.coerceAtLeast(0L)) / 1000L
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** 首帧 + 时长 + 分辨率；任何失败都退化成空 meta（只影响观感，不影响点击）。 */
private fun readVideoMeta(path: String): VideoMeta {
    if (path.isEmpty() || path.startsWith("http") || !File(path).exists()) return VideoMeta()
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        VideoMeta(
            frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC),
            durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
            width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
            height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
        )
    } catch (e: Exception) {
        VideoMeta()
    } finally {
        runCatching { retriever.release() }
    }
}

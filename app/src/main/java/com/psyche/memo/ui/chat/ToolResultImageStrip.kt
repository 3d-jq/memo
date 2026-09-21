package com.psyche.memo.ui.chat

import com.psyche.memo.ui.theme.MemoRadius
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.composables.icons.lucide.ImageOff
import com.composables.icons.lucide.Lucide

/**
 * 工具结果图片条 — chat_message_widget.dart `_ToolCallItem` / `_ChainOfThoughtToolStep`
 * 里 `parseToolResultImages` 出来的图片横滚列表（5905-5928 / 5489-5515）。
 * 单图：固定高度 + 按内禀宽高比定宽（上限 maxWidth），contain 裁切，r8 圆角，
 * 点击用 [onOpenViewer] 打开全屏查看器。
 */

/** chat_message_widget.dart _toolImageProvider (260-270)：http(s)/data:/本地路径。 */
private fun toolImageModel(path: String): Any? {
    val p = path.trim()
    if (p.startsWith("http://") || p.startsWith("https://")) return p
    if (p.startsWith("data:")) return decodeDataUriBytes(p)
    return p
}

/** chat_message_widget.dart _decodeDataUriBytes (161-161)：只认 base64, 后缀。 */
private fun decodeDataUriBytes(dataUri: String): ByteArray? {
    val marker = "base64,"
    val idx = dataUri.indexOf(marker)
    if (idx == -1) return null
    return try {
        Base64.decode(dataUri.substring(idx + marker.length), Base64.DEFAULT)
    } catch (e: Exception) {
        null
    }
}

/** 图片横滚条：8dp 间隔；点击以 [index] 打开查看器。 */
@Composable
internal fun ToolResultImageStrip(
    paths: List<String>,
    height: Dp,
    maxWidth: Dp,
    onOpenViewer: (paths: List<String>, initialIndex: Int) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(paths, key = { index, _ -> index }) { index, path ->
            ToolImageThumb(
                path = path,
                height = height,
                maxWidth = maxWidth,
                onTap = { onOpenViewer(paths, index) },
            )
        }
    }
}

@Composable
private fun ToolImageThumb(
    path: String,
    height: Dp,
    maxWidth: Dp,
    onTap: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val model = remember(path) { toolImageModel(path) }
    var aspect by remember(path) { mutableStateOf(1f) }

    Box(
        modifier = Modifier
            .size(
                width = (height.value * aspect).coerceIn(0f, maxWidth.value).dp,
                height = height,
            )
            .clip(RoundedCornerShape(MemoRadius.SMALL_DP.dp))
            .background(cs.surfaceContainerHighest)
            .clickable(enabled = model != null, onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        if (model == null) {
            // 空路径/无法解码的 data URI → Dart errorWidget。
            Icon(
                Lucide.ImageOff,
                contentDescription = null,
                tint = cs.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp),
            )
        } else {
            SubcomposeAsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onState = { state ->
                    val d = (state as? AsyncImagePainter.State.Success)?.result?.drawable
                    if (d != null && d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
                        aspect = d.intrinsicHeight.toFloat() / d.intrinsicWidth.toFloat()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                when (val state = painter.state) {
                    is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
                    is AsyncImagePainter.State.Error -> Icon(
                        Lucide.ImageOff,
                        contentDescription = null,
                        tint = cs.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(24.dp),
                    )
                    else -> Unit
                }
            }
        }
    }
}

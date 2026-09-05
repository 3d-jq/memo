package com.psyche.memo.ui.chat

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composables.icons.lucide.ImageOff
import com.psyche.memo.data.model.ImagePart
import com.psyche.memo.data.model.MessagePart
import com.psyche.memo.ui.R as UiR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 消息附件图片渲染 + 全屏查看器 — 移植 chat_message_widget.dart
 * _buildAttachmentPreview 的 ImagePart 分支与 image_viewer_page.dart 的
 * 移动端核心（翻页 / 捏合缩放 / 双击重置 / 保存到相册）。
 */

/** 可打开查看的图片 URI 列表（image part 顺序，跳过 unavailable/空 uri）。 */
internal fun viewableImageUris(parts: List<MessagePart>): List<String> =
    parts.filterIsInstance<ImagePart>()
        .filter { it.unavailable != true && it.uri.isNotBlank() }
        .map { it.uri.trim() }

/**
 * 消息时间线里的图片 part 渲染（chat_message_widget.dart
 * _buildAttachmentPreview ImagePart 分支）：112dp cover 缩略图，圆角 10，
 * 按 part 顺序横向 Wrap 排布；unavailable / 空 uri 显示 ImageOff 占位。
 * 点击打开全屏查看器（从当前图开始，可在全部图片间翻页）。
 */
@Composable
fun MessageImageAttachments(
    parts: List<MessagePart>,
    onOpenViewer: (uris: List<String>, initialIndex: Int) -> Unit,
) {
    val entries = parts.filterIsInstance<ImagePart>()
    if (entries.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val viewable = viewableImageUris(parts)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (part in entries) {
            val uri = part.uri.trim()
            val resolved = uri.ifBlank { "" }
            val viewIndex = viewable.indexOf(resolved)
            val unavailable = part.unavailable == true || resolved.isEmpty()
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(cs.onSurface.copy(alpha = 0.07f))
                    .clickable(enabled = !unavailable && viewIndex >= 0) {
                        onOpenViewer(viewable, viewIndex)
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (unavailable) {
                    Icon(
                        Lucide.ImageOff,
                        contentDescription = androidx.compose.ui.res.stringResource(
                            UiR.string.chat_message_widget_attachment_unavailable,
                        ),
                        tint = cs.onSurface.copy(alpha = 0.45f),
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    AsyncImage(
                        model = resolved,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * 全屏图片查看器（image_viewer_page.dart 移动端核心子集）：HorizontalPager
 * 翻页 + 捏合缩放/拖拽 + 双击重置 + 顶部计数器 + 保存到相册。旋转/翻转/
 * 分享/复制不在本批（原版 2261 行完整行为集）。
 */
@Composable
fun ImageViewerOverlay(
    images: List<String>,
    initialIndex: Int,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val pagerState = rememberPagerState(
            initialPage = initialIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0)),
            pageCount = { images.size },
        )
        var scale by remember { mutableStateOf(1f) }
        var offsetXPx by remember { mutableStateOf(0f) }
        var offsetYPx by remember { mutableStateOf(0f) }
        var saving by remember { mutableStateOf(false) }

        fun resetTransform() {
            scale = 1f
            offsetXPx = 0f
            offsetYPx = 0f
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { images[it] },
            ) { page ->
                AsyncImage(
                    model = images[page],
                    contentDescription = androidx.compose.ui.res.stringResource(
                        UiR.string.image_viewer_page_image_label,
                        page + 1,
                        images.size,
                    ),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(page) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 8f)
                                if (scale > 1f) {
                                    offsetXPx += pan.x
                                    offsetYPx += pan.y
                                } else {
                                    offsetXPx = 0f
                                    offsetYPx = 0f
                                }
                            }
                        }
                        .pointerInput(page) {
                            detectTapGestures(
                                onDoubleTap = { resetTransform() },
                            )
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetXPx
                            translationY = offsetYPx
                        },
                )
            }
            // 顶部：关闭 + 计数器（image_viewer_page.dart 顶部栏子集）。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 24.dp),
            ) {
                ViewerCircleButton(Lucide.X, UiR.string.image_viewer_page_close_button, onClose)
                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                Text(
                    text = androidx.compose.ui.res.stringResource(
                        UiR.string.image_viewer_page_counter,
                        pagerState.currentPage + 1,
                        images.size,
                    ),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Box(Modifier.weight(1f))
                ViewerCircleButton(
                    Lucide.Download,
                    UiR.string.image_viewer_page_save_button,
                    enabled = !saving,
                    onClick = {
                        if (!saving) {
                            saving = true
                            val url = images.getOrNull(pagerState.currentPage).orEmpty()
                            scope.launch {
                                val message = withContext(Dispatchers.IO) {
                                    saveImageToGallery(context, url)
                                }
                                saving = false
                                com.psyche.memo.ui.snackbar.SnackbarManager.show(
                                    com.psyche.memo.ui.snackbar.AppNotification(
                                        message = message,
                                        type = com.psyche.memo.ui.snackbar.NotificationType.INFO,
                                    ),
                                )
                            }
                        }
                    },
                )
            }
            // 底部页码点（多图时）。
            if (images.size > 1) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp),
                ) {
                    for (i in images.indices) {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .size(if (i == pagerState.currentPage) 8.dp else 6.dp)
                                .background(
                                    if (i == pagerState.currentPage) Color.White else Color.White.copy(alpha = 0.4f),
                                    CircleShape,
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerCircleButton(
    icon: ImageVector,
    labelRes: Int,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(Color.White.copy(alpha = 0.12f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = androidx.compose.ui.res.stringResource(labelRes),
            tint = Color.White.copy(alpha = alpha),
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * 保存到系统相册（image_viewer_page.dart _saveCurrent 的 Android 等价物）。
 * 支持 http(s) 与本地 file/data URI。返回用户可见的结果消息。
 */
internal fun saveImageToGallery(context: android.content.Context, url: String): String {
    if (url.isEmpty()) {
        return context.getString(UiR.string.image_viewer_page_image_load_failed)
    }
    return try {
        val bytes = readImageBytes(url)
            ?: return context.getString(UiR.string.image_viewer_page_image_load_failed)
        val name = "memo-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Memo")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return context.getString(UiR.string.image_viewer_page_save_failed, "insert failed")
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: return context.getString(UiR.string.image_viewer_page_save_failed, "stream failed")
        context.getString(UiR.string.image_viewer_page_save_success)
    } catch (e: Exception) {
        context.getString(UiR.string.image_viewer_page_save_failed, e.message ?: "error")
    }
}

private fun readImageBytes(url: String): ByteArray? = when {
    url.startsWith("http://") || url.startsWith("https://") ->
        java.net.URL(url).openStream().use { it.readBytes() }
    url.startsWith("data:") -> {
        val base64 = url.substringAfter("base64,", "")
        if (base64.isEmpty()) null
        else android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
    }
    url.startsWith("file://") ->
        java.io.File(url.removePrefix("file://")).takeIf { it.exists() }?.readBytes()
    else ->
        java.io.File(url).takeIf { it.exists() }?.readBytes()
}

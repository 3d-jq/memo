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
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.LaunchedEffect
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
import com.composables.icons.lucide.FlipHorizontal2
import com.composables.icons.lucide.FlipVertical2
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.Share2
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
 * Per-image display state (image_viewer_page.dart `_ImageDisplayTransform`):
 * zoom/pan plus display-only flip/rotation, kept independently per page index.
 */
private data class ImageViewerTransform(
    val scale: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val flipX: Boolean = false,
    val flipY: Boolean = false,
    val quarterTurns: Int = 0,
)

/**
 * 全屏图片查看器（image_viewer_page.dart 移动端核心）：HorizontalPager
 * 翻页 + 捏合缩放/拖拽 + 双击重置 + 顶部计数器 + 底部玻璃功能栏
 * （保存/分享/左右镜像/上下镜像/左旋/右旋）。每张图独立保留变换状态。
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
        var saving by remember { mutableStateOf(false) }
        var sharing by remember { mutableStateOf(false) }
        // _displayTransforms/_zoomCtrls — one display transform per image index
        // (image_viewer_page.dart L364): zoom/pan/flip/rotation survive paging.
        var transforms by remember {
            mutableStateOf(List(images.size.coerceAtLeast(1)) { ImageViewerTransform() })
        }
        val currentIndex = pagerState.currentPage.coerceIn(0, transforms.lastIndex)
        val current = transforms[currentIndex]

        fun updateCurrent(block: (ImageViewerTransform) -> ImageViewerTransform) {
            transforms = transforms.toMutableList().also { it[currentIndex] = block(it[currentIndex]) }
        }

        fun showSnack(message: String) {
            com.psyche.memo.ui.snackbar.SnackbarManager.show(
                com.psyche.memo.ui.snackbar.AppNotification(
                    message = message,
                    type = com.psyche.memo.ui.snackbar.NotificationType.INFO,
                ),
            )
        }

        fun saveCurrent() {
            if (saving) return
            saving = true
            val url = images.getOrNull(pagerState.currentPage).orEmpty()
            scope.launch {
                val message = withContext(Dispatchers.IO) { saveImageToGallery(context, url) }
                saving = false
                showSnack(message)
            }
        }

        // _shareCurrent: resolve the current image to a shareable local file,
        // then hand it to the system share sheet.
        fun shareCurrent() {
            if (sharing) return
            sharing = true
            val url = images.getOrNull(pagerState.currentPage).orEmpty()
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    resolveShareableImage(context, url)
                }
                sharing = false
                if (result == null) {
                    showSnack(context.getString(UiR.string.image_viewer_page_image_load_failed))
                    return@launch
                }
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "image/*"
                    putExtra(android.content.Intent.EXTRA_STREAM, result)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    context.startActivity(android.content.Intent.createChooser(send, null))
                }.onFailure { showSnack(it.message ?: "share failed") }
            }
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
                val t = transforms[page.coerceIn(0, transforms.lastIndex)]
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
                                val newZoom = (t.scale * zoom).coerceIn(1f, 8f)
                                if (newZoom > 1f) {
                                    transforms = transforms.toMutableList().also { list ->
                                        list[page.coerceIn(0, list.lastIndex)] = list[page.coerceIn(0, list.lastIndex)]
                                            .let { it.copy(scale = newZoom, panX = it.panX + pan.x, panY = it.panY + pan.y) }
                                    }
                                } else {
                                    transforms = transforms.toMutableList().also { list ->
                                        list[page.coerceIn(0, list.lastIndex)] = ImageViewerTransform(
                                            flipX = t.flipX,
                                            flipY = t.flipY,
                                            quarterTurns = t.quarterTurns,
                                        )
                                    }
                                }
                            }
                        }
                        .pointerInput(page) {
                            detectTapGestures(
                                onDoubleTap = {
                                    transforms = transforms.toMutableList().also { list ->
                                        list[page.coerceIn(0, list.lastIndex)] = ImageViewerTransform(
                                            flipX = t.flipX,
                                            flipY = t.flipY,
                                            quarterTurns = t.quarterTurns,
                                        )
                                    }
                                },
                            )
                        }
                        .graphicsLayer {
                            scaleX = t.scale * (if (t.flipX) -1f else 1f)
                            scaleY = t.scale * (if (t.flipY) -1f else 1f)
                            translationX = t.panX
                            translationY = t.panY
                            rotationZ = t.quarterTurns * 90f
                        },
                )
            }
            // 顶部：关闭 + 计数器（image_viewer_page.dart 顶部栏子集）。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 34.dp),
            ) {
                ViewerCircleButton(Lucide.X, UiR.string.image_viewer_page_close_button, onClose)
                Box(Modifier.weight(1f))
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
                    modifier = Modifier
                        .clip(RoundedCornerShape(50.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .border(0.7.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(50.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            // 底部功能栏（image_viewer_page.dart _buildActionChrome）：玻璃面板内
            // 保存 · 分享 | 左右镜像 · 上下镜像 · 左旋 · 右旋。
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(Color.Black.copy(alpha = 0.26f))
                    .border(0.7.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(30.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                GlassViewerButton(
                    icon = Lucide.Download,
                    labelRes = UiR.string.image_viewer_page_save_button,
                    loading = saving,
                    onClick = ::saveCurrent,
                )
                GlassViewerButton(
                    icon = Lucide.Share2,
                    labelRes = UiR.string.image_viewer_page_share_button,
                    loading = sharing,
                    onClick = ::shareCurrent,
                )
                GlassViewerDivider()
                GlassViewerButton(
                    icon = Lucide.FlipHorizontal2,
                    labelRes = UiR.string.image_viewer_page_flip_horizontal_button,
                    active = current.flipX,
                    onClick = { updateCurrent { it.copy(flipX = !it.flipX) } },
                )
                GlassViewerButton(
                    icon = Lucide.FlipVertical2,
                    labelRes = UiR.string.image_viewer_page_flip_vertical_button,
                    active = current.flipY,
                    onClick = { updateCurrent { it.copy(flipY = !it.flipY) } },
                )
                GlassViewerButton(
                    icon = Lucide.RotateCcw,
                    labelRes = UiR.string.image_viewer_page_rotate_left_button,
                    onClick = {
                        updateCurrent { it.copy(quarterTurns = (it.quarterTurns - 1).mod(4)) }
                    },
                )
                GlassViewerButton(
                    icon = Lucide.RotateCw,
                    labelRes = UiR.string.image_viewer_page_rotate_right_button,
                    onClick = {
                        updateCurrent { it.copy(quarterTurns = (it.quarterTurns + 1).mod(4)) }
                    },
                )
            }
        }
    }
}

/**
 * Resolve the current image to a shareable local path: local file paths are
 * used as-is, remote/data images are materialized into the cache dir.
 * Returns null when the input is unusable.
 */
internal fun materializeShareablePath(context: android.content.Context, url: String): String? {
    if (url.isEmpty()) return null
    return when {
        url.startsWith("file://") -> {
            java.io.File(url.removePrefix("file://")).takeIf { it.exists() }?.absolutePath
        }
        url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:") -> {
            val bytes = readImageBytes(url) ?: return null
            val ext = when {
                url.startsWith("data:image/png") || url.endsWith(".png") -> "png"
                url.endsWith(".webp") -> "webp"
                else -> "jpg"
            }
            val out = java.io.File(context.cacheDir, "share/memo-${System.currentTimeMillis()}.$ext")
            out.parentFile?.mkdirs()
            out.writeBytes(bytes)
            out.absolutePath
        }
        else -> java.io.File(url).takeIf { it.exists() }?.absolutePath
    }
}

/** Wrap a shareable path in a FileProvider content uri (file_paths.xml). */
internal fun resolveShareableImage(context: android.content.Context, url: String): android.net.Uri? {
    val file = materializeShareablePath(context, url)?.let { java.io.File(it) } ?: return null
    return androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
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

/** _GlassCircleButton: 48dp frosted circle, white-on-dark, loading state. */
@Composable
private fun GlassViewerButton(
    icon: ImageVector,
    labelRes: Int,
    loading: Boolean = false,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val fill = Color.White.copy(alpha = if (active) 0.26f else 0.16f)
    val border = Color.White.copy(alpha = 0.30f)
    val contentAlpha = if (loading) 0.52f else 0.92f
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(fill, CircleShape)
            .border(0.7.dp, border, CircleShape)
            .clickable(enabled = !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(19.dp),
                strokeWidth = 2.1.dp,
                color = Color.White.copy(alpha = contentAlpha),
            )
        } else {
            Icon(
                icon,
                contentDescription = androidx.compose.ui.res.stringResource(labelRes),
                tint = Color.White.copy(alpha = contentAlpha),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** _GlassDivider: 1x24 vertical white hairline between action groups. */
@Composable
private fun GlassViewerDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(24.dp)
            .background(Color.White.copy(alpha = 0.16f)),
    )
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

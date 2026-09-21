package com.psyche.memo.ui.chat

import com.psyche.memo.ui.theme.MemoRadius
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import com.psyche.memo.data.model.FilePart
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
 * 按 part 顺序 Wrap 排布（原版 `Wrap(spacing: 8, runSpacing: 8)`）；unavailable /
 * 空 uri 显示 ImageOff 占位。点击打开全屏查看器（从当前图开始，可在全部图片间翻页）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageImageAttachments(
    parts: List<MessagePart>,
    onOpenViewer: (uris: List<String>, initialIndex: Int) -> Unit,
) {
    val entries = parts.filterIsInstance<ImagePart>()
    if (entries.isEmpty()) return
    val viewable = viewableImageUris(parts)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (part in entries) {
            if (isChartAttachment(part)) {
                ChartAttachmentCard(part = part, viewable = viewable, onOpenViewer = onOpenViewer)
            } else {
                ImageAttachmentTile(part = part, viewable = viewable, onOpenViewer = onOpenViewer)
            }
        }
    }
}

/**
 * 附件预览（chat_message_widget.dart `_buildAttachmentPreview`）：**按 part 顺序**把
 * 图片（112dp 图块）与文件（[MessageDocCard]）排进一个 `Wrap(spacing 8, runSpacing 8)`，
 * 用户侧右对齐、助手侧左对齐。
 *
 * 关键结构：附件是正文气泡的**兄弟**、排在气泡**上方**（用户 CMW:1835-1843、
 * 助手 CMW:2848-2851），不要把文档卡塞进气泡里——塞进去会变成「气泡里一块近不透明的
 * cover 底」，用户实测报「文档发在对话界面渲染有问题」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageAttachmentPreview(
    parts: List<MessagePart>,
    alignEnd: Boolean,
    onOpenViewer: (uris: List<String>, initialIndex: Int) -> Unit,
) {
    val entries = parts.filter { it is ImagePart || it is FilePart }
    if (entries.isEmpty()) return
    val viewable = viewableImageUris(parts)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (alignEnd) {
            Arrangement.spacedBy(8.dp, Alignment.End)
        } else {
            Arrangement.spacedBy(8.dp, Alignment.Start)
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (part in entries) {
            when (part) {
                is ImagePart -> if (isChartAttachment(part)) {
                    ChartAttachmentCard(part = part, viewable = viewable, onOpenViewer = onOpenViewer)
                } else {
                    ImageAttachmentTile(part = part, viewable = viewable, onOpenViewer = onOpenViewer)
                }
                is FilePart -> if (part.mime?.startsWith("video/") == true) {
                    MessageVideoCard(part)
                } else {
                    MessageDocCard(part)
                }
                else -> Unit
            }
        }
    }
}

/** 单张图片附件块（112dp、r10、cover；不可用 → ImageOff 占位）。 */
@Composable
internal fun ImageAttachmentTile(
    part: ImagePart,
    viewable: List<String>,
    onOpenViewer: (uris: List<String>, initialIndex: Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val uri = part.uri.trim()
    val viewIndex = viewable.indexOf(uri)
    val unavailable = part.unavailable == true || uri.isEmpty()
    Box(
        modifier = Modifier
            .size(112.dp)
            .clip(RoundedCornerShape(MemoRadius.SMALL_DP.dp))
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
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 图表产物（`render_chart` 的 SVG）—— 聊天里用**等比满宽卡片**显示。
 *
 * 为什么不复用 [ImageAttachmentTile]：那个是 112dp + `ContentScale.Crop` 的缩略块
 *（原版 `_buildAttachmentPreview` 口径，给照片用的），图表被裁掉坐标轴/图例就没法看了。
 */
internal fun isChartAttachment(part: MessagePart): Boolean =
    part is ImagePart &&
        part.mime?.trim()?.lowercase() == com.psyche.memo.provider.chart.ChartSvgRenderer.MIME

@Composable
internal fun ChartAttachmentCard(
    part: ImagePart,
    viewable: List<String>,
    onOpenViewer: (uris: List<String>, initialIndex: Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val uri = part.uri.trim()
    val viewIndex = viewable.indexOf(uri)
    val unavailable = part.unavailable == true || uri.isEmpty()
    // 比例取自 SVG 自己（结构化图表恒为 900×560；自由绘制的流程图可能又高又窄，
    // 用固定比例会被压扁）。
    val aspect = remember(uri) {
        com.psyche.memo.provider.chart.SvgAspect.ofFile(uri)
            ?: com.psyche.memo.provider.chart.ChartSvgRenderer.ASPECT_RATIO
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .background(cs.onSurface.copy(alpha = 0.04f))
            .border(0.6.dp, cs.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
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
            // SVG 自带 900×560 画布，等比铺满 → 坐标轴与图例都看得清。
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
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
                    .clip(RoundedCornerShape(MemoRadius.PILL_DP.dp))
                    .background(Color.Black.copy(alpha = 0.26f))
                    .border(0.7.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(MemoRadius.PILL_DP.dp))
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
            // 项目自己的顶部 toast（`AppSnackBarOverlay` + `ToastItem`）只在 MainActivity
            // 根部挂了一份，而本查看器是**独立 Dialog 窗口** —— 它会被盖住看不见，
            // 所以这里在 Dialog 内再挂一份：状态还是同一个 `SnackbarManager`，
            // 「已保存到相册」的观感与全站一致（用户 2026-09-18「改成我们项目里面那个 toast」）。
            com.psyche.memo.ui.snackbar.AppSnackBarOverlay { }
        }
    }
}

/**
 * Resolve the current image to a shareable local path: local file paths are
 * used as-is, remote/data images are materialized into the cache dir.
 * Returns null when the input is unusable.
 */
/** 这个地址/路径是不是 SVG（自研可视化工具 `render_visual` 的产物）。 */
internal fun isSvgUrl(url: String): Boolean {
    val clean = url.substringBefore('?').substringBefore('#').lowercase()
    return clean.endsWith(".svg") || clean.startsWith("data:image/svg")
}

/**
 * SVG 字节 → PNG 字节。
 *
 * 为什么必须转：「保存到相册」和「分享」走的都是**位图**通道 —— MediaStore 按
 * `image/png` 写、微信等应用也只认常见位图。直接把 SVG 的文本字节当 PNG 存进去，
 * 相册里就是一张坏图（用户 2026-09-18「生成的这个 svg 点击下载不了」）。
 * 用 AndroidSVG 渲染成位图再编码，原有保存/分享路径一行都不用改。
 */
/**
 * 栅格化的目标像素尺寸：最长边放大到 [maxDimension]（清晰又不过大），缩放夹在 1~3 倍。
 */
internal fun svgPngSize(
    width: Float,
    height: Float,
    maxDimension: Int = 2048,
): Pair<Int, Int> {
    val w = width.takeIf { it > 0f } ?: 900f
    val h = height.takeIf { it > 0f } ?: 560f
    val scale = (maxDimension.toFloat() / maxOf(w, h)).coerceIn(1f, 3f)
    return (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
}

internal fun svgToPng(svgBytes: ByteArray, maxDimension: Int = 2048): ByteArray? = runCatching {
    val svg = com.caverock.androidsvg.SVG.getFromString(String(svgBytes, Charsets.UTF_8))
    val (pixelWidth, pixelHeight) = svgPngSize(
        width = svg.documentWidth,
        height = svg.documentHeight,
        maxDimension = maxDimension,
    )
    val picture = svg.renderToPicture(pixelWidth, pixelHeight)
    val bitmap = android.graphics.Bitmap.createBitmap(
        pixelWidth,
        pixelHeight,
        android.graphics.Bitmap.Config.ARGB_8888,
    )
    android.graphics.drawable.PictureDrawable(picture)
        .apply { setBounds(0, 0, pixelWidth, pixelHeight) }
        .draw(android.graphics.Canvas(bitmap))
    java.io.ByteArrayOutputStream().use { out ->
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
}.getOrNull()

internal fun materializeShareablePath(context: android.content.Context, url: String): String? {
    if (url.isEmpty()) return null
    // SVG（图表 / 自由绘制）先栅格化成 PNG —— 多数应用不认 SVG。
    val localFile = when {
        url.startsWith("file://") -> java.io.File(url.removePrefix("file://"))
        url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:") -> null
        else -> java.io.File(url)
    }
    if (isSvgUrl(url) || localFile?.name?.lowercase()?.endsWith(".svg") == true) {
        val raw = localFile?.takeIf { it.exists() }?.readBytes()
            ?: readImageBytes(url)
            ?: return null
        val png = svgToPng(raw) ?: return null
        val out = java.io.File(context.cacheDir, "share/memo-${System.currentTimeMillis()}.png")
        out.parentFile?.mkdirs()
        out.writeBytes(png)
        return out.absolutePath
    }
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
        val raw = readImageBytes(url)
            ?: return context.getString(UiR.string.image_viewer_page_image_load_failed)
        // SVG 先栅格化成 PNG，否则写进相册的是一段文本字节（坏图）。
        val bytes = if (isSvgUrl(url)) {
            svgToPng(raw) ?: return context.getString(UiR.string.image_viewer_page_image_load_failed)
        } else {
            raw
        }
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

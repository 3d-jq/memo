package com.psyche.memo.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.psyche.memo.ui.R
import com.psyche.memo.ui.markdown.MarkdownTableActions
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.graphics.Bitmap

/**
 * Wires the platform half of the markdown table toolbar,
 * `_MarkdownTableToolbar` (markdown_with_highlight.dart L3843-3930).
 *
 * `core:ui` owns the visuals and the serialisation but cannot reach the
 * clipboard, MediaStore or SAF — so the app module supplies the four actions
 * here. Any action left null makes its button disappear.
 *
 * The original's tap/long-press split is preserved:
 *  - copy:  tap -> markdown, long-press -> image
 *  - image: tap -> save to gallery (`_saveImageToGallery`)
 */
@Composable
fun rememberMarkdownTableActions(): MarkdownTableActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The captured GraphicsLayer is translucent (the table card relies on the
    // page's surface showing through), so it must be flattened onto an opaque
    // base before being written to a file — otherwise every transparent pixel
    // is stored as black. The original hit the same issue (see the
    // `_capturingTableImage` note at markdown_with_highlight.dart L3269:
    // "Capture must be opaque").
    val surface = MaterialTheme.colorScheme.surface

    // `_exportCsv` (L3542) opens a save dialog; on Android that is
    // ActivityResultContracts.CreateDocument, same as BackupScreen.
    var pendingCsv by remember { mutableStateOf<String?>(null) }

    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val csv = pendingCsv
        pendingCsv = null
        if (uri == null || csv == null) return@rememberLauncherForActivityResult
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(csv.toByteArray(Charsets.UTF_8))
                    } ?: error("stream failed")
                }
            }
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: ""
            if (written.isSuccess) {
                toast(exportedAs(context).format(name), NotificationType.SUCCESS)
            } else {
                toast(
                    exportFailed(context).format(written.exceptionOrNull()?.message ?: name),
                    NotificationType.ERROR,
                )
            }
        }
    }

    return remember(context, csvLauncher, surface) {
        val copiedMarkdown = context.getString(R.string.markdown_table_copied_markdown_snackbar)
        val copiedImage = context.getString(R.string.markdown_table_copied_csv_snackbar)
        val saveSuccess = context.getString(R.string.image_viewer_page_save_success)
        val saveFailed = context.getString(R.string.image_viewer_page_save_failed)

        MarkdownTableActions(
            onCopyMarkdown = { markdown ->
                copyPlainText(context, markdown)
                toast(copiedMarkdown, NotificationType.SUCCESS)
            },
            onCopyImage = { bitmap ->
                scope.launch {
                    val error = withContext(Dispatchers.IO) {
                        saveBitmapToGallery(context, bitmap, surface)
                    }
                    toast(
                        error?.let { saveFailed.format(it) } ?: copiedImage,
                        if (error == null) NotificationType.SUCCESS else NotificationType.ERROR,
                    )
                }
            },
            onExportCsv = { csv ->
                pendingCsv = csv
                val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss-SSS", Locale.US).format(Date())
                val stem = context.getString(R.string.markdown_table_default_file_name_stem)
                csvLauncher.launch("${stem}_$timestamp.csv")
            },
            onSaveImage = { bitmap ->
                scope.launch {
                    val error = withContext(Dispatchers.IO) {
                        saveBitmapToGallery(context, bitmap, surface)
                    }
                    toast(
                        error?.let { saveFailed.format(it) } ?: saveSuccess,
                        if (error == null) NotificationType.SUCCESS else NotificationType.ERROR,
                    )
                }
            },
        )
    }
}

// 代码块「另存为」（CodeBlockSave.kt）复用同一对导出文案与提示通道。
internal fun exportedAs(context: Context): String =
    context.getString(R.string.message_export_sheet_exported_as)

internal fun exportFailed(context: Context): String =
    context.getString(R.string.message_export_sheet_export_failed)

internal fun toast(message: String, type: NotificationType) {
    SnackbarManager.show(AppNotification(message, type))
}

private fun copyPlainText(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("memo", text))
}

private fun timestamp(): String =
    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

/** Writes the PNG into `Pictures/Memo` (API 29+); null on success, else why. */
private fun saveBitmapToGallery(
    context: Context,
    bitmap: ImageBitmap,
    background: Color,
): String? {
    return try {
        val png = flattenOntoOpaque(bitmap, background)
        val name = "memo-table-${timestamp()}.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Memo")
            }
            val resolver = context.contentResolver
            val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return "insert returned null"
            val out = resolver.openOutputStream(uri)
                ?: return "openOutputStream returned null"
            out.use { stream -> png.compress(Bitmap.CompressFormat.PNG, 100, stream) }
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "Memo",
            ).apply { mkdirs() }
            FileOutputStream(File(dir, name)).use { out ->
                png.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
        null
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }
}

/**
 * Flattens the captured layer onto an opaque [background].
 *
 * Two things have to happen here:
 *  1. `GraphicsLayer.toImageBitmap()` hands back a **hardware** bitmap, which a
 *     software `Canvas` refuses to draw ("Software rendering doesn't support
 *     hardware bitmaps"). It is copied into an ARGB_8888 bitmap first.
 *  2. The captured layer is translucent — the table card relies on the page's
 *     `surface` showing through — so writing it out directly stores every
 *     transparent pixel as black, which the original also hit (see the
 *     `_capturingTableImage` note at markdown_with_highlight.dart L3269:
 *     "Capture must be opaque").
 */
private fun flattenOntoOpaque(bitmap: ImageBitmap, background: Color): Bitmap {
    val hardware = bitmap.asAndroidBitmap()
    val width = hardware.width
    val height = hardware.height
    if (width <= 0 || height <= 0) return hardware

    // Step 1: move off the hardware buffer so software drawing is allowed.
    val source = if (hardware.config == Bitmap.Config.HARDWARE) {
        hardware.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        hardware
    }

    // Step 2: composite onto an opaque base.
    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(output)
    canvas.drawColor(background.toArgb())
    canvas.drawBitmap(source, 0f, 0f, null)
    return output
}

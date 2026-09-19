package com.psyche.memo.ui.chat

import com.psyche.memo.ui.theme.MemoRadius
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Lucide
import com.psyche.memo.data.model.FilePart
import java.io.File

/**
 * 消息里的文件附件卡 —— chat_message_widget.dart:2244-2360 的 `FilePart` 分支
 * （`_buildAttachmentPreview`）：r10 卡、深色 onSurface@0.08 / 浅色 surface@0.92、
 * 描边 outlineVariant@0.18、内边距 h10 v8，行内 16dp 文件图标@0.72 + 6 + 文件名
 * （13sp@0.86、最长 180dp 省略）。
 *
 * 点击行为与原版一致：http(s) 走外链、本地文件用 FileProvider 交给系统打开，
 * 缺失/不支持时不动（原版弹错误 toast，这里没有 toast 通道，静默返回）。
 */
@Composable
fun MessageDocCard(
    part: FilePart,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val context = androidx.compose.ui.platform.LocalContext.current
    val path = part.uri.trim()
    val missing = part.unavailable == true || (!path.startsWith("http") && !File(path).exists())
    Row(
        modifier = modifier
            .widthIn(max = 220.dp)
            .background(
                color = if (isDark) cs.onSurface.copy(alpha = 0.08f) else cs.surface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(MemoRadius.SMALL_DP.dp),
            )
            .border(1.dp, cs.outlineVariant.copy(alpha = 0.18f), RoundedCornerShape(MemoRadius.SMALL_DP.dp))
            .clickable(enabled = !missing) { openDoc(context, path, part.mime) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            Lucide.FileText,
            contentDescription = null,
            tint = cs.onSurface.copy(alpha = 0.72f),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = part.name.ifEmpty { path.substringAfterLast('/') },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.86f)),
            modifier = Modifier.widthIn(max = 180.dp),
        )
    }
}

/**
 * 打开本地文件（FileProvider content uri）或 http(s) 链接。
 *
 * 存储页的文件行也用这个（原版 `_openFile` → OpenFilex 同一语义）。
 */
internal fun openDocument(context: Context, path: String, mime: String?) = openDoc(context, path, mime)

/** 打开本地文件（FileProvider content uri）或 http(s) 链接。 */
private fun openDoc(context: Context, path: String, mime: String?) {
    if (path.isEmpty()) return
    if (path.startsWith("http://") || path.startsWith("https://")) {
        openExternal(context, path)
        return
    }
    val file = File(path)
    if (!file.exists()) return
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime ?: "application/octet-stream")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { if (it !is ActivityNotFoundException) throw it }
}

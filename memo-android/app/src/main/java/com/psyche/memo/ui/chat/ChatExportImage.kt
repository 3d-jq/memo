package com.psyche.memo.ui.chat

import com.psyche.memo.ui.theme.MemoRadius
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psyche.memo.ui.chat.MessageExport.ExportMessage
import com.psyche.memo.ui.markdown.MarkdownText
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.ProvideSemanticColors

/**
 * 「导出为图片」的渲染引擎（UI-7i）—— 对应上游 `message_export_sheet.dart`
 * 的 widget 截图管线（ExportCaptureScope + RepaintBoundary.toImage +
 * PNG 切片拼接）。
 *
 * **Compose 等价实现**：没有 Flutter 那种「离屏再入树的 RepaintBoundary」，
 * 所以把导出文档放进一个不可见 Dialog（alpha 0 + 不可触碰/聚焦）里的
 * ComposeView，组合完成两帧后手动 draw 进 ARGB_8888 位图再关掉。内容用
 * 与上游一致的形状：标题 + 日期 + 每条消息（角色名 + 时间 + 气泡正文，
 * Markdown 渲染）。
 *
 * 全程主线程；调用方拿到位图后自行编码 PNG（IO）并分享。
 *
 * 有意偏差（PORTING §5.25）：上游的切片拼接/空白裁剪是为了绕 Flutter 长图
 * 纹理上限；Compose 直接一次 draw 整棵树（离屏 Canvas 无 GPU 纹理上限），
 * 不需要切片，也没有透明 padding 可裁。
 */
object ChatExportImage {

    /** 固定导出宽度（上游按视口宽度；我们钳到 1080px 内）。 */
    fun exportWidthPx(context: android.content.Context): Int =
        minOf(
            context.resources.displayMetrics.widthPixels,
            (720 * context.resources.displayMetrics.density).toInt(),
        ).coerceAtLeast(360)

    /**
     * 渲染选中消息为一张长图。[onDone] 在主线程回调；宽度不足或组合失败的
     * 情况回调 null（调用方报导出失败）。
     */
    fun render(
        context: android.content.Context,
        title: String,
        dateLine: String,
        messages: List<ExportMessage>,
        roleNameOf: (ExportMessage) -> String,
        timeOf: (Long) -> String,
        scheme: androidx.compose.material3.ColorScheme,
        onDone: (Bitmap?) -> Unit,
    ) {
        val widthPx = exportWidthPx(context)
        val dialog = Dialog(context)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            )
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            )
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            )
            window.setDimAmount(0f)
            window.decorView.alpha = 0f
        }
        val composeView = ComposeView(context)
        dialog.setContentView(
            composeView,
            android.view.ViewGroup.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val dark = scheme.surface.luminance() < 0.5f
        composeView.setContent {
            ProvideSemanticColors(scheme = scheme, dark = dark) {
                MaterialTheme(colorScheme = scheme) {
                    ExportDocument(title, dateLine, messages, roleNameOf, timeOf)
                }
            }
        }
        dialog.show()
        // 组合 + 布局需要一到两帧；两帧后绘制并关闭。
        val decor = dialog.window?.decorView ?: return onDone(null)
        fun captureIfReady() {
            if (composeView.width <= 0 || composeView.height <= 0) {
                decor.post { captureIfReady() }
                return
            }
            val bitmap = try {
                Bitmap.createBitmap(composeView.width, composeView.height, Bitmap.Config.ARGB_8888)
                    .also { composeView.draw(Canvas(it)) }
            } catch (_: Exception) {
                null
            }
            dialog.dismiss()
            onDone(bitmap)
        }
        decor.post { decor.post { captureIfReady() } }
    }

    /** 导出文档本体：标题 + 日期 + 每条消息的角色名 / 时间 / 气泡正文。 */
    @Composable
    private fun ExportDocument(
        title: String,
        dateLine: String,
        messages: List<ExportMessage>,
        roleNameOf: (ExportMessage) -> String,
        timeOf: (Long) -> String,
    ) {
        val cs = MaterialTheme.colorScheme
        val semantic = LocalSemanticColors.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(semantic.surfaceCard)
                .padding(vertical = 20.dp),
        ) {
            Text(
                text = title,
                style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = dateLine,
                style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(14.dp))
            for (message in messages) {
                MessageBlock(message, roleNameOf(message), timeOf(message.timestamp))
                Spacer(Modifier.height(10.dp))
            }
        }
    }

    @Composable
    private fun MessageBlock(
        message: ExportMessage,
        roleName: String,
        timeLine: String,
    ) {
        val cs = MaterialTheme.colorScheme
        val isUser = message.role == "user"
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = roleName,
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurface.copy(alpha = 0.6f)),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = timeLine,
                    style = TextStyle(fontSize = 11.sp, color = cs.onSurface.copy(alpha = 0.4f)),
                )
            }
            Spacer(Modifier.height(4.dp))
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(
                        if (isUser) cs.primary.copy(alpha = 0.14f) else cs.onSurface.copy(alpha = 0.05f),
                        RoundedCornerShape(MemoRadius.INNER_DP.dp),
                    )
                    .padding(10.dp),
            ) {
                val text = message.parts.filterIsInstance<com.psyche.memo.data.model.TextPart>()
                    .joinToString("\n") { it.text }
                if (text.isNotEmpty()) MarkdownText(markdown = text)
                for (image in message.parts.filterIsInstance<com.psyche.memo.data.model.ImagePart>()) {
                    Spacer(Modifier.height(6.dp))
                    coil.compose.AsyncImage(
                        model = if (image.uri.startsWith("/")) java.io.File(image.uri) else image.uri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

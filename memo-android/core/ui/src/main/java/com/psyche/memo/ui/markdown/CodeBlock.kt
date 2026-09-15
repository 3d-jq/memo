package com.psyche.memo.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.Lucide
import com.psyche.memo.ui.R
import com.psyche.memo.ui.theme.alphaBlend
import com.psyche.memo.ui.theme.LocalSemanticColors
import org.commonmark.node.Code
import org.commonmark.node.Text

data class CodeBlockConfig(
    val autoCollapse: Boolean = false,
    val autoCollapseLines: Int = 2,
    val wrap: Boolean = false,
)

/**
 * 代码块头部右侧的动作（原版 `_CodeBlockIconAction` 三枚：另存为 / 复制 / 预览）。
 * 回调为 null 的钮**不渲染** —— 与表格工具栏同一约定（core:ui 不认识 app 的
 * SAF 与预览页，由调用方注入）。
 */
data class CodeBlockActions(
    /** 「另存为文件」（app 侧走 SAF）。 */
    val onSaveAs: ((code: String) -> Unit)? = null,
    /** HTML 代码块的「预览」（app 侧走 HtmlPreviewScreen）。 */
    val onPreviewHtml: ((code: String) -> Unit)? = null,
)

/**
 * 代码块的字体族。原版由 `settings.codeFontFamily` 决定（默认 `'monospace'`，
 * markdown_with_highlight.dart:299-305），core:ui 不认识偏好存储，所以由宿主
 * （app）读设置后 provide。内联 code 与围栏块共用同一个族。
 */
val LocalMarkdownCodeFont = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }

/** 折叠状态跨重组记忆（原版 `_manualExpansionByCodeKey`，LRU 80 条）。 */
private val codeBlockExpansion = android.util.LruCache<String, Boolean>(80)

/** 原版 `_codeBlockStateKey`：语言 + 代码前 16 字符（空白折叠）。 */
private fun codeBlockStateKey(language: String?, code: String): String {
    val lang = language.orEmpty().trim().lowercase()
    val normalized = code.trimStart().replace(Regex("\\s+"), " ")
    val anchor = if (normalized.length <= 16) normalized else normalized.take(16)
    return "$lang|${normalized.length}|$anchor"
}

/** 原版 `_trimTrailingNewlines`：去掉尾部空行（代码块末尾换行不算一行）。 */
private fun trimTrailingNewlines(raw: String): String = raw.trimEnd('\n', '\r')

/** 原版 `_exceedsLineThreshold`：行数 > 阈值（阈值 <1 一律算超）。 */
internal fun codeExceedsLineThreshold(code: String, threshold: Int): Boolean {
    if (threshold < 1) return true
    val trimmed = trimTrailingNewlines(code)
    if (trimmed.isEmpty()) return false
    var lines = 1
    for (ch in trimmed) {
        if (ch == '\n') {
            lines++
            if (lines > threshold) return true
        }
    }
    return false
}

/** 折叠时只显示前 [visibleLines] 行（原版 `_collapsedHighlightedCode`）。 */
internal fun collapsedCodePreview(code: String, visibleLines: Int): String {
    val trimmed = trimTrailingNewlines(code)
    if (trimmed.isEmpty()) return trimmed
    return trimmed.split('\n').take(visibleLines.coerceAtLeast(1)).joinToString("\n")
}

/** 语言标签：fence 的 info 原样显示，空则「代码」/「Code」。 */
@Composable
private fun codeLanguageLabel(language: String?): String {
    val trimmed = language?.trim().orEmpty()
    if (trimmed.isNotEmpty()) return trimmed
    val isZh = java.util.Locale.getDefault().language == "zh"
    return if (isZh) "代码" else "Code"
}

@Composable
internal fun CodeBlockView(
    code: String?,
    language: String?,
    config: CodeBlockConfig,
    actions: CodeBlockActions,
) {
    val cs = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val full = remember(code) { trimTrailingNewlines(code ?: "") }
    val stateKey = remember(language, full) { codeBlockStateKey(language, full) }
    // 展开态：手动记忆优先，否则按设置自动折叠（原版 _isEffectivelyExpanded）。
    var manual by remember(stateKey) { mutableStateOf(codeBlockExpansion.get(stateKey)) }
    val exceeds = remember(full, config.autoCollapseLines) {
        codeExceedsLineThreshold(full, config.autoCollapseLines)
    }
    val expanded = manual ?: !(config.autoCollapse && exceeds)
    val hiddenTail = !expanded && exceeds

    val copiedMessage = stringResource(R.string.chat_message_widget_copied_to_clipboard)
    val copyLabel = stringResource(R.string.share_provider_sheet_copy_button)
    val saveLabel = stringResource(R.string.code_block_save_as_button)
    val previewLabel = stringResource(R.string.code_block_preview_button)
    val isHtml = language?.trim()?.lowercase() in setOf("html", "htm")

    fun toggle() {
        val next = !expanded
        manual = next
        codeBlockExpansion.put(stateKey, next)
    }

    // 原版：bodyBg = surfaceContainer@80%，headerBg = surfaceContainerHighest@80%，
    // r16 + 1dp outlineVariant 边框，垂直外边距 6。原版 Container 另有两条关键行为：
    // `clipBehavior: Clip.antiAlias`（把 header 的方角裁进 16dp 圆角）与
    // `foregroundDecoration`（边框画在内容**之上**）。缺这两条时头部方角会盖住上两个
    // 圆角、边框在上角也被盖掉，看起来像"顶部两个角有阴影"（用户实测报障）。
    val bodyBg = cs.surfaceContainer.copy(alpha = CODE_BLOCK_FILL_ALPHA)
    val headerBg = cs.surfaceContainerHighest.copy(alpha = CODE_BLOCK_FILL_ALPHA)
    val borderColor = codeBlockBorderColor(cs, LocalSemanticColors.current.isDark)
    val corner = 16.dp
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(corner))
                .background(bodyBg)
                .drawWithContent {
                    drawContent()
                    // 边框画在内容之上（原版 foregroundDecoration）；描边内缩半个线宽，
                    // 免得被上面的 clip 裁掉一半。
                    val stroke = 1.dp.toPx()
                    drawRoundRect(
                        color = borderColor,
                        topLeft = Offset(stroke / 2f, stroke / 2f),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(corner.toPx() - stroke / 2f),
                        style = Stroke(width = stroke),
                    )
                },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBg)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { toggle() }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = codeLanguageLabel(language),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = cs.onSurfaceVariant.copy(alpha = 0.72f),
                            lineHeight = 12.sp,
                        ),
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        if (expanded) Lucide.ChevronDown else Lucide.ChevronRight,
                        contentDescription = stringResource(
                            if (expanded) R.string.code_block_collapse_button else R.string.code_block_expand_button,
                        ),
                        tint = cs.onSurfaceVariant.copy(alpha = 0.72f),
                        modifier = Modifier.size(14.dp),
                    )
                }
                actions.onSaveAs?.let { save ->
                    CodeBlockIconAction(icon = Lucide.Download, label = saveLabel) { save(full) }
                    Spacer(Modifier.width(16.dp))
                }
                CodeBlockIconAction(icon = Lucide.Copy, label = copyLabel) {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("code", full))
                    com.psyche.memo.ui.snackbar.SnackbarManager.show(
                        com.psyche.memo.ui.snackbar.AppNotification(
                            message = copiedMessage,
                            type = com.psyche.memo.ui.snackbar.NotificationType.SUCCESS,
                        ),
                    )
                }
                if (isHtml && actions.onPreviewHtml != null) {
                    Spacer(Modifier.width(16.dp))
                    CodeBlockIconAction(icon = Lucide.Eye, label = previewLabel) {
                        actions.onPreviewHtml.invoke(full)
                    }
                }
            }
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Column {
                    val visible = if (expanded) full else collapsedCodePreview(full, config.autoCollapseLines)
                    val textModifier = if (config.wrap) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier.horizontalScroll(rememberScrollState())
                    }
                    Text(
                        text = visible,
                        fontSize = 13.sp,
                        lineHeight = 19.5.sp,
                        fontFamily = LocalMarkdownCodeFont.current,
                        color = cs.onSurface,
                        softWrap = config.wrap,
                        modifier = textModifier,
                    )
                }
                if (hiddenTail) {
                    // 折叠且还有隐藏行时，底部 24dp 渐隐（原版 _CodeBlockCollapsedTailFade）。
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(24.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, bodyBg),
                                ),
                            ),
                    )
                }
            }
        }
    }
    // 消除未使用变量告警（scope 供后续动效预留）。
    scope.hashCode()
}

/** 原版 `_CodeBlockIconAction`：16dp 图标、`onSurfaceVariant@72%`。 */
@Composable
private fun CodeBlockIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onTap: () -> Unit,
) {
    Icon(
        imageVector = icon,
        contentDescription = label,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        modifier = Modifier
            .size(20.dp)
            .clickable(onClick = onTap),
    )
}

private const val CODE_BLOCK_FILL_ALPHA = 0.80f

/**
 * 原版 `_codeBlockBorderColor`：调色板的 `outlineVariant` 是纯黑/纯白时，改用
 * `onSurfaceVariant` 与 `surface` 的 alphaBlend，避免代码块描边变成刺眼的纯色。
 */
private fun codeBlockBorderColor(cs: androidx.compose.material3.ColorScheme, isDark: Boolean): Color {
    val outlineVariant = cs.outlineVariant
    if (outlineVariant != Color.Black && outlineVariant != Color.White) return outlineVariant
    return alphaBlend(cs.onSurfaceVariant, if (isDark) 0.32 else 0.24, cs.surface)
}
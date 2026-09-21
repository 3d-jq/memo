package com.psyche.memo.ui.chat

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Boxes
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Hammer
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Zap
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalView
import com.psyche.memo.common.Haptics
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ChatViewModel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.max
import com.psyche.memo.ui.BrandAssets
import com.psyche.memo.ui.ModelAvatarGlyph
import com.psyche.memo.ui.ChatStyleSpec
import com.psyche.memo.ui.AttachmentPreviewStrip
import com.psyche.memo.ui.ProviderAvatarSource

/**
 * 聊天输入栏 —— 第 3 步从 `ui/HomeScreen.kt` 摘出（纯搬运）：1:1 常量与算法（间距/断点/
 * 附件预览高度/软上限/输入容器形状/不透明度默认值）、`alphaBlend`/`inputFillColor`、
 * `ChatInputBar` 本体（含附件条、快捷短语、模型/搜索/工具/工作区/技能按钮、发送/停止）、
 * 停止方块、录音行、两颗输入栏图标。
 */

// ---------------------------------------------------------------------------
// 输入栏 1:1 常量与算法 —— 数值全部取自 Flutter 源码，逐项标注「源码文件:行号」
// ---------------------------------------------------------------------------

/** 源码 lib/theme/design_tokens.dart:31-37 —— AppSpacing */
private val SpacingXxs = 4.dp
private val SpacingXs = 8.dp
private val SpacingSm = 12.dp
private val SpacingMd = 16.dp

/** 源码 lib/shared/responsive/breakpoints.dart:5 —— AppBreakpoints.tablet = 900 */
private const val BREAKPOINT_TABLET_DP = 900f

/** 源码 lib/features/home/widgets/chat_input_bar.dart:247-248 —— 附件预览高度 */
private const val DOCUMENT_PREVIEW_HEIGHT_DP = 48f
private const val IMAGE_PREVIEW_HEIGHT_DP = 64f

/**
 * 源码 lib/features/home/widgets/chat_input_bar.dart:2569
 * baseChromeHeight = 120 // padding + action row + chrome buffer
 */
private const val BASE_CHROME_HEIGHT_DP = 120f

/** 源码 chat_input_bar.dart:2574 —— softCap = visibleHeight * 0.45 */
private const val SOFT_CAP_RATIO = 0.45f

/** 源码 chat_input_bar.dart:2577/2579 —— max(80.0, …) 下限 */
private const val MIN_INPUT_HEIGHT_DP = 80f

/** 源码 chat_input_bar.dart:2618-2619 / 2626 —— ClipRRect + BoxDecoration borderRadius: 20 */
private val InputContainerShape = RoundedCornerShape(MemoRadius.CARD_DP.dp)

/**
 * 源码 chat_input_bar.dart:2620-2621
 * BackdropFilter(filter: ImageFilter.blur(sigmaX: 14, sigmaY: 14))
 */

/** 源码 lib/core/providers/settings_provider.dart:5094-5095 —— 默认输入框背景透明度 */
private const val DEFAULT_INPUT_BG_OPACITY_LIGHT = 0.8236f
private const val DEFAULT_INPUT_BG_OPACITY_DARK = 0.7396f

/**
 * Flutter `Color.alphaBlend(foreground, background)` 的等价实现（source-over，
 * 非线性 sRGB，与 Flutter SDK 的整型算法一致）：
 *   backAlpha' = ba * (1 - fa); outAlpha = fa + backAlpha'
 *   channel    = (fc * fa + bc * backAlpha') / outAlpha
 *
 * 源码 lib/features/home/widgets/chat_input_bar.dart:284
 */
private fun alphaBlend(foreground: Color, background: Color): Color {
    val fa = foreground.alpha
    val ba = background.alpha
    if (fa == 0f) return background
    val backAlpha = ba * (1f - fa)
    val outAlpha = fa + backAlpha
    if (outAlpha == 0f) return Color.Transparent
    return Color(
        red = (foreground.red * fa + background.red * backAlpha) / outAlpha,
        green = (foreground.green * fa + background.green * backAlpha) / outAlpha,
        blue = (foreground.blue * fa + background.blue * backAlpha) / outAlpha,
        alpha = outAlpha,
    )
}

/**
 * 源码 lib/features/home/widgets/chat_input_bar.dart:260-285 —— _inputFillColor
 *
 * @param backgroundImageActive 移植版暂无聊天背景图能力，恒为 false，
 *   因此 backgroundRatio 分支（源码 :270-275）不会生效，仅保留结构。
 */
private fun inputFillColor(
    cs: androidx.compose.material3.ColorScheme,
    isDark: Boolean,
    backgroundImageActive: Boolean = false,
    lightOpacity: Float = DEFAULT_INPUT_BG_OPACITY_LIGHT,
    darkOpacity: Float = DEFAULT_INPUT_BG_OPACITY_DARK,
): Color {
    val configuredOpacity = (if (isDark) darkOpacity else lightOpacity).coerceIn(0f, 1f)
    val backgroundRatio = if (isDark) {
        0.545f / DEFAULT_INPUT_BG_OPACITY_DARK
    } else {
        0.5296f / DEFAULT_INPUT_BG_OPACITY_LIGHT
    }
    val targetOpacity = if (backgroundImageActive) {
        configuredOpacity * backgroundRatio
    } else {
        configuredOpacity
    }
    val overlayAlpha = if (isDark) {
        if (backgroundImageActive) 0.09f else 0.07f
    } else {
        0.02f
    }
    val overlayTint = (if (isDark) cs.onSurface else cs.primary).copy(alpha = overlayAlpha)
    val baseAlpha = ((targetOpacity - overlayAlpha) / (1f - overlayAlpha)).coerceIn(0f, 1f)
    val base = cs.surface.copy(alpha = baseAlpha)
    return alphaBlend(overlayTint, base).copy(alpha = targetOpacity)
}

/**
 * 输入框高度上限 —— `chat_input_bar.dart:2569-2586` 的 `maxInputHeight` 的**布局期**版本。
 *
 * 公式与源码逐字一致：
 * ```
 * visibleHeight  = size.height - viewInsets.bottom
 * available      = visibleHeight - attachmentPreview - baseChrome
 * softCap        = visibleHeight * SOFT_CAP_RATIO
 * maxInputHeight = available > 0
 *     ? min(available, max(MIN_INPUT_HEIGHT, min(softCap, available)))
 *     : max(MIN_INPUT_HEIGHT, softCap)
 * ```
 * 与源码的唯一区别是**求值时机**：`viewInsets.bottom`（= `WindowInsets.ime`）在
 * measure 阶段读，而不是组合阶段。键盘动画期间这个值每帧都在变，组合期读它会让
 * 整个输入栏（输入框 + 图标行 + 建议气泡 + 语音 UI）按帧重组；布局期读只让这一个
 * 节点重排 —— 而重排本来就每帧都在发生（列表视口随键盘收缩），所以是净赚。
 *
 * 组合期的 `WindowInsets.ime` 读取是本文件历史上「键盘抬起一顿一顿」的根因
 * （用户 2026-09-17）。**后续改动不要把它挪回组合体**。
 */
private fun Modifier.imeCappedInputHeight(
    windowInfo: androidx.compose.ui.platform.WindowInfo,
    imeInsets: WindowInsets,
    attachmentPreviewHeightDp: Float,
): Modifier = layout { measurable, constraints ->
    // 源码 chat_input_bar.dart:2560 —— isMobileLayout = size.width < AppBreakpoints.tablet
    val isMobileLayout = windowInfo.containerSize.width.toDp() < BREAKPOINT_TABLET_DP.dp
    if (!isMobileLayout) {
        // 桌面/平板宽度下 `maxInputHeight` 是 +∞，等价于不约束。
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    // `WindowInsets.ime` 这个**属性 getter 是 @Composable**（所以由调用方在组合期取好
    // 实例传进来）；而 `getBottom()` 本身是普通函数，在这里调 = 布局期读快照状态，
    // 只会让这个节点重排，不会让输入栏整体重组。这正是本次修复的关键。
    val visibleHeightDp = windowInfo.containerSize.height.toDp().value -
        imeInsets.getBottom(this) / density
    val available = visibleHeightDp - attachmentPreviewHeightDp - BASE_CHROME_HEIGHT_DP
    val softCap = visibleHeightDp * SOFT_CAP_RATIO
    val maxInputHeightDp = if (available > 0) {
        val capped = minOf(softCap, available)
        minOf(available, maxOf(MIN_INPUT_HEIGHT_DP, capped))
    } else {
        maxOf(MIN_INPUT_HEIGHT_DP, softCap)
    }
    // 源码 2583-2586：只有高度有限且 > 0 才约束。
    val maxHeightPx = if (maxInputHeightDp.isFinite() && maxInputHeightDp > 0) {
        maxInputHeightDp.dp.roundToPx()
    } else {
        constraints.maxHeight
    }
    val placeable = measurable.measure(
        constraints.copy(maxHeight = minOf(constraints.maxHeight, maxHeightPx)),
    )
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

@Composable
internal fun ChatInputBar(
    input: String,
    streaming: Boolean,
    enterToSend: Boolean = false,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    reasoningBudget: Int? = null,
    onOpenReasoning: () -> Unit = {},
    onOpenMcp: () -> Unit = {},
    onSelectModel: () -> Unit,
    onOpenSearch: () -> Unit = {},
    // 模型按钮（chat_input_bar.dart CIB:1763-1773 + model_icon.dart
    // CurrentModelIcon）：选中模型后按钮显示品牌图标圆（modelIconAsset），
    // 无品牌资产时用首字母圆（modelIconInitial）；两者都空 → Boxes。
    // 供应商自己配了头像（图标/emoji/图片）时优先用它（用户 2026-09-14：
    // 第三方供应商 key 叫「OpenAI - Agnes」会被品牌匹配成 GPT 图标）。
    modelIconAsset: String? = null,
    modelIconInitial: String? = null,
    modelAvatarSource: ProviderAvatarSource? = null,
    // 搜索按钮（CIB:1782-1863）：searchActive 时显示所选搜索服务的品牌
    // 图标（searchIconAsset），否则 Globe。
    searchActive: Boolean = false,
    searchIconAsset: String? = null,
    // 输入栏左排按钮的可用/选中态（chat_input_section.dart）：
    // supportsReasoning = 当前模型有推理能力（否则 Brain 整颗不显示，CIB:187）；
    // showMcpButton = 工具能力 + 有启用的 MCP（否则 Hammer 不显示，CIS:283-293）；
    // reasoningActive = 推理没关；mcpActive = 有已连接的已选 MCP；
    // quickPhraseAvailable = 全局或本助手配了快捷短语（否则整颗按钮不显示）。
    supportsReasoning: Boolean = true,
    showMcpButton: Boolean = true,
    reasoningActive: Boolean = false,
    mcpActive: Boolean = false,
    quickPhraseAvailable: Boolean = true,
    onOpenTools: () -> Unit = {},
    onQuickPhrase: () -> Unit = {},
    attachments: List<ChatViewModel.PendingAttachment> = emptyList(),
    onRemoveAttachment: (Int) -> Unit = {},
    // 语音输入执行器（chat_input_bar.dart asrProvider 的系统分支）；null =
    // 不可用，麦克风按钮按 CIB:2542-2546 showVoiceInput 条件隐藏。
    voice: com.psyche.memo.ui.chat.VoiceInputController? = null,
    // chat_input_bar.dart:2548-2553 `_inputFillColor(...)` 的三个入参：当前助手有壁纸
    // 时底色再乘一个比例，浅/深色不透明度来自显示设置（默认 0.8236 / 0.7396）。
    backgroundImageActive: Boolean = false,
    inputOpacityLight: Float = DEFAULT_INPUT_BG_OPACITY_LIGHT,
    inputOpacityDark: Float = DEFAULT_INPUT_BG_OPACITY_DARK,
    /** 长粘贴转文件（settings_provider.dart:252-255 两键）。 */
    longPaste: com.psyche.memo.ui.chat.LongPasteSettings = com.psyche.memo.ui.chat.LongPasteSettings(),
    /** 判定为长粘贴时把文本交出去（写文件 + 变成附件，不插入输入框）。 */
    onPasteText: (String) -> Unit = {},
    /** 麦克风权限未授予时请求（HomeScreen 持有 launcher）。 */
    onRequestMicPermission: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    // 源码 chat_input_bar.dart:2547 —— theme.brightness == Brightness.dark。
    // 移植版没有暴露主题模式的 CompositionLocal，按 Material3 惯例由 surface 亮度判定
    // （浅色 surface 亮度 ≈0.96，深色 ≈0.05）。
    val isDark = cs.surface.luminance() < 0.5f

    // 语音会话状态（CIB:852-932 录音行、2750-2752 readOnly、2542-2546 可见性）。
    val voiceState: com.psyche.memo.ui.chat.VoiceInputController.State =
        if (voice != null) {
            voice.state.collectAsState().value
        } else {
            com.psyche.memo.ui.chat.VoiceInputController.State.Idle
        }
    val voiceLevels = voice?.levels?.collectAsState()?.value ?: emptyList()
    val voiceActive = voiceState != com.psyche.memo.ui.chat.VoiceInputController.State.Idle
    // CIB:2542-2546 showVoiceInput —— asr 可用才显示麦克风。
    val voiceAvailable = remember(voice) { voice?.canUse() == true }
    // CIB:_startVoiceInput —— 成功启动后 unfocus。
    val voiceFocusManager = androidx.compose.ui.platform.LocalFocusManager.current
    /** 麦克风权限查询用（点击回调里不能读 LocalContext）。 */
    val voiceMicContext = androidx.compose.ui.platform.LocalContext.current
    // CIB onPartialResults —— 实时转写进输入框。
    LaunchedEffect(voiceState) {
        val listening = voiceState as? com.psyche.memo.ui.chat.VoiceInputController.State.Listening
        if (listening != null && listening.partial.isNotEmpty()) {
            onInputChange(listening.partial)
        }
    }

    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    // 源码 chat_input_bar.dart:2558-2561 的 `visibleHeight = size.height - viewInsets.bottom`
    // 与 2569-2586 的 `maxInputHeight` 计算**整体搬到布局期**（见 `imeCappedInputHeight`）。
    // `WindowInsets.ime` 在键盘动画期间每帧都在变，在组合体里读它会让整个 ChatInputBar
    // （输入框 + 图标行 + 建议气泡 + 语音 UI）按帧重组；放到 measure 阶段读只触发重排，
    // 而重排本来就每帧都在做（列表视口在缩）。用户 2026-09-17「点输入框、键盘抬起
    // 一顿一顿」的另一半根因（另一半在 `ChatContent` 的 `ImeRisePinEffect`）。

    // 源码 chat_input_bar.dart:2555-2556 / 2641-2642 —— 附件（图片 / 文档）内联预览。
    // 移植版当前没有附件数据，两个列表恒为空：预览高度按源码公式算得 0，
    // 结构保留，接入附件时只需让列表非空。
    val imageAttachments: List<String> = emptyList()
    val docAttachments: List<String> = emptyList()
    val hasImages = imageAttachments.isNotEmpty()
    val hasDocs = docAttachments.isNotEmpty()
    // 源码 chat_input_bar.dart:2562-2568
    val attachmentPreviewHeight = if (hasDocs || hasImages) {
        SpacingSm.value +
            (if (hasImages) IMAGE_PREVIEW_HEIGHT_DP else 0f) +
            (if (hasImages && hasDocs) SpacingXs.value else 0f) +
            (if (hasDocs) DOCUMENT_PREVIEW_HEIGHT_DP else 0f) +
            SpacingXxs.value
    } else {
        0f
    }

    // 源码 chat_input_bar.dart:2569-2586 —— maxInputHeight 与 heightIn 约束。
    // 公式逐字保留，只把求值时机从组合期挪到布局期（见 `imeCappedInputHeight`）。
    // `WindowInsets.ime` 在这里取 holder（@Composable getter，只能组合期取），
    // 真正的 inset 数值到 measure 阶段才读。
    val textFieldModifier = Modifier
        .fillMaxWidth()
        .imeCappedInputHeight(windowInfo, WindowInsets.ime, attachmentPreviewHeight)

    // 源码 chat_input_bar.dart:2588-2599
    //   SafeArea(top:false, left:false, right:false, bottom:true)
    //   + Padding.fromLTRB(AppSpacing.sm, AppSpacing.xxs, AppSpacing.sm, AppSpacing.xs)
    // union（而非叠加）：IME 高度已覆盖导航栏区域，叠加会把输入卡顶出可视区。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .padding(
                start = SpacingSm,
                top = SpacingXxs,
                end = SpacingSm,
                bottom = SpacingXs,
            ),
    ) {
        // 源码 chat_input_bar.dart:2600-2602 Column(mainAxisSize: min)
        //   → 2614 Stack → 2618 ClipRRect(20) → 2620 BackdropFilter(14) → 2622 Container
        Box(modifier = Modifier.fillMaxWidth().clip(InputContainerShape)) {
            // 背景 / 模糊层。
            // 源码 chat_input_bar.dart:2620-2621 BackdropFilter(ImageFilter.blur(14, 14))。
            // Compose 没有 BackdropFilter 等价物，用 Modifier.blur 近似；该修饰符只在
            // API 31 (Android 12) 及以上生效，API 30 及以下为空实现，自动退化为「不加
            // 模糊」，不会崩溃。放在最底层，避免把容器内的文本 / 图标一起模糊掉。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    // 源码 chat_input_bar.dart:2625 —— color: inputFillColor。
                    // 原版叠的是 BackdropFilter（真模糊背后的聊天内容）；Compose 的
                    // Modifier.blur 只模糊**自身内容**，而本层内容就是这块纯色 —— 模糊
                    // 纯色在观感上没有任何变化，却每帧都要走一遍模糊渲染管线。去掉它，
                    // 视觉一致、省掉一笔常驻开销。
                    .background(
                        color = inputFillColor(
                            cs = cs,
                            isDark = isDark,
                            backgroundImageActive = backgroundImageActive,
                            lightOpacity = inputOpacityLight,
                            darkOpacity = inputOpacityDark,
                        ),
                        shape = InputContainerShape,
                    ),
            )
            // 源码 chat_input_bar.dart:2639-2655 / 2830-2949 —— 容器内 Column：
            // ① 附件预览区 ② 输入区 ③ 底部按钮行
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 源码 chat_input_bar.dart:2628-2637
                    //   Border.all(width: 1,
                    //     color: isDark ? onSurface@0.10 : outline@0.20)
                    // 不随背景层模糊，保持 1px 描边清晰可见。
                    .border(
                        width = 1.dp,
                        color = if (isDark) {
                            cs.onSurface.copy(alpha = 0.10f)
                        } else {
                            cs.outline.copy(alpha = 0.20f)
                        },
                        shape = InputContainerShape,
                    ),
            ) {
                // ① 附件内联预览区（源码 chat_input_bar.dart:2641-2642）
                AttachmentPreviewStrip(
                    attachments = attachments,
                    onRemove = onRemoveAttachment,
                )

                // ② 输入区（源码 chat_input_bar.dart:2646-2654）
                //    Padding.fromLTRB(md, xxs, md, xs) + ConstrainedBox(maxHeight)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = SpacingMd,
                            top = SpacingXxs,
                            end = SpacingMd,
                            bottom = SpacingXs,
                        ),
                ) {
                    // CIB:2779-2782 —— 原版是 border:none + contentPadding
                    // (vertical:2, horizontal:0) 的裸输入框：M3 TextField 自带
                    // 16dp 横向内边距（会把打字区左右收窄）与 56dp 最小高，改用
                    // BasicTextField 复刻——横向零内边距，单行时文本垂直居中
                    // （interactiveAdjustment 语义），多行时内容撑高。
                    // 最小高按用户要求定：原版 kMinInteractiveDimension 是
                    // 48dp（M3 非 dense 字段默认 56dp），用户两次反馈上下留白
                    // 偏小 → 48 → 56 → 64。
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        // chat_input_bar.dart:1602-1665 `_handlePastedText` —— 剪贴板
                        // 文本超过阈值（按字素簇算）且开关打开时，**不插入输入框**，
                        // 而是写成一个 .txt 附件。原版把自定义 Paste 菜单项接到这条
                        // 路径上；Compose 的等价入口是 TextToolbar：包一层平台工具栏，
                        // 只替换 paste 回调，其余（复制/剪切/全选）原样透传。
                        val baseToolbar = androidx.compose.ui.platform.LocalTextToolbar.current
                        val clipboard = androidx.compose.ui.platform.LocalClipboard.current
                        val clipboardScope = rememberCoroutineScope()
                        val pasteToolbar = remember(baseToolbar, clipboard, longPaste, onPasteText) {
                            object : androidx.compose.ui.platform.TextToolbar {
                                override val status: androidx.compose.ui.platform.TextToolbarStatus
                                    get() = baseToolbar.status

                                override fun showMenu(
                                    rect: androidx.compose.ui.geometry.Rect,
                                    onCopyRequested: (() -> Unit)?,
                                    onPasteRequested: (() -> Unit)?,
                                    onCutRequested: (() -> Unit)?,
                                    onSelectAllRequested: (() -> Unit)?,
                                ) {
                                    baseToolbar.showMenu(
                                        rect,
                                        onCopyRequested,
                                        {
                                            // 新 Clipboard API 的读取是 suspend，转协程；
                                            // 语义与旧 getText() 一致（无文本走系统默认粘贴）。
                                            clipboardScope.launch {
                                                val text = clipboard.getClipEntry()
                                                    ?.clipData?.getItemAt(0)?.text?.toString().orEmpty()
                                                if (text.isEmpty() || !longPaste.isLongPaste(text)) {
                                                    onPasteRequested?.invoke()
                                                } else {
                                                    onPasteText(text)
                                                }
                                            }
                                        },
                                        onCutRequested,
                                        onSelectAllRequested,
                                    )
                                }

                                override fun hide() = baseToolbar.hide()
                            }
                        }
                        androidx.compose.runtime.CompositionLocalProvider(
                            androidx.compose.ui.platform.LocalTextToolbar provides pasteToolbar,
                        ) {
                        BasicTextField(
                            value = input,
                            onValueChange = onInputChange,
                            modifier = textFieldModifier,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = ChatStyleSpec.INPUT_TEXT_SP.sp,
                                color = cs.onSurface,
                            ),
                            cursorBrush = SolidColor(cs.primary),
                            // CIB:2750-2752 readOnly —— composerLocked || _ownsVoiceSession。
                            readOnly = voiceActive,
                            // 源码 chat_input_bar.dart:2741 —— maxLines: 5（未展开状态）
                            maxLines = 5,
                            // display_enter_to_send_on_mobile_v1（chat_input_bar.dart
                            // L2727）：关闭时回车换行，不再触发发送。
                            keyboardOptions = KeyboardOptions(
                                imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default,
                            ),
                            // CIB:934-938 _handleSend —— 语音会话中不触发发送。
                            keyboardActions = KeyboardActions(
                                onSend = { if (enterToSend && !streaming && !voiceActive) onSend() },
                            ),
                            decorationBox = { inner ->
                                Box {
                                    if (input.isEmpty()) {
                                        Text(
                                            stringResource(UiR.string.chat_input_bar_hint),
                                            style = androidx.compose.ui.text.TextStyle(
                                                fontSize = ChatStyleSpec.INPUT_TEXT_SP.sp,
                                                color = cs.onSurface.copy(
                                                    alpha = ChatStyleSpec.INPUT_HINT_ALPHA,
                                                ),
                                            ),
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                        }
                    }
                }

                // ③ 底部按钮行（源码 chat_input_bar.dart:2830-2949）
                //    Padding.fromLTRB(xs, 0, xs, xs)，spaceBetween：左侧工具 + 右侧 plus/mic/发送
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = SpacingXs,
                            top = 0.dp,
                            end = SpacingXs,
                            bottom = SpacingXs,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // CIB:2837-2853 —— 录音行与常规按钮行经 AnimatedSwitcher
                    // (260ms) fade + 0.35 高度竖滑切换（入场上滑、出场下滑），
                    // AnimatedContent 等价实现。
                    AnimatedContent(
                        targetState = voiceActive,
                        transitionSpec = {
                            (fadeIn(tween(260)) + slideInVertically(tween(260)) { (it * 0.35f).toInt() }) togetherWith
                                (fadeOut(tween(260)) + slideOutVertically(tween(260)) { (it * 0.35f).toInt() })
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = "inputBottomRowSwitch",
                    ) { recording ->
                        if (recording && voice != null) {
                            ChatVoiceRecordingRow(
                                state = voiceState,
                                levels = voiceLevels,
                                isDark = isDark,
                                cs = cs,
                                voice = voice,
                                onFinalText = { text, send ->
                                    if (text.isNotEmpty()) {
                                        onInputChange(text)
                                        if (send) onSend()
                                    }
                                },
                            )
                        } else {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    // CIB:2891-2926 —— 左侧工具图标间 8。
                                    horizontalArrangement = Arrangement.spacedBy(
                                        ChatStyleSpec.INPUT_ACTIONS_GAP_DP.dp,
                                    ),
                                ) {
                                    // CIB:1763-1773 —— 模型按钮：选中模型后显示
                                    // CurrentModelIcon。底色透明（原版
                                    // backgroundColor: Colors.transparent，
                                    // RikkaHub ModelSelectorButton 同样是
                                    // AutoAIIcon(color = Color.Transparent)），
                                    // 品牌图标保留原本的品牌色，只有深色模式下
                                    // 需要反色的 mono 资产才 tint onSurface
                                    // （model_icon.dart 的 assetNeedsDarkInvert）。
                                    val modelAsset = modelIconAsset
                                    if (modelAvatarSource != null || modelAsset != null || !modelIconInitial.isNullOrEmpty()) {
                                        IconButton(
                                            onClick = onSelectModel,
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            if (modelAvatarSource != null) {
                                                ModelAvatarGlyph(modelAvatarSource, cs, isDark)
                                            } else if (modelAsset != null) {
                                                coil.compose.AsyncImage(
                                                    model = modelAsset,
                                                    contentDescription = stringResource(UiR.string.chat_input_bar_select_model_tooltip),
                                                    colorFilter = if (isDark && BrandAssets.assetNeedsDarkInvert(modelAsset)) {
                                                        androidx.compose.ui.graphics.ColorFilter.tint(cs.onSurface)
                                                    } else {
                                                        null
                                                    },
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            } else {
                                                Text(
                                                    text = modelIconInitial!!.trim().take(1).uppercase(),
                                                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.primary),
                                                )
                                            }
                                        }
                                    } else {
                                        InputIcon(
                                            Lucide.Boxes,
                                            stringResource(UiR.string.chat_input_bar_select_model_tooltip),
                                            onSelectModel,
                                            cs,
                                        )
                                    }
                                    // CIB:1811-1863 —— 搜索按钮：启用搜索时显示
                                    // 所选服务的品牌图标（active 色），否则 Globe。
                                    if (searchActive && searchIconAsset != null) {
                                        InputIconAsset(
                                            searchIconAsset,
                                            stringResource(UiR.string.chat_input_bar_online_search_tooltip),
                                            onOpenSearch,
                                            cs,
                                            active = true,
                                        )
                                    } else {
                                        InputIcon(
                                            Lucide.Globe,
                                            stringResource(UiR.string.chat_input_bar_online_search_tooltip),
                                            onOpenSearch,
                                            cs,
                                        )
                                    }
                                    // CIB:1879-1920 —— supportsReasoning 门控：模型没有
                                    // 推理能力（override abilities 或名称推断都没有）时
                                    // 整颗按钮不显示。渲染预算图标（ReasoningIcons），
                                    // 推理没关时走 active 色（CIB:1904）。
                                    if (supportsReasoning) {
                                        InputIconAsset(
                                            asset = com.psyche.memo.ui.chat.ReasoningBudgetIcons
                                                .assetForBudget(reasoningBudget),
                                            label = stringResource(UiR.string.chat_input_bar_reasoning_strength_tooltip),
                                            onClick = onOpenReasoning,
                                            cs = cs,
                                            active = reasoningActive,
                                        )
                                    }
                                    // MCP 按钮**有意不再渲染**（用户 2026-09-14：
                                    // 「你加了 MCP 在加号里面，输入框里面为什么还有呀」）——
                                    // MCP 入口已并进输入栏「+」面板的 MCP 行，同一屏两个入口
                                    // 是重复。`showMcpButton` 的门控逻辑与测试保留（能力判定
                                    // 仍然成立，将来若要放回来直接用）。
                                    // 没配快捷短语（全局+本助手）整颗按钮不显示。
                                    if (quickPhraseAvailable) {
                                        InputIcon(
                                            Lucide.Zap,
                                            stringResource(UiR.string.chat_input_bar_quick_phrase_tooltip),
                                            onQuickPhrase,
                                            cs,
                                        )
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    // CIB:2912/2928 —— 右侧 + 与语音按钮后各 8。
                                    horizontalArrangement = Arrangement.spacedBy(
                                        ChatStyleSpec.INPUT_ACTIONS_GAP_DP.dp,
                                    ),
                                ) {
                                    InputIcon(
                                        Lucide.Plus,
                                        stringResource(UiR.string.chat_input_bar_more_tooltip),
                                        onOpenTools,
                                        cs,
                                    )
                                    // CIB:2542-2546 —— asr 可用才显示麦克风；
                                    // 点击 _startVoiceInput（unfocus + start）。
                                    if (voiceAvailable) {
                                        InputIcon(
                                            Lucide.Mic,
                                            stringResource(UiR.string.chat_input_bar_voice_input_tooltip),
                                            {
                                                if (!voiceActive) {
                                                    // 先确保麦克风权限（云端与系统识别都要），
                                                    // 授权回调里再真正开始（原版 permission_handler 同款顺序）。
                                                    val micContext = voiceMicContext
                                                    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                                                        micContext,
                                                        android.Manifest.permission.RECORD_AUDIO,
                                                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                                    if (granted) {
                                                        voiceFocusManager.clearFocus()
                                                        voice?.start()
                                                    } else {
                                                        onRequestMicPermission()
                                                    }
                                                }
                                            },
                                            cs,
                                        )
                                    }
                                    // CIB:3264-3315 _CompactSendButton —— 32 圆（icon 18 +
                                    // pad 7）；可用/流式: primary 底 + onPrimary 图标；
                                    // 禁用: onSurface@0.12 底 + onSurface@0.38 图标。
                                    // 流式时图标换成原项目 assets/icons/stop.svg 的实心
                                    // 圆角方块（24 viewBox 内 14×14、rx2），并用
                                    // AnimatedSwitcher 等价的 Scale+Fade 做 200ms 形变。
                                    // CIB:2929-2942 _CompactSendButton enabled：
                                    // hasText || hasImages || hasDocs（只有附件的消息
                                    // 也能发，长粘贴转文件后输入框本来就是空的）。
                                    val canSend = input.isNotBlank() || attachments.isNotEmpty()
                                    val sendBg = if (canSend || streaming) cs.primary
                                    else cs.onSurface.copy(alpha = ChatStyleSpec.SEND_DISABLED_BG_ALPHA)
                                    val sendFg = if (canSend || streaming) cs.onPrimary
                                    else cs.onSurface.copy(alpha = ChatStyleSpec.SEND_DISABLED_FG_ALPHA)
                                    Box(
                                        modifier = Modifier
                                            .size(ChatStyleSpec.SEND_BUTTON_DP.dp)
                                            .background(sendBg, CircleShape)
                                            .clickable {
                                                if (streaming) onStop() else if (canSend) onSend()
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        AnimatedContent(
                                            targetState = streaming,
                                            transitionSpec = {
                                                (scaleIn(
                                                    initialScale = 0.6f,
                                                    animationSpec = tween(
                                                        ChatStyleSpec.SEND_ICON_SWITCH_MS,
                                                    ),
                                                ) + fadeIn(
                                                    animationSpec = tween(
                                                        ChatStyleSpec.SEND_ICON_SWITCH_MS,
                                                    ),
                                                )) togetherWith
                                                    (scaleOut(
                                                        targetScale = 0.6f,
                                                        animationSpec = tween(
                                                            ChatStyleSpec.SEND_ICON_SWITCH_MS,
                                                        ),
                                                    ) + fadeOut(
                                                        animationSpec = tween(
                                                            ChatStyleSpec.SEND_ICON_SWITCH_MS,
                                                        ),
                                                    ))
                                            },
                                            label = "send-stop",
                                        ) { isStreaming ->
                                            if (isStreaming) {
                                                ChatStopSquare(
                                                    color = sendFg,
                                                    size = ChatStyleSpec.SEND_ICON_DP.dp,
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Lucide.ArrowUp,
                                                    contentDescription = "Send",
                                                    tint = sendFg,
                                                    modifier = Modifier.size(
                                                        ChatStyleSpec.SEND_ICON_DP.dp,
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 停止图标 —— 原项目 `assets/icons/stop.svg` 的 Compose 等价物：
 * 24 viewBox 内一个 14×14、圆角 2 的**实心**方块（fill=currentColor）。
 * 按比例缩放到给定的 [size]（发送按钮用 18dp → 方块 10.5dp、圆角 1.5dp）。
 * 不用 Lucide 的描边方块：原项目是实心且比例不同，描边版观感偏"取消"。
 */
@Composable
private fun ChatStopSquare(
    color: androidx.compose.ui.graphics.Color,
    size: androidx.compose.ui.unit.Dp,
) {
    val scale = size / ChatStyleSpec.STOP_SVG_VIEWBOX_DP.dp
    val side = ChatStyleSpec.STOP_SVG_SIDE_DP.dp * scale
    val radius = ChatStyleSpec.STOP_SVG_RADIUS_DP.dp * scale
    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(side)
                .background(color, androidx.compose.foundation.shape.RoundedCornerShape(radius)),
        )
    }
}

/**
 * 录音行 —— chat_input_bar.dart `_buildVoiceRecordingRow`（CIB:852-932）1:1：
 * 取消 X — 波形/转写指示（Expanded）— 停止方块 — 发送 Check。
 * [onFinalText] 收到 (最终文本, 是否随后发送)。
 */
@Composable
private fun ChatVoiceRecordingRow(
    state: com.psyche.memo.ui.chat.VoiceInputController.State,
    levels: List<Float>,
    isDark: Boolean,
    cs: androidx.compose.material3.ColorScheme,
    voice: com.psyche.memo.ui.chat.VoiceInputController,
    onFinalText: (String, Boolean) -> Unit,
) {
    // CIB:854-855 canFinish = isListening && !_finishingVoice。
    val canFinish = state is com.psyche.memo.ui.chat.VoiceInputController.State.Listening
    // 触感（项目惯例：Haptics + LocalHapticsSettings 门控）。三键轻点反馈。
    val hapticsView = androidx.compose.ui.platform.LocalView.current
    val hapticsSettings = com.psyche.memo.ui.LocalHapticsSettings.current
    fun tapHaptic() {
        if (hapticsSettings.globalEnabled) com.psyche.memo.common.Haptics.light(hapticsView)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ① 取消（CIB:859-863）—— 收尾中禁用。
        InputIcon(
            Lucide.X,
            stringResource(UiR.string.chat_input_bar_voice_cancel_tooltip),
            onClick = {
                tapHaptic()
                voice.cancel()
            },
            cs = cs,
            enabled = canFinish,
        )
        // ② 波形 / 转写指示（CIB:864-901：Expanded + 左 8 右 2 + 32 高，
        // AnimatedSwitcher 180ms fade 切换）。
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp, end = 2.dp)
                .height(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = !canFinish,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                label = "voiceTranscribingSwitch",
            ) { transcribing ->
                if (transcribing) {
                    com.psyche.memo.ui.chat.VoiceTranscribingIndicator(
                        label = stringResource(UiR.string.chat_input_bar_voice_transcribing),
                        color = cs.onSurface.copy(alpha = 0.72f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    com.psyche.memo.ui.chat.VoiceWaveform(
                        levels = levels,
                        color = cs.onSurface.copy(alpha = 0.85f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        // ③ 停止（CIB:904-920）：12×12 圆角 3.5 方块，颜色 = 图标前景色。
        val stopTint = cs.onSurface.copy(
            alpha = if (isDark) ChatStyleSpec.COMPACT_ICON_ALPHA_DARK
            else ChatStyleSpec.COMPACT_ICON_ALPHA_LIGHT,
        )
        IconButton(
            onClick = {
                tapHaptic()
                voice.finish { text -> onFinalText(text, false) }
            },
            enabled = canFinish,
            modifier = Modifier.size(32.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(stopTint, RoundedCornerShape(3.5f.dp)),
            )
        }
        Spacer(Modifier.width(8.dp))
        // ④ 发送（CIB:923-929）：_CompactSendButton —— primary 底 Check 图标，
        // 禁用态灰底灰字。
        Box(
            modifier = Modifier
                .size(ChatStyleSpec.SEND_BUTTON_DP.dp)
                .background(
                    if (canFinish) cs.primary
                    else cs.onSurface.copy(alpha = ChatStyleSpec.SEND_DISABLED_BG_ALPHA),
                    CircleShape,
                )
                .clickable(enabled = canFinish) {
                    // 用户 2026-09-16：「对勾改成显示在输入框里面，不要直接发送」——
                    // 识别结果一律回填输入框，发送交给用户按常规发送键。
                    tapHaptic()
                    voice.finish { text -> onFinalText(text, false) }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Lucide.Check,
                contentDescription = stringResource(UiR.string.chat_input_bar_voice_send_tooltip),
                tint = if (canFinish) cs.onPrimary
                else cs.onSurface.copy(alpha = ChatStyleSpec.SEND_DISABLED_FG_ALPHA),
                modifier = Modifier.size(ChatStyleSpec.SEND_ICON_DP.dp),
            )
        }
    }
}

@Composable
private fun InputIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    cs: androidx.compose.material3.ColorScheme,
    enabled: Boolean = true,
    /** 选中/生效态走 primary（CIB:1880-2042 的 `active:` 参数，如推理、MCP）。 */
    active: Boolean = false,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp)) {
        Icon(
            icon,
            contentDescription = label,
            // CIB:3211-3213 —— active = primary；非 active 色 onSurface@0.70(dark)/0.54(light)；
            // 禁用态 = 原色 ×0.45（ios_tactile.dart:54-59）。
            tint = if (active) {
                cs.primary
            } else {
                cs.onSurface.copy(
                    alpha = (if (cs.surface.luminance() < 0.5f) ChatStyleSpec.COMPACT_ICON_ALPHA_DARK
                    else ChatStyleSpec.COMPACT_ICON_ALPHA_LIGHT) * if (enabled) 1f else 0.45f,
                )
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun InputIconAsset(
    asset: String,
    label: String,
    onClick: () -> Unit,
    cs: androidx.compose.material3.ColorScheme,
    active: Boolean = false,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        // SVG brand glyphs are tinted (kelivo's SvgPicture/Image with
        // colorBlendMode srcIn) so a mono logo stays legible in both themes;
        // raster brand icons keep their own colours, like RikkaHub's
        // AutoAIIcon, which only injects a fill for SVGs.
        val tint = if (asset.endsWith(".svg")) {
            androidx.compose.ui.graphics.ColorFilter.tint(
                if (active) {
                    cs.primary
                } else {
                    cs.onSurface.copy(
                        alpha = if (cs.surface.luminance() < 0.5f) ChatStyleSpec.COMPACT_ICON_ALPHA_DARK
                        else ChatStyleSpec.COMPACT_ICON_ALPHA_LIGHT,
                    )
                },
            )
        } else {
            null
        }
        coil.compose.AsyncImage(
            model = asset,
            contentDescription = label,
            colorFilter = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

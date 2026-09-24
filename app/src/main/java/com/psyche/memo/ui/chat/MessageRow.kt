package com.psyche.memo.ui.chat

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleStop
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.Languages
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.User
import com.composables.icons.lucide.Volume2
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ChatViewModel
import com.psyche.memo.common.Haptics
import com.psyche.memo.data.model.ImagePart
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.AssistantListAvatar
import com.psyche.memo.ui.BrandAssets
import com.psyche.memo.ui.ChatStyleSpec
import com.psyche.memo.ui.UserAvatar
import com.psyche.memo.ui.UserAvatarFallback
import com.psyche.memo.ui.UserProfileStore
import com.psyche.memo.ui.chat.AskUserInteractionService
import com.psyche.memo.ui.chat.AskUserResult
import com.psyche.memo.ui.chat.ToolApprovalService
import com.psyche.memo.ui.chat.ToolUiPart
import kotlin.math.max

/**
 * 消息行 —— 「一屏一条消息」的全部渲染：模型图标、用户/助手正文与气泡、附件、
 * 思考卡与工具卡、操作行、Token 统计、重试倒计时、建议气泡、分支选择器。
 *
 * **2026-09-16 从 `ui/HomeScreen.kt` 原样摘出**（重构第 2 步，纯搬运、无行为变化）：
 * 那边曾是一个 4500 行的单文件，`ChatContent` 一个 composable 就吃掉 1600 行，
 * 消息行又占 930 行，改一行要在大文件里来回跳。摘出后 `HomeScreen.kt` 只留页面骨架
 * （抽屉 + 时间线 + 输入栏）与它自己的排版常量。
 *
 * 依赖只有三处来自原文件：`ChatRecompositionProbe` 与 `timeStr` 随本文件一起搬过来
 * （前者被 `ChatRowRecompositionTest` 用作「消息行没有被无谓重组」的判据），
 * 其余全部走 `com.psyche.memo.ui.chat` 里的既有组件。
 */

/**
 * model_icon.dart `CurrentModelIcon` in the shape the chat header uses it
 * (chat_message_widget.dart:2278-2287 → `CurrentModelIcon(size: 30)`):
 * primary-tinted circle with the model's brand glyph at 0.5x, falling back to
 * the first character of the model id. Mono assets that need inverting in dark
 * theme are tinted onSurface, exactly like the Flutter original.
 */
@Composable
private fun MessageModelIcon(
    providerKey: String,
    modelId: String,
    size: androidx.compose.ui.unit.Dp,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val asset = remember(modelId, providerKey) {
        modelId.takeIf { it.isNotEmpty() }?.let { BrandAssets.assetForName(it) }
            ?: providerKey.takeIf { it.isNotEmpty() }?.let { BrandAssets.assetForName(it) }
    }
    Box(
        modifier = Modifier
            .size(size)
            .background(cs.primary.copy(alpha = if (isDark) 0.18f else 0.1f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (asset != null) {
            coil.compose.AsyncImage(
                model = asset,
                contentDescription = null,
                colorFilter = if (isDark && BrandAssets.assetNeedsDarkInvert(asset)) {
                    androidx.compose.ui.graphics.ColorFilter.tint(cs.onSurface)
                } else {
                    null
                },
                modifier = Modifier.size(size * 0.5f),
            )
        } else {
            Text(
                text = modelId.trim().take(1).uppercase().ifEmpty { "?" },
                style = TextStyle(
                    fontSize = (size.value * 0.43f).sp,
                    fontWeight = FontWeight.Bold,
                    color = cs.primary,
                ),
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MessageRow(
    msg: ChatViewModel.UiMessage,
    selecting: Boolean = false,
    suggestions: List<String> = emptyList(),
    onSuggestionTap: (String) -> Unit = {},
    assistantLabel: String,
    /**
     * 该消息的模型显示名（`_resolveModelDisplayName`，CMW:1355-1407；含
     * `display_show_provider_in_chat_message_v1` 的 `" | 供应商"` 后缀）。
     */
    modelLabel: String = "",
    /** 当前助手：消息头在「助手头像 / 模型图标」之间二选一（CMW:2787-2802）。 */
    assistant: com.psyche.memo.data.model.Assistant? = null,
    /** 用户资料（user_provider.dart）：用户头的头像/名字。 */
    userProfile: UserProfileStore.Profile = UserProfileStore.Profile(),
    /** display_show_model_icon_v1，默认 true（settings_provider.dart:1069）。 */
    showModelIcon: Boolean = true,
    versionCount: Int,
    versionIndex: Int,
    onPrevVersion: (() -> Unit)?,
    onNextVersion: (() -> Unit)?,
    onCopy: () -> Unit,
    onRegenerate: (() -> Unit)?,
    /** 助手消息的重新生成（CMW:3239-3249 _confirmRegeneration 确认后执行）。 */
    onRegenerateAssistant: (() -> Unit)? = null,
    /** Translate 按钮（CMW:3298-3339）：传入所选语言 code（含 __clear__）。 */
    onTranslate: (String) -> Unit = {},
    onEdit: () -> Unit,
    onMore: () -> Unit,
    onDelete: () -> Unit,
    /** 思考卡 / 工具卡的 6 个显示开关（settings_provider.dart display_*）。 */
    timelineSettings: com.psyche.memo.ui.chat.ChatTimelineSettings,
    /** 展开/折叠某个思考段（home_page_controller.toggleReasoningSegment）。 */
    onToggleReasoning: (segmentIndex: Int) -> Unit,
    /** 当前会话 id（审批卡 / ask-user 卡按会话匹配 pending 请求）。 */
    conversationId: String?,
    /** 代码块「预览」（HTML 块）→ 打开 WebView 预览页（core:ui 不认识该页面）。 */
    onOpenHtmlPreview: ((String) -> Unit)? = null,
    /** 工具审批服务（tool_approval_service.dart）—— 审批卡与时间线可见性。 */
    approvalService: ToolApprovalService?,
    /** ask-user 交互服务（ask_user_interaction_service.dart）。 */
    askUserService: AskUserInteractionService?,
    /** 恢复已持久化 ask-user 回答（home_page_controller.submitRecoveredAskUserAnswer）。 */
    onRecoveredAnswer: ((ToolUiPart, AskUserResult) -> Unit)?,
    /** display_show_regenerate_confirm_dialog_v1 = false 时跳过确认弹窗。 */
    skipRegenerateConfirm: Boolean = false,
) {
    ChatRecompositionProbe.messageRows++
    val cs = MaterialTheme.colorScheme
    val rowView = LocalView.current
    val isUser = msg.role == "user"
    // 该消息所属助手的正则规则（visual 目标只影响这里显示的文本）。
    val assistantRegexRulesCache = remember(msg.id) {
        com.psyche.memo.data.model.AssistantRegexApplier.decodeRules(
            assistant?.regexRules.orEmpty(),
        )
    }
    // 时间戳文本：滚动时每行都会重组，格式化一次就够（头部 user/assistant
    // 两个分支共用）。
    val timeLabel = remember(msg.timestamp) { timeStr(msg.timestamp) }
    // 暗色判定（chat_input_bar.dart:2547 同款口径）。
    val isDark = cs.surface.luminance() < 0.5f
    // 每条消息外边距：用户 h16 / 助手 h20，垂直 12（CMW:1768 / 2780）。
    val rowHorizontal = if (isUser) ChatStyleSpec.USER_MESSAGE_HORIZONTAL_DP.dp
    else ChatStyleSpec.ASSISTANT_MESSAGE_HORIZONTAL_DP.dp
    // User bubble max width = screen width * 0.75
    // (chat_message_widget.dart L1833/1853).
    val maxBubbleWidth = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp() * ChatStyleSpec.USER_MAX_WIDTH_RATIO
    }
    var showContextMenu by remember { mutableStateOf(false) }
    // 全屏图片查看器状态（image_viewer_page.dart 移动端路径）。
    var viewerState by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    // 引用来源 sheet 状态（citation_sources_sheet.dart）。
    var showCitations by remember { mutableStateOf(false) }
    // 翻译区折叠状态（chat_message_widget.dart translationExpanded）。
    var translationExpanded by remember(msg.id, msg.translation) { mutableStateOf(true) }
    // 助手操作行：语言选择 sheet + 重新生成确认（CMW:3298-3339 / 1328-1358）。
    var showLanguageSheet by remember { mutableStateOf(false) }
    var showRegenerateConfirm by remember { mutableStateOf(false) }
    // TTS 播放状态 → Speak/Stop/Resume 图标。原版用全局 isActive，导致读一条消息
    // 时**所有**消息都显示停止；这里按 ownerId 只让被朗读的那条消息响应，并在暂停
    // 时显示继续（用户实测反馈）。
    val ttsState by com.psyche.memo.ui.chat.TtsPlayer.state.collectAsState()
    val ttsAction = com.psyche.memo.ui.chat.messageTtsAction(ttsState, msg.id)
    // search_web / builtin_search 工具结果提取为引用来源
    // （chat_message_widget.dart _allSearchItems，从后往前、去重）。
    val searchItems = remember(msg.id, msg.parts) {
        com.psyche.memo.ui.chat.extractCitationItems(msg.parts)
    }
    // 引用元数据解析 + 点击处理（渲染为 RikkaHub 圆形域名胶囊：新格式
    // [citation,domain](id) 由模型直写域名；历史 [cite:id] 归一化后经此
    // 反查 search_items 得到域名/序号回退）。仅当本条消息含搜索来源时有效。
    val context = androidx.compose.ui.platform.LocalContext.current
    val citationResolver: (String) -> com.psyche.memo.ui.markdown.CitationInfo? = { id ->
        val key = id.trim()
        if (key.isEmpty()) {
            null
        } else {
            val item = searchItems.firstOrNull { it.id == key }
                ?: key.toIntOrNull()?.let { n -> searchItems.firstOrNull { it.index == n } }
                // 正文里的普通 Markdown 链接按 **URL** 命中来源（模型实测会写
                // `[链接](https://…)`，没有 id 可查）。
                ?: searchItems.firstOrNull { it.url.isNotEmpty() && it.url.equals(key, ignoreCase = true) }
                ?: searchItems.firstOrNull {
                    val normalized = com.psyche.memo.ui.chat.normalizeExternalUri(it.url)?.toString()
                    normalized != null && normalized.equals(key, ignoreCase = true)
                }
            if (item == null) {
                null
            } else {
                com.psyche.memo.ui.markdown.CitationInfo(
                    domain = com.psyche.memo.ui.chat.citationDomain(item.url).takeIf { it.isNotEmpty() },
                    index = item.index,
                )
            }
        }
    }
    val handleCitationTap: (String) -> Unit = { id ->
        val item = searchItems.firstOrNull { it.id == id }
            ?: id.toIntOrNull()?.let { n -> searchItems.firstOrNull { it.index == n } }
        val url = item?.url ?: if (id.contains('/') || id.contains('.')) id else null
        if (!url.isNullOrEmpty()) com.psyche.memo.ui.chat.openExternal(context, url)
    }
    // 表格工具栏（_MarkdownTableToolbar）：复制 / 存图 / 导出 CSV 的平台侧实现，
    // 由 app 注入给 core:ui（core:ui 拿不到剪贴板、MediaStore、SAF）。
    val tableActions = com.psyche.memo.ui.chat.rememberMarkdownTableActions()
    // 代码块：折叠/换行三个设置 +「另存为」「预览」两个动作（另存为走 SAF，
    // 与表格导出 CSV 同一套系统创建文档通道）。
    val codeBlockConfig = remember(
        timelineSettings.autoCollapseCodeBlock,
        timelineSettings.autoCollapseCodeBlockLines,
        timelineSettings.mobileCodeBlockWrap,
        msg.isStreaming,
    ) {
        com.psyche.memo.ui.markdown.CodeBlockConfig(
            autoCollapse = timelineSettings.autoCollapseCodeBlock,
            autoCollapseLines = timelineSettings.autoCollapseCodeBlockLines,
            wrap = timelineSettings.mobileCodeBlockWrap,
            // 流式中不折叠：否则长代码块停在开头，用户以为没在输出。
            isStreaming = msg.isStreaming,
        )
    }
    val codeBlockActions = rememberCodeBlockActions(onPreviewHtml = onOpenHtmlPreview)
    // 数学公式两开关（渲染页）：总开关 + 是否把 `$…$` 当公式。
    val mathConfig = remember(timelineSettings.mathRendering, timelineSettings.dollarLatex) {
        com.psyche.memo.ui.markdown.MathConfig(
            enabled = timelineSettings.mathRendering,
            dollarLatex = timelineSettings.dollarLatex,
        )
    }
    // CMW:3707-3711 的第三分支 _buildToolMessage(1662-1706)：role == tool 的
    // 消息没有头像/气泡/操作行，正文本身就是 {tool, arguments, result, metadata}，
    // 渲染成 h16 v6 里的一张工具卡；按显示设置不可见时整条不占位。
    if (msg.role == "tool") {
        val toolPart = remember(msg.id, msg.parts) {
            com.psyche.memo.ui.chat.ToolUiPart.fromToolMessage(msg.id, msg.content)
        }
        // `visible` already implies a non-null part, so the extra null check
        // is redundant (and smart-casts through the local).
        val visible = toolPart != null && com.psyche.memo.ui.chat.isTimelineToolVisible(
            toolName = toolPart.toolName,
            loading = toolPart.loading,
            showToolCards = timelineSettings.showToolCards,
            filterBuiltinSearch = false,
        )
        if (visible) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                // CMW:3700-3713 —— role == tool 的消息同样套 _ChatSurfaceTheme，
                // 工具卡的前景色板跟随当前气泡样式。
                androidx.compose.runtime.CompositionLocalProvider(
                    com.psyche.memo.ui.chat.LocalChatSurfaceFg provides
                        com.psyche.memo.ui.chat.computeChatSurfaceFg(
                            cs, isDark, false, timelineSettings.bubbleStyles,
                        ),
                    com.psyche.memo.ui.chat.LocalChatBubbleStyles provides timelineSettings.bubbleStyles,
                ) {
                    com.psyche.memo.ui.chat.ToolCallCard(
                        part = toolPart,
                        hideToolResultImages = timelineSettings.hideToolResultImages,
                        conversationId = conversationId,
                        approval = approvalService,
                        askUser = askUserService,
                        onRecoveredAnswer = onRecoveredAnswer,
                    )
                }
            }
        }
        return
    }
    // CMW:2870-2884 timelineProjection → visibleBlocks：助手气泡里的文本块与思考
    // 卡按 part 到达顺序排列（用户消息不走投影）。
    val assistantBlocks = if (isUser) {
        emptyList()
    } else {
        remember(msg.id, msg.parts, msg.reasoningSegmentsJson, msg.isStreaming) {
            com.psyche.memo.ui.chat.projectAssistantBlocks(
                parts = msg.parts,
                segmentsJson = msg.reasoningSegmentsJson,
                isStreaming = msg.isStreaming,
            )
        }
    }
    // CMW 侧的等价物：message_list_view.dart:2018-2024 把整条消息包进
    // `MediaQuery(textScaler: 系统缩放 × chatFontScale)`，所以消息头/正文/思考卡/
    // 代码块的字号一起缩放，而 dp（头像、图标、内边距）不变。Compose 里 sp 的缩放
    // 因子就是 LocalDensity.fontScale，照原样乘上去即可。
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    val messageDensity = remember(baseDensity, timelineSettings.chatFontScale) {
        if (timelineSettings.chatFontScale == 1f) {
            baseDensity
        } else {
            androidx.compose.ui.unit.Density(
                density = baseDensity.density,
                fontScale = baseDensity.fontScale * timelineSettings.chatFontScale,
            )
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides messageDensity,
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = rowHorizontal,
                vertical = ChatStyleSpec.MESSAGE_VERTICAL_DP.dp,
            ),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (isUser) {
            // Header: name 13px α0.7 + timestamp 11px α0.5 (right-aligned) +
            // 用户头像（CMW:1773-1809）：名字/时间戳/头像分别受
            // display_show_user_name_v1 / _timestamp_v1 / _avatar_v1 控制，
            // 头像四态（emoji/url/file/空回退 User 图标）取自 UserProvider。
            val userLabel = userProfile.name.ifEmpty {
                stringResource(UiR.string.user_provider_default_user_name)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End) {
                    if (timelineSettings.showUserName) {
                        Text(
                            text = userLabel,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = cs.onSurface.copy(alpha = 0.7f),
                            ),
                        )
                    }
                    // 名与时间戳同时显示时才留那 2dp（CMW:1790-1792）。
                    if (timelineSettings.showUserName && timelineSettings.showUserTimestamp) {
                        Spacer(Modifier.height(ChatStyleSpec.NAME_TIME_GAP_DP.dp))
                    }
                    if (timelineSettings.showUserTimestamp) {
                        Text(
                            text = timeLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                color = cs.onSurface.copy(alpha = 0.5f),
                            ),
                        )
                    }
                }
                if (timelineSettings.showUserAvatar) {
                    Spacer(Modifier.width(ChatStyleSpec.ASSISTANT_AVATAR_NAME_GAP_DP.dp))
                    UserAvatar(
                        profile = userProfile,
                        name = userLabel,
                        size = ChatStyleSpec.AVATAR_SIZE_DP.dp,
                        fallback = UserAvatarFallback.Icon,
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // chat_message_widget.dart:2787-2802 —— useAssistantAvatar 优先
                // （助手头像四态），否则 showModelIcon 时显示该消息的模型品牌
                // 图标；两者都不显示时头部只有名字。
                val headerAssistant = assistant
                // display_show_assistant_avatar_v1（Memo 新增，**默认开**）：就是**显示/隐藏**助手头像。
                // 打开 ⇒ 一定显示助手头像（助手没设头像时 AssistantListAvatar 自带首字母圆牌回退）；
                // 关闭 ⇒ 不画头像，回落到上游的「模型图标」开关（showModelIcon）。
                if (timelineSettings.showAssistantAvatar && headerAssistant != null) {
                    AssistantListAvatar(headerAssistant, 32.dp)
                    Spacer(Modifier.width(ChatStyleSpec.ASSISTANT_AVATAR_NAME_GAP_DP.dp))
                } else if (showModelIcon) {
                    MessageModelIcon(
                        providerKey = msg.providerId,
                        modelId = msg.model,
                        size = 30.dp,
                    )
                    Spacer(Modifier.width(ChatStyleSpec.ASSISTANT_AVATAR_NAME_GAP_DP.dp))
                }
                Column {
                    // CMW:2807-2820 —— 名字行由 display_show_model_name_v1 门控；文字是
                    // 「助手开了『使用助手名字』→ 助手名，否则模型名」（CMW:2809-2813），
                    // 模型名拿不到（预设消息没有 modelId）时回落助手名。
                    if (timelineSettings.showModelName) {
                        Text(
                            text = if (assistant?.useAssistantName == true) {
                                assistantLabel
                            } else {
                                modelLabel.ifEmpty { assistantLabel }
                            },
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = cs.onSurface.copy(alpha = 0.7f),
                            ),
                        )
                    }
                    // CMW:2821-2834 —— 时间戳由 display_show_model_timestamp_v1 门控。
                    if (timelineSettings.showModelTimestamp) {
                        Spacer(Modifier.height(ChatStyleSpec.NAME_TIME_GAP_DP.dp))
                        Text(
                            text = timeLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                color = cs.onSurface.copy(alpha = 0.5f),
                            ),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(ChatStyleSpec.HEADER_CONTENT_GAP_DP.dp))
        // 长按浮层锚定在气泡上（chat_message_widget.dart:1812-1846 mobile
        // long-press → _showUserContextMenu）。
        Box {
            // CMW:3700-3713 —— 整条消息套一层 _ChatSurfaceTheme：卡片类子组件
            // 通过继承拿到本角色的前景色板；气泡外壳再叠 LocalChatBubbleStyles。
            val surfaceFg = remember(timelineSettings.bubbleStyles, isDark, isUser) {
                com.psyche.memo.ui.chat.computeChatSurfaceFg(cs, isDark, isUser, timelineSettings.bubbleStyles)
            }
            androidx.compose.runtime.CompositionLocalProvider(
                com.psyche.memo.ui.chat.LocalChatSurfaceFg provides surfaceFg,
                com.psyche.memo.ui.chat.LocalChatBubbleStyles provides timelineSettings.bubbleStyles,
            ) {
            Column(
                // 用户侧整体靠右（CMW:1769-1771 `crossAxisAlignment: end`）：附件预览与
                // 正文气泡同列，窄的那个不能因为兄弟更宽就被推到左边去。
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                modifier = Modifier
                    .then(
                        if (isUser) Modifier.widthIn(max = maxBubbleWidth)
                        // 助手块默认撑满整行（CMW:2478-2484
                        // _assistantBlockWidth，assistantBubbleFitContent 默认关）。
                        else Modifier.fillMaxWidth()
                    )
                    .combinedClickable(
                        enabled = isUser,
                        onLongClick = {
                            if (isUser && !selecting) {
                                Haptics.light(rowView)
                                showContextMenu = true
                            }
                        },
                        onClick = {},
                    ),
            ) {
                // 附件（图片 + 文档）渲染在正文气泡**上方**、是气泡的兄弟，
                // 按 part 顺序 Wrap（chat_message_widget.dart `_buildAttachmentPreview`
                // + 用户 CMW:1835-1843 / 助手 CMW:2848-2851）。**别把文档卡塞进气泡**：
                // 卡自身是近不透明的 surface 底，塞进半透明气泡里会变成一块突兀的方块
                // （用户实测「文档发在对话界面渲染有问题」）。
                val userAttachmentParts = msg.parts.filter { it is ImagePart || it is com.psyche.memo.data.model.FilePart }
                if (isUser) {
                    if (userAttachmentParts.isNotEmpty()) {
                        com.psyche.memo.ui.chat.MessageAttachmentPreview(
                            parts = msg.parts,
                            alignEnd = true,
                            onOpenViewer = { uris, index -> viewerState = uris to index },
                        )
                    }
                } else {
                    // 助手侧的图片 / 文档**不再挂在气泡上方**：它们已经是投影里的
                    // [com.psyche.memo.ui.chat.AssistantBlock.Media] 块，按 part 顺序
                    // 在正文之间渲染（用户 2026-09-17「生成结果怎么在上面了」）。
                }
                if (isUser) {
                    // CMW:1752-1765 —— 只有正文非空才有文本气泡；纯图片的用户消息
                    // 不该留一个空底色块（原版 textBubble == null）。
                    val userHasText = msg.parts.any { it is TextPart && it.text.isNotEmpty() }
                    val userContent: @Composable () -> Unit = {
                        for (part in msg.parts) {
                            when (part) {
                                is TextPart -> {
                                    // visual 规则在显示层改写（chat_message_widget.dart L1291）。
                                    val visual = remember(part.text) {
                                        com.psyche.memo.data.model.AssistantRegexApplier.applyAll(
                                            part.text,
                                            assistantRegexRulesCache,
                                            com.psyche.memo.data.model.AssistantRegexScope.USER,
                                            com.psyche.memo.data.model.AssistantRegexApplier.Target.VISUAL,
                                        )
                                    }
                                    if (timelineSettings.enableUserMarkdown) {
                                        // CMW:2046-2054 —— 用户正文 15.5 / 行高 1.45×15.5。
                                        com.psyche.memo.ui.markdown.MarkdownText(
                                            markdown = visual,
                                            baseFontSize = ChatStyleSpec.USER_TEXT_SP,
                                            baseLineHeight = ChatStyleSpec.USER_TEXT_LINE_HEIGHT_SP,
                                            onCitationTap = handleCitationTap,
                                            citationInfoResolver = citationResolver,
                                            codeBlock = codeBlockConfig,
                                            math = mathConfig,
                                        )
                                    } else {
                                        // 关掉 Markdown：同字号/行高的纯文本（CMW:2055-2066）。
                                        Text(
                                            text = visual,
                                            style = TextStyle(
                                                fontSize = ChatStyleSpec.USER_TEXT_SP.sp,
                                                lineHeight = ChatStyleSpec.USER_TEXT_LINE_HEIGHT_SP.sp,
                                                color = com.psyche.memo.ui.chat.chatSurfacePlainTextColor(isUser = true),
                                            ),
                                        )
                                    }
                                }
                                is ImagePart -> Unit // 用户侧附件：整组渲染在气泡上方（见上面 userAttachmentParts）
                                is com.psyche.memo.data.model.FilePart -> Unit // 同上（附件预览）
                                else -> Text("‹${part.kind}›", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    // CMW:2382-2403 _buildBubbleContainer(isUser: true)：整条用户
                    // 正文一个气泡（primary@0.15/0.08 + r16 + 内边距 12）；附件与气泡
                    // 之间 8pt（CMW:1840-1841）。
                    val userHasBubbleContent = userHasText || msg.parts.any {
                        it !is TextPart && it !is ImagePart && it !is com.psyche.memo.data.model.FilePart
                    }
                    if (userHasBubbleContent) {
                        if (userAttachmentParts.isNotEmpty()) Spacer(Modifier.height(8.dp))
                        com.psyche.memo.ui.chat.ChatBubbleSurface(isUser = true) { userContent() }
                    }
                } else {
                    // CMW:2951-3009 —— 文本气泡与思考卡按 part 到达顺序交替出现，
                    // addVisible 在相邻块之间插 8pt；每段文本各自一个气泡
                    // （_buildAssistantTextBubbles，assistantBubbleSplitParagraphs
                    // 打开时按段落再拆）。助手正文 15.7 / 行高 1.5×15.7。
                    assistantBlocks.forEachIndexed { index, block ->
                        // 一次性入场（graphicsLayer alpha+scale 420ms，不改布局高度）**只给卡片与
                        // 媒体**。正文块以前也吃这一套，于是观感变成「一坨一坨往外冒」、字还在
                        // 动画里缩放 —— Agora 里正文根本不走这条路，它做逐字淡入
                        // （`ui/markdown/StreamingGlyphFade.kt`，见该文件注释）。
                        val blockAppearance = if (block is com.psyche.memo.ui.chat.AssistantBlock.Text) {
                            Modifier
                        } else {
                            com.psyche.memo.ui.chat.generationAppearanceModifier(
                                animationKey = "msg-${msg.id}-block-$index",
                                animate = msg.isStreaming,
                            )
                        }
                        Box(modifier = blockAppearance) {
                        if (index > 0) Spacer(Modifier.height(8.dp))
                        when (block) {
                            is com.psyche.memo.ui.chat.AssistantBlock.Media -> {
                                // 媒体块：与正文块同序（工具产出的图紧跟工具卡）。
                                if (block.parts.any { it is ImagePart }) {
                                    com.psyche.memo.ui.chat.ChatBubbleSurface(
                                        isUser = false,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        com.psyche.memo.ui.chat.MessageImageAttachments(
                                            parts = block.parts,
                                            onOpenViewer = { uris, at -> viewerState = uris to at },
                                        )
                                    }
                                }
                                if (block.parts.any { it is com.psyche.memo.data.model.FilePart }) {
                                    com.psyche.memo.ui.chat.MessageAttachmentPreview(
                                        parts = block.parts,
                                        alignEnd = false,
                                        onOpenViewer = { uris, at -> viewerState = uris to at },
                                    )
                                }
                            }
                            is com.psyche.memo.ui.chat.AssistantBlock.Text -> {
                                // visual 规则（chat_message_widget.dart L1276）。
                                val visual = remember(block.text) {
                                    com.psyche.memo.data.model.AssistantRegexApplier.applyAll(
                                        block.text,
                                        assistantRegexRulesCache,
                                        com.psyche.memo.data.model.AssistantRegexScope.ASSISTANT,
                                        com.psyche.memo.data.model.AssistantRegexApplier.Target.VISUAL,
                                    )
                                }
                                // CMW:2505-2520 —— 拆段开关只在助手正文生效。
                                val parts = remember(visual, timelineSettings.assistantBubbleSplitParagraphs) {
                                    if (timelineSettings.assistantBubbleSplitParagraphs) {
                                        com.psyche.memo.ui.chat.splitAssistantParagraphs(visual)
                                    } else {
                                        listOf(visual)
                                    }
                                }
                                parts.forEachIndexed { partIndex, part ->
                                    if (partIndex > 0) Spacer(Modifier.height(8.dp))
                                    // 逐字淡入只给「活的尾部」：这条消息还在生成、且这是最后一个
                                    // 正文块的最后一段。出生表按消息 id remember ⇒ 同一条消息
                                    // 增长期间不被清空（清空＝整段重播）。
                                    val tailFadeScope = remember(msg.id) {
                                        com.psyche.memo.ui.markdown.StreamTailFadeScope(
                                            com.psyche.memo.ui.markdown.StreamTailFadeTracker(),
                                        )
                                    }
                                    val tailFading = msg.isStreaming &&
                                        index == assistantBlocks.lastIndex &&
                                        partIndex == parts.lastIndex
                                    com.psyche.memo.ui.chat.ChatBubbleSurface(
                                        isUser = false,
                                        // CMW:2478-2484 _assistantBlockWidth：
                                        // 贴合内容时不给宽度约束，气泡裹住文字。
                                        modifier = if (timelineSettings.assistantBubbleFitContent) {
                                            Modifier
                                        } else {
                                            Modifier.fillMaxWidth()
                                        },
                                    ) {
                                        if (timelineSettings.enableAssistantMarkdown) {
                                            val markdownBody: @Composable () -> Unit = {
                                                com.psyche.memo.ui.markdown.MarkdownText(
                                                    markdown = part,
                                                    baseFontSize = 15.7f,
                                                    baseLineHeight = 23.55f,
                                                    onCitationTap = handleCitationTap,
                                                    citationInfoResolver = citationResolver,
                                                    tableActions = tableActions,
                                                    codeBlock = codeBlockConfig,
                                                    codeBlockActions = codeBlockActions,
                                                    math = mathConfig,
                                                )
                                            }
                                            if (tailFading) {
                                                androidx.compose.runtime.CompositionLocalProvider(
                                                    com.psyche.memo.ui.markdown.LocalStreamTailFade provides tailFadeScope,
                                                ) { markdownBody() }
                                            } else {
                                                markdownBody()
                                            }
                                        } else {
                                            // 关掉 Markdown：同字号/行高纯文本（CMW:2432-2441）。
                                            Text(
                                                text = part,
                                                style = TextStyle(
                                                    fontSize = 15.7.sp,
                                                    lineHeight = 23.55.sp,
                                                    color = com.psyche.memo.ui.chat.chatSurfacePlainTextColor(),
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                            is com.psyche.memo.ui.chat.AssistantBlock.Thinking ->
                                com.psyche.memo.ui.chat.ChainOfThoughtCard(
                                    steps = block.steps,
                                    settings = timelineSettings,
                                    conversationId = conversationId,
                                    approval = approvalService,
                                    askUser = askUserService,
                                    onRecoveredAnswer = onRecoveredAnswer,
                                    onToggleReasoning = onToggleReasoning,
                                )
                        }
                        }
                    }
                }
                if (msg.failed) {
                    Text(
                        stringResource(UiR.string.generation_interrupted),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.error,
                    )
                }
                // 翻译显示分支（chat_message_widget.dart:3023-3180，显示层；
                // 翻译动作按钮属输入域批次）：primaryContainer 容器 + 可折叠
                // Languages 标题行 + 译文。
                if (!isUser && !msg.translation.isNullOrEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    // CMW:3025-3038 —— 译文卡走同一个 _buildSharedChatSurface：
                    // default 样式用 primaryContainer@0.25(dark)/0.30(light)，
                    // 选了 frosted/solid 时一起换成气泡皮肤。
                    com.psyche.memo.ui.chat.ChatBubbleSurface(
                        isUser = false,
                        modifier = Modifier.fillMaxWidth(),
                        defaultColor = cs.primaryContainer.copy(alpha = if (isDark) 0.25f else 0.30f),
                        padding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { translationExpanded = !translationExpanded }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                            ) {
                                Icon(
                                    Lucide.Languages,
                                    contentDescription = null,
                                    tint = cs.onSurface.copy(alpha = 0.88f),
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(UiR.string.chat_message_widget_translation),
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = cs.onSurface.copy(alpha = 0.88f),
                                    ),
                                )
                                Spacer(Modifier.weight(1f))
                                Icon(
                                    if (translationExpanded) Lucide.ChevronDown else Lucide.ChevronRight,
                                    contentDescription = null,
                                    tint = cs.onSurface.copy(alpha = 0.88f),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            if (translationExpanded) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = msg.translation,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 15.5.sp,
                                        lineHeight = 21.7.sp,
                                        color = cs.onSurface.copy(alpha = 0.85f),
                                    ),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
                // 来源摘要卡（chat_message_widget.dart:3182-3189）。
                if (!isUser && searchItems.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    com.psyche.memo.ui.chat.CitationSourcesSummaryCard(
                        items = searchItems,
                        onTap = { showCitations = true },
                    )
                }
            }
            }
            if (showContextMenu) {
                com.psyche.memo.ui.chat.UserContextMenu(
                    onCopy = onCopy,
                    onEdit = onEdit,
                    onDelete = onDelete,
                    onDismiss = { showContextMenu = false },
                )
            }
        }
        // 全屏图片查看器 + 引用来源 sheet（挂载于消息行级状态）。
        viewerState?.let { (uris, index) ->
            com.psyche.memo.ui.chat.ImageViewerOverlay(
                images = uris,
                initialIndex = index,
                onClose = { viewerState = null },
            )
        }
        if (showCitations) {
            com.psyche.memo.ui.chat.CitationSourcesSheet(
                items = searchItems,
                onDismiss = { showCitations = false },
            )
        }
        // Message actions (user messages): copy / regenerate / edit / more —
        // 28px rounded actions row, right-aligned below the bubble (kelivo
        // chat_message_widget.dart:1847-1974); assistant rows: copy /
        // regenerate / speak / translate / more (CMW:3191-3410), version
        // selector and token stats trail the row.
        val showVersionSwitcher = versionCount > 1
        // CMW:1847-1858 —— 用户侧那一排（复制/重发/编辑/更多）由
        // display_show_user_message_actions_v1 门控；关掉后整行还能因为分支选择器而存在。
        val userActionsVisible = isUser && timelineSettings.showUserMessageActions
        // CMW:1979 —— 多选态隐藏操作行与建议气泡。
        if (!selecting && (!isUser || userActionsVisible || showVersionSwitcher)) {
            // CMW:1848 / 3209 —— 按钮行上方 8（用户与助手一致）。
            Spacer(Modifier.height(ChatStyleSpec.ACTIONS_TOP_GAP_DP.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (userActionsVisible) {
                    MessageActionIcon(Lucide.Copy, "Copy", onClick = onCopy)
                    Spacer(Modifier.width(ChatStyleSpec.ACTION_GAP_DP.dp))
                    MessageActionIcon(
                        Lucide.RefreshCw,
                        "Regenerate",
                        onClick = { onRegenerate?.invoke() },
                        enabled = onRegenerate != null,
                    )
                    Spacer(Modifier.width(ChatStyleSpec.ACTION_GAP_DP.dp))
                    MessageActionIcon(Lucide.Pencil, "Edit", onClick = onEdit)
                    Spacer(Modifier.width(ChatStyleSpec.ACTION_GAP_DP.dp))
                    MessageActionIcon(Lucide.Ellipsis, "More", onClick = onMore)
                }
                if (!isUser) {
                    // 照 Agora（`AssistantMessageContent.kt` 尾部 Box）：这条尾行**常驻**——
                    // 流式时放呼吸圆点、结束后放操作按钮，行本身不折叠/展开
                    // ⇒ 结束时没有任何高度跳变（用户 2026-09-24「最后那排复制出现时不要那样一下」）。
                    if (msg.isStreaming) {
                        Box(modifier = Modifier.height(28.dp), contentAlignment = Alignment.CenterStart) {
                            // 呼吸圆点（颜色跟随主题 primary；自带渐显）。
                            com.psyche.memo.ui.chat.GenerationActivityDot()
                        }
                    } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MessageActionIcon(Lucide.Copy, "Copy", onClick = onCopy)
                                Spacer(Modifier.width(ChatStyleSpec.ACTION_GAP_DP.dp))
                                MessageActionIcon(
                                    Lucide.RefreshCw,
                                    "Regenerate",
                                    // display_show_regenerate_confirm_dialog_v1（默认开）：
                                    // 关闭时跳过确认直接重生成（CMW:1330）。
                                    onClick = {
                                        if (skipRegenerateConfirm) onRegenerateAssistant?.invoke()
                                        else showRegenerateConfirm = true
                                    },
                                    enabled = onRegenerateAssistant != null,
                                )
                                Spacer(Modifier.width(ChatStyleSpec.ACTION_GAP_DP.dp))
                                // Speak/Stop/Resume：只有被朗读的那条消息切图标。
                                MessageActionIcon(
                                    when (ttsAction) {
                                        com.psyche.memo.ui.chat.MessageTtsAction.STOP -> Lucide.CircleStop
                                        com.psyche.memo.ui.chat.MessageTtsAction.RESUME -> Lucide.Play
                                        else -> Lucide.Volume2
                                    },
                                    when (ttsAction) {
                                        com.psyche.memo.ui.chat.MessageTtsAction.STOP -> "Stop"
                                        com.psyche.memo.ui.chat.MessageTtsAction.RESUME -> "Resume"
                                        else -> "Speak"
                                    },
                                    onClick = {
                                        when (ttsAction) {
                                            com.psyche.memo.ui.chat.MessageTtsAction.STOP ->
                                                com.psyche.memo.ui.chat.TtsPlayer.stop()
    
                                            com.psyche.memo.ui.chat.MessageTtsAction.RESUME ->
                                                com.psyche.memo.ui.chat.TtsPlayer.togglePause()
    
                                            com.psyche.memo.ui.chat.MessageTtsAction.SPEAK ->
                                                com.psyche.memo.ui.chat.TtsPlayer.speakAssistantReply(context, msg.content, ownerId = msg.id)
                                        }
                                    },
                                )
                                Spacer(Modifier.width(ChatStyleSpec.ACTION_GAP_DP.dp))
                                MessageActionIcon(
                                    Lucide.Languages,
                                    "Translate",
                                    onClick = { showLanguageSheet = true },
                                )
                                Spacer(Modifier.width(ChatStyleSpec.ACTION_GAP_DP.dp))
                                MessageActionIcon(Lucide.Ellipsis, "More", onClick = onMore)
                            }
                    }
                }
                if (showVersionSwitcher) {
                    if (isUser || !msg.isStreaming) Spacer(Modifier.width(ChatStyleSpec.ACTION_GAP_DP.dp))
                    com.psyche.memo.ui.chat.BranchSelector(
                        index = versionIndex,
                        total = versionCount,
                        onPrev = onPrevVersion,
                        onNext = onNextVersion,
                    )
                }
                if (!isUser && timelineSettings.showTokenStats && msg.totalTokens != null) {
                    // 源码 chat_message_widget.dart:3395-3405 —— Spacer 后
                    // 靠右的 TokenDisplayWidget（display_show_token_stats_v1 门控）。
                    Spacer(Modifier.weight(1f))
                    com.psyche.memo.ui.chat.TokenDisplay(
                        totalTokens = msg.totalTokens,
                        promptTokens = msg.promptTokens,
                        completionTokens = msg.completionTokens,
                        cachedTokens = msg.cachedTokens,
                        durationMs = msg.durationMs,
                    )
                }
            }
            // 建议气泡（chat_message_widget.dart:3410-3418）—— 最后一条助手
            // 消息、非流式时显示。
            if (!selecting && !isUser && !msg.isStreaming && suggestions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                com.psyche.memo.ui.chat.ChatSuggestionBubbles(
                    suggestions = suggestions,
                    onTap = onSuggestionTap,
                )
            }
        }
    }
    }
    if (showLanguageSheet) {
        com.psyche.memo.ui.chat.LanguageSelectSheet(
            onSelect = { lang ->
                showLanguageSheet = false
                onTranslate(lang.code)
            },
            onDismiss = { showLanguageSheet = false },
        )
    }
    if (showRegenerateConfirm) {
        // CMW:1328-1358 _confirmRegeneration —— 确认弹窗（标题/正文/取消/确定）。
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showRegenerateConfirm = false },
            title = {
                Text(stringResource(UiR.string.chat_message_widget_regenerate_confirm_title))
            },
            text = {
                Text(stringResource(UiR.string.chat_message_widget_regenerate_confirm_content))
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showRegenerateConfirm = false
                        onRegenerateAssistant?.invoke()
                    },
                ) { Text(stringResource(UiR.string.chat_message_widget_regenerate_confirm_ok)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showRegenerateConfirm = false },
                ) { Text(stringResource(UiR.string.chat_message_widget_regenerate_confirm_cancel)) }
            },
        )
    }
}

@Composable
private fun MessageActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    // CMW:1859-1959 —— 28×28 槽位内裸 IosIconButton(16, pad4)，无背景，
    // 色 onSurface@0.9；禁用态 alpha×0.45（ios_tactile.dart:54-59）。
    val view = LocalView.current
    Box(
        modifier = Modifier
            .size(ChatStyleSpec.ACTION_SLOT_DP.dp)
            .clickable(enabled = enabled) {
                // chat_message_widget.dart L3961: menu-row taps tick.
                Haptics.light(view)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = cs.onSurface.copy(
                alpha = if (enabled) ChatStyleSpec.ACTION_ICON_ALPHA
                else ChatStyleSpec.ACTION_DISABLED_ALPHA,
            ),
            modifier = Modifier.size(ChatStyleSpec.ACTION_ICON_DP.dp),
        )
    }
}

/**
 * 组合次数探针 —— **诊断/测试用**，`MessageRow` 每组合一次 +1。
 *
 * 为什么值得留在生产代码里：聊天页的状态变化（`pointerDown` / `following` /
 * `navVisible` / 输入框内容…）**不该**重组合消息行 —— 这正是用户 2026-09-15
 * 「点一下『到底部』整个对话界面闪一下」这类问题的判据。`ChatRowRecompositionTest`
 * 用它断言「碰列表/打字都不会重组合消息行」，哪天某个 `unstable` 实参把 `MessageRow`
 * 的 skippable 打破，测试立刻红。计数器是一次 int 自增，可以忽略不计。
 */
internal object ChatRecompositionProbe {
    @Volatile var messageRows = 0

    fun reset() {
        messageRows = 0
    }
}

// 线程安全的 java.time formatter（lint ConstantLocale：SimpleDateFormat 静态持有
// Locale.getDefault() 在运行时切语言后行为不正确）。
private val TIME_FORMATTER: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withLocale(java.util.Locale.getDefault())

internal fun timeStr(millis: Long): String =
    TIME_FORMATTER.format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()))


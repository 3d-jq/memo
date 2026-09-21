package com.psyche.memo.ui.chat

import com.psyche.memo.ui.theme.MemoRadius
import com.psyche.memo.ui.overlaySurfaceColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.BookDashed
import com.composables.icons.lucide.BookHeart
import com.composables.icons.lucide.Calculator
import com.composables.icons.lucide.Calendar
import com.composables.icons.lucide.CalendarPlus
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Clipboard
import com.composables.icons.lucide.ClipboardCheck
import com.composables.icons.lucide.ClipboardPen
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.CloudSun
import com.composables.icons.lucide.Code
import com.composables.icons.lucide.Earth
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Video
import com.composables.icons.lucide.HeartPulse
import com.composables.icons.lucide.Link
import com.composables.icons.lucide.ListPlus
import com.composables.icons.lucide.ListTodo
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Puzzle
import com.composables.icons.lucide.MapPin
import com.composables.icons.lucide.MessageCircleQuestion
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Shield
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Terminal
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.Wrench
import com.composables.icons.lucide.X
import com.psyche.memo.common.IcuPlural
import com.psyche.memo.ui.BuiltInToolCatalog.LocalToolNames
import com.psyche.memo.ui.ChatStyleSpec
import com.psyche.memo.ui.IosIconButton
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.theme.AppFontWeights
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Tool call rendering — 1:1 port of chat_message_widget.dart tool surfaces:
 * `_ChainOfThoughtToolStep` (5242-5588) inside the chain-of-thought timeline,
 * `_ToolCallItem` (5590-5934) as the boxed card used by `role == tool`
 * messages, and `_showToolDetail` (695-770) + tool_detail_text_section.dart
 * for the detail sheet.
 *
 * Tool result image strips (`parseToolResultImages` + ImageViewerPage) and
 * the ask-user surfaces (`_AskUserToolCard` / `_AskUserInlineBody`) are
 * ported (AskUserCard.kt / ToolResultImageStrip.kt). Still deferred to the
 * tool-execution batch, where the data first becomes reachable: approval
 * pending state (Shield icon, deny/approve buttons, `_argsSummary` — the
 * pure helper itself is ported in ToolResultImages.kt).
 */

/** UI data for a tool call — mirrors chat_message_widget.dart ToolUIPart. */
data class ToolUiPart(
    val id: String,
    val toolName: String,
    val arguments: JsonObject,
    val content: String?,
    val metadata: JsonObject?,
    /**
     * payload `images` 键里的工具结果图片（本工程新增，工作区 `workspace_read_file`
     * 读图片时写入）；与正文里的 markdown 图片标记合并成同一条图片横滚条。
     */
    val attachedImages: List<String> = emptyList(),
    /** Dart 侧是显式字段（默认 false）；流式 payload 没有它，按 content 推断。 */
    val loading: Boolean = content.isNullOrEmpty(),
) {
    /** chat_message_widget.dart `parseToolResultImages(content).$1` —— 剥离整行图片标记后的正文。 */
    val cleanText: String by lazy { parseToolResultImages(content).first }

    /** chat_message_widget.dart `parseToolResultImages(content).$2` —— 首见去重后的图片路径。 */
    val imagePaths: List<String> by lazy { parseToolResultImages(content).second }

    /** 实际渲染的图片：payload 附件在前，正文 markdown 图片在后。 */
    val allImagePaths: List<String> get() = attachedImages + imagePaths

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** chat_message_widget.dart toolUiFromPayload — 解析失败返回 null。 */
        fun fromPayload(payloadJson: String, fallbackOrdinal: Int = 0): ToolUiPart? {
            val obj = try { json.parseToJsonElement(payloadJson).jsonObject } catch (e: Exception) { return null }
            var id = obj.str("id").orEmpty()
            val name = obj.str("name").orEmpty()
            if (id.isEmpty()) id = "${if (name.isEmpty()) "tool" else name}-$fallbackOrdinal"
            return ToolUiPart(
                id = id,
                toolName = name,
                arguments = obj["arguments"] as? JsonObject ?: JsonObject(emptyMap()),
                content = obj.str("content"),
                metadata = obj["metadata"] as? JsonObject,
                attachedImages = (obj["images"] as? JsonArray)
                    ?.mapNotNull { element -> (element as? JsonObject)?.str("uri") }
                    .orEmpty(),
            )
        }

        /**
         * chat_message_widget.dart `_buildToolMessage` 1662-1686：`role == tool`
         * 的消息正文本身就是 `{tool, arguments, result, metadata}`，且结果已定，
         * 所以 loading 恒为 false。解析失败返回 null。
         */
        fun fromToolMessage(messageId: String, content: String): ToolUiPart? {
            val obj = try { json.parseToJsonElement(content).jsonObject } catch (e: Exception) { return null }
            return ToolUiPart(
                id = messageId,
                toolName = obj.str("tool") ?: "tool",
                arguments = obj["arguments"] as? JsonObject ?: JsonObject(emptyMap()),
                content = obj.str("result").orEmpty(),
                metadata = obj["metadata"] as? JsonObject,
                loading = false,
            )
        }
    }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it !is JsonNull }?.content

// ---------------------------------------------------------------------------
// Icon / title mapping (chat_message_widget.dart 423-640)
// ---------------------------------------------------------------------------

/** chat_message_widget.dart _toolIconFor —— 按线上工具名（LocalToolNames）匹配。 */
fun toolIconFor(name: String, args: JsonObject? = null): ImageVector {
    localToolIconFor(name, args)?.let { return it }
    return when (name) {
        // 加载技能：与设置→技能页同一个 Puzzle 图标（RikkaHub 用 MagicWand01，
        // icons-lucide 1.1.0 没有那个图标；技能域内保持同一个图标更重要）。
        com.psyche.memo.provider.SkillTools.USE_SKILL -> Lucide.Puzzle
        "memory_read", "memory_update", "memory_search_profile", "memory_edit",
        "update_user_profile", "create_memory", "edit_memory",
        -> Lucide.BookHeart
        "memory_delete", "delete_memory" -> Lucide.BookDashed
        "chat_search", "builtin_search" -> Lucide.Search
        "search_web" -> Lucide.Earth
        // 生成工具（自研功能）：与设置里两个入口同一个图标语言。
        com.psyche.memo.provider.generation.GenerationTools.GENERATE_IMAGE -> Lucide.Image
        com.psyche.memo.provider.generation.GenerationTools.GENERATE_VIDEO -> Lucide.Video
        // Provider 内置服务端工具（chat_message_widget.dart:444-453）。
        "web_fetch" -> Lucide.Link
        "code_execution", "code_interpreter", "text_editor_code_execution" -> Lucide.Code
        "bash_code_execution" -> Lucide.Terminal
        else -> Lucide.Wrench
    }
}

/** chat_message_widget.dart _localToolIconFor。 */
private fun localToolIconFor(name: String, args: JsonObject?): ImageVector? =
    when (name) {
        // icons-lucide 1.1.0 尚未收录 MessageCircleQuestionMark，用同名图标的前代。
        LocalToolNames.ASK_USER -> Lucide.MessageCircleQuestion
        LocalToolNames.TIME_INFO -> Lucide.Clock
        LocalToolNames.CLIPBOARD -> when (args?.str("action")) {
            "read" -> Lucide.ClipboardCheck
            "write" -> Lucide.ClipboardPen
            else -> Lucide.Clipboard
        }
        LocalToolNames.TEXT_TO_SPEECH -> Lucide.Volume2
        LocalToolNames.CALCULATE -> Lucide.Calculator
        LocalToolNames.SCREEN_TIME -> Lucide.Smartphone
        LocalToolNames.CALENDAR_QUERY -> Lucide.Calendar
        LocalToolNames.CALENDAR_CREATE -> Lucide.CalendarPlus
        LocalToolNames.CURRENT_LOCATION -> Lucide.MapPin
        LocalToolNames.WEATHER -> Lucide.CloudSun
        LocalToolNames.HEALTH_SUMMARY -> Lucide.HeartPulse
        LocalToolNames.REMINDERS_QUERY -> Lucide.ListTodo
        LocalToolNames.REMINDERS_CREATE -> Lucide.ListPlus
        LocalToolNames.REMINDERS_COMPLETE -> Lucide.CircleCheck
        else -> null
    }

/** chat_message_widget.dart _toolTitleFor。 */
@Composable
fun toolTitleFor(name: String, args: JsonObject?, isResult: Boolean): String {
    if (name == LocalToolNames.ASK_USER) return askUserToolTitleFor(args)
    localToolTitleFor(name, args)?.let { return it }
    return when (name) {
        // 加载技能：照 RikkaHub `UseSkillToolUI.title` —— "Skill: <技能名>"（带 path 时
        // 追加 " / <路径>"）。不这么写就会落到默认的「调用工具 <工具名>」，看起来像
        // 随便调了个工具，而不是"正在加载技能"。
        com.psyche.memo.provider.SkillTools.USE_SKILL -> skillToolTitleFor(args)
        "memory_read" -> stringResource(UiR.string.chat_message_widget_memory_read)
        "memory_update" -> stringResource(UiR.string.chat_message_widget_memory_update)
        "memory_search_profile" -> stringResource(UiR.string.chat_message_widget_memory_search_profile)
        "memory_edit", "edit_memory" -> stringResource(UiR.string.chat_message_widget_memory_edit)
        "memory_delete", "delete_memory" -> stringResource(UiR.string.chat_message_widget_memory_delete)
        "update_user_profile" -> stringResource(UiR.string.chat_message_widget_update_user_profile)
        "chat_search" -> stringResource(UiR.string.chat_message_widget_chat_search)
        "create_memory" -> stringResource(UiR.string.chat_message_widget_create_memory)
        "search_web" -> stringResource(UiR.string.chat_message_widget_web_search, args?.str("query").orEmpty())
        // 生成工具：标题带提示词（照 search_web 带 query 的写法）。
        com.psyche.memo.provider.generation.GenerationTools.GENERATE_IMAGE -> stringResource(
            UiR.string.chat_message_widget_generate_image,
            args?.str("prompt").orEmpty(),
        )
        com.psyche.memo.provider.generation.GenerationTools.GENERATE_VIDEO -> stringResource(
            UiR.string.chat_message_widget_generate_video,
            args?.str("prompt").orEmpty(),
        )
        "builtin_search" -> stringResource(UiR.string.chat_message_widget_builtin_search)
        else -> stringResource(
            if (isResult) UiR.string.chat_message_widget_tool_result
            else UiR.string.chat_message_widget_tool_call,
            if (name.isEmpty()) "tool" else name,
        )
    }
}

/** chat_message_widget.dart _askUserToolTitleFor。 */
@Composable
internal fun askUserToolTitleFor(args: JsonObject?): String {
    val questions = normalizeAskUserQuestions(args ?: JsonObject(emptyMap()))
    if (questions.isNotEmpty()) {
        return IcuPlural.format(
            stringResource(UiR.string.ask_user_card_question_count),
            mapOf("count" to questions.size),
        )
    }
    return stringResource(UiR.string.assistant_edit_local_tool_ask_user_title)
}

/**
 * 加载技能的标题（RikkaHub `UseSkillToolUI.title`）：`Skill: <技能名>`，带 `path`
 * 时追加 ` / <路径>`。技能名缺失时退回 `skill`，别让卡片出现空的 "Skill: "。
 */
@Composable
private fun skillToolTitleFor(args: JsonObject?): String =
    skillToolTitle(
        base = stringResource(UiR.string.chat_message_widget_skill, skillNameFrom(args)),
        path = args?.str("path"),
    )

/** `use_skill` 的技能名（缺失/空白时退回 `skill`）。 */
internal fun skillNameFrom(args: JsonObject?): String =
    args?.str("name").orEmpty().ifBlank { "skill" }

/** 技能标题的拼装形状：有 path 就 `"<前缀+技能名> / <路径>"`（1:1 上游）。 */
internal fun skillToolTitle(base: String, path: String?): String =
    if (path.isNullOrBlank()) base else "$base / $path"

/** chat_message_widget.dart _localToolTitleFor。 */
@Composable
private fun localToolTitleFor(name: String, args: JsonObject?): String? = when (name) {
    LocalToolNames.TIME_INFO -> stringResource(UiR.string.assistant_edit_local_tool_time_info_title)
    LocalToolNames.CLIPBOARD -> when (args?.str("action")) {
        "read" -> stringResource(UiR.string.chat_message_widget_read_clipboard)
        "write" -> stringResource(UiR.string.chat_message_widget_write_clipboard)
        else -> stringResource(UiR.string.assistant_edit_local_tool_clipboard_title)
    }
    LocalToolNames.TEXT_TO_SPEECH -> stringResource(UiR.string.chat_message_widget_speaking_title)
    LocalToolNames.CALCULATE -> stringResource(UiR.string.assistant_edit_local_tool_calculate_title)
    LocalToolNames.SCREEN_TIME -> stringResource(UiR.string.assistant_edit_local_tool_screen_time_title)
    LocalToolNames.CALENDAR_QUERY -> stringResource(UiR.string.assistant_edit_local_tool_calendar_query_title)
    LocalToolNames.CALENDAR_CREATE -> stringResource(UiR.string.assistant_edit_local_tool_calendar_create_title)
    LocalToolNames.CURRENT_LOCATION -> stringResource(UiR.string.assistant_edit_local_tool_location_title)
    LocalToolNames.WEATHER -> stringResource(UiR.string.assistant_edit_local_tool_weather_title)
    LocalToolNames.HEALTH_SUMMARY -> stringResource(UiR.string.assistant_edit_local_tool_health_title)
    LocalToolNames.REMINDERS_QUERY -> stringResource(UiR.string.assistant_edit_local_tool_reminders_query_title)
    LocalToolNames.REMINDERS_CREATE -> stringResource(UiR.string.assistant_edit_local_tool_reminders_create_title)
    LocalToolNames.REMINDERS_COMPLETE -> stringResource(UiR.string.assistant_edit_local_tool_reminders_complete_title)
    else -> null
}

// ---------------------------------------------------------------------------
// Timeline tool step (chat_message_widget.dart _ChainOfThoughtToolStep)
// ---------------------------------------------------------------------------

/**
 * 时间线里的一行工具调用：轨道图标（18dp 位，加载态是 3×2/12dp 的呼吸点）、
 * 13sp 标题（加载态带呼吸高光）、行尾 ChevronRight（ask-user 换成上下箭头），
 * 正文按 ask-user → TTS → 屏幕时间 → 天气 → 纯文本摘要的优先级取一种。
 * 点标题打开详情弹层（ask-user 改为折叠/展开）。
 */
@Composable
fun ChainOfThoughtToolStep(
    part: ToolUiPart,
    isFirst: Boolean,
    isLast: Boolean,
    showToolResultSummary: Boolean,
    hideToolResultImages: Boolean = false,
    conversationId: String? = null,
    approval: ToolApprovalService? = null,
    askUser: AskUserInteractionService? = null,
    onSubmitAskUser: ((AskUserResult) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val fg = chatSurfaceFg()
    val isAskUser = part.toolName == LocalToolNames.ASK_USER
    val loading = part.loading
    // CMW:5388-5398 —— 待审批时（loading 且命中请求）在轨道位显示工具图标、行尾加
    // X/Check extra 按钮，摘要换参数摘要。
    val pendingRequests by remember(approval) {
        approval?.pendingRequests ?: MutableStateFlow<List<ToolApprovalRequest>>(emptyList())
    }.collectAsState()
    val pendingRequest = matchingApprovalRequest(pendingRequests, conversationId, part.id)
    val isPendingApproval = pendingRequest != null
    // _askUserExpanded 默认 true（`_askUserExpanded ?? true`）。
    var askUserExpanded by rememberSaveable { mutableStateOf(true) }
    var showDetail by remember { mutableStateOf(false) }
    var viewerState by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }

    val icon: @Composable () -> Unit = if (isAskUser || !loading || isPendingApproval) {
        @Composable {
            Icon(
                imageVector = toolIconFor(part.toolName, part.arguments),
                contentDescription = null,
                tint = fg.strong,
                modifier = Modifier.size(16.dp),
            )
        }
    } else {
        @Composable {
            LoadingDotsIndicator(
                color = fg.strong,
                dotDp = ChatStyleSpec.TOOL_LOADING_DOTS_DOT_DP,
                gapDp = ChatStyleSpec.TOOL_LOADING_DOTS_GAP_DP,
                heightDp = ChatStyleSpec.TOOL_LOADING_DOTS_HEIGHT_DP,
            )
        }
    }

    val label: @Composable () -> Unit = {
        Text(
            text = toolTitleFor(part.toolName, part.arguments, isResult = !loading),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontSize = ChatStyleSpec.TIMELINE_LABEL_SP.sp,
                fontWeight = AppFontWeights.semibold,
                color = fg.strong,
            ),
            modifier = Modifier.thinkingSheen(fg.strong, isDark, enabled = loading && !isAskUser),
        )
    }

    // CMW:5282-5286 —— 未答 → 已答 时自动重新展开（didUpdateWidget 等价）。
    val answered = part.content?.trim()?.isNotEmpty() == true && !loading
    var wasAnswered by remember(part.id) { mutableStateOf(answered) }
    LaunchedEffect(answered) {
        if (isAskUser && !wasAnswered && answered) askUserExpanded = true
        wasAnswered = answered
    }

    // CMW:5457-5526 —— ask-user 时正文整块换成 _AskUserInlineBody；否则按摘要
    // 优先级链取一种，再在摘要下方挂工具结果图片横滚条（120/240 常量）。
    val askUserBody: (@Composable () -> Unit)? = if (isAskUser) {
        { AskUserInlineBody(part = part, onSubmit = onSubmitAskUser, askUser = askUser) }
    } else {
        null
    }
    val summaryContent: (@Composable () -> Unit)? = askUserBody ?: toolStepSummary(
        part = part,
        fg = fg,
        errorColor = cs.error,
        isAskUser = isAskUser,
        showToolResultSummary = showToolResultSummary,
        pendingApproval = pendingRequest,
    )
    val imageStrip: (@Composable () -> Unit)? =
        if (!isAskUser && !hideToolResultImages && part.allImagePaths.isNotEmpty()) {
            {
                ToolResultImageStrip(
                    paths = part.allImagePaths,
                    height = ChatStyleSpec.TOOL_IMAGE_TIMELINE_HEIGHT_DP.dp,
                    maxWidth = ChatStyleSpec.TOOL_IMAGE_TIMELINE_MAX_WIDTH_DP.dp,
                    // CMW:5501 / 667-672 —— 点击只开单张（ImageViewerPage(images: [path])）。
                    onOpenViewer = { paths, index -> viewerState = listOf(paths[index]) to 0 },
                )
            }
        } else {
            null
        }
    val content: (@Composable () -> Unit)? =
        if (summaryContent == null && imageStrip == null) {
            null
        } else {
            {
                Column(horizontalAlignment = Alignment.Start) {
                    if (summaryContent != null) summaryContent()
                    if (summaryContent != null && imageStrip != null) {
                        Spacer(Modifier.height(8.dp))
                    }
                    if (imageStrip != null) imageStrip()
                }
            }
        }

    val indicator: @Composable () -> Unit = if (isAskUser) {
        @Composable {
            Icon(
                imageVector = if (askUserExpanded) Lucide.ChevronUp else Lucide.ChevronDown,
                contentDescription = null,
                tint = fg.muted,
                modifier = Modifier.size(16.dp),
            )
        }
    } else {
        @Composable {
            Icon(
                imageVector = Lucide.ChevronRight,
                contentDescription = null,
                tint = fg.muted,
                modifier = Modifier.size(16.dp),
            )
        }
    }

    val onToggleAskUser: () -> Unit = { askUserExpanded = !askUserExpanded }
    val onOpenDetail: () -> Unit = { showDetail = true }

    // CMW:5528-5561 的「审批中行尾 X/Check」不再内联：待审批时由输入栏位置的审批面板
    // 负责（用户 2026-09-14「工具权限确认这个…应该出现在输入框那个位置」）。
    val extra: (@Composable () -> Unit)? = null

    TimelineStepShell(
        icon = icon,
        label = label,
        isFirst = isFirst,
        isLast = isLast,
        fg = fg,
        isDark = isDark,
        onTap = if (isAskUser) onToggleAskUser else onOpenDetail,
        extra = extra,
        indicator = indicator,
        content = content,
        contentVisible = content != null && (!isAskUser || askUserExpanded),
        expectContent = loading || isPendingApproval || isAskUser || content != null,
    )

    if (showDetail) {
        ToolDetailSheet(part = part, onDismiss = { showDetail = false })
    }
    viewerState?.let { (paths, index) ->
        ImageViewerOverlay(
            images = paths,
            initialIndex = index,
            onClose = { viewerState = null },
        )
    }
}

/**
 * CMW:5457-5488 的摘要优先级（ask-user 分支在 ChainOfThoughtToolStep 里先被
 * 替换成 _AskUserInlineBody，这里的 isAskUser 分支只是兜底）。
 */
@Composable
private fun toolStepSummary(
    part: ToolUiPart,
    fg: ChatSurfaceFg,
    errorColor: Color,
    isAskUser: Boolean,
    showToolResultSummary: Boolean,
    pendingApproval: ToolApprovalRequest?,
): (@Composable () -> Unit)? {
    if (isAskUser) return null
    val cleanText = part.cleanText
    val ttsText = if (part.toolName == LocalToolNames.TEXT_TO_SPEECH) {
        textToSpeechToolText(part.arguments)
    } else {
        ""
    }
    val screenTime = if (part.toolName == LocalToolNames.SCREEN_TIME) {
        ScreenTimeResult.tryParse(cleanText)
    } else {
        null
    }
    val weather = if (part.toolName == LocalToolNames.WEATHER) {
        WeatherToolResult.tryParse(cleanText)
    } else {
        null
    }
    // CMW:5443-5451 —— 审批中摘要换参数摘要（approvalRequest.arguments）。
    val summaryText = if (pendingApproval != null) {
        argsSummary(pendingApproval.arguments)
    } else if (cleanText.isNotEmpty()) {
        cleanText
    } else {
        part.arguments.str("query") ?: part.arguments.str("url") ?: part.arguments.str("text") ?: ""
    }

    val ttsSummary: (@Composable () -> Unit)? = if (ttsText.isNotEmpty()) {
        {
            TextToSpeechReplayRow(
                text = ttsText,
                textColor = fg.body,
                buttonColor = fg.accent,
            )
        }
    } else {
        null
    }
    val screenTimeSummary: (@Composable () -> Unit)? =
        if (screenTime != null && (screenTime.isNoPermission || screenTime.hasApps)) {
            {
                ScreenTimeToolSummary(
                    result = screenTime,
                    textColor = fg.body,
                    secondaryColor = fg.muted,
                    errorColor = errorColor,
                )
            }
        } else {
            null
        }
    val weatherSummary: (@Composable () -> Unit)? = if (weather != null && !weather.isError) {
        { WeatherToolSummary(result = weather, textColor = fg.body) }
    } else {
        null
    }
    val textSummary: (@Composable () -> Unit)? =
        if (showToolResultSummary && summaryText.isNotBlank()) {
            {
                Text(
                    text = summaryText.trim(),
                    maxLines = if (pendingApproval != null) 2 else 4,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontSize = 12.sp,
                        lineHeight = 16.8.sp,
                        fontFamily = if (pendingApproval != null) FontFamily.Monospace else null,
                        color = fg.body,
                    ),
                )
            }
        } else {
            null
        }
    return ttsSummary ?: screenTimeSummary ?: weatherSummary ?: textSummary
}

// ---------------------------------------------------------------------------
// Boxed inline card (chat_message_widget.dart _ToolCallItem, mobile)
// ---------------------------------------------------------------------------

/** chat_message_widget.dart `_matchingApprovalRequest` 4360-4372 —— 列表版 pendingFor 匹配。 */
internal fun matchingApprovalRequest(
    requests: List<ToolApprovalRequest>,
    conversationId: String?,
    toolCallId: String?,
): ToolApprovalRequest? {
    if (toolCallId.isNullOrEmpty()) return null
    val scopedId = conversationId?.trim().orEmpty()
    if (scopedId.isNotEmpty()) {
        return requests.firstOrNull { it.toolCallId == toolCallId && it.conversationId == scopedId }
            ?: requests.firstOrNull { it.toolCallId == toolCallId && it.conversationId == null }
    }
    val matches = requests.filter { it.toolCallId == toolCallId }
    if (matches.size == 1) return matches.single()
    return matches.firstOrNull { it.conversationId == null }
}

/**
 * `role == tool` 消息里的独立工具卡：18dp 状态位（approval pending → Shield，
 * 否则 loading 用 2dp 圆环，颜色 fg.accent）+ 13sp emphasis 标题（加载态呼吸高光，
 * 审批中 accent 标题 + "Waiting for approval" 副标题）+ TTS / 天气 / 屏幕时间专属
 * 摘要 + 审批态的参数摘要框与 Deny/Approve 按钮。整卡 16dp 圆角、按压 260ms，审批中
 * 禁点开详情、其余点开详情弹层。ask-user 整卡换 [AskUserToolCard]。
 */
@Composable
fun ToolCallCard(
    part: ToolUiPart,
    hideToolResultImages: Boolean = false,
    conversationId: String? = null,
    approval: ToolApprovalService? = null,
    askUser: AskUserInteractionService? = null,
    onRecoveredAnswer: ((ToolUiPart, AskUserResult) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val fg = chatSurfaceFg()
    var showDetail by remember { mutableStateOf(false) }
    var showDeny by remember { mutableStateOf(false) }
    var viewerState by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }

    // CMW:5686-5688 —— ask-user 整卡换 _AskUserToolCard；onSubmit 只接恢复路径
    // （无进行中请求时），askUser 供 inline body 直接路由 service.answer。
    if (part.toolName == LocalToolNames.ASK_USER) {
        AskUserToolCard(
            part = part,
            onSubmit = onRecoveredAnswer?.let { cb -> { result -> cb(part, result) } },
            askUser = askUser,
        )
        return
    }

    val loading = part.loading
    // CMW:5691-5700 —— 提交审批回调选择器：loading 时按 (conversationId, id) 取待批请求。
    val pendingRequests by remember(approval) {
        approval?.pendingRequests ?: MutableStateFlow<List<ToolApprovalRequest>>(emptyList())
    }.collectAsState()
    val pendingRequest = matchingApprovalRequest(pendingRequests, conversationId, part.id)
    val isPendingApproval = pendingRequest != null
    val ttsText = if (part.toolName == LocalToolNames.TEXT_TO_SPEECH) {
        textToSpeechToolText(part.arguments)
    } else {
        ""
    }

    CardPress(
        onTap = if (isPendingApproval) {
            null
        } else {
            { showDetail = true }
        },
        isDark = isDark,
        modifier = Modifier.fillMaxWidth(),
        radius = 16.dp,
        durationMs = 260,
    ) {
        // _buildSharedChatSurface(defaultColor: primaryContainer α .25/.30)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    cs.primaryContainer.copy(
                        alpha = if (isDark) {
                            ChatStyleSpec.TIMELINE_CARD_ALPHA_DARK
                        } else {
                            ChatStyleSpec.TIMELINE_CARD_ALPHA_LIGHT
                        },
                    ),
                    RoundedCornerShape(MemoRadius.INNER_DP.dp),
                )
                .padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        isPendingApproval -> Icon(
                            imageVector = Lucide.Shield,
                            contentDescription = null,
                            tint = fg.accent,
                            modifier = Modifier.size(18.dp),
                        )
                        loading -> CircularProgressIndicator(
                            color = fg.accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                        else -> Icon(
                            imageVector = toolIconFor(part.toolName, part.arguments),
                            contentDescription = null,
                            tint = fg.strong,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text(
                        text = toolTitleFor(
                            part.toolName,
                            part.arguments,
                            isResult = !loading && !isPendingApproval,
                        ),
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = AppFontWeights.emphasis,
                            color = if (isPendingApproval) fg.accent else fg.strong,
                        ),
                        modifier = Modifier.thinkingSheen(
                            fg.strong,
                            isDark,
                            enabled = loading && !isPendingApproval,
                        ),
                    )
                    // "Waiting for approval" subtitle
                    if (isPendingApproval) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(UiR.string.tool_approval_pending),
                            style = TextStyle(
                                fontSize = 11.sp,
                                fontWeight = AppFontWeights.medium,
                                color = fg.medium,
                            ),
                        )
                    }
                }
            }
            if (ttsText.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                TextToSpeechReplayRow(
                    text = ttsText,
                    textColor = fg.body,
                    buttonColor = fg.accent,
                )
            }
            if (!loading && !isPendingApproval && part.toolName == LocalToolNames.WEATHER) {
                val weather = WeatherToolResult.tryParse(part.content)
                if (weather != null && !weather.isError) {
                    Spacer(Modifier.height(8.dp))
                    WeatherToolSummary(result = weather, textColor = fg.body)
                }
            }
            if (!loading && !isPendingApproval && part.toolName == LocalToolNames.SCREEN_TIME) {
                val screenTime = ScreenTimeResult.tryParse(part.content)
                if (screenTime != null && (screenTime.isNoPermission || screenTime.hasApps)) {
                    Spacer(Modifier.height(8.dp))
                    ScreenTimeToolSummary(
                        result = screenTime,
                        textColor = fg.body,
                        secondaryColor = fg.muted,
                        errorColor = cs.error,
                    )
                }
            }
            // Argument summary so users know what the tool is about to do
            if (isPendingApproval && part.arguments.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            cs.onSurface.copy(alpha = if (isDark) 0.06f else 0.04f),
                            RoundedCornerShape(MemoRadius.SMALL_DP.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = argsSummary(part.arguments),
                        style = TextStyle(
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = fg.body,
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 审批按钮不再内联：待审批时由**输入栏位置的审批面板**负责
            // （用户 2026-09-14「工具权限确认这个…应该出现在输入框那个位置」），
            // 这里只保留参数摘要，让人看得见"要做什么"。
            // CMW:5905-5929 —— 工具结果图片横滚条（180/320 常量），点击只开单张。
            if (!hideToolResultImages && part.allImagePaths.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                ToolResultImageStrip(
                    paths = part.allImagePaths,
                    height = ChatStyleSpec.TOOL_IMAGE_CARD_HEIGHT_DP.dp,
                    maxWidth = ChatStyleSpec.TOOL_IMAGE_CARD_MAX_WIDTH_DP.dp,
                    onOpenViewer = { paths, index -> viewerState = listOf(paths[index]) to 0 },
                )
            }
        }
    }

    if (showDeny && pendingRequest != null && approval != null) {
        ApprovalDenyDialog(
            approval = approval,
            toolCallId = pendingRequest.toolCallId,
            conversationId = pendingRequest.conversationId,
            onDismiss = { showDeny = false },
        )
    }
    if (showDetail) {
        ToolDetailSheet(part = part, onDismiss = { showDetail = false })
    }
    viewerState?.let { (paths, index) ->
        ImageViewerOverlay(
            images = paths,
            initialIndex = index,
            onClose = { viewerState = null },
        )
    }
}

/** _ApprovalButton (chat_message_widget.dart 6769-6814)：高 36 居中，filled 用色浸底、
 * 否则透明；描边 α filled?0.5:0.35，文字 13 semibold，禁用时文字半透明。 */
@Composable
private fun ApprovalButton(
    label: String,
    color: Color,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val enabled = onTap != null
    CardPress(
        onTap = onTap,
        isDark = isDark,
        modifier = modifier,
        radius = 10.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(
                    if (filled) color.copy(alpha = if (isDark) 0.25f else 0.15f) else Color.Transparent,
                    RoundedCornerShape(MemoRadius.SMALL_DP.dp),
                )
                .border(
                    width = 1.dp,
                    color = color.copy(alpha = if (filled) 0.5f else 0.35f),
                    shape = RoundedCornerShape(MemoRadius.SMALL_DP.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = AppFontWeights.semibold,
                    color = if (enabled) color else color.copy(alpha = 0.45f),
                ),
            )
        }
    }
}

/** _showDenyDialog (chat_message_widget.dart 5333-5372 / 5936-5975)：AlertDialog + 备注
 * 输入，确认时 trim 后空则无原因，直接 deny。 */
@Composable
private fun ApprovalDenyDialog(
    approval: ToolApprovalService,
    toolCallId: String,
    conversationId: String?,
    onDismiss: () -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(UiR.string.tool_approval_deny_title)) },
        text = {
            BasicTextField(
                value = reason,
                onValueChange = { reason = it },
                modifier = Modifier.focusRequester(focusRequester).fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium,
                decorationBox = { inner ->
                    Box {
                        if (reason.isEmpty()) {
                            Text(
                                text = stringResource(UiR.string.tool_approval_deny_hint),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = cs.onSurface.copy(alpha = 0.5f),
                                ),
                            )
                        }
                        inner()
                    }
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = reason.trim()
                    approval.deny(toolCallId, reason = trimmed.ifEmpty { null }, conversationId = conversationId)
                    onDismiss()
                },
            ) {
                Text(stringResource(UiR.string.tool_approval_deny))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(UiR.string.home_page_cancel))
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Detail sheet (chat_message_widget.dart _showToolDetail + tool_detail_text_section)
// ---------------------------------------------------------------------------

private const val LAZY_LINE_THRESHOLD = 120
private const val LAZY_CHAR_THRESHOLD = 8000
private const val CHUNK_LINES = 40

// CustomBottomSheet 的档位（custom_bottom_sheet.dart:20-21）：停在 0.60，
// 上拉到 0.90，下探到 0.60 以下直接关闭。
private const val SHEET_PARTIAL_FRACTION = 0.60f
private const val SHEET_EXPANDED_FRACTION = 0.90f

/** tool_detail_text_section.dart shouldChunk — 大文本分块懒加载阈值。 */
internal fun shouldChunkText(text: String): Boolean {
    if (text.length > LAZY_CHAR_THRESHOLD) return true
    var lines = 1
    for (ch in text) {
        if (ch == '\n') {
            lines++
            if (lines > LAZY_LINE_THRESHOLD) return true
        }
    }
    return false
}

internal fun chunkText(text: String): List<String> {
    val lines = text.split('\n')
    val chunks = ArrayList<String>()
    var start = 0
    while (start < lines.size) {
        val end = (start + CHUNK_LINES).coerceAtMost(lines.size)
        chunks.add(lines.subList(start, end).joinToString("\n"))
        start = end
    }
    return chunks.ifEmpty { listOf(text) }
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val prettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    ignoreUnknownKeys = true
}

/** chat_message_widget.dart _prettyToolJson — 失败时原样返回。 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal fun prettyToolJson(raw: String): String = try {
    prettyJson.encodeToString(JsonElement.serializer(), prettyJson.parseToJsonElement(raw))
} catch (e: Exception) {
    raw
}

/**
 * 工具详情弹层：CustomBottomSheet 皮肤（overlaySurface + 顶部 20dp 圆角 +
 * 32×4 抓手 + 15sp 标题 / 24dp 关闭键），正文 LTRB(16,8,16,24)。
 * screen_time 有 apps 时整块换成 ScreenTimeToolDetailBody。
 */
@OptIn(ExperimentalMaterial3Api::class, kotlinx.serialization.ExperimentalSerializationApi::class)
@Composable
fun ToolDetailSheet(part: ToolUiPart, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val scope = rememberCoroutineScope()
    val title = toolTitleFor(part.toolName, part.arguments, isResult = !part.loading)
    val argumentsLabel = stringResource(UiR.string.chat_message_widget_arguments)
    val resultLabel = stringResource(UiR.string.chat_message_widget_result)
    val closeLabel = stringResource(UiR.string.mcp_page_close)
    val cleanText = part.cleanText
    val argsPretty = prettyJson.encodeToString(JsonElement.serializer(), part.arguments)
    val resultText = if (cleanText.isNotEmpty()) {
        prettyToolJson(cleanText)
    } else {
        stringResource(UiR.string.chat_message_widget_no_result_yet)
    }
    val screenTime = if (part.toolName == LocalToolNames.SCREEN_TIME) {
        ScreenTimeResult.tryParse(cleanText)
    } else {
        null
    }
    val useScreenTimeDetail = screenTime != null && screenTime.hasApps
    val listState = rememberLazyListState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = semantic.overlaySurface(cs),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        dragHandle = null,
    ) {
        // 拖拽调高：CustomBottomSheet 的手势在 Compose 里用 nestedScroll 等价
        // 实现（列表在顶部继续下拉则缩层，缩到 0.60 以下关闭；上拉先扩到
        // 0.90 再让列表滚动）。
        val screenHpx = LocalWindowInfo.current.containerSize.height.toFloat()
        var sheetFraction by remember { mutableFloatStateOf(SHEET_PARTIAL_FRACTION) }
        var closing by remember { mutableStateOf(false) }
        val sheetResize = object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if (dy < 0 && listState.canScrollBackward.not() &&
                    sheetFraction < SHEET_EXPANDED_FRACTION
                ) {
                    val grow = (-dy / screenHpx).coerceAtMost(SHEET_EXPANDED_FRACTION - sheetFraction)
                    sheetFraction += grow
                    return Offset(0f, -grow * screenHpx)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val dy = available.y
                if (dy > 0 && sheetFraction > SHEET_PARTIAL_FRACTION) {
                    val remaining = sheetFraction - SHEET_PARTIAL_FRACTION
                    val shrink = (dy / screenHpx).coerceAtMost(remaining)
                    sheetFraction -= shrink
                    if (shrink >= remaining && !closing) {
                        closing = true
                        scope.launch { sheetState.hide() }
                    }
                    return Offset(0f, shrink * screenHpx)
                }
                return Offset.Zero
            }
        }
        val sheetHeight = with(LocalDensity.current) { screenHpx.toDp() } * sheetFraction

        Column(
            Modifier
                .fillMaxWidth()
                .height(sheetHeight)
                .nestedScroll(sheetResize),
        ) {
            // _DragHandle：30dp 命中区内的 32×4 r2 色条，onSurface α0.12。
            Box(
                modifier = Modifier.fillMaxWidth().height(30.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 32.dp, height = 4.dp)
                        .background(
                            cs.onSurface.copy(alpha = 0.12f),
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
            // _SheetHeader：LTRB(20,8,16,0) + 15sp emphasis 标题 + 24dp 关闭键。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 8.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        color = cs.onSurface,
                        fontSize = 15.sp,
                        fontWeight = AppFontWeights.emphasis,
                        lineHeight = 18.sp,
                    ),
                    modifier = Modifier.weight(1f),
                )
                IosIconButton(
                    icon = Lucide.X,
                    onTap = onDismiss,
                    modifier = Modifier.size(24.dp),
                    size = 20.dp,
                    contentPadding = 0.dp,
                    color = cs.onSurface.copy(alpha = 0.62f),
                    semanticLabel = closeLabel,
                )
            }
            // SelectionArea（_ToolDetailBody 外层）。
            SelectionContainer(Modifier.fillMaxWidth().weight(1f)) {
                if (screenTime != null && useScreenTimeDetail) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
                    ) {
                        ScreenTimeToolDetailBody(result = screenTime)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            top = 8.dp,
                            end = 16.dp,
                            bottom = 24.dp,
                        ),
                    ) {
                        toolDetailTextSection(argumentsLabel, argsPretty)
                        item { Spacer(Modifier.height(12.dp)) }
                        toolDetailTextSection(resultLabel, resultText)
                    }
                }
            }
        }
    }
}

/**
 * tool_detail_text_section.dart ToolDetailTextSection：12sp 标签（下距 6dp）
 * + surfaceFill/outlineVariant α0.2 的 10dp 圆角容器；超阈值文本按 40 行
 * 分块挂到外层 LazyColumn，容器的底色与描边按首/中/末段拆到各 item 上，
 * 拼出 Dart DecoratedSliver 的一整圈装饰（段间没有横线）。
 */
private fun LazyListScope.toolDetailTextSection(label: String, text: String) {
    item(key = "tool-detail-label-$label") { SectionLabel(label) }
    if (!shouldChunkText(text)) {
        item(key = "tool-detail-text-$label") {
            TextBlockChunk(first = true, last = true) {
                Text(text = text, style = TextStyle(fontSize = 12.sp))
            }
        }
        return
    }
    val chunks = chunkText(text)
    itemsIndexed(chunks, key = { index, _ -> "tool-detail-text-$label-$index" }) { index, chunk ->
        TextBlockChunk(first = index == 0, last = index == chunks.lastIndex) {
            Text(text = chunk, style = TextStyle(fontSize = 12.sp))
        }
    }
}

@Composable
private fun SectionLabel(label: String) {
    val cs = MaterialTheme.colorScheme
    Text(
        text = label,
        style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.6f)),
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun TextBlockChunk(first: Boolean, last: Boolean, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val line = cs.outlineVariant.copy(alpha = 0.2f)
    val shape: Shape = RoundedCornerShape(
        topStart = if (first) 10.dp else 0.dp,
        topEnd = if (first) 10.dp else 0.dp,
        bottomStart = if (last) 10.dp else 0.dp,
        bottomEnd = if (last) 10.dp else 0.dp,
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(semantic.surfaceFill, shape)
            // DecoratedSliver 的描边是整组一圈，分块之间没有横线，所以每块只画
            // 自己那几段（Modifier.border 四边齐全，画不出这个效果）。
            .drawBehind {
                val sw = 1.dp.toPx()
                val half = sw / 2f
                val w = size.width
                val h = size.height
                val r = 10.dp.toPx().coerceAtMost(minOf(w, h) / 2f - half).coerceAtLeast(0f)
                val vTop = if (first) half + r else 0f
                val vBottom = if (last) h - half - r else h
                drawLine(line, Offset(half, vTop), Offset(half, vBottom), sw)
                drawLine(line, Offset(w - half, vTop), Offset(w - half, vBottom), sw)
                val corner = Size(2 * r, 2 * r)
                val stroke = Stroke(sw)
                if (first) {
                    drawLine(line, Offset(half + r, half), Offset(w - half - r, half), sw)
                    drawArc(line, 180f, 90f, false, Offset(half, half), corner, style = stroke)
                    drawArc(
                        line,
                        270f,
                        90f,
                        false,
                        Offset(w - half - 2 * r, half),
                        corner,
                        style = stroke,
                    )
                }
                if (last) {
                    drawLine(line, Offset(half + r, h - half), Offset(w - half - r, h - half), sw)
                    drawArc(
                        line,
                        90f,
                        90f,
                        false,
                        Offset(half, h - half - 2 * r),
                        corner,
                        style = stroke,
                    )
                    drawArc(
                        line,
                        0f,
                        90f,
                        false,
                        Offset(w - half - 2 * r, h - half - 2 * r),
                        corner,
                        style = stroke,
                    )
                }
            }
            .padding(
                start = 10.dp,
                top = if (first) 10.dp else 0.dp,
                end = 10.dp,
                bottom = if (last) 10.dp else 0.dp,
            ),
    ) {
        content()
    }
}

// TTS replay row lives in TtsPlayer.kt (TextToSpeechReplayRow).


// ---------------------------------------------------------------------------
// 输入栏位置的审批面板（本工程新增；用户 2026-09-14「工具权限确认这个…应该出现在
// 输入框那个位置」）
// ---------------------------------------------------------------------------

/**
 * 底部审批面板：工具名 + 参数摘要 + 拒绝/允许。与内联卡共用同一套
 * [ApprovalButton] / [ApprovalDenyDialog] 与同一条 [ToolApprovalService] 链路，
 * 只是把宿主从对话里的工具卡换到输入栏位置。
 */
@Composable
internal fun ToolApprovalPanel(
    request: ToolApprovalRequest,
    approval: ToolApprovalService?,
    conversationId: String?,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val fg = chatSurfaceFg()
    var showDeny by remember(request.toolCallId) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                cs.primaryContainer.copy(
                    alpha = if (isDark) {
                        ChatStyleSpec.TIMELINE_CARD_ALPHA_DARK
                    } else {
                        ChatStyleSpec.TIMELINE_CARD_ALPHA_LIGHT
                    },
                ),
                RoundedCornerShape(MemoRadius.CARD_DP.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = toolIconFor(request.toolName, request.arguments),
                contentDescription = null,
                tint = fg.strong,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = toolTitleFor(request.toolName, request.arguments, isResult = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontSize = ChatStyleSpec.TIMELINE_LABEL_SP.sp,
                    fontWeight = AppFontWeights.semibold,
                    color = fg.strong,
                ),
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(UiR.string.tool_approval_pending),
                style = TextStyle(fontSize = 11.sp, color = cs.onSurfaceVariant),
            )
        }
        if (request.arguments.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        cs.onSurface.copy(alpha = if (isDark) 0.06f else 0.04f),
                        RoundedCornerShape(MemoRadius.SMALL_DP.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = argsSummary(request.arguments),
                    style = TextStyle(
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = fg.body,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row {
            ApprovalButton(
                label = stringResource(UiR.string.tool_approval_deny),
                color = cs.error,
                filled = false,
                modifier = Modifier.weight(1f),
                onTap = { showDeny = true },
            )
            Spacer(Modifier.width(8.dp))
            ApprovalButton(
                label = stringResource(UiR.string.tool_approval_approve),
                color = fg.accent,
                filled = true,
                modifier = Modifier.weight(1f),
                onTap = {
                    approval?.approve(request.toolCallId, conversationId = request.conversationId)
                    Unit
                },
            )
        }
    }

    if (showDeny && approval != null) {
        ApprovalDenyDialog(
            approval = approval,
            toolCallId = request.toolCallId,
            conversationId = request.conversationId ?: conversationId,
            onDismiss = { showDeny = false },
        )
    }
}
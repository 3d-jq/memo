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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.ArrowUpDown
import com.composables.icons.lucide.BookDashed
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.BookPlus
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.ListChecks
import com.composables.icons.lucide.Menu
import com.composables.icons.lucide.MousePointer2
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.TextCursorInput
import com.composables.icons.lucide.Timer
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
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Video
import com.composables.icons.lucide.HeartPulse
import com.composables.icons.lucide.Link
import com.composables.icons.lucide.Layers
import com.composables.icons.lucide.ListPlus
import com.composables.icons.lucide.ListTodo
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.NotebookPen
import com.composables.icons.lucide.FilePlus
import com.composables.icons.lucide.FilePen
import com.composables.icons.lucide.FileSearch
import com.composables.icons.lucide.List
import com.composables.icons.lucide.TextSearch
import com.composables.icons.lucide.UserPen
import com.composables.icons.lucide.UserSearch
import com.composables.icons.lucide.Shapes
import com.composables.icons.lucide.Workflow
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Puzzle
import com.composables.icons.lucide.MapPin
import com.composables.icons.lucide.MessageCircleQuestion
import com.composables.icons.lucide.Search
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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * ported (AskUserCard.kt / ToolResultImageStrip.kt). 上游那套审批 UI（Shield 状态位、
 * 拒绝/允许按钮、审批中换参数摘要）按用户 2026-09-25「工具的权限审批全部去掉」整块拆除，
 * 只留下 [_argsSummary] 那个纯 helper（ToolResultImages.kt）——摘要别处还在用。
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
    /**
     * 起止时刻（本工程新增，2026-09-26 ZCode 式工具行计时的数据层）：epoch 毫秒，
     * payload 缺键（老消息）即 null → 界面不显示时长。
     */
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
) {
    /** chat_message_widget.dart `parseToolResultImages(content).$1` —— 剥离整行图片标记后的正文。 */
    val cleanText: String by lazy { parseToolResultImages(content).first }

    /** chat_message_widget.dart `parseToolResultImages(content).$2` —— 首见去重后的图片路径。 */
    val imagePaths: List<String> by lazy { parseToolResultImages(content).second }

    /** 实际渲染的图片：payload 附件在前，正文 markdown 图片在后。 */
    val allImagePaths: List<String> get() = attachedImages + imagePaths

    /**
     * 工具执行失败（ToolExecution 契约的失败信封 `{type:"tool_error", status:"error", …}`）。
     * 纯文本结果解析失败按「非失败」处理 —— 老会话的大多数结果不是 JSON 信封。
     */
    val isError: Boolean by lazy {
        val raw = content.orEmpty()
        raw.isNotEmpty() && runCatching {
            val obj = json.parseToJsonElement(raw).jsonObject
            (obj["status"] as? JsonPrimitive)?.content == "error"
        }.getOrDefault(false)
    }

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
                startedAt = obj.long("startedAt"),
                finishedAt = obj.long("finishedAt"),
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

        private fun JsonObject.long(key: String): Long? =
            (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toLongOrNull()
    }
}

/**
 * 「查看页面」的入口不在这里（spec §12.5，用户 2026-09-26）：浏览器页面是**整会话共享**的，
 * 不属于某一条消息，工具卡还会滚走。那一行临时落在输入区的「+」面板
 * （`ui/BottomToolsSheet.kt`），判据是「本会话有没有活着的浏览器实例」。
 */
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
        // 记忆工具（上游全族共用一个 BookHeart —— 用户 2026-09-23「记忆工具里的图标也改
        // 一下吧，很多也一样呀」）：按动作各给一个图标，和本地工具那种"一个动作一个图标"
        // 的强度对齐。**这是有意偏离上游的映射**，两个界面共用这一份（设置页的
        // `toolSchemaIconFor` 就调这里）。
        // 遗留名（create_memory / edit_memory / delete_memory）跟随各自的现代同名工具：
        // 老会话里的工具卡还要能正确显示。
        "memory_read" -> Lucide.BookOpen
        "memory_update", "create_memory" -> Lucide.BookPlus
        "memory_search_profile" -> Lucide.UserSearch
        "memory_edit", "edit_memory" -> Lucide.NotebookPen
        "memory_delete", "delete_memory" -> Lucide.BookDashed
        "update_user_profile" -> Lucide.UserPen
        "chat_search", "builtin_search" -> Lucide.Search
        "search_web" -> Lucide.Earth
        // Agent 浏览器（app 级那一族 13 颗，本工程新增）：**一颗动作一个图标**，按工具名出、
        // 不看 args（spec §12.1）。这正是拆工具的动机 —— 九种动作全落在同一个 Globe 上就
        // 看不出模型这一轮做了什么，与工作区/记忆族「一个动作一个图标」同一强度
        // （用户 2026-09-22「图标都用一样的」、2026-09-23「很多也一样呀」）。
        in com.psyche.memo.provider.browser.BrowserTools.ALL_TOOL_NAMES -> browserIconFor(name)
        // 生成工具（自研功能）：与设置里两个入口同一个图标语言。
        com.psyche.memo.provider.generation.GenerationTools.GENERATE_IMAGE -> Lucide.Image
        com.psyche.memo.provider.generation.GenerationTools.GENERATE_VIDEO -> Lucide.Video
        // 工作区工具（自研）：每个动作各一个图标 —— 原来它们没有映射、全落到 Wrench，
        // 用户 2026-09-22「工具加上对应图标吧，现在图标都用一样的」指的就是这个。
        com.psyche.memo.provider.workspace.WorkspaceTools.READ_FILE -> Lucide.FileText
        com.psyche.memo.provider.workspace.WorkspaceTools.WRITE_FILE -> Lucide.FilePlus
        com.psyche.memo.provider.workspace.WorkspaceTools.EDIT_FILE -> Lucide.FilePen
        com.psyche.memo.provider.workspace.WorkspaceTools.LIST -> Lucide.List
        com.psyche.memo.provider.workspace.WorkspaceTools.GLOB -> Lucide.FileSearch
        com.psyche.memo.provider.workspace.WorkspaceTools.GREP -> Lucide.TextSearch
        com.psyche.memo.provider.workspace.WorkspaceTools.SHELL -> Lucide.Terminal
        // 自研绘图工具：`Shapes`/`Workflow` 与「设置 → 工具描述」里那份映射一致 ——
        // 两个界面列的是同一批工具，图标不一样会很奇怪（`toolSchemaIconFor` 的兜底
        // 就是这个函数，改这里要一起想）。
        com.psyche.memo.provider.chart.VisualTools.TOOL_NAME -> Lucide.Shapes
        com.psyche.memo.provider.chart.MermaidTools.TOOL_NAME -> Lucide.Workflow
        // Provider 内置服务端工具（chat_message_widget.dart:444-453）。
        "web_fetch" -> Lucide.Link
        "code_execution", "code_interpreter", "text_editor_code_execution" -> Lucide.Code
        "bash_code_execution" -> Lucide.Terminal
        else -> Lucide.Wrench
    }
}

/**
 * 浏览器族的图标表（spec §12.1 那份表逐行照抄）。
 *
 * `icons-lucide` 的图标是**文件级扩展属性**，13 支都得在本文件里逐个 import，
 * 少一支就是 unresolved reference。族内**两两不同图标**由
 * `ToolIconCoverageTest.theBrowserFamilyHasOneIconPerAction` 钉住 —— 重复图标等于
 * 把拆工具换来的可读性又还回去了。
 */
private fun browserIconFor(name: String): ImageVector = when (name) {
    com.psyche.memo.provider.browser.BrowserTools.OPEN -> Lucide.Globe
    com.psyche.memo.provider.browser.BrowserTools.READ -> Lucide.Menu
    com.psyche.memo.provider.browser.BrowserTools.FIND -> Lucide.Search
    com.psyche.memo.provider.browser.BrowserTools.CLICK -> Lucide.MousePointer2
    com.psyche.memo.provider.browser.BrowserTools.TYPE -> Lucide.TextCursorInput
    com.psyche.memo.provider.browser.BrowserTools.SELECT -> Lucide.ListChecks
    com.psyche.memo.provider.browser.BrowserTools.SCROLL -> Lucide.ArrowUpDown
    com.psyche.memo.provider.browser.BrowserTools.SCREENSHOT -> Lucide.Camera
    com.psyche.memo.provider.browser.BrowserTools.BACK -> Lucide.ArrowLeft
    com.psyche.memo.provider.browser.BrowserTools.FORWARD -> Lucide.ArrowRight
    com.psyche.memo.provider.browser.BrowserTools.WAIT -> Lucide.Timer
    com.psyche.memo.provider.browser.BrowserTools.PAGE_INFO -> Lucide.Info
    com.psyche.memo.provider.browser.BrowserTools.RELOAD -> Lucide.RefreshCw
    com.psyche.memo.provider.browser.BrowserTools.TABS -> Lucide.Layers
    else -> Lucide.Wrench
}

/**
 * 浏览器族的标题表（与 [browserIconFor] 对着 spec §12.1 那份名单各自一条，三份 strings.xml
 * 都有）。`internal` 只为让 `ToolIconCoverageTest` 能钉「每个名字都有自己的标题」——
 * 漏一条不会崩，但那张卡会落到默认的「调用工具 browser_click」，正是拆工具要消灭的东西。
 */
internal val BROWSER_TITLE_RES: Map<String, Int> = mapOf(
    com.psyche.memo.provider.browser.BrowserTools.OPEN to UiR.string.chat_message_widget_browser_open,
    com.psyche.memo.provider.browser.BrowserTools.READ to UiR.string.chat_message_widget_browser_read,
    com.psyche.memo.provider.browser.BrowserTools.FIND to UiR.string.chat_message_widget_browser_find,
    com.psyche.memo.provider.browser.BrowserTools.CLICK to UiR.string.chat_message_widget_browser_click,
    com.psyche.memo.provider.browser.BrowserTools.TYPE to UiR.string.chat_message_widget_browser_type,
    com.psyche.memo.provider.browser.BrowserTools.SELECT to UiR.string.chat_message_widget_browser_select,
    com.psyche.memo.provider.browser.BrowserTools.SCROLL to UiR.string.chat_message_widget_browser_scroll,
    com.psyche.memo.provider.browser.BrowserTools.SCREENSHOT to UiR.string.chat_message_widget_browser_screenshot,
    com.psyche.memo.provider.browser.BrowserTools.BACK to UiR.string.chat_message_widget_browser_back,
    com.psyche.memo.provider.browser.BrowserTools.FORWARD to UiR.string.chat_message_widget_browser_forward,
    com.psyche.memo.provider.browser.BrowserTools.WAIT to UiR.string.chat_message_widget_browser_wait,
    com.psyche.memo.provider.browser.BrowserTools.PAGE_INFO to UiR.string.chat_message_widget_browser_page_info,
    com.psyche.memo.provider.browser.BrowserTools.RELOAD to UiR.string.chat_message_widget_browser_reload,
    com.psyche.memo.provider.browser.BrowserTools.TABS to UiR.string.chat_message_widget_browser_tabs,
)

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
        // Agent 浏览器（本工程新增，上游没有这一族工具）：**一颗动作一行标题**，别落到默认的
        // 「调用工具 browser_click」——用户看到的应该是「在点页面」，而不是某个匿名内部函数
        // （spec §12.1 拆工具的动机就在这张卡上）。
        in com.psyche.memo.provider.browser.BrowserTools.ALL_TOOL_NAMES ->
            BROWSER_TITLE_RES[name]?.let { stringResource(it) }
                ?: stringResource(
                    if (isResult) UiR.string.chat_message_widget_tool_result
                    else UiR.string.chat_message_widget_tool_call,
                    if (name.isEmpty()) "tool" else name,
                )
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

/**
 * CMW:5457-5488 的摘要优先级（ask-user 分支在 ChainOfThoughtToolStep 里先被
 * 替换成 _AskUserInlineBody，这里的 isAskUser 分支只是兜底）。
 */
@Composable
internal fun toolStepSummary(
    part: ToolUiPart,
    fg: ChatSurfaceFg,
    errorColor: Color,
    isAskUser: Boolean,
    showToolResultSummary: Boolean,
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
    val summaryText = if (cleanText.isNotEmpty()) {
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
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontSize = 12.sp,
                        lineHeight = 16.8.sp,
                        fontFamily = null,
                        color = fg.body,
                    ),
                )
            }
        } else {
            null
        }
    return ttsSummary ?: screenTimeSummary ?: weatherSummary ?: textSummary
}

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

/** 参数 JSON 美化（行内详情正文用，失败原样返回）。 */
internal fun prettyArgsJson(args: JsonObject): String = try {
    prettyJson.encodeToString(JsonElement.serializer(), args)
} catch (e: Exception) {
    args.toString()
}

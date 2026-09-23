package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.BadgeInfo
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize2
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Settings2
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.User
import com.composables.icons.lucide.X
import com.psyche.memo.common.Haptics
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.data.model.PresetMessage
import com.psyche.memo.llm.prompt.PromptTransformer
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.markdown.MarkdownText
import com.psyche.memo.ui.reorder.ReorderableInlineColumn
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.withAlpha
import kotlinx.coroutines.launch

/**
 * assistant_settings_edit_prompt_tab.dart `_PromptTab` — system prompt card
 * (fullscreen editor + file import + variable list + prompt-cache warning),
 * the append-current-time row and its dialogs, and the message template card
 * with its live two-bubble preview. The preset conversation card follows.
 */
@Composable
internal fun PromptTab(
    assistant: Assistant,
    onEdit: ((Assistant) -> Assistant) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The Dart side owns a TextEditingController created in initState; the
    // assistant passed in here comes back from the database asynchronously, so
    // the field keeps its own text and only pushes edits out.
    var sys by remember(assistant.id) { mutableStateOf(TextFieldValue(assistant.systemPrompt)) }
    var tmpl by remember(assistant.id) { mutableStateOf(TextFieldValue(assistant.messageTemplate)) }
    val sysRequester = remember { FocusRequester() }
    val tmplRequester = remember { FocusRequester() }
    var editorSheet by remember { mutableStateOf<String?>(null) }
    var timeVarDialog by remember { mutableStateOf(false) }
    var infoDialog by remember { mutableStateOf(false) }

    fun persistPrompt(value: String) {
        onEdit { it.copy(systemPrompt = value) }
    }

    fun persistTemplate(value: String) {
        onEdit { it.copy(messageTemplate = value) }
    }

    fun applyPrompt(value: String) {
        sys = TextFieldValue(value, TextRange(value.length))
        persistPrompt(value)
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            // FileType.custom + allowedExtensions has no SAF equivalent, so the
            // picker offers every file and only the read is guarded.
            val content = runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    String(it.readBytes(), Charsets.UTF_8)
                } ?: ""
            }
            if (content.isFailure) {
                SnackbarManager.show(
                    AppNotification(
                        message = context.getString(UiR.string.assistant_edit_system_prompt_import_failed),
                        type = NotificationType.ERROR,
                    ),
                )
            } else if (content.getOrNull().isNullOrBlank()) {
                SnackbarManager.show(
                    AppNotification(
                        message = context.getString(UiR.string.assistant_edit_system_prompt_import_empty),
                        type = NotificationType.ERROR,
                    ),
                )
            } else {
                applyPrompt(content.getOrThrow())
                SnackbarManager.show(
                    AppNotification(
                        message = context.getString(UiR.string.assistant_edit_system_prompt_import_success),
                        type = NotificationType.SUCCESS,
                    ),
                )
                scope.launch { sysRequester.requestFocus() }
            }
        }
    }

    fun onAppendTimeChanged(enabled: Boolean) {
        if (enabled && MemoryPrompts.detectTimeVariablesInSystemPrompt(sys.text).isNotEmpty()) {
            timeVarDialog = true
        } else {
            onEdit { it.copy(appendCurrentTimeToUserMessage = enabled) }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 20.dp),
    ) {
        item {
            SystemPromptCard(
                value = sys,
                focusRequester = sysRequester,
                timeVarsInPrompt = MemoryPrompts.detectTimeVariablesInSystemPrompt(sys.text),
                onValueChange = { sys = it; persistPrompt(it.text) },
                onInsertVariable = {
                    sys = sys.insertAtCursor(it)
                    persistPrompt(sys.text)
                    scope.launch { sysRequester.requestFocus() }
                },
                onOpenEditor = { editorSheet = sys.text },
                onImport = { importLauncher.launch(arrayOf("*/*")) },
            )
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            SectionCard {
                AppendCurrentTimeRow(
                    value = assistant.appendCurrentTimeToUserMessage,
                    onChanged = ::onAppendTimeChanged,
                    onInfoTap = { infoDialog = true },
                )
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            MessageTemplateCard(
                value = tmpl,
                focusRequester = tmplRequester,
                sampleMessage = stringResource(UiR.string.assistant_edit_sample_message),
                sampleReply = stringResource(UiR.string.assistant_edit_sample_reply),
                onValueChange = { tmpl = it; persistTemplate(it.text) },
                onInsertVariable = {
                    tmpl = tmpl.insertAtCursor(it)
                    persistTemplate(tmpl.text)
                    scope.launch { tmplRequester.requestFocus() }
                },
            )
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            PresetConversationCard(
                items = PresetMessage.decodeList(assistant.presetMessages),
                onAdd = { role, content ->
                    onEdit {
                        it.copy(
                            presetMessages = PresetMessage.encodeList(
                                PresetMessage.decodeList(it.presetMessages) +
                                    PresetMessage(role = role, content = content),
                            ),
                        )
                    }
                },
                onMove = { from, to ->
                    onEdit {
                        it.copy(
                            presetMessages = PresetMessage.encodeList(
                                PresetMessage.decodeList(it.presetMessages).toMutableList()
                                    .apply { add(to, removeAt(from)) },
                            ),
                        )
                    }
                },
                onSaved = { id, content ->
                    onEdit {
                        it.copy(
                            presetMessages = PresetMessage.encodeList(
                                PresetMessage.decodeList(it.presetMessages).map { m ->
                                    if (m.id == id) m.copy(content = content) else m
                                },
                            ),
                        )
                    }
                },
                onDelete = { id ->
                    onEdit {
                        it.copy(
                            presetMessages = PresetMessage.encodeList(
                                PresetMessage.decodeList(it.presetMessages).filter { m -> m.id != id },
                            ),
                        )
                    }
                },
            )
        }
    }

    editorSheet?.let { initial ->
        SystemPromptEditorSheet(
            initial = initial,
            onDismiss = { editorSheet = null },
            onSave = { next ->
                editorSheet = null
                if (next != sys.text) applyPrompt(next)
            },
        )
    }

    if (timeVarDialog) {
        TimeVarEnableDialog(
            variables = MemoryPrompts.detectTimeVariablesInSystemPrompt(sys.text).joinToString(", "),
            onDismiss = { timeVarDialog = false },
            onRemove = {
                timeVarDialog = false
                scope.launch { sysRequester.requestFocus() }
            },
            onKeep = {
                timeVarDialog = false
                onEdit { it.copy(appendCurrentTimeToUserMessage = true) }
            },
        )
    }

    if (infoDialog) {
        AppendTimeInfoDialog(onDismiss = { infoDialog = false })
    }
}

/** _sysCard L301-457 — surfaceCard r14, padding 12, outlined 8-line field. */
@Composable
private fun SystemPromptCard(
    value: TextFieldValue,
    focusRequester: FocusRequester,
    timeVarsInPrompt: List<String>,
    onValueChange: (TextFieldValue) -> Unit,
    onInsertVariable: (String) -> Unit,
    onOpenEditor: () -> Unit,
    onImport: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val title = stringResource(UiR.string.assistant_edit_system_prompt_title)

    Box(
        Modifier
            .fillMaxWidth()
            .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                )
                IosIconButton(
                    icon = Lucide.Maximize2,
                    onTap = onOpenEditor,
                    color = cs.primary,
                    size = 20.dp,
                    contentPadding = 8.dp,
                    minSize = 38.dp,
                    semanticLabel = title,
                )
                Spacer(Modifier.width(4.dp))
                IosButton(
                    label = stringResource(UiR.string.assistant_edit_system_prompt_import_button),
                    onTap = onImport,
                    icon = Lucide.FileText,
                    dense = true,
                    neutral = false,
                )
            }
            Spacer(Modifier.height(10.dp))
            PromptField(
                value = value,
                hint = stringResource(UiR.string.assistant_edit_system_prompt_hint),
                focusRequester = focusRequester,
                maxLines = 8,
                onValueChange = onValueChange,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(UiR.string.assistant_edit_available_variables),
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(4.dp))
            VarExplainList(
                items = com.psyche.memo.provider.prompt.PromptVariableCatalog.entries.map {
                    stringResource(it.first) to it.second
                },
                cacheWarningVars = com.psyche.memo.provider.prompt.PromptVariableCatalog.timeSensitiveKeys,
                cacheWarningTooltip = stringResource(UiR.string.assistant_edit_prompt_time_var_warning),
                onTapVar = onInsertVariable,
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .animateContentSize(
                        animationSpec = tween(durationMillis = 180, easing = EaseOutCubic),
                    ),
            ) {
                if (timeVarsInPrompt.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(MemoRadius.INNER_DP.dp))
                            .background(withAlpha(cs.errorContainer, 0.30)),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                            Icon(
                                Lucide.TriangleAlert,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = cs.error,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(UiR.string.assistant_edit_prompt_time_var_warning),
                                modifier = Modifier.weight(1f),
                                style = TextStyle(
                                    fontSize = 12.sp,
                                    lineHeight = 16.2.sp,
                                    color = withAlpha(cs.onSurface, 0.8),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Flutter `TextField` + `OutlineInputBorder(12)` with contentPadding 12. Compose's
 * text fields take no contentPadding at this version, and Flutter draws the
 * outline outside it, so the border rides the wrapper Box.
 */
@Composable
private fun PromptField(
    value: TextFieldValue,
    hint: String,
    focusRequester: FocusRequester,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    maxLines: Int = 8,
) {
    val cs = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (focused) withAlpha(cs.primary, 0.5) else withAlpha(cs.outlineVariant, 0.35),
                RoundedCornerShape(MemoRadius.INNER_DP.dp),
            )
            .padding(12.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused },
            textStyle = TextStyle(fontSize = 16.sp, color = cs.onSurface),
            cursorBrush = SolidColor(cs.primary),
            minLines = 1,
            maxLines = maxLines,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            decorationBox = { inner ->
                Box {
                    if (value.text.isEmpty()) {
                        Text(
                            text = hint,
                            style = TextStyle(fontSize = 16.sp, color = withAlpha(cs.onSurface, 0.4)),
                        )
                    }
                    inner()
                }
            },
        )
    }
}

/** _tmplCard L470-586 — template field, its four variables and the live preview. */
@Composable
private fun MessageTemplateCard(
    value: TextFieldValue,
    focusRequester: FocusRequester,
    sampleMessage: String,
    sampleReply: String,
    onValueChange: (TextFieldValue) -> Unit,
    onInsertVariable: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val preview = remember(value.text, sampleMessage) {
        PromptTransformer.applyMessageTemplate(
            value.text.ifBlank { "{{ message }}" },
            role = "user",
            message = sampleMessage,
        )
    }

    Box(
        Modifier
            .fillMaxWidth()
            .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = stringResource(UiR.string.assistant_edit_message_template_title),
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(10.dp))
            PromptField(
                value = value,
                hint = "{{ message }}",
                focusRequester = focusRequester,
                maxLines = 4,
                onValueChange = onValueChange,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(UiR.string.assistant_edit_available_variables),
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(4.dp))
            VarExplainList(
                items = listOf(
                    stringResource(UiR.string.assistant_edit_variable_role) to "{{ role }}",
                    stringResource(UiR.string.assistant_edit_variable_message) to "{{ message }}",
                    stringResource(UiR.string.assistant_edit_variable_time) to "{{ time }}",
                    stringResource(UiR.string.assistant_edit_variable_date) to "{{ date }}",
                ),
                cacheWarningVars = emptySet(),
                cacheWarningTooltip = "",
                onTapVar = onInsertVariable,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(UiR.string.assistant_edit_preview_title),
                style = TextStyle(fontSize = 12.sp, color = withAlpha(cs.onSurface, 0.7)),
            )
            Spacer(Modifier.height(12.dp))
            TemplatePreviewBubble(role = "user", content = preview)
            TemplatePreviewBubble(role = "assistant", content = sampleReply)
        }
    }
}

/**
 * ChatMessageWidget in preview mode (showModelIcon/showTokenStats off): the user
 * bubble and the bare assistant markdown, on the chat screen's metrics.
 */
@Composable
private fun TemplatePreviewBubble(role: String, content: String) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val maxBubbleWidth = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp() * ChatStyleSpec.USER_MAX_WIDTH_RATIO
    }
    if (role == "user") {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = ChatStyleSpec.MESSAGE_VERTICAL_DP.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                Modifier
                    .widthIn(max = maxBubbleWidth)
                    .background(
                        cs.primary.copy(
                            alpha = if (isDark) {
                                ChatStyleSpec.USER_BUBBLE_ALPHA_DARK
                            } else {
                                ChatStyleSpec.USER_BUBBLE_ALPHA_LIGHT
                            }
                        ),
                        RoundedCornerShape(ChatStyleSpec.BUBBLE_CORNER_DP.dp),
                    )
                    .padding(ChatStyleSpec.BUBBLE_PADDING_DP.dp),
            ) {
                MarkdownText(
                    markdown = content,
                    baseFontSize = ChatStyleSpec.USER_TEXT_SP,
                    baseLineHeight = ChatStyleSpec.USER_TEXT_LINE_HEIGHT_SP,
                )
            }
        }
    } else {
        MarkdownText(
            markdown = content,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = ChatStyleSpec.MESSAGE_VERTICAL_DP.dp),
        )
    }
}

/**
 * presetCard L589-897 — pill buttons, the reorderable preset list and the
 * inline add input. Flutter scrolls the header into view when a pill is
 * tapped; the soft keyboard already reveals the input here, so that step is
 * dropped. The pill's desktop hover tint has no touch equivalent.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetConversationCard(
    items: List<PresetMessage>,
    onAdd: (role: String, content: String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onSaved: (id: String, content: String) -> Unit,
    onDelete: (id: String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var role by remember { mutableStateOf("user") }
    var showInput by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    val draftRequester = remember { FocusRequester() }
    var editing by remember { mutableStateOf<PresetMessage?>(null) }
    val addLabel = stringResource(UiR.string.assistant_edit_preset_add_user)
    val addAssistantLabel = stringResource(UiR.string.assistant_edit_preset_add_assistant)
    val title = stringResource(UiR.string.assistant_edit_preset_title)
    val draftHint = stringResource(
        if (role == "assistant") {
            UiR.string.assistant_edit_preset_input_hint_assistant
        } else {
            UiR.string.assistant_edit_preset_input_hint_user
        },
    )

    fun submitDraft() {
        val text = draft.text.trim()
        if (text.isEmpty()) return
        onAdd(role, text)
        showInput = false
        draft = TextFieldValue("")
    }

    @Composable
    fun pills() {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PresetPillButton(Lucide.User, cs.primary, addLabel) {
                role = "user"
                draft = TextFieldValue("")
                showInput = true
            }
            PresetPillButton(Lucide.Bot, cs.secondary, addAssistantLabel) {
                role = "assistant"
                draft = TextFieldValue("")
                showInput = true
            }
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp)),
    ) {
        Column(Modifier.padding(12.dp)) {
            BoxWithConstraints {
                // LayoutBuilder L659-662: narrow when the card is under 420dp or
                // the text scale is past 1.15.
                val narrow = maxWidth < 420.dp || LocalDensity.current.fontScale > 1.15f
                val titleStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (narrow) {
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(text = title, style = titleStyle)
                        Spacer(Modifier.height(8.dp))
                        pills()
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = title, style = titleStyle, modifier = Modifier.weight(1f))
                        pills()
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (items.isEmpty()) {
                Text(
                    text = stringResource(UiR.string.assistant_edit_preset_empty),
                    style = TextStyle(fontSize = 12.sp, color = withAlpha(cs.onSurface, 0.6)),
                )
            } else {
                ReorderableInlineColumn(
                    items = items,
                    keyOf = { it.id },
                    onMove = onMove,
                ) { item, _ ->
                    PresetMessageCard(
                        role = item.role,
                        content = item.content,
                        onEdit = { editing = item },
                        onDelete = { onDelete(item.id) },
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
            AnimatedVisibility(
                visible = showInput,
                enter = expandVertically(tween(200, easing = EaseOutCubic)) +
                    fadeIn(tween(200, easing = EaseOutCubic)),
                exit = shrinkVertically(tween(200, easing = EaseInCubic)) +
                    fadeOut(tween(200, easing = EaseInCubic)),
            ) {
                Column(Modifier.padding(top = 10.dp)) {
                    PromptField(
                        value = draft,
                        hint = draftHint,
                        focusRequester = draftRequester,
                        maxLines = 6,
                        onValueChange = { draft = it },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        IosButton(
                            label = stringResource(UiR.string.assistant_edit_emoji_dialog_cancel),
                            onTap = {
                                showInput = false
                                draft = TextFieldValue("")
                            },
                            dense = true,
                        )
                        Spacer(Modifier.width(8.dp))
                        IosButton(
                            label = stringResource(UiR.string.assistant_edit_emoji_dialog_save),
                            onTap = { submitDraft() },
                            filled = true,
                            neutral = false,
                            dense = true,
                        )
                    }
                }
            }
        }
    }

    editing?.let { target ->
        PresetEditSheet(
            message = target,
            onDismiss = { editing = null },
            onSave = { content ->
                onSaved(target.id, content)
                editing = null
            },
        )
    }
}

/** _HoverPillButton L1422-1466 — 14 icon + 12sp label on a 10% tint of its own colour. */
@Composable
private fun PresetPillButton(
    icon: ImageVector,
    color: Color,
    label: String,
    onTap: () -> Unit,
) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = withAlpha(color, if (pressed) 0.18 else 0.10),
        animationSpec = tween(120, easing = EaseOutCubic),
        label = "presetPillBackground",
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(MemoRadius.SMALL_DP.dp))
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    Haptics.soft(view)
                    onTap()
                },
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color),
        )
    }
}

/**
 * _PresetMessageCard L1034-1067. The hover ring is desktop-only, so the resting
 * outlineVariant tint is the one that ships.
 */
@Composable
private fun PresetMessageCard(
    role: String,
    content: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val isDark = cs.surface.luminance() < 0.5f
    val isAssistant = role == "assistant"
    val shape = RoundedCornerShape(MemoRadius.CARD_DP.dp)
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .background(semantic.surfaceCard, shape)
            .border(
                1.dp,
                withAlpha(cs.outlineVariant, if (isDark) 0.12 else 0.08),
                shape,
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isAssistant) Lucide.Bot else Lucide.User,
            contentDescription = null,
            tint = if (isAssistant) cs.secondary else cs.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = content,
            modifier = Modifier.weight(1f),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontSize = 13.5.sp, color = withAlpha(cs.onSurface, 0.9)),
        )
        Spacer(Modifier.width(8.dp))
        IosIconButton(
            icon = Lucide.Settings2,
            onTap = onEdit,
            color = withAlpha(cs.onSurface, 0.9),
            size = 16.dp,
            contentPadding = 6.dp,
        )
        Spacer(Modifier.width(4.dp))
        IosIconButton(
            icon = Lucide.Trash2,
            onTap = onDelete,
            color = withAlpha(cs.onSurface, 0.9),
            size = 16.dp,
            contentPadding = 6.dp,
        )
    }
}

/** _showEditPresetDialog mobile branch L1583-1671. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetEditSheet(
    message: PresetMessage,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val view = LocalView.current
    var value by remember { mutableStateOf(TextFieldValue(message.content)) }
    var focused by remember { mutableStateOf(false) }
    val hint = stringResource(
        if (message.role == "assistant") {
            UiR.string.assistant_edit_preset_input_hint_assistant
        } else {
            UiR.string.assistant_edit_preset_input_hint_user
        },
    )
    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        dragHandle = null,
        containerColor = semantic.overlaySurface(cs),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Lucide.MessageSquare,
                    contentDescription = null,
                    tint = cs.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(UiR.string.assistant_edit_preset_edit_dialog_title),
                    modifier = Modifier.weight(1f),
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                )
            }
            Spacer(Modifier.height(12.dp))
            val shape = RoundedCornerShape(MemoRadius.SMALL_DP.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(semantic.surfaceFill, shape)
                    .border(
                        1.dp,
                        if (focused) withAlpha(cs.primary, 0.5) else withAlpha(cs.outlineVariant, 0.2),
                        shape,
                    )
                    .padding(horizontal = 12.dp, vertical = 13.dp),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused },
                    textStyle = TextStyle(fontSize = 16.sp, color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    minLines = 1,
                    maxLines = 8,
                    decorationBox = { inner ->
                        Box {
                            if (value.text.isEmpty()) {
                                Text(
                                    text = hint,
                                    style = TextStyle(
                                        fontSize = 16.sp,
                                        color = withAlpha(cs.onSurface, 0.4),
                                    ),
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            Row {
                IosButton(
                    label = stringResource(UiR.string.assistant_edit_emoji_dialog_cancel),
                    onTap = onDismiss,
                    modifier = Modifier.weight(1f),
                    icon = Lucide.X,
                )
                Spacer(Modifier.width(10.dp))
                IosButton(
                    label = stringResource(UiR.string.assistant_edit_emoji_dialog_save),
                    onTap = {
                        val text = value.text.trim()
                        if (text.isNotEmpty()) {
                            Haptics.soft(view)
                            onSave(text)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    icon = Lucide.Check,
                    filled = true,
                    neutral = false,
                )
            }
        }
    }
}

/** _VarExplainList L1674-1728 — wrap of "label: {var}" with the tappable variable. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VarExplainList(
    items: List<Pair<String, String>>,
    cacheWarningVars: Set<String>,
    cacheWarningTooltip: String,
    onTapVar: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (label, variable) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$label: ",
                    style = TextStyle(fontSize = 12.sp, color = withAlpha(cs.onSurface, 0.75)),
                )
                Text(
                    text = variable,
                    modifier = Modifier.clickable { onTapVar(variable) },
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.primary,
                        textDecoration = TextDecoration.Underline,
                    ),
                )
                if (variable in cacheWarningVars) {
                    Spacer(Modifier.width(4.dp))
                    CacheWarningIcon(message = cacheWarningTooltip)
                }
            }
        }
    }
}

/** Flutter's Tooltip around the 14px warning glyph; touch users get a tap-shown tooltip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CacheWarningIcon(message: String) {
    val cs = MaterialTheme.colorScheme
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        modifier = Modifier.clickable { scope.launch { state.show() } },
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(message) } },
        state = state,
    ) {
        Icon(
            Lucide.TriangleAlert,
            contentDescription = message,
            modifier = Modifier.size(14.dp),
            tint = cs.error,
        )
    }
}

/** _AppendCurrentTimeRow L915-1004. */
@Composable
private fun AppendCurrentTimeRow(
    value: Boolean,
    onChanged: (Boolean) -> Unit,
    onInfoTap: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 文本块 + ⓘ 打包成一组（weight(1f) 吃满余量、内层只占所需宽度）⇒ ⓘ 紧贴
        // 文字块（用户 2026-09-12 点名：不要在开关那侧），开关照旧贴右。
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            TactileRow(
                modifier = Modifier.weight(1f, fill = false),
                onTap = { onChanged(!value) },
            ) { pressed ->
                AnimatedPressColor(pressed = pressed, base = withAlpha(cs.onSurface, 0.9)) { color ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(36.dp)) {
                            Icon(
                                Lucide.Clock,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = if (value) cs.primary else color,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(UiR.string.assistant_edit_prompt_append_time_title),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color),
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = stringResource(UiR.string.assistant_edit_prompt_append_time_subtitle),
                                style = TextStyle(
                                    fontSize = 12.sp,
                                    lineHeight = 15.sp,
                                    color = withAlpha(cs.onSurface, 0.62),
                                ),
                            )
                        }
                    }
                }
            }
            IosIconButton(
                icon = Lucide.BadgeInfo,
                onTap = onInfoTap,
                color = withAlpha(cs.onSurface, 0.55),
                size = 16.dp,
                contentPadding = 6.dp,
                minSize = 32.dp,
                semanticLabel = stringResource(UiR.string.assistant_edit_prompt_append_time_info_title),
            )
        }
        Spacer(Modifier.width(4.dp))
        IosSwitch(value = value, onValueChanged = onChanged)
    }
}

/** _showTimeVarEnableDialog L225-252 — Remove pops false, Keep true. */
@Composable
private fun TimeVarEnableDialog(
    variables: String,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onKeep: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(UiR.string.assistant_edit_prompt_time_var_dialog_title)) },
        text = {
            Text(
                text = stringResource(UiR.string.assistant_edit_prompt_time_var_dialog_body, variables),
            )
        },
        confirmButton = {
            TextButton(onClick = onKeep) {
                Text(
                    text = stringResource(UiR.string.assistant_edit_prompt_time_var_dialog_keep),
                    color = cs.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onRemove) {
                Text(stringResource(UiR.string.assistant_edit_prompt_time_var_dialog_remove))
            }
        },
    )
}

/** _showAppendCurrentTimeInfoDialog L254-270. */
@Composable
private fun AppendTimeInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(UiR.string.assistant_edit_prompt_append_time_info_title)) },
        text = {
            Text(
                text = stringResource(
                    UiR.string.assistant_edit_prompt_append_time_info_body,
                    "<current_time>Mon 2026-08-08 14:30:05</current_time>",
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(UiR.string.assistant_edit_prompt_append_time_info_close))
            }
        },
    )
}

/** _showSystemPromptMobileSheet L150-161 + _SystemPromptMobileSheet L1189-1271. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SystemPromptEditorSheet(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var text by remember { mutableStateOf(TextFieldValue(initial)) }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        containerColor = semantic.overlaySurface(cs),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(screenHeight * 0.96f)
                .imePadding()
                .padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Row {
                // MaterialLocalizations.closeButtonLabel has no ARB counterpart and
                // strings.xml is generator-owned, so an existing "Close" is reused.
                SheetTextButton(
                    label = stringResource(UiR.string.tts_services_close_button),
                    color = cs.onSurface,
                    onTap = onDismiss,
                )
                Spacer(Modifier.weight(1f))
                SheetTextButton(
                    label = stringResource(UiR.string.assistant_edit_emoji_dialog_save),
                    color = cs.primary,
                    onTap = { onSave(text.text) },
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .background(semantic.surfaceFill, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                    .border(1.dp, withAlpha(cs.outlineVariant, 0.2), RoundedCornerShape(MemoRadius.INNER_DP.dp))
                    .padding(12.dp),
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxSize(),
                    textStyle = TextStyle(fontSize = 16.sp, color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    minLines = 1,
                    maxLines = Int.MAX_VALUE,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    decorationBox = { inner ->
                        Box {
                            if (text.text.isEmpty()) {
                                Text(
                                    text = stringResource(UiR.string.assistant_edit_system_prompt_hint),
                                    style = TextStyle(fontSize = 16.sp, color = withAlpha(cs.onSurface, 0.4)),
                                )
                            }
                            inner()
                        }
                    },
                )
            }
        }
    }
}

/**
 * _HoverTextButton L1118-1187 with `enableHover: false` — the sheet is the only
 * caller, so only the pressed background (140ms easeOutCubic) is rendered.
 */
@Composable
private fun SheetTextButton(
    label: String,
    color: Color,
    onTap: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = if (pressed) withAlpha(cs.onSurface, if (isDark) 0.12 else 0.08) else Color.Transparent,
        animationSpec = tween(durationMillis = 140, easing = EaseOutCubic),
        label = "sheetTextButtonBackground",
    )
    Box(
        Modifier
            .clip(RoundedCornerShape(MemoRadius.SMALL_DP.dp))
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    Haptics.soft(view)
                    onTap()
                },
            ),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            style = TextStyle(
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (pressed) withAlpha(color, 0.8) else color,
            ),
        )
    }
}

/** _insertAtCursor L54-69 — replaces the selection and parks the caret after the insert. */
private fun TextFieldValue.insertAtCursor(toInsert: String): TextFieldValue {
    val start = selection.start.coerceIn(0, text.length)
    val end = selection.end.coerceIn(start, text.length)
    val next = text.replaceRange(start, end, toInsert)
    return TextFieldValue(next, TextRange(start + toInsert.length))
}

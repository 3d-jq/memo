package com.psyche.memo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize2
import com.composables.icons.lucide.TriangleAlert
import com.psyche.memo.common.Haptics
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.withAlpha
import kotlinx.coroutines.launch

/**
 * assistant_settings_edit_prompt_tab.dart `_PromptTab` — system prompt card
 * (fullscreen editor + file import + variable list + prompt-cache warning),
 * the append-current-time row and its dialogs. The message template card and
 * the preset conversation card follow in the next slice.
 */
@Composable
internal fun PromptTab(
    assistant: Assistant,
    onEdit: ((Assistant) -> Assistant) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sysRequester = remember { FocusRequester() }

    // The Dart side owns a TextEditingController created in initState; the
    // assistant passed in here comes back from the database asynchronously, so
    // the field keeps its own text and only pushes edits out.
    var sys by remember(assistant.id) { mutableStateOf(TextFieldValue(assistant.systemPrompt)) }
    var sysFocused by remember { mutableStateOf(false) }
    var editorSheet by remember { mutableStateOf<String?>(null) }
    var timeVarDialog by remember { mutableStateOf(false) }
    var infoDialog by remember { mutableStateOf(false) }

    fun persistPrompt(value: String) {
        onEdit { it.copy(systemPrompt = value) }
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
                focused = sysFocused,
                focusRequester = sysRequester,
                timeVarsInPrompt = MemoryPrompts.detectTimeVariablesInSystemPrompt(sys.text),
                onFocusChanged = { sysFocused = it },
                onValueChange = { sys = it; persistPrompt(it.text) },
                onInsertVariable = { sys = sys.insertAtCursor(it); persistPrompt(sys.text) },
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
    focused: Boolean,
    focusRequester: FocusRequester,
    timeVarsInPrompt: List<String>,
    onFocusChanged: (Boolean) -> Unit,
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
            .background(semantic.surfaceCard, RoundedCornerShape(14.dp)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
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
                    icon = Icons.Filled.FileOpen,
                    dense = true,
                    neutral = false,
                )
            }
            Spacer(Modifier.height(10.dp))
            // Flutter draws InputDecoration's outline outside contentPadding(12),
            // so the border sits on the wrapper and the field is inset by it.
            Box(
                Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (focused) withAlpha(cs.primary, 0.5) else withAlpha(cs.outlineVariant, 0.35),
                        RoundedCornerShape(12.dp),
                    )
                    .padding(12.dp),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { onFocusChanged(it.isFocused) },
                    textStyle = TextStyle(fontSize = 16.sp, color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    minLines = 1,
                    maxLines = 8,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    decorationBox = { inner ->
                        Box {
                            if (value.text.isEmpty()) {
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
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(UiR.string.assistant_edit_available_variables),
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
            )
            Spacer(Modifier.height(4.dp))
            VarExplainList(
                items = listOf(
                    stringResource(UiR.string.assistant_edit_variable_date) to "{cur_date}",
                    stringResource(UiR.string.assistant_edit_variable_time) to "{cur_time}",
                    stringResource(UiR.string.assistant_edit_variable_datetime) to "{cur_datetime}",
                    stringResource(UiR.string.assistant_edit_variable_model_id) to "{model_id}",
                    stringResource(UiR.string.assistant_edit_variable_model_name) to "{model_name}",
                    stringResource(UiR.string.assistant_edit_variable_locale) to "{locale}",
                    stringResource(UiR.string.assistant_edit_variable_timezone) to "{timezone}",
                    stringResource(UiR.string.assistant_edit_variable_system_version) to "{system_version}",
                    stringResource(UiR.string.assistant_edit_variable_device_info) to "{device_info}",
                    stringResource(UiR.string.assistant_edit_variable_battery_level) to "{battery_level}",
                    stringResource(UiR.string.assistant_edit_variable_nickname) to "{nickname}",
                    stringResource(UiR.string.assistant_edit_variable_assistant_name) to "{assistant_name}",
                ),
                cacheWarningVars = setOf("{cur_date}", "{cur_time}", "{cur_datetime}"),
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
                            .clip(RoundedCornerShape(12.dp))
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
                        fontWeight = FontWeight.Medium,
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
        TactileRow(
            modifier = Modifier.weight(1f),
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
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = color),
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
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
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
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
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
        onDismissRequest = onDismiss,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
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
                    .background(semantic.surfaceFill, RoundedCornerShape(14.dp))
                    .border(1.dp, withAlpha(cs.outlineVariant, 0.2), RoundedCornerShape(14.dp))
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
            .clip(RoundedCornerShape(10.dp))
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
                fontWeight = FontWeight.Medium,
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

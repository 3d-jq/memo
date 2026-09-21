package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psyche.memo.ui.R as UiR
import kotlinx.coroutines.launch
import com.composables.icons.lucide.BadgeInfo
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircleWarning
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Settings2
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.X
import com.psyche.memo.data.db.PayloadEntityDao
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.withAlpha
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.psyche.memo.AppContainerImpl

/** memory_ui.dart L24 — `DateFormat('yyyy-MM-dd')`. */
internal val memoryEntryDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

// ── Label / color helpers ────────────────────────────────────────────────────

@Composable
internal fun memoryTypeLabel(type: MemoryType): String = stringResource(
    when (type) {
        MemoryType.identity -> UiR.string.memory_entry_type_identity
        MemoryType.workflow -> UiR.string.memory_entry_type_workflow
        MemoryType.voice -> UiR.string.memory_entry_type_voice
        MemoryType.instruction -> UiR.string.memory_entry_type_instruction
    },
)

@Composable
internal fun memorySourceLabel(source: MemorySource): String = stringResource(
    when (source) {
        MemorySource.manual -> UiR.string.memory_entry_source_manual
        MemorySource.tool -> UiR.string.memory_entry_source_tool
        MemorySource.extracted -> UiR.string.memory_entry_source_extracted
        MemorySource.distilled -> UiR.string.memory_entry_source_distilled
    },
)

/** memory_ui.dart L52-63. */
internal fun memoryTypeColor(cs: androidx.compose.material3.ColorScheme, type: MemoryType): Color = when (type) {
    MemoryType.identity -> cs.primary
    MemoryType.workflow -> cs.tertiary
    MemoryType.voice -> cs.secondary
    MemoryType.instruction -> cs.error
}

@Composable
internal fun memoryScopeLabel(entry: MemoryEntry, assistantName: String?, useThisAssistant: Boolean = false): String {
    if (entry.scope == MemoryScope.global) return stringResource(UiR.string.memory_entry_scope_global)
    return if (useThisAssistant || assistantName.isNullOrEmpty()) {
        stringResource(UiR.string.memory_entry_scope_assistant)
    } else {
        stringResource(UiR.string.memory_entry_scope_assistant_named, assistantName)
    }
}

/** memory_ui.dart L65-78 — resolve an assistant display name by id. */
internal fun resolveAssistantNameSync(container: AppContainerImpl, assistantId: String?): String? {
    if (assistantId == null) return null
    return loadAssistantsSync(container).firstOrNull { it.id == assistantId }?.name
}

/** Reads assistant_rows via PayloadEntityDao (same pattern as currentAssistantId). */
internal fun loadAssistantsSync(container: AppContainerImpl): List<Assistant> =
    runCatching {
        PayloadEntityDao(
            container.database.readableDatabase,
            "assistant_rows",
            primaryKey = "id",
        ).getAll().mapNotNull { row ->
            runCatching {
                Assistant.fromJsonString(kotlinx.serialization.json.Json { ignoreUnknownKeys = true }, row.payload)
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

// ── MemoryTipIcon ────────────────────────────────────────────────────────────

// ── memoryOutcomeLabel ───────────────────────────────────────────────────────

/** memory_ui.dart L129-164 — pipeline/tool outcome code → l10n label. */
@Composable
internal fun memoryOutcomeLabel(code: String): String {
    val key = code.indexOf(':').let { if (it < 0) code else code.substring(0, it) }
    return when (key) {
        "temporary_conversation" -> stringResource(UiR.string.memory_outcome_temporary_conversation)
        "memory_disabled" -> stringResource(UiR.string.memory_outcome_memory_disabled)
        "auto_organize_off" -> stringResource(UiR.string.memory_outcome_auto_organize_off)
        "streaming" -> stringResource(UiR.string.memory_outcome_streaming)
        "below_threshold" -> stringResource(UiR.string.memory_outcome_below_threshold)
        "empty_window" -> stringResource(UiR.string.memory_outcome_empty_window)
        "memory_model_unset" -> stringResource(UiR.string.memory_outcome_memory_model_unset)
        "memory_model_missing" -> stringResource(UiR.string.memory_outcome_memory_model_missing)
        "assistant_missing" -> stringResource(UiR.string.memory_outcome_assistant_missing)
        "conversation_missing" -> stringResource(UiR.string.memory_outcome_conversation_missing)
        "queue_overflow" -> stringResource(UiR.string.memory_outcome_queue_overflow)
        "gate_request_failed" -> stringResource(UiR.string.memory_outcome_gate_request_failed)
        "gate_parse_failed" -> stringResource(UiR.string.memory_outcome_gate_parse_failed)
        "extract_request_failed" -> stringResource(UiR.string.memory_outcome_extract_request_failed)
        "extract_parse_failed" -> stringResource(UiR.string.memory_outcome_extract_parse_failed)
        "distill_failed" -> stringResource(UiR.string.memory_outcome_distill_failed)
        "memory_execution_error" -> stringResource(UiR.string.memory_outcome_memory_execution_error)
        "unsupported_tool" -> stringResource(UiR.string.memory_outcome_unsupported_tool)
        "invalid_memory_type" -> stringResource(UiR.string.memory_outcome_invalid_memory_type)
        "invalid_memory_content" -> stringResource(UiR.string.memory_outcome_invalid_memory_content)
        "invalid_query" -> stringResource(UiR.string.memory_outcome_invalid_query)
        "invalid_memory_id" -> stringResource(UiR.string.memory_outcome_invalid_memory_id)
        "memory_not_found" -> stringResource(UiR.string.memory_outcome_memory_not_found)
        "invalid_profile_fields" -> stringResource(UiR.string.memory_outcome_invalid_profile_fields)
        "chat_search_unavailable" -> stringResource(UiR.string.memory_outcome_chat_search_unavailable)
        else -> code
    }
}

// ── Confirm dialogs ──────────────────────────────────────────────────────────

/** memory_ui.dart L166-189. */
@Composable
internal fun ConfirmHardDeleteMemoryDialog(onResult: (Boolean) -> Unit) {
    MemoryConfirmDialog(
        title = stringResource(UiR.string.memory_entry_delete_confirm_title),
        content = stringResource(UiR.string.memory_entry_delete_confirm_content),
        confirmLabel = stringResource(UiR.string.memory_entry_action_delete),
        confirmError = true,
        onResult = onResult,
    )
}

/** memory_ui.dart L191-217. */
@Composable
internal fun ConfirmBatchHardDeleteDialog(count: Int, onResult: (Boolean) -> Unit) {
    MemoryConfirmDialog(
        title = stringResource(UiR.string.memory_entry_batch_delete_confirm_title, count.toString()),
        content = stringResource(UiR.string.memory_entry_batch_delete_confirm_content),
        confirmLabel = stringResource(UiR.string.memory_entry_action_delete),
        confirmError = true,
        onResult = onResult,
    )
}

/** memory_ui.dart L219-245. */
@Composable
internal fun ConfirmOrphanCleanupDialog(count: Int, onResult: (Boolean) -> Unit) {
    MemoryConfirmDialog(
        title = stringResource(UiR.string.memory_orphan_confirm_title),
        content = stringResource(UiR.string.memory_orphan_confirm_content, count.toString()),
        confirmLabel = stringResource(UiR.string.memory_orphan_cleanup_button),
        confirmError = true,
        onResult = onResult,
    )
}

/** memory_ui.dart L247-274. */
@Composable
internal fun ConfirmScopeSwitchDialog(toGlobal: Boolean, onResult: (Boolean) -> Unit) {
    MemoryConfirmDialog(
        title = stringResource(UiR.string.memory_entry_switch_scope_confirm_title),
        content = stringResource(
            if (toGlobal) UiR.string.memory_entry_switch_scope_to_global else UiR.string.memory_entry_switch_scope_to_assistant,
        ),
        confirmLabel = stringResource(UiR.string.memory_entry_action_switch_scope),
        confirmError = false,
        onResult = onResult,
    )
}

@Composable
private fun MemoryConfirmDialog(
    title: String,
    content: String,
    confirmLabel: String,
    confirmError: Boolean,
    onResult: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = { onResult(false) },
        title = { Text(title) },
        text = { Text(content) },
        confirmButton = {
            TextButton(onClick = { onResult(true) }) {
                Text(confirmLabel, color = if (confirmError) cs.error else cs.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = { onResult(false) }) {
                Text(stringResource(UiR.string.home_page_cancel))
            }
        },
    )
}

// ── Banners / sections / rows ────────────────────────────────────────────────

/** memory_ui.dart L277-339 — soft info banner. */
@Composable
internal fun MemoryInfoBanner(body: String, title: String? = null, icon: ImageVector = Lucide.BadgeInfo) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                withAlpha(cs.primaryContainer, if (app.isDark) 0.20 else 0.35),
                RoundedCornerShape(MemoRadius.INNER_DP.dp),
            )
            .border(0.6.dp, withAlpha(cs.primary, 0.10), RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .padding(12.dp),
    ) {
        Row {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = cs.primary)
            Spacer(Modifier.width(10.dp))
            Column {
                if (title != null) {
                    Text(title, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface))
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    body,
                    style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, color = withAlpha(cs.onSurface, 0.72)),
                )
            }
        }
    }
}

/** memory_ui.dart L442-456 — iOS grouped card. */
@Composable
internal fun MemorySectionCard(content: @Composable () -> Unit) {
    SectionCard(content = content)
}

/** memory_ui.dart L459-479. */
@Composable
internal fun MemorySectionLabel(text: String) {
    val cs = MaterialTheme.colorScheme
    Text(
        text,
        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.8)),
        modifier = Modifier.padding(start = 12.dp, top = 0.dp, end = 12.dp, bottom = 6.dp),
    )
}

/** memory_ui.dart L482-541 — title + info Tooltip + chevron row. */
@Composable
internal fun MemoryNavRow(title: String, tip: String, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    TactileRow(onTap = onTap) { pressed ->
        val bg = if (pressed) withAlpha(cs.onSurface, 0.05) else Color.Transparent
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bg)
                .padding(start = 14.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ⓘ 紧跟标题文字（用户 2026-09-12 点名，见 SettingsUi.TipHuggingLabel）。
            TipHuggingLabel(
                label = title,
                tip = tip,
                labelStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.9)),
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                Lucide.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = withAlpha(cs.onSurface, 0.35),
            )
        }
    }
}

/** memory_ui.dart L544-611 — selectable pill (ChoiceChip replacement). */
@Composable
internal fun MemorySelectChip(
    label: String,
    onTap: (() -> Unit)?,
    selected: Boolean = false,
    emphasized: Boolean = false,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current
    val background = when {
        selected -> withAlpha(cs.primary, if (app.isDark) 0.22 else 0.12)
        emphasized -> withAlpha(cs.primary, if (app.isDark) 0.16 else 0.10)
        else -> app.surfaceFill
    }
    val borderColor = when {
        selected -> withAlpha(cs.primary, 0.38)
        emphasized -> withAlpha(cs.primary, if (app.isDark) 0.24 else 0.18)
        else -> withAlpha(cs.outlineVariant, if (app.isDark) 0.18 else 0.14)
    }
    val foreground = if (selected || emphasized) cs.primary else withAlpha(cs.onSurface, 0.8)

    TactileRow(onTap = onTap, haptics = false) { pressed ->
        Row(
            modifier = Modifier
                .background(if (pressed) withAlpha(background, 0.8) else background, RoundedCornerShape(MemoRadius.PILL_DP.dp))
                .border(1.dp, borderColor, RoundedCornerShape(MemoRadius.PILL_DP.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = foreground)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                label,
                style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = foreground, lineHeight = 13.sp),
            )
            if (trailingIcon != null) {
                Spacer(Modifier.width(4.dp))
                Icon(trailingIcon, contentDescription = null, modifier = Modifier.size(14.dp), tint = foreground)
            }
        }
    }
}

/** memory_ui.dart L614-770 — rounded search box (mobile branch L709-769). */
@Composable
internal fun MemorySearchField(
    value: String,
    onValueChange: (String) -> Unit,
    hintText: String,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 42.dp)
            .background(app.surfaceFill, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = withAlpha(cs.onSurface, 0.55))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    hintText,
                    style = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = withAlpha(cs.onSurface, if (app.isDark) 0.42 else 0.46),
                    ),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = withAlpha(cs.onSurface, 0.92),
                    lineHeight = 17.sp,
                ),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            TactileRow(onTap = { onValueChange("") }, haptics = false) {
                Icon(
                    Lucide.X,
                    contentDescription = stringResource(UiR.string.memory_ui_search_clear),
                    modifier = Modifier
                        .padding(4.dp)
                        .size(16.dp),
                    tint = withAlpha(cs.onSurface, 0.55),
                )
            }
        } else {
            Spacer(Modifier.width(4.dp))
        }
    }
}

// ── Option picker ────────────────────────────────────────────────────────────

/** memory_ui.dart L772-782. */
internal data class MemoryPickerOption<T>(
    val value: T,
    val label: String,
    val subtitle: String? = null,
)

/**
 * memory_ui.dart L785-934 — bottom sheet on mobile (the desktop dialog branch
 * is unreachable on Android). Returns the chosen value via [onSelected].
 *
 * 样式走**全站统一件**（`MemoSheetHandle` + [MemoSheetOptionRow] + `spacedBy(8.dp)`），
 * 不再自撸选项行与分隔线、也不带标题 —— 用户 2026-09-14：「记忆列表界面里的全部范围、
 * 全部类型这个 sheet 也没用我们那个统一的 sheet 样式」。额外加 `verticalScroll`
 * 是因为助手筛选项可能很长（统一样式的其它 sheet 都是短列表）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> MemoryOptionPickerSheet(
    options: List<MemoryPickerOption<T>>,
    selected: T,
    onDismiss: () -> Unit,
    onSelected: (T) -> Unit,
) {
    ModalBottomSheet(containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
sheetState = rememberMemoSheetState(), onDismissRequest = onDismiss, dragHandle = null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MemoSheetHandle(trailingGap = 0.dp)
            options.forEach { opt ->
                MemoSheetOptionRow(
                    label = opt.label,
                    subtitle = opt.subtitle,
                    selected = opt.value == selected,
                    onClick = {
                        onSelected(opt.value)
                        onDismiss()
                    },
                )
            }
        }
    }
}

// ── MemorySheetActions ───────────────────────────────────────────────────────

/** memory_ui.dart L1006-1052 — cancel / confirm footer. */
@Composable
internal fun MemorySheetActions(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    confirmLabel: String,
    confirmEnabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    Row {
        IosTileButton(
            label = stringResource(UiR.string.home_page_cancel),
            icon = Lucide.X,
            onClick = onCancel,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        IosTileButton(
            label = confirmLabel,
            icon = Lucide.Check,
            onClick = onConfirm,
            enabled = confirmEnabled,
            backgroundColor = cs.primary,
            modifier = Modifier.weight(1f),
        )
    }
}

// ── Badges / cards ───────────────────────────────────────────────────────────

/** memory_ui.dart L1435-1475. */
@Composable
internal fun MemoryBadge(label: String, color: Color, onTap: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(MemoRadius.PILL_DP.dp)
    val inner: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .background(withAlpha(color, 0.14), shape)
                .border(1.dp, withAlpha(color, 0.28), shape)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                label,
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color, lineHeight = 12.sp),
            )
        }
    }
    if (onTap == null) {
        inner()
    } else {
        TactileRow(onTap = onTap, haptics = false) { inner() }
    }
}

@Composable
private fun IconAction(icon: ImageVector, color: Color, contentDescription: String, onTap: () -> Unit) {
    TactileRow(onTap = onTap, haptics = false) { pressed ->
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier
                .padding(7.dp)
                .size(18.dp),
            tint = if (pressed) withAlpha(color, 0.7) else color,
        )
    }
}

/** memory_ui.dart L1477-1673 — entry card with badges, actions, meta row. */
@Composable
internal fun MemoryEntryCard(
    entry: MemoryEntry,
    assistantName: String? = null,
    useThisAssistantLabel: Boolean = false,
    selectable: Boolean = false,
    selected: Boolean = false,
    onSelectedChanged: ((Boolean) -> Unit)? = null,
    scopeToggleAssistantId: String? = null,
    onEdit: (() -> Unit)? = null,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onHardDelete: () -> Unit,
    onToggleScope: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current
    val typeColor = memoryTypeColor(cs, entry.type)
    val scopeColor = if (entry.scope == MemoryScope.global) cs.primary else withAlpha(cs.onSurface, 0.65)
    val date = memoryEntryDateFormat.format(Date(entry.updatedAt / 1000))
    val meta = stringResource(UiR.string.memory_entry_updated_at, date) + " · " + memorySourceLabel(entry.source)

    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(app.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(
                0.6.dp,
                if (selected) withAlpha(cs.primary, 0.45) else app.hairline,
                RoundedCornerShape(MemoRadius.INNER_DP.dp),
            )
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selectable) {
                IosCheckbox(
                    value = selected,
                    onValueChanged = { onSelectedChanged?.invoke(it) },
                    size = 20.dp,
                    hitTestSize = 24.dp,
                    borderWidth = 1.6.dp,
                    activeColor = cs.primary,
                    borderColor = withAlpha(cs.primary, 0.55),
                    semanticLabel = stringResource(UiR.string.memory_entry_action_batch_delete),
                )
                Spacer(Modifier.width(6.dp))
            }
            MemoryBadge(label = memoryTypeLabel(entry.type), color = typeColor)
            Spacer(Modifier.width(6.dp))
            MemoryBadge(
                label = memoryScopeLabel(entry, assistantName, useThisAssistantLabel),
                color = scopeColor,
                onTap = if (scopeToggleAssistantId == null) null else onToggleScope,
            )
            Spacer(Modifier.weight(1f))
            if (onEdit != null) {
                IconAction(
                    icon = Lucide.Pencil,
                    color = cs.primary,
                    contentDescription = stringResource(UiR.string.memory_entry_action_edit),
                    onTap = onEdit,
                )
                Spacer(Modifier.width(4.dp))
            }
            IconAction(
                icon = Lucide.Trash2,
                color = cs.error,
                contentDescription = stringResource(UiR.string.memory_entry_action_delete),
                onTap = onHardDelete,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            entry.content,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, color = cs.onSurface),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            meta,
            style = TextStyle(fontSize = 11.5.sp, color = withAlpha(cs.onSurface, 0.55)),
        )
    }
}

/** memory_ui.dart L1703-1760 — model-missing notice card. */
@Composable
internal fun MemoryModelMissingNotice(onGoSelect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .background(withAlpha(cs.errorContainer, 0.30), RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .padding(12.dp),
    ) {
        Row {
            Icon(Lucide.MessageCircleWarning, contentDescription = null, modifier = Modifier.size(18.dp), tint = cs.error)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(UiR.string.memory_model_missing_notice),
                style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = withAlpha(cs.onSurface, 0.8)),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row {
            IosTileButton(
                label = stringResource(UiR.string.memory_model_missing_go_select),
                icon = Lucide.Settings2,
                fontSize = 12.5.sp,
                backgroundColor = cs.primary,
                onClick = onGoSelect,
            )
        }
    }
}

/** memory_ui.dart L1762-1816 — orphan banner (count supplied by caller). */
@Composable
internal fun MemoryOrphanBanner(count: Int, onCleanup: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (count <= 0) return
    Row(
        modifier = Modifier
            .padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 8.dp)
            .background(withAlpha(cs.errorContainer, 0.30), RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.TriangleAlert, contentDescription = null, modifier = Modifier.size(18.dp), tint = cs.error)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(UiR.string.memory_orphan_banner, count.toString()),
            style = TextStyle(fontSize = 12.5.sp, lineHeight = 16.sp, color = withAlpha(cs.onSurface, 0.8)),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        IosTileButton(
            label = stringResource(UiR.string.memory_orphan_cleanup_button),
            icon = Lucide.Trash2,
            fontSize = 12.5.sp,
            backgroundColor = cs.error,
            onClick = onCleanup,
        )
    }
}

/** memory_ui.dart L166-274 combined: dialog host that maps a confirm result. */
@Composable
internal fun MemoryConfirmHost(
    request: MemoryConfirmRequest?,
    onResult: (MemoryConfirmRequest, Boolean) -> Unit,
) {
    when (request) {
        is MemoryConfirmRequest.HardDelete -> ConfirmHardDeleteMemoryDialog { onResult(request, it) }
        is MemoryConfirmRequest.BatchHardDelete -> ConfirmBatchHardDeleteDialog(request.count) { onResult(request, it) }
        is MemoryConfirmRequest.OrphanCleanup -> ConfirmOrphanCleanupDialog(request.count) { onResult(request, it) }
        is MemoryConfirmRequest.ScopeSwitch -> ConfirmScopeSwitchDialog(request.toGlobal) { onResult(request, it) }
        null -> Unit
    }
}

internal sealed interface MemoryConfirmRequest {
    data class HardDelete(val entryId: String) : MemoryConfirmRequest
    data class BatchHardDelete(val count: Int) : MemoryConfirmRequest
    data class OrphanCleanup(val count: Int) : MemoryConfirmRequest
    data class ScopeSwitch(val toGlobal: Boolean) : MemoryConfirmRequest
}

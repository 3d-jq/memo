package com.psyche.memo.ui.chat

import com.psyche.memo.ui.theme.MemoRadius
import com.psyche.memo.ui.overlaySurfaceColor
import com.psyche.memo.ui.rememberMemoSheetState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.ListChecks
import com.composables.icons.lucide.GitFork
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Share
import com.composables.icons.lucide.TextSelect
import com.composables.icons.lucide.Trash
import com.composables.icons.lucide.Trash2
import com.psyche.memo.ui.R as UiR

/** Actions of the message more sheet (message_more_sheet.dart enum). */
enum class MessageMoreAction {
    SELECT_COPY,
    RENDER_WEB_VIEW,
    EDIT,
    SHARE,
    SELECT_MESSAGES,
    FORK,
    DELETE_CURRENT_VERSION,
    DELETE_ALL_VERSIONS,
}

/**
 * 1:1 port of message_more_sheet.dart (mobile bottom sheet): 40x4 drag
 * handle + 48dp action rows (20dp icon, 10dp gap, 15sp medium label),
 * danger rows use colorScheme.error. Item availability mirrors the source:
 * Edit only for non-user messages; Delete All Versions only when the group
 * holds more than one version.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageMoreSheet(
    isUserMessage: Boolean,
    canDeleteAllVersions: Boolean,
    canCreateBranch: Boolean,
    onAction: (MessageMoreAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        dragHandle = null, // 原版自绘 40x4 拖柄，禁用 Material 默认 handle
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        containerColor = cs.overlaySurfaceColor(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 8.dp),
        ) {
            // Drag handle (message_more_sheet.dart L246-256).
            Box(
                modifier = Modifier
                    .padding(top = 6.dp, bottom = 6.dp)
                    .align(Alignment.CenterHorizontally)
                    .size(width = 40.dp, height = 4.dp)
                    .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
            )
            Spacer(Modifier.height(4.dp))
            MoreActionItem(Lucide.TextSelect, UiR.string.message_more_sheet_select_copy) {
                onAction(MessageMoreAction.SELECT_COPY)
            }
            MoreActionItem(Lucide.BookOpen, UiR.string.message_more_sheet_render_web_view) {
                onAction(MessageMoreAction.RENDER_WEB_VIEW)
            }
            // 源码 message_more_sheet.dart:317 —— 仅非用户消息显示 Edit。
            if (!isUserMessage) {
                MoreActionItem(Lucide.Pencil, UiR.string.message_more_sheet_edit) {
                    onAction(MessageMoreAction.EDIT)
                }
            }
            MoreActionItem(Lucide.Share, UiR.string.message_more_sheet_share) {
                onAction(MessageMoreAction.SHARE)
            }
            MoreActionItem(Lucide.ListChecks, UiR.string.message_more_sheet_select_messages) {
                onAction(MessageMoreAction.SELECT_MESSAGES)
            }
            if (canCreateBranch) {
                MoreActionItem(Lucide.GitFork, UiR.string.message_more_sheet_create_branch) {
                    onAction(MessageMoreAction.FORK)
                }
            }
            MoreActionItem(Lucide.Trash2, UiR.string.message_more_sheet_delete, danger = true) {
                onAction(MessageMoreAction.DELETE_CURRENT_VERSION)
            }
            if (canDeleteAllVersions) {
                MoreActionItem(Lucide.Trash, UiR.string.message_more_sheet_delete_all_versions, danger = true) {
                    onAction(MessageMoreAction.DELETE_ALL_VERSIONS)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MoreActionItem(
    icon: ImageVector,
    labelRes: Int,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val fg = if (danger) cs.error else cs.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(48.dp)
            .background(
                cs.surfaceContainerHigh.copy(alpha = 0.6f),
                RoundedCornerShape(MemoRadius.INNER_DP.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(10.dp))
        Text(
            text = androidx.compose.ui.res.stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = fg,
            ),
        )
    }
}

/**
 * 1:1 port of message_edit_sheet.dart: "Save & Send" left / centered title /
 * "Save" right above a multiline editor (rounded 20, hint
 * message_edit_page_hint). Returns the trimmed text with shouldSend flag.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageEditSheet(
    initialContent: String,
    onConfirm: (content: String, shouldSend: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var text by remember { mutableStateOf(initialContent) }
    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        dragHandle = null, // 原版自绘 40x4 拖柄，禁用 Material 默认 handle
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        containerColor = cs.overlaySurfaceColor(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.ime)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            // 源码 message_edit_sheet.dart:64-73 —— 40x4 drag handle。
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 40.dp, height = 4.dp)
                    .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth().height(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 源码 message_edit_sheet.dart:80-108 —— 左"Save & Send"、
                // 中标题、右"Save"（左贴左、右贴右）。
                Box(modifier = Modifier.weight(1f)) {
                    TextButton(
                        onClick = { onConfirm(text.trim(), true) },
                        modifier = Modifier.align(Alignment.CenterStart),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(
                            text = androidx.compose.ui.res.stringResource(
                                UiR.string.message_edit_page_save_and_send,
                            ),
                            color = cs.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(
                    text = androidx.compose.ui.res.stringResource(UiR.string.message_edit_page_title),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Box(modifier = Modifier.weight(1f)) {
                    TextButton(
                        onClick = { onConfirm(text.trim(), false) },
                        modifier = Modifier.align(Alignment.CenterEnd),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(
                            text = androidx.compose.ui.res.stringResource(UiR.string.message_edit_page_save),
                            color = cs.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // 源码 message_edit_sheet.dart:152-181 —— 多行编辑器 minLines 8。
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().height(240.dp),
                placeholder = {
                    Text(androidx.compose.ui.res.stringResource(UiR.string.message_edit_page_hint))
                },
                minLines = 8,
                maxLines = Int.MAX_VALUE,
                shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = cs.primary.copy(alpha = 0.45f),
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = cs.surfaceVariant.copy(alpha = 0.4f),
                    unfocusedContainerColor = cs.surfaceVariant.copy(alpha = 0.4f),
                ),
            )
        }
    }
}

/**
 * 1:1 port of chat_message_widget.dart:1328-1358 _confirmRegeneration.
 * The port always deletes the trailing messages, so the content variant is
 * the delete-trailing one.
 */
@Composable
fun RegenerateConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = onDismiss,
        title = {
            Text(
                androidx.compose.ui.res.stringResource(
                    UiR.string.chat_message_widget_regenerate_confirm_title,
                ),
            )
        },
        text = {
            Text(
                androidx.compose.ui.res.stringResource(
                    UiR.string.chat_message_widget_regenerate_confirm_delete_trailing_content,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    androidx.compose.ui.res.stringResource(
                        UiR.string.chat_message_widget_regenerate_confirm_ok,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    androidx.compose.ui.res.stringResource(
                        UiR.string.chat_message_widget_regenerate_confirm_cancel,
                    ),
                )
            }
        },
    )
}

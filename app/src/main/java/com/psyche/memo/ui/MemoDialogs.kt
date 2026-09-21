package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psyche.memo.ui.theme.LocalSemanticColors

/** overlaySurface 的快捷取色：`containerColor = cs.overlaySurfaceColor()`。 */
@Composable
fun ColorScheme.overlaySurfaceColor(): Color =
    LocalSemanticColors.current.overlaySurface(this)

/**
 * 全站对话框/sheet 的共享样式壳（UI_AUDIT_2026-09-12 H1/H2/M1/M2/M3 的修法落点）。
 *
 * 合规基线 = backup 对话框三件套（BackupImportModeDialog 等）：**r16 圆角 +
 * overlaySurface 底 + 标题 16sp SemiBold + 正文 14sp + 取消钮 onSurface@74% /
 * 确认钮 primary（破坏性 = error）**。M3 默认的 28dp 圆角与裸 surface 底从此
 * 不再出现在新代码里；旧调用点按审计清单逐个迁进来。
 *
 * 与 Flutter 源的对应：`showModalBottomSheet(backgroundColor:
 * context.overlaySurface, shape: 16)` / `showDialog` 同款参数。
 */

/** 对话框文字按钮：取消 = onSurface@74%，确认 = primary / error（破坏性）。 */
@Composable
private fun MemoDialogTextButton(
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) {
        Text(text = label, style = TextStyle(fontSize = 14.sp, color = color))
    }
}

/**
 * 全站标准 AlertDialog：r16 + overlaySurface。带 [destructive] 时确认钮走
 * error 色（删除确认等破坏性对话框的基线）。
 *
 * 简单场景直接传 [text]；复杂正文传 [content]（与 [text] 二选一，[text] 优先）。
 */
@Composable
fun MemoAlertDialog(
    onDismiss: () -> Unit,
    title: String? = null,
    text: String? = null,
    content: (@Composable () -> Unit)? = null,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    destructive: Boolean = false,
    dismissLabel: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
        title = title?.let { t ->
            {
                Text(
                    text = t,
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                )
            }
        },
        text = {
            if (text != null) {
                Text(
                    text = text,
                    style = TextStyle(fontSize = 14.sp, color = cs.onSurface),
                )
            } else if (content != null) {
                content()
            }
        },
        confirmButton = {
            if (confirmLabel != null && onConfirm != null) {
                MemoDialogTextButton(
                    label = confirmLabel,
                    color = if (destructive) cs.error else cs.primary,
                    onClick = onConfirm,
                )
            }
        },
        dismissButton = {
            if (dismissLabel != null) {
                MemoDialogTextButton(
                    label = dismissLabel,
                    color = cs.onSurface.copy(alpha = 0.74f),
                    onClick = onDismiss,
                )
            }
        },
    )
}

/**
 * 全站标准底部 sheet：r16 顶圆角 + overlaySurface + 拖柄（MemoSheetHandle）+
 * 居中标题（15sp SemiBold）。内容列自带 16dp 左右 / 底部 16dp 内边距，标题下
 * 10dp，与合规样例（MessageExportSheet）同节奏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        containerColor = semantic.overlaySurface(cs),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
        ) {
            MemoSheetHandle()
            Spacer(Modifier.height(10.dp))
            if (title != null) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = title,
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

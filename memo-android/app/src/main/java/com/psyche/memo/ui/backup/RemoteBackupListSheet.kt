package com.psyche.memo.ui.backup

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Import
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2
import com.psyche.memo.ui.IosIconButton
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.R as UiR

/** One row of a remote backup list — the fields the sheet renders. */
data class RemoteBackupRow(
    val displayName: String,
    val size: Long,
)

/**
 * 远端备份列表 sheet（backup_page.dart `_RemoteListSheet` L2239-2382）：拖拽
 * 高度 0.4–0.9（初始 0.6）、拖柄 + 居中标题、行 = surfaceFill r12 + 0.18 边框，
 * 名称（2 行 semibold）+ 字节大小（12sp/70%），行尾 恢复（Import）/ 删除
 * （Trash2，error 色）。空态 `backupPageNoBackups`。
 *
 * Generic over the source item so WebDAV and S3 share one sheet — the original
 * has a single `_RemoteListSheet` for both.
 */
/**
 * 远端列表的 item key：显示名优先（同一目录/前缀的 href/key 唯一），
 * 万一上游返回了同名条目就补 `#2`、`#3` —— LazyList 撞 key 是直接抛异常的。
 */
internal fun remoteBackupRowKeys(names: List<String>): List<String> {
    val seen = HashMap<String, Int>()
    return names.map { name ->
        val times = seen.merge(name, 1) { old, one -> old + one } ?: 1
        if (times == 1) name else "$name#$times"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> RemoteBackupListSheet(
    items: List<T>,
    rowOf: (T) -> RemoteBackupRow,
    onRestore: (T) -> Unit,
    onDelete: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 一次投影：以前 `rowOf(item)` 摊在 item 组合体里，每行每次重组都分配一个
    // RemoteBackupRow；连带 key 也从这里取（同名时补序号，见 [remoteBackupRowKeys]）。
    val rows = remember(items) { items.map { it to rowOf(it) } }
    val rowKeys = remember(rows) { remoteBackupRowKeys(rows.map { it.second.displayName }) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = semantic.overlaySurface(cs),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(420.dp)
                .padding(horizontal = 12.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 42.dp, height = 4.dp)
                    .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(UiR.string.backup_page_remote_backups),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                textAlign = TextAlign.Center,
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
            )
            Spacer(Modifier.height(10.dp))
            if (items.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(UiR.string.backup_page_no_backups),
                        style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    items(rows.size, key = { rowKeys[it] }) { index ->
                        val (item, row) = rows[index]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .background(semantic.surfaceCardFill, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                                .border(
                                    1.dp,
                                    cs.outlineVariant.copy(alpha = 0.18f),
                                    RoundedCornerShape(MemoRadius.INNER_DP.dp),
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                Modifier.weight(1f),
                                horizontalAlignment = Alignment.Start,
                            ) {
                                Text(
                                    row.displayName,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = cs.onSurface,
                                    ),
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    formatRemoteBytes(row.size),
                                    style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.7f)),
                                )
                            }
                            IosIconButton(
                                icon = Lucide.Import,
                                onTap = { onRestore(item) },
                                color = cs.primary,
                            )
                            Spacer(Modifier.width(6.dp))
                            IosIconButton(
                                icon = Lucide.Trash2,
                                onTap = { onDelete(item) },
                                color = cs.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** `_fmtBytes`（backup_page.dart L2384+）——复用共享的 `formatBytes`。 */
internal fun formatRemoteBytes(bytes: Long): String = formatBytes(bytes)

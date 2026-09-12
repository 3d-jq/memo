package com.psyche.memo.ui.backup

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Import
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2
import com.psyche.memo.data.backup.WebDavFileItem
import com.psyche.memo.ui.IosIconButton
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.R as UiR

/**
 * 远端备份列表 sheet（backup_page.dart `_RemoteListSheet` L2239-2382）：拖拽
 * 高度 0.4–0.9（初始 0.6）、拖柄 + 居中标题、行 = surfaceFill r12 + 0.18 边框，
 * 名称（2 行 semibold）+ 字节大小（12sp/70%），行尾 恢复（Import）/ 删除
 * （Trash2，error 色）。空态 `backupPageNoBackups`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavRemoteListSheet(
    items: List<WebDavFileItem>,
    onRestore: (WebDavFileItem) -> Unit,
    onDelete: (WebDavFileItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = semantic.overlaySurface(cs),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
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
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
                    items(items.size) { index ->
                        val item = items[index]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .background(semantic.surfaceCardFill, RoundedCornerShape(12.dp))
                                .border(
                                    1.dp,
                                    cs.outlineVariant.copy(alpha = 0.18f),
                                    RoundedCornerShape(12.dp),
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                Modifier.weight(1f),
                                horizontalAlignment = Alignment.Start,
                            ) {
                                Text(
                                    item.displayName,
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
                                    formatWebDavBytes(item.size),
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
internal fun formatWebDavBytes(bytes: Long): String = formatBytes(bytes)

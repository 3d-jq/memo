package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import androidx.compose.ui.platform.LocalView
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.Haptics
import com.psyche.memo.data.repo.WorldBookRepository
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.withAlpha

/**
 * Port of world_book_sheet.dart: the per-assistant world-book picker opened
 * from the bottom tools sheet. Tapping a row toggles it for [assistantId];
 * disabled books can only be deselected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldBookSheet(
    container: AppContainerImpl,
    assistantId: String?,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val view = LocalView.current
    val repo = remember(container) {
        WorldBookRepository(container.database.writableDatabase, container.preferenceRepository)
    }
    var reload by remember { mutableIntStateOf(0) }
    val books = remember(reload) { repo.books() }
    val activeIds = remember(reload, assistantId) { repo.activeIds(assistantId).toSet() }

    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        containerColor = semantic.overlaySurface(cs),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        dragHandle = null,
    ) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IosIconButton(
                    icon = Lucide.ArrowLeft,
                    onTap = onDismiss,
                    color = cs.onSurface,
                    size = 20.dp,
                    contentPadding = 10.dp,
                    semanticLabel = stringResource(R.string.settings_page_back_button),
                )
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.world_book_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                    )
                }
                Spacer(Modifier.width(40.dp))
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
            ) {
                if (books.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.world_book_empty_message),
                                style = TextStyle(color = withAlpha(cs.onSurface, 0.6)),
                            )
                        }
                    }
                }
                items(books, key = { it.id }) { book ->
                    val selected = book.id in activeIds
                    val disabled = !book.enabled
                    val onColor = if (selected) cs.primary else cs.onSurface
                    val opacity = if (disabled) 0.55f else 1f
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .height(if (book.description.trim().isEmpty()) 52.dp else 66.dp)
                            .background(
                                if (ThemeState.useLayeredSheetTiles) semantic.surfaceCardFill else Color.Transparent,
                                RoundedCornerShape(MemoRadius.INNER_DP.dp),
                            )
                            .clickable(enabled = !disabled || selected) {
                                Haptics.light(view)
                                repo.toggleActive(book.id, assistantId)
                                reload++
                            }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Lucide.BookOpen,
                            contentDescription = null,
                            tint = withAlpha(onColor, opacity.toDouble()),
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = book.name.trim().ifEmpty { stringResource(R.string.world_book_unnamed) },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = withAlpha(onColor, opacity.toDouble()),
                                ),
                            )
                            if (book.description.trim().isNotEmpty()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = book.description.trim(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(
                                        fontSize = 12.5.sp,
                                        color = withAlpha(cs.onSurface, 0.55 * opacity.toDouble()),
                                    ),
                                )
                            }
                        }
                        if (selected) {
                            Icon(
                                Lucide.Check,
                                contentDescription = null,
                                tint = withAlpha(cs.primary, opacity.toDouble()),
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            Spacer(Modifier.width(18.dp))
                        }
                    }
                }
            }
        }
    }
}

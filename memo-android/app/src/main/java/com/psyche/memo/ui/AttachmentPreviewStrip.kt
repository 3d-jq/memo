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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Paperclip
import com.composables.icons.lucide.X
import com.psyche.memo.ChatViewModel
import com.psyche.memo.ui.theme.LocalSemanticColors
import java.io.File

/**
 * Port of chat_input_bar.dart's attachment preview area: 64dp image thumbs
 * (r10 border, scrim remove badge) and 48dp document chips.
 */
@Composable
fun AttachmentPreviewStrip(
    attachments: List<ChatViewModel.PendingAttachment>,
    onRemove: (Int) -> Unit,
) {
    if (attachments.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val isDark = semantic.isDark
    val previewFill = cs.onSurface.copy(alpha = if (isDark) 0.08f else 0.045f)
    val previewBorder = if (isDark) cs.onSurface.copy(alpha = 0.10f) else cs.outline.copy(alpha = 0.13f)

    Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 4.dp)) {
        val images = attachments.withIndex().filter { it.value.isImage }
        val docs = attachments.withIndex().filter { !it.value.isImage }
        if (images.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(images) { _, indexed ->
                    Box(modifier = Modifier.size(64.dp)) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .border(1.dp, previewBorder, RoundedCornerShape(MemoRadius.SMALL_DP.dp))
                                .padding(1.dp),
                        ) {
                            coil.compose.AsyncImage(
                                model = File(indexed.value.uri),
                                contentDescription = indexed.value.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(62.dp).background(previewFill, RoundedCornerShape(MemoRadius.SMALL_DP.dp)),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(20.dp)
                                .background(
                                    cs.scrim.copy(alpha = if (isDark) 0.50f else 0.46f),
                                    CircleShape,
                                )
                                .clickable { onRemove(indexed.index) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Lucide.X,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(11.dp),
                            )
                        }
                    }
                }
            }
            if (docs.isNotEmpty()) Spacer(Modifier.height(6.dp))
        }
        if (docs.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(docs) { _, indexed ->
                    Row(
                        modifier = Modifier
                            .height(48.dp)
                            .background(previewFill, RoundedCornerShape(MemoRadius.SMALL_DP.dp))
                            .border(1.dp, previewBorder, RoundedCornerShape(MemoRadius.SMALL_DP.dp))
                            .padding(start = 10.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Lucide.Paperclip, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = indexed.value.name,
                            style = TextStyle(fontSize = 13.sp, color = cs.onSurface),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(120.dp),
                        )
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clickable { onRemove(indexed.index) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Lucide.X, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}

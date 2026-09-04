package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.psyche.memo.AppContainerImpl

data class ModelOption(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val selected: Boolean = false,
)

/**
 * Mirrors memo's model_select_sheet.dart: the mobile picker is a
 * showModalBottomSheet(isScrollControlled: true) whose body is a
 * DraggableScrollableSheet with initial/max size 0.8 and min 0.4 of the
 * screen. The sheet is therefore always 80% tall, never content-sized:
 *
 *   fixed header (40x4 centered drag indicator + search field),
 *   Expanded grouped model list,
 *   fixed bottom provider-chip bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectSheet(
    container: AppContainerImpl,
    options: List<ModelOption>,
    onSelect: (ModelOption) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val cs = MaterialTheme.colorScheme
    val providers = remember(options) { options.map { it.providerName }.distinct() }
    // DraggableScrollableSheet initial/maxChildSize = 0.8.
    val sheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.8f

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = cs.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        // memo draws its own handle; Material3's built-in one is suppressed.
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight),
        ) {
            // Header: centered drag handle 40x4 α0.2 r999, 8dp above and below.
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(999.dp)),
                )
                Spacer(Modifier.height(8.dp))
            }

            // Search field: filled r14, Search prefix, optional Bookmark suffix.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(cs.surfaceVariant, RoundedCornerShape(14.dp))
                    .border(1.dp, cs.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(14.dp)),
            ) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            UIStrings.modelSearchHint(),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Lucide.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = cs.onSurface.copy(alpha = 0.6f),
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { /* favorites */ }) {
                            Icon(
                                Lucide.Bookmark,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = cs.onSurface.copy(alpha = 0.7f),
                            )
                        }
                    },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = cs.onSurface,
                        unfocusedTextColor = cs.onSurface,
                        cursorColor = cs.primary,
                    ),
                )
            }

            // Scrollable grouped model list fills the rest of the sheet.
            val filtered = options.filter {
                query.isBlank() ||
                    it.modelId.contains(query, ignoreCase = true) ||
                    it.providerName.contains(query, ignoreCase = true)
            }
            val grouped = filtered.groupBy { it.providerName }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                grouped.forEach { (providerName, models) ->
                    item(key = "h_$providerName") {
                        Text(
                            text = providerName,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = cs.onSurface.copy(alpha = 0.6f),
                            ),
                        )
                    }
                    items(models, key = { "${it.providerId}::${it.modelId}" }) { option ->
                        ModelTile(option, onClick = { onSelect(option); onDismiss() })
                    }
                }
            }

            // Bottom provider-chip bar.
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(providers) { providerName ->
                    ProviderChip(
                        name = providerName,
                        selected = providerName == options.firstOrNull { it.selected }?.providerName,
                        onClick = { /* filter by provider — P2 scroll */ },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelTile(option: ModelOption, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg = if (option.selected) cs.primary.copy(alpha = 0.08f) else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Brand avatar 28px, primary α0.1, initial letter.
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(cs.primary.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = option.providerName.take(1).uppercase(),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, color = cs.primary),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.modelId,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "| ${option.providerName}",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 12.sp,
                    color = cs.onSurface.copy(alpha = 0.6f),
                ),
                maxLines = 1,
            )
        }
        // Favorite toggle: 36px touch target.
        Box(
            modifier = Modifier
                .size(36.dp)
                .clickable { /* toggle pinned model */ },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.FavoriteBorder,
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ProviderChip(name: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg = if (selected) cs.primary.copy(alpha = 0.08f) else cs.surface
    Row(
        modifier = Modifier
            .background(bg, RoundedCornerShape(14.dp))
            .border(1.dp, cs.outlineVariant.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .background(cs.primary.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1).uppercase(), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, color = cs.onSurface),
            maxLines = 1,
        )
    }
}

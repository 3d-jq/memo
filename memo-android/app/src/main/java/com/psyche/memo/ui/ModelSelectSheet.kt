package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import com.psyche.memo.common.Haptics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.rememberLazyListState
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Wrench
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Type
import com.psyche.memo.AppContainerImpl

data class ModelOption(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val selected: Boolean = false,
)

/** provider_rows → picker options (kelivo showModelSelector's option list). */
fun loadModelOptions(
    container: AppContainerImpl,
    selectedProviderId: String?,
    selectedModelId: String?,
): List<ModelOption> = com.psyche.memo.data.db.PayloadEntityDao(
    container.database.readableDatabase,
    "provider_rows",
    primaryKey = "provider_key",
).getAll().flatMap { row ->
    val config = runCatching {
        com.psyche.memo.data.model.ProviderConfig.fromJsonString(
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
            row.payload,
        )
    }.getOrNull() ?: return@flatMap emptyList()
    config.models.map { id ->
        ModelOption(
            providerId = config.id,
            providerName = config.name,
            modelId = id,
            selected = id == selectedModelId && config.id == selectedProviderId,
        )
    }
}

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
    onOptionsInvalidated: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // Pinned models — Flutter SettingsProvider.pinnedModels
    // (pinned_models_v1, "providerKey::modelId" entries).
    val pinned = remember { mutableStateOf(readPinnedModels(container)) }
    // Long-press opens the model detail sheet (model_detail_sheet.dart
    // _modelTile onLongPress); a successful save refreshes the options.
    var detailTarget by remember { mutableStateOf<ModelOption?>(null) }
    fun togglePinned(providerId: String, modelId: String) {
        val key = "$providerId::$modelId"
        val next = pinned.value.toMutableSet()
        if (!next.add(key)) next.remove(key)
        pinned.value = next
        scope.launch(Dispatchers.IO) {
            writePinnedModels(container, next)
        }
    }
    // _jumpToFavorites (L1527): clear the search then scroll to the
    // favorites header, which sits at index 0 whenever it exists.
    fun jumpToFavorites() {
        if (query.isNotBlank()) query = ""
        scope.launch { listState.animateScrollToItem(0) }
    }
    val cs = MaterialTheme.colorScheme
    val providers = remember(options) { options.map { it.providerName }.distinct() }
    // DraggableScrollableSheet initial/maxChildSize = 0.8.
    val sheetHeight = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp() * 0.8f
    }

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

            val semantic = LocalSemanticColors.current
            val semanticSearch = semantic
            val searchInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val searchFocused by searchInteraction.collectIsFocusedAsState()
            // Search field: filled r14, Search prefix, optional Bookmark suffix.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    // Flutter InputDecoration: filled surfaceFill, r14
                    // border outlineVariant 40%, focused primary 50%.
                    .background(semanticSearch.surfaceFill, RoundedCornerShape(14.dp))
                    .border(
                        1.dp,
                        if (searchFocused) cs.primary.copy(alpha = 0.5f) else cs.outlineVariant.copy(alpha = 0.4f),
                        RoundedCornerShape(14.dp),
                    ),
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
                        // Bookmark suffix appears only when pins exist and no
                        // provider limit (L905-933); tap = _jumpToFavorites.
                        if (pinned.value.isNotEmpty()) {
                            IconButton(onClick = { jumpToFavorites() }) {
                                Icon(
                                    Lucide.Bookmark,
                                    contentDescription = stringResource(UiR.string.model_select_sheet_favorites_section),
                                    modifier = Modifier.size(18.dp),
                                    tint = cs.onSurface.copy(alpha = 0.7f),
                                )
                            }
                        }
                    },
                    singleLine = true,
                    interactionSource = searchInteraction,
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
            // Favorites aggregation (L1016-1082): pins matching the search
            // ride on top and are removed from their own group while
            // searching; with no search they show on top AND in place.
            val favs = filtered.filter { it.providerId + "::" + it.modelId in pinned.value }
            val favKeys = favs.map { it.providerId + "::" + it.modelId }.toSet()
            val groupedDisplay = if (query.isBlank()) grouped
            else grouped
                .mapValues { (_, models) ->
                    models.filter { "${it.providerId}::${it.modelId}" !in favKeys }
                }
                .filterValues { it.isNotEmpty() }
            val favOffset = if (favs.isNotEmpty()) 1 + favs.size else 0

            val headerIndex = remember(groupedDisplay, favOffset) {
                buildMap {
                    var i = favOffset
                    groupedDisplay.forEach { (p, models) ->
                        put(p, i)
                        i += 1 + models.size
                    }
                }
            }
            // _scrollToFirstSearchGroup: on entering search, jump to the
            // favorites header when present, else the first matching group.
            var lastQueryForJump by remember { mutableStateOf("") }
            LaunchedEffect(query) {
                val entering = lastQueryForJump.isBlank() && query.isNotBlank()
                lastQueryForJump = query
                if (entering) {
                    val target = if (favs.isNotEmpty()) 0 else headerIndex.values.firstOrNull()
                    target?.let { listState.scrollToItem(it) }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                // Favorites group rides on top whenever pins exist
                // (L1016-1070); rows carry the provider label (L1059).
                if (favs.isNotEmpty()) {
                    item(key = "h__favorites") {
                        Text(
                            text = stringResource(UiR.string.model_select_sheet_favorites_section),
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = cs.onSurface.copy(alpha = 0.6f),
                            ),
                        )
                    }
                    items(favs, key = { "fav::${it.providerId}::${it.modelId}" }) { option ->
                        ModelTile(option, pinned = pinned.value, onTogglePin = ::togglePinned, showProviderLabel = true, onClick = { onSelect(option); onDismiss() }, onLongClick = { scope.launch { delay(220); detailTarget = option } })
                    }
                }
                groupedDisplay.forEach { (providerName, models) ->
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
                        ModelTile(option, pinned = pinned.value, onTogglePin = ::togglePinned, onClick = { onSelect(option); onDismiss() }, onLongClick = { scope.launch { delay(220); detailTarget = option } })
                    }
                }
            }

            // Model detail sheet stacks on top of the picker (Flutter opens
            // showModalBottomSheet over the open sheet).
            detailTarget?.let { target ->
                ModelDetailSheet(
                    container = container,
                    providerKey = target.providerId,
                    modelId = target.modelId,
                    onDismiss = { saved ->
                        detailTarget = null
                        if (saved) onOptionsInvalidated()
                    },
                )
            }

            // Bottom provider-chip bar.
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = if (providers.size == 1) Arrangement.Center else Arrangement.spacedBy(8.dp),
            ) {
                items(providers) { providerName ->
                    ProviderChip(
                        name = providerName,
                        selected = providerName == options.firstOrNull { it.selected }?.providerName,
                        onClick = {
                            // Jump to the provider's group header (sticky header lands with A2c).
                            headerIndex[providerName]?.let { idx ->
                                scope.launch { listState.animateScrollToItem(idx) }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ModelTile(
    option: ModelOption,
    pinned: Set<String>,
    onTogglePin: (String, String) -> Unit,
    showProviderLabel: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val bg = if (option.selected) cs.primary.copy(alpha = 0.08f) else semantic.surfaceCard
    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderAvatarSmall(option.providerId, option.modelId, 28.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (showProviderLabel) {
                // Favorites row: name plus " | provider" suffix at 12sp 60%
                // (model_select_sheet.dart L1403-1425).
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)) {
                            append(option.modelId)
                        }
                        withStyle(SpanStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.6f))) {
                            append(" | ${option.providerName}")
                        }
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = option.modelId,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSurface,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            ModelTagRow(modelId = option.modelId)
        }
        // Favorite toggle — solid heart when pinned (settings.togglePinModel).
        val pinKey = option.providerId + "::" + option.modelId
        val pinnedNow = pinKey in pinned
        val view = LocalView.current
        Box(
            modifier = Modifier
                .size(36.dp)
                .clickable {
                    onTogglePin(option.providerId, option.modelId)
                    Haptics.light(view)
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (pinnedNow) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = stringResource(UiR.string.model_select_sheet_favorite_tooltip),
                tint = cs.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** model_tag_wrap.dart — chat-type + I/O modality + tools/reasoning pills. */
@Composable
internal fun ModelTagRow(modelId: String) {
    val cs = MaterialTheme.colorScheme
    val traits = remember(modelId) { com.psyche.memo.ModelRegistry.infer(modelId) }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill(color = cs.primary) {
            Text(
                text = stringResource(UiR.string.model_select_sheet_chat_type),
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = cs.primary),
            )
        }
        Pill(color = cs.tertiary) {
            Icon(Lucide.Type, contentDescription = null, tint = cs.tertiary.copy(alpha = 0.9f), modifier = Modifier.size(12.dp))
            if (traits.visionInput) {
                Icon(Lucide.Image, contentDescription = null, tint = cs.tertiary.copy(alpha = 0.9f), modifier = Modifier.size(12.dp))
            }
            Icon(Lucide.ChevronRight, contentDescription = null, tint = cs.tertiary.copy(alpha = 0.9f), modifier = Modifier.size(12.dp))
            // Chat output defaults to text (tag_wrap outputMods fallback).
            Icon(Lucide.Type, contentDescription = null, tint = cs.tertiary.copy(alpha = 0.9f), modifier = Modifier.size(12.dp))
        }
        if (traits.tool) {
            Pill(color = cs.secondary) {
                Icon(Lucide.Wrench, contentDescription = null, tint = cs.secondary.copy(alpha = 0.9f), modifier = Modifier.size(12.dp))
            }
        }
        if (traits.reasoning) {
            Pill(color = cs.secondary) {
                Icon(Lucide.Brain, contentDescription = null, tint = cs.secondary.copy(alpha = 0.9f), modifier = Modifier.size(12.dp))
            }
        }
    }
}

@Composable
private fun Pill(color: Color, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(999.dp))
            .border(0.5.dp, color.copy(alpha = 0.2f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
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


/** pinned_models_v1 — StringList of "providerKey::modelId" (settings_provider.dart L838). */
internal fun readPinnedModels(container: com.psyche.memo.AppContainerImpl): Set<String> =
    runCatching {
        val raw = container.preferenceRepository.readJson("pinned_models_v1") ?: return emptySet()
        kotlinx.serialization.json.Json.decodeFromString<List<String>>(raw).toSet()
    }.getOrDefault(emptySet())

internal fun writePinnedModels(container: com.psyche.memo.AppContainerImpl, entries: Set<String>) {
    runCatching {
        container.preferenceRepository.writeJson(
            "pinned_models_v1",
            kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer<List<String>>(), entries.toList()),
        )
    }
}

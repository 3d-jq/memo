package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.rememberLazyListState
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Hammer
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Wrench
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
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

/** 思考能力胶囊的图标（与品牌头像同走 [cachedSvgIcon]，避免每行冷解码）。 */
private const val DEEPTHINK_ASSET = "file:///android_asset/icons/deepthink.svg"

/**
 * 行内胶囊/卡片的圆角形状提成常量：`RoundedCornerShape(...)` 每次组合都新造一个
 * 实例，修饰符链的等值比较就永远是「变了」。
 */
private val SheetPillShape = RoundedCornerShape(MemoRadius.PILL_DP.dp)
private val SheetInnerShape = RoundedCornerShape(MemoRadius.INNER_DP.dp)

/** 收藏组之外的一个供应商分组（组名 = 供应商名，保持 options 里的服务端顺序）。 */
internal data class ModelPickerGroup(val name: String, val models: List<ModelOption>)

/**
 * 选择面板的列表派生结果：搜索命中、收藏置顶、组头下标（provider chip 点跳用）。
 * 纯函数，被 [ModelSelectSheet] 用 `remember(options, query, pinned)` 缓存。
 */
internal class ModelPickerLayout(
    val favorites: List<ModelOption>,
    val groups: List<ModelPickerGroup>,
    val favoriteOffset: Int,
    val headerIndex: Map<String, Int>,
)

/**
 * Favorites aggregation (L1016-1082): pins matching the search ride on top and
 * are removed from their own group while searching; with no search they show on
 * top AND in place.
 */
internal fun buildModelPickerLayout(
    options: List<ModelOption>,
    query: String,
    pinned: Set<String>,
): ModelPickerLayout {
    val filtered = options.filter {
        query.isBlank() ||
            it.modelId.contains(query, ignoreCase = true) ||
            it.providerName.contains(query, ignoreCase = true)
    }
    val grouped = filtered.groupBy { it.providerName }
    val favs = filtered.filter { it.providerId + "::" + it.modelId in pinned }
    val favKeys = favs.mapTo(HashSet(favs.size)) { it.providerId + "::" + it.modelId }
    val groups = if (query.isBlank()) {
        grouped.map { (name, models) -> ModelPickerGroup(name, models) }
    } else {
        grouped
            .mapValues { (_, models) -> models.filter { "${it.providerId}::${it.modelId}" !in favKeys } }
            .filterValues { it.isNotEmpty() }
            .map { (name, models) -> ModelPickerGroup(name, models) }
    }
    val favoriteOffset = if (favs.isNotEmpty()) 1 + favs.size else 0
    val headerIndex = buildMap {
        var i = favoriteOffset
        groups.forEach { group ->
            put(group.name, i)
            i += 1 + group.models.size
        }
    }
    return ModelPickerLayout(favs, groups, favoriteOffset, headerIndex)
}

/**
 * 一行的三个动作。提成 `@Stable` 对象（实例在面板存活期内不变）是为了让
 * [ModelTile] 保持**可跳过**：以前每行现造 onClick/onLongClick/onTogglePin 三个
 * lambda，面板一重组（拖 sheet、翻收藏、改搜索词）整屏行都得重组合 + 重解码图标。
 */
@Stable
internal class ModelTileActions(
    val select: (ModelOption) -> Unit,
    val openDetail: (ModelOption) -> Unit,
    val togglePin: (String, String) -> Unit,
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
 * DraggableScrollableSheet sizes for the picker (model_select_sheet.dart
 * `_initialSize`/`_maxSize` L334-335 = 0.8, `minChildSize` L843 = 0.4).
 */
private const val MODEL_SELECT_INITIAL_FRACTION = 0.80f
private const val MODEL_SELECT_MIN_FRACTION = 0.40f

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
    // 能力标签要叠加模型 override（effectiveModelInfo）→ 每个 provider 取一次配置。
    val modelCfgs = remember(options) {
        options.map { it.providerId }.distinct()
            .associateWith { container.providerConfig(it) }
    }
    // Long-press opens the model detail sheet (model_detail_sheet.dart
    // _modelTile onLongPress); a successful save refreshes the options.
    var detailTarget by remember { mutableStateOf<ModelOption?>(null) }
    val onSelectRef = rememberUpdatedState(onSelect)
    val onDismissRef = rememberUpdatedState(onDismiss)
    // 行内三个动作提成「面板存活期内不变」的一个 @Stable 对象。此前每行现造
    // onClick/onLongClick/onTogglePin 三个 lambda，参数永远算「变了」，面板一重组
    // （拖 sheet、翻收藏、改搜索词）整屏行都得重组合 —— 顺带每行重解码一次 SVG。
    val rowActions = remember(pinned, container, scope) {
        ModelTileActions(
            select = { option ->
                onSelectRef.value(option)
                onDismissRef.value()
            },
            openDetail = { option ->
                scope.launch { delay(220); detailTarget = option }
            },
            togglePin = { providerId, modelId ->
                val key = "$providerId::$modelId"
                val next = pinned.value.toMutableSet()
                if (!next.add(key)) next.remove(key)
                pinned.value = next
                scope.launch(Dispatchers.IO) { writePinnedModels(container, next) }
            },
        )
    }
    // _jumpToFavorites (L1527): clear the search then scroll to the
    // favorites header, which sits at index 0 whenever it exists.
    fun jumpToFavorites() {
        if (query.isNotBlank()) query = ""
        scope.launch { listState.animateScrollToItem(0) }
    }
    val cs = MaterialTheme.colorScheme
    val providers = remember(options) { options.map { it.providerName }.distinct() }
    // 搜索命中 + 收藏置顶 + 组头下标：整表派生一次，随 options/query/pinned 才重算
    // （此前在 sheet 内容 lambda 里，拖 sheet 每帧都重算）。
    val pickerLayout = remember(options, query, pinned.value) {
        buildModelPickerLayout(options, query, pinned.value)
    }
    // DraggableScrollableSheet (model_select_sheet.dart L838-843): initial and
    // max child size 0.8 (`_initialSize`/`_maxSize` L334-335), min 0.4. Its
    // linked scroll is ported the Compose-native way — a NestedScrollConnection
    // lets the list drive the sheet height. Since initial == max there is
    // nothing to grow into, so only the shrink half exists: pulling the list
    // down at its top edge shrinks the sheet, and reaching 0.4 dismisses it
    // (`shouldCloseOnMinExtent`, same as ModelDetailSheet).
    val screenHpx = LocalWindowInfo.current.containerSize.height.toFloat()
    var sheetFraction by remember { mutableFloatStateOf(MODEL_SELECT_INITIAL_FRACTION) }
    var sheetClosing by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetResizeConnection = object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            val dy = available.y
            // Finger down with the list already at its top edge: shrink the
            // sheet; hitting the min extent closes it at once (hide() slides
            // the sheet out, onDismissRequest fires after).
            if (dy > 0 && !listState.canScrollBackward && sheetFraction > MODEL_SELECT_MIN_FRACTION) {
                val remaining = sheetFraction - MODEL_SELECT_MIN_FRACTION
                val shrink = (dy / screenHpx).coerceAtMost(remaining)
                sheetFraction -= shrink
                if (shrink >= remaining && !sheetClosing) {
                    sheetClosing = true
                    scope.launch { sheetState.hide() }
                }
                return Offset(0f, shrink * screenHpx)
            }
            return Offset.Zero
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = cs.overlaySurfaceColor(),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        // memo draws its own handle; Material3's built-in one is suppressed.
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(LocalDensity.current) { screenHpx.toDp() } * sheetFraction)
                .nestedScroll(sheetResizeConnection),
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
                        .background(cs.onSurface.copy(alpha = 0.2f), SheetPillShape),
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
                    .background(semanticSearch.surfaceFill, SheetInnerShape)
                    .border(
                        1.dp,
                        if (searchFocused) cs.primary.copy(alpha = 0.5f) else cs.outlineVariant.copy(alpha = 0.4f),
                        SheetInnerShape,
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
            // 派生数据在 [ModelSelectSheet] 外层一次算好并 remember（这里只读）：
            // 此前 filter+groupBy 写在 sheet 内容 lambda 里，拖动 sheet 高度
            // （sheetFraction 每帧变）与每次搜索输入都会把全表重算一遍。
            val favs = pickerLayout.favorites
            val groupedDisplay = pickerLayout.groups
            val favOffset = pickerLayout.favoriteOffset
            val headerIndex = pickerLayout.headerIndex
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
                        // 收藏组里的行必然是已 pin 的（favs 就是按 pinned 过滤出来的）。
                        ModelTile(
                            option = option,
                            pinnedNow = true,
                            cfg = modelCfgs[option.providerId],
                            showProviderLabel = true,
                            actions = rowActions,
                        )
                    }
                }
                groupedDisplay.forEach { group ->
                    val providerName = group.name
                    val models = group.models
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
                        ModelTile(
                            option = option,
                            pinnedNow = "${option.providerId}::${option.modelId}" in pinned.value,
                            cfg = modelCfgs[option.providerId],
                            showProviderLabel = false,
                            actions = rowActions,
                        )
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
    pinnedNow: Boolean,
    actions: ModelTileActions,
    cfg: com.psyche.memo.data.model.ProviderConfig? = null,
    showProviderLabel: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val bg = if (option.selected) cs.primary.copy(alpha = 0.08f) else semantic.surfaceCard
    Row(
        modifier = Modifier
            // 卡片尺寸/留白对齐「更多」sheet（用户 2026-09-12）：卡片左右缩进 16、
            // 卡间距 8（原来是 12/12），行高仍是 48。
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .background(bg, SheetInnerShape)
            .combinedClickable(
                onClick = { actions.select(option) },
                onLongClick = { actions.openDetail(option) },
            )
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
            ModelTagRow(modelId = option.modelId, cfg = cfg)
        }
        // Favorite toggle — solid heart when pinned (settings.togglePinModel).
        val view = LocalView.current
        Box(
            modifier = Modifier
                .size(36.dp)
                .clickable {
                    actions.togglePin(option.providerId, option.modelId)
                    Haptics.light(view)
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (pinnedNow) Lucide.Heart else Lucide.Heart,
                contentDescription = stringResource(UiR.string.model_select_sheet_favorite_tooltip),
                tint = cs.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** model_tag_wrap.dart — chat/embedding type + I/O modality + tools/reasoning pills.
 *
 *  能力取 `effectiveModelInfo`（名称推断 **叠加** 模型 override）：用户在模型编辑页
 *  改了输入模态/abilities 之后，这里的标签必须跟着变（此前只看名称推断，改了不动）。
 *
 *  2026-09-14 对齐原版细节：
 *  · I/O 胶囊按模型真实 input/output 画图标（此前写死 T > T，image 模型也是 T）；
 *  · embedding 类型：类型胶囊显示 Embedding、输出恒 text；
 *  · tools 胶囊 Hammer @primary（底 25%/15% 描边 0.2），reasoning 胶囊 deepthink.svg
 *   （回落 Brain）@secondary（底 30%/18% 描边 0.25）——两颗此前共用 Brain+底色。 */
@Composable
internal fun ModelTagRow(modelId: String, cfg: com.psyche.memo.data.model.ProviderConfig? = null) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val traits = remember(modelId, cfg?.modelOverrides) {
        com.psyche.memo.ModelOverrideResolver.forModel(cfg, modelId)
    }
    val isDark = semantic.isDark
    // model_tag_wrap.dart inputMods/outputMods —— embedding 输出恒 text；chat 空
    // list 回落 [text]。
    val inputMods = remember(traits) {
        if (traits.embedding) setOf("text") else (traits.input.ifEmpty { setOf("text") })
    }
    val outputMods = remember(traits) {
        if (traits.embedding) setOf("text") else (traits.output.ifEmpty { setOf("text") })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        // Type pill — chat primary@25%/15% + 0.2 border; embedding tertiary 同式
        // 但原版类型胶囊恒 primary（model_tag_wrap L57-77 只用 cs.primary）。
        Pill(color = cs.primary) {
            Text(
                text = stringResource(
                    if (traits.embedding) UiR.string.model_select_sheet_embedding_type
                    else UiR.string.model_select_sheet_chat_type,
                ),
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isDark) cs.primary else cs.primary.copy(alpha = 0.9f),
                ),
            )
        }
        // I/O pill —— 每个输入模态一枚图标，ChevronRight，每个输出模态一枚
        //（L117-160）。图标按模态取 Type/Image。
        Pill(color = cs.tertiary) {
            inputMods.forEach { mod ->
                Icon(
                    if (mod == "image") Lucide.Image else Lucide.Type,
                    contentDescription = null,
                    tint = if (isDark) cs.tertiary else cs.tertiary.copy(alpha = 0.9f),
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(2.dp))
            }
            Icon(Lucide.ChevronRight, contentDescription = null, tint = if (isDark) cs.tertiary else cs.tertiary.copy(alpha = 0.9f), modifier = Modifier.size(12.dp))
            outputMods.forEach { mod ->
                Icon(
                    if (mod == "image") Lucide.Image else Lucide.Type,
                    contentDescription = null,
                    tint = if (isDark) cs.tertiary else cs.tertiary.copy(alpha = 0.9f),
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(2.dp))
            }
        }
        if (!traits.embedding) {
            if (traits.tool) {
                // Tools pill —— Hammer @primary，底 25%/15%、描边 0.2（_abilityChip）。
                AbilityPill(
                    color = cs.primary,
                    bgAlpha = if (isDark) 0.25f else 0.15f,
                    borderAlpha = 0.2f,
                ) {
                    Icon(
                        Lucide.Hammer,
                        contentDescription = null,
                        tint = if (isDark) cs.primary else cs.primary.copy(alpha = 0.9f),
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            if (traits.reasoning) {
                // Reasoning pill —— deepthink.svg（回落 Brain）@secondary，
                // 底 30%/18%、描边 0.25。
                AbilityPill(
                    color = cs.secondary,
                    bgAlpha = if (isDark) 0.3f else 0.18f,
                    borderAlpha = 0.25f,
                ) {
                    // 同一枚 12dp 图标在整屏每一行都会出现：命中 [cachedSvgIcon] 就直接画位图，
                    // 未命中保持原 AsyncImage 路径（首帧外观不变）。
                    val tint = remember(isDark, cs.secondary) {
                        androidx.compose.ui.graphics.ColorFilter.tint(
                            if (isDark) cs.secondary else cs.secondary.copy(alpha = 0.9f),
                        )
                    }
                    val decoded = cachedSvgIcon(DEEPTHINK_ASSET, 12.dp)
                    if (decoded != null) {
                        androidx.compose.foundation.Image(
                            bitmap = decoded,
                            contentDescription = null,
                            colorFilter = tint,
                            modifier = Modifier.size(12.dp),
                        )
                    } else {
                        coil.compose.AsyncImage(
                            model = DEEPTHINK_ASSET,
                            contentDescription = null,
                            colorFilter = tint,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * model_tag_wrap.dart `_abilityChip` —— 12dp 圆胶囊，底色/描边透明度按能力
 * 分别指定（tools 与 reasoning 不同）。
 */
@Composable
private fun AbilityPill(
    color: Color,
    bgAlpha: Float,
    borderAlpha: Float,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .background(color.copy(alpha = bgAlpha), SheetPillShape)
            .border(0.5.dp, color.copy(alpha = borderAlpha), SheetPillShape)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun Pill(color: Color, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), SheetPillShape)
            .border(0.5.dp, color.copy(alpha = 0.2f), SheetPillShape)
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
            .background(bg, SheetInnerShape)
            .border(1.dp, cs.outlineVariant.copy(alpha = 0.25f), SheetInnerShape)
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

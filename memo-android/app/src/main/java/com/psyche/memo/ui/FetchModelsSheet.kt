package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.SquareCheck
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Repeat
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Square
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ModelRegistry
import com.psyche.memo.data.model.ProviderConfig
import com.psyche.memo.llm.client.LlmModelInfo
import com.psyche.memo.ui.theme.LocalSemanticColors

/**
 * 从服务器获取模型的**选择面板** —— 1:1 移植 `_showModelPicker`
 * （provider_detail_page.dart L3341-4019）。
 *
 * 原版不是"抓回来全塞进列表"，而是弹一个可拖拽高度的 sheet：搜索框（前缀
 * 放大镜 + 后缀「全选/清空」「反选」两钮）、按模型家族**分组**（可折叠 + 每组
 * 单独 加/减）、每行品牌头像 + 名称 + 能力标签 + 加/减。这里按本仓库的 sheet
 * 规范落地（自绘拖柄、overlaySurface、内容自适应展开），其余结构照抄。
 */

// ------------------------------------------------------------ 纯逻辑（可单测）

/**
 * `ModelGrouping.groupFor`（lib/utils/model_grouping.dart）—— 模型家族分组名。
 * 分组标签由调用方传（embeddings/other 是 l10n 文案，其余是固定英文家族名）。
 */
internal object ModelGrouping {
    private val GPT_O_SERIES = Regex("(^|[^a-z])o[134]")
    private val QWEN = Regex("qwen|qwq|qvq|dashscope")
    private val DOUBAO = Regex("doubao|ark|volc")

    fun groupFor(modelId: String, embeddingsLabel: String, otherLabel: String): String {
        val id = modelId.lowercase()
        if (ModelRegistry.isLikelyEmbeddingId(id)) return embeddingsLabel
        if (id.contains("gpt") || GPT_O_SERIES.containsMatchIn(id)) return "GPT"
        if (id.contains("gemini-3")) return "Gemini 3"
        if (id.contains("gemini-2.5")) return "Gemini 2.5"
        if (id.contains("gemini")) return "Gemini"
        if (id.contains("claude-4")) return "Claude 4"
        if (id.contains("claude-sonnet")) return "Claude Sonnet"
        if (id.contains("claude-opus")) return "Claude Opus"
        if (id.contains("claude-haiku")) return "Claude Haiku"
        if (id.contains("claude-3.5")) return "Claude 3.5"
        if (id.contains("claude-3")) return "Claude 3"
        if (id.contains("deepseek")) return "DeepSeek"
        if (id.contains("kimi")) return "Kimi"
        if (QWEN.containsMatchIn(id)) return "Qwen"
        if (DOUBAO.containsMatchIn(id)) return "Doubao"
        if (id.contains("glm") || id.contains("zhipu")) return "GLM"
        if (id.contains("mistral")) return "Mistral"
        if (id.contains("minimax")) return "MiniMax"
        if (id.contains("grok") || id.contains("xai")) return "Grok"
        if (id.contains("kat")) return "KAT"
        return otherLabel
    }
}

/** L3420-3428 —— 过滤：id 或显示名包含查询串（不区分大小写）。 */
internal fun filterFetchedModels(items: List<LlmModelInfo>, query: String): List<LlmModelInfo> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return items
    return items.filter {
        it.id.lowercase().contains(q) || it.displayName.lowercase().contains(q)
    }
}

/** L3430-3444 —— 分组并按组名（小写）排序，组内保持服务端顺序。 */
internal fun groupFetchedModels(
    items: List<LlmModelInfo>,
    embeddingsLabel: String,
    otherLabel: String,
): List<Pair<String, List<LlmModelInfo>>> {
    val grouped = LinkedHashMap<String, MutableList<LlmModelInfo>>()
    for (item in items) {
        val key = ModelGrouping.groupFor(item.id, embeddingsLabel, otherLabel)
        grouped.getOrPut(key) { mutableListOf() }.add(item)
    }
    return grouped.entries
        .sortedBy { it.key.lowercase() }
        .map { it.key to it.value.toList() }
}

/** 组头 加/减（L3765-3838）：整组加进或从列表里移出。 */
internal fun toggleGroupSelection(
    models: List<String>,
    groupIds: List<String>,
    allAdded: Boolean,
): List<String> =
    if (allAdded) {
        val remove = groupIds.toSet()
        models.filterNot { it in remove }
    } else {
        (models + groupIds).distinct()
    }

/** 单行 加/减（L3945-3976）。 */
internal fun toggleModelSelection(models: List<String>, modelId: String, added: Boolean): List<String> =
    if (added) models.filterNot { it == modelId } else (models + modelId).distinct()

/** 全选/清空（只作用于**当前过滤后**可见的模型，L3518-3555）。 */
internal fun selectAllVisible(
    models: List<String>,
    visibleIds: List<String>,
    allSelected: Boolean,
): List<String> =
    if (allSelected) {
        val remove = visibleIds.toSet()
        models.filterNot { it in remove }
    } else {
        (models + visibleIds).distinct()
    }

/** 反选（同样只作用于可见的模型，L3573-3609）。 */
internal fun invertVisible(models: List<String>, visibleIds: List<String>): List<String> {
    if (visibleIds.isEmpty()) return models
    val current = models.toMutableList()
    for (id in visibleIds) {
        if (!current.remove(id)) current.add(id)
    }
    return current
}

/** SiliconFlow 无自备 key 时只给两个合作免费模型（L3348-3391）。 */
internal val SILICONFLOW_FREE_MODEL_IDS = listOf("THUDM/GLM-4-9B-0414", "Qwen/Qwen3-8B")

/**
 * 一个组头的派生数据：组名、组内 id、是否整组已加。
 *
 * 拆出来是为了 **能 remember**：面板每次重组（搜索框每敲一个字、勾选变一次）
 * 以前都要 `groupItems.all { it.id in selected }`（selected 是 `List` ⇒ 每行 O(N)）
 * 并重新 `map { it.id }` 分配一份新列表。这里按「分组结果 + 勾选集合」算一次。
 */
internal class FetchedGroupRow(
    val name: String,
    val ids: List<String>,
    val allAdded: Boolean,
    val items: List<LlmModelInfo>,
)

internal fun deriveFetchedGroups(
    groups: List<Pair<String, List<LlmModelInfo>>>,
    selected: Set<String>,
): List<FetchedGroupRow> = groups.map { (name, items) ->
    val ids = items.map { it.id }
    FetchedGroupRow(name = name, ids = ids, allAdded = ids.all { it in selected }, items = items)
}

/**
 * 是否只显示免费模型：内置 SiliconFlow 且没有用户 key（多 Key 模式看
 * `apiKeys`，否则看 `apiKey`）。
 */
internal fun restrictToFreeModels(cfg: ProviderConfig): Boolean {
    val isDefaultSilicon = cfg.id.lowercase() == "siliconflow"
    val hasUserKey = (cfg.multiKeyEnabled == true && !cfg.apiKeys.isNullOrEmpty()) ||
        cfg.apiKey.trim().isNotEmpty()
    return isDefaultSilicon && !hasUserKey
}

// ------------------------------------------------------------------ 面板

/**
 * 面板里的圆角形状提成常量：`RoundedCornerShape(...)` 每次组合都新造实例，
 * 修饰符链的等值比较永远是「变了」⇒ 该行必重排。
 */
private val FetchSheetInnerShape = RoundedCornerShape(MemoRadius.INNER_DP.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FetchModelsSheet(
    cfg: ProviderConfig,
    container: AppContainerImpl,
    onCfgChange: (ProviderConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<LlmModelInfo>>(emptyList()) }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }

    val embeddingsLabel = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_embeddings_group_title)
    val otherLabel = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_other_models_group_title)

    LaunchedEffect(cfg.id) {
        val restrictToFree = restrictToFreeModels(cfg)
        val fetched = if (restrictToFree) {
            SILICONFLOW_FREE_MODEL_IDS.map { LlmModelInfo(id = it, displayName = it) }
        } else {
            runCatching {
                container.clientFor(cfg.classifiedKind())
                    .listModels(container.baseUrlFor(cfg.id), cfg.apiKey)
            }.getOrElse { e ->
                error = e.message ?: e.toString()
                emptyList()
            }
        }
        items = fetched
        loading = false
    }

    val visible = remember(items, query) { filterFetchedModels(items, query) }
    val groups = remember(visible, embeddingsLabel, otherLabel) {
        groupFetchedModels(visible, embeddingsLabel, otherLabel)
    }
    val visibleIds = remember(visible) { visible.map { it.id } }
    val selected = cfg.models
    // 勾选判据走 Set（以前每行 `in selected` 是对 List 的 O(N) 线性扫）。
    val selectedSet = remember(selected) { selected.toSet() }
    val groupRows = remember(groups, selectedSet) { deriveFetchedGroups(groups, selectedSet) }
    val allSelected = remember(visibleIds, selectedSet) {
        visibleIds.isNotEmpty() && visibleIds.all { it in selectedSet }
    }

    // 面板存活期内 cfg / onCfgChange 通过 State 引用读，好让下面三个行回调
    // 的实例始终不变（行实参稳定 ⇒ 可跳过重组）。
    val cfgRef = rememberUpdatedState(cfg)
    val onCfgChangeRef = rememberUpdatedState(onCfgChange)
    val groupIdsRef = rememberUpdatedState(groupRows.associate { it.name to it.ids })

    val onToggleModel: (String) -> Unit = remember {
        { modelId ->
            val cur = cfgRef.value
            onCfgChangeRef.value(
                cur.copy(
                    models = toggleModelSelection(
                        models = cur.models,
                        modelId = modelId,
                        added = modelId in cur.models,
                    ),
                ),
            )
        }
    }
    val onToggleGroup: (String) -> Unit = remember {
        { group ->
            val ids = groupIdsRef.value[group]
            if (!ids.isNullOrEmpty()) {
                val cur = cfgRef.value
                onCfgChangeRef.value(
                    cur.copy(
                        models = toggleGroupSelection(
                            models = cur.models,
                            groupIds = ids,
                            allAdded = ids.all { it in cur.models },
                        ),
                    ),
                )
            }
        }
    }
    val onToggleCollapse: (String) -> Unit = remember {
        // 原来：`collapsed[group] = !isCollapsed`（isCollapsed = collapsed[group] == true）
        { group -> collapsed[group] = collapsed[group] != true }
    }

    fun apply(next: List<String>) {
        onCfgChange(cfg.copy(models = next))
    }

    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        containerColor = semantic.overlaySurface(cs),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        dragHandle = null,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            MemoSheetHandle()
            // 搜索框（L3476-3633）：surfaceFill 底、r12、放大镜前缀、后缀两钮。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .background(semantic.surfaceFill, FetchSheetInnerShape),
            ) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            stringResource(com.psyche.memo.ui.R.string.provider_detail_page_filter_hint),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Lucide.Search,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = cs.onSurface.copy(alpha = 0.7f),
                        )
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { apply(selectAllVisible(selected, visibleIds, allSelected)) }) {
                                Icon(
                                    if (allSelected) Lucide.Square else Lucide.SquareCheck,
                                    contentDescription = stringResource(
                                        if (allSelected) {
                                            com.psyche.memo.ui.R.string.mcp_assistant_sheet_clear_all
                                        } else {
                                            com.psyche.memo.ui.R.string.mcp_assistant_sheet_select_all
                                        },
                                    ),
                                    modifier = Modifier.size(22.dp),
                                    tint = cs.onSurface.copy(alpha = 0.7f),
                                )
                            }
                            IconButton(onClick = { apply(invertVisible(selected, visibleIds)) }) {
                                Icon(
                                    Lucide.Repeat,
                                    contentDescription = stringResource(com.psyche.memo.ui.R.string.model_fetch_invert_tooltip),
                                    modifier = Modifier.size(22.dp),
                                    tint = cs.onSurface.copy(alpha = 0.7f),
                                )
                            }
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
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                when {
                    loading -> Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }

                    error.isNotEmpty() -> Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(error, style = TextStyle(color = cs.error, fontSize = 14.sp))
                    }

                    else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        groupRows.forEach { group ->
                            val isCollapsed = collapsed[group.name] == true
                            item(key = "h_${group.name}") {
                                GroupHeader(
                                    group = group.name,
                                    expanded = !isCollapsed,
                                    allAdded = group.allAdded,
                                    onToggleCollapse = onToggleCollapse,
                                    onToggleGroup = onToggleGroup,
                                )
                            }
                            if (!isCollapsed) {
                                items(group.items, key = { "m_${it.id}" }) { model ->
                                    FetchedModelRow(
                                        modelId = model.id,
                                        displayName = model.displayName,
                                        added = model.id in selectedSet,
                                        cfg = cfg,
                                        onToggle = onToggleModel,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 组头（L3652-3847）：surfaceFill r12、chevron 旋转 220ms、组名 + 整组加/减。 */
@Composable
private fun GroupHeader(
    group: String,
    expanded: Boolean,
    allAdded: Boolean,
    onToggleCollapse: (String) -> Unit,
    onToggleGroup: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(semantic.surfaceFill, FetchSheetInnerShape)
            .clickable { onToggleCollapse(group) }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            // Flutter `AnimatedRotation(turns: 0.25)` → Compose 用
            // animateFloatAsState + graphicsLayer(rotationZ)（本项目既有做法）。
            val turns by animateFloatAsState(
                targetValue = if (expanded) 0.25f else 0f,
                animationSpec = tween(durationMillis = 220),
                label = "groupChevron",
            )
            Icon(
                Lucide.ChevronRight,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = turns * 360f },
                tint = cs.onSurface.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = group,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = { onToggleGroup(group) }) {
            Icon(
                if (allAdded) Lucide.Minus else Lucide.Plus,
                contentDescription = stringResource(
                    if (allAdded) {
                        com.psyche.memo.ui.R.string.provider_detail_page_remove_group_tooltip
                    } else {
                        com.psyche.memo.ui.R.string.provider_detail_page_add_group_tooltip
                    },
                ),
                modifier = Modifier.size(24.dp),
                tint = cs.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

/** 一行模型（L3857-4001）：28dp 品牌头像 + 名称/能力标签 + 加/减。 */
@Composable
private fun FetchedModelRow(
    modelId: String,
    displayName: String,
    added: Boolean,
    cfg: com.psyche.memo.data.model.ProviderConfig,
    onToggle: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(28.dp)) {
            ProviderAvatarSmall(providerKey = modelId, displayName = modelId, size = 28.dp)
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName.ifEmpty { modelId },
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            ModelTagRow(modelId = modelId, cfg = cfg)
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = { onToggle(modelId) }) {
            Icon(
                if (added) Lucide.Minus else Lucide.Plus,
                contentDescription = stringResource(
                    if (added) {
                        com.psyche.memo.ui.R.string.provider_detail_page_remove_group_tooltip
                    } else {
                        com.psyche.memo.ui.R.string.provider_detail_page_add_group_tooltip
                    },
                ),
                modifier = Modifier.size(24.dp),
                tint = cs.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

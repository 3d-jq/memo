package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.CaseSensitive
import com.composables.icons.lucide.EthernetPort
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.GripVertical
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.ListTree
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Puzzle
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.Settings2
import com.composables.icons.lucide.Terminal
import com.composables.icons.lucide.Video
import com.composables.icons.lucide.Wrench
import com.composables.icons.lucide.Zap
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.Haptics
import com.psyche.memo.data.settings.PreferenceRepository
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.AppFontWeights
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.psyche.memo.ui.R as UiR

/**
 * assistant_edit_tab_layout.dart — the assistant edit tabs as data: id, label
 * and icon, in the same declaration order as `_assistantEditTabSpecs`.
 */
enum class AssistantEditTab(val id: String, val labelRes: Int, val icon: ImageVector) {
    BASIC("basic", UiR.string.assistant_edit_page_basic_tab, Lucide.Settings2),
    PROMPTS("prompts", UiR.string.assistant_edit_page_prompts_tab, Lucide.FileText),
    MEMORY("memory", UiR.string.assistant_edit_page_memory_tab, Lucide.Brain),
    LOCAL_TOOLS("localTools", UiR.string.assistant_edit_page_local_tools_tab, Lucide.Wrench),
    SKILLS("skills", UiR.string.assistant_edit_page_skills_tab, Lucide.Puzzle),
    WORKSPACE("workspace", UiR.string.assistant_edit_page_workspace_tab, Lucide.HardDrive),
    /** 生成服务（自研功能）：图片/视频各一个 tab。 */
    IMAGE_GENERATION("imageGeneration", UiR.string.assistant_edit_page_image_generation_tab, Lucide.Image),
    VIDEO_GENERATION("videoGeneration", UiR.string.assistant_edit_page_video_generation_tab, Lucide.Video),
    MCP("mcp", UiR.string.assistant_edit_page_mcp_tab, Lucide.Terminal),
    QUICK_PHRASE("quickPhrase", UiR.string.assistant_edit_page_quick_phrase_tab, Lucide.Zap),
    CUSTOM("custom", UiR.string.assistant_edit_page_custom_tab, Lucide.EthernetPort),
    REGEX("regex", UiR.string.assistant_edit_page_regex_tab, Lucide.CaseSensitive);

    companion object {
        fun byId(id: String): AssistantEditTab? = entries.firstOrNull { it.id == id }
    }
}

/** `defaultAssistantEditTabIds` — the display order when nothing is saved. */
val DEFAULT_ASSISTANT_EDIT_TAB_ORDER: List<String> = listOf(
    "basic", "prompts", "memory", "quickPhrase", "custom", "regex", "localTools", "skills",
    "workspace", "imageGeneration", "videoGeneration", "mcp",
)

/**
 * `orderAssistantEditTabIds` — saved order first (known ids, no duplicates),
 * then every remaining default id appended in its default position. This is why
 * a tab added by a later release still shows up for users with a stored order.
 */
fun orderAssistantEditTabIds(
    savedOrder: List<String>,
    defaultOrder: List<String> = DEFAULT_ASSISTANT_EDIT_TAB_ORDER,
): List<String> {
    val valid = defaultOrder.toSet()
    val seen = LinkedHashSet<String>()
    for (id in savedOrder) {
        if (id in valid) seen.add(id)
    }
    for (id in defaultOrder) seen.add(id)
    return seen.toList()
}

/**
 * `visibleAssistantEditTabIds` — the ordered ids minus the hidden ones, never
 * empty: hiding the last visible tab falls back to the first ordered tab.
 */
fun visibleAssistantEditTabIds(
    savedOrder: List<String>,
    hiddenIds: Set<String>,
    defaultOrder: List<String> = DEFAULT_ASSISTANT_EDIT_TAB_ORDER,
): List<String> {
    val ordered = orderAssistantEditTabIds(savedOrder, defaultOrder)
    val visible = ordered.filterNot { it in hiddenIds }
    return visible.ifEmpty { listOf(ordered.first()) }
}

/**
 * `add(to, removeAt(from))` on an id list — one reorder crossing. Returns the
 * list unchanged for out-of-range or no-op moves (the library can emit stale
 * layout indices when the list recomposes mid-drag).
 */
fun applyAssistantTabMove(order: List<String>, from: Int, to: Int): List<String> {
    if (from !in order.indices || to !in order.indices || from == to) return order
    return order.toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * Compose-observable twin of SettingsProvider's three assistant-edit-tab keys
 * (L3051-3083): the saved order (`setStringList` → JSON array text), the hidden
 * ids and the outline-mode flag.
 *
 * Container-scoped like [com.psyche.memo.ui.TtsServicesStore] so the edit page
 * and the layout page share one live copy — Flutter's
 * `context.watch<SettingsProvider>()` gives the same immediate repaint after a
 * reorder or a hidden toggle.
 */
class AssistantTabLayoutState(private val prefs: PreferenceRepository) {
    var order: List<String> by mutableStateOf(readIds(KEY_ORDER))
        private set
    var hidden: Set<String> by mutableStateOf(LinkedHashSet(readIds(KEY_HIDDEN)))
        private set
    var outlineEnabled: Boolean by mutableStateOf(prefs.readJson(KEY_OUTLINE) == "true")
        private set

    fun updateOrder(next: List<String>) {
        order = next
        prefs.writeJson(KEY_ORDER, encode(next))
    }

    fun updateHidden(next: Set<String>) {
        hidden = next
        prefs.writeJson(KEY_HIDDEN, encode(next))
    }

    fun updateOutline(enabled: Boolean) {
        outlineEnabled = enabled
        prefs.writeJson(KEY_OUTLINE, if (enabled) "true" else "false")
    }

    /** The page's reset action: `setMobileAssistantEditTabOrder(const [])` +
     * `setHiddenMobileAssistantEditTabs(const {})`. */
    fun reset() {
        updateOrder(emptyList())
        updateHidden(emptySet())
    }

    private fun readIds(key: String): List<String> {
        val raw = prefs.readJson(key) ?: return emptyList()
        return runCatching {
            (Json.parseToJsonElement(raw) as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun encode(ids: Collection<String>): String =
        JsonArray(ids.map { JsonPrimitive(it) }).toString()

    private companion object {
        const val KEY_ORDER = "mobile_assistant_edit_tab_order_v1"
        const val KEY_HIDDEN = "mobile_assistant_edit_tab_hidden_v1"
        const val KEY_OUTLINE = "mobile_assistant_detail_outline_enabled_v1"
    }
}

/**
 * `_AssistantTabLayoutPage` L598-733 — outline-mode switch, helper copy and the
 * reorderable tile list. The reset action clears both the order and the hidden
 * set (`setMobileAssistantEditTabOrder(const [])` + `setHiddenMobileAssistantEditTabs(const {})`).
 */
@Composable
fun AssistantTabLayoutScreen(container: AppContainerImpl, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    val state = container.assistantTabLayout

    // Live display order while a drag is in flight. `onMove` fires on every
    // crossing (the library expects each move applied immediately) but the
    // store is written once on release, matching providers_page.
    var dragOrder by remember { mutableStateOf<List<String>?>(null) }
    val orderedIds = dragOrder ?: orderAssistantEditTabIds(state.order)
    val tabs = remember(orderedIds) { orderedIds.mapNotNull { AssistantEditTab.byId(it) } }
    val hidden = state.hidden
    val visibleCount = tabs.count { it.id !in hidden }
    val atLeastOneVisible = stringResource(UiR.string.assistant_edit_tab_layout_at_least_one_visible)

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // No header items in the LazyColumn, so lazy indices are data indices.
        dragOrder = applyAssistantTabMove(orderedIds, from.index, to.index)
    }
    LaunchedEffect(reorderableState.isAnyItemDragging) {
        if (!reorderableState.isAnyItemDragging) {
            dragOrder?.let(state::updateOrder)
            dragOrder = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        MemoTopBar(
            title = stringResource(UiR.string.assistant_edit_tab_layout_title),
            onBack = onBack,
        ) {
            IosIconButton(
                icon = Lucide.RotateCcw,
                onTap = { state.reset() },
                color = cs.onSurface,
                size = 20.dp,
                minSize = 44.dp,
                semanticLabel = stringResource(UiR.string.assistant_edit_tab_layout_reset_tooltip),
            )
            Spacer(Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 12.dp),
        ) {
            SectionCard {
                SettingsSwitchRow(
                    icon = Lucide.ListTree,
                    label = stringResource(UiR.string.assistant_edit_outline_mode_title),
                    tip = stringResource(UiR.string.assistant_edit_outline_mode_subtitle),
                    value = state.outlineEnabled,
                    onToggle = { state.updateOutline(it) },
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(UiR.string.assistant_edit_tab_layout_subtitle),
                style = TextStyle(
                    fontSize = 13.sp,
                    lineHeight = 17.55.sp,
                    color = cs.onSurface.copy(alpha = 0.68f),
                ),
            )
        }

        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(tabs, key = { it.id }) { tab ->
                ReorderableItem(state = reorderableState, key = tab.id) { _ ->
                    AssistantTabLayoutTile(
                        tab = tab,
                        visible = tab.id !in hidden,
                        onVisibleChanged = { nextVisible ->
                            if (!nextVisible && visibleCount <= 1) {
                                SnackbarManager.show(
                                    AppNotification(
                                        message = atLeastOneVisible,
                                        type = NotificationType.WARNING,
                                    ),
                                )
                            } else {
                                val next = hidden.toMutableSet()
                                if (nextVisible) next.remove(tab.id) else next.add(tab.id)
                                state.updateHidden(next)
                            }
                        },
                        dragHandle = {
                            Icon(
                                Lucide.GripVertical,
                                contentDescription = stringResource(
                                    UiR.string.assistant_edit_tab_layout_drag_handle,
                                    stringResource(tab.labelRes),
                                ),
                                tint = cs.onSurface.copy(alpha = 0.42f),
                                modifier = Modifier
                                    .padding(10.dp)
                                    .size(18.dp)
                                    .draggableHandle(
                                        onDragStarted = { Haptics.medium(view) },
                                        onDragStopped = { Haptics.light(view) },
                                    ),
                            )
                        },
                    )
                }
            }
        }
    }
}

/**
 * `_AssistantTabLayoutTile` L773-848 — r14 surfaceCard tile, hairline at
 * dark 0.12 / light 0.08, 34dp icon slot, 15sp semibold label that dims to 42%
 * while hidden, the visibility switch and the drag handle.
 */
@Composable
private fun AssistantTabLayoutTile(
    tab: AssistantEditTab,
    visible: Boolean,
    onVisibleChanged: (Boolean) -> Unit,
    dragHandle: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val fg = cs.onSurface.copy(alpha = if (visible) 0.9f else 0.42f)
    val label = stringResource(tab.labelRes)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .background(semantic.surfaceCard)
            .border(
                width = 0.8.dp,
                color = cs.outlineVariant.copy(alpha = if (semantic.isDark) 0.12f else 0.08f),
                shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
            )
            .padding(start = 12.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(34.dp), contentAlignment = Alignment.Center) {
            Icon(tab.icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontSize = 15.sp,
                fontWeight = AppFontWeights.semibold,
                color = fg,
            ),
            modifier = Modifier.weight(1f),
        )
        IosSwitch(
            value = visible,
            onValueChanged = onVisibleChanged,
            semanticLabel = label,
        )
        dragHandle()
    }
}

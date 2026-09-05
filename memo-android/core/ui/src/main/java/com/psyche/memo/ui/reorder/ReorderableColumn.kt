package com.psyche.memo.ui.reorder

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Long-press drag-to-reorder list backed by `sh.calvin.reorderable`
 * (the same battle-tested library RikkaHub uses) instead of a hand-rolled
 * gesture detector:
 * - drag starts on long-press via [longPressDraggableHandle] (no drag handle),
 * - the dragged card lifts to opacity 0.95 / scale 0.98 (kelivo's
 *   proxyDecorator), siblings shift through the library's stable swap logic,
 * - on release the caller can replay kelivo's settle bounce via the
 *   [itemContent] `isDragging` flag.
 *
 * The list scrolls (LazyColumn); pass [header]/[footer] for fixed content
 * inside the same scroll container.
 */
@Composable
fun <T> ReorderableColumn(
    items: List<T>,
    keyOf: (T) -> Any,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    reorderEnabled: Boolean = true,
    header: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null,
    footer: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null,
    itemContent: @Composable (T, Boolean) -> Unit,
) {
    val listState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        onMove(from.index, to.index)
    }

    androidx.compose.foundation.lazy.LazyColumn(state = listState, modifier = modifier) {
        header?.let { it() }
        items(items.size, key = { keyOf(items[it]) }) { index ->
            val item = items[index]
            val itemKey = keyOf(item)
            ReorderableItem(
                state = reorderableState,
                key = itemKey,
            ) { isDragging ->
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .alpha(if (isDragging) 0.95f else 1f)
                        .scale(if (isDragging) 0.98f else 1f)
                        .longPressDraggableHandle(enabled = reorderEnabled && !isDragging),
                ) {
                    itemContent(item, isDragging)
                }
            }
        }
        footer?.let { it() }
    }
}

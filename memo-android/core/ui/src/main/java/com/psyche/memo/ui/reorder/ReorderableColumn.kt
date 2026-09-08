package com.psyche.memo.ui.reorder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
    header: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null,
    footer: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null,
    itemContent: @Composable (T, Boolean) -> Unit,
) {
    val listState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        onMove(from.index, to.index)
    }

    androidx.compose.foundation.lazy.LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        header?.let { it() }
        items(items.size, key = { keyOf(items[it]) }) { index ->
            val item = items[index]
            val itemKey = keyOf(item)
            ReorderableItem(
                state = reorderableState,
                key = itemKey,
                // Siblings glide into their new slots while a card is
                // dragged (without this they teleport, visually piling
                // up under/over the dragged card).
                modifier = Modifier.animateItem(),
            ) { isDragging ->
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .alpha(if (isDragging) 0.95f else 1f)
                        .scale(if (isDragging) 0.98f else 1f)
                        .longPressDraggableHandle(enabled = reorderEnabled),
                ) {
                    itemContent(item, isDragging)
                }
            }
        }
        footer?.let { it() }
    }
}

/**
 * [ReorderableColumn] variant that hands the drag handle to the caller instead
 * of covering the whole item — kelivo's world-book page starts the book drag
 * from the header only (`ReorderableDelayedDragStartListener` around the
 * header row), leaving the rows below free for their own tap/long-press.
 */
@Composable
fun <T> ReorderableColumnWithHandle(
    items: List<T>,
    keyOf: (T) -> Any,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    handleEnabled: Boolean = true,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
    itemContent: @Composable (T, Boolean, Modifier) -> Unit,
) {
    val listState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        onMove(from.index, to.index)
    }

    androidx.compose.foundation.lazy.LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        items(items.size, key = { keyOf(items[it]) }) { index ->
            val item = items[index]
            ReorderableItem(
                state = reorderableState,
                key = keyOf(item),
                modifier = Modifier.animateItem(),
            ) { isDragging ->
                val handle = Modifier.longPressDraggableHandle(enabled = handleEnabled)
                Column(
                    modifier = Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .alpha(if (isDragging) 0.95f else 1f)
                        .scale(if (isDragging) 0.98f else 1f),
                ) {
                    itemContent(item, isDragging, handle)
                }
            }
        }
    }
}

/**
 * Non-scrolling twin of [ReorderableColumn] for lists embedded in an outer
 * scroll container — Flutter does the same with
 * `ReorderableListView(shrinkWrap: true, physics: NeverScrollableScrollPhysics())`.
 * Backed by the library's Column-based `ReorderableColumn` (fully qualified here
 * because it collides with our own name in this package).
 */
@Composable
fun <T> ReorderableInlineColumn(
    items: List<T>,
    keyOf: (T) -> Any,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    itemContent: @Composable (T, Boolean) -> Unit,
) {
    sh.calvin.reorderable.ReorderableColumn(
        list = items,
        onSettle = { from, to -> onMove(from, to) },
        modifier = modifier,
        verticalArrangement = verticalArrangement,
    ) { _, item, isDragging ->
        key(keyOf(item)) {
            ReorderableItem {
                Column(
                    modifier = Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .alpha(if (isDragging) 0.95f else 1f)
                        .scale(if (isDragging) 0.98f else 1f)
                        .longPressDraggableHandle(),
                ) {
                    itemContent(item, isDragging)
                }
            }
        }
    }
}

/**
 * [ReorderableInlineColumn] variant that hands the drag handle to the caller
 * (kelivo's world-book entry rows drag from the leading bookmark icon).
 */
@Composable
fun <T> ReorderableInlineColumnWithHandle(
    items: List<T>,
    keyOf: (T) -> Any,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    handleEnabled: Boolean = true,
    itemContent: @Composable (T, Boolean, Modifier) -> Unit,
) {
    sh.calvin.reorderable.ReorderableColumn(
        list = items,
        onSettle = { from, to -> onMove(from, to) },
        modifier = modifier,
        verticalArrangement = verticalArrangement,
    ) { _, item, isDragging ->
        key(keyOf(item)) {
            ReorderableItem {
                val handle = Modifier.longPressDraggableHandle(enabled = handleEnabled)
                Column(
                    modifier = Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .alpha(if (isDragging) 0.95f else 1f)
                        .scale(if (isDragging) 0.98f else 1f),
                ) {
                    itemContent(item, isDragging, handle)
                }
            }
        }
    }
}

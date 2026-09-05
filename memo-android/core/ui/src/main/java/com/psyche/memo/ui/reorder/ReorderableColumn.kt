package com.psyche.memo.ui.reorder

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

/**
 * Long-press drag-to-reorder column — self-drawn equivalent of the
 * reorderable list kelivo uses in providers_page.dart:
 * - drag starts on long-press (no drag handle); the card lifts to opacity
 *   0.95 / scale 0.98 (proxyDecorator),
 * - siblings shift as the dragged card's center crosses their centers,
 * - on release the card settles with a 0.94 -> 1.0 easeOutBack 180ms bounce
 *   (mirrors kelivo's _SettleAnim).
 *
 * [onMove] receives (fromIndex, toIndex) as the drag progresses; the caller
 * mutates its list so recomposition reorders the children. Gestures read the
 * latest list through [rememberUpdatedState]-style snapshot access, so moves
 * during an active drag are safe.
 */
@Composable
fun <T> ReorderableColumn(
    items: List<T>,
    keyOf: (T) -> Any,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    itemContent: @Composable ColumnScope.(T, Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var draggingKey by remember { mutableStateOf<Any?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    val itemBounds = remember { mutableStateMapOf<Any, Rect>() }
    val settleAnims = remember { mutableStateMapOf<Any, Animatable<Float, *>>() }

    // Live reads for gesture closures (avoid stale `items`/index capture).
    val keysState = remember { mutableStateOf(items.map(keyOf)) }
    androidx.compose.runtime.LaunchedEffect(items) {
        keysState.value = items.map(keyOf)
    }

    Column(modifier = modifier) {
        items.forEach { item ->
            val key = keyOf(item)
            val isDragging = draggingKey == key
            val settle = settleAnims[key]
            val settleModifier: Modifier = if (settle != null) {
                Modifier.scale(settle.value)
            } else {
                Modifier
            }

            Column(
                modifier = settleModifier
                    .zIndex(if (isDragging) 1f else 0f)
                    .alpha(if (isDragging) 0.95f else 1f)
                    .scale(if (isDragging) 0.98f else 1f)
                    .graphicsLayer {
                        translationY = if (isDragging) dragOffsetY else 0f
                    }
                    .onGloballyPositioned { coords ->
                        itemBounds[key] = coords.boundsInParent()
                    }
                    .pointerInput(key) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingKey = key
                                dragOffsetY = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                if (draggingKey != key) return@detectDragGesturesAfterLongPress
                                dragOffsetY += amount.y

                                val keys = keysState.value
                                val index = keys.indexOf(key)
                                if (index < 0) return@detectDragGesturesAfterLongPress
                                val bounds = itemBounds[key] ?: return@detectDragGesturesAfterLongPress
                                val center = bounds.center.y + dragOffsetY

                                if (index < keys.lastIndex) {
                                    val nextKey = keys[index + 1]
                                    val next = itemBounds[nextKey]
                                    if (next != null && center > next.center.y) {
                                        dragOffsetY -= next.height
                                        onMove(index, index + 1)
                                    }
                                } else if (index > 0) {
                                    val prevKey = keys[index - 1]
                                    val prev = itemBounds[prevKey]
                                    if (prev != null && center < prev.center.y) {
                                        dragOffsetY += prev.height
                                        onMove(index, index - 1)
                                    }
                                }
                            },
                            onDragEnd = {
                                if (draggingKey == key) {
                                    draggingKey = null
                                    dragOffsetY = 0f
                                    // Settle bounce: 0.94 -> 1.0 easeOutBack 180ms.
                                    val anim = Animatable(0.94f)
                                    settleAnims[key] = anim
                                    scope.launch {
                                        anim.animateTo(
                                            1f,
                                            tween(180, easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)),
                                        )
                                        settleAnims.remove(key)
                                    }
                                }
                            },
                            onDragCancel = {
                                draggingKey = null
                                dragOffsetY = 0f
                            },
                        )
                    },
            ) {
                itemContent(item, isDragging)
            }
        }
    }
}

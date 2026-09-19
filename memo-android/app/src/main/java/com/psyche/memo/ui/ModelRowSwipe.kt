package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2
import com.psyche.memo.common.Haptics
import com.psyche.memo.data.model.ProviderConfig
import com.psyche.memo.ui.theme.LocalSemanticColors

/**
 * [ProviderModelRow] wrapped in a left-swipe-to-delete action pane — port of
 * the `Slidable` in `provider_detail_page.dart` L1538-1712.
 *
 * Flutter uses `ActionPane(motion: StretchMotion(), extentRatio: 0.42)` with a
 * single destructive action: a radius-12 container tinted `cs.error` (α 0.22
 * dark / 0.14 light) with a 1px error border at α 0.35, holding a 18dp `Trash2`
 * and the "删除" label.
 *
 * Compose has no first-party equivalent, so this drives the offset with
 * [draggable] + an animated snap. The reveal is `extentRatio` of the row width;
 * releasing past half snaps open, otherwise it snaps shut. Swiping is disabled
 * in selection mode, exactly as Flutter does (`enabled: !_isSelectionMode`).
 *
 * The action only *requests* deletion — the caller shows the confirmation
 * dialog, because Flutter's `autoClose: true` pane leads into a dialog too.
 */
@Composable
internal fun ModelRowWithSwipe(
    modelId: String,
    cfg: ProviderConfig,
    selectMode: Boolean,
    selected: Boolean,
    check: ModelCheckResult?,
    onToggleSelect: () -> Unit,
    onEdit: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val view = LocalView.current
    val density = LocalDensity.current

    // 0f = closed, 1f = fully revealed action pane.
    var revealed by remember(modelId) { mutableStateOf(false) }
    var dragFraction by remember(modelId) { mutableStateOf<Float?>(null) }
    val settled by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "modelSwipeReveal",
    )
    val fraction = dragFraction ?: settled
    val actionWidthDp = 118.dp
    val actionWidthPx = with(density) { actionWidthDp.toPx() }

    Box(modifier = Modifier.fillMaxWidth()) {
        // ---- Action pane, revealed underneath ----
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(actionWidthDp)
                .fillMaxSize()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(MemoRadius.INNER_DP.dp))
                .background(
                    if (semantic.isDark) cs.error.copy(alpha = 0.22f) else cs.error.copy(alpha = 0.14f),
                )
                .border(1.dp, cs.error.copy(alpha = 0.35f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
                .clickable {
                    Haptics.light(view)
                    revealed = false
                    onRequestDelete()
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Lucide.Trash2,
                contentDescription = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_delete_model_button),
                tint = cs.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_delete_model_button),
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.error),
                maxLines = 1,
            )
        }

        // ---- Row body, translated by the drag ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = -fraction * actionWidthPx }
                .background(cs.surface)
                .then(
                    if (selectMode) Modifier else Modifier.draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            // Dragging left (negative delta) increases the reveal.
                            val current = (dragFraction ?: settled) * actionWidthPx
                            val next = (current - delta).coerceIn(0f, actionWidthPx)
                            dragFraction = next / actionWidthPx
                        },
                        onDragStopped = {
                            val f = dragFraction ?: settled
                            revealed = f > 0.5f
                            dragFraction = null
                        },
                    ),
                ),
        ) {
            ProviderModelRow(
                modelId = modelId,
                cfg = cfg,
                selectMode = selectMode,
                selected = selected,
                check = check,
                onToggleSelect = onToggleSelect,
                onEdit = onEdit,
            )
        }
    }
}

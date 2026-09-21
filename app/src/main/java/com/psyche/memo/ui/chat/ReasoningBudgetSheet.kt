package com.psyche.memo.ui.chat

import com.psyche.memo.ui.theme.MemoRadius
import com.psyche.memo.ui.overlaySurfaceColor
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.foundation.text.KeyboardOptions
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Hash
import com.composables.icons.lucide.Lucide
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.Haptics
import com.psyche.memo.llm.client.ReasoningBudget
import com.psyche.memo.ui.ThemeState
import com.psyche.memo.ui.rememberMemoSheetState
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.psyche.memo.ui.R as UiR

/** ReasoningIcons — idea-01 svg per budget tier. */
object ReasoningBudgetIcons {
    const val OFF = "file:///android_asset/icons/idea-01-no-rays.svg"
    const val AUTO = "file:///android_asset/icons/idea-01-stroke-rounded.svg"
    const val LIGHT = "file:///android_asset/icons/idea-01-no-side-rays.svg"
    const val MEDIUM = "file:///android_asset/icons/idea-01-stroke-rounded.svg"
    const val HEAVY = "file:///android_asset/icons/idea-01-more-rays.svg"
    const val XHIGH = "file:///android_asset/icons/idea-01-moremore-rays.svg"

    fun assetForBudget(budget: Int?): String = when {
        budget == null || budget == ReasoningBudget.AUTO -> AUTO
        budget == ReasoningBudget.OFF -> OFF
        budget <= 1024 -> LIGHT
        budget <= 16000 -> MEDIUM
        budget <= 32000 -> HEAVY
        else -> XHIGH
    }
}

// ── 档位模型（纯逻辑，可测）──────────────────────────────────────────────────
// reasoning_budget_sheet.dart `_EffortStop` + `_buildStops` + `_indexForSelection`
// （83ab329 重写后的形状：关闭 → 自动 → Low → Medium → High → (XHigh) → (Max)）。

internal const val REASONING_STOP_LOW = 1024
internal const val REASONING_STOP_MEDIUM = 16000
internal const val REASONING_STOP_HIGH = 32000
internal const val REASONING_STOP_XHIGH = 64000
internal const val REASONING_STOP_MAX = 128000

internal data class EffortStop(
    val title: String,
    val subtitle: String,
    val value: Int,
    val icon: String,
)

internal data class ReasoningStopTitles(
    val off: String,
    val auto: String,
    val low: String,
    val medium: String,
    val high: String,
    val xhigh: String,
    val max: String,
    val offSubtitle: String,
    val autoSubtitle: String,
    val lowSubtitle: String,
    val mediumSubtitle: String,
    val highSubtitle: String,
    val xhighSubtitle: String,
)

internal object ReasoningEffortStops {
    fun build(titles: ReasoningStopTitles, showXhigh: Boolean, showMax: Boolean): List<EffortStop> =
        listOf(
            EffortStop(titles.off, titles.offSubtitle, ReasoningBudget.OFF, ReasoningBudgetIcons.OFF),
            EffortStop(titles.auto, titles.autoSubtitle, ReasoningBudget.AUTO, ReasoningBudgetIcons.AUTO),
            EffortStop(titles.low, titles.lowSubtitle, REASONING_STOP_LOW, ReasoningBudgetIcons.LIGHT),
            EffortStop(titles.medium, titles.mediumSubtitle, REASONING_STOP_MEDIUM, ReasoningBudgetIcons.MEDIUM),
            EffortStop(titles.high, titles.highSubtitle, REASONING_STOP_HIGH, ReasoningBudgetIcons.HEAVY),
        ) +
            (if (showXhigh) {
                listOf(EffortStop(titles.xhigh, titles.xhighSubtitle, REASONING_STOP_XHIGH, ReasoningBudgetIcons.XHIGH))
            } else {
                emptyList()
            }) +
            (if (showMax) {
                // Dart: Max reuses the XHigh subtitle.
                listOf(EffortStop(titles.max, titles.xhighSubtitle, REASONING_STOP_MAX, ReasoningBudgetIcons.XHIGH))
            } else {
                emptyList()
            })

    /** Exact hit, else the numerically closest preset (custom values park there). */
    fun indexFor(stops: List<EffortStop>, selected: Int): Int {
        stops.indexOfFirst { it.value == selected }.takeIf { it >= 0 }?.let { return it }
        var best = 0
        var bestDiff = Double.MAX_VALUE
        stops.forEachIndexed { index, stop ->
            val diff = kotlin.math.abs(stop.value - selected).toDouble()
            if (diff < bestDiff) {
                best = index
                bestDiff = diff
            }
        }
        return best
    }

    fun isCustom(stops: List<EffortStop>, selected: Int): Boolean =
        stops.none { it.value == selected }
}

/**
 * Port of reasoning_budget_sheet.dart as redesigned by upstream 83ab329
 * (2026-09-06): the preset list became a **discrete animated slider** — a thumb
 * that springs between the effort stops (spring mass 1 / stiffness 320 /
 * damping 28), a label pill whose icon and width tween to the active stop, and
 * a custom row underneath. Values are unchanged: off / auto / 1024 / 16000 /
 * 32000 (/ 64000 / 128000 when the model supports them) plus a custom token
 * budget, pushed to [onSelect] on every pick.
 *
 * Deliberate behavior change from the old list version: picking **no longer
 * dismisses** the sheet — the user keeps dragging between levels, and callers
 * refresh whatever they display when the sheet finally closes ([onDismiss]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReasoningBudgetSheet(
    initialBudget: Int?,
    onSelect: (Int) -> Unit,
    modelId: String,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val view = LocalView.current
    val isDark = cs.surface.luminance() < 0.5f

    val showXhigh = ReasoningBudget.supportsXhighReasoning(modelId)
    val showMax = ReasoningBudget.supportsMaxReasoning(modelId)

    val titles = ReasoningStopTitles(
        off = stringResource(UiR.string.reasoning_budget_sheet_off),
        auto = stringResource(UiR.string.reasoning_budget_sheet_auto),
        low = stringResource(UiR.string.reasoning_budget_slider_low),
        medium = stringResource(UiR.string.reasoning_budget_slider_medium),
        high = stringResource(UiR.string.reasoning_budget_slider_high),
        xhigh = stringResource(UiR.string.reasoning_budget_slider_xhigh),
        max = stringResource(UiR.string.reasoning_budget_slider_max),
        offSubtitle = stringResource(UiR.string.reasoning_budget_sheet_off_subtitle),
        autoSubtitle = stringResource(UiR.string.reasoning_budget_sheet_auto_subtitle),
        lowSubtitle = stringResource(UiR.string.reasoning_budget_sheet_light_subtitle),
        mediumSubtitle = stringResource(UiR.string.reasoning_budget_sheet_medium_subtitle),
        highSubtitle = stringResource(UiR.string.reasoning_budget_sheet_heavy_subtitle),
        xhighSubtitle = stringResource(UiR.string.reasoning_budget_sheet_xhigh_subtitle),
    )
    val stops = remember(titles, showXhigh, showMax) {
        ReasoningEffortStops.build(titles, showXhigh, showMax)
    }
    val lastIndex = (stops.size - 1).toFloat()

    var selected by remember { mutableStateOf(initialBudget ?: -1) }
    // Fractional stop index driving thumb/fill; spring-animated unless dragged.
    var position by remember {
        mutableFloatStateOf(ReasoningEffortStops.indexFor(stops, selected).toFloat())
    }
    var dragging by remember { mutableStateOf(false) }
    var customOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snap = remember { Animatable(0f) }

    val customActive = ReasoningEffortStops.isCustom(stops, selected)
    // The pill follows the *animated* thumb (`_position.round()`), so the label
    // flips as the thumb crosses a stop, not the moment the value commits.
    val activeIndex = position.coerceIn(0f, lastIndex).roundToInt()
    val activeStop = stops[activeIndex]

    fun commitIndex(index: Int) {
        if (index < 0 || index >= stops.size) return
        val value = stops[index].value
        if (value == selected) return
        Haptics.soft(view)
        selected = value
        onSelect(value)
    }

    fun animateToIndex(index: Int) {
        scope.launch {
            snap.stop()
            snap.snapTo(position)
            // 本工程 Compose 版的 block 是带 receiver 的（无参）：当前值取 this.value。
            snap.animateTo(
                targetValue = index.toFloat(),
                animationSpec = spring(dampingRatio = 0.78f, stiffness = 320f),
            ) {
                position = value.coerceIn(0f, lastIndex)
            }
        }
    }

    fun snapTo(index: Int) {
        commitIndex(index)
        animateToIndex(index)
    }

    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        containerColor = semantic.overlaySurface(cs),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        dragHandle = null,
    ) {
        Column(modifier = Modifier.fillMaxWidth().imePadding()) {
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
                )
            }
            Spacer(Modifier.height(16.dp))
            // 顶部档位标签：图标 + 标题 + 副标题，随档位切换做宽度/位移动画。
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedEffortGlyph(key = if (customActive) -2 else activeStop.value) {
                        if (customActive) {
                            Icon(
                                Lucide.Hash,
                                contentDescription = null,
                                tint = cs.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            AsyncImage(
                                model = activeStop.icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                colorFilter = ColorFilter.tint(cs.primary),
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    AnimatedWidthText(
                        text = if (customActive) {
                            stringResource(UiR.string.reasoning_budget_sheet_custom_label)
                        } else {
                            activeStop.title
                        },
                        style = TextStyle(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = cs.primary,
                        ),
                    )
                }
                Spacer(Modifier.height(2.dp))
                AnimatedWidthText(
                    text = if (customActive) selected.toString() else activeStop.subtitle,
                    style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.55f)),
                )
            }
            Spacer(Modifier.height(22.dp))
            EffortSlider(
                position = position.coerceIn(0f, lastIndex),
                stopCount = stops.size,
                stopKeys = stops.map { "reasoning-stop-${it.value}" },
                dragging = dragging,
                onDragStart = {
                    scope.launch { snap.stop() }
                    dragging = true
                },
                onDragEnd = {
                    dragging = false
                    snapTo(position.coerceIn(0f, lastIndex).roundToInt())
                },
                onPositionChanged = { fractional ->
                    position = fractional
                    commitIndex(fractional.roundToInt())
                },
                onTapStop = { index -> snapTo(index) },
            )
            Spacer(Modifier.height(20.dp))
            // 自定义推理预算行（Lucide.Hash + 当前值 / 箭头）。
            CardPress(
                onTap = {
                    Haptics.light(view)
                    customOpen = true
                },
                isDark = isDark,
                radius = 14.dp,
                durationMs = 260,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(
                            if (ThemeState.useLayeredSheetTiles) semantic.surfaceCardFill else Color.Transparent,
                            RoundedCornerShape(MemoRadius.INNER_DP.dp),
                        )
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Lucide.Hash,
                        contentDescription = null,
                        tint = if (customActive) cs.primary else cs.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(UiR.string.reasoning_budget_sheet_custom_label),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (customActive) cs.primary else cs.onSurface,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    if (customActive) {
                        Text(
                            text = selected.toString(),
                            style = TextStyle(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = cs.primary,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Lucide.Check,
                            contentDescription = null,
                            tint = cs.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Icon(
                            Lucide.ChevronRight,
                            contentDescription = null,
                            tint = cs.onSurface.copy(alpha = 0.45f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (customOpen) {
        var text by remember { mutableStateOf((if (customActive) selected else 2048).toString()) }
        val parsed = text.trim().toIntOrNull()
        val valid = parsed != null && (parsed == -1 || parsed >= 0)
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = { customOpen = false },
            title = { Text(stringResource(UiR.string.reasoning_budget_sheet_custom_label)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { new -> if (new.isEmpty() || new.matches(Regex("^-?\\d*$"))) text = new },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text(stringResource(UiR.string.reasoning_budget_sheet_custom_hint)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        customOpen = false
                        if (parsed != null && valid) {
                            // Dart: jump straight to the chosen index (no spring).
                            selected = parsed
                            position = ReasoningEffortStops
                                .indexFor(stops, parsed)
                                .coerceIn(0, stops.size - 1)
                                .toFloat()
                            onSelect(parsed)
                        }
                    },
                    enabled = valid,
                ) { Text(stringResource(UiR.string.assistant_edit_emoji_dialog_save)) }
            },
            dismissButton = {
                TextButton(onClick = { customOpen = false }) {
                    Text(stringResource(UiR.string.assistant_edit_emoji_dialog_cancel))
                }
            },
        )
    }
}

/**
 * Discrete-step slider for the reasoning effort presets
 * (`_EffortSlider` + `_SliderPainter`). Purely presentational: the parent owns
 * [position] and animates it; this reports raw drag positions and snap targets.
 */
@Composable
private fun EffortSlider(
    position: Float,
    stopCount: Int,
    stopKeys: List<String>,
    dragging: Boolean,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onPositionChanged: (Float) -> Unit,
    onTapStop: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val dragScale by animateFloatAsState(
        targetValue = if (dragging) 1.14f else 1f,
        animationSpec = tween(durationMillis = 180, easing = EaseOutBack),
        label = "effortThumbScale",
    )

    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = with(density) { 56.dp.toPx() }
        val thumbRadius = with(density) { 19.dp.toPx() }
        val trackHeight = with(density) { 34.dp.toPx() }
        val dotRadius = with(density) { 3.5.dp.toPx() }
        val shadowBlur = with(density) { 10.dp.toPx() }
        val shadowDy = with(density) { 3.dp.toPx() }
        // Stops sit exactly one thumb radius from each end, so at the extremes
        // the thumb is flush with the track edge and fully covers the fill —
        // no visible track/fill sliver beside the thumb.
        val stopInset = thumbRadius
        val span = widthPx - 2 * stopInset

        fun indexForDx(dx: Float): Float {
            if (span <= 0f || stopCount <= 1) return 0f
            return (((dx - stopInset) / span) * (stopCount - 1))
                .coerceIn(0f, (stopCount - 1).toFloat())
        }

        fun thumbX(): Float {
            if (span <= 0f || stopCount <= 1) return stopInset
            return stopInset + span * (position / (stopCount - 1))
        }

        // 这里不需要 constraints（宽度由外层 BoxWithConstraints 量出），用 Box 即可 ——
        // lint UnusedBoxWithConstraintsScope：无 scope 消费的 subcomposition 是白付的成本。
        // pointerInput 键不变时不重启，闭包里直接读 position 参数会拿到首帧旧值
        // （拖完弹回初始档）——rememberUpdatedState 保证读到的是当前值。
        val currentPosition by rememberUpdatedState(position)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .pointerInput(stopCount, widthPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { onDragStart() },
                        onDragEnd = {
                            onDragEnd()
                            onTapStop(currentPosition.coerceIn(0f, (stopCount - 1).toFloat()).roundToInt())
                        },
                        onDragCancel = {
                            onDragEnd()
                            onTapStop(currentPosition.coerceIn(0f, (stopCount - 1).toFloat()).roundToInt())
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            onPositionChanged(indexForDx(change.position.x))
                        },
                    )
                }
                .pointerInput(stopCount, widthPx) {
                    detectTapGestures { offset -> onTapStop(indexForDx(offset.x).roundToInt()) }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawEffortSlider(
                    position = position,
                    stopCount = stopCount,
                    thumbX = thumbX(),
                    thumbRadius = thumbRadius,
                    dragScale = dragScale,
                    trackHeight = trackHeight,
                    dotRadius = dotRadius,
                    shadowBlur = shadowBlur,
                    shadowDy = shadowDy,
                    primary = cs.primary,
                    trackColor = cs.onSurface.copy(alpha = 0.10f),
                    upcomingDotColor = cs.onSurface.copy(alpha = 0.25f),
                )
            }
            // Invisible per-stop anchors (a11y landmarks + test hooks).
            Row(modifier = Modifier.fillMaxSize()) {
                for (key in stopKeys) {
                    Box(modifier = Modifier.weight(1f).testTag(key))
                }
            }
        }
    }
}

private fun DrawScope.drawEffortSlider(
    position: Float,
    stopCount: Int,
    thumbX: Float,
    thumbRadius: Float,
    dragScale: Float,
    trackHeight: Float,
    dotRadius: Float,
    shadowBlur: Float,
    shadowDy: Float,
    primary: Color,
    trackColor: Color,
    upcomingDotColor: Color,
) {
    val stopInset = thumbRadius
    val cy = size.height / 2f
    val trackRect = Rect(
        left = 0f,
        top = cy - trackHeight / 2f,
        right = size.width,
        bottom = cy + trackHeight / 2f,
    )
    val trackRadius = CornerRadius(trackHeight / 2f)
    drawRoundRect(
        color = trackColor,
        topLeft = trackRect.topLeft,
        size = trackRect.size,
        cornerRadius = trackRadius,
    )

    if (thumbX > 0f) {
        val brush = Brush.horizontalGradient(
            colors = listOf(primary.copy(alpha = 0.5f), primary),
            startX = 0f,
            endX = thumbX.coerceIn(0f, size.width),
        )
        val trackPath = Path().apply {
            addRoundRect(RoundRect(rect = trackRect, cornerRadius = trackRadius))
        }
        clipPath(trackPath) {
            // 填充必须贴轨道矩形（上游 Rect.fromLTWH(0, cy - trackHeight/2, …)）——
            // 从 Offset.Zero（Canvas 顶）画会被 clip 截成只剩上半截的细条。
            drawRect(
                brush = brush,
                topLeft = trackRect.topLeft,
                size = Size(thumbX.coerceIn(0f, size.width), trackHeight),
            )
        }
    }

    if (stopCount > 1) {
        val passedColor = Color.White.copy(alpha = 0.9f)
        for (i in 0 until stopCount) {
            val x = stopInset + (size.width - 2 * stopInset) * (i / (stopCount - 1).toFloat())
            // Dots the thumb already passed sit on the fill and turn white.
            val passed = i <= position.roundToInt()
            drawCircle(
                color = if (passed) passedColor else upcomingDotColor,
                radius = dotRadius,
                center = Offset(x, cy),
            )
        }
    }

    // White thumb carrying a fixed soft shadow (blur 10, offset y 3, black 25%).
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.argb((0.25f * 255).toInt(), 0, 0, 0)
            setMaskFilter(android.graphics.BlurMaskFilter(shadowBlur, android.graphics.BlurMaskFilter.Blur.NORMAL))
        }
        canvas.nativeCanvas.drawCircle(thumbX, cy + shadowDy, thumbRadius * dragScale, paint)
    }
    drawCircle(color = Color.White, radius = thumbRadius * dragScale, center = Offset(thumbX, cy))
}

/** `AnimatedSwitcher` with the sheet's slide-up + fade glyph transition. */
@Composable
private fun <T> AnimatedEffortGlyph(key: T, content: @Composable () -> Unit) {
    AnimatedContent(
        targetState = key,
        transitionSpec = {
            (
                slideInVertically(tween(220, easing = EaseOutCubic)) { it / 2 } +
                    fadeIn(tween(220, easing = EaseOutCubic))
                ) togetherWith (
                slideOutVertically(tween(220, easing = EaseInCubic)) { -it / 2 } +
                    fadeOut(tween(220, easing = EaseInCubic))
                )
        },
        label = "effortGlyph",
    ) { target ->
        // lint UnusedContentLambdaTargetStateParameter：content 是按 targetState 查表的
        // 函数，必须消费参数才会在 target 变化时真正切换内容（key() 强制按 key 重启组合）。
        key(target) { content() }
    }
}

/**
 * Text that crossfades between values while its width tweens smoothly
 * (`_AnimatedWidthText`) — a bare switcher snaps its size at the transition
 * boundary, which makes the centered icon+label row jump in a single frame.
 */
@Composable
private fun AnimatedWidthText(text: String, style: TextStyle) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val targetWidth = remember(text, style) {
        measurer.measure(text = text, style = style, maxLines = 1, softWrap = false).size.width
    }
    val width by animateIntAsState(
        targetValue = targetWidth,
        animationSpec = tween(durationMillis = 240, easing = EaseOutCubic),
        label = "effortTextWidth",
    )
    Box(
        modifier = Modifier
            .width(with(density) { width.toDp() })
            .clipToBounds(),
    ) {
        AnimatedContent(
            targetState = text,
            transitionSpec = {
                (
                    slideInVertically(tween(220, easing = EaseOutCubic)) { it / 2 } +
                        fadeIn(tween(220, easing = EaseOutCubic))
                    ) togetherWith (
                    slideOutVertically(tween(220, easing = EaseInCubic)) { -it / 2 } +
                        fadeOut(tween(220, easing = EaseInCubic))
                    )
            },
            label = "effortTextGlyph",
        ) { value ->
            Text(
                text = value,
                style = style,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/** `thinking_budget_v1` reader (null = auto). */
internal fun readBudget(container: AppContainerImpl): Int? {
    val raw = runCatching {
        container.preferenceRepository.readJson("thinking_budget_v1")
    }.getOrNull()
    return parseBudgetJson(raw)
}

/**
 * Pure-JVM parser for the `thinking_budget_v1` payload. Mirrors the
 * `JsonPrimitive.content.toIntOrNull()` projection used by the original Dart
 * `SettingsProvider.readInt`; tolerates null, blank, malformed, and non-int
 * JSON by returning null.
 */
internal fun parseBudgetJson(raw: String?): Int? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(raw)
            .let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
    }.getOrNull()
}

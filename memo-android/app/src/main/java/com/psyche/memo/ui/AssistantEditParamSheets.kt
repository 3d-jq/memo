package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import com.psyche.memo.ui.slider.MemoSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.withAlpha
import kotlin.math.round

/**
 * Port of the three value sheets in `assistant_settings_edit_basic_tab.dart`
 * (`_showTemperatureSheet` L635-747, `_showTopPSheet` L749-859,
 * `_showContextMessagesSheet` L861-998). They share one layout, so they share
 * one composable: 40x4 handle, Title + enable switch, `_SliderTileNew`,
 * description — or the "parameter disabled" caption while the switch is off.
 *
 * 原版这里用 Syncfusion `SfSlider`（刻度 + 间隔标签 + 水滴 tooltip）；我们换成
 * `MemoSlider`（照「推理强度」滑条：药丸轨道 + 档位圆点 + 拖动胶囊），所以**刻度线与
 * 间隔标签由档位圆点/胶囊承担**，`customLabelStops`（上下文消息那组预设值）仍画在滑条下方
 * 作为数值参考 —— 但原版按值线性摆位会把前五个档位叠在一起，见 [spreadLabelStops]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ParamSliderSheet(
    title: String,
    description: String,
    disabledText: String,
    enabled: Boolean,
    value: Double,
    minValue: Double,
    maxValue: Double,
    labelOf: (Double) -> String,
    onEnabledChange: (Boolean) -> Unit,
    onValueChange: (Double) -> Unit,
    onDismiss: () -> Unit,
    customLabelStops: List<Double> = emptyList(),
    onValuePillTap: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val range = minValue.toFloat()..maxValue.toFloat()
    var local by remember { mutableDoubleStateOf(value.coerceIn(minValue, maxValue)) }

    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        dragHandle = null, // 原版自绘 40x4 拖柄
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        containerColor = semantic.overlaySurface(cs),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 18.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 40.dp, height = 4.dp)
                    .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    maxLines = 1,
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                    modifier = Modifier.weight(1f),
                )
                IosSwitch(value = enabled, onValueChanged = onEnabledChange)
            }
            Spacer(Modifier.height(8.dp))
            if (enabled) {
                SliderTile(
                    value = local.toFloat().coerceIn(range.start, range.endInclusive),
                    valueText = labelOf(local),
                    // 范围上限的文案即最宽文案：胶囊按它预留宽度，拖动时不会挤动滑条。
                    widestValueText = labelOf(maxValue),
                    range = range,
                    labelStops = customLabelStops.filter { it in minValue..maxValue }.sorted(),
                    onValueChange = { v ->
                        local = v.toDouble()
                        onValueChange(v.toDouble())
                    },
                    onValuePillTap = onValuePillTap,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = description,
                    style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                )
            } else {
                Text(
                    text = disabledText,
                    style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

/** `_SliderTileNew` L1217-1401 — slider (+ optional custom label row) and the value pill. */
@Composable
private fun SliderTile(
    value: Float,
    valueText: String,
    /** 该参数**最宽**的数值文案（= 范围上限的格式化结果），用来给胶囊预留固定宽度。 */
    widestValueText: String,
    range: ClosedFloatingPointRange<Float>,
    labelStops: List<Double>,
    onValueChange: (Float) -> Unit,
    onValuePillTap: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            MemoSlider(
                value = value,
                onValueChange = onValueChange,
                valueRange = range,
                // 右侧已有 ValuePill 常显当前值，拖动胶囊复用它（同一份文案）。
                valueLabel = { valueText },
            )
            if (labelStops.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                SliderLabelRow(stops = labelStops, range = range)
            }
        }
        Spacer(Modifier.width(8.dp))
        // 胶囊宽度按最宽文案预留（"0.50" → "1.00"、"9" → "4096" 都会变宽，不能挤动左边的滑条）；
        // 点胶囊仍可打开精确输入。
        ValuePill(text = valueText, widest = widestValueText, onTap = onValuePillTap)
    }
}

/**
 * 滑条下方的预设值标签行（原版 `_SliderTileNew` L1359-1388 的 `Stack` + `Align`）。
 *
 * 位置仍按**真实值**线性摆（`BiasAlignment(-1 + t*2)`，与滑条刻度对齐），但先过一遍
 * [spreadLabelStops] 把会叠在一起的档位去掉 —— 原版这里直接全画，导致「上下文消息」那组
 * （1/64/128/256/512/1024/2048/4096）前五个标签挤在左侧 12% 里叠成一团
 * （用户 2026-09-16「助手里面那个上下文消息这个下面那个数字显示有重叠」）。
 */
@Composable
internal fun SliderLabelRow(
    stops: List<Double>,
    range: ClosedFloatingPointRange<Float>,
) {
    val cs = MaterialTheme.colorScheme
    val span = range.endInclusive - range.start
    val visible = remember(stops, range) {
        spreadLabelStops(stops, range.start, range.endInclusive)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp),
    ) {
        visible.forEach { stop ->
            val t = if (span == 0f) 0f else ((stop.toFloat() - range.start) / span).coerceIn(0f, 1f)
            Text(
                text = formatStopLabel(stop),
                style = TextStyle(fontSize = 11.sp, color = cs.onSurface.copy(alpha = 0.65f)),
                maxLines = 1,
                modifier = Modifier.align(
                    BiasAlignment(horizontalBias = -1f + t * 2f, verticalBias = 0f),
                ),
            )
        }
    }
}

/**
 * 预设值标签去重叠（纯逻辑，`AssistantParamLabelStopsTest` 钉住）。
 *
 * 原版按值线性摆位；档位值若是「1、64、128、256、512、1024、2048、4096」这种密集起步的
 * 序列，前几个（1/64/128/256 全在左侧 6%）与 512（12.5%）会挤在同一处，11sp 的标签直接
 * 叠在一起。规则：从左到右贪心保留，与前一个保留项的**位置差**不足 [minGap]（默认 12% 行宽）
 * 就跳过；首项与末项一定保留（末项与前一项太近时**顶替**它，保证最大档位始终可见）。
 */
internal fun spreadLabelStops(
    stops: List<Double>,
    minValue: Float,
    maxValue: Float,
    minGap: Float = 0.12f,
): List<Double> {
    val span = maxValue - minValue
    if (span <= 0f || stops.size <= 1) return stops
    val out = ArrayList<Double>(stops.size)
    var lastFraction = Float.NEGATIVE_INFINITY
    stops.forEachIndexed { index, stop ->
        val fraction = ((stop.toFloat() - minValue) / span).coerceIn(0f, 1f)
        val isLast = index == stops.lastIndex
        if (isLast) {
            if (out.isNotEmpty() && fraction - lastFraction < minGap) out.removeAt(out.size - 1)
            out += stop
            return@forEachIndexed
        }
        if (out.isEmpty() || fraction - lastFraction >= minGap) {
            out += stop
            lastFraction = fraction
        }
    }
    return out
}

/** `_ValuePill` L1403-1439 —— 宽度按 [widest]（该参数最宽的数值文案）预留，避免拖动时挤动滑条。 */
@Composable
private fun ValuePill(text: String, widest: String, onTap: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val isDark = LocalSemanticColors.current.isDark
    val shape = RoundedCornerShape(MemoRadius.SMALL_DP.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(withAlpha(cs.primary, if (isDark) 0.18 else 0.10))
            .border(1.dp, withAlpha(cs.primary, if (isDark) 0.28 else 0.22), shape)
            .then(if (onTap != null) Modifier.clickable { onTap() } else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        com.psyche.memo.ui.slider.SliderValueLabel(
            text = text,
            widest = widest,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cs.primary),
            contentAlignment = Alignment.Center,
            textAlign = TextAlign.Center,
        )
    }
}

/** `_SliderTileNew` labelFormatter — integers for wide ranges, one decimal otherwise. */
private fun formatStopLabel(stop: Double): String =
    if (stop == round(stop)) stop.toInt().toString() else String.format("%.1f", stop)

/**
 * `_showMaxTokensSheet` L1000-1130 — handle, X / Title / Save header, one number
 * field (focused on open) and the description caption.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MaxTokensSheet(
    initial: Int?,
    title: String,
    hint: String,
    description: String,
    saveLabel: String,
    onDismiss: () -> Unit,
    onSave: (Int?) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var text by remember { mutableStateOf(initial?.toString() ?: "") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun save() {
        onSave(text.trim().toIntOrNull())
        onDismiss()
    }

    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        containerColor = semantic.overlaySurface(cs),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 40.dp, height = 4.dp)
                    .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TactileIconButton(
                    icon = Lucide.X,
                    color = cs.onSurface,
                    size = 20.dp,
                    onTap = onDismiss,
                )
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = title,
                        maxLines = 1,
                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                    )
                }
                TactileRow(onTap = { save() }) { pressed ->
                    Text(
                        text = saveLabel,
                        maxLines = 1,
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (pressed) withAlpha(cs.primary, 0.7) else cs.primary,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            FilledNumberField(
                value = text,
                hint = hint,
                onValueChange = { text = it },
                onSubmit = { save() },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = description,
                style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.6f)),
            )
        }
    }
}

/**
 * `_showContextMessageInputDialog` (assistant_settings_edit_page.dart L181-256)
 * — exact-value entry for the context-message slider, whose 1-4096 range makes
 * dragging imprecise.
 */
@Composable
internal fun ContextMessageInputDialog(
    initialValue: Int,
    minValue: Int,
    maxValue: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val title = stringResource(UiR.string.assistant_edit_context_messages_title)
    val description = stringResource(UiR.string.assistant_edit_context_messages_description)
    var text by remember {
        mutableStateOf(initialValue.coerceIn(minValue, maxValue).toString())
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val rangeLabel = "$minValue-$maxValue"
    val parsed = text.trim().toIntOrNull()

    fun submit() {
        val v = parsed ?: return
        onConfirm(v.coerceIn(minValue, maxValue))
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                    label = { Text("$title ($rangeLabel)") },
                    supportingText = { Text(rangeLabel) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = semantic.surfaceFill,
                        unfocusedContainerColor = semantic.surfaceFill,
                        focusedIndicatorColor = withAlpha(cs.primary, 0.4),
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = cs.primary,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "$description ($rangeLabel)",
                    style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.65f)),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = parsed != null) {
                Text(
                    text = stringResource(UiR.string.assistant_edit_emoji_dialog_save),
                    style = TextStyle(
                        fontWeight = FontWeight.SemiBold,
                        color = if (parsed != null) cs.primary else cs.onSurface.copy(alpha = 0.38f),
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(UiR.string.assistant_edit_emoji_dialog_cancel))
            }
        },
    )
}

/** Dart `TextField(filled: true)` with the sheet's surfaceFill + 12dp radius. */
@Composable
internal fun FilledNumberField(
    value: String,
    hint: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = { Text(hint, style = TextStyle(fontSize = 16.sp, color = cs.onSurface.copy(alpha = 0.4f))) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = semantic.surfaceFill,
            unfocusedContainerColor = semantic.surfaceFill,
            focusedIndicatorColor = withAlpha(cs.primary, 0.5),
            unfocusedIndicatorColor = withAlpha(cs.outlineVariant, 0.4),
            cursorColor = cs.primary,
        ),
    )
}

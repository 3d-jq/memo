package com.psyche.memo.ui

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
import androidx.compose.material3.Slider
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
 * SfSlider's ticks, interval labels and waterdrop tooltip have no material3
 * equivalent, so all three are dropped (same simplification as
 * `MessageStyleSettingsScreen`); the live value stays visible in [ValuePill].
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
    divisions: Int,
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
        onDismissRequest = onDismiss,
        dragHandle = null, // 原版自绘 40x4 拖柄
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
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
                    .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(999.dp)),
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
                    range = range,
                    steps = (divisions - 1).coerceAtLeast(0),
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
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    labelStops: List<Double>,
    onValueChange: (Float) -> Unit,
    onValuePillTap: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = range,
                steps = steps,
            )
            if (labelStops.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                val span = range.endInclusive - range.start
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp),
                ) {
                    labelStops.forEach { stop ->
                        val t = if (span == 0f) 0f else ((stop.toFloat() - range.start) / span).coerceIn(0f, 1f)
                        Text(
                            text = formatStopLabel(stop),
                            style = TextStyle(fontSize = 11.sp, color = cs.onSurface.copy(alpha = 0.65f)),
                            modifier = Modifier.align(
                                BiasAlignment(horizontalBias = -1f + t * 2f, verticalBias = 0f),
                            ),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        ValuePill(text = valueText, onTap = onValuePillTap)
    }
}

/** `_ValuePill` L1403-1439. */
@Composable
private fun ValuePill(text: String, onTap: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val isDark = LocalSemanticColors.current.isDark
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(withAlpha(cs.primary, if (isDark) 0.18 else 0.10))
            .border(1.dp, withAlpha(cs.primary, if (isDark) 0.28 else 0.22), shape)
            .then(if (onTap != null) Modifier.clickable { onTap() } else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cs.primary),
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
        onDismissRequest = onDismiss,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
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
                    .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(999.dp)),
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
        shape = RoundedCornerShape(16.dp),
        containerColor = semantic.overlaySurface(cs),
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
                    shape = RoundedCornerShape(12.dp),
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
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = semantic.surfaceFill,
            unfocusedContainerColor = semantic.surfaceFill,
            focusedIndicatorColor = withAlpha(cs.primary, 0.5),
            unfocusedIndicatorColor = withAlpha(cs.outlineVariant, 0.4),
            cursorColor = cs.primary,
        ),
    )
}

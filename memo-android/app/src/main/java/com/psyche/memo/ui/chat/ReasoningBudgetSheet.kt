package com.psyche.memo.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Hash
import com.composables.icons.lucide.Lucide
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.Haptics
import com.psyche.memo.llm.client.ReasoningBudget
import com.psyche.memo.ui.theme.LocalSemanticColors
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

/**
 * Port of reasoning_budget_sheet.dart: off / auto / light / medium / heavy /
 * xhigh / max presets plus a custom token budget. The sheet writes
 * `thinking_budget_v1` through [onSelect].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReasoningBudgetSheet(
    container: AppContainerImpl,
    modelId: String,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val view = LocalView.current
    val context = LocalContext.current
    var selected by remember { mutableStateOf(readBudget(container)) }
    var customOpen by remember { mutableStateOf(false) }

    val showXhigh = ReasoningBudget.supportsXhighReasoning(modelId)
    val showMax = ReasoningBudget.supportsMaxReasoning(modelId)
    val presets = setOf(-1, 0, 1024, 16000, 32000) +
        (if (showXhigh) setOf(64000) else emptySet()) +
        (if (showMax) setOf(128000) else emptySet())
    val customActive = selected !in presets

    fun select(value: Int) {
        selected = value
        container.preferenceRepository.writeJson(
            "thinking_budget_v1",
            kotlinx.serialization.json.JsonPrimitive(value).toString(),
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = semantic.overlaySurface(cs),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = null,
    ) {
        Column(modifier = Modifier.fillMaxWidth().imePadding()) {
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(999.dp)),
                )
            }
            Spacer(Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
            ) {
                BudgetTile(stringResource(UiR.string.reasoning_budget_sheet_off), 0, ReasoningBudgetIcons.OFF, selected == 0) {
                    Haptics.light(view); select(0); onDismiss()
                }
                BudgetTile(stringResource(UiR.string.reasoning_budget_sheet_auto), -1, ReasoningBudgetIcons.AUTO, selected == -1) {
                    Haptics.light(view); select(-1); onDismiss()
                }
                BudgetTile(stringResource(UiR.string.reasoning_budget_sheet_light), 1024, ReasoningBudgetIcons.LIGHT, selected == 1024) {
                    Haptics.light(view); select(1024); onDismiss()
                }
                BudgetTile(stringResource(UiR.string.reasoning_budget_sheet_medium), 16000, ReasoningBudgetIcons.MEDIUM, selected == 16000) {
                    Haptics.light(view); select(16000); onDismiss()
                }
                BudgetTile(stringResource(UiR.string.reasoning_budget_sheet_heavy), 32000, ReasoningBudgetIcons.HEAVY, selected == 32000) {
                    Haptics.light(view); select(32000); onDismiss()
                }
                if (showXhigh) {
                    BudgetTile(stringResource(UiR.string.reasoning_budget_sheet_xhigh), 64000, ReasoningBudgetIcons.XHIGH, selected == 64000) {
                        Haptics.light(view); select(64000); onDismiss()
                    }
                }
                if (showMax) {
                    BudgetTile(stringResource(UiR.string.reasoning_budget_sheet_max), 128000, ReasoningBudgetIcons.XHIGH, selected == 128000) {
                        Haptics.light(view); select(128000); onDismiss()
                    }
                }
                // Custom row (Lucide.Hash + current value / chevron).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .height(48.dp)
                        .clickable {
                            Haptics.light(view)
                            customOpen = true
                        }
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
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.primary),
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(Lucide.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
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
        }
    }

    if (customOpen) {
        var text by remember { mutableStateOf((if (customActive) selected else 2048).toString()) }
        val parsed = text.trim().toIntOrNull()
        val valid = parsed != null && (parsed == -1 || parsed >= 0)
        AlertDialog(
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
                            select(parsed)
                            onDismiss()
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

@Composable
private fun BudgetTile(
    title: String,
    value: Int,
    asset: String,
    active: Boolean,
    onTap: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .height(48.dp)
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = asset,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                if (active) cs.primary else cs.onSurface.copy(alpha = 0.7f),
            ),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (active) cs.primary else cs.onSurface,
            ),
            modifier = Modifier.weight(1f),
        )
        if (active) {
            Icon(Lucide.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
        } else {
            Spacer(Modifier.width(18.dp))
        }
    }
}

/** thinking_budget_v1 reader (null = auto). */
internal fun readBudget(container: AppContainerImpl): Int? {
    val raw = runCatching {
        container.preferenceRepository.readJson("thinking_budget_v1")
    }.getOrNull() ?: return null
    return runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(raw)
            .let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
    }.getOrNull()
}

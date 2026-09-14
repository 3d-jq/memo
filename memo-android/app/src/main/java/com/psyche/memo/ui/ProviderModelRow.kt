package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings2
import com.composables.icons.lucide.CircleX
import com.psyche.memo.ModelRegistry
import com.psyche.memo.data.model.ProviderConfig
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Per-model connection-check progress, shared by the models list, the batch
 * runner and the selection toolbar. Mirrors the three transient states
 * `_ModelCard` renders (isDetecting / isPending / detectionResult).
 */
enum class ModelCheckState {
    /** No check has been run for this model. */
    IDLE,

    /** Queued behind another model in a batch run. */
    PENDING,

    /** Currently being checked. */
    RUNNING,

    /** Check finished successfully. */
    SUCCESS,

    /** Check finished with an error. */
    FAILURE,
}

/** Result + message for one model, kept so the failure tooltip survives recomposition. */
data class ModelCheckResult(
    val state: ModelCheckState,
    val message: String? = null,
)

/**
 * Resolved display identity of a model id: the effective display name plus the
 * base id whose brand avatar should be shown.
 *
 * Port of `_ModelCard._resolveBaseAndOverride` — an override may rename the
 * model (`displayName`) and/or point at a different upstream (`apiModelId`), and
 * the avatar follows the *upstream* id (`resolved.baseId`), not the local alias.
 */
internal data class ResolvedModelIdentity(
    val displayName: String,
    val baseId: String,
)

/** Pure part of the identity resolution (unit-tested). */
internal fun resolveModelIdentity(
    modelId: String,
    override: JsonObject?,
): ResolvedModelIdentity {
    fun str(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (override?.get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }
    val baseId = str("apiModelId", "api_model_id") ?: modelId
    val displayName = str("displayName", "display_name") ?: modelId
    return ResolvedModelIdentity(displayName = displayName, baseId = baseId)
}

/** Looks the override up on the config; a wrong-typed value is ignored, not crashed on. */
internal fun ProviderConfig.overrideFor(modelId: String): JsonObject? =
    modelOverrides[modelId] as? JsonObject

/**
 * One row of the models list — port of `_ModelCard` (`provider_detail_page.dart`
 * L4022-4186).
 *
 * Layout, left to right: `[IosCheckbox when selecting]`, brand avatar, display
 * name over the capability tag row, the connection-check indicator, and — when
 * not selecting — the standalone edit button.
 *
 * The row body itself is deliberately inert outside selection mode: Flutter
 * binds `onTap` to `() {}` there, and editing goes through the trailing
 * `Settings2` button. Binding the whole row to "test this model" (the previous
 * behaviour here) made every tap fire a network request.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderModelRow(
    modelId: String,
    cfg: ProviderConfig,
    selectMode: Boolean,
    selected: Boolean,
    check: ModelCheckResult?,
    onToggleSelect: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val identity = remember(modelId, cfg.modelOverrides) {
        resolveModelIdentity(modelId, cfg.overrideFor(modelId))
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = selectMode) { onToggleSelect() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectMode) {
            IosCheckbox(
                value = selected,
                onValueChanged = { onToggleSelect() },
            )
            Spacer(Modifier.width(12.dp))
        }

        // _BrandAvatar(resolved.baseId, size: 28) — brand logo, falling back to
        // the upstream id's initial. baseId (not the alias) decides the brand.
        Box(modifier = Modifier.size(28.dp)) {
            ProviderAvatarSmall(
                providerKey = identity.baseId,
                displayName = identity.baseId,
                size = 28.dp,
            )
        }
        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = identity.displayName.ifEmpty { modelId },
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(4.dp))
            ModelTagRow(modelId = identity.baseId, cfg = cfg)
        }

        if (check != null && check.state != ModelCheckState.IDLE) {
            Spacer(Modifier.width(8.dp))
            ModelCheckIndicator(check = check)
        }

        if (!selectMode) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Lucide.Settings2,
                contentDescription = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_edit_tooltip),
                tint = cs.onSurface.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(34.dp)
                    .padding(7.dp)
                    .clickable { onEdit() },
            )
        }
    }
}

/**
 * The trailing status indicator of [ProviderModelRow]:
 * - RUNNING  — 16dp spinner in the primary colour,
 * - PENDING  — 16dp hollow ring (queued, not started),
 * - SUCCESS  — filled CheckCircle in `appColors.success`,
 * - FAILURE  — XCircle in `cs.error`, wrapped in a tooltip carrying the message.
 *
 * IDLE renders nothing (the caller filters it out, but this stays total).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelCheckIndicator(check: ModelCheckResult) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    when (check.state) {
        ModelCheckState.RUNNING -> CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = cs.primary,
        )

        ModelCheckState.PENDING -> Box(
            modifier = Modifier
                .size(16.dp)
                .border(2.dp, cs.onSurface.copy(alpha = 0.3f), androidx.compose.foundation.shape.CircleShape),
        )

        // 成功也带 tooltip（provider_detail_page L4076-4091：成功/失败都在
        // Tooltip 里，点一下才显示）。
        ModelCheckState.SUCCESS -> {
            val tipState = rememberTooltipState(isPersistent = true)
            val scope = rememberCoroutineScope()
            val successLabel = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_detect_success)
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                state = tipState,
                tooltip = { PlainTooltip { Text(successLabel) } },
            ) {
                Icon(
                    imageVector = Lucide.CircleCheck,
                    contentDescription = successLabel,
                    tint = semantic.success,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { scope.launch { tipState.show() } },
                )
            }
        }

        ModelCheckState.FAILURE -> {
            val tipState = rememberTooltipState(isPersistent = true)
            val scope = rememberCoroutineScope()
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                state = tipState,
                tooltip = {
                    PlainTooltip {
                        Text(
                            check.message
                                ?: stringResource(com.psyche.memo.ui.R.string.provider_detail_page_detect_failed),
                        )
                    }
                },
            ) {
                Icon(
                    imageVector = Lucide.CircleX,
                    contentDescription = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_detect_failed),
                    tint = cs.error,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { scope.launch { tipState.show() } },
                )
            }
        }

        ModelCheckState.IDLE -> Unit
    }
}

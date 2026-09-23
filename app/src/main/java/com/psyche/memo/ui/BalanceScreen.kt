package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Coins
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.ProviderConfig
import com.psyche.memo.data.repo.ProviderRepository
import com.psyche.memo.provider.ProviderBalanceService
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlinx.coroutines.launch

/**
 * Port of provider_balance_page.dart: enable switch, API-path / result-path
 * inputs saved on every change, live status area (auto-fetching badge or the
 * query outcome), reset-to-defaults button and the query button. Edits flow
 * through [onCfgChange] so the owning detail screen persists them.
 */
@Composable
fun BalanceScreen(
    container: AppContainerImpl,
    cfg: ProviderConfig,
    onCfgChange: (ProviderConfig) -> Unit,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var balanceLoading by remember { mutableStateOf(false) }
    var balanceValue by remember { mutableStateOf<String?>(null) }
    var balanceError by remember { mutableStateOf<String?>(null) }

    val enabled = cfg.balanceEnabled == true
    val queryButtonLabel = if (balanceLoading) {
        stringResource(R.string.provider_detail_page_balance_querying)
    } else {
        stringResource(R.string.provider_detail_page_balance_query_button)
    }

    fun saveBalance(enabled: Boolean, apiPath: String, resultPath: String) {
        onCfgChange(
            cfg.copy(
                balanceEnabled = enabled,
                balanceApiPath = apiPath.trim(),
                balanceResultPath = resultPath.trim(),
            ),
        )
    }

    fun queryBalance() {
        if (balanceLoading) return
        balanceLoading = true
        balanceValue = null
        balanceError = null
        scope.launch {
            try {
                // 阻塞式 OkHttp：必须在 IO 上跑 —— `scope` 默认是 Main，
                // 之前就是这里抛 NetworkOnMainThreadException（用户 2026-09-22 实测）。
                val value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ProviderBalanceService.fetchBalance(cfg, container.httpClient)
                }
                balanceValue = context.getString(R.string.provider_detail_page_balance_result, value)
                SnackbarManager.show(
                    AppNotification(
                        message = context.getString(R.string.provider_detail_page_balance_result, value),
                        type = NotificationType.SUCCESS,
                    ),
                )
            } catch (e: Exception) {
                balanceError = context.getString(R.string.provider_detail_page_balance_error, e.message ?: e.toString())
                SnackbarManager.show(
                    AppNotification(
                        message = context.getString(R.string.provider_detail_page_balance_error, e.message ?: e.toString()),
                        type = NotificationType.ERROR,
                    ),
                )
            } finally {
                balanceLoading = false
            }
        }
    }

    // Opaque surface: this page renders as a full-screen overlay above the
    // detail screen, so without a background it shows through.
    Column(modifier = Modifier.fillMaxSize().background(cs.surface).statusBarsPadding()) {
        // ---- AppBar ----
        MemoTopBar(
            title = stringResource(R.string.provider_detail_page_balance_title),
            onBack = onBack,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.provider_detail_page_balance_info),
                        style = TextStyle(fontSize = 15.sp),
                        modifier = Modifier.weight(1f),
                    )
                    IosSwitch(
                        value = enabled,
                        onValueChanged = { v ->
                            balanceValue = null
                            balanceError = null
                            saveBalance(v, cfg.balanceApiPath ?: "", cfg.balanceResultPath ?: "")
                        },
                    )
                }
            }
            if (enabled) {
                item { Spacer(Modifier.height(12.dp)) }
                item {
                    BalanceField(
                        label = stringResource(R.string.provider_detail_page_balance_api_path_label),
                        value = cfg.balanceApiPath ?: "",
                        onValueChange = { v ->
                            balanceValue = null
                            balanceError = null
                            saveBalance(enabled, v, cfg.balanceResultPath ?: "")
                        },
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
                item {
                    BalanceField(
                        label = stringResource(R.string.provider_detail_page_balance_result_path_label),
                        value = cfg.balanceResultPath ?: "",
                        onValueChange = { v ->
                            balanceValue = null
                            balanceError = null
                            saveBalance(enabled, cfg.balanceApiPath ?: "", v)
                        },
                    )
                }
                item { Spacer(Modifier.height(10.dp)) }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AnimatedContent(
                            targetState = balanceError ?: balanceValue,
                            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                            modifier = Modifier.weight(1f),
                        ) { status ->
                            when {
                                status != null -> Text(
                                    text = status,
                                    style = TextStyle(
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (balanceError != null) cs.error else cs.onSurface.copy(alpha = 0.72f),
                                    ),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                else -> ProviderBalanceBadge(
                                    cfg = cfg,
                                    container = container,
                                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                                    color = cs.primary,
                                )
                            }
                        }
                        ResetDefaultsButton {
                            val defaults = ProviderRepository.defaultsFor(cfg.id)
                            balanceValue = null
                            balanceError = null
                            saveBalance(
                                defaults.balanceEnabled ?: false,
                                defaults.balanceApiPath ?: "",
                                defaults.balanceResultPath ?: "",
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        BalanceQueryButton(
                            label = queryButtonLabel,
                            enabled = !balanceLoading,
                            onTap = { queryBalance() },
                        )
                    }
                }
            }
        }
    }
}

/** Label + filled r10 field (provider_balance_page _inputRow + decoration). */
@Composable
private fun BalanceField(label: String, value: String, onValueChange: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Column {
        Text(
            text = label,
            style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.8f)),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp),
            shape = RoundedCornerShape(MemoRadius.SMALL_DP.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = semantic.surfaceFill,
                unfocusedContainerColor = semantic.surfaceFill,
                focusedBorderColor = cs.primary.copy(alpha = 0.35f),
                unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.12f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** RefreshCw round button (IosIconButton 36dp in upstream). */
@Composable
private fun ResetDefaultsButton(onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Lucide.RefreshCw,
            contentDescription = stringResource(R.string.provider_detail_page_balance_reset_defaults_tooltip),
            tint = cs.onSurface.copy(alpha = 0.72f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Coins + label pill (provider_balance_page _BalanceQueryButton). */
@Composable
private fun BalanceQueryButton(label: String, enabled: Boolean, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var pressed by remember { mutableStateOf(false) }
    val base = if (enabled) cs.primary else cs.onSurface.copy(alpha = 0.38f)
    val bg = if (enabled) {
        cs.primary.copy(alpha = if (pressed) 0.18f else 0.12f)
    } else {
        cs.onSurface.copy(alpha = 0.06f)
    }
    Row(
        modifier = Modifier
            .clickable(enabled = enabled) { onTap() }
            .background(bg, RoundedCornerShape(MemoRadius.SMALL_DP.dp))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.Coins, contentDescription = null, tint = base, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = base),
        )
    }
}

/**
 * Port of provider_balance_badge.dart: Coins icon + fetched balance value
 * ('~' while loading, '!' on failure). Fetches only for OpenAI-compatible
 * providers with balance enabled; re-fetches when the relevant config parts
 * change (upstream cache key fields).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderBalanceBadge(
    cfg: ProviderConfig,
    container: AppContainerImpl,
    style: TextStyle = TextStyle(fontSize = 12.sp),
    color: androidx.compose.ui.graphics.Color? = null,
) {
    val cs = MaterialTheme.colorScheme
    val badgeColor = color ?: cs.onSurface.copy(alpha = 0.62f)
    var value by remember { mutableStateOf("~") }
    var error by remember { mutableStateOf("") }

    // 原版 `provider_balance_badge.dart` L133-135：非 OpenAI 或未开余额时
    // **什么都不画**（此前恒画一个「~」）。L160-166 把失败原因放进 tooltip。
    val canFetch = cfg.classifiedKind() == "openai" && cfg.balanceEnabled == true
    if (!canFetch) return
    LaunchedEffect(cfg.id, cfg.balanceApiPath, cfg.balanceResultPath, cfg.apiKey, cfg.multiKeyEnabled, cfg.apiKeys?.size) {
        runCatching {
            // LaunchedEffect 的体跑在组合线程（主线程）上 —— 同一类崩溃的第二个入口，
            // 一起挪到 IO（见 AGENTS「Composition must stay cheap」那条的同类问题）。
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ProviderBalanceService.fetchBalance(cfg, container.httpClient)
            }
        }.onSuccess {
            value = it
            error = ""
        }.onFailure {
            value = "!"
            error = it.message ?: it.toString()
        }
    }

    val badge: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.Coins, contentDescription = null, tint = badgeColor, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                text = value,
                style = style.copy(color = badgeColor),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 108.dp),
            )
        }
    }
    if (error.isEmpty()) {
        badge()
    } else {
        val tipState = rememberTooltipState(isPersistent = true)
        val scope = rememberCoroutineScope()
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            state = tipState,
            tooltip = {
                PlainTooltip {
                    Text(stringResource(com.psyche.memo.ui.R.string.provider_detail_page_balance_error, error))
                }
            },
        ) {
            Box(modifier = Modifier.clickable { scope.launch { tipState.show() } }) { badge() }
        }
    }
}

package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.AudioWaveform
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Network
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Settings2
import com.composables.icons.lucide.Trash2
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.Haptics
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.withAlpha

/**
 * AsrServicesSection (asr_services_section.dart) — the voice-recognition half
 * of the services page, embedded under the TTS section (tts_services_page.dart
 * L200). Mobile form factor only.
 *
 * Scope note: `sherpa_onnx` (offline models) needs the SherpaModelManager
 * runtime (download/install/inference) which is its own batch; the on-device
 * group therefore shows System only, mirroring what actually works on this
 * platform. Cloud kinds are fully editable and persisted under
 * `asr_services_v1` with byte-identical JSON to the Flutter app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AsrServicesSection(
    container: AppContainerImpl,
    onOpenAsrEditor: (serviceId: String?) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    val store = container.asrServicesStore

    LaunchedEffect(Unit) { store.load() }

    Column(Modifier.fillMaxWidth()) {
        // VoiceServiceSectionHeader (L129-134): title + trailing "+" add button.
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 18.dp, end = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(UiR.string.asr_services_section_title),
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.8)),
                modifier = Modifier.weight(1f),
            )
            val addTooltip = stringResource(UiR.string.asr_services_add_tooltip)
            val addInteraction = remember { MutableInteractionSource() }
            val addPressed by addInteraction.collectIsPressedAsState()
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        withAlpha(cs.onSurface, if (addPressed) 0.10 else 0.0),
                        RoundedCornerShape(MemoRadius.SMALL_DP.dp),
                    )
                    .clickable(interactionSource = addInteraction, indication = null) {
                        Haptics.light(view)
                        onOpenAsrEditor(null)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.Plus, contentDescription = addTooltip, modifier = Modifier.size(18.dp), tint = cs.onSurface)
            }
        }

        // store.version bumps whenever the editor page (AsrServicesEditorScreen)
        // upserts/adds/removes — both share container.asrServicesStore, so the
        // list re-renders without any nav-result plumbing.
        val services = store.services
        if (services.isEmpty()) {
            // _EmptyAsrState L188-229.
            SectionCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(UiR.string.asr_services_empty_title),
                        textAlign = TextAlign.Center,
                        style = TextStyle(fontSize = 14.sp, color = withAlpha(cs.onSurface, 0.6)),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(UiR.string.asr_services_empty_subtitle),
                        textAlign = TextAlign.Center,
                        style = TextStyle(fontSize = 12.sp, color = withAlpha(cs.onSurface, 0.5)),
                    )
                }
            }
        } else {
            SectionCard {
                key(store.version) {
                    services.forEachIndexed { index, service ->
                        AsrServiceCard(
                            service = service,
                            selected = store.selectedServiceId == service.id,
                            onSelect = { store.selectedServiceId = service.id },
                            onEdit = { onOpenAsrEditor(service.id) },
                            onDelete = { store.remove(service) },
                        )
                        if (index != services.lastIndex) {
                            // voiceServiceMobileDivider L145-154 (indent 54, end 12).
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 54.dp, end = 12.dp)
                                    .height(0.6.dp)
                                    .background(withAlpha(cs.outlineVariant, 0.18)),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** _AsrServiceCard mobile (L263-326). */
@Composable
private fun AsrServiceCard(
    service: AsrServiceOptions,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val displayName = asrServiceDisplayName(service)
    TactileRow(onTap = onSelect, haptics = false) { pressed ->
        val overlay = withAlpha(cs.onSurface, 0.05).takeIf { pressed } ?: Color.Transparent
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(overlay)
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsrProviderBadge(kind = service.kind, badgeSize = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.9)),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    asrKindSubtitle(service.kind),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 12.sp, color = withAlpha(cs.onSurface, 0.6)),
                )
            }
            Spacer(Modifier.width(8.dp))
            SmallTactileIcon(icon = Lucide.Settings2, tint = withAlpha(cs.onSurface, 0.9), onTap = onEdit)
            Spacer(Modifier.width(6.dp))
            SmallTactileIcon(icon = Lucide.Trash2, tint = withAlpha(cs.onSurface, 0.9), onTap = onDelete)
            Spacer(Modifier.width(8.dp))
            if (selected) {
                Icon(Lucide.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = withAlpha(cs.onSurface, 0.9))
            } else {
                Spacer(Modifier.width(16.dp))
            }
        }
    }
}

/**
 * `_ProviderBadge` L444-482 —— **有品牌图标就画图标、否则回落该种类图标**。
 *
 * 用户 2026-09-16「语音识别这个图标你怎么没有弄呀」：此前这里只画种类图标
 *（原注释写着 brand assets are a later batch，那个批次一直没补）。上游按
 * [asrKindBrandAsset] 的 hint 取品牌图：OpenAI / Qwen / Doubao / MiMo / Step；
 * sherpa(本地) 与 system(系统) 没有品牌，仍旧用种类图标。
 */
@Composable
private fun AsrProviderBadge(kind: AsrServiceKind, badgeSize: androidx.compose.ui.unit.Dp) {
    val cs = MaterialTheme.colorScheme
    val isDark = LocalSemanticColors.current.isDark
    val asset = remember(kind) { asrKindBrandAsset(kind) }
    Box(
        modifier = Modifier.size(badgeSize).background(withAlpha(cs.primary, 0.11), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (asset == null) {
            Icon(
                asrKindIcon(kind),
                contentDescription = null,
                modifier = Modifier.size(badgeSize * 0.5f),
                tint = cs.primary,
            )
        } else {
            coil.compose.AsyncImage(
                model = asset,
                contentDescription = null,
                colorFilter = if (isDark && BrandAssets.assetNeedsDarkInvert(asset)) {
                    androidx.compose.ui.graphics.ColorFilter.tint(cs.onSurface)
                } else {
                    null
                },
                modifier = Modifier.size(badgeSize * 0.56f),
            )
        }
    }
}

/**
 * `_kindBrandAsset` L484-495 —— 每种服务的品牌图标 hint（照抄上游的对应关系）。
 * 返回 null 表示这种服务没有品牌图标（本地/系统），调用方回落种类图标。
 */
private fun asrKindBrandAsset(kind: AsrServiceKind): String? {
    val hint = when (kind) {
        AsrServiceKind.openAiRealtime -> "OpenAI"
        AsrServiceKind.dashScope -> "Qwen"
        AsrServiceKind.qwenAudio -> "Qwen"
        AsrServiceKind.volcengine -> "Doubao"
        AsrServiceKind.mimo -> "MiMo"
        AsrServiceKind.step -> "Step"
        AsrServiceKind.sherpaOnnx, AsrServiceKind.system -> ""
    }
    return if (hint.isEmpty()) null else BrandAssets.assetForName(hint)
}

/** _showAsrEditor (L524-565) + _AsrEditor (L589-1178) moved to
 * AsrServicesEditorScreen.kt — 1:1 fidelity fix; the original Flutter page
 * is a full Scaffold (pushed via Navigator.push), not a bottom sheet. The
 * form body is the same, only the container is MemoTopBar + scrollable
 * Column instead of ModalBottomSheet.
 */
// —— asr_services_section.dart top-level helpers L1975-2223 ——

private fun asrKindIcon(kind: AsrServiceKind): ImageVector = when (kind) {
    AsrServiceKind.system -> Lucide.Mic
    AsrServiceKind.sherpaOnnx -> Lucide.HardDrive
    AsrServiceKind.openAiRealtime -> Lucide.AudioWaveform
    AsrServiceKind.dashScope -> Lucide.Network
    AsrServiceKind.qwenAudio -> Lucide.Network
    AsrServiceKind.volcengine -> Lucide.AudioWaveform
    AsrServiceKind.mimo -> Lucide.Globe
    AsrServiceKind.step -> Lucide.AudioWaveform
}

/** Resource id per kind; null where the Dart source hardcodes the label. */
private fun asrKindTitleRes(kind: AsrServiceKind): Int? = when (kind) {
    AsrServiceKind.system -> UiR.string.asr_services_system_title
    AsrServiceKind.sherpaOnnx -> UiR.string.asr_services_local_title
    AsrServiceKind.openAiRealtime -> UiR.string.asr_services_open_ai_title
    AsrServiceKind.dashScope -> UiR.string.asr_services_dash_scope_title
    AsrServiceKind.qwenAudio -> null // "Qwen Audio" (Dart L2081)
    AsrServiceKind.volcengine -> UiR.string.asr_services_volcengine_title
    AsrServiceKind.mimo -> UiR.string.asr_services_mimo_title
    AsrServiceKind.step -> UiR.string.asr_services_step_title
}

/** Composable variant (cards / chips / hints).
 *  `internal` so AsrServicesEditorScreen.kt can render the kind chip labels
 *  with the same localized text as the section list. */
@Composable
internal fun asrKindTitle(kind: AsrServiceKind): String =
    asrKindTitleRes(kind)?.let { stringResource(it) } ?: "Qwen Audio"

/** Non-composable variant (submit fallback name — Dart L779-781).
 *  `internal` so AsrServicesEditorScreen.kt can compute the saved-name
 *  fallback in the submit() lambda. */
internal fun asrKindTitleText(context: android.content.Context, kind: AsrServiceKind): String =
    asrKindTitleRes(kind)?.let { context.getString(it) } ?: "Qwen Audio"

@Composable
private fun asrKindSubtitle(kind: AsrServiceKind): String = when (kind) {
    AsrServiceKind.system -> stringResource(UiR.string.asr_services_system_subtitle)
    AsrServiceKind.sherpaOnnx -> stringResource(UiR.string.asr_services_local_subtitle)
    AsrServiceKind.openAiRealtime -> stringResource(UiR.string.asr_services_open_ai_subtitle)
    AsrServiceKind.dashScope -> stringResource(UiR.string.asr_services_dash_scope_subtitle)
    AsrServiceKind.qwenAudio -> "Qwen Audio 3.0 ASR (/api-ws/v1/inference)" // Dart L2102
    AsrServiceKind.volcengine -> stringResource(UiR.string.asr_services_volcengine_subtitle)
    AsrServiceKind.mimo -> stringResource(UiR.string.asr_services_mimo_subtitle)
    AsrServiceKind.step -> stringResource(UiR.string.asr_services_step_subtitle)
}

// asr_services_section.dart L2112-2165 — per-kind field extractors.
// `internal` so AsrServicesEditorScreen.kt (same package) can hydrate the
// form state.
internal fun asrApiKeyOf(o: AsrServiceOptions?): String = when (o) {
    is OpenAiRealtimeAsrOptions -> o.apiKey
    is DashScopeAsrOptions -> o.apiKey
    is QwenAudioAsrOptions -> o.apiKey
    is VolcengineAsrOptions -> o.apiKey
    is MimoAsrOptions -> o.apiKey
    is StepAsrOptions -> o.apiKey
    else -> ""
}

internal fun asrEndpointOf(o: AsrServiceOptions?): String = when (o) {
    is OpenAiRealtimeAsrOptions -> o.websocketUrl
    is DashScopeAsrOptions -> o.websocketUrl
    is QwenAudioAsrOptions -> o.workspaceId
    is VolcengineAsrOptions -> o.websocketUrl
    is MimoAsrOptions -> o.baseUrl
    is StepAsrOptions -> o.baseUrl
    else -> ""
}

internal fun asrModelOf(o: AsrServiceOptions?): String = when (o) {
    is OpenAiRealtimeAsrOptions -> o.model
    is DashScopeAsrOptions -> o.model
    is QwenAudioAsrOptions -> o.model
    is MimoAsrOptions -> o.model
    is StepAsrOptions -> o.model
    else -> ""
}

internal fun asrResourceIdOf(o: AsrServiceOptions?): String = when (o) {
    is VolcengineAsrOptions -> o.resourceId
    else -> ""
}

internal fun asrLanguageOf(o: AsrServiceOptions?): String = when (o) {
    is SherpaOnnxAsrOptions -> o.language
    is SystemAsrOptions -> o.localeId
    is OpenAiRealtimeAsrOptions -> o.language
    is DashScopeAsrOptions -> o.language
    is VolcengineAsrOptions -> o.language
    is MimoAsrOptions -> o.language
    is StepAsrOptions -> o.language
    else -> ""
}

// asr_services_section.dart L2167-2213 — kind defaults.
internal fun asrDefaultEndpoint(kind: AsrServiceKind): String = when (kind) {
    AsrServiceKind.openAiRealtime -> "wss://api.openai.com/v1/realtime?intent=transcription"
    AsrServiceKind.dashScope -> "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"
    AsrServiceKind.qwenAudio -> ""
    AsrServiceKind.volcengine -> "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel"
    AsrServiceKind.mimo -> "https://api.xiaomimimo.com/v1"
    AsrServiceKind.step -> "https://api.stepfun.com"
    AsrServiceKind.sherpaOnnx, AsrServiceKind.system -> ""
}

internal fun asrDefaultModel(kind: AsrServiceKind): String = when (kind) {
    AsrServiceKind.openAiRealtime -> "gpt-live-transcribe"
    AsrServiceKind.dashScope -> "qwen3-asr-flash-realtime"
    AsrServiceKind.qwenAudio -> "qwen-audio-3.0-asr-flash-streaming"
    AsrServiceKind.volcengine -> ""
    AsrServiceKind.mimo -> "mimo-v2.5-asr"
    AsrServiceKind.step -> "stepaudio-2.5-asr"
    AsrServiceKind.sherpaOnnx, AsrServiceKind.system -> ""
}

internal fun asrDefaultResourceId(kind: AsrServiceKind): String =
    if (kind == AsrServiceKind.volcengine) VolcengineAsrOptions.SEED_ASR_DURATION_RESOURCE_ID else ""

// asr_services_section.dart L1996-2001 — display name (composable: falls back
// to the localized kind title).
@Composable
private fun asrServiceDisplayName(service: AsrServiceOptions): String {
    val name = service.name.trim()
    return if (name.isEmpty() || asrIsDefaultServiceName(service.kind, name)) asrKindTitle(service.kind) else name
}

internal fun asrEditableServiceName(service: AsrServiceOptions?): String {
    if (service == null) return ""
    val name = service.name.trim()
    return if (asrIsDefaultServiceName(service.kind, name)) "" else name
}

internal fun asrIsDefaultServiceName(kind: AsrServiceKind, name: String): Boolean = when (kind) {
    AsrServiceKind.sherpaOnnx -> name in setOf(
        "Sherpa-ONNX", "Offline Model", "本地离线模型", "本機離線模型", "本地模型", "本機模型",
    )
    AsrServiceKind.system -> name in setOf(
        "System speech recognition", "System Recognition", "System", "系统语音识别", "系統語音辨識", "系统", "系統",
    )
    AsrServiceKind.openAiRealtime -> name in setOf("OpenAI Realtime ASR", "OpenAI Realtime")
    AsrServiceKind.dashScope -> name in setOf(
        "DashScope ASR", "DashScope Realtime", "DashScope 实时识别", "DashScope 即時辨識", "DashScope",
    )
    AsrServiceKind.qwenAudio -> name in setOf("Qwen Audio ASR", "Qwen Audio")
    AsrServiceKind.volcengine -> name in setOf(
        "Volcengine ASR", "Volcengine Speech Recognition", "Volcengine", "火山引擎语音识别", "火山引擎語音辨識", "火山引擎",
    )
    AsrServiceKind.mimo -> name in setOf(
        "MiMo ASR", "MiMo Speech Recognition", "MiMo 语音识别", "MiMo 語音辨識", "MiMo",
    )
    AsrServiceKind.step -> name in setOf(
        "Step ASR", "Step Speech Recognition", "Step", "阶跃星辰语音识别", "階躍星辰語音辨識", "阶跃星辰", "階躍星辰",
    )
}

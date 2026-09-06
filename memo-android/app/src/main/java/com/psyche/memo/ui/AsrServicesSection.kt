package com.psyche.memo.ui

import android.speech.SpeechRecognizer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
fun AsrServicesSection(container: AppContainerImpl) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    val store = remember { AsrServicesStore(container.preferenceRepository) }

    LaunchedEffect(Unit) { store.load() }

    var editorInitial by remember { mutableStateOf<AsrServiceOptions?>(null) }
    var editorOpen by remember { mutableStateOf(false) }

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
                        RoundedCornerShape(8.dp),
                    )
                    .clickable(interactionSource = addInteraction, indication = null) {
                        Haptics.light(view)
                        editorInitial = null
                        editorOpen = true
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.Plus, contentDescription = addTooltip, modifier = Modifier.size(18.dp), tint = cs.onSurface)
            }
        }

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
                services.forEachIndexed { index, service ->
                    AsrServiceCard(
                        service = service,
                        selected = store.selectedServiceId == service.id,
                        onSelect = { store.selectedServiceId = service.id },
                        onEdit = { editorInitial = service; editorOpen = true },
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

    if (editorOpen) {
        AsrEditorSheet(
            store = store,
            initial = editorInitial,
            onDismiss = { editorOpen = false },
        )
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

/** _ProviderBadge L444-482 (icon variant — brand assets are a later batch). */
@Composable
private fun AsrProviderBadge(kind: AsrServiceKind, badgeSize: androidx.compose.ui.unit.Dp) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.size(badgeSize).background(withAlpha(cs.primary, 0.11), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(asrKindIcon(kind), contentDescription = null, modifier = Modifier.size(badgeSize * 0.5f), tint = cs.primary)
    }
}

/** _showAsrEditor mobile (L524-565) as a sheet; _AsrEditor L589-1178. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AsrEditorSheet(
    store: AsrServicesStore,
    initial: AsrServiceOptions?,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val view = LocalView.current

    var kind by remember(initial) { mutableStateOf(initial?.kind ?: AsrServiceKind.system) }
    var name by remember(initial) { mutableStateOf(asrEditableServiceName(initial)) }
    var apiKey by remember(initial) { mutableStateOf(asrApiKeyOf(initial)) }
    var endpoint by remember(initial) { mutableStateOf(asrEndpointOf(initial)) }
    var model by remember(initial) { mutableStateOf(asrModelOf(initial)) }
    var resourceId by remember(initial) { mutableStateOf(asrResourceIdOf(initial)) }
    var language by remember(initial) { mutableStateOf(asrLanguageOf(initial)) }
    var apiKeyError by remember(initial) { mutableStateOf(false) }
    var systemAvailable by remember(initial) { mutableStateOf<Boolean?>(null) }
    var checkingSystem by remember(initial) { mutableStateOf(false) }

    val checkSystem: () -> Unit = {
        if (!checkingSystem) {
            checkingSystem = true
            systemAvailable = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
            checkingSystem = false
        }
    }

    // initState L621-623: system kind auto-checks on open.
    LaunchedEffect(kind) { if (kind == AsrServiceKind.system) checkSystem() }

    // _selectKind L666-683 — switching kinds clears fields and re-seeds defaults.
    fun selectKind(k: AsrServiceKind) {
        if (k == kind) return
        Haptics.light(view)
        kind = k
        apiKeyError = false
        name = ""
        apiKey = ""
        endpoint = asrDefaultEndpoint(k)
        model = asrDefaultModel(k)
        resourceId = asrDefaultResourceId(k)
        language = if (k == AsrServiceKind.mimo || k == AsrServiceKind.step) "auto" else ""
    }

    // _canSubmit L756-763 (sherpa needs the model manager — later batch).
    val canSubmit = when (kind) {
        AsrServiceKind.system -> systemAvailable == true
        else -> apiKey.trim().isNotEmpty()
    }

    fun submit() {
        // _submit L765-927.
        if (kind != AsrServiceKind.system && apiKey.trim().isEmpty()) {
            apiKeyError = true
            return
        }
        if (!canSubmit) return
        val nm = name.trim().ifEmpty { asrKindTitleText(context, kind) }
        val lang = language.trim()
        val id = initial?.id ?: java.util.UUID.randomUUID().toString()
        fun valueOrDefault(v: String, fallback: String) = v.trim().ifEmpty { fallback }
        val created: AsrServiceOptions = when (kind) {
            AsrServiceKind.system -> SystemAsrOptions(id = id, name = nm, localeId = lang)
            AsrServiceKind.openAiRealtime -> {
                val prev = initial as? OpenAiRealtimeAsrOptions
                OpenAiRealtimeAsrOptions(
                    id = id, name = nm, apiKey = apiKey.trim(),
                    websocketUrl = valueOrDefault(endpoint, asrDefaultEndpoint(kind)),
                    model = valueOrDefault(model, asrDefaultModel(kind)),
                    language = lang,
                    prompt = prev?.prompt ?: "",
                    sampleRate = prev?.sampleRate ?: 24000,
                    vadThreshold = prev?.vadThreshold ?: 0.0,
                    prefixPaddingMs = prev?.prefixPaddingMs ?: 300,
                    silenceDurationMs = prev?.silenceDurationMs ?: 500,
                )
            }
            AsrServiceKind.dashScope -> {
                val prev = initial as? DashScopeAsrOptions
                DashScopeAsrOptions(
                    id = id, name = nm, apiKey = apiKey.trim(),
                    websocketUrl = valueOrDefault(endpoint, asrDefaultEndpoint(kind)),
                    model = valueOrDefault(model, asrDefaultModel(kind)),
                    language = lang,
                    sampleRate = prev?.sampleRate ?: 16000,
                    vadThreshold = prev?.vadThreshold ?: 0.0,
                    silenceDurationMs = prev?.silenceDurationMs ?: 800,
                )
            }
            AsrServiceKind.qwenAudio -> {
                val prev = initial as? QwenAudioAsrOptions
                QwenAudioAsrOptions(
                    id = id, name = nm, apiKey = apiKey.trim(),
                    workspaceId = endpoint.trim(),
                    region = prev?.region ?: "cn-beijing",
                    model = valueOrDefault(model, asrDefaultModel(kind)),
                    sampleRate = prev?.sampleRate ?: 16000,
                    format = prev?.format ?: "pcm",
                )
            }
            AsrServiceKind.volcengine -> VolcengineAsrOptions(
                id = id, name = nm, apiKey = apiKey.trim(),
                websocketUrl = valueOrDefault(endpoint, asrDefaultEndpoint(kind)),
                resourceId = valueOrDefault(resourceId, asrDefaultResourceId(kind)),
                language = lang,
            )
            AsrServiceKind.mimo -> {
                val prev = initial as? MimoAsrOptions
                MimoAsrOptions(
                    id = id, name = nm, apiKey = apiKey.trim(),
                    baseUrl = valueOrDefault(endpoint, asrDefaultEndpoint(kind)),
                    model = valueOrDefault(model, asrDefaultModel(kind)),
                    language = lang.ifEmpty { "auto" },
                    sampleRate = prev?.sampleRate ?: 16000,
                    segmentDurationSec = prev?.segmentDurationSec ?: 30,
                )
            }
            AsrServiceKind.step -> {
                val prev = initial as? StepAsrOptions
                StepAsrOptions(
                    id = id, name = nm, apiKey = apiKey.trim(),
                    baseUrl = valueOrDefault(endpoint, asrDefaultEndpoint(kind)),
                    model = valueOrDefault(model, asrDefaultModel(kind)),
                    language = lang.ifEmpty { "auto" },
                    sampleRate = prev?.sampleRate ?: 16000,
                    segmentDurationSec = prev?.segmentDurationSec ?: 30,
                    enableItn = prev?.enableItn ?: true,
                    enableTimestamp = prev?.enableTimestamp ?: false,
                    hotwords = prev?.hotwords ?: emptyList(),
                )
            }
            // Needs SherpaModelManager (download/install) — later batch.
            AsrServiceKind.sherpaOnnx -> return
        }
        // _addService L60-81 / _editService L83-104 persistence semantics.
        if (initial == null) store.add(created) else store.upsert(created)
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
        ) {
            // _EditorSectionHeader + _ProviderChoiceGrid (mobile L1034-1052).
            Text(
                stringResource(UiR.string.tts_services_dialog_provider_type),
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.8)),
                modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
            )
            SectionCard {
                Column(Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 12.dp)) {
                    // On-device group (sherpa_onnx pending model-manager batch).
                    Text(
                        stringResource(UiR.string.asr_services_on_device_group),
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.66)),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AsrProviderChoice(kind = AsrServiceKind.system, selected = kind == AsrServiceKind.system, onTap = { selectKind(AsrServiceKind.system) })
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(UiR.string.asr_services_cloud_group),
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.66)),
                    )
                    Spacer(Modifier.height(8.dp))
                    AsrKindChipGrid(selected = kind, onSelected = ::selectKind)
                }
            }

            Text(
                stringResource(UiR.string.asr_services_section_title),
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.8)),
                modifier = Modifier.padding(start = 12.dp, top = 18.dp, bottom = 6.dp),
            )
            SectionCard {
                Column(Modifier.fillMaxWidth()) {
                    // _configurationWidgets L929-1012.
                    AsrEditorField(
                        label = stringResource(UiR.string.asr_services_name_label),
                        value = name,
                        onValueChange = { name = it },
                        hint = asrKindTitle(kind),
                    )
                    when (kind) {
                        AsrServiceKind.system -> {
                            AsrSystemStatusRow(
                                available = systemAvailable,
                                checking = checkingSystem,
                                onCheck = { checkSystem() },
                            )
                            AsrEditorField(
                                label = stringResource(UiR.string.asr_services_language_label),
                                value = language,
                                onValueChange = { language = it },
                                hint = stringResource(UiR.string.asr_services_automatic_label),
                            )
                        }
                        else -> {
                            AsrEditorField(
                                label = stringResource(UiR.string.asr_services_api_key_label),
                                value = apiKey,
                                onValueChange = {
                                    apiKey = it
                                    if (apiKeyError && it.trim().isNotEmpty()) apiKeyError = false
                                },
                                obscure = true,
                                errorText = if (apiKeyError) stringResource(UiR.string.asr_services_api_key_required) else null,
                            )
                            AsrEditorField(
                                label = stringResource(UiR.string.asr_services_endpoint_label),
                                value = endpoint,
                                onValueChange = { endpoint = it },
                                hint = asrDefaultEndpoint(kind),
                            )
                            if (kind == AsrServiceKind.volcengine) {
                                AsrEditorField(
                                    label = stringResource(UiR.string.asr_services_resource_id_label),
                                    value = resourceId,
                                    onValueChange = { resourceId = it },
                                    hint = asrDefaultResourceId(kind),
                                )
                            } else {
                                AsrEditorField(
                                    label = stringResource(UiR.string.asr_services_model_label),
                                    value = model,
                                    onValueChange = { model = it },
                                    hint = asrDefaultModel(kind),
                                )
                            }
                            AsrEditorField(
                                label = stringResource(UiR.string.asr_services_language_label),
                                value = language,
                                onValueChange = { language = it },
                                hint = stringResource(UiR.string.asr_services_automatic_label),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            MemorySheetActions(
                onCancel = onDismiss,
                onConfirm = { submit() },
                confirmLabel = stringResource(
                    if (initial == null) UiR.string.asr_services_add_action else UiR.string.asr_services_save_action,
                ),
                confirmEnabled = canSubmit,
            )
        }
    }
}

/** _ProviderChoiceGrid L1203-1257 — cloud kinds in a wrapping grid. */
@Composable
private fun AsrKindChipGrid(selected: AsrServiceKind, onSelected: (AsrServiceKind) -> Unit) {
    val cloudKinds = listOf(
        AsrServiceKind.openAiRealtime,
        AsrServiceKind.dashScope,
        AsrServiceKind.qwenAudio,
        AsrServiceKind.volcengine,
        AsrServiceKind.mimo,
        AsrServiceKind.step,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cloudKinds.chunked(3).forEach { rowKinds ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowKinds.forEach { k ->
                    AsrProviderChoice(kind = k, selected = k == selected, onTap = { onSelected(k) })
                }
            }
        }
    }
}

/** _ProviderChoice L1259-1308. */
@Composable
private fun AsrProviderChoice(kind: AsrServiceKind, selected: Boolean, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val base = if (selected) withAlpha(cs.primary, 0.13) else withAlpha(cs.onSurface, 0.06)
    Text(
        text = asrKindTitle(kind),
        style = TextStyle(
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) cs.primary else cs.onSurface,
        ),
        modifier = Modifier
            .background(
                if (pressed) withAlpha(cs.onSurface, 0.06).compositeOver(base) else base,
                RoundedCornerShape(12.dp),
            )
            .border(0.8.dp, if (selected) withAlpha(cs.primary, 0.5) else withAlpha(cs.outlineVariant, 0.22), RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

/** _SystemConfiguration L1328-1446 — status pill + tap-to-recheck. */
@Composable
private fun AsrSystemStatusRow(available: Boolean?, checking: Boolean, onCheck: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current
    val statusText = when {
        checking -> stringResource(UiR.string.asr_services_system_checking)
        available == true -> stringResource(UiR.string.asr_services_system_available)
        available == false -> stringResource(UiR.string.asr_services_system_check_failed)
        else -> stringResource(UiR.string.asr_services_system_subtitle)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .background(
                if (available == false) withAlpha(cs.error, if (app.isDark) 0.10 else 0.06) else app.surfaceFill,
                RoundedCornerShape(12.dp),
            )
            .clickable(enabled = !checking, onClick = onCheck)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (available == true) Lucide.Check else Lucide.Mic,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = when {
                available == false -> cs.error
                available == true -> cs.primary
                else -> withAlpha(cs.onSurface, 0.58)
            },
        )
        Spacer(Modifier.width(10.dp))
        Text(
            statusText,
            style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = if (available == false) cs.error else withAlpha(cs.onSurface, 0.7)),
            modifier = Modifier.weight(1f),
        )
    }
}

/** _EditorField mobile L1800-1931 (label above filled field, hint in gap). */
@Composable
private fun AsrEditorField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String = "",
    obscure: Boolean = false,
    errorText: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(
            label,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.72)),
        )
        Spacer(Modifier.height(7.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            visualTransformation = if (obscure) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = TextStyle(fontSize = 15.sp, color = withAlpha(cs.onSurface, 0.92)),
            cursorBrush = SolidColor(cs.primary),
            modifier = Modifier
                .fillMaxWidth()
                .background(app.surfaceFill, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && hint.isNotEmpty()) {
                        Text(hint, style = TextStyle(fontSize = 15.sp, color = withAlpha(cs.onSurface, 0.38)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
            },
        )
        if (errorText != null) {
            Spacer(Modifier.height(4.dp))
            Text(errorText, style = TextStyle(fontSize = 12.sp, color = cs.error))
        }
    }
}

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

/** Composable variant (cards / chips / hints). */
@Composable
private fun asrKindTitle(kind: AsrServiceKind): String =
    asrKindTitleRes(kind)?.let { stringResource(it) } ?: "Qwen Audio"

/** Non-composable variant (submit fallback name — Dart L779-781). */
private fun asrKindTitleText(context: android.content.Context, kind: AsrServiceKind): String =
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
private fun asrApiKeyOf(o: AsrServiceOptions?): String = when (o) {
    is OpenAiRealtimeAsrOptions -> o.apiKey
    is DashScopeAsrOptions -> o.apiKey
    is QwenAudioAsrOptions -> o.apiKey
    is VolcengineAsrOptions -> o.apiKey
    is MimoAsrOptions -> o.apiKey
    is StepAsrOptions -> o.apiKey
    else -> ""
}

private fun asrEndpointOf(o: AsrServiceOptions?): String = when (o) {
    is OpenAiRealtimeAsrOptions -> o.websocketUrl
    is DashScopeAsrOptions -> o.websocketUrl
    is QwenAudioAsrOptions -> o.workspaceId
    is VolcengineAsrOptions -> o.websocketUrl
    is MimoAsrOptions -> o.baseUrl
    is StepAsrOptions -> o.baseUrl
    else -> ""
}

private fun asrModelOf(o: AsrServiceOptions?): String = when (o) {
    is OpenAiRealtimeAsrOptions -> o.model
    is DashScopeAsrOptions -> o.model
    is QwenAudioAsrOptions -> o.model
    is MimoAsrOptions -> o.model
    is StepAsrOptions -> o.model
    else -> ""
}

private fun asrResourceIdOf(o: AsrServiceOptions?): String = when (o) {
    is VolcengineAsrOptions -> o.resourceId
    else -> ""
}

private fun asrLanguageOf(o: AsrServiceOptions?): String = when (o) {
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

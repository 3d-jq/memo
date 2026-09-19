package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.Haptics
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.withAlpha
import java.util.UUID

/**
 * Port of `asr_services_section.dart` `_showAsrEditor` (L524-565) +
 * `_AsrEditor` (L589-1178) as a full screen, matching the original
 * `Navigator.push(MaterialPageRoute(...))` entry. The previous Android port
 * rendered the same form inside a `ModalBottomSheet`; Flutter pushes a
 * `Scaffold` with a back arrow + "Add Speech Recognition" / "Edit Speech
 * Recognition" title, so this is a 1:1 fidelity fix.
 *
 *  - `serviceId == null` -> add mode (matches `_addService` L60-81)
 *  - `serviceId != null` -> edit mode (matches `_editService` L83-104)
 *  - On save: `store.upsert(created)` / `store.add(created)` and `onBack()`.
 *
 * The shared `AsrServicesStore` lives on `AppContainer` so the list section
 * re-renders after the editor pops back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AsrServicesEditorScreen(
    container: AppContainerImpl,
    serviceId: String?,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val view = LocalView.current

    val store = container.asrServicesStore
    LaunchedEffect(Unit) { store.load() }

    val initial = remember(store.version, serviceId) {
        if (serviceId == null) null else store.services.firstOrNull { it.id == serviceId }
    }

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
        val id = initial?.id ?: UUID.randomUUID().toString()
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
        onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        MemoTopBar(
            title = stringResource(
                if (initial == null) UiR.string.asr_services_add_title
                else UiR.string.asr_services_edit_title,
            ),
            onBack = onBack,
        )
        // Scrollable form area (Flutter asr_services_section.dart mobile
        // L1025-1075: SafeArea > Form > Column > [Expanded(SingleChildScrollView),
        // Padding(IosTileButton)]).
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
        ) {
            Spacer(Modifier.height(12.dp))

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
                        AsrEditorProviderChoice(kind = AsrServiceKind.system, selected = kind == AsrServiceKind.system, onTap = { selectKind(AsrServiceKind.system) })
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(UiR.string.asr_services_cloud_group),
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.66)),
                    )
                    Spacer(Modifier.height(8.dp))
                    AsrEditorKindChipGrid(selected = kind, onSelected = ::selectKind)
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
                            AsrEditorSystemStatusRow(
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
        }
        // Fixed bottom primary "Add" / "Save" button — Flutter L1058-1071
        // (Padding(EdgeInsets.fromLTRB(16, 8, 16, 16)) > SizedBox(width:
        // double.infinity) > IosTileButton(icon: Check, primary tinted,
        // enabled: _canSubmit)). Cancel is handled by the back arrow in the
        // AppBar, so there is no Cancel pair here (that would be the sheet
        // variant, not the full-page variant).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
        ) {
            IosTileButton(
                label = stringResource(
                    if (initial == null) UiR.string.asr_services_add_action
                    else UiR.string.asr_services_save_action,
                ),
                icon = Lucide.Check,
                onClick = { submit() },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSubmit,
                backgroundColor = cs.primary,
                foregroundColor = cs.primary,
            )
        }
    }
}

// —— Editor-only helpers, lifted from AsrServicesSection.kt. Kept
// `internal` to avoid duplicating the small widget set across the two files. ——

/** _ProviderChoiceGrid L1203-1257 — cloud kinds in a wrapping grid. */
@Composable
internal fun AsrEditorKindChipGrid(selected: AsrServiceKind, onSelected: (AsrServiceKind) -> Unit) {
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
                    AsrEditorProviderChoice(kind = k, selected = k == selected, onTap = { onSelected(k) })
                }
            }
        }
    }
}

/** _ProviderChoice L1259-1308. */
@Composable
internal fun AsrEditorProviderChoice(kind: AsrServiceKind, selected: Boolean, onTap: () -> Unit) {
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
                RoundedCornerShape(MemoRadius.INNER_DP.dp),
            )
            .border(0.8.dp, if (selected) withAlpha(cs.primary, 0.5) else withAlpha(cs.outlineVariant, 0.22), RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

/** _SystemConfiguration L1328-1446 — status pill + tap-to-recheck. */
@Composable
internal fun AsrEditorSystemStatusRow(available: Boolean?, checking: Boolean, onCheck: () -> Unit) {
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
                RoundedCornerShape(MemoRadius.INNER_DP.dp),
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
internal fun AsrEditorField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String = "",
    obscure: Boolean = false,
    errorText: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current
    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)) {
        Text(
            label,
            style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.7)),
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    if (errorText != null) withAlpha(cs.error, if (app.isDark) 0.12 else 0.08) else app.surfaceFill,
                    RoundedCornerShape(MemoRadius.SMALL_DP.dp),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (value.isEmpty()) {
                Text(
                    hint,
                    style = TextStyle(fontSize = 14.sp, color = withAlpha(cs.onSurface, 0.4)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                )
            }
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                visualTransformation = if (obscure) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                textStyle = TextStyle(fontSize = 14.sp, color = cs.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(cs.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (errorText != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                errorText,
                style = TextStyle(fontSize = 11.sp, color = cs.error),
            )
        }
    }
}

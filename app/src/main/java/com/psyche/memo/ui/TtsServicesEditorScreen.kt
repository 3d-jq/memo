package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.withAlpha
import java.util.UUID

/**
 * Port of `tts_services_page.dart` `_NetworkTtsEditorPage` (L762-1860) as a
 * full screen, matching the original `Navigator.push(MaterialPageRoute(...))`
 * entry. The previous Android port rendered the same form inside a
 * `ModalBottomSheet`; Flutter pushes a `Scaffold` page with a back arrow, so
 * this is a 1:1 fidelity fix.
 *
 *  - `serviceId == null` -> add mode (matches `_showAddNetworkTtsSheet`)
 *  - `serviceId != null` -> edit mode (matches `_showEditNetworkTtsSheet`)
 *  - On save: `store.upsert(created)` and `onBack()`.
 *
 * Hydration extractors live in `TtsServicesScreen.kt` (kept `internal` so
 * `TtsServicesEditorHydrationTest` keeps its current import path) and are
 * shared with this file via the `com.psyche.memo.ui` package.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsServicesEditorScreen(
    container: AppContainerImpl,
    serviceId: String?,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current

    val store = container.ttsServicesStore
    LaunchedEffect(Unit) { store.load() }

    // Wait for the store to be populated (load() bumps store.version) before
    // deciding `initial`; otherwise an add/edit launch races the JSON read.
    val initial = remember(store.version, serviceId) {
        if (serviceId == null) null else store.services.firstOrNull { it.id == serviceId }
    }

    var kind by remember(initial) { mutableStateOf(initial?.kind ?: NetworkTtsKind.openai) }
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var apiKey by remember(initial) { mutableStateOf(initial?.apiKey ?: "") }
    var baseUrl by remember(initial) {
        mutableStateOf(initial?.let { baseUrlOf(it) } ?: TtsServicesStore.defaultBaseUrl(NetworkTtsKind.openai))
    }
    var model by remember(initial) {
        mutableStateOf(initial?.let { modelOf(it) } ?: TtsServicesStore.defaultModel(NetworkTtsKind.openai))
    }
    var voice by remember(initial) {
        mutableStateOf(initial?.let { voiceOf(it) } ?: TtsServicesStore.defaultVoice(NetworkTtsKind.openai))
    }
    // Kind-specific extras, re-hydrated from the existing options on edit.
    // extra1 = azure.language | minimax.emotion | qwenAudio.workspaceId |
    //          xai.language | elevenlabs.outputFormat | mimo.instruction |
    //          step.responseFormat | fishAudio.latency
    // extra2 = minimax.languageBoost | qwenAudio.region | step.instruction
    var extra1 by remember(initial) { mutableStateOf(extra1Of(initial)) }
    var extra2 by remember(initial) { mutableStateOf(extra2Of(initial)) }
    var languageType by remember(initial) { mutableStateOf(languageTypeOf(initial)) }
    var stream by remember(initial) { mutableStateOf(streamOf(initial)) }

    fun applyKindDefaults(k: NetworkTtsKind) {
        kind = k
        baseUrl = TtsServicesStore.defaultBaseUrl(k)
        model = TtsServicesStore.defaultModel(k)
        voice = TtsServicesStore.defaultVoice(k)
        extra1 = ""
        extra2 = ""
        languageType = "Auto"
        stream = true
    }

    fun submit() {
        val id = initial?.id ?: "tts-" + UUID.randomUUID().toString()
        val enabled = initial?.enabled ?: true
        val nm = name.trim().ifEmpty { kind.display + " TTS" }
        val created: TtsServiceOptions = when (kind) {
            NetworkTtsKind.openai -> OpenAiTtsOptions(id, enabled, nm, apiKey, baseUrl, model, voice)
            NetworkTtsKind.gemini -> GeminiTtsOptions(id, enabled, nm, apiKey, baseUrl, model, voice)
            NetworkTtsKind.azure -> AzureTtsOptions(id, enabled, nm, apiKey, baseUrl, extra1.ifEmpty { "zh-CN" }, voice)
            NetworkTtsKind.minimax -> MiniMaxTtsOptions(id, enabled, nm, apiKey, baseUrl, model, voice,
                emotion = extra1, speed = 1.0, volume = 1.0, pitch = 0, languageBoost = extra2,
                format = "mp3", sampleRate = 32000, bitrate = 128000, channel = 1,
                subtitleEnable = false, pronunciationDictionary = emptyList())
            NetworkTtsKind.qwen -> QwenTtsOptions(id, enabled, nm, apiKey, baseUrl, model, voice, languageType)
            NetworkTtsKind.qwenAudio -> QwenAudioTtsOptions(id, enabled, nm, apiKey, extra1, extra2.ifEmpty { "cn-beijing" }, model, voice, "mp3", 22050)
            NetworkTtsKind.groq -> GroqTtsOptions(id, enabled, nm, apiKey, baseUrl, model, voice)
            NetworkTtsKind.xai -> XaiTtsOptions(id, enabled, nm, apiKey, baseUrl, voice, extra1.ifEmpty { "auto" })
            NetworkTtsKind.elevenlabs -> ElevenLabsTtsOptions(id, enabled, nm, apiKey, baseUrl, model, voice, extra1.ifEmpty { "mp3_44100_128" })
            NetworkTtsKind.mimo -> MimoTtsOptions(id, enabled, nm, apiKey, baseUrl, model, voice, extra1, stream, false)
            NetworkTtsKind.step -> StepTtsOptions(id, enabled, nm, apiKey, baseUrl, model, voice,
                extra1.ifEmpty { "mp3" }, 1.0, 1.0, 24000, extra2)
            NetworkTtsKind.fishAudio -> FishAudioTtsOptions(id, enabled, nm, apiKey, baseUrl, model, voice,
                "mp3", 0.7, 0.7, 1.0, 44100, extra1.ifEmpty { "normal" })
        }
        if (initial == null) {
            store.upsert(created)
            // _handleAddNetworkTts L226-238: new service auto-selected when
            // the user is on the system TTS row.
            if (store.selectedServiceId == null) store.selectedServiceId = created.id
        } else {
            store.upsert(created)
        }
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
                if (initial == null) UiR.string.tts_services_dialog_add_title
                else UiR.string.tts_services_dialog_edit_title,
            ),
            onBack = onBack,
        )
        // Scrollable form area (Flutter _NetworkTtsEditorPage body: Form >
        // Column > [Expanded(SingleChildScrollView), Padding(IosTileButton)]).
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

            // _ProviderKindWrap L1628-1698 — kind chip wrap.
            Row(Modifier.fillMaxWidth()) {
                Column {
                    NetworkTtsKind.values().toList().chunked(3).forEach { rowKinds ->
                        Row {
                            rowKinds.forEach { k ->
                                val selected = kind == k
                                TactileRow(onTap = { applyKindDefaults(k) }, haptics = false) { pressed ->
                                    Text(
                                        k.display,
                                        style = TextStyle(
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (selected) cs.primary else withAlpha(cs.onSurface, 0.8),
                                        ),
                                        modifier = Modifier
                                            .background(
                                                if (selected) withAlpha(cs.primary, if (app.isDark) 0.22 else 0.12) else app.surfaceFill,
                                                RoundedCornerShape(MemoRadius.PILL_DP.dp),
                                            )
                                            .border(
                                                1.dp,
                                                if (selected) withAlpha(cs.primary, 0.38) else withAlpha(cs.outlineVariant, 0.14),
                                                RoundedCornerShape(MemoRadius.PILL_DP.dp),
                                            )
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            TtsEditorTextField(label = stringResource(UiR.string.tts_services_field_name_label), value = name, onValueChange = { name = it })
            TtsEditorTextField(
                label = stringResource(UiR.string.tts_services_field_api_key_label),
                value = apiKey,
                onValueChange = { apiKey = it },
                obscure = true,
            )
            if (kind != NetworkTtsKind.qwenAudio) {
                TtsEditorTextField(label = stringResource(UiR.string.tts_services_field_base_url_label), value = baseUrl, onValueChange = { baseUrl = it })
            } else {
                TtsEditorTextField(label = stringResource(UiR.string.tts_services_field_workspace_id_label), value = extra1, onValueChange = { extra1 = it })
                TtsEditorTextField(label = stringResource(UiR.string.tts_services_field_region_label), value = extra2, onValueChange = { extra2 = it })
            }
            if (model.isNotEmpty() || kind != NetworkTtsKind.azure) {
                TtsEditorTextField(
                    label = stringResource(UiR.string.tts_services_field_model_label),
                    value = model,
                    onValueChange = { model = it },
                )
            }
            val voiceLabel = voiceLabelFor(kind)
            TtsEditorTextField(label = stringResource(voiceLabel), value = voice, onValueChange = { voice = it })

            // Kind-specific extras (dense subset of the dart editor fields).
            when (kind) {
                NetworkTtsKind.azure -> TtsEditorTextField(label = stringResource(UiR.string.tts_services_field_language_label), value = extra1.ifEmpty { "zh-CN" }, onValueChange = { extra1 = it })
                NetworkTtsKind.qwen -> TtsEditorTextField(label = stringResource(UiR.string.tts_services_field_language_type_label), value = languageType, onValueChange = { languageType = it })
                NetworkTtsKind.xai -> TtsEditorTextField(label = stringResource(UiR.string.tts_services_field_language_label), value = extra1.ifEmpty { "auto" }, onValueChange = { extra1 = it })
                NetworkTtsKind.elevenlabs -> TtsEditorTextField(label = stringResource(UiR.string.tts_services_field_output_format_label), value = extra1.ifEmpty { "mp3_44100_128" }, onValueChange = { extra1 = it })
                NetworkTtsKind.mimo -> {
                    TtsEditorTextField(label = stringResource(UiR.string.tts_services_field_instruction_label), value = extra1, onValueChange = { extra1 = it })
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(UiR.string.tts_services_field_streaming_label),
                            style = TextStyle(fontSize = 15.sp, color = withAlpha(cs.onSurface, 0.9)),
                            modifier = Modifier.weight(1f),
                        )
                        IosSwitch(value = stream, onValueChanged = { stream = it })
                    }
                }
                NetworkTtsKind.step -> {
                    TtsEditorTextField(label = stringResource(UiR.string.tts_services_field_format_label), value = extra1.ifEmpty { "mp3" }, onValueChange = { extra1 = it })
                    TtsEditorTextField(label = stringResource(UiR.string.tts_services_field_instruction_label), value = extra2, onValueChange = { extra2 = it })
                }
                NetworkTtsKind.minimax -> {
                    TtsEditorTextField(label = stringResource(UiR.string.tts_services_field_emotion_label), value = extra1, onValueChange = { extra1 = it })
                    TtsEditorTextField(label = stringResource(UiR.string.tts_services_field_language_boost_label), value = extra2, onValueChange = { extra2 = it })
                }
                NetworkTtsKind.fishAudio -> TtsEditorTextField(label = stringResource(UiR.string.tts_services_field_latency_label), value = extra1.ifEmpty { "normal" }, onValueChange = { extra1 = it })
                else -> Unit
            }
        }
        // Fixed bottom primary "Add" / "Save" button — Flutter L1305-1319
        // (Padding(EdgeInsets.fromLTRB(16, 8, 16, 16)) > SizedBox(width:
        // double.infinity) > IosTileButton(icon: Check, primary tinted)).
        // Cancel is handled by the back arrow in the AppBar, so there is
        // no Cancel pair here (that would be the sheet variant, not the
        // full-page variant).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
        ) {
            IosTileButton(
                label = stringResource(
                    if (initial == null) UiR.string.tts_services_dialog_add_button
                    else UiR.string.tts_services_dialog_save_button,
                ),
                icon = Lucide.Check,
                onClick = { submit() },
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = cs.primary,
                foregroundColor = cs.primary,
            )
        }
    }
}

@Composable
internal fun TtsEditorTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    obscure: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(
            label,
            style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.7)),
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .background(app.surfaceFill, RoundedCornerShape(MemoRadius.SMALL_DP.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (value.isEmpty()) {
                Text(label, style = TextStyle(fontSize = 14.sp, color = withAlpha(cs.onSurface, 0.4)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                visualTransformation = if (obscure) androidx.compose.ui.text.input.PasswordVisualTransformation() else VisualTransformation.None,
                textStyle = TextStyle(fontSize = 14.sp, color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

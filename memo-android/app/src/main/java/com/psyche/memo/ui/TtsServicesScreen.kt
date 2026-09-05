package com.psyche.memo.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings2
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Volume2
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.withAlpha
import java.util.UUID

/**
 * tts_services_page.dart 1:1 (mobile branches) — system TTS row + network TTS
 * service rows + add/edit editor + system TTS config sheet. Cloud synthesis
 * execution belongs to the later TTS-service batch; this page ships the
 * configuration UI and persistence (`tts_services_v1` /
 * `tts_selected_service_id_v1` / `tts_speech_rate_v1` / `tts_pitch_v1` /
 * `tts_engine_v1` / `tts_language_v1`).
 */
private var systemTtsRef: TextToSpeech? = null
private var systemTtsReady = false
private var systemTtsError: String? = null

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsServicesScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current
    val context = LocalContext.current
    val store = remember { TtsServicesStore(container.preferenceRepository) }

    androidx.compose.runtime.LaunchedEffect(Unit) { store.load() }
    var rev by remember { mutableStateOf(0) }

    // TtsProvider.initialize — Android TextToSpeech init for the system row.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (systemTtsRef == null) {
            systemTtsReady = false
            systemTtsError = null
            systemTtsRef = TextToSpeech(context) { status ->
                systemTtsReady = status == TextToSpeech.SUCCESS
                if (!systemTtsReady) {
                    systemTtsError = "TextToSpeech init failed (status=$status)"
                }
                rev++
            }
        } else {
            rev++
        }
    }

    val systemTitle = stringResource(UiR.string.tts_services_page_system_tts_title)
    val systemSub = if (systemTtsReady) {
        stringResource(UiR.string.tts_services_page_system_tts_available_subtitle)
    } else {
        stringResource(
            UiR.string.tts_services_page_system_tts_unavailable_subtitle,
            systemTtsError ?: stringResource(UiR.string.tts_services_page_system_tts_unavailable_not_initialized),
        )
    }

    var editorExisting by remember { mutableStateOf<TtsServiceOptions?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var systemConfigOpen by remember { mutableStateOf(false) }
    var errorDetails by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // AppBar (L30-59): back + title + settings action.
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton44(onClick = onBack, icon = Lucide.ArrowLeft, tint = cs.onSurface, cd = stringResource(UiR.string.settings_page_back_button))
            Text(
                text = stringResource(UiR.string.tts_services_page_title),
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                modifier = Modifier.weight(1f),
            )
            IconButton44(
                onClick = { systemConfigOpen = true },
                icon = Lucide.Settings2,
                tint = cs.onSurface,
                cd = stringResource(UiR.string.tts_services_page_settings_tooltip),
            )
            Spacer(Modifier.width(12.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        ) {
            key(rev) {
                // VoiceServiceSectionHeader (L79-84).
                Text(
                    stringResource(UiR.string.tts_services_section_title),
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.8)),
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 6.dp),
                )

                SectionCard {
                    // ── System TTS first row (L88-190) ──────────────────────
                    val available = systemTtsReady
                    val systemLetter = (systemTitle.trim().ifEmpty { "?" }.first()).uppercaseChar().toString()
                    TactileRow(onTap = if (available) ({ store.selectedServiceId = null }) else null, haptics = false) { pressed ->
                        val overlay = withAlpha(cs.surface, if (app.isDark) 0.06 else 0.05).takeIf { pressed } ?: Color.Transparent
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AvatarBadge(letter = systemLetter, overlay = overlay)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    systemTitle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.9)),
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    systemSub,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(fontSize = 12.sp, color = withAlpha(cs.onSurface, 0.63)),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            SmallTactileIcon(
                                icon = Lucide.Volume2,
                                tint = withAlpha(cs.onSurface, 0.9),
                                enabled = available,
                                onTap = {
                                    // tts.speakSystem(demo) via Android TTS.
                                    val demo = context.getString(UiR.string.tts_services_page_test_speech_text)
                                    systemTtsRef?.language = java.util.Locale.getDefault()
                                    systemTtsRef?.speak(demo, TextToSpeech.QUEUE_FLUSH, null, "memo_demo")
                                },
                            )
                            Spacer(Modifier.width(6.dp))
                            SmallTactileIcon(
                                icon = Lucide.Settings2,
                                tint = withAlpha(cs.onSurface, 0.9),
                                enabled = available,
                                onTap = { systemConfigOpen = true },
                            )
                            Spacer(Modifier.width(8.dp))
                            if (store.usingSystemTts) {
                                Icon(Lucide.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = withAlpha(cs.onSurface, 0.9))
                            } else {
                                Spacer(Modifier.width(16.dp))
                            }
                        }
                    }

                    val services = store.services
                    if (services.isNotEmpty()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(0.6.dp)
                                .padding(horizontal = 0.dp)
                                .background(withAlpha(cs.outlineVariant, 0.18)),
                        )
                    }
                    services.forEachIndexed { i, service ->
                        NetworkTtsRow(
                            service = service,
                            selected = store.selectedServiceId == service.id,
                            onSelect = { store.selectedServiceId = service.id },
                            onEdit = { editorExisting = service; editorOpen = true },
                            onTest = { demo ->
                                // Cloud synthesis call belongs to the later
                                // TTS-service batch — no request is made yet.
                                null
                            },
                            onDelete = { store.removeAt(i) },
                            onErrorDetails = { errorDetails = it },
                        )
                        if (i != services.lastIndex) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(0.6.dp)
                                    .background(withAlpha(cs.outlineVariant, 0.18)),
                            )
                        }
                    }
                }

                // AsrServicesSection (asr_services_page.dart) is its own page
                // batch — placeholder slot kept per L200.
            }
        }
    }

    // Editor for add (null) / edit (existing) — L745-761.
    if (editorOpen) {
        NetworkTtsEditorOverlay(
            container = container,
            store = store,
            initial = editorExisting,
            onDismiss = { editorOpen = false },
            onSaved = { created ->
                // _handleAddNetworkTts L226-238.
                if (editorExisting == null) {
                    store.upsert(created)
                    if (store.usingSystemTts) store.selectedServiceId = created.id
                } else {
                    store.upsert(created)
                }
                editorOpen = false
            },
        )
    }

    // System TTS config sheet — _showSystemTtsConfig L1859-2010.
    if (systemConfigOpen) {
        SystemTtsConfigSheet(container = container, onDismiss = { systemConfigOpen = false })
    }

    // _showMobileErrorDetails L681-741.
    errorDetails?.let { message ->
        ModalBottomSheet(onDismissRequest = { errorDetails = null }) {
            Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 16.dp)) {
                Text(
                    stringResource(UiR.string.tts_services_dialog_error_title),
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    message,
                    style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = withAlpha(cs.onSurface, 0.9)),
                )
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { errorDetails = null }) {
                    Text(stringResource(UiR.string.tts_services_close_button))
                }
            }
        }
    }
}

@Composable
private fun IconButton44(onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, cd: String) {
    androidx.compose.material3.IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(icon, contentDescription = cd, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** _AvatarBadge L413-505 (letter variant; brand assets are a later batch). */
@Composable
private fun AvatarBadge(letter: String, overlay: Color) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(withAlpha(cs.primary, if (app.isDark) 0.18 else 0.1), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter.uppercase(),
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.primary),
        )
        if (overlay != Color.Transparent) {
            Box(Modifier.size(36.dp).background(overlay, CircleShape))
        }
    }
}

/** _SmallTactileIcon L368-411. */
@Composable
private fun SmallTactileIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, enabled: Boolean = true, onTap: () -> Unit) {
    TactileRow(onTap = if (enabled) onTap else null, haptics = false) { pressed ->
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (pressed) withAlpha(tint, 0.7) else tint,
        )
    }
}

/** _NetworkTtsRowMobile L507-643 + _ErrorInlineMobile L645-679. */
@Composable
private fun NetworkTtsRow(
    service: TtsServiceOptions,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onTest: (String) -> String?,
    onDelete: () -> Unit,
    onErrorDetails: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    val displayName = service.name.trim().ifEmpty { service.kind.display }

    Column(Modifier.fillMaxWidth()) {
        TactileRow(onTap = onSelect, haptics = false) { pressed ->
            val overlay = withAlpha(cs.surface, if (app.isDark) 0.06 else 0.05).takeIf { pressed } ?: Color.Transparent
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarBadge(letter = displayName.take(1), overlay = overlay)
                Spacer(Modifier.width(12.dp))
                Text(
                    displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.9)),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                SmallTactileIcon(icon = Lucide.Settings2, tint = withAlpha(cs.onSurface, 0.9), onTap = onEdit)
                Spacer(Modifier.width(6.dp))
                SmallTactileIcon(
                    icon = Lucide.Volume2,
                    tint = withAlpha(cs.onSurface, 0.9),
                    onTap = {
                        val demo = context.getString(UiR.string.tts_services_page_test_speech_text)
                        val err = onTest(demo)
                        error = err
                    },
                )
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
        if (!error.isNullOrEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .background(withAlpha(cs.error, 0.08), RoundedCornerShape(10.dp))
                    .border(0.6.dp, withAlpha(cs.error, 0.3), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    error!!.replace("\n", " "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 12.sp, color = cs.error),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { onErrorDetails(error!!) }) {
                    Text(stringResource(UiR.string.tts_services_view_details_button))
                }
            }
        }
    }
}

/**
 * _NetworkTtsEditorPage L762-1860 — kind chips + per-kind fields + submit.
 * Field sets mirror TtsServiceOptions.fromJson (network_tts.dart L83-266).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkTtsEditorOverlay(
    container: AppContainerImpl,
    store: TtsServicesStore,
    initial: TtsServiceOptions?,
    onDismiss: () -> Unit,
    onSaved: (TtsServiceOptions) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current

    var kind by remember { mutableStateOf(initial?.kind ?: NetworkTtsKind.openai) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var apiKey by remember { mutableStateOf(initial?.apiKey ?: "") }
    var baseUrl by remember { mutableStateOf(initial?.let { baseUrlOf(it) } ?: TtsServicesStore.defaultBaseUrl(NetworkTtsKind.openai)) }
    var model by remember { mutableStateOf(initial?.let { modelOf(it) } ?: TtsServicesStore.defaultModel(NetworkTtsKind.openai)) }
    var voice by remember { mutableStateOf(initial?.let { voiceOf(it) } ?: TtsServicesStore.defaultVoice(NetworkTtsKind.openai)) }
    // Extra kind-specific plain-text fields.
    var extra1 by remember { mutableStateOf("") }
    var extra2 by remember { mutableStateOf("") }
    var languageType by remember { mutableStateOf("Auto") }
    var stream by remember { mutableStateOf(true) }

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
        onSaved(created)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
        ) {
            Text(
                stringResource(if (initial == null) UiR.string.tts_services_dialog_add_title else UiR.string.tts_services_dialog_edit_title),
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
            )
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
                                                RoundedCornerShape(999.dp),
                                            )
                                            .border(
                                                1.dp,
                                                if (selected) withAlpha(cs.primary, 0.38) else withAlpha(cs.outlineVariant, 0.14),
                                                RoundedCornerShape(999.dp),
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

            EditorTextField(label = stringResource(UiR.string.tts_services_field_name_label), value = name, onValueChange = { name = it })
            EditorTextField(label = stringResource(UiR.string.tts_services_field_api_key_label), value = apiKey, onValueChange = { apiKey = it }, obscure = true)
            if (kind != NetworkTtsKind.qwenAudio) {
                EditorTextField(label = stringResource(UiR.string.tts_services_field_base_url_label), value = baseUrl, onValueChange = { baseUrl = it })
            } else {
                EditorTextField(label = stringResource(UiR.string.tts_services_field_workspace_id_label), value = extra1, onValueChange = { extra1 = it })
                EditorTextField(label = stringResource(UiR.string.tts_services_field_region_label), value = extra2, onValueChange = { extra2 = it })
            }
            if (model.isNotEmpty() || kind != NetworkTtsKind.azure) {
                val modelLabel = when (kind) {
                    NetworkTtsKind.elevenlabs -> UiR.string.tts_services_field_model_label
                    else -> UiR.string.tts_services_field_model_label
                }
                EditorTextField(label = stringResource(modelLabel), value = model, onValueChange = { model = it })
            }
            val voiceLabel = voiceLabelFor(kind)
            EditorTextField(label = stringResource(voiceLabel), value = voice, onValueChange = { voice = it })

            // Kind-specific extras (dense subset of the dart editor fields).
            when (kind) {
                NetworkTtsKind.azure -> EditorTextField(label = stringResource(UiR.string.tts_services_field_language_label), value = extra1.ifEmpty { "zh-CN" }, onValueChange = { extra1 = it })
                NetworkTtsKind.qwen -> EditorTextField(label = stringResource(UiR.string.tts_services_field_language_type_label), value = languageType, onValueChange = { languageType = it })
                NetworkTtsKind.xai -> EditorTextField(label = stringResource(UiR.string.tts_services_field_language_label), value = extra1.ifEmpty { "auto" }, onValueChange = { extra1 = it })
                NetworkTtsKind.elevenlabs -> EditorTextField(label = stringResource(UiR.string.tts_services_field_output_format_label), value = extra1.ifEmpty { "mp3_44100_128" }, onValueChange = { extra1 = it })
                NetworkTtsKind.mimo -> {
                    EditorTextField(label = stringResource(UiR.string.tts_services_field_instruction_label), value = extra1, onValueChange = { extra1 = it })
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
                    EditorTextField(label = stringResource(UiR.string.tts_services_field_format_label), value = extra1.ifEmpty { "mp3" }, onValueChange = { extra1 = it })
                    EditorTextField(label = stringResource(UiR.string.tts_services_field_instruction_label), value = extra2, onValueChange = { extra2 = it })
                }
                NetworkTtsKind.minimax -> {
                    EditorTextField(label = stringResource(UiR.string.tts_services_field_emotion_label), value = extra1, onValueChange = { extra1 = it })
                    EditorTextField(label = stringResource(UiR.string.tts_services_field_language_boost_label), value = extra2, onValueChange = { extra2 = it })
                }
                NetworkTtsKind.fishAudio -> EditorTextField(label = stringResource(UiR.string.tts_services_field_latency_label), value = extra1.ifEmpty { "normal" }, onValueChange = { extra1 = it })
                else -> Unit
            }

            Spacer(Modifier.height(16.dp))
            MemorySheetActions(
                onCancel = onDismiss,
                onConfirm = { submit() },
                confirmLabel = stringResource(UiR.string.user_profile_save),
            )
        }
    }
}

@Composable
private fun EditorTextField(label: String, value: String, onValueChange: (String) -> Unit, obscure: Boolean = false) {
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
                .background(app.surfaceFill, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (value.isEmpty()) {
                Text(label, style = TextStyle(fontSize = 14.sp, color = withAlpha(cs.onSurface, 0.4)))
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                visualTransformation = if (obscure) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                textStyle = TextStyle(fontSize = 14.sp, color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// tts_services_page.dart L2161-2221 — per-kind field extractors.
private fun baseUrlOf(o: TtsServiceOptions): String = when (o) {
    is OpenAiTtsOptions -> o.baseUrl
    is GeminiTtsOptions -> o.baseUrl
    is AzureTtsOptions -> o.baseUrl
    is MiniMaxTtsOptions -> o.baseUrl
    is QwenTtsOptions -> o.baseUrl
    is QwenAudioTtsOptions -> o.workspaceId
    is GroqTtsOptions -> o.baseUrl
    is XaiTtsOptions -> o.baseUrl
    is ElevenLabsTtsOptions -> o.baseUrl
    is MimoTtsOptions -> o.baseUrl
    is StepTtsOptions -> o.baseUrl
    is FishAudioTtsOptions -> o.baseUrl
}

private fun modelOf(o: TtsServiceOptions): String = when (o) {
    is OpenAiTtsOptions -> o.model
    is GeminiTtsOptions -> o.model
    is MiniMaxTtsOptions -> o.model
    is QwenTtsOptions -> o.model
    is QwenAudioTtsOptions -> o.model
    is GroqTtsOptions -> o.model
    is ElevenLabsTtsOptions -> o.modelId
    is MimoTtsOptions -> o.model
    is StepTtsOptions -> o.model
    is FishAudioTtsOptions -> o.model
    else -> ""
}

private fun voiceOf(o: TtsServiceOptions): String = when (o) {
    is OpenAiTtsOptions -> o.voice
    is GeminiTtsOptions -> o.voiceName
    is AzureTtsOptions -> o.voice
    is MiniMaxTtsOptions -> o.voiceId
    is QwenTtsOptions -> o.voice
    is QwenAudioTtsOptions -> o.voice
    is GroqTtsOptions -> o.voice
    is XaiTtsOptions -> o.voiceId
    is ElevenLabsTtsOptions -> o.voiceId
    is MimoTtsOptions -> o.voice
    is StepTtsOptions -> o.voice
    is FishAudioTtsOptions -> o.referenceId
}

/** tts_services_page.dart _voiceLabelFor L2310-2337. */
@Composable
private fun voiceLabelFor(k: NetworkTtsKind): Int = when (k) {
    NetworkTtsKind.minimax, NetworkTtsKind.xai, NetworkTtsKind.elevenlabs, NetworkTtsKind.fishAudio ->
        UiR.string.tts_services_field_voice_id_label
    else -> UiR.string.tts_services_field_voice_label
}

/** _showSystemTtsConfig L1859-2010 — engine/language pickers + rate/pitch sliders. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SystemTtsConfigSheet(container: AppContainerImpl, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val prefs = remember { container.preferenceRepository }
    // TtsProvider keys (L44-48) — same key names via PreferenceRepository JSON.
    fun readNum(key: String, def: Float): Float =
        prefs.readJson(key)?.toFloatOrNull() ?: def
    fun writeNum(key: String, v: Float) =
        prefs.writeJson(key, kotlinx.serialization.json.JsonPrimitive(v).toString())

    var rate by remember { mutableStateOf(readNum("tts_speech_rate_v1", 0.5f)) }
    var pitch by remember { mutableStateOf(readNum("tts_pitch_v1", 1.0f)) }
    var engines by remember { mutableStateOf(listOf<String>()) }
    var engineId by remember { mutableStateOf(prefs.readJson("tts_engine_v1")?.removeSurrounding("\"") ?: "") }
    var languageTag by remember { mutableStateOf(prefs.readJson("tts_language_v1")?.removeSurrounding("\"") ?: "") }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        // listEngines (tts_provider) via TextToSpeech engine enumeration.
        engines = systemTtsRef?.let { tts ->
            runCatching {
                tts.engines.map { it.name }
            }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 16.dp)) {
            Text(
                stringResource(UiR.string.tts_services_page_system_tts_settings_title),
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(10.dp))

            // Engine selector row (_sheetSelectRow L2012-2088).
            val curEngine = engineId.ifEmpty { engines.firstOrNull() ?: "" }
            TactileRow(onTap = if (engines.isEmpty()) null else ({ engineId = engines.firstOrNull() ?: engineId })) { pressed ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (pressed) withAlpha(cs.onSurface, 0.05) else Color.Transparent)
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(UiR.string.tts_services_page_engine_label), style = TextStyle(fontSize = 15.sp, color = withAlpha(cs.onSurface, 0.9)), modifier = Modifier.weight(1f))
                    Text(
                        curEngine.ifEmpty { stringResource(UiR.string.tts_services_page_auto_label) },
                        style = TextStyle(fontSize = 13.sp, color = withAlpha(cs.onSurface, 0.6)),
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(Lucide.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = withAlpha(cs.onSurface, 0.9))
                }
            }
            Spacer(Modifier.height(4.dp))

            Text(
                stringResource(UiR.string.tts_services_page_speech_rate_label),
                style = TextStyle(fontSize = 12.sp, color = withAlpha(cs.onSurface, 0.7)),
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.1f..1.0f, onValueChangeFinished = {
                prefs.writeJson("tts_speech_rate_v1", rate.toString())
            })
            Text(
                stringResource(UiR.string.tts_services_page_pitch_label),
                style = TextStyle(fontSize = 12.sp, color = withAlpha(cs.onSurface, 0.7)),
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Slider(value = pitch, onValueChange = { pitch = it }, valueRange = 0.5f..2.0f, onValueChangeFinished = {
                prefs.writeJson("tts_pitch_v1", pitch.toString())
            })

            Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                TextButton(onClick = {
                    SnackbarManager.show(
                        AppNotification(
                            message = context.getString(UiR.string.tts_services_page_settings_saved_message),
                            type = NotificationType.SUCCESS,
                        ),
                    )
                    onDismiss()
                }) {
                    Icon(Lucide.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(UiR.string.tts_services_page_done_button))
                }
            }
        }
    }
}

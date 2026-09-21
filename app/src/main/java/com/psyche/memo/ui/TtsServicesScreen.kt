package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import com.psyche.memo.ui.slider.MemoSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Settings2
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Volume2
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.Haptics
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
    onOpenSettings: () -> Unit,
    onOpenTtsEditor: (serviceId: String?) -> Unit,
    onOpenAsrEditor: (serviceId: String?) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current
    val context = LocalContext.current
    val store = container.ttsServicesStore

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

    var systemConfigOpen by remember { mutableStateOf(false) }
    var errorDetails by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // AppBar (L30-59): back + title + settings action.
        MemoTopBar(
            title = stringResource(UiR.string.tts_services_page_title),
            onBack = onBack,
        ) {
            // AppBar settings action (tts_services_page.dart L44-56): opens
            // TtsSettingsPage — NOT the system-TTS config sheet.
            IconButton44(
                onClick = onOpenSettings,
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
            // rev bumps on Android TextToSpeech init, store.version bumps on
            // any add/edit/delete from the editor page (shared store instance,
            // see AppContainer.ttsServicesStore). Key on both so the section
            // re-renders when either changes.
            key(rev + store.version) {
                // VoiceServiceSectionHeader (tts_services_page.dart L79-84 +
                // voice_service_widgets.dart L15-69): title + trailing "+" add
                // button (_handleAddNetworkTts opens the editor in add mode).
                Row(
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(UiR.string.tts_services_section_title),
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.8)),
                        modifier = Modifier.weight(1f),
                    )
                    val view = LocalView.current
                    val addTooltip = stringResource(UiR.string.tts_services_page_add_tooltip)
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
                                onOpenTtsEditor(null)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Lucide.Plus,
                            contentDescription = addTooltip,
                            modifier = Modifier.size(18.dp),
                            tint = cs.onSurface,
                        )
                    }
                }

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
                                    // 原版 `tts_services_page.dart:158` `tts.speakSystem(demo)`
                                    // —— 走与对话同一条播放管线，所以「听测试」也会出悬浮播放
                                    // 胶囊。此前这里直连裸 TextToSpeech，状态机不动 → 没胶囊。
                                    val demo = context.getString(UiR.string.tts_services_page_test_speech_text)
                                    com.psyche.memo.ui.chat.TtsPlayer.speakSystem(context, demo)
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
                            onEdit = { onOpenTtsEditor(service.id) },
                            onTest = { demo ->
                                // 原版 `tts_services_page.dart:158` 的
                                // `tts.speakWithNetworkService(...)`：用**这一行**的服务试播。
                                // 之前这里是空实现（「later batch」），点了什么都不发生。
                                com.psyche.memo.ui.chat.TtsPlayer.speakWithService(context, demo, service)
                                // 网络合成的失败会走播放器的 errorMessage（悬浮胶囊上显示），
                                // 不再由这里同步返回。
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

                // AsrServicesSection (tts_services_page.dart L200) — the
                // voice-recognition half of the services page.
                AsrServicesSection(
                    container = container,
                    onOpenAsrEditor = onOpenAsrEditor,
                )
            }
        }
    }

    // System TTS config sheet — _showSystemTtsConfig L1859-2010.
    if (systemConfigOpen) {
        SystemTtsConfigSheet(container = container, onDismiss = { systemConfigOpen = false })
    }

    // _showMobileErrorDetails L681-741.
    errorDetails?.let { message ->
        ModalBottomSheet(containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
sheetState = rememberMemoSheetState(), onDismissRequest = { errorDetails = null }, dragHandle = null) {
            Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 16.dp)) {
                MemoSheetHandle()
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

/**
 * `_AvatarBrandBadge` L444-505 —— provider 语音服务的徽章：**有品牌图标就画图标**
 *（`BrandAssets.assetForName(name)`，取不到时按名字首词再试一次，与上游同序），
 * 都没有才回落到首字母。深色模式下对「需要反色」的图标套 `onSurface` tint。
 *
 * 用户 2026-09-16「语音服务那部分人家是有对应的图标」：此前这里只画首字母 ——
 * 原实现自己写着「brand assets are a later batch」，那个批次一直没补上。
 */
@Composable
private fun AvatarBrandBadge(name: String, overlay: Color) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current
    val asset = remember(name) {
        BrandAssets.assetForName(name)
            ?: BrandAssets.assetForName(name.split(' ').first())
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(withAlpha(cs.primary, if (app.isDark) 0.18 else 0.1), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (asset == null) {
            Text(
                name.ifEmpty { "?" }.first().uppercaseChar().toString(),
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.primary),
            )
        } else {
            coil.compose.AsyncImage(
                model = asset,
                contentDescription = null,
                colorFilter = if (app.isDark && BrandAssets.assetNeedsDarkInvert(asset)) {
                    androidx.compose.ui.graphics.ColorFilter.tint(cs.onSurface)
                } else {
                    null
                },
                modifier = Modifier.size(20.dp),
            )
        }
        if (overlay != Color.Transparent) {
            Box(Modifier.size(36.dp).background(overlay, CircleShape))
        }
    }
}

/** _SmallTactileIcon L368-411 (shared with AsrServicesSection). */
@Composable
internal fun SmallTactileIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, enabled: Boolean = true, onTap: () -> Unit) {
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
                AvatarBrandBadge(name = displayName, overlay = overlay)
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
                    .background(withAlpha(cs.error, 0.08), RoundedCornerShape(MemoRadius.SMALL_DP.dp))
                    .border(0.6.dp, withAlpha(cs.error, 0.3), RoundedCornerShape(MemoRadius.SMALL_DP.dp))
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

// _NetworkTtsEditorPage L762-1860 moved to TtsServicesEditorScreen.kt —
// 1:1 fidelity fix; the original Flutter page is a full Scaffold (pushed
// via Navigator.push), not a bottom sheet. The editor form body is the same,
// only the container is MemoTopBar + scrollable Column instead of
// ModalBottomSheet. Hydration extractors (baseUrlOf / modelOf / voiceOf /
// extra1Of / extra2Of / languageTypeOf / streamOf) and the per-kind voice
// label lookup (voiceLabelFor) are kept here so the existing
// TtsServicesEditorHydrationTest import paths keep working.

// tts_services_page.dart L2161-2221 — per-kind field extractors.
// `internal` so TtsServicesEditorScreen.kt (same package) can hydrate the
// form state. Mirrored by TtsServicesEditorHydrationTest (extra1Of/extra2Of/
// languageTypeOf/streamOf are `internal` for the same reason).
internal fun baseUrlOf(o: TtsServiceOptions): String = when (o) {
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

internal fun modelOf(o: TtsServiceOptions): String = when (o) {
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

internal fun voiceOf(o: TtsServiceOptions): String = when (o) {
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
internal fun voiceLabelFor(k: NetworkTtsKind): Int = when (k) {
    NetworkTtsKind.minimax, NetworkTtsKind.xai, NetworkTtsKind.elevenlabs, NetworkTtsKind.fishAudio ->
        UiR.string.tts_services_field_voice_id_label
    else -> UiR.string.tts_services_field_voice_label
}

// —— Editor hydration extractors (_NetworkTtsEditorPageState.initState
// L810-922). Pure so the round-trip "edit -> save keeps values" is unit
// testable; the editor composables just seed their state from these. ——

internal fun extra1Of(initial: TtsServiceOptions?): String = when (initial) {
    is AzureTtsOptions -> initial.language
    is MiniMaxTtsOptions -> initial.emotion
    is QwenAudioTtsOptions -> initial.workspaceId
    is XaiTtsOptions -> initial.language
    is ElevenLabsTtsOptions -> initial.outputFormat
    is MimoTtsOptions -> initial.instruction
    is StepTtsOptions -> initial.responseFormat
    is FishAudioTtsOptions -> initial.latency
    else -> "" // null / openai / gemini / qwen / groq carry no extra1 field
}

internal fun extra2Of(initial: TtsServiceOptions?): String = when (initial) {
    is MiniMaxTtsOptions -> initial.languageBoost
    is QwenAudioTtsOptions -> initial.region
    is StepTtsOptions -> initial.instruction
    else -> ""
}

internal fun languageTypeOf(initial: TtsServiceOptions?): String =
    (initial as? QwenTtsOptions)?.languageType ?: "Auto"

internal fun streamOf(initial: TtsServiceOptions?): Boolean =
    (initial as? MimoTtsOptions)?.stream ?: true

/** _showSystemTtsConfig L1859-2010 — engine/language pickers + rate/pitch sliders. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SystemTtsConfigSheet(container: AppContainerImpl, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val store = remember { container.ttsServicesStore }

    // TtsProvider 的四个键（tts_provider.dart L44-47），读写都过 store。
    var config by remember { mutableStateOf(store.systemTtsConfig()) }
    var rate by remember { mutableStateOf(config.speechRate.toFloat()) }
    var pitch by remember { mutableStateOf(config.pitch.toFloat()) }
    var engines by remember { mutableStateOf(listOf<String>()) }
    var languages by remember { mutableStateOf(listOf<String>()) }

    // 原版 listEngines()/listLanguages() 问的是**正在播放的那个**引擎实例，所以走
    // TtsPlayer；引擎还没绑好时照 `_ensureBound` 每 120ms 再问一次（有界）。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.psyche.memo.ui.chat.TtsPlayer.prepareSystemEngine()
        var attempt = 0
        while (attempt < ENGINE_POLL_ATTEMPTS) {
            engines = withContext(Dispatchers.IO) { com.psyche.memo.ui.chat.TtsPlayer.listEngines() }
            languages = withContext(Dispatchers.IO) { com.psyche.memo.ui.chat.TtsPlayer.listLanguages() }
            if (engines.isNotEmpty() && languages.isNotEmpty()) break
            delay(120)
            attempt++
        }
    }

    /** 存偏好 + 立刻下发（原版 setSpeechRate/setPitch/setEngineId/setLanguageTag）。 */
    fun commit(next: com.psyche.memo.ui.chat.SystemTtsConfig) {
        store.setSystemTtsConfig(next)
        config = next
        com.psyche.memo.ui.chat.TtsPlayer.reloadSystemConfig()
    }

    ModalBottomSheet(containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
sheetState = rememberMemoSheetState(), onDismissRequest = onDismiss, dragHandle = null) {
        Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 16.dp)) {
            MemoSheetHandle()
            Text(
                stringResource(UiR.string.tts_services_page_system_tts_settings_title),
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(10.dp))

            // Engine selector row (_sheetSelectRow L2012-2088)。没点名时显示的是
            // `_selectEngine` 实际选中的那一个（优先 google），不是列表第一项。
            val autoLabel = stringResource(UiR.string.tts_services_page_auto_label)
            SheetSelectRow(
                label = stringResource(UiR.string.tts_services_page_engine_label),
                value = com.psyche.memo.ui.chat.preferredSystemEngine(engines, config.engineId)
                    ?.takeIf { it.isNotEmpty() } ?: autoLabel,
                options = engines,
                onPicked = { commit(config.copy(engineId = it)) },
            )
            Spacer(Modifier.height(4.dp))

            // Language selector row（L1922-1945）：cur 回落链 zh-CN → en-US → 第一个。
            val curLanguage = config.languageTag ?: when {
                languages.contains("zh-CN") -> "zh-CN"
                languages.contains("en-US") -> "en-US"
                else -> languages.firstOrNull() ?: ""
            }
            SheetSelectRow(
                label = stringResource(UiR.string.tts_services_page_language_label),
                value = curLanguage.ifEmpty { autoLabel },
                options = languages,
                onPicked = { commit(config.copy(languageTag = it)) },
            )
            Spacer(Modifier.height(8.dp))

            Text(
                stringResource(UiR.string.tts_services_page_speech_rate_label),
                style = TextStyle(fontSize = 12.sp, color = withAlpha(cs.onSurface, 0.7)),
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            MemoSlider(
                value = rate,
                onValueChange = { rate = it },
                valueRange = 0.1f..1.0f,
                valueLabel = { String.format(java.util.Locale.US, "%.2f", it) },
                onValueChangeFinished = {
                    commit(config.copy(speechRate = rate.toDouble().coerceIn(0.1, 1.0)))
                },
            )
            Text(
                stringResource(UiR.string.tts_services_page_pitch_label),
                style = TextStyle(fontSize = 12.sp, color = withAlpha(cs.onSurface, 0.7)),
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            MemoSlider(
                value = pitch,
                onValueChange = { pitch = it },
                valueRange = 0.5f..2.0f,
                valueLabel = { String.format(java.util.Locale.US, "%.2f", it) },
                onValueChangeFinished = {
                    commit(config.copy(pitch = pitch.toDouble().coerceIn(0.5, 2.0)))
                },
            )

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

/**
 * `_sheetSelectRow` L2012-2088：行右侧显示当前值，点开是一列选项。
 *
 * 选项面板用锚定下拉而不是**第二层** bottom sheet —— Compose 里 sheet 套 sheet 的
 * 手势/层级不可靠，而同一个工程里 `ApiPathField`（供应商端点三选一）已经用这个
 * 组件跑通了「列表选一个」的交互。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetSelectRow(
    label: String,
    value: String,
    options: List<String>,
    onPicked: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    Box {
        TactileRow(onTap = if (options.isEmpty()) null else ({ expanded = true })) { pressed ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (pressed) withAlpha(cs.onSurface, 0.05) else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = TextStyle(fontSize = 15.sp, color = withAlpha(cs.onSurface, 0.9)),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    value,
                    style = TextStyle(fontSize = 13.sp, color = withAlpha(cs.onSurface, 0.6)),
                    modifier = Modifier.padding(end = 6.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Lucide.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = withAlpha(cs.onSurface, 0.9))
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
            containerColor = LocalSemanticColors.current.surfaceCard,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
        ) {
            Column(
                Modifier
                    .widthIn(max = 320.dp)
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEach { option ->
                    val selected = option == value
                    DropdownMenuItem(
                        modifier = Modifier.background(
                            if (selected) cs.primary.copy(alpha = 0.08f) else Color.Transparent,
                        ),
                        text = {
                            Text(
                                option,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(
                                    fontSize = 14.sp,
                                    color = if (selected) cs.primary else cs.onSurface,
                                ),
                            )
                        },
                        onClick = {
                            expanded = false
                            onPicked(option)
                        },
                    )
                }
            }
        }
    }
}

/** `_ensureBound` 的轮询上限（120ms × 20 ≈ 2.4s）。 */
private const val ENGINE_POLL_ATTEMPTS = 20

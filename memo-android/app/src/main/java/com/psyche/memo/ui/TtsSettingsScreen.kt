package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.BadgeInfo
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ui.R as UiR
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 1:1 port of lib/features/settings/pages/tts_settings_page.dart.
 * Playback section (auto-play assistant replies / cache network audio for
 * replay) plus the text-selection section with its five TtsTextSelectionMode
 * rows. Keys mirror settings_provider.dart / tts_provider.dart:
 *   tts_auto_play_assistant_replies_v1 (bool)
 *   tts_cache_network_audio_for_replay_v1 (bool)
 *   tts_text_selection_mode_v1 (string: enum .name)
 */
@Composable
fun TtsSettingsScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme

    // PREFERENCE keys — preference_rows JSON (ThemeState convention).
    fun readBool(key: String, default: Boolean): Boolean = runCatching {
        container.preferenceRepository.readJson(key)
            ?.let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonPrimitive.booleanOrNull }
    }.getOrNull() ?: default
    fun writeBool(key: String, value: Boolean) {
        container.preferenceRepository.writeJson(key, value.toString())
    }

    var autoPlay by remember { mutableStateOf(readBool("tts_auto_play_assistant_replies_v1", false)) }
    // 「使用缓存复播」是 TtsProvider 自己的键（`tts_provider.dart` L48-49），播放器的
    // replay 读它决定要不要重新请求语音服务 —— 写也走同一处，别留下第二套解析。
    var cacheReplay by remember { mutableStateOf(container.ttsServicesStore.cacheNetworkAudioForReplay) }
    var selectionMode by remember {
        mutableStateOf(
            container.preferenceRepository.readJson("tts_text_selection_mode_v1")
                ?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() }
                ?: "fullText",
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
                MemoTopBar(
                    title = stringResource(UiR.string.tts_settings_page_title),
                    onBack = onBack,
                )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item {
                TtsSettingsSection(title = stringResource(UiR.string.tts_settings_playback_section)) {
                    // L57-70 — auto-play row.
                    TtsSettingsRow(
                        title = stringResource(UiR.string.tts_settings_auto_play_title),
                        subtitle = stringResource(UiR.string.tts_settings_auto_play_description),
                        trailing = {
                            IosSwitch(value = autoPlay, onValueChanged = {
                                autoPlay = it
                                writeBool("tts_auto_play_assistant_replies_v1", it)
                            })
                        },
                    )
                    TtsSettingsDivider()
                    // L71-81 — cache replay row.
                    TtsSettingsRow(
                        title = stringResource(UiR.string.tts_settings_cache_replay_title),
                        subtitle = stringResource(UiR.string.tts_settings_cache_replay_description),
                        trailing = {
                            IosSwitch(value = cacheReplay, onValueChanged = {
                                cacheReplay = it
                                container.ttsServicesStore.cacheNetworkAudioForReplay = it
                            })
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(18.dp)) }
            item {
                TtsSettingsSection(
                    title = stringResource(UiR.string.tts_settings_text_selection_section),
                    footer = stringResource(UiR.string.tts_settings_text_selection_fallback_description),
                ) {
                    // L88-98 — one row per TtsTextSelectionMode.
                    val modes = listOf("fullText", "quotedOnly", "outsideParentheses", "italicOnly", "nonItalic")
                    modes.forEachIndexed { index, mode ->
                        if (index > 0) TtsSettingsDivider()
                        TtsTextSelectionRow(
                            mode = mode,
                            selected = selectionMode == mode,
                            onTap = {
                                selectionMode = mode
                                container.preferenceRepository.writeJson(
                                    "tts_text_selection_mode_v1", "\"$mode\"",
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

/** L104-161 — _SettingsSection: 13sp semibold header + SectionCard + optional footer. */
@Composable
private fun TtsSettingsSection(
    title: String,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column {
        Text(
            text = title,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface.copy(alpha = 0.8f),
            ),
        )
        SettingsSectionCard { content() }
        if (footer != null) {
            Spacer(Modifier.height(7.dp))
            Text(
                text = footer,
                modifier = Modifier.padding(horizontal = 12.dp),
                style = TextStyle(
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    color = cs.onSurface.copy(alpha = 0.58f),
                ),
            )
        }
    }
}

/** L163-189 — _SettingsRow: title + trailing widget；说明走 Tooltip 气泡（用户规范：不裸排）。 */
@Composable
private fun TtsSettingsRow(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ⓘ 紧跟标题文字（用户 2026-09-12 点名，见 SettingsUi.TipHuggingLabel）。
        TipHuggingLabel(
            label = title,
            tip = subtitle.ifEmpty { null },
            labelStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.9f)),
        )
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

/** L191-232 — _TextSelectionRow with the animated trailing check. */
@Composable
private fun TtsTextSelectionRow(
    mode: String,
    selected: Boolean,
    onTap: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(start = 14.dp, top = 11.dp, end = 12.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(ttsSelectionModeTitle(mode)),
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.9f)),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = stringResource(ttsSelectionModeDescription(mode)),
                style = TextStyle(fontSize = 12.sp, lineHeight = 15.sp, color = cs.onSurface.copy(alpha = 0.62f)),
            )
        }
        Spacer(Modifier.width(12.dp))
        // 上游是 `AnimatedOpacity(selected ? 1 : 0, 160ms)`：勾选**只在选中行**出现。
        // 之前无条件画，五行全带勾，看起来就像"没有可以选择的地方"。
        val checkAlpha by androidx.compose.animation.core.animateFloatAsState(
            if (selected) 1f else 0f,
            animationSpec = androidx.compose.animation.core.tween(160),
        )
        Icon(
            Lucide.Check,
            contentDescription = null,
            tint = cs.primary.copy(alpha = checkAlpha),
            modifier = Modifier.size(18.dp),
        )
    }
}

/** L268-280 — _SettingsDivider. */
@Composable
private fun TtsSettingsDivider() {
    val cs = MaterialTheme.colorScheme
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(start = 14.dp, end = 12.dp),
        thickness = 0.6.dp,
        color = cs.outlineVariant.copy(alpha = 0.18f),
    )
}

/** L282-294 — _modeTitle. */
private fun ttsSelectionModeTitle(mode: String): Int = when (mode) {
    "quotedOnly" -> UiR.string.tts_settings_text_selection_quoted_only_title
    "outsideParentheses" -> UiR.string.tts_settings_text_selection_outside_parentheses_title
    "italicOnly" -> UiR.string.tts_settings_text_selection_italic_only_title
    "nonItalic" -> UiR.string.tts_settings_text_selection_non_italic_title
    else -> UiR.string.tts_settings_text_selection_full_text_title
}

/** L296-309 — _modeDescription. */
private fun ttsSelectionModeDescription(mode: String): Int = when (mode) {
    "quotedOnly" -> UiR.string.tts_settings_text_selection_quoted_only_description
    "outsideParentheses" -> UiR.string.tts_settings_text_selection_outside_parentheses_description
    "italicOnly" -> UiR.string.tts_settings_text_selection_italic_only_description
    "nonItalic" -> UiR.string.tts_settings_text_selection_non_italic_description
    else -> UiR.string.tts_settings_text_selection_full_text_description
}

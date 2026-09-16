package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Crop
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Link
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ui.R as UiR

/**
 * 1:1 port of lib/features/settings/pages/image_settings_page.dart —
 * three sections (编辑 / 发送 / 画质) with footers, five quality rows
 * (original / high / balanced / saver / custom, with check state), the custom
 * slider (10-100, step 5) only visible for the custom quality, and the
 * transparent-compression switch disabled (α 0.5) while original is selected.
 *
 * Persistence matches settings_provider.dart:281-287,5209-5221: the quality
 * enum is stored under 'image_upload_quality_v1' (name string, default
 * 'balanced'), and the custom int under 'image_compress_custom_quality_v1'
 * (default 85, clamped 10-100).
 */
private val qualityIds = listOf("original", "high", "balanced", "saver", "custom")

@Composable
fun ImageSettingsScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme

    fun readBool(key: String, default: Boolean): Boolean =
        container.preferenceRepository.readJson(key)?.let { it == "1" } ?: default
    fun writeBool(key: String, value: Boolean) {
        container.preferenceRepository.writeJson(key, if (value) "1" else "0")
    }

    var cropperEnabled by remember { mutableStateOf(readBool("image_cropper_enabled_v1", false)) }
    // settings_provider.dart:5211-5217 — unknown/missing → balanced.
    var uploadQuality by remember {
        mutableStateOf(
            container.preferenceRepository.readJson("image_upload_quality_v1")?.takeIf { it in qualityIds }
                ?: "balanced",
        )
    }
    // settings_provider.dart:5218-5219 — default 85, clamp 10..100.
    var customQuality by remember {
        mutableIntStateOf((container.preferenceRepository.readJson("image_compress_custom_quality_v1")?.toIntOrNull() ?: 85).coerceIn(10, 100))
    }
    var compressTransparent by remember { mutableStateOf(readBool("image_compress_transparent_enabled_v1", false)) }
    var mdImageLinks by remember { mutableStateOf(readBool("send_markdown_image_links_as_images_v1", false)) }

    // image_settings_page.dart:22-23 — compression only applies to non-original.
    val compressionEnabled = uploadQuality != "original"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        MemoTopBar(
            title = stringResource(UiR.string.image_settings_page_title),
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp,
            ),
        ) {
            // 编辑 section.
            item {
                ImageSectionTitle(stringResource(UiR.string.image_settings_page_edit_section_title))
                SettingsSectionCard {
                    SettingsSwitchRow(
                        Lucide.Crop,
                        stringResource(UiR.string.display_settings_page_enable_image_cropper_title),
                        tip = stringResource(UiR.string.display_settings_page_enable_image_cropper_subtitle),
                        value = cropperEnabled,
                        onToggle = { cropperEnabled = it; writeBool("image_cropper_enabled_v1", it) },
                    )
                }
            }
            // 发送 section.
            item {
                Spacer(Modifier.height(18.dp))
                ImageSectionTitle(stringResource(UiR.string.image_settings_page_send_section_title))
                SettingsSectionCard {
                    SettingsSwitchRow(
                        Lucide.Link,
                        stringResource(UiR.string.image_settings_page_markdown_image_links_title),
                        tip = stringResource(UiR.string.image_settings_page_markdown_image_links_subtitle),
                        value = mdImageLinks,
                        onToggle = { mdImageLinks = it; writeBool("send_markdown_image_links_as_images_v1", it) },
                    )
                }
            }
            // 画质 section with footer.
            item {
                Spacer(Modifier.height(18.dp))
                ImageSectionTitle(stringResource(UiR.string.image_settings_page_quality_section_title))
                SettingsSectionCard {
                    qualityIds.forEachIndexed { index, id ->
                        QualityRow(
                            titleRes = when (id) {
                                "original" -> UiR.string.image_settings_page_quality_original
                                "high" -> UiR.string.image_settings_page_quality_high
                                "balanced" -> UiR.string.image_settings_page_quality_balanced
                                "saver" -> UiR.string.image_settings_page_quality_saver
                                else -> UiR.string.image_settings_page_quality_custom
                            },
                            subtitleRes = when (id) {
                                "original" -> UiR.string.image_settings_page_quality_original_subtitle
                                "high" -> UiR.string.image_settings_page_quality_high_subtitle
                                "balanced" -> UiR.string.image_settings_page_quality_balanced_subtitle
                                "saver" -> UiR.string.image_settings_page_quality_saver_subtitle
                                else -> UiR.string.image_settings_page_quality_custom_subtitle
                            },
                            selected = uploadQuality == id,
                            onTap = {
                                uploadQuality = id
                                container.preferenceRepository.writeJson("image_upload_quality_v1", id)
                            },
                        )
                        if (id == "custom" && uploadQuality == "custom") {
                            // image_settings_page.dart:34-40,211-305 — slider only for custom.
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.dp, top = 11.dp, end = 12.dp, bottom = 10.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = stringResource(UiR.string.image_settings_page_custom_quality_title),
                                        modifier = Modifier.weight(1f),
                                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.9f)),
                                    )
                                    Text(
                                        text = "$customQuality",
                                        style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.68f)),
                                    )
                                }
                                com.psyche.memo.ui.slider.MemoSlider(
                                    value = customQuality.toFloat(),
                                    // 画质本身是整数百分比（10..100），取整是数据粒度、不是档位。
                                    onValueChange = { customQuality = it.roundToInt().coerceIn(10, 100) },
                                    valueRange = 10f..100f,
                                    valueLabel = { it.roundToInt().toString() },
                                    onValueChangeFinished = {
                                        container.preferenceRepository.writeJson(
                                            "image_compress_custom_quality_v1",
                                            customQuality.toString(),
                                        )
                                    },
                                )
                            }
                        }
                        if (index != qualityIds.lastIndex) SettingsIosDivider()
                    }
                    SettingsIosDivider()
                    // L41-50,322-337 — transparent compression disabled (α 0.5) while original.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (compressionEnabled) 1f else 0.5f)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(
                                    text = stringResource(UiR.string.image_settings_page_compress_transparent_title),
                                    style = TextStyle(fontSize = 15.sp, color = cs.onSurface),
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = stringResource(UiR.string.image_settings_page_compress_transparent_subtitle),
                                    style = TextStyle(fontSize = 12.sp, lineHeight = 14.sp, color = cs.onSurface.copy(alpha = 0.56f)),
                                )
                            }
                        }
                        IosSwitch(
                            value = if (compressionEnabled) compressTransparent else false,
                            onValueChanged = if (compressionEnabled) {
                                { compressTransparent = it; writeBool("image_compress_transparent_enabled_v1", it) }
                            } else {
                                null
                            },
                        )
                    }
                }
                // L101-102 — quality section footer.
                Spacer(Modifier.height(7.dp))
                Text(
                    text = stringResource(UiR.string.image_settings_page_footer),
                    modifier = Modifier.padding(horizontal = 12.dp),
                    style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurface.copy(alpha = 0.58f)),
                )
            }
        }
    }
}

/** L129-138 — section title: 13sp semibold onSurface@0.8, LTRB(12,0,12,6). */
@Composable
private fun ImageSectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)),
    )
}

/** L170-209 — quality option row with trailing check only while selected. */
@Composable
private fun QualityRow(titleRes: Int, subtitleRes: Int, selected: Boolean, onTap: () -> Unit) {
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
                text = stringResource(titleRes),
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.9f)),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = stringResource(subtitleRes),
                style = TextStyle(fontSize = 12.sp, lineHeight = 15.sp, color = cs.onSurface.copy(alpha = 0.62f)),
            )
        }
        Spacer(Modifier.width(12.dp))
        // L199-203 — check fades in only while selected.
        if (selected) {
            Icon(
                Lucide.Check,
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

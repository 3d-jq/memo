package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Crop
import com.composables.icons.lucide.Eraser
import com.composables.icons.lucide.Link
import com.composables.icons.lucide.Lucide
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ui.R as UiR

/**
 * 1:1 port of lib/features/settings/pages/image_settings_page.dart —
 * image cropper toggle, upload quality slider, compress transparency,
 * markdown image links as images. Keys are the original Flutter strings
 * (settings_provider.dart:280-284).
 */
@Composable
fun ImageSettingsScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme

    fun readBool(key: String, default: Boolean): Boolean =
        container.preferenceRepository.readLocal(key)?.let { it == "1" } ?: default
    fun writeBool(key: String, value: Boolean) {
        container.preferenceRepository.writeLocal(key, if (value) "1" else "0")
    }

    var cropperEnabled by remember { mutableStateOf(readBool("image_cropper_enabled_v1", false)) }
    var uploadQuality by remember {
        mutableStateOf(
            container.preferenceRepository.readLocal("image_upload_quality_v1")?.toIntOrNull() ?: 100,
        )
    }
    var compressTransparent by remember { mutableStateOf(readBool("image_compress_transparent_enabled_v1", false)) }
    var mdImageLinks by remember { mutableStateOf(readBool("send_markdown_image_links_as_images_v1", false)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(
                    Lucide.ArrowLeft,
                    contentDescription = stringResource(UiR.string.settings_page_back_button),
                    tint = cs.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = stringResource(UiR.string.image_settings_page_title),
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp,
            ),
        ) {
            item {
                SettingsSectionCard {
                    SettingsSwitchRow(
                        Lucide.Crop,
                        stringResource(UiR.string.display_settings_page_enable_image_cropper_title),
                        value = cropperEnabled,
                        onToggle = { cropperEnabled = it; writeBool("image_cropper_enabled_v1", it) },
                    )
                }
            }
            item {
                // Upload quality slider (source uses SfSlider; values 0-100).
                SettingsSectionCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(UiR.string.image_settings_page_custom_quality_title),
                                modifier = Modifier.weight(1f),
                                style = TextStyle(fontSize = 15.sp, color = cs.onSurface),
                            )
                            Text(
                                text = "$uploadQuality",
                                style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                            )
                        }
                        Slider(
                            value = uploadQuality.toFloat(),
                            onValueChange = { uploadQuality = it.toInt() },
                            valueRange = 0f..100f,
                            onValueChangeFinished = {
                                container.preferenceRepository.writeLocal(
                                    "image_upload_quality_v1",
                                    uploadQuality.toString(),
                                )
                            },
                        )
                    }
                }
            }
            item {
                SettingsSectionCard {
                    SettingsSwitchRow(
                        Lucide.Eraser,
                        stringResource(UiR.string.image_settings_page_compress_transparent_title),
                        value = compressTransparent,
                        onToggle = { compressTransparent = it; writeBool("image_compress_transparent_enabled_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.Link,
                        stringResource(UiR.string.image_settings_page_markdown_image_links_title),
                        value = mdImageLinks,
                        onToggle = { mdImageLinks = it; writeBool("send_markdown_image_links_as_images_v1", it) },
                    )
                }
            }
        }
    }
}

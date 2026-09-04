package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ui.R as UiR

/**
 * 1:1 port of message_style_settings_page.dart — style picker
 * (Default / Frosted Glass / Solid) + layout switches
 * (assistantBubbleFitContent / assistantBubbleSplitParagraphs).
 * Style persisted as display_chat_message_background_style_v1
 * (default / frosted / solid — chat_bubble_style.dart values).
 */
@Composable
fun MessageStyleSettingsScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme

    var style by remember { mutableStateOf("default") }
    var fitContent by remember { mutableStateOf(false) }
    var splitParagraphs by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        style = container.preferenceRepository.readLocal("display_chat_message_background_style_v1")
            ?.takeIf { it.isNotEmpty() } ?: "default"
        fitContent = container.preferenceRepository.readLocal("display_assistant_bubble_fit_content_v1") == "1"
        splitParagraphs = container.preferenceRepository.readLocal("display_assistant_bubble_split_paragraphs_v1") == "1"
    }
    fun saveStyle(v: String) {
        style = v
        container.preferenceRepository.writeLocal("display_chat_message_background_style_v1", v)
    }
    fun saveBool(key: String, v: Boolean) {
        container.preferenceRepository.writeLocal(key, if (v) "1" else "0")
    }

    val styles = listOf(
        Triple("default", stringResource(UiR.string.display_settings_page_chat_message_background_default), cs.surfaceVariant),
        Triple("frosted", stringResource(UiR.string.display_settings_page_chat_message_background_frosted), cs.primary.copy(alpha = 0.25f)),
        Triple("solid", stringResource(UiR.string.display_settings_page_chat_message_background_solid), cs.primary.copy(alpha = 0.6f)),
    )

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
                text = stringResource(UiR.string.message_style_settings_page_title),
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
                Text(
                    text = stringResource(UiR.string.message_style_settings_page_background_color),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.primary),
                )
                styles.forEach { (id, label, swatch) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { saveStyle(id) }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(swatch, RoundedCornerShape(10.dp))
                                .border(1.dp, cs.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(10.dp)),
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(
                            text = label,
                            modifier = Modifier.weight(1f),
                            style = TextStyle(fontSize = 15.sp, color = cs.onSurface),
                        )
                        if (style == id) {
                            Icon(
                                Lucide.Check,
                                contentDescription = null,
                                tint = cs.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.size(12.dp))
                SettingsSectionCard {
                    SettingsSwitchRow(
                        icon = Lucide.Check,
                        label = stringResource(UiR.string.message_style_settings_page_assistant_fit_content),
                        value = fitContent,
                        onToggle = { fitContent = it; saveBool("display_assistant_bubble_fit_content_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        icon = Lucide.Check,
                        label = stringResource(UiR.string.message_style_settings_page_assistant_split_paragraphs),
                        value = splitParagraphs,
                        onToggle = { splitParagraphs = it; saveBool("display_assistant_bubble_split_paragraphs_v1", it) },
                    )
                }
            }
        }
    }
}

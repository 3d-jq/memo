package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.CircleHelp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Type
import com.composables.icons.lucide.User
import com.composables.icons.lucide.Wrench
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ui.R as UiR

/**
 * 1:1 port of display_settings_page.dart L1620-1764 —
 * ChatItemDisplaySettingsPage: 13 switch rows in source order. Keys are the
 * original Flutter prefs keys (settings_provider.dart:178-221) stored as
 * "1"/"0"; all default to true (settings_provider.dart:1068).
 */
private data class SwitchItem(
    val icon: ImageVector,
    val labelRes: Int,
    val tipRes: Int? = null,
    val prefsKey: String,
)

private val switchRows = listOf(
    SwitchItem(Lucide.User, UiR.string.display_settings_page_show_user_avatar_title, prefsKey = "display_show_user_avatar_v1"),
    SwitchItem(Lucide.MessageCircle, UiR.string.display_settings_page_show_user_name_title, prefsKey = "display_show_user_name_v1"),
    SwitchItem(Lucide.Clock, UiR.string.display_settings_page_show_user_timestamp_title, prefsKey = "display_show_user_timestamp_v1"),
    SwitchItem(Lucide.Ellipsis, UiR.string.display_settings_page_show_user_message_actions_title, prefsKey = "display_show_user_message_actions_v1"),
    SwitchItem(Lucide.Bot, UiR.string.display_settings_page_chat_model_icon_title, prefsKey = "display_show_model_icon_v1"),
    SwitchItem(Lucide.Bot, UiR.string.display_settings_page_use_new_assistant_avatar_ux_title, prefsKey = "display_use_new_assistant_avatar_ux_v1"),
    SwitchItem(Lucide.MessageSquare, UiR.string.display_settings_page_show_model_name_title, prefsKey = "display_show_model_name_v1"),
    SwitchItem(Lucide.Clock, UiR.string.display_settings_page_show_model_timestamp_title, prefsKey = "display_show_model_timestamp_v1"),
    SwitchItem(Lucide.Globe, UiR.string.display_settings_page_show_provider_in_chat_message_title, prefsKey = "display_show_provider_in_chat_message_v1"),
    SwitchItem(Lucide.Type, UiR.string.display_settings_page_show_token_stats_title, prefsKey = "display_show_token_stats_v1"),
    SwitchItem(
        Lucide.Sparkles,
        UiR.string.display_settings_page_show_thinking_cards_title,
        tipRes = UiR.string.display_settings_page_show_thinking_cards_subtitle,
        prefsKey = "display_show_thinking_cards_v1",
    ),
    SwitchItem(
        Lucide.Wrench,
        UiR.string.display_settings_page_show_tool_cards_title,
        tipRes = UiR.string.display_settings_page_show_tool_cards_subtitle,
        prefsKey = "display_show_tool_cards_v1",
    ),
)

/** L1183-1192: iOS divider — height 6, 0.6 thick, indent 54, endIndent 12. */
@Composable
private fun IosDivider() {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 54.dp, end = 12.dp)
            .height(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.6.dp)
                .background(cs.outlineVariant.copy(alpha = 0.18f)),
        )
    }
}

/** L1363-1424: nav/switch row shared metrics (h12/v11 via tip==null branch). */
@Composable
private fun SwitchRow(
    item: SwitchItem,
    value: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!value) }
            .padding(horizontal = 12.dp, vertical = if (item.tipRes == null) 2.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.width(36.dp)) {
                Icon(
                    item.icon,
                    contentDescription = null,
                    tint = cs.onSurface.copy(alpha = 0.9f),
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(item.labelRes),
                    style = TextStyle(fontSize = 15.sp, color = cs.onSurface),
                )
                item.tipRes?.let { tipRes ->
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = stringResource(tipRes),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                            color = cs.onSurface.copy(alpha = 0.56f),
                        ),
                    )
                }
            }
        }
        // MemoryTipIcon slot (L1424-1426) — info glyph at 45%.
        if (item.tipRes != null) {
            Icon(
                Lucide.CircleHelp,
                contentDescription = null,
                tint = cs.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = value,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedTrackColor = cs.primary,
                checkedThumbColor = Color.White,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = cs.surfaceVariant,
            ),
        )
    }
}

@Composable
fun ChatItemDisplaySettingsScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme

    // Boolean prefs persisted as "1"/"0" (defaults all true — L1068).
    var values by remember {
        mutableStateOf(switchRows.associate { it.prefsKey to true })
    }
    LaunchedEffect(Unit) {
        values = switchRows.associate {
            it.prefsKey to (container.preferenceRepository.readLocal(it.prefsKey)?.let { v -> v == "1" } ?: true)
        }
    }
    fun writeBool(key: String, value: Boolean) {
        values = values + (key to value)
        container.preferenceRepository.writeLocal(key, if (value) "1" else "0")
    }

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
                text = stringResource(UiR.string.display_settings_page_chat_item_display_title),
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
                // SectionCard standard (section_card.dart L30-66): r12,
                // hairline border, vertical padding 4. hairline =
                // outlineVariant @ (dark 0.08 / light 0.06).
                val dark = cs.surface.luminanceCompat() < 0.5f
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            cs.surfaceCardColor(dark),
                            RoundedCornerShape(12.dp),
                        )
                        .border(
                            1.dp,
                            cs.outlineVariant.copy(alpha = if (dark) 0.08f else 0.06f),
                            RoundedCornerShape(12.dp),
                        )
                        .padding(vertical = 4.dp),
                ) {
                    switchRows.forEachIndexed { index, item ->
                        SwitchRow(
                            item = item,
                            value = values[item.prefsKey] ?: true,
                            onToggle = { writeBool(item.prefsKey, it) },
                        )
                        if (index != switchRows.lastIndex) IosDivider()
                    }
                }
            }
        }
    }
}

/** AppSemanticColors.surfaceCard (AppSemanticColors.kt L29). */
private fun ColorScheme.surfaceCardColor(dark: Boolean): Color =
    lerp(surface, Color.White, if (dark) 0.10f else 0.96f)

private fun Color.luminanceCompat(): Float =
    0.2126f * red + 0.7152f * green + 0.0722f * blue

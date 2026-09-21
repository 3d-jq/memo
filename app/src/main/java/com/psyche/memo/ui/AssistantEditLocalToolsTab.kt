package com.psyche.memo.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.composables.icons.lucide.Calculator
import com.composables.icons.lucide.Calendar
import com.composables.icons.lucide.CalendarPlus
import com.composables.icons.lucide.Clipboard
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircleQuestion
import com.composables.icons.lucide.MapPin
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Shapes
import com.composables.icons.lucide.Workflow
import com.composables.icons.lucide.Volume2
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.provider.DeviceLocalTools
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager

/**
 * Port of assistant_settings_edit_local_tools_tab.dart: the Android-available
 * local tools with their permission flows. iOS-only rows (location / weather /
 * health / reminders) are hidden exactly like DeviceLocalTools does upstream.
 */
@Composable
fun AssistantEditLocalToolsTab(
    container: AppContainerImpl,
    assistant: Assistant,
    onEdit: ((Assistant) -> Assistant) -> Unit,
) {
    val context = LocalContext.current
    val names = BuiltInToolCatalog.LocalToolNames
    val timeInfo = names.TIME_INFO
    val clipboard = names.CLIPBOARD
    val tts = names.TEXT_TO_SPEECH
    val askUser = names.ASK_USER
    val calculate = names.CALCULATE
    val screenTime = names.SCREEN_TIME
    val calendarQuery = names.CALENDAR_QUERY
    val calendarCreate = names.CALENDAR_CREATE
    val currentLocation = names.CURRENT_LOCATION
    val renderVisual = names.RENDER_VISUAL
    val renderMermaid = names.RENDER_MERMAID

    val screenTimePermissionMessage =
        stringResource(R.string.chat_message_widget_screen_time_permission_required)

    fun updateTool(toolId: String, value: Boolean) {
        onEdit { a ->
            val ids = a.localToolIds.toMutableSet()
            if (value) ids.add(toolId) else ids.remove(toolId)
            a.copy(localToolIds = ids.toList())
        }
    }

    fun hasCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    val calendarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) {
            updateTool(calendarQuery, true)
            updateTool(calendarCreate, true)
        }
    }

    // 上游同样在「打开这一行」时就申请（assistant_settings_edit_local_tools_tab.dart
    // :106-118）：没同意就不把工具开进来。同意了就只授权、不落开关，等用户再点一次。
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) updateTool(currentLocation, true)
    }

    fun toggleTool(toolId: String, value: Boolean) {
        if (!value) {
            updateTool(toolId, false)
            return
        }
        when (toolId) {
            screenTime -> {
                if (!DeviceLocalTools.hasUsageStatsPermission(context)) {
                    SnackbarManager.show(
                        AppNotification(message = screenTimePermissionMessage, type = NotificationType.WARNING),
                    )
                    DeviceLocalTools.openUsageAccessSettings(context)
                }
                // Still enable even if Usage Access is not granted yet.
                updateTool(toolId, true)
            }
            currentLocation -> {
                if (com.psyche.memo.provider.LocationTool.hasPermission(context)) {
                    updateTool(toolId, true)
                } else {
                    locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
            calendarQuery, calendarCreate -> {
                if (hasCalendarPermission()) {
                    updateTool(toolId, true)
                } else {
                    calendarLauncher.launch(
                        arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                    )
                }
            }
            else -> updateTool(toolId, true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 20.dp),
    ) {
        SettingsSectionCard {
            LocalToolRow(
                icon = Lucide.Clock,
                titleRes = R.string.assistant_edit_local_tool_time_info_title,
                subtitleRes = R.string.assistant_edit_local_tool_time_info_subtitle,
                enabled = timeInfo in assistant.localToolIds,
                onChanged = { toggleTool(timeInfo, it) },
            )
            SettingsIosDivider()
            LocalToolRow(
                icon = Lucide.Clipboard,
                titleRes = R.string.assistant_edit_local_tool_clipboard_title,
                subtitleRes = R.string.assistant_edit_local_tool_clipboard_subtitle,
                enabled = clipboard in assistant.localToolIds,
                onChanged = { toggleTool(clipboard, it) },
            )
            SettingsIosDivider()
            LocalToolRow(
                icon = Lucide.Volume2,
                titleRes = R.string.assistant_edit_local_tool_text_to_speech_title,
                subtitleRes = R.string.assistant_edit_local_tool_text_to_speech_subtitle,
                enabled = tts in assistant.localToolIds,
                onChanged = { toggleTool(tts, it) },
            )
            SettingsIosDivider()
            LocalToolRow(
                icon = Lucide.MessageCircleQuestion,
                titleRes = R.string.assistant_edit_local_tool_ask_user_title,
                subtitleRes = R.string.assistant_edit_local_tool_ask_user_subtitle,
                enabled = askUser in assistant.localToolIds,
                onChanged = { toggleTool(askUser, it) },
            )
            SettingsIosDivider()
            LocalToolRow(
                icon = Lucide.Calculator,
                titleRes = R.string.assistant_edit_local_tool_calculate_title,
                subtitleRes = R.string.assistant_edit_local_tool_calculate_subtitle,
                enabled = calculate in assistant.localToolIds,
                onChanged = { toggleTool(calculate, it) },
            )
            // Android-only rows (DeviceLocalTools.screenTimeSupported /
            // calendarSupported).
            SettingsIosDivider()
            LocalToolRow(
                icon = Lucide.Smartphone,
                titleRes = R.string.assistant_edit_local_tool_screen_time_title,
                subtitleRes = R.string.assistant_edit_local_tool_screen_time_subtitle,
                enabled = screenTime in assistant.localToolIds,
                onChanged = { toggleTool(screenTime, it) },
            )
            SettingsIosDivider()
            LocalToolRow(
                icon = Lucide.Calendar,
                titleRes = R.string.assistant_edit_local_tool_calendar_query_title,
                subtitleRes = R.string.assistant_edit_local_tool_calendar_query_subtitle,
                enabled = calendarQuery in assistant.localToolIds,
                onChanged = { toggleTool(calendarQuery, it) },
            )
            SettingsIosDivider()
            LocalToolRow(
                icon = Lucide.CalendarPlus,
                titleRes = R.string.assistant_edit_local_tool_calendar_create_title,
                subtitleRes = R.string.assistant_edit_local_tool_calendar_create_subtitle,
                enabled = calendarCreate in assistant.localToolIds,
                onChanged = { toggleTool(calendarCreate, it) },
            )
            // 当前位置（上游 iOS-only，安卓侧执行器见 LocationTool；开这一行时就要权限）。
            SettingsIosDivider()
            LocalToolRow(
                icon = Lucide.MapPin,
                titleRes = R.string.assistant_edit_local_tool_location_title,
                subtitleRes = R.string.assistant_edit_local_tool_location_subtitle,
                enabled = currentLocation in assistant.localToolIds,
                onChanged = { toggleTool(currentLocation, it) },
            )
            // 可视化绘图（自研，上游没有这一行）：一个工具、一个 kind 枚举 —— 数据图由
            // 我们画（跟主题），kind="svg" 时模型直接写 SVG（流程图/时间轴/仪表盘等）。
            SettingsIosDivider()
            LocalToolRow(
                icon = Lucide.Shapes,
                titleRes = R.string.assistant_edit_local_tool_render_visual_title,
                subtitleRes = R.string.assistant_edit_local_tool_render_visual_subtitle,
                enabled = renderVisual in assistant.localToolIds,
                onChanged = { toggleTool(renderVisual, it) },
            )
            // Mermaid 图（自研）：流程图/时序图/状态图/ER/类图/甘特/思维导图等。
            SettingsIosDivider()
            LocalToolRow(
                icon = Lucide.Workflow,
                titleRes = R.string.assistant_edit_local_tool_render_mermaid_title,
                subtitleRes = R.string.assistant_edit_local_tool_render_mermaid_subtitle,
                enabled = renderMermaid in assistant.localToolIds,
                onChanged = { toggleTool(renderMermaid, it) },
            )
        }
    }
}

/** _LocalToolRow: icon slot + 15sp title + 12sp subtitle + switch. */
@Composable
private fun LocalToolRow(
    icon: ImageVector,
    titleRes: Int,
    subtitleRes: Int,
    enabled: Boolean,
    onChanged: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChanged(!enabled) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(titleRes), style = TextStyle(fontSize = 15.sp))
            Text(
                text = stringResource(subtitleRes),
                style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.56f)),
            )
        }
        IosSwitch(value = enabled, onValueChanged = onChanged)
    }
}

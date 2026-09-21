package com.psyche.memo.ui.backup

import com.psyche.memo.ui.theme.MemoRadius
import com.psyche.memo.ui.overlaySurfaceColor
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.DatabaseBackup
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.BackupReminder
import com.psyche.memo.ui.icuString
import com.psyche.memo.ui.IosFormField
import com.psyche.memo.ui.IosIconButton
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.TactileRow
import com.psyche.memo.ui.theme.alphaBlend
import com.psyche.memo.ui.R as UiR
import java.time.LocalDateTime
import java.util.Calendar

/**
 * 备份提醒的 UI 层：backup_page.dart `_BackupReminderMobileSection` L1602-1694、
 * side_drawer.dart `_buildBackupReminderBanner` L1520-1612，以及
 * backup_reminder_helpers.dart 的时间滚轮 / 频率 sheet / 自定义天数对话框。
 * 几何数值全部取自那三处源码。
 */

// ── labels ───────────────────────────────────────────────────────────────────

@Composable
fun backupReminderFrequencyLabel(days: Int): String = when (days) {
    1 -> androidx.compose.ui.res.stringResource(UiR.string.backup_reminder_every_day)
    3 -> androidx.compose.ui.res.stringResource(UiR.string.backup_reminder_every_three_days)
    7 -> androidx.compose.ui.res.stringResource(UiR.string.backup_reminder_every_week)
    14 -> androidx.compose.ui.res.stringResource(UiR.string.backup_reminder_every_fourteen_days)
    30 -> androidx.compose.ui.res.stringResource(UiR.string.backup_reminder_every_month)
    else -> icuString(UiR.string.backup_reminder_custom_days, "days" to days.toString())
}

/** `TimeOfDay.format(context)` 的等价物：跟随系统 12/24 小时制。 */
fun backupReminderTimeLabel(context: android.content.Context, minutes: Int?): String {
    if (minutes == null) return context.getString(UiR.string.backup_reminder_disabled)
    val calendar = Calendar.getInstance().apply { set(0, 0, 0, minutes / 60, minutes % 60) }
    return DateFormat.getTimeFormat(context).format(calendar.time)
}

fun backupReminderDateTimeLabel(context: android.content.Context, value: LocalDateTime?): String {
    if (value == null) return context.getString(UiR.string.backup_reminder_never)
    val calendar = Calendar.getInstance().apply {
        set(value.year, value.monthValue - 1, value.dayOfMonth, value.hour, value.minute, value.second)
    }
    val date = DateFormat.getMediumDateFormat(context).format(calendar.time)
    val time = DateFormat.getTimeFormat(context).format(calendar.time)
    return "$date $time"
}

fun backupReminderNextLabel(context: android.content.Context, value: LocalDateTime?): String {
    if (value == null) return context.getString(UiR.string.backup_reminder_disabled)
    if (!java.time.LocalDateTime.now().isBefore(value)) {
        return context.getString(UiR.string.backup_reminder_due_now)
    }
    return backupReminderDateTimeLabel(context, value)
}

// ── drawer banner ────────────────────────────────────────────────────────────

/**
 * 侧栏顶部的到期横幅：到期才出现（`shouldShowReminder`），点击进备份页，X 是
 * 会话内 snooze（重进 app 会再次出现，`snoozedForSession` 不落盘）。
 */
@Composable
fun BackupReminderBanner(
    container: AppContainerImpl,
    onOpenBackup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reminder = container.backupReminder
    val shouldShow by reminder.shouldShowReminderFlow.collectAsState()
    val loaded by reminder.loadedFlow.collectAsState()
    if (!loaded || !shouldShow) return

    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val bg = if (isDark) cs.primary.copy(alpha = 0.18f) else cs.primary.copy(alpha = 0.10f)
    val border = cs.primary.copy(alpha = if (isDark) 0.35f else 0.22f)

    TactileRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        onTap = onOpenBackup,
        haptics = false,
    ) { pressed ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (pressed) bg.copy(alpha = bg.alpha + 0.05f) else bg,
                    RoundedCornerShape(MemoRadius.INNER_DP.dp),
                )
                .border(0.6.dp, border, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                .padding(start = 12.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Lucide.DatabaseBackup,
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    androidx.compose.ui.res.stringResource(UiR.string.backup_reminder_sidebar_title),
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSurface.copy(alpha = 0.92f),
                    ),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    androidx.compose.ui.res.stringResource(UiR.string.backup_reminder_sidebar_subtitle),
                    maxLines = 2,
                    style = TextStyle(
                        fontSize = 12.5.sp,
                        lineHeight = 12.5.sp * 1.25f,
                        color = cs.onSurface.copy(alpha = 0.68f),
                    ),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    androidx.compose.ui.res.stringResource(UiR.string.backup_reminder_sidebar_action),
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.primary),
                )
            }
            IosIconButton(
                icon = Lucide.X,
                onTap = { reminder.snoozeForSession() },
                color = cs.onSurface.copy(alpha = 0.62f),
                size = 16.dp,
                contentPadding = 6.dp,
                semanticLabel = androidx.compose.ui.res.stringResource(UiR.string.backup_reminder_snooze_tooltip),
            )
        }
    }
}

// ── backup page section state ────────────────────────────────────────────────

/** 备份页 §2 各行读的状态（行组件在 BackupScreen.kt，private）。 */
class BackupReminderUiState(
    val reminder: BackupReminder,
    val enabled: Boolean,
    val intervalDays: Int,
    val reminderMinutesOfDay: Int?,
    val lastBackupAt: LocalDateTime?,
)

@Composable
fun rememberBackupReminderState(container: AppContainerImpl): BackupReminderUiState {
    val reminder = container.backupReminder
    val enabled by reminder.enabledFlow.collectAsState()
    val intervalDays by reminder.intervalDaysFlow.collectAsState()
    val minutes by reminder.reminderMinutesOfDayFlow.collectAsState()
    val lastBackupAt by reminder.lastBackupAtFlow.collectAsState()
    return BackupReminderUiState(reminder, enabled, intervalDays, minutes, lastBackupAt)
}

/**
 * 启用开关的 onTap：打开且未选时间 → 先弹时间滚轮（upstream `setEnabled(true)`
 * 在没有时间时抛 StateError，UI 先取时间再 saveSchedule）。
 */
@Composable
fun rememberBackupReminderEnableHandler(
    container: AppContainerImpl,
    onNeedTimePicker: () -> Unit,
): (Boolean) -> Unit {
    val reminder = container.backupReminder
    val minutes by reminder.reminderMinutesOfDayFlow.collectAsState()
    val intervalDays by reminder.intervalDaysFlow.collectAsState()
    return { enable ->
        when {
            enable && minutes == null -> onNeedTimePicker()
            enable -> reminder.saveSchedule(true, intervalDays, minutes ?: 0)
            else -> reminder.disable()
        }
    }
}

// ── time wheel sheet ─────────────────────────────────────────────────────────

/** 42dp itemExtent / 210dp pickerHeight（backup_reminder_helpers.dart L142-143）。 */
private val WHEEL_ITEM_EXTENT = 42.dp
private val WHEEL_HEIGHT = 210.dp
private const val WHEEL_LOOPS = 40

/**
 * 时间滚轮面板（`_BackupReminderTimeWheelPanel` 移动端形态）：双滚轮 +
 * 42dp 选中带 + 28sp primary 时间 + Cancel/Save。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BackupReminderTimeSheet(
    initialMinutes: Int?,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val context = LocalContext.current
    val initial = initialMinutes ?: Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
    var hour by remember { mutableStateOf(initial / 60) }
    var minute by remember { mutableStateOf(initial % 60) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = semantic.overlaySurface(cs),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        ) {
            Text(
                androidx.compose.ui.res.stringResource(UiR.string.backup_reminder_time_title),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                style = TextStyle(
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface.copy(alpha = 0.92f),
                ),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    backupReminderTimeLabel(context, hour * 60 + minute),
                    style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, color = cs.primary),
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(WHEEL_HEIGHT),
                ) {
                    // Selection band (L316-330): primary ~10/18% wash + border.
                    val isDark = cs.surface.luminance() < 0.5f
                    val bandColor = alphaBlend(
                        cs.primary,
                        (if (isDark) 0.18 else 0.10),
                        cs.surface,
                    )
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .height(WHEEL_ITEM_EXTENT)
                            .background(bandColor, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                            .border(
                                1.dp,
                                cs.primary.copy(alpha = if (isDark) 0.30f else 0.18f),
                                RoundedCornerShape(MemoRadius.INNER_DP.dp),
                            ),
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            ReminderWheel(count = 24, initial = initial / 60, onSelected = { hour = it })
                        }
                        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                            Text(
                                ":",
                                style = TextStyle(
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = cs.onSurface.copy(alpha = 0.62f),
                                ),
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            ReminderWheel(count = 60, initial = initial % 60, onSelected = { minute = it })
                        }
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SheetActionButton(
                    label = androidx.compose.ui.res.stringResource(UiR.string.backup_page_cancel),
                    filled = false,
                    onTap = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                SheetActionButton(
                    label = androidx.compose.ui.res.stringResource(UiR.string.backup_page_save),
                    filled = true,
                    onTap = { onSave(hour * 60 + minute) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 循环滚轮：count × WHEEL_LOOPS 个 item 起始于中间副本，snap 对齐格；
 * 选中项随滚动实时变化（对齐 CupertinoPicker.onSelectedItemChanged）。
 */
@Composable
private fun ReminderWheel(count: Int, initial: Int, onSelected: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val total = count * WHEEL_LOOPS
    val state = rememberLazyListState(initialFirstVisibleItemIndex = count * (WHEEL_LOOPS / 2) + initial)
    var selected by remember { mutableStateOf(initial % count) }
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex }
            .collect { index ->
                val value = ((index % count) + count) % count
                if (value != selected) {
                    selected = value
                    onSelected(value)
                }
            }
    }
    LazyColumn(
        state = state,
        modifier = Modifier.height(WHEEL_HEIGHT),
        flingBehavior = rememberSnapFlingBehavior(lazyListState = state),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            vertical = (WHEEL_HEIGHT - WHEEL_ITEM_EXTENT) / 2,
        ),
    ) {
        items(total) { index ->
            val value = index % count
            val isSelected = value == selected
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(WHEEL_ITEM_EXTENT),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    value.toString().padStart(2, '0'),
                    style = TextStyle(
                        fontSize = if (isSelected) 23.sp else 21.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) cs.primary else cs.onSurface.copy(alpha = 0.72f),
                    ),
                )
            }
        }
    }
}

/** Cancel / Save（L447-486）：onSurface 8-9% / 14-16% 底 + r13。 */
@Composable
private fun SheetActionButton(label: String, filled: Boolean, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    TactileRow(
        modifier = modifier,
        haptics = false,
        onTap = onTap,
    ) { pressed ->
        val base = when {
            filled && isDark -> 0.16f
            filled -> 0.14f
            isDark -> 0.08f
            else -> 0.09f
        }
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    cs.onSurface.copy(alpha = if (pressed) base + 0.03f else base),
                    RoundedCornerShape(MemoRadius.INNER_DP.dp),
                )
                .padding(vertical = 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = if (filled) FontWeight.ExtraBold else FontWeight.SemiBold,
                    color = cs.onSurface.copy(alpha = if (filled) 0.9f else 0.74f),
                ),
            )
        }
    }
}

// ── frequency sheet + custom days dialog ─────────────────────────────────────

/** 频率 sheet（`_showBackupReminderFrequencySheet`）：预设 + 当前自定义值 + Custom… */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BackupReminderFrequencySheet(
    intervalDays: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showCustomDialog by remember { mutableStateOf(false) }
    val options = remember(intervalDays) {
        BackupReminder.PRESET_INTERVALS +
            (if (intervalDays !in BackupReminder.PRESET_INTERVALS) listOf(intervalDays) else emptyList()) +
            0
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = semantic.overlaySurface(cs),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
        ) {
            for (days in options) {
                FrequencyTile(
                    label = if (days == 0) {
                        androidx.compose.ui.res.stringResource(UiR.string.backup_reminder_custom_option)
                    } else {
                        backupReminderFrequencyLabel(days)
                    },
                    selected = days != 0 && days == intervalDays,
                    onTap = {
                        if (days == 0) {
                            showCustomDialog = true
                        } else {
                            onPick(days)
                        }
                    },
                )
            }
        }
    }
    if (showCustomDialog) {
        BackupReminderCustomDaysDialog(
            initialDays = intervalDays,
            onDismiss = { showCustomDialog = false },
            onConfirm = { days ->
                showCustomDialog = false
                onPick(days)
            },
        )
    }
}

@Composable
private fun FrequencyTile(label: String, selected: Boolean, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    TactileRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        onTap = onTap,
    ) { pressed ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    when {
                        selected -> cs.primary.copy(alpha = 0.12f)
                        pressed -> cs.onSurface.copy(alpha = 0.05f)
                        else -> Color.Transparent
                    },
                    RoundedCornerShape(MemoRadius.INNER_DP.dp),
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface),
            )
        }
    }
}

/** `_BackupReminderCustomDaysDialog`：1-365 数字输入。 */
@Composable
fun BackupReminderCustomDaysDialog(
    initialDays: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var text by remember { mutableStateOf(initialDays.toString()) }
    val days = text.toIntOrNull()
    val valid = days != null && days in 1..365
    AlertDialog(
        shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(UiR.string.backup_reminder_custom_dialog_title)) },
        text = {
            Column {
                Text(
                    androidx.compose.ui.res.stringResource(UiR.string.backup_reminder_custom_dialog_description),
                    style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.7f)),
                )
                Spacer(Modifier.height(10.dp))
                IosFormField(
                    label = androidx.compose.ui.res.stringResource(UiR.string.backup_reminder_custom_days_label),
                    value = text,
                    onValueChange = { text = it.filter { ch -> ch.isDigit() }.take(3) },
                )
                if (!valid) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        androidx.compose.ui.res.stringResource(UiR.string.backup_reminder_custom_days_invalid),
                        style = TextStyle(fontSize = 12.sp, color = cs.error),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(days!!) }) {
                Text(
                    androidx.compose.ui.res.stringResource(UiR.string.backup_page_save),
                    color = if (valid) cs.primary else cs.onSurface.copy(alpha = 0.38f),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    androidx.compose.ui.res.stringResource(UiR.string.backup_page_cancel),
                    color = cs.onSurface.copy(alpha = 0.74f),
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
    )
}

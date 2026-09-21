package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psyche.memo.common.Haptics
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.withAlpha
import java.text.BreakIterator
import kotlin.random.Random

/**
 * 头像选择这套 UI 的文案来源：助手编辑页用 `assistant_edit_*`，抽屉里的用户
 * 头像用 `side_drawer_*`（原版 side_drawer 是各写一份私有实现，这里改成参数化
 * 共用同一套组件）。
 */
internal data class AvatarSheetStrings(
    val chooseImage: Int,
    val chooseEmoji: Int,
    val enterLink: Int,
    val importQq: Int,
    val reset: Int,
    val emojiTitle: Int,
    val emojiHint: Int,
    val urlTitle: Int,
    val urlHint: Int,
    val qqTitle: Int,
    val qqHint: Int,
    val qqRandom: Int,
    val save: Int,
    val cancel: Int,
) {
    companion object {
        val Assistant = AvatarSheetStrings(
            chooseImage = UiR.string.assistant_edit_avatar_choose_image,
            chooseEmoji = UiR.string.assistant_edit_avatar_choose_emoji,
            enterLink = UiR.string.assistant_edit_avatar_enter_link,
            importQq = UiR.string.assistant_edit_avatar_import_q_q,
            reset = UiR.string.assistant_edit_avatar_reset,
            emojiTitle = UiR.string.assistant_edit_emoji_dialog_title,
            emojiHint = UiR.string.assistant_edit_emoji_dialog_hint,
            urlTitle = UiR.string.assistant_edit_image_url_dialog_title,
            urlHint = UiR.string.assistant_edit_image_url_dialog_hint,
            qqTitle = UiR.string.assistant_edit_q_q_avatar_dialog_title,
            qqHint = UiR.string.assistant_edit_q_q_avatar_dialog_hint,
            qqRandom = UiR.string.assistant_edit_q_q_avatar_random_button,
            save = UiR.string.assistant_edit_emoji_dialog_save,
            cancel = UiR.string.assistant_edit_emoji_dialog_cancel,
        )

        val User = AvatarSheetStrings(
            chooseImage = UiR.string.side_drawer_choose_image,
            chooseEmoji = UiR.string.side_drawer_choose_emoji,
            enterLink = UiR.string.side_drawer_enter_link,
            importQq = UiR.string.side_drawer_import_from_q_q,
            reset = UiR.string.side_drawer_reset,
            emojiTitle = UiR.string.side_drawer_emoji_dialog_title,
            emojiHint = UiR.string.side_drawer_emoji_dialog_hint,
            urlTitle = UiR.string.side_drawer_image_url_dialog_title,
            urlHint = UiR.string.side_drawer_image_url_dialog_hint,
            qqTitle = UiR.string.side_drawer_q_q_avatar_dialog_title,
            qqHint = UiR.string.side_drawer_q_q_avatar_input_hint,
            qqRandom = UiR.string.side_drawer_random_q_q,
            save = UiR.string.side_drawer_save,
            cancel = UiR.string.side_drawer_cancel,
        )
    }
}

/**
 * `_showAvatarPicker` (assistant_settings_edit_basic_tab.dart L517-617) — five
 * 48dp `IosCardPress` rows; each row pops the sheet *before* running its action,
 * as the source does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AvatarPickerSheet(
    strings: AvatarSheetStrings = AvatarSheetStrings.Assistant,
    onDismiss: () -> Unit,
    onChooseImage: () -> Unit,
    onChooseEmoji: () -> Unit,
    onEnterLink: () -> Unit,
    onImportQq: () -> Unit,
    onReset: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current

    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        dragHandle = null, // 全站自绘 40x4 拖柄
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        containerColor = semantic.overlaySurface(cs),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MemoSheetHandle(trailingGap = 0.dp)
            // 选项行统一成「更多」sheet 的卡片样式（用户 2026-09-12）：原来这里是
            // 透明底的行（`sheetTileColor`，只有开了分层磁贴才显底色），看着不像卡片。
            listOf(
                stringResource(strings.chooseImage) to onChooseImage,
                stringResource(strings.chooseEmoji) to onChooseEmoji,
                stringResource(strings.enterLink) to onEnterLink,
                stringResource(strings.importQq) to onImportQq,
                stringResource(strings.reset) to onReset,
            ).forEach { (label, action) ->
                MemoSheetOptionRow(
                    label = label,
                    selected = false,
                    onClick = {
                        onDismiss()
                        action()
                    },
                )
            }
        }
    }
}

/**
 * `_pickEmoji` L1442-1702 — 72dp preview, free-text entry that only validates a
 * single grapheme, and the fixed 8-column quick grid.
 */
@Composable
internal fun EmojiPickerDialog(
    strings: AvatarSheetStrings = AvatarSheetStrings.Assistant,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var text by remember { mutableStateOf("") }
    val valid = isSingleGrapheme(text)
    val preview = if (text.isEmpty()) "🙂" else firstGrapheme(text)

    fun confirm() {
        if (valid) onPick(preview)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
        title = { Text(stringResource(strings.emojiTitle)) },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(withAlpha(cs.primary, 0.08), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = preview,
                        style = TextStyle(fontSize = 40.sp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                AvatarDialogField(
                    value = text,
                    placeholder = stringResource(strings.emojiHint),
                    onValueChange = { text = it },
                    onSubmit = { confirm() },
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(emojiGridHeight()),
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(8),
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(QuickEmojis) { emoji ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(MemoRadius.INNER_DP.dp))
                                    .background(withAlpha(cs.primary, 0.08))
                                    .clickable { onPick(emoji) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(text = emoji, style = TextStyle(fontSize = 20.sp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { confirm() }, enabled = valid) {
                Text(
                    text = stringResource(strings.save),
                    style = TextStyle(
                        fontWeight = FontWeight.SemiBold,
                        color = if (valid) cs.primary else cs.onSurface.copy(alpha = 0.38f),
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(strings.cancel))
            }
        },
    )
}

/**
 * `_inputAvatarUrl` L1704-1780 — accepts only http(s) URLs, trimmed.
 */
@Composable
internal fun AvatarUrlDialog(
    strings: AvatarSheetStrings = AvatarSheetStrings.Assistant,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var text by remember { mutableStateOf("") }
    val trimmed = text.trim()
    val valid = trimmed.startsWith("http://") || trimmed.startsWith("https://")

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
        title = { Text(stringResource(strings.urlTitle)) },
        text = {
            AvatarDialogField(
                value = text,
                placeholder = stringResource(strings.urlHint),
                onValueChange = { text = it },
                onSubmit = { if (valid) onSave(trimmed) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(trimmed) }, enabled = valid) {
                Text(
                    text = stringResource(strings.save),
                    style = TextStyle(
                        fontWeight = FontWeight.SemiBold,
                        color = if (valid) cs.primary else cs.onSurface.copy(alpha = 0.38f),
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(strings.cancel))
            }
        },
    )
}

/**
 * `_inputQQAvatar` L1782-1946 — QQ-number entry plus the "random" button that
 * probes generated numbers until one resolves to a real avatar.
 *
 * The source uses `actionsAlignment: spaceBetween`; material3 `AlertDialog`
 * exposes only two action slots, so the three buttons share the trailing row.
 */
@Composable
internal fun QQAvatarDialog(
    strings: AvatarSheetStrings = AvatarSheetStrings.Assistant,
    onDismiss: () -> Unit,
    onApplyUrl: (String) -> Unit,
    onRandom: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var text by remember { mutableStateOf("") }
    val trimmed = text.trim()
    val valid = trimmed.matches(QqNumberPattern)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
        title = { Text(stringResource(strings.qqTitle)) },
        text = {
            AvatarDialogField(
                value = text,
                placeholder = stringResource(strings.qqHint),
                numeric = true,
                onValueChange = { text = it },
                onSubmit = { if (valid) onApplyUrl(qqAvatarUrl(trimmed)) },
            )
        },
        dismissButton = {
            TextButton(onClick = onRandom) {
                Text(stringResource(strings.qqRandom))
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(strings.cancel))
                }
                TextButton(onClick = { onApplyUrl(qqAvatarUrl(trimmed)) }, enabled = valid) {
                    Text(
                        text = stringResource(strings.save),
                        style = TextStyle(
                            fontWeight = FontWeight.SemiBold,
                            color = if (valid) cs.primary else cs.onSurface.copy(alpha = 0.38f),
                        ),
                    )
                }
            }
        },
    )
}

/**
 * `TextField(filled: true)` with `surfaceFill`, 12dp radius and the primary
 * 0.4 focus border used by all three avatar dialogs.
 */
@Composable
private fun AvatarDialogField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focus),
        singleLine = true,
        placeholder = { Text(placeholder, style = TextStyle(fontSize = 16.sp, color = cs.onSurface.copy(alpha = 0.45f))) },
        shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = semantic.surfaceFill,
            unfocusedContainerColor = semantic.surfaceFill,
            focusedBorderColor = withAlpha(cs.primary, 0.4),
            unfocusedBorderColor = Color.Transparent,
            cursorColor = cs.primary,
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Uri,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
    )
}

/** `gridHeight = (avail * 0.28).clamp(120, 220)` L1571-1573, IME inset included. */
@Composable
private fun emojiGridHeight(): Dp {
    val density = LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val imeBottom = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    return ((screenHeight - imeBottom) * 0.28f).coerceIn(120.dp, 220.dp)
}

/** Dart `characters.first` — the first extended grapheme cluster. */
internal fun firstGrapheme(value: String): String {
    if (value.isEmpty()) return value
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(value)
    val end = iterator.next()
    return if (end <= 0) value.substring(0, 1) else value.substring(0, end)
}

/** `validGrapheme` L1446-1449 — first grapheme of the raw input, and nothing else. */
internal fun isSingleGrapheme(value: String): Boolean {
    val first = firstGrapheme(value).trim()
    return first.isNotEmpty() && first == value.trim()
}

private val QqNumberPattern = Regex("^[0-9]{5,12}$")

/** `_inputQQAvatar` L1941. */
internal fun qqAvatarUrl(qq: String): String =
    "https://q2.qlogo.cn/headimg_dl?dst_uin=$qq&spec=100"

/** `_inputQQAvatar` random branch L1882-1886 gives each probe a 5s timeout. */
private val QqProbeClient = okhttp3.OkHttpClient.Builder()
    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
    .build()

/** Blocking, so callers run it on an IO dispatcher. */
internal fun qqAvatarResolves(url: String): Boolean = runCatching {
    QqProbeClient.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { resp ->
        resp.code == 200 && (resp.body?.bytes()?.isNotEmpty() ?: false)
    }
}.getOrDefault(false)

/**
 * `randomQQ()` L1793-1833 — length is drawn from a weighted table (10-digit
 * numbers dominate), then the leading digit from the real-world QQ ranges.
 */
internal fun randomQqNumber(random: Random = Random.Default): String {
    val lengths = intArrayOf(5, 6, 7, 8, 9, 10, 11)
    val lengthWeights = intArrayOf(1, 20, 80, 100, 500, 5000, 80)
    var roll = random.nextInt(lengthWeights.sum()) + 1
    var chosenLen = lengths.last()
    var acc = 0
    for (i in lengths.indices) {
        acc += lengthWeights[i]
        if (roll <= acc) {
            chosenLen = lengths[i]
            break
        }
    }
    val firstGroups = arrayOf(
        intArrayOf(1, 2),
        intArrayOf(3, 4),
        intArrayOf(5, 6, 7, 8),
        intArrayOf(9),
    )
    val firstWeights = intArrayOf(128, 4, 2, 1)
    var second = random.nextInt(firstWeights.sum()) + 1
    var group = firstGroups.last()
    var acc2 = 0
    for (i in firstGroups.indices) {
        acc2 += firstWeights[i]
        if (second <= acc2) {
            group = firstGroups[i]
            break
        }
    }
    val sb = StringBuilder()
    sb.append(group[random.nextInt(group.size)])
    for (i in 1 until chosenLen) sb.append(random.nextInt(10))
    return sb.toString()
}


/** assistant_settings_edit_basic_tab.dart L1451-1564. */
internal val QuickEmojis = listOf(
    "😀", "😁", "😂", "🤣", "😃", "😄",
    "😅", "😊", "😍", "😘", "😗", "😙",
    "😚", "🙂", "🤗", "🤩", "🫶", "🤝",
    "👍", "👎", "👋", "🙏", "💪", "🔥",
    "✨", "🌟", "💡", "🎉", "🎊", "🎈",
    "🌈", "☀️", "🌙", "⭐", "⚡", "☁️",
    "❄️", "🌧️", "🍎", "🍊", "🍋", "🍉",
    "🍇", "🍓", "🍒", "🍑", "🥭", "🍍",
    "🥝", "🍅", "🥕", "🌽", "🍞", "🧀",
    "🍔", "🍟", "🍕", "🌮", "🌯", "🍣",
    "🍜", "🍰", "🍪", "🍩", "🍫", "🍻",
    "☕", "🧋", "🥤", "⚽", "🏀", "🏈",
    "🎾", "🏐", "🎮", "🎧", "🎸", "🎹",
    "🎺", "📚", "✏️", "💼", "💻", "🖥️",
    "📱", "🛩️", "✈️", "🚗", "🚕", "🚙",
    "🚌", "🚀", "🛰️", "🧠", "🫀", "💊",
    "🩺", "🐶", "🐱", "🐭", "🐹", "🐰",
    "🦊", "🐻", "🐼", "🐨", "🐯", "🦁",
    "🐮", "🐷", "🐸", "🐵",
)

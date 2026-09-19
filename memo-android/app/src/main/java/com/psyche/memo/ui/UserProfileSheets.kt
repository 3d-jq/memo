package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.R as UiR
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 用户头像 / 昵称编辑 —— 原版 side_drawer.dart `_editAvatar`(3082-3174) 与
 * `_editUserName`(3735-3837)。
 *
 * 头像这一套（五选一 sheet、emoji 网格、链接、QQ）直接复用助手编辑页的
 * [AvatarPickerSheet] / [EmojiPickerDialog] / [AvatarUrlDialog] / [QQAvatarDialog]，
 * 只把文案切到 `side_drawer_*`（原版 side_drawer 是另写一份私有实现）。
 */
@Composable
internal fun UserAvatarEditor(
    store: UserProfileStore,
    open: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // sheet → 子弹窗的单向流转（原版同样是先 pop sheet 再开弹窗）。
    //
    // 关键：本组件必须**常驻组合**。AvatarPickerSheet 每一行都是「先 onDismiss
    // 再执行 action」，如果调用方用 if (open) 包住本组件，点「选图」会先卸载
    // 组件 → rememberLauncherForActivityResult 注销 → 系统选择器返回时没有接收
    // 方（emoji/链接/QQ 同样什么都不会弹出）。所以用 open + step 两个状态决定
    // 渲染，而不是让调用方决定挂载。
    var step by remember { mutableStateOf(Step.Sheet) }
    androidx.compose.runtime.LaunchedEffect(open) { if (open) step = Step.Sheet }

    // 子弹窗的取消/确认都要走这里：只调 onDismiss() 会把 open 置 false 而
    // step 仍停在子弹窗，下面的渲染条件继续成立 → 弹窗关不掉。
    fun closeEditor() {
        step = Step.Sheet
        onDismiss()
    }

    // `_pickLocalImage` L3693-3733 —— 相册选图后复制进应用目录（content://
    // URI 重启后失效），并按原版的 maxWidth 1024 / quality 90 压缩；失败时
    // 提示并降级到「输入链接」。
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) {
            onDismiss()
            return@rememberLauncherForActivityResult
        }
        scope.launch(Dispatchers.IO) {
            val saved = runCatching { copyIntoUserAvatars(context, uri) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (saved != null) {
                    store.setAvatarFilePath(saved)
                    closeEditor()
                } else {
                    SnackbarManager.show(
                        AppNotification(
                            message = context.getString(UiR.string.side_drawer_general_image_error),
                            type = NotificationType.ERROR,
                        ),
                    )
                    step = Step.Url
                }
            }
        }
    }

    // `_inputQQAvatar` 随机分支 L3522-3691：最多试 20 次，命中即用。
    fun probeRandomQqAvatar() {
        scope.launch(Dispatchers.IO) {
            var found: String? = null
            var tries = 0
            while (found == null && tries < 20) {
                tries++
                val candidate = qqAvatarUrl(randomQqNumber())
                if (qqAvatarResolves(candidate)) found = candidate
            }
            val url = found
            withContext(Dispatchers.Main) {
                if (url != null) {
                    store.setAvatarUrl(url)
                    closeEditor()
                } else {
                    SnackbarManager.show(
                        AppNotification(
                            message = context.getString(UiR.string.side_drawer_q_q_avatar_fetch_failed),
                            type = NotificationType.ERROR,
                        ),
                    )
                }
            }
        }
    }

    // open=false 且停在 sheet 步骤 → 完全空闲（用户取消 / 未打开）。
    // 子弹窗步骤不受 open 影响：sheet 关闭时把 step 切成 Emoji/Url/Qq，
    // 这时要继续渲染子弹窗。
    if (!open && step == Step.Sheet) return

    when (step) {
        Step.Sheet -> AvatarPickerSheet(
            strings = AvatarSheetStrings.User,
            onDismiss = onDismiss,
            onChooseImage = {
                pickImage.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onChooseEmoji = { step = Step.Emoji },
            onEnterLink = { step = Step.Url },
            onImportQq = { step = Step.Qq },
            onReset = {
                store.resetAvatar()
                onDismiss()
            },
        )

        Step.Emoji -> EmojiPickerDialog(
            strings = AvatarSheetStrings.User,
            onDismiss = { closeEditor() },
            onPick = { emoji ->
                store.setAvatarEmoji(emoji)
                closeEditor()
            },
        )

        Step.Url -> AvatarUrlDialog(
            strings = AvatarSheetStrings.User,
            onDismiss = { closeEditor() },
            onSave = { url ->
                store.setAvatarUrl(url)
                closeEditor()
            },
        )

        Step.Qq -> QQAvatarDialog(
            strings = AvatarSheetStrings.User,
            onDismiss = { closeEditor() },
            onApplyUrl = { url ->
                store.setAvatarUrl(url)
                closeEditor()
            },
            onRandom = { probeRandomQqAvatar() },
        )
    }
}

private enum class Step { Sheet, Emoji, Url, Qq }

/**
 * `_editUserName` L3735-3837 —— 昵称对话框：上限 24 字符、非空且与当前不同才可
 * 保存、右下角实时计数。
 */
@Composable
internal fun NicknameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var text by remember { mutableStateOf(initial) }
    val trimmed = text.trim()
    val canSave = trimmed.isNotEmpty() && trimmed != initial

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(UiR.string.side_drawer_set_nickname_title)) },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(semantic.surfaceFill, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (text.isEmpty()) {
                        Text(
                            text = stringResource(UiR.string.side_drawer_nickname_hint),
                            style = TextStyle(fontSize = 15.sp, color = cs.onSurface.copy(alpha = 0.45f)),
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { if (it.length <= NICKNAME_MAX_LENGTH) text = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 15.sp, color = cs.onSurface),
                        cursorBrush = SolidColor(cs.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${text.length}/$NICKNAME_MAX_LENGTH",
                    style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.5f)),
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(trimmed) }, enabled = canSave) {
                Text(
                    text = stringResource(UiR.string.side_drawer_save),
                    color = if (canSave) cs.primary else cs.onSurface.copy(alpha = 0.38f),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(UiR.string.side_drawer_cancel))
            }
        },
    )
}

private const val NICKNAME_MAX_LENGTH = 24

/** 压缩参数（对齐 Flutter 的 maxWidth1024/quality90）；目录与原版一致用 avatars/。 */
private const val USER_AVATAR_MAX_WIDTH_PX = 1024
private const val USER_AVATAR_QUALITY = 90

/**
 * 把选中的图片复制进 `filesDir/user_avatars/` —— `UserProvider.setAvatarFilePath`
 * L98-148 的 Android 等价物（解码 → 限宽缩放 → JPEG 重编码 → 返回新路径）。
 */
private fun copyIntoUserAvatars(context: Context, uri: android.net.Uri): String? {
    val dir = com.psyche.memo.AppDirs.avatars(context)
    val dest = File(dir, "avatar_${System.currentTimeMillis()}.jpg")
    val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input)
    } ?: return null
    val scaled = if (bitmap.width > USER_AVATAR_MAX_WIDTH_PX) {
        val ratio = USER_AVATAR_MAX_WIDTH_PX.toFloat() / bitmap.width
        Bitmap.createScaledBitmap(
            bitmap,
            USER_AVATAR_MAX_WIDTH_PX,
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        ).also { if (it !== bitmap) bitmap.recycle() }
    } else {
        bitmap
    }
    dest.outputStream().use { output ->
        scaled.compress(Bitmap.CompressFormat.JPEG, USER_AVATAR_QUALITY, output)
    }
    scaled.recycle()
    return dest.absolutePath
}

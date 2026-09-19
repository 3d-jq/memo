package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.psyche.memo.AppDirs
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.R as UiR

/**
 * 供应商头像编辑（`_editProviderAvatar` provider_detail_page L346-452 +
 * `_pickProviderIcon` / `_inputLobehubIcon` / `_inputProviderAvatarUrl`）：
 * 五行 —— 选择内置图标 / 输入 LobeHub 图标 / 相册 / 链接 / 重置。
 * 行样式沿用原版（48dp、r14、surface 底、按压换色）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderAvatarSheet(
    onPickBuiltInIcon: () -> Unit,
    onPickLobehubIcon: () -> Unit,
    onPickGallery: () -> Unit,
    onEnterLink: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        containerColor = semantic.overlaySurface(cs),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MemoSheetHandle(trailingGap = 0.dp)
            // 选项行统一成「更多」sheet 的卡片样式（用户 2026-09-12）。
            listOf(
                stringResource(UiR.string.provider_avatar_choose_built_in_icon) to onPickBuiltInIcon,
                stringResource(UiR.string.provider_avatar_input_lobehub_icon) to onPickLobehubIcon,
                stringResource(UiR.string.side_drawer_choose_image) to onPickGallery,
                stringResource(UiR.string.side_drawer_enter_link) to onEnterLink,
                stringResource(UiR.string.side_drawer_reset) to onReset,
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

/** `_pickProviderIcon` —— 可搜索的内置图标网格（BrandIconCatalog 59 项）。 */
@Composable
internal fun ProviderIconPickerDialog(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            BrandIconCatalog.icons
        } else {
            BrandIconCatalog.icons.filter {
                it.label.lowercase().contains(q) || it.id.contains(q)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
        title = { Text(stringResource(UiR.string.provider_avatar_icon_dialog_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(UiR.string.provider_avatar_icon_search_hint)) },
                    shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                if (filtered.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(UiR.string.provider_avatar_icon_no_results),
                            style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(filtered, key = { it.id }) { option ->
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .background(semantic.surfaceFill, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                                    .clickable { onPick(option.asset) },
                                contentAlignment = Alignment.Center,
                            ) {
                                ProviderAvatarSmall(
                                    providerKey = option.id,
                                    displayName = option.label,
                                    size = 36.dp,
                                    assetOverride = BrandIconCatalog.coilModel(option.asset),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.side_drawer_cancel)) }
        },
    )
}

/** 单个文本输入的弹窗（LobeHub 图标名 / 头像链接共用；`valid` 决定保存可用）。 */
@Composable
internal fun ProviderAvatarTextDialog(
    title: String,
    hint: String,
    initial: String = "",
    valid: (String) -> Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var value by remember { mutableStateOf(initial) }
    val ok = valid(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                placeholder = { Text(hint) },
                shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (ok) onConfirm(value.trim()) }, enabled = ok) {
                Text(
                    text = stringResource(UiR.string.side_drawer_save),
                    color = if (ok) cs.primary else cs.onSurface.copy(alpha = 0.38f),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.side_drawer_cancel)) }
        },
    )
}

/** 相册选的图拷进 `filesDir/avatars/provider_<key>.<ext>`（对齐原版头像目录）。 */
internal fun copyProviderAvatarFile(
    context: android.content.Context,
    providerKey: String,
    uri: android.net.Uri,
): String? = runCatching {
    val dir = AppDirs.avatars(context).apply { mkdirs() }
    val ext = context.contentResolver.getType(uri)?.substringAfterLast('/')?.takeIf { it.length in 2..5 } ?: "png"
    val target = java.io.File(dir, "provider_${providerKey.replace(Regex("[^A-Za-z0-9_-]"), "_")}.$ext")
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    } ?: return null
    target.absolutePath
}.getOrNull()

package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.FilePen
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.skill.SkillFileEntry
import com.psyche.memo.common.skill.SkillStore
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.withAlpha

/**
 * 技能详情 —— RikkaHub `SkillDetailPage.kt` 的功能子集：列出技能目录内的文件、编辑、
 * 新建、删除（`SKILL.md` 本身不可删）。**外壳按 Memo 既有风格**（`MemoTopBar` +
 * `SectionCard`），不搬上游的 `LargeFlexibleTopAppBar` + 会随滚动隐现的 FAB。
 */
@Composable
fun SkillDetailScreen(
    container: AppContainerImpl,
    skillName: String,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var reload by remember { mutableIntStateOf(0) }
    // 技能目录的文件遍历 + 目录存在性判断都是文件 IO，不能放组合期（§5.13）。
    val files = rememberLoaded(emptyList(), reload, skillName) { container.skillStore.listFiles(skillName) }
    val skillExists = rememberLoaded(false, reload, skillName) {
        container.skillStore.skillDir(skillName)?.isDirectory == true
    }

    var editing by remember { mutableStateOf<SkillFileEntry?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SkillFileEntry?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(cs.surface).statusBarsPadding()) {
        MemoTopBar(title = skillName, onBack = onBack) {
            IconActionButton(
                Lucide.Plus,
                cs.onSurface,
                stringResource(R.string.skill_detail_page_new_file),
            ) { creating = true }
            Spacer(Modifier.width(12.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        ) {
            if (!skillExists || files.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.skills_page_empty_title),
                        style = TextStyle(fontSize = 14.sp, color = cs.onSurfaceVariant),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    )
                }
            } else {
                item {
                    SectionCard {
                        files.forEachIndexed { index, entry ->
                            SkillFileRow(
                                entry = entry,
                                onEdit = { editing = entry },
                                onDelete = { deleteTarget = entry },
                            )
                            if (index != files.lastIndex) DividerRow()
                        }
                    }
                }
            }
        }
    }

    editing?.let { entry ->
        SkillFileEditor(
            title = entry.relativePath,
            // 读技能文件是文件 IO，不能放组合期（§5.13）。
            initialContent = rememberLoaded("", entry.relativePath, reload) {
                container.skillStore.resolveSkillFile(skillName, entry.relativePath)
                    ?.takeIf { it.isFile }?.readText().orEmpty()
            },
            confirmLabel = stringResource(R.string.skill_detail_page_save),
            onDismiss = { editing = null },
            onConfirm = { content ->
                if (container.skillStore.saveSkillFile(skillName, entry.relativePath, content)) {
                    editing = null
                    reload++
                } else {
                    SnackbarManager.show(
                        AppNotification(
                            message = container.appContext.getString(R.string.skill_detail_page_delete_failed),
                            type = NotificationType.ERROR,
                        ),
                    )
                }
            },
        )
    }

    if (creating) {
        var fileName by remember { mutableStateOf("") }
        var content by remember { mutableStateOf("") }
        val invalid = fileName.isNotBlank() && fileName.contains('\\')
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = { creating = false },
            title = { Text(stringResource(R.string.skill_detail_page_new_file)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = fileName,
                        onValueChange = { fileName = it },
                        label = { Text(stringResource(R.string.skill_detail_page_file_name)) },
                        placeholder = { Text("examples/basic.md", fontFamily = FontFamily.Monospace) },
                        singleLine = true,
                        isError = invalid,
                        supportingText = if (invalid) {
                            { Text(stringResource(R.string.skill_detail_page_file_name_invalid), color = cs.error) }
                        } else null,
                        textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text(stringResource(R.string.skill_detail_page_content)) },
                        minLines = 6,
                        maxLines = 14,
                        textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (container.skillStore.saveSkillFile(skillName, fileName.trim(), content)) {
                            creating = false
                            reload++
                        }
                    },
                    enabled = fileName.isNotBlank() && !invalid,
                ) {
                    Text(stringResource(R.string.skill_detail_page_create))
                }
            },
            dismissButton = {
                TextButton(onClick = { creating = false }) {
                    Text(stringResource(R.string.custom_theme_cancel))
                }
            },
        )
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.skill_detail_page_delete_file)) },
            text = { Text(stringResource(R.string.skill_detail_page_delete_confirm, entry.relativePath)) },
            confirmButton = {
                TextButton(onClick = {
                    val ok = container.skillStore.deleteSkillFile(skillName, entry.relativePath)
                    if (!ok) {
                        SnackbarManager.show(
                            AppNotification(
                                message = container.appContext.getString(R.string.skill_detail_page_delete_failed),
                                type = NotificationType.ERROR,
                            ),
                        )
                    }
                    deleteTarget = null
                    reload++
                }) {
                    Text(stringResource(R.string.custom_theme_delete), color = cs.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.custom_theme_cancel))
                }
            },
        )
    }
}

/** 文件行：按目录层级缩进 + 等宽路径 + 字节数 + 编辑/删除（SKILL.md 不给删）。 */
@Composable
private fun SkillFileRow(
    entry: SkillFileEntry,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (14 + entry.depth * 16).dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Lucide.FileText,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = cs.primary,
        )
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                text = entry.relativePath.substringAfterLast('/'),
                style = TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.depth > 0) {
                Text(
                    text = entry.relativePath,
                    style = TextStyle(fontSize = 11.sp, color = withAlpha(cs.onSurface, 0.5)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = "${entry.sizeBytes} B",
            style = TextStyle(fontSize = 11.sp, color = cs.onSurfaceVariant),
        )
        IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
            Icon(
                Lucide.FilePen,
                contentDescription = stringResource(R.string.custom_theme_edit_theme),
                modifier = Modifier.size(16.dp),
                tint = withAlpha(cs.onSurface, 0.7),
            )
        }
        // SKILL.md 是技能本体，删了整个技能就废了 —— 与上游一致：不给删。
        if (entry.relativePath != SkillStore.SKILL_MD) {
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Lucide.Trash2,
                    contentDescription = stringResource(R.string.skill_detail_page_delete_file),
                    modifier = Modifier.size(16.dp),
                    tint = cs.error,
                )
            }
        } else {
            Spacer(Modifier.width(32.dp))
        }
    }
}

/** 文件内容编辑器（等宽、多行）。 */
@Composable
private fun SkillFileEditor(
    title: String,
    initialContent: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var content by remember(title) { mutableStateOf(initialContent) }
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = onDismiss,
        title = { Text(title, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text(stringResource(R.string.skill_detail_page_content)) },
                minLines = 10,
                maxLines = 20,
                textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(content) }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.custom_theme_cancel)) }
        },
    )
}

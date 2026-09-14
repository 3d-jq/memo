package com.psyche.memo.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Puzzle
import com.composables.icons.lucide.Trash2
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.skill.SkillMetadata
import com.psyche.memo.provider.SkillImporter
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.withAlpha
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Agent Skills 管理页 —— RikkaHub `ui/pages/extensions/skills/SkillsPage.kt` 的功能子集，
 * **外壳按 Memo 既有风格**（`MemoTopBar` + `SectionCard` 行 + 长按操作面板，与
 * 搜索服务页/记忆页一致），不搬上游的 `LargeFlexibleTopAppBar` + `FloatingActionButton`。
 *
 * 技能本体在 `<filesDir>/skills/<名>/SKILL.md`；这里只做列表 / 手动添加 / 从文件导入 /
 * 删除。技能目录内的文件编辑在 [SkillDetailScreen]。
 *
 * 未移植：上游的「从 GitHub 导入」（网络 + Contents API 的独立能力，见 PORTING.md）。
 */
@Composable
fun SkillsScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var reload by remember { mutableIntStateOf(0) }
    val skills = remember(reload) { container.skillStore.listSkills() }

    var showAddSheet by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SkillMetadata?>(null) }

    val importFailedFmt = stringResource(R.string.skills_page_import_failed)

    // 进页面时清一次「幽灵技能名」：用户在 App 外删掉技能目录后，助手 enabledSkills 里
    // 的残留名会让技能开关显示成已启用却永远加载不到（上游 pruneOrphanedEnabledSkills）。
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val existing = container.skillStore.listSkills().map { it.name }.toSet()
            container.assistantStore.getAll().forEach { assistant ->
                val pruned = assistant.enabledSkills.filter { it in existing }
                if (pruned.size != assistant.enabledSkills.size) {
                    container.assistantStore.update(assistant.copy(enabledSkills = pruned))
                }
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val fileName = displayName(context, uri)
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("unreadable")
                    SkillImporter.import(container.skillStore, fileName, bytes)
                }
            }
            outcome
                .onSuccess { names ->
                    reload++
                    SnackbarManager.show(
                        AppNotification(
                            message = context.getString(R.string.skills_page_import_success, names.joinToString()),
                            type = NotificationType.SUCCESS,
                        ),
                    )
                }
                .onFailure { error ->
                    SnackbarManager.show(
                        AppNotification(
                            message = importFailedFmt.format(error.message ?: ""),
                            type = NotificationType.ERROR,
                        ),
                    )
                }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(cs.surface).statusBarsPadding()) {
        MemoTopBar(
            title = stringResource(R.string.skills_page_title),
            onBack = onBack,
        ) {
            IconActionButton(
                Lucide.Plus,
                cs.onSurface,
                stringResource(R.string.skills_page_add_title),
            ) { showAddSheet = true }
            Spacer(Modifier.width(12.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        ) {
            if (skills.isEmpty()) {
                item { SkillsEmptyState() }
            } else {
                item {
                    SectionCard {
                        skills.forEachIndexed { index, skill ->
                            SkillRow(
                                skill = skill,
                                onTap = { onOpenDetail(skill.name) },
                                onDelete = { deleteTarget = skill },
                            )
                            if (index != skills.lastIndex) DividerRow()
                        }
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        SkillAddSheet(
            onDismiss = { showAddSheet = false },
            onAddManually = { showAddSheet = false; showAddDialog = true },
            onImportFromFile = {
                showAddSheet = false
                filePicker.launch(arrayOf("text/*", "application/zip", "application/octet-stream"))
            },
        )
    }

    if (showAddDialog) {
        AddSkillDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, content ->
                // saveSkill 先校验 frontmatter 再落盘，失败时磁盘上不会留垃圾目录。
                val saved = container.skillStore.saveSkill(name, content)
                if (saved == null) {
                    SnackbarManager.show(
                        AppNotification(
                            message = context.getString(R.string.skills_page_save_failed),
                            type = NotificationType.ERROR,
                        ),
                    )
                } else {
                    showAddDialog = false
                    reload++
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.skills_page_delete_title)) },
            text = { Text(stringResource(R.string.skills_page_delete_message, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    container.skillStore.deleteSkill(target.name)
                    // 同步清掉所有助手上对这个技能的引用（上游 deleteSkill 里的
                    // settingsStore.update 段）。
                    container.assistantStore.getAll().forEach { assistant ->
                        if (target.name in assistant.enabledSkills) {
                            container.assistantStore.update(
                                assistant.copy(enabledSkills = assistant.enabledSkills - target.name),
                            )
                        }
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

@Composable
private fun SkillsEmptyState() {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Lucide.Puzzle,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = cs.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.skills_page_empty_title),
            style = TextStyle(fontSize = 15.sp, color = cs.onSurfaceVariant),
        )
        Text(
            text = stringResource(R.string.skills_page_empty_hint),
            style = TextStyle(fontSize = 12.sp, color = withAlpha(cs.onSurfaceVariant, 0.8)),
            textAlign = TextAlign.Center,
        )
    }
}

/** 技能行：图标 + 名字 + 描述（两行）；点进详情，长按出操作面板。 */
@Composable
private fun SkillRow(
    skill: SkillMetadata,
    onTap: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var showActions by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = { showActions = true })
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Lucide.Puzzle,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = cs.primary,
        )
        Column(
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = skill.name,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = skill.description,
                style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = withAlpha(cs.onSurface, 0.62)),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            skill.compatibility?.takeIf { it.isNotBlank() }?.let { compatibility ->
                Text(
                    text = compatibility,
                    style = TextStyle(fontSize = 11.sp, color = cs.tertiary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Lucide.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = withAlpha(cs.onSurface, 0.35),
        )
    }

    if (showActions) {
        ActionSheet(
            onDismiss = { showActions = false },
            actions = listOf(
                SheetAction(
                    icon = Lucide.Trash2,
                    label = stringResource(R.string.skills_page_delete_title),
                    destructive = true,
                ) { showActions = false; onDelete() },
            ),
        )
    }
}

/** 添加方式选择（沿用 Memo 的底部面板样式）。 */
@Composable
private fun SkillAddSheet(
    onDismiss: () -> Unit,
    onAddManually: () -> Unit,
    onImportFromFile: () -> Unit,
) {
    ActionSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.skills_page_add_title),
        actions = listOf(
            SheetAction(Lucide.Plus, stringResource(R.string.skills_page_add_manually)) { onAddManually() },
            SheetAction(Lucide.Download, stringResource(R.string.skills_page_import_from_file)) { onImportFromFile() },
        ),
    )
}

/**
 * 粘贴一份 `SKILL.md` 新建技能。名字从 frontmatter 里现解 —— 与上游一致：用户不填名字，
 * 少了 `name` 就当场报错并禁止保存。
 */
@Composable
private fun AddSkillDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, content: String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var content by remember { mutableStateOf("") }
    val name = remember(content) {
        com.psyche.memo.common.skill.SkillFrontmatterParser.parse(content)["name"]?.trim().orEmpty()
    }
    val nameError = content.isNotBlank() && name.isBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skills_page_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.skills_page_skill_content_label)) },
                    placeholder = {
                        Text(
                            text = "---\nname: my-skill\ndescription: \"...\"\n---\n\n指令内容...",
                            fontFamily = FontFamily.Monospace,
                        )
                    },
                    isError = nameError,
                    minLines = 8,
                    maxLines = 14,
                    textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = when {
                        nameError -> stringResource(R.string.skills_page_name_error)
                        name.isNotBlank() -> stringResource(R.string.skills_page_skill_name, name)
                        else -> stringResource(R.string.skills_page_paste_hint)
                    },
                    style = TextStyle(fontSize = 11.sp, color = if (nameError) cs.error else cs.onSurfaceVariant),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, content) },
                enabled = name.isNotBlank() && !nameError,
            ) {
                Text(stringResource(R.string.skills_page_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.custom_theme_cancel)) }
        },
    )
}

/** `OpenableColumns.DISPLAY_NAME`（上游 `FileUtils.getFileNameFromUri` 的等价物）。 */
private fun displayName(context: Context, uri: Uri): String {
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0) ?: ""
            }
    }
    return uri.lastPathSegment.orEmpty()
}

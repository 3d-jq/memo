package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import com.composables.icons.lucide.Github
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Puzzle
import com.composables.icons.lucide.Trash2
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.provider.SkillGitHubImporter
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
 * 技能本体在 `<filesDir>/skills/<名>/SKILL.md`；这里做列表 / 手动添加 / 从文件导入 /
 * 从 GitHub 仓库导入 / 删除。技能目录内的文件编辑在 [SkillDetailScreen]。
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
    var showGitHubDialog by remember { mutableStateOf(false) }
    // 待删技能名（原来存整个 SkillMetadata：对话框只用到 name，而行需要「稳定实参」
    // 才能跳过重组，名字既是目录名也是唯一键）。
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    val openDetailRef = rememberUpdatedState(onOpenDetail)
    // 行动作只造一次：以前每行现造两个 lambda ⇒ 整列每帧都被判「参数变了」。
    val rowActions = remember {
        SkillRowActions(
            open = { name -> openDetailRef.value(name) },
            delete = { name -> deleteTarget = name },
        )
    }

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
                // 卡片仍是**一张** SectionCard（原版形态：一组行共用一个圆角面），
                // 所以这里不做成 items(...)：拆成多个 lazy item 会把卡片切成一段一段
                // 的圆角+描边，外观就变了。取而代之的是两件等价的事：
                //  · `key(技能名)` —— 行的组合身份跟着技能走，删掉一个技能不会让
                //    下面每一行继承上一行的 `showActions`（以前按下标记忆，会串）；
                //  · 行实参全稳定（三个字符串 + 一个记住的动作对象）⇒ 行可跳过重组，
                //    页面任何无关状态变化（弹层开关、reload）不再重组合整列。
                item {
                    SectionCard {
                        skills.forEachIndexed { index, skill ->
                            key(skill.name) {
                                SkillRow(
                                    name = skill.name,
                                    description = skill.description,
                                    compatibility = skill.compatibility,
                                    actions = rowActions,
                                )
                            }
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
            onImportFromGitHub = { showAddSheet = false; showGitHubDialog = true },
        )
    }

    if (showGitHubDialog) {
        // 网络拉取走容器的 OkHttp ⇒ 全局代理设置对 GitHub 导入同样生效。
        ImportGitHubDialog(
            onDismiss = { showGitHubDialog = false },
            onImport = { url ->
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        runCatching {
                            SkillGitHubImporter(
                                store = container.skillStore,
                                fetch = SkillGitHubImporter.okHttpFetcher(container.httpClient),
                            ).import(url)
                        }
                    }
                    outcome
                        .onSuccess { names ->
                            reload++
                            SnackbarManager.show(
                                AppNotification(
                                    message = context.getString(
                                        R.string.skills_page_import_success,
                                        names.joinToString(),
                                    ),
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
                    showGitHubDialog = false
                }
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

    deleteTarget?.let { targetName ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.skills_page_delete_title)) },
            text = { Text(stringResource(R.string.skills_page_delete_message, targetName)) },
            confirmButton = {
                TextButton(onClick = {
                    container.skillStore.deleteSkill(targetName)
                    // 同步清掉所有助手上对这个技能的引用（上游 deleteSkill 里的
                    // settingsStore.update 段）。
                    container.assistantStore.getAll().forEach { assistant ->
                        if (targetName in assistant.enabledSkills) {
                            container.assistantStore.update(
                                assistant.copy(enabledSkills = assistant.enabledSkills - targetName),
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

/**
 * 技能行的两个动作。提成 `@Stable` 对象（实例在页面存活期内不变）是为了让
 * [SkillRow] 的实参全部稳定 —— 见上面列表处 `key(...)` 的说明。
 */
@Stable
private class SkillRowActions(
    val open: (String) -> Unit,
    val delete: (String) -> Unit,
)

/** 技能行：图标 + 名字 + 描述（两行）；点进详情，长按出操作面板。 */
@Composable
private fun SkillRow(
    name: String,
    description: String,
    compatibility: String?,
    actions: SkillRowActions,
) {
    val cs = MaterialTheme.colorScheme
    var showActions by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { actions.open(name) },
                onLongClick = { showActions = true },
            )
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
            // ⓘ 紧贴技能名（TipHuggingLabel 是 RowScope 扩展，所以这里套一层 Row）。
            Row(verticalAlignment = Alignment.CenterVertically) {
                TipHuggingLabel(
                    label = name,
                    tip = description,
                    labelStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
            }
            compatibility?.takeIf { it.isNotBlank() }?.let { compatibility ->
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
                ) { showActions = false; actions.delete(name) },
            ),
        )
    }
}

/** 添加方式选择 —— 全站统一的操作面板样式（无标题，见 [ActionSheet]）。 */
@Composable
private fun SkillAddSheet(
    onDismiss: () -> Unit,
    onAddManually: () -> Unit,
    onImportFromFile: () -> Unit,
    onImportFromGitHub: () -> Unit,
) {
    ActionSheet(
        onDismiss = onDismiss,
        actions = listOf(
            SheetAction(Lucide.Plus, stringResource(R.string.skills_page_add_manually)) { onAddManually() },
            SheetAction(Lucide.Download, stringResource(R.string.skills_page_import_from_file)) { onImportFromFile() },
            SheetAction(Lucide.Github, stringResource(R.string.skills_page_import_github)) { onImportFromGitHub() },
        ),
    )
}

/**
 * 从 GitHub 仓库导入（上游 `ImportSkillDialog`）：一句说明 + 仓库 URL + 下载中指示。
 * 下载期间禁用确认与取消，避免半途关掉。
 */
@Composable
private fun ImportGitHubDialog(
    onDismiss: () -> Unit,
    onImport: (url: String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var url by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text(stringResource(R.string.skills_page_import_github)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.skills_page_import_description),
                    style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.skills_page_repo_url_label)) },
                    placeholder = {
                        Text("https://github.com/owner/repo", fontFamily = FontFamily.Monospace)
                    },
                    supportingText = { Text(stringResource(R.string.skills_page_repo_url_hint)) },
                    singleLine = true,
                    enabled = !loading,
                    textStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (loading) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.skills_page_downloading),
                            style = TextStyle(fontSize = 12.sp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    loading = true
                    onImport(url)
                },
                enabled = url.isNotBlank() && !loading,
            ) {
                Text(stringResource(R.string.skills_page_import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) {
                Text(stringResource(R.string.custom_theme_cancel))
            }
        },
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
        containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skills_page_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 提示词不裸排 —— 走全站统一的 ⓘ 浮动气泡（SettingsTipIcon）。
                // 只有**校验反馈**（缺 name / 解析出的技能名）才直接显示。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.skills_page_skill_content_label),
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    )
                    SettingsTipIcon(stringResource(R.string.skills_page_paste_hint))
                }
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
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
                val feedback = when {
                    nameError -> stringResource(R.string.skills_page_name_error)
                    name.isNotBlank() -> stringResource(R.string.skills_page_skill_name, name)
                    else -> null
                }
                if (feedback != null) {
                    Text(
                        text = feedback,
                        style = TextStyle(
                            fontSize = 11.sp,
                            color = if (nameError) cs.error else cs.onSurfaceVariant,
                        ),
                    )
                }
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

package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import com.psyche.memo.common.logging.ContextLogMessage
import com.psyche.memo.common.logging.ContextLogSnapshot
import com.psyche.memo.common.logging.ContextLogTailCursor
import com.psyche.memo.common.logging.ContextLogTailReader
import com.psyche.memo.common.logging.ContextSegment
import com.psyche.memo.common.logging.ContextSource
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ArrowDown
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.BadgeInfo
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.FileClock
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.FileQuestion
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Hash
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.ListTree
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessagesSquare
import com.composables.icons.lucide.Paperclip
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Terminal
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.logging.LogPayloadRef
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.draw.clipToBounds
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.CircleX
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 1:1 port of lib/features/settings/pages/log_viewer_page.dart (mobile log
 * viewer): three tabs (context logs / request logs / app logs), the log-files
 * list, the plain-text viewer, the request-log list + detail pages, the
 * context snapshot pages, and the log settings sheet (log_save_output_v1 /
 * log_elide_large_payloads_v1 / log_auto_delete_days_v1 / log_max_size_mb_v1).
 */
private const val ACTIVE_REQUEST_LOG = "logs.txt"
private const val ACTIVE_APP_LOG = "flutter_logs.txt"
private const val ACTIVE_CONTEXT_LOG = "context_logs.txt"

private data class LogFileEntry(val path: String, val name: String, val size: Long, val modified: Long)

@Composable
fun LogViewerScreen(
    container: AppContainerImpl,
    /** `LogViewerPage.initialTab`（context=0 / request=1 / app=2）。 */
    initialTab: Int = 0,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var requestFiles by remember { mutableStateOf<List<LogFileEntry>>(emptyList()) }
    var appFiles by remember { mutableStateOf<List<LogFileEntry>>(emptyList()) }
    var contextFiles by remember { mutableStateOf<List<LogFileEntry>>(emptyList()) }

    // L72-135 — scan <filesDir>/logs and bucket by file-name prefix.
    fun loadLogFiles() {
        loading = true
        scope.launch {
            val (req, app, ctx) = withContext(Dispatchers.IO) { scanLogFiles(context) }
            requestFiles = req; appFiles = app; contextFiles = ctx
            loading = false
        }
    }
    LaunchedEffect(Unit) { loadLogFiles() }

    var settingsSheetVisible by remember { mutableStateOf(false) }
    var openFile by remember { mutableStateOf<LogFileEntry?>(null) }
    var openFileTitle by remember { mutableStateOf("") }
    var openTab by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
                MemoTopBar(
                    title = stringResource(UiR.string.storage_space_category_logs),
                    onBack = onBack,
                ) {
                    TopBarAction(
                        icon = Lucide.RefreshCw,
                        label = stringResource(UiR.string.log_viewer_refresh),
                        onClick = { loadLogFiles() },
                    )
                    TopBarAction(
                        icon = Lucide.Settings,
                        label = stringResource(UiR.string.log_settings_title),
                        onClick = { settingsSheetVisible = true },
                    )
                }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator()
            }
        } else {
            LogViewerTabsAndPages(
                container = container,
                initialTab = initialTab,
                requestFiles = requestFiles,
                appFiles = appFiles,
                contextFiles = contextFiles,
                onOpenFile = { tab, file, title ->
                    openTab = tab
                    openFileTitle = title
                    openFile = file
                },
            )
        }
    }

    openFile?.let { file ->
        when (openTab) {
            0 -> ContextLogFileOverlay(container, file, openFileTitle) { openFile = null }
            1 -> RequestLogFileOverlay(container, file, openFileTitle) { openFile = null }
            else -> PlainLogContentOverlay(file, openFileTitle) { openFile = null }
        }
    }

    if (settingsSheetVisible) {
        LogSettingsSheet(
            container = container,
            onDismiss = { settingsSheetVisible = false },
            onChanged = { loadLogFiles() },
        )
    }
}

// —— Tab bar + files lists ——————————————————————————————————————————————

private fun scanLogFiles(context: android.content.Context): Triple<List<LogFileEntry>, List<LogFileEntry>, List<LogFileEntry>> {
    val logsDir = java.io.File(context.filesDir, "logs")
    if (!logsDir.exists()) return Triple(emptyList(), emptyList(), emptyList())
    val all = logsDir.listFiles()?.filter { it.isFile && it.name.lowercase().endsWith(".txt") } ?: emptyList()
    val request = mutableListOf<LogFileEntry>()
    val app = mutableListOf<LogFileEntry>()
    val contextFiles = mutableListOf<LogFileEntry>()
    for (f in all) {
        val name = f.name.lowercase()
        val entry = LogFileEntry(f.absolutePath, f.name, f.length(), f.lastModified())
        when {
            name.startsWith("context_logs") -> contextFiles.add(entry)
            name.startsWith("flutter_logs") -> app.add(entry)
            name.startsWith("logs") -> request.add(entry)
            else -> app.add(entry) // "other" logs keep the simpler viewer
        }
    }
    fun sortByMtimeDesc(list: MutableList<LogFileEntry>) = list.sortByDescending { it.modified }
    sortByMtimeDesc(request); sortByMtimeDesc(app); sortByMtimeDesc(contextFiles)
    return Triple(request, app, contextFiles)
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

private fun formatDate(dt: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = dt }
    fun two(v: Int) = v.toString().padStart(2, '0')
    return "${cal.get(java.util.Calendar.YEAR)}-${two(cal.get(java.util.Calendar.MONTH) + 1)}-${two(cal.get(java.util.Calendar.DAY_OF_MONTH))} " +
        "${two(cal.get(java.util.Calendar.HOUR_OF_DAY))}:${two(cal.get(java.util.Calendar.MINUTE))}"
}

private fun fmtTime(dt: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = dt }
    fun two(v: Int) = v.toString().padStart(2, '0')
    return "${two(cal.get(java.util.Calendar.HOUR_OF_DAY))}:${two(cal.get(java.util.Calendar.MINUTE))}:${two(cal.get(java.util.Calendar.SECOND))}"
}

private fun fmtTimestamp(dt: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = dt }
    fun two(v: Int) = v.toString().padStart(2, '0')
    fun three(v: Int) = v.toString().padStart(3, '0')
    return "${cal.get(java.util.Calendar.YEAR)}-${two(cal.get(java.util.Calendar.MONTH) + 1)}-${two(cal.get(java.util.Calendar.DAY_OF_MONTH))} " +
        "${two(cal.get(java.util.Calendar.HOUR_OF_DAY))}:${two(cal.get(java.util.Calendar.MINUTE))}:${two(cal.get(java.util.Calendar.SECOND))}.${three(cal.get(java.util.Calendar.MILLISECOND))}"
}

private fun fmtDuration(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "${ms / 1000}s"
    else -> "${ms / 60000}m ${(ms % 60000) / 1000}s"
}

private fun fmtBytes(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

private fun fmtDateTimeSeconds(dt: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = dt }
    fun two(v: Int) = v.toString().padStart(2, '0')
    return "${cal.get(java.util.Calendar.YEAR)}-${two(cal.get(java.util.Calendar.MONTH) + 1)}-${two(cal.get(java.util.Calendar.DAY_OF_MONTH))} " +
        "${two(cal.get(java.util.Calendar.HOUR_OF_DAY))}:${two(cal.get(java.util.Calendar.MINUTE))}:${two(cal.get(java.util.Calendar.SECOND))}"
}

/** L1990-1999 — "OpenAI - DeepSeek" style keys show the name only. */
private fun displayProviderName(raw: String): String =
    raw.trim().replace(Regex("^(OpenAI|Google|Claude)\\s*-\\s*", RegexOption.IGNORE_CASE), "").trim()

private fun trimSurroundingBlankLines(text: String): String =
    text.replaceFirst(Regex("^(\\s*\\n)+"), "").replaceFirst(Regex("(\\n\\s*)+$"), "")

// —— Tab bar + files lists ——————————————————————————————————————————————

@Composable
private fun LogViewerTabsAndPages(
    container: AppContainerImpl,
    initialTab: Int,
    requestFiles: List<LogFileEntry>,
    appFiles: List<LogFileEntry>,
    contextFiles: List<LogFileEntry>,
    onOpenFile: (tab: Int, file: LogFileEntry, title: String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val pagerState = rememberPagerState(initialPage = initialTab.coerceIn(0, 2)) { 3 }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val zh = java.util.Locale.getDefault().language.lowercase() == "zh"
    val appTabLabel = if (zh) {
        // flutterLogSettingTitle with the trailing 打印/列印 stripped (L170-175).
        stringResource(UiR.string.flutter_log_setting_title).replace(Regex("(打印|列印)$"), "")
    } else {
        stringResource(UiR.string.storage_space_sub_logs_flutter)
    }

    Column(Modifier.fillMaxSize()) {
        // _SegTabBar L2798-2921 (simplified press handling, same geometry).
        Box(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 10.dp)) {
            val tabs = listOf(
                stringResource(UiR.string.context_log_viewer_title),
                stringResource(UiR.string.log_viewer_title),
                appTabLabel,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(cs.surfaceCardColorCompat(), RoundedCornerShape(MemoRadius.CARD_DP.dp))
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, label ->
                    val selected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .background(
                                if (selected) cs.primary.copy(alpha = 0.14f) else Color.Transparent,
                                RoundedCornerShape(MemoRadius.INNER_DP.dp),
                            )
                            .clickable { scope.launch { pagerState.animateScrollToPage(index) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-0.1).sp,
                                color = if (selected) cs.primary else cs.onSurface.copy(alpha = 0.82f),
                            ),
                        )
                    }
                }
            }
        }
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> LogFilesList(
                    files = contextFiles,
                    activeFileName = ACTIVE_CONTEXT_LOG,
                    emptyIcon = Lucide.MessagesSquare,
                    onOpen = { f, t -> onOpenFile(0, f, t) },
                )
                1 -> LogFilesList(
                    files = requestFiles,
                    activeFileName = ACTIVE_REQUEST_LOG,
                    emptyIcon = Lucide.Globe,
                    onOpen = { f, t -> onOpenFile(1, f, t) },
                )
                else -> LogFilesList(
                    files = appFiles,
                    activeFileName = ACTIVE_APP_LOG,
                    emptyIcon = Lucide.Terminal,
                    onOpen = { f, t -> onOpenFile(2, f, t) },
                )
            }
        }
    }
}

/** _LogFilesList L275-405 + _FileIcon L407-435. */
@Composable
private fun LogFilesList(
    files: List<LogFileEntry>,
    activeFileName: String,
    emptyIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onOpen: (LogFileEntry, String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val lum = 0.2126f * cs.surface.red + 0.7152f * cs.surface.green + 0.0722f * cs.surface.blue
    val isDark = lum < 0.5f

    if (files.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(emptyIcon, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.28f), modifier = Modifier.size(46.dp))
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(UiR.string.log_viewer_empty),
                    style = TextStyle(fontWeight = FontWeight.Medium, color = cs.onSurface.copy(alpha = 0.62f)),
                )
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 16.dp),
    ) {
        items(files.size, key = { files[it].path }) { index ->
            val file = files[index]
            val isCurrentLog = file.name.lowercase() == activeFileName.lowercase()
            val title = if (isCurrentLog) stringResource(UiR.string.log_viewer_current_log) else file.name
            val subtitle = "${formatFileSize(file.size)} · ${formatDate(file.modified)}"
            Row(
                modifier = Modifier
                    .padding(bottom = 10.dp)
                    .fillMaxWidth()
                    .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                    .border(
                        1.dp,
                        cs.outlineVariant.copy(alpha = if (isDark) 0.26f else 0.38f),
                        RoundedCornerShape(MemoRadius.INNER_DP.dp),
                    )
                    .clickable { onOpen(file, title) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            if (isCurrentLog) cs.primary.copy(alpha = if (isDark) 0.22f else 0.14f) else semantic.surfaceFill,
                            RoundedCornerShape(MemoRadius.INNER_DP.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isCurrentLog) Lucide.FileText else Lucide.FileClock,
                        contentDescription = null,
                        tint = if (isCurrentLog) cs.primary else cs.onSurface.copy(alpha = 0.72f),
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            fontWeight = if (isCurrentLog) FontWeight.SemiBold else FontWeight.SemiBold,
                            color = cs.onSurface.copy(alpha = 0.92f),
                            letterSpacing = (-0.2).sp,
                        ),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.58f)),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Icon(Lucide.ChevronRight, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.30f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

// —— Plain log viewer (app tab) ————————————————————————————————————————

/** _PlainLogContentPage L437-535 — full-screen overlay with export. */
/**
 * 复制到剪贴板。Compose 1.8 起 `LocalClipboardManager` 已废弃，统一走
 * `LocalClipboard` + `ClipEntry`（`setClipEntry` 是 suspend，所以借组合作用域 launch）。
 */
private fun copyToClipboard(
    scope: kotlinx.coroutines.CoroutineScope,
    clipboard: androidx.compose.ui.platform.Clipboard,
    text: String,
) {
    scope.launch {
        clipboard.setClipEntry(
            androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("", text)),
        )
    }
}

@Composable
private fun PlainLogContentOverlay(file: LogFileEntry, title: String, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val exportFailedText = stringResource(com.psyche.memo.ui.R.string.log_viewer_export_failed)
    val context = LocalContext.current
    var content by remember(file.path) { mutableStateOf("") }
    var loading by remember(file.path) { mutableStateOf(true) }
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val clipboardScope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(file.path) {
        content = withContext(Dispatchers.IO) {
            runCatching { java.io.File(file.path).readText() }.getOrElse { "Error loading file: $it" }
        }
        loading = false
    }
    OverlayScaffold(
        title = title,
        onClose = onClose,
        actions = {
            TopBarAction(
                icon = Lucide.Share2,
                label = stringResource(UiR.string.log_viewer_export),
                onClick = {
                    runCatching {
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_SUBJECT, file.name)
                            putExtra(android.content.Intent.EXTRA_STREAM, shareUri(file))
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        UiBridge.startActivity(android.content.Intent.createChooser(intent, file.name))
                    }.onFailure {
                        SnackbarManager.show(AppNotification(message = exportFailedText.format(it.toString()), type = NotificationType.ERROR))
                    }
                },
            )
            TopBarAction(
                icon = Lucide.Copy,
                label = stringResource(UiR.string.log_viewer_copy),
                onClick = {
                    copyToClipboard(clipboardScope, clipboard, content)
                    SnackbarManager.show(AppNotification(
                        message = context.getString(UiR.string.chat_message_widget_copied_to_clipboard),
                        type = NotificationType.SUCCESS,
                    ))
                },
            )
        },
    ) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator()
            }
            content.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(UiR.string.log_viewer_empty), style = TextStyle(color = cs.onSurface.copy(alpha = 0.6f)))
            }
            else -> SelectionContainerText(content)
        }
    }
}

@Composable
private fun SelectionContainerText(content: String) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(
            text = content,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.4.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            ),
        )
    }
}

/** Minimal bridge so overlays can launch the share chooser. */
private object UiBridge {
    fun startActivity(intent: android.content.Intent) {
        val activity = currentActivity ?: return
        activity.startActivity(intent)
    }
    var currentActivity: android.app.Activity? = null
}

private fun shareUri(file: LogFileEntry): android.net.Uri {
    val paths = listOf(file.path)
    return androidx.core.content.FileProvider.getUriForFile(
        requireNotNull(UiBridge.currentActivity),
        "${requireNotNull(UiBridge.currentActivity).packageName}.fileprovider",
        java.io.File(file.path),
    )
}

// —— Shared overlay scaffold (full-screen "pushed page" look) ——————————

/**
 * Full-screen overlay shell for the file/snapshot/detail pages. The top bar is
 * the standard [MemoTopBar] so its back-button slot (56dp leading + 16dp spacer)
 * and 22dp ArrowLeft match every other page in the app — without this the
 * overlay felt slightly off (44dp slot, no spacer) and the action icons were
 * 20dp instead of the standard 22dp.
 *
 * **系统返回键必须在这里拦掉**：overlay 是「同屏二级页」（不是新的导航目的地，
 * 只是叠在列表上的一层 composable），不拦的话手机返回键会直接 pop 掉整个路由 ——
 * 从日志文件页返回直接掉回主设置页（用户 2026-09-15「二级界面返回不是上一个界面，
 * 直接返回主设置界面」，点名的就是日志这些界面）。按返回应当先回到列表。
 * [androidx.compose.ui.window.Dialog] 形态的覆盖层（如图片查看器）不需要这个，
 * 系统返回由 Dialog 自己收掉。
 */
@Composable
internal fun OverlayScaffold(
    title: String,
    onClose: () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    OverlayBackHandler(onClose)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        MemoTopBar(
            title = title,
            onBack = onClose,
        ) {
            actions()
        }
        content()
    }
}

// —— Request log file page ——————————————————————————————————————————————

/** _RequestLogFilePage L546-697 + summary bar + cards. */
@Composable
private fun RequestLogFileOverlay(container: AppContainerImpl, file: LogFileEntry, title: String, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val exportFailedText = stringResource(com.psyche.memo.ui.R.string.log_viewer_export_failed)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var loading by remember(file.path) { mutableStateOf(true) }
    var requests by remember(file.path) { mutableStateOf<List<RequestLogEntry>>(emptyList()) }
    var detail by remember { mutableStateOf<RequestLogEntry?>(null) }

    fun load() {
        loading = true
        val elide = runCatching {
            container.preferenceRepository.readJson("log_elide_large_payloads_v1")
                ?.let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonPrimitive.booleanOrNull }
        }.getOrNull() ?: true
        scope.launch {
            requests = withContext(Dispatchers.IO) {
                runCatching {
                    RequestLogParser.parse(java.io.File(file.path).readText(), elide = elide)
                }.getOrDefault(emptyList())
            }
            loading = false
        }
    }
    LaunchedEffect(file.path) { load() }

    OverlayScaffold(
        title = title,
        onClose = onClose,
        actions = {
            TopBarAction(
                icon = Lucide.RefreshCw,
                label = stringResource(UiR.string.log_viewer_refresh),
                onClick = { load() },
            )
            TopBarAction(
                icon = Lucide.Share2,
                label = stringResource(UiR.string.log_viewer_export),
                onClick = { shareLogFile(file, exportFailedText) },
            )
        },
    ) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator()
            }
            requests.isEmpty() -> RequestEmptyState()
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 16.dp),
            ) {
                item {
                    val errorCount = requests.count { it.hasError }
                    val warnCount = requests.count { it.hasWarning }
                    RequestSummaryBar(total = requests.size, errors = errorCount, warnings = warnCount)
                    Spacer(Modifier.height(12.dp))
                }
                items(requests.size, key = { requests[it].sequence }) { index ->
                    val e = requests[index]
                    Row(Modifier.padding(bottom = 10.dp)) {
                        RequestLogCard(entry = e, onTap = { detail = e })
                    }
                }
            }
        }
    }
    detail?.let { RequestLogDetailOverlay(it) { detail = null } }
}

private fun shareLogFile(file: LogFileEntry, exportFailedText: String) {
    runCatching {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, file.name)
            putExtra(android.content.Intent.EXTRA_STREAM, shareUri(file))
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        UiBridge.startActivity(android.content.Intent.createChooser(intent, file.name))
    }.onFailure {
        SnackbarManager.show(AppNotification(message = exportFailedText.format(it.toString()), type = NotificationType.ERROR))
    }
}

@Composable
private fun RequestEmptyState() {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Lucide.FileQuestion, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.28f), modifier = Modifier.size(46.dp))
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(UiR.string.log_viewer_empty),
                style = TextStyle(fontWeight = FontWeight.Medium, color = cs.onSurface.copy(alpha = 0.62f)),
            )
        }
    }
}

/** _RequestLogSummaryBar L1558-1626 + _CountPill L1628-1666. */
@Composable
private fun RequestSummaryBar(total: Int, errors: Int, warnings: Int) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val lum = 0.2126f * cs.surface.red + 0.7152f * cs.surface.green + 0.0722f * cs.surface.blue
    val isDark = lum < 0.5f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(1.dp, cs.outlineVariant.copy(alpha = if (isDark) 0.26f else 0.38f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(UiR.string.log_viewer_requests_count, total.toString()),
            style = TextStyle(fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.90f), letterSpacing = (-0.2).sp),
            modifier = Modifier.weight(1f),
        )
        if (warnings > 0) {
            CountPill(
                icon = Lucide.BadgeInfo,
                label = warnings.toString(),
                bg = cs.tertiaryContainer.copy(alpha = if (isDark) 0.50f else 0.55f),
                fg = cs.onTertiaryContainer.copy(alpha = 0.92f),
            )
            Spacer(Modifier.width(8.dp))
        }
        if (errors > 0) {
            CountPill(
                icon = Lucide.CircleX,
                label = errors.toString(),
                bg = androidx.compose.ui.graphics.lerp(semantic.surfaceCard, cs.error, if (isDark) 0.18f else 0.12f),
                fg = cs.error.copy(alpha = if (isDark) 0.92f else 0.88f),
            )
        }
    }
}

@Composable
private fun CountPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, bg: Color, fg: Color) {
    Row(
        modifier = Modifier
            .background(bg, RoundedCornerShape(MemoRadius.PILL_DP.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = TextStyle(fontWeight = FontWeight.SemiBold, color = fg, fontSize = 12.sp, letterSpacing = (-0.1).sp))
    }
}

/** _RequestLogCard L1668-1769 + _MethodPill/_StatusPill/_InlineErrorPreview. */
@Composable
private fun RequestLogCard(entry: RequestLogEntry, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val lum = 0.2126f * cs.surface.red + 0.7152f * cs.surface.green + 0.0722f * cs.surface.blue
    val isDark = lum < 0.5f

    val uri = entry.rawUrl?.let { runCatching { java.net.URI(it) }.getOrNull() }
    val title = if (uri == null) {
        (entry.rawUrl ?: "").trim()
    } else {
        val path = uri.path.ifEmpty { "/" }
        if (uri.query.isNullOrEmpty()) path else "$path?${uri.query}"
    }
    val subtitleParts = buildList {
        uri?.host?.takeIf { it.isNotEmpty() }?.let { add(it) }
        entry.startedAt?.let { add(fmtTime(it)) }
        entry.durationMs?.let { add(fmtDuration(it)) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(1.dp, cs.outlineVariant.copy(alpha = if (isDark) 0.26f else 0.38f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .clickable(onClick = onTap)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MethodPill(method = (entry.method ?: "—").uppercase())
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.92f), letterSpacing = (-0.2).sp, lineHeight = 20.sp),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            StatusPill(status = entry.statusCode, isError = entry.hasError)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = subtitleParts.joinToString(" · "),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.60f)),
        )
        if (entry.hasError && entry.errors.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            InlineErrorPreview(text = entry.errors.first())
        }
    }
}

@Composable
private fun MethodPill(method: String) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val lum = 0.2126f * cs.surface.red + 0.7152f * cs.surface.green + 0.0722f * cs.surface.blue
    val isDark = lum < 0.5f
    val (bg, fg) = when (method) {
        "GET" -> semantic.success.copy(alpha = if (isDark) 0.22f else 0.16f) to semantic.success
        "POST" -> cs.primary.copy(alpha = if (isDark) 0.22f else 0.16f) to cs.primary
        "PUT", "PATCH" -> semantic.warning.copy(alpha = if (isDark) 0.22f else 0.16f) to semantic.warning
        "DELETE" -> cs.error.copy(alpha = if (isDark) 0.22f else 0.14f) to cs.error
        else -> cs.onSurface.copy(alpha = if (isDark) 0.12f else 0.06f) to cs.onSurface.copy(alpha = if (isDark) 0.80f else 0.72f)
    }
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(MemoRadius.PILL_DP.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(method, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp, color = fg))
    }
}

@Composable
private fun StatusPill(status: Int?, isError: Boolean) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val lum = 0.2126f * cs.surface.red + 0.7152f * cs.surface.green + 0.0722f * cs.surface.blue
    val isDark = lum < 0.5f
    val code = status ?: 0
    val ok = code in 200..299 && !isError
    val warn = code in 300..399 && !isError
    val bg = when {
        isError || code >= 400 -> androidx.compose.ui.graphics.lerp(semantic.surfaceCard, cs.error, if (isDark) 0.18f else 0.12f)
        ok -> semantic.success.copy(alpha = if (isDark) 0.26f else 0.18f)
        warn -> cs.tertiaryContainer.copy(alpha = if (isDark) 0.50f else 0.55f)
        else -> cs.onSurface.copy(alpha = if (isDark) 0.12f else 0.06f)
    }
    val fg = when {
        isError || code >= 400 -> cs.error.copy(alpha = if (isDark) 0.92f else 0.88f)
        ok -> semantic.success
        warn -> cs.onTertiaryContainer.copy(alpha = 0.92f)
        else -> cs.onSurface.copy(alpha = if (isDark) 0.78f else 0.72f)
    }
    Row(
        modifier = Modifier
            .background(bg, RoundedCornerShape(MemoRadius.PILL_DP.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isError || code >= 400) {
            Icon(Lucide.CircleX, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        } else if (ok) {
            Icon(Lucide.CircleCheck, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = status?.toString() ?: "—",
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.1).sp, color = fg),
        )
    }
}

@Composable
private fun InlineErrorPreview(text: String) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val lum = 0.2126f * cs.surface.red + 0.7152f * cs.surface.green + 0.0722f * cs.surface.blue
    val isDark = lum < 0.5f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.lerp(semantic.surfaceFill, cs.error, if (isDark) 0.12f else 0.07f),
                RoundedCornerShape(MemoRadius.INNER_DP.dp),
            )
            .border(1.dp, cs.error.copy(alpha = if (isDark) 0.32f else 0.22f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.CircleX, contentDescription = null, tint = cs.error.copy(alpha = if (isDark) 0.92f else 0.86f), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontSize = 12.sp, lineHeight = 14.4.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.86f)),
        )
    }
}

// —— Request log detail page ————————————————————————————————————————————

private const val PRETTY_JSON_INLINE_LIMIT = 64 * 1024

private fun prettyJson(text: String): String {
    val v = text.trim()
    if (v.isEmpty()) return ""
    return runCatching {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(v)
        obj.toString() // compact re-encode; full pretty-printing is not
        // available without an indenting encoder, matching structure.
    }.getOrDefault(text)
}

private fun prettyJsonObj(obj: kotlinx.serialization.json.JsonElement?): String =
    obj?.toString() ?: ""

/** _RequestLogDetailPage L2080-2345 — summary/params/headers/bodies/warnings. */
@Composable
private fun RequestLogDetailOverlay(entry: RequestLogEntry, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val clipboardScope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current

    fun copy(text: String) {
        copyToClipboard(clipboardScope, clipboard, text)
        SnackbarManager.show(AppNotification(
            message = context.getString(UiR.string.chat_message_widget_copied_to_clipboard),
            type = NotificationType.SUCCESS,
        ))
    }

    val url = entry.rawUrl ?: ""
    OverlayScaffold(
        title = "${(entry.method ?: "REQ").uppercase()} · ${entry.statusCode ?: "—"}",
        onClose = onClose,
        actions = {
            if (url.trim().isNotEmpty()) {
                IconButton(onClick = { copy(url) }, modifier = Modifier.size(44.dp)) {
                    Icon(Lucide.Copy, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(20.dp))
                }
            }
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
        ) {
            val errorLines = entry.errors.ifEmpty {
                if ((entry.statusCode ?: 0) >= 400) listOf("HTTP ${entry.statusCode}") else emptyList()
            }
            if (errorLines.isNotEmpty()) {
                item {
                    ErrorHeroCard(errors = errorLines)
                    Spacer(Modifier.height(12.dp))
                }
            }
            item {
                DetailSectionCard(
                    icon = Lucide.BadgeInfo,
                    title = stringResource(UiR.string.log_viewer_section_summary),
                    trailing = {
                        if (url.trim().isNotEmpty()) {
                            IconButton(onClick = { copy(url) }, modifier = Modifier.size(30.dp)) {
                                Icon(Lucide.Copy, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                ) {
                    CodeBlock(text = url, tone = CodeTone.NEUTRAL)
                    Spacer(Modifier.height(12.dp))
                    KvGrid(items = listOf(
                        stringResource(UiR.string.log_viewer_field_id) to entry.id.toString(),
                        stringResource(UiR.string.log_viewer_field_method) to (entry.method ?: "—").uppercase(),
                        stringResource(UiR.string.log_viewer_field_status) to (entry.statusCode?.toString() ?: "—"),
                        stringResource(UiR.string.log_viewer_field_started) to (entry.startedAt?.let { fmtTimestamp(it) } ?: "—"),
                        stringResource(UiR.string.log_viewer_field_ended) to (entry.lastEventAt?.let { fmtTimestamp(it) } ?: "—"),
                        stringResource(UiR.string.log_viewer_field_duration) to (entry.durationMs?.let { fmtDuration(it) } ?: "—"),
                    ))
                }
                Spacer(Modifier.height(12.dp))
            }
            if (entry.requestHeaders?.isNotEmpty() == true) {
                item {
                    DetailSectionCard(
                        icon = Lucide.Hash,
                        title = stringResource(UiR.string.log_viewer_section_request_headers),
                        trailing = {
                            IconButton(onClick = { copy(prettyJsonObj(entry.requestHeaders)) }, modifier = Modifier.size(30.dp)) {
                                Icon(Lucide.Copy, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(18.dp))
                            }
                        },
                    ) { CodeBlock(text = prettyJsonObj(entry.requestHeaders), tone = CodeTone.NEUTRAL) }
                    Spacer(Modifier.height(12.dp))
                }
            }
            if (entry.attachments.isNotEmpty()) {
                item {
                    DetailSectionCard(icon = Lucide.Paperclip, title = stringResource(UiR.string.log_viewer_section_attachments)) {
                        AttachmentChips(entry.attachments)
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            if (!entry.requestBody.isNullOrBlank()) {
                item {
                    DetailSectionCard(
                        icon = Lucide.ArrowUp,
                        title = stringResource(UiR.string.log_viewer_section_request_body),
                        trailing = {
                            IconButton(onClick = { copy(entry.requestBody ?: "") }, modifier = Modifier.size(30.dp)) {
                                Icon(Lucide.Copy, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(18.dp))
                            }
                        },
                    ) {
                        CollapsibleCodeBlock(
                            text = prettyJson(entry.requestBody ?: ""),
                            tone = CodeTone.NEUTRAL,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            if (entry.responseHeaders?.isNotEmpty() == true) {
                item {
                    DetailSectionCard(
                        icon = Lucide.Hash,
                        title = stringResource(UiR.string.log_viewer_section_response_headers),
                        trailing = {
                            IconButton(onClick = { copy(prettyJsonObj(entry.responseHeaders)) }, modifier = Modifier.size(30.dp)) {
                                Icon(Lucide.Copy, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(18.dp))
                            }
                        },
                    ) { CodeBlock(text = prettyJsonObj(entry.responseHeaders), tone = CodeTone.NEUTRAL) }
                    Spacer(Modifier.height(12.dp))
                }
            }
            if (!entry.responseBody.isNullOrBlank()) {
                item {
                    DetailSectionCard(
                        icon = Lucide.ArrowDown,
                        title = stringResource(UiR.string.log_viewer_section_response_body),
                        trailing = {
                            IconButton(onClick = { copy(entry.responseBody ?: "") }, modifier = Modifier.size(30.dp)) {
                                Icon(Lucide.Copy, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(18.dp))
                            }
                        },
                    ) {
                        CollapsibleCodeBlock(
                            text = prettyJson(entry.responseBody ?: ""),
                            tone = if (entry.hasError) CodeTone.ERROR else CodeTone.NEUTRAL,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            if (entry.warnings.isNotEmpty()) {
                item {
                    DetailSectionCard(icon = Lucide.BadgeInfo, title = stringResource(UiR.string.log_viewer_section_warnings)) {
                        Column {
                            for (w in entry.warnings) {
                                Text(
                                    w,
                                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.78f), lineHeight = 16.3.sp),
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorHeroCard(errors: List<String>) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val lum = 0.2126f * cs.surface.red + 0.7152f * cs.surface.green + 0.0722f * cs.surface.blue
    val isDark = lum < 0.5f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.lerp(semantic.surfaceCard, cs.error, if (isDark) 0.14f else 0.08f),
                RoundedCornerShape(MemoRadius.CARD_DP.dp),
            )
            .border(1.dp, cs.error.copy(alpha = if (isDark) 0.34f else 0.22f), RoundedCornerShape(MemoRadius.CARD_DP.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.CircleX, contentDescription = null, tint = cs.error.copy(alpha = if (isDark) 0.92f else 0.88f), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(UiR.string.log_viewer_error_title),
                style = TextStyle(fontWeight = FontWeight.Bold, color = cs.error.copy(alpha = if (isDark) 0.92f else 0.88f), letterSpacing = (-0.2).sp),
            )
        }
        Spacer(Modifier.height(10.dp))
        for (e in errors.take(3)) {
            Text(
                e,
                style = TextStyle(color = cs.onSurface.copy(alpha = 0.86f), fontSize = 12.5.sp, lineHeight = 15.6.sp, fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(6.dp))
        }
        if (errors.size > 3) {
            Text(
                text = stringResource(UiR.string.log_viewer_more_count, (errors.size - 3).toString()),
                style = TextStyle(color = cs.onSurface.copy(alpha = 0.62f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

enum class CodeTone { NEUTRAL, ERROR }

@Composable
private fun DetailSectionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(0.6.dp, semantic.hairline, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 分区标题（附件/参数/请求体…）的颜色跟主题走 —— 判据与设置页各分组标题
            // 同一个 `settingsSectionHeaderColor`（用户 2026-09-17「日志界面那个分类
            // 怎么没有跟着主题颜色走」）。图标与标题同色（RikkaHub CardGroup 的
            // LocalContentColor provides primary 也是整行同色）。
            val headerColor = settingsSectionHeaderColor(cs)
            Icon(icon, contentDescription = null, tint = headerColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                style = TextStyle(fontWeight = FontWeight.Bold, color = headerColor, letterSpacing = (-0.2).sp),
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun CodeBlock(text: String, tone: CodeTone) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val lum = 0.2126f * cs.surface.red + 0.7152f * cs.surface.green + 0.0722f * cs.surface.blue
    val isDark = lum < 0.5f
    val bg = if (tone == CodeTone.ERROR) {
        androidx.compose.ui.graphics.lerp(semantic.surfaceCardFill, cs.error, if (isDark) 0.10f else 0.06f)
    } else {
        semantic.surfaceCardFill
    }
    val border = if (tone == CodeTone.ERROR) {
        cs.error.copy(alpha = if (isDark) 0.32f else 0.22f)
    } else {
        cs.outlineVariant.copy(alpha = if (isDark) 0.22f else 0.34f)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(1.dp, border, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .padding(12.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 16.1.sp, color = cs.onSurface.copy(alpha = 0.86f)),
        )
    }
}

/** _CollapsibleCodeBlock L2525-2682 — bounded slices keep layout cheap. */
@Composable
private fun CollapsibleCodeBlock(text: String, tone: CodeTone) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val lum = 0.2126f * cs.surface.red + 0.7152f * cs.surface.green + 0.0722f * cs.surface.blue
    val isDark = lum < 0.5f
    val collapsedChars = 2048
    val collapsedLines = 8
    val windowChars = 32 * 1024

    var expanded by remember(text) { mutableStateOf(false) }
    var shown by remember(text) { mutableStateOf(windowChars) }

    val bg = if (tone == CodeTone.ERROR) {
        androidx.compose.ui.graphics.lerp(semantic.surfaceCardFill, cs.error, if (isDark) 0.10f else 0.06f)
    } else {
        semantic.surfaceCardFill
    }
    val border = if (tone == CodeTone.ERROR) {
        cs.error.copy(alpha = if (isDark) 0.32f else 0.22f)
    } else {
        cs.outlineVariant.copy(alpha = if (isDark) 0.22f else 0.34f)
    }
    val hint = cs.onSurface.copy(alpha = 0.45f)
    val textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 16.1.sp, color = cs.onSurface.copy(alpha = 0.86f))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(1.dp, border, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .padding(12.dp),
    ) {
        val visibleLen = if (expanded) minOf(shown, text.length) else minOf(collapsedChars, text.length)
        val visible = if (visibleLen >= text.length) text else text.substring(0, visibleLen)
        val hasMore = expanded && visibleLen < text.length
        if (expanded) {
            Text(visible, style = textStyle)
        } else {
            Text(
                visible,
                maxLines = collapsedLines,
                overflow = TextOverflow.Ellipsis,
                style = textStyle,
                modifier = Modifier.clickable { expanded = true },
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .clickable {
                        expanded = !expanded
                        if (!expanded) shown = windowChars
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.ChevronDown, contentDescription = null, tint = hint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (expanded) "Collapse" else "Expand",
                    style = TextStyle(fontSize = 11.5.sp, color = hint),
                )
            }
            Spacer(Modifier.weight(1f))
            if (hasMore) {
                Text(
                    text = stringResource(UiR.string.log_viewer_show_more),
                    style = TextStyle(fontSize = 11.5.sp, color = cs.primary.copy(alpha = 0.9f), fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.clickable { shown += windowChars },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachmentChips(attachments: List<LogPayloadRef>) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val lum = 0.2126f * cs.surface.red + 0.7152f * cs.surface.green + 0.0722f * cs.surface.blue
    val isDark = lum < 0.5f
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (a in attachments) {
            Row(
                modifier = Modifier
                    .background(semantic.surfaceFill, RoundedCornerShape(MemoRadius.SMALL_DP.dp))
                    .border(1.dp, cs.outlineVariant.copy(alpha = if (isDark) 0.22f else 0.34f), RoundedCornerShape(MemoRadius.SMALL_DP.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (a.mime.startsWith("image/")) Lucide.Image else Lucide.FileText,
                    contentDescription = null,
                    tint = cs.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${a.mime} · ${fmtBytes(a.byteLength)} · ${stringResource(UiR.string.log_viewer_payload_omitted)}",
                    style = TextStyle(fontSize = 11.5.sp, color = cs.onSurface.copy(alpha = 0.72f)),
                )
            }
        }
    }
}

@Composable
private fun KvGrid(items: List<Pair<String, String>>) {
    val cs = MaterialTheme.colorScheme
    Column {
        items.forEachIndexed { index, (k, v) ->
            Row {
                Text(
                    text = k,
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.55f)),
                    modifier = Modifier.width(84.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = v,
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.86f), lineHeight = 15.6.sp),
                    modifier = Modifier.weight(1f),
                )
            }
            if (index != items.lastIndex) Spacer(Modifier.height(10.dp))
        }
    }
}

// —— Context log file page ——————————————————————————————————————————————

/** _ContextLogFilePage L699-884 — tail-paged snapshot list. */
@Composable
private fun ContextLogFileOverlay(container: AppContainerImpl, file: LogFileEntry, title: String, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val exportFailedText = stringResource(com.psyche.memo.ui.R.string.log_viewer_export_failed)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var loading by remember(file.path) { mutableStateOf(true) }
    var loadingMore by remember(file.path) { mutableStateOf(false) }
    var cursor by remember(file.path) { mutableStateOf(ContextLogTailCursor()) }
    var hasMore by remember(file.path) { mutableStateOf(false) }
    var snapshots by remember(file.path) { mutableStateOf<List<ContextLogSnapshot>>(emptyList()) }
    var detail by remember { mutableStateOf<ContextLogSnapshot?>(null) }

    fun fetchPage(reset: Boolean) {
        val cur = if (reset) ContextLogTailCursor() else cursor
        scope.launch {
            val page = withContext(Dispatchers.IO) {
                runCatching { ContextLogTailReader.readPage(file.path, cur) }.getOrNull()
            }
            if (page != null) {
                snapshots = if (reset) page.snapshots else snapshots + page.snapshots
                cursor = page.cursor
                hasMore = page.hasMore
            } else if (reset) {
                snapshots = emptyList()
                hasMore = false
            }
            loading = false
            loadingMore = false
        }
    }
    LaunchedEffect(file.path) { fetchPage(reset = true) }

    OverlayScaffold(
        title = title,
        onClose = onClose,
        actions = {
            TopBarAction(
                icon = Lucide.RefreshCw,
                label = stringResource(UiR.string.log_viewer_refresh),
                onClick = {
                    loading = true; loadingMore = false; hasMore = false
                    snapshots = emptyList(); cursor = ContextLogTailCursor()
                    fetchPage(reset = true)
                },
            )
            TopBarAction(
                icon = Lucide.Share2,
                label = stringResource(UiR.string.log_viewer_export),
                onClick = { shareLogFile(file, exportFailedText) },
            )
        },
    ) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator()
            }
            snapshots.isEmpty() -> RequestEmptyState()
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 16.dp),
            ) {
                item {
                    ContextSummaryBar(snapshots)
                    Spacer(Modifier.height(10.dp))
                }
                items(snapshots.size, key = { "${snapshots[it].timestamp}_${snapshots[it].conversationId}_$it" }) { index ->
                    val snapshot = snapshots[index]
                    Column(Modifier.padding(bottom = 10.dp)) {
                        ContextSnapshotCard(snapshot, onTap = { detail = snapshot })
                    }
                }
                item {
                    ContextLoadOlderFooter(
                        loading = loadingMore,
                        hasMore = hasMore,
                        onTap = {
                            if (!loading && !loadingMore && hasMore) {
                                loadingMore = true
                                fetchPage(reset = false)
                            }
                        },
                    )
                }
            }
        }
    }
    detail?.let { ContextSnapshotDetailOverlay(it) { detail = null } }
}

/** _ContextLoadOlderFooter L886-943. */
@Composable
private fun ContextLoadOlderFooter(loading: Boolean, hasMore: Boolean, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val labelStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurface.copy(alpha = 0.62f))
    when {
        loading -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(UiR.string.context_log_loading), style = labelStyle)
        }
        !hasMore -> Text(
            text = stringResource(UiR.string.context_log_all_loaded),
            style = labelStyle,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 12.dp)
                .wrapContentWidth(Alignment.CenterHorizontally),
        )
        else -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                .clickable(onClick = onTap)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(UiR.string.context_log_load_older), style = labelStyle)
        }
    }
}

/** _ContextSummaryBar L945-999. */
@Composable
private fun ContextSummaryBar(snapshots: List<ContextLogSnapshot>) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val totalTokens = snapshots.sumOf { it.totalTokens }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(0.6.dp, semantic.hairline, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(UiR.string.context_log_snapshots_count, snapshots.size.toString()),
                style = TextStyle(fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.90f), letterSpacing = (-0.2).sp),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(UiR.string.context_log_snapshot_tokens, totalTokens.toString()),
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurface.copy(alpha = 0.55f)),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(UiR.string.context_log_tokens_estimate_hint),
            style = TextStyle(fontSize = 12.sp, lineHeight = 15.sp, color = cs.onSurface.copy(alpha = 0.55f)),
        )
    }
}

/** _compositionOf L1114-1129 — per-source token totals in source order. */
private fun compositionOf(snapshot: ContextLogSnapshot): List<Pair<ContextSource, Int>> {
    val totals = LinkedHashMap<ContextSource, Int>()
    for (message in snapshot.messages) {
        for (segment in message.segments) {
            if (segment.tokens <= 0) continue
            totals[segment.source] = (totals[segment.source] ?: 0) + segment.tokens
        }
    }
    return totals.entries.sortedBy { it.key.ordinal }.map { it.key to it.value }
}

@Composable
private fun contextSourceColor(source: ContextSource): Color {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    return when (source) {
        ContextSource.systemPrompt -> cs.primary
        ContextSource.memoryRules, ContextSource.memorySnapshot -> cs.tertiary
        ContextSource.worldBook -> semantic.success
        ContextSource.instructionInjection -> semantic.warning
        ContextSource.searchPrompt -> cs.secondary
        ContextSource.skillPrompt -> cs.primary
        ContextSource.workspace -> cs.tertiary
        ContextSource.chatHistory -> cs.onSurfaceVariant
        ContextSource.toolCall, ContextSource.toolResult -> cs.outline
    }
}

private fun contextSourceLabelRes(source: ContextSource): Int = when (source) {
    ContextSource.systemPrompt -> UiR.string.context_log_source_system_prompt
    ContextSource.memoryRules -> UiR.string.context_log_source_memory_rules
    ContextSource.searchPrompt -> UiR.string.context_log_source_search_prompt
    ContextSource.instructionInjection -> UiR.string.context_log_source_instruction_injection
    ContextSource.skillPrompt -> UiR.string.context_log_source_skill_prompt
    ContextSource.workspace -> UiR.string.context_log_source_workspace
    ContextSource.worldBook -> UiR.string.context_log_source_world_book
    ContextSource.memorySnapshot -> UiR.string.context_log_source_memory_snapshot
    ContextSource.chatHistory -> UiR.string.context_log_source_chat_history
    ContextSource.toolCall -> UiR.string.context_log_source_tool_call
    ContextSource.toolResult -> UiR.string.context_log_source_tool_result
}

/** _ContextLogSnapshotCard L1001-1111 + _ContextCompositionBar L1132-1174. */
@Composable
private fun ContextSnapshotCard(snapshot: ContextLogSnapshot, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val lum = 0.2126f * cs.surface.red + 0.7152f * cs.surface.green + 0.0722f * cs.surface.blue
    val isDark = lum < 0.5f

    val assistant = snapshot.assistantName.trim()
    val model = snapshot.model.trim()
    val title = assistant.ifEmpty { model.ifEmpty { "" } }
    val providerName = displayProviderName(snapshot.provider)
    val providerModel = listOf(providerName, model).filter { it.isNotEmpty() }.joinToString(" · ")
    val metaLine = listOf(
        fmtDateTimeSeconds(snapshot.timestamp),
        stringResource(UiR.string.context_log_snapshot_messages, snapshot.messages.size.toString()),
    ).joinToString(" · ")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(0.6.dp, cs.outlineVariant.copy(alpha = if (isDark) 0.08f else 0.06f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .clickable(onClick = onTap),
    ) {
        Column(Modifier.padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 10.dp)) {
            Row {
                Text(
                    text = title.ifEmpty { stringResource(UiR.string.context_log_snapshot_fallback_title) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.92f), letterSpacing = (-0.2).sp, lineHeight = 20.sp),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(UiR.string.context_log_snapshot_tokens, snapshot.totalTokens.toString()),
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cs.primary.copy(alpha = 0.90f)),
                )
            }
            if (providerModel.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = providerModel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.60f)),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = metaLine,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.48f)),
            )
        }
        CompositionBar(compositionOf(snapshot), isEmptyBg = cs.onSurface.copy(alpha = if (isDark) 0.10f else 0.06f))
    }
}

@Composable
private fun CompositionBar(entries: List<Pair<ContextSource, Int>>, isEmptyBg: Color) {
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(6.dp).background(isEmptyBg, RoundedCornerShape(MemoRadius.PILL_DP.dp)))
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(Color.Transparent, RoundedCornerShape(MemoRadius.PILL_DP.dp))
            .clipToBounds(),
        verticalAlignment = Alignment.Bottom,
    ) {
        entries.forEachIndexed { i, (source, value) ->
            if (i > 0) Spacer(Modifier.width(2.dp))
            Box(
                modifier = Modifier
                    .weight(maxOf(value, 1).toFloat())
                    .fillMaxSize()
                    .background(contextSourceColor(source)),
            )
        }
    }
}

/** _ContextSnapshotDetailPage L1231-1318 + info card + message groups. */
@Composable
private fun ContextSnapshotDetailOverlay(snapshot: ContextLogSnapshot, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val clipboardScope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    val model = snapshot.model.trim()
    val assistant = snapshot.assistantName.trim()
    val title = assistant.ifEmpty { model }

    fun copyAll() {
        val sb = StringBuilder()
        for (message in snapshot.messages) {
            sb.appendLine("[${message.role}]")
            for (segment in message.segments) {
                val text = trimSurroundingBlankLines(segment.text)
                if (text.isNotEmpty()) sb.appendLine(text)
            }
            sb.appendLine()
        }
        copyToClipboard(clipboardScope, clipboard, sb.toString())
        SnackbarManager.show(AppNotification(
            message = context.getString(UiR.string.chat_message_widget_copied_to_clipboard),
            type = NotificationType.SUCCESS,
        ))
    }

    OverlayScaffold(
        title = title.ifEmpty { stringResource(UiR.string.context_log_snapshot_fallback_title) },
        onClose = onClose,
        actions = {
            TopBarAction(
                icon = Lucide.Copy,
                label = stringResource(UiR.string.log_viewer_copy),
                onClick = { copyAll() },
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
        ) {
            item(key = "ctx_info") {
                ContextInfoCard(snapshot)
                Spacer(Modifier.height(14.dp))
            }
            // 带 key：同文件另三个列表（405/686/1417）都带，这里漏了。快照本身不重排，
            // 但缺 key 会让 LazyList 在增删时整段按位置重建（丢掉每组的展开态）。
            items(snapshot.messages.size, key = { "ctx_msg_$it" }) { index ->
                val message = snapshot.messages[index]
                ContextMessageGroup(message)
                if (index != snapshot.messages.lastIndex) Spacer(Modifier.height(14.dp))
            }
        }
    }
}

@Composable
private fun ContextInfoCard(snapshot: ContextLogSnapshot) {
    val semantic = LocalSemanticColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(0.6.dp, semantic.hairline, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .padding(14.dp),
    ) {
        val providerName = displayProviderName(snapshot.provider)
        KvGrid(items = buildList {
            add(stringResource(UiR.string.log_viewer_field_started) to fmtDateTimeSeconds(snapshot.timestamp))
            if (assistantName(snapshot).isNotEmpty()) add(stringResource(UiR.string.stats_page_assistant_column) to assistantName(snapshot))
            if (providerName.isNotEmpty()) add(stringResource(UiR.string.tts_services_dialog_provider_type) to providerName)
            if (snapshot.model.trim().isNotEmpty()) add(stringResource(UiR.string.stats_page_model_column) to snapshot.model.trim())
            add(
                stringResource(UiR.string.context_log_viewer_title) to
                    "${stringResource(UiR.string.context_log_snapshot_messages, snapshot.messages.size.toString())} · " +
                    stringResource(UiR.string.context_log_snapshot_tokens, snapshot.totalTokens.toString()),
            )
        })
        Spacer(Modifier.height(12.dp))
        val lum = 0.2126f * MaterialTheme.colorScheme.surface.red + 0.7152f * MaterialTheme.colorScheme.surface.green + 0.0722f * MaterialTheme.colorScheme.surface.blue
        CompositionBar(compositionOf(snapshot), isEmptyBg = MaterialTheme.colorScheme.onSurface.copy(alpha = if (lum < 0.5f) 0.10f else 0.06f))
        Spacer(Modifier.height(10.dp))
        CompositionLegend(compositionOf(snapshot))
    }
}

private fun assistantName(snapshot: ContextLogSnapshot): String = snapshot.assistantName.trim()

/** _ContextCompositionLegend L1176-1229. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompositionLegend(entries: List<Pair<ContextSource, Int>>) {
    if (entries.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((source, value) in entries) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(contextSourceColor(source), CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(contextSourceLabelRes(source)), style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.72f)))
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(UiR.string.context_log_snapshot_tokens, value.toString()),
                    style = TextStyle(fontSize = 11.sp, color = cs.onSurface.copy(alpha = 0.45f)),
                )
            }
        }
    }
}

@Composable
private fun ContextMessageGroup(message: ContextLogMessage) {
    val cs = MaterialTheme.colorScheme
    val role = message.role.trim()
    Column(Modifier.fillMaxWidth()) {
        if (role.isNotEmpty()) {
            Text(
                text = role,
                modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 8.dp),
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.45f), letterSpacing = 0.2.sp),
            )
        }
        message.segments.forEachIndexed { i, segment ->
            ContextSegmentBlock(segment)
            if (i != message.segments.lastIndex) Spacer(Modifier.height(8.dp))
        }
    }
}

/** _ContextSegmentBlock L1413-1556 — collapsible, long-press copies. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContextSegmentBlock(segment: ContextSegment) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val clipboardScope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    val lum = 0.2126f * cs.surface.red + 0.7152f * cs.surface.green + 0.0722f * cs.surface.blue
    val isDark = lum < 0.5f
    var expanded by remember(segment.text) { mutableStateOf(false) }

    val metaLabel = contextSegmentMetaLabel(segment)
    val displayText = trimSurroundingBlankLines(segment.text)
    val hasText = displayText.isNotEmpty()
    val color = contextSourceColor(segment.source)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(0.6.dp, cs.outlineVariant.copy(alpha = if (isDark) 0.08f else 0.06f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .combinedClickable(
                onClick = { if (hasText) expanded = !expanded },
                onLongClick = {
                    copyToClipboard(clipboardScope, clipboard, displayText)
                    SnackbarManager.show(AppNotification(
                        message = context.getString(UiR.string.chat_message_widget_copied_to_clipboard),
                        type = NotificationType.SUCCESS,
                    ))
                },
            )
            .padding(start = 14.dp, top = 10.dp, end = 12.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(contextSourceLabelRes(segment.source)),
                style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.82f), letterSpacing = (-0.1).sp),
            )
            if (metaLabel.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = metaLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 11.sp, color = cs.onSurface.copy(alpha = 0.45f)),
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(UiR.string.context_log_snapshot_tokens, segment.tokens.toString()),
                style = TextStyle(fontSize = 11.sp, color = cs.onSurface.copy(alpha = 0.45f)),
            )
            if (hasText) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Lucide.ChevronDown,
                    contentDescription = null,
                    tint = cs.onSurface.copy(alpha = 0.35f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (hasText) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = displayText,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.8.sp, color = cs.onSurface.copy(alpha = 0.86f)),
            )
        }
    }
}

/** _contextSegmentMetaLabel L1382-1411 — world book position + snapshot kind. */
private fun contextSegmentMetaLabel(segment: ContextSegment): String {
    val meta = segment.meta ?: return ""
    val bits = mutableListOf<String>()
    val position = (meta["position"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim().orEmpty()
    if (position.isNotEmpty()) {
        // world_book injection position wire values, mapped to UiR keys.
        val res = when (position) {
            "beforeSystemPrompt" -> UiR.string.world_book_injection_position_before_system_prompt
            "afterSystemPrompt" -> UiR.string.world_book_injection_position_after_system_prompt
            "topOfChat" -> UiR.string.world_book_injection_position_top_of_chat
            "bottomOfChat" -> UiR.string.world_book_injection_position_bottom_of_chat
            "atDepth" -> UiR.string.world_book_injection_position_at_depth
            else -> null
        }
        if (res != null) bits.add(res.toString())
    }
    val kind = (meta["kind"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim().orEmpty()
    if (kind == "full" || kind == "update") {
        bits.add(kind)
    } else if (kind.isNotEmpty()) {
        bits.add(kind)
    }
    return bits.joinToString(" · ")
}

// —— Log settings sheet —————————————————————————————————————————————————

/** _LogSettingsSheet L2950-3135 + _SettingTile L3137-3250. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogSettingsSheet(container: AppContainerImpl, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val lum = 0.2126f * cs.surface.red + 0.7152f * cs.surface.green + 0.0722f * cs.surface.blue
    val isDark = lum < 0.5f

    val bs = com.psyche.memo.logging.LogBootstrap
    val prefs = container.preferenceRepository
    var saveOutput by remember { mutableStateOf(bs.isSaveOutput(prefs, false)) }
    var elidePayloads by remember { mutableStateOf(bs.isElideLargePayloads(prefs, true)) }
    var autoDeleteDays by remember { mutableStateOf(bs.autoDeleteDays(prefs, 0)) }
    var maxSizeMB by remember { mutableStateOf(bs.maxSizeMB(prefs, 50)) }

    val autoDeleteOptions = listOf(0, 3, 7, 14, 30)
    val maxSizeOptions = listOf(0, 50, 100, 200, 500)

    ModalBottomSheet(containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
sheetState = rememberMemoSheetState(), onDismissRequest = onDismiss, dragHandle = null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 16.dp),
        ) {
            // 用户 2026-09-16：「日志设置 sheet 那个 title 去掉吧」——把手下面直接是设置项。
            MemoSheetHandle(trailingGap = 12.dp)
            SettingsTileCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(UiR.string.log_settings_save_output),
                            style = TextStyle(fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.92f), fontSize = 14.sp),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(UiR.string.log_settings_save_output_subtitle),
                            style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.55f)),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    IosSwitch(
                        value = saveOutput,
                        onValueChanged = {
                            saveOutput = it
                            bs.setSaveOutput(prefs, it)
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            SettingsTileCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(UiR.string.log_settings_elide_payloads),
                            style = TextStyle(fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.92f), fontSize = 14.sp),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(UiR.string.log_settings_elide_payloads_subtitle),
                            style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.55f)),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    IosSwitch(
                        value = elidePayloads,
                        onValueChanged = {
                            elidePayloads = it
                            bs.setElideLargePayloads(prefs, it)
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            SettingTile(
                title = stringResource(UiR.string.log_settings_auto_delete),
                subtitle = stringResource(UiR.string.log_settings_auto_delete_subtitle),
                value = if (autoDeleteDays == 0) {
                    stringResource(UiR.string.log_settings_auto_delete_disabled)
                } else {
                    stringResource(UiR.string.log_settings_auto_delete_days, autoDeleteDays.toString())
                },
                options = autoDeleteOptions.map {
                    if (it == 0) stringResource(UiR.string.log_settings_auto_delete_disabled)
                    else stringResource(UiR.string.log_settings_auto_delete_days, it.toString())
                },
                selectedIndex = autoDeleteOptions.indexOf(autoDeleteDays).coerceIn(0, autoDeleteOptions.lastIndex),
                onSelected = { i ->
                    autoDeleteDays = autoDeleteOptions[i]
                    bs.setAutoDeleteDays(prefs, autoDeleteOptions[i])
                    // 上游 settings_provider.dart:5563 —— 改完立刻按新阈值清理一次。
                    container.maybeCleanupLogs()
                    onChanged()
                },
            )
            Spacer(Modifier.height(12.dp))
            SettingTile(
                title = stringResource(UiR.string.log_settings_max_size),
                subtitle = stringResource(UiR.string.log_settings_max_size_subtitle),
                value = if (maxSizeMB == 0) stringResource(UiR.string.log_settings_max_size_unlimited) else "$maxSizeMB MB",
                options = maxSizeOptions.map { if (it == 0) stringResource(UiR.string.log_settings_max_size_unlimited) else "$it MB" },
                selectedIndex = maxSizeOptions.indexOf(maxSizeMB).coerceIn(0, maxSizeOptions.lastIndex),
                onSelected = { i ->
                    maxSizeMB = maxSizeOptions[i]
                    bs.setMaxSizeMB(prefs, maxSizeOptions[i])
                    // 上游 settings_provider.dart:5575 同上。
                    container.maybeCleanupLogs()
                    onChanged()
                },
            )
        }
    }
}

@Composable
private fun SettingsTileCard(content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val lum = 0.2126f * cs.surface.red + 0.7152f * cs.surface.green + 0.0722f * cs.surface.blue
    val isDark = lum < 0.5f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(1.dp, cs.outlineVariant.copy(alpha = if (isDark) 0.26f else 0.38f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) { content() }
}

/** _SettingTile — expandable option picker. */
@Composable
private fun SettingTile(
    title: String,
    subtitle: String,
    value: String,
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val lum = 0.2126f * cs.surface.red + 0.7152f * cs.surface.green + 0.0722f * cs.surface.blue
    val isDark = lum < 0.5f
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(1.dp, cs.outlineVariant.copy(alpha = if (isDark) 0.26f else 0.38f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(modifier = Modifier.clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = TextStyle(fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.92f), fontSize = 14.sp))
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.55f)))
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .background(cs.primary.copy(alpha = if (isDark) 0.18f else 0.10f), RoundedCornerShape(MemoRadius.SMALL_DP.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(value, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cs.primary))
            }
            Icon(Lucide.ChevronDown, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.45f), modifier = Modifier.size(16.dp))
        }
        if (expanded) {
            Spacer(Modifier.height(12.dp))
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEachIndexed { i, option ->
                    val selected = i == selectedIndex
                    Text(
                        text = option,
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) cs.primary else cs.onSurface.copy(alpha = 0.72f),
                        ),
                        modifier = Modifier
                            .background(
                                if (selected) cs.primary.copy(alpha = if (isDark) 0.22f else 0.14f)
                                else cs.onSurface.copy(alpha = if (isDark) 0.08f else 0.05f),
                                RoundedCornerShape(MemoRadius.SMALL_DP.dp),
                            )
                            .border(
                                if (selected) 1.dp else 0.dp,
                                if (selected) cs.primary.copy(alpha = 0.5f) else Color.Transparent,
                                RoundedCornerShape(MemoRadius.SMALL_DP.dp),
                            )
                            .clickable { onSelected(i); expanded = false }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Code
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Github
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Phone
import com.composables.icons.lucide.Sparkles
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.Haptics
import com.psyche.memo.ui.R as UiR

/**
 * 1:1 port of about_page.dart with the Memo brand rules applied: the kelivo
 * name, GitHub/Discord/QQ/afdian links are dropped (upstream-specific), the
 * app name/description come from the generated strings (about_page_app_name
 * / about_page_app_description), and version info comes from PackageManager
 * (PackageInfo.fromPlatform upstream). Kept: header card (L426-440 long-press
 * the 54dp app icon → debug page, one press, no developer switch, no release
 * gating), version row with the 7-tap easter egg → log settings sheet
 * (L99-385) with its three folder-open buttons → log viewer tabs, system row
 * (L496-503).
 */
@Composable
fun AboutScreen(
    container: AppContainerImpl,
    onOpenDebug: () -> Unit,
    onOpenLogs: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val view = LocalView.current

    var version by remember { mutableStateOf("") }
    var buildNumber by remember { mutableStateOf("") }
    var easterEggVisible by remember { mutableStateOf(false) }
    var versionTaps by remember { mutableIntStateOf(0) }
    var lastVersionTap by remember { mutableLongStateOf(0L) }

    // PackageInfo.fromPlatform equivalent.
    LaunchedEffect(Unit) {
        runCatching {
            val pm = context.packageManager.getPackageInfo(context.packageName, 0)
            version = pm.versionName ?: ""
            buildNumber = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                pm.versionCode.toString()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        MemoTopBar(
            title = stringResource(UiR.string.settings_page_about),
            onBack = onBack,
            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 16.dp),
        ) {
            // L417-478: header card — app icon + name/description. The app
            // name tap counter (unlock kelivo search) is upstream-only and is
            // not ported; the search services domain lands in a later batch.
            item {
                SectionCard {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // `R.mipmap.ic_launcher` is the adaptive-icon XML
                        // (`mipmap-anydpi/ic_launcher.xml`), which
                        // `painterResource` cannot decode — Compose's
                        // `Image` only takes VectorDrawables or rasterized
                        // PNG/JPG/WEBP, hence the `IllegalArgumentException`
                        // that crashed this screen on entry. Fall back to
                        // the foreground PNG drawable which is a regular
                        // raster.
                        Image(
                            painter = painterResource(com.psyche.memo.R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            // L426-440：长按图标本体一次即进调试页（中触觉，无条件编译、
                            // 无连点计数、无开发者开关）。
                            modifier = Modifier
                                .size(54.dp)
                                .clip(RoundedCornerShape(MemoRadius.INNER_DP.dp))
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = {
                                        Haptics.medium(view)
                                        onOpenDebug()
                                    },
                                ),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(UiR.string.about_page_app_name),
                                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(UiR.string.about_page_app_description),
                                style = TextStyle(
                                    fontSize = 13.sp,
                                    color = cs.onSurface.copy(alpha = 0.65f),
                                    lineHeight = 15.6.sp,
                                ),
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
            item { SectionHeader(stringResource(UiR.string.about_page_section_version)) }
            item {
                SectionCard {
                    // L486-494: version row (tap 7x → easter egg).
                    AboutNavRow(
                        icon = Lucide.Code,
                        label = stringResource(UiR.string.about_page_version),
                        detail = if (version.isEmpty()) "..." else "$version / $buildNumber",
                        showChevron = false,
                        onTap = {
                            val now = System.currentTimeMillis()
                            if (now - lastVersionTap > 2000L) versionTaps = 0
                            lastVersionTap = now
                            versionTaps += 1
                            if (versionTaps >= 7) {
                                versionTaps = 0
                                easterEggVisible = true
                            }
                        },
                    )
                    DividerRow()
                    // L496-503: system row (informational only).
                    AboutNavRow(
                        icon = Lucide.Phone,
                        label = stringResource(UiR.string.about_page_system),
                        detail = stringResource(UiR.string.about_page_platform_android),
                        showChevron = false,
                        onTap = null,
                    )
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
            item { SectionHeader(stringResource(UiR.string.about_page_section_community)) }
            item {
                SectionCard {
                    // Memo 自己的源码仓。上游 kelivo / RikkaHub 的署名放在仓库的 README 与
                    // NOTICE 里，不占应用内入口。
                    AboutNavRow(
                        icon = Lucide.Github,
                        label = stringResource(UiR.string.about_page_github),
                        detail = "3d-jq/memo",
                        showChevron = true,
                        onTap = {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    "https://github.com/3d-jq/memo".toUri(),
                                ),
                            )
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // L116-385: easter egg sheet — per-channel log toggles, each with the
    // folder-open button upstream routes to that log viewer tab.
    if (easterEggVisible) {
        EasterEggSheet(
            container = container,
            onOpenLogs = onOpenLogs,
            onDismiss = { easterEggVisible = false },
        )
    }
}

/** about_page.dart _iosNavRow (L645-709): 36dp icon slot + label + detail. */
@Composable
private fun AboutNavRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    detail: String,
    showChevron: Boolean,
    onTap: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(36.dp)) {
            Icon(icon, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.9f), modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = TextStyle(fontSize = 15.sp, color = cs.onSurface.copy(alpha = 0.9f)),
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(
            text = detail,
            style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.6f)),
        )
        if (showChevron) {
            Icon(Lucide.ChevronRight, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.9f), modifier = Modifier.size(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EasterEggSheet(
    container: AppContainerImpl,
    onOpenLogs: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // Mirrors Flutter's settings_provider.dart enable sequence: read the
    // current value via LogBootstrap (which routes to the correct backing
    // store), write through LogBootstrap so the change is also pushed to
    // the writer's enabled flag.
    val prefs = container.preferenceRepository
    var contextLog by remember { mutableStateOf(
        com.psyche.memo.logging.LogBootstrap.isContextLogEnabled(prefs, default = true)
    ) }
    var requestLog by remember { mutableStateOf(
        com.psyche.memo.logging.LogBootstrap.isRequestLogEnabled(prefs, default = true)
    ) }
    var flutterLog by remember { mutableStateOf(
        com.psyche.memo.logging.LogBootstrap.isFlutterLogEnabled(prefs, default = false)
    ) }

    ModalBottomSheet(containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
sheetState = rememberMemoSheetState(), onDismissRequest = onDismiss, dragHandle = null) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 20.dp)) {
            MemoSheetHandle()
            Icon(
                Lucide.Sparkles,
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(16.dp))
            // LogViewerPage 的三个 tab 常量（log_viewer_page.dart:32-34）：
            // context=0 / request=1 / app=2。
            LogToggleRow(
                title = stringResource(UiR.string.context_log_setting_title),
                subtitle = stringResource(UiR.string.context_log_setting_subtitle),
                value = contextLog,
                onOpenLogs = { onOpenLogs(0) },
                onChange = { v ->
                    contextLog = v
                    com.psyche.memo.logging.LogBootstrap.setContextLogEnabled(prefs, v)
                },
            )
            Spacer(Modifier.height(12.dp))
            LogToggleRow(
                title = stringResource(UiR.string.request_log_setting_title),
                subtitle = stringResource(UiR.string.request_log_setting_subtitle),
                value = requestLog,
                onOpenLogs = { onOpenLogs(1) },
                onChange = { v ->
                    requestLog = v
                    com.psyche.memo.logging.LogBootstrap.setRequestLogEnabled(prefs, v)
                },
            )
            Spacer(Modifier.height(12.dp))
            LogToggleRow(
                title = stringResource(UiR.string.flutter_log_setting_title),
                subtitle = stringResource(UiR.string.flutter_log_setting_subtitle),
                value = flutterLog,
                onOpenLogs = { onOpenLogs(2) },
                onChange = { v ->
                    flutterLog = v
                    com.psyche.memo.logging.LogBootstrap.setFlutterLogEnabled(prefs, v)
                },
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(UiR.string.about_page_easter_egg_button))
            }
        }
    }
}

@Composable
private fun LogToggleRow(
    title: String,
    subtitle: String,
    value: Boolean,
    onOpenLogs: () -> Unit,
    onChange: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 6.dp),
        ) {
            Text(
                text = title,
                style = TextStyle(fontSize = 15.sp, color = cs.onSurface.copy(alpha = 0.9f)),
                modifier = Modifier.weight(1f),
            )
            // L171-194：文件夹钮在标签与开关**之间**（圆角 6、图标 20 走 primary）。
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(MemoRadius.SMALL_DP.dp))
                    .clickable(onClick = onOpenLogs)
                    .padding(6.dp),
            ) {
                Icon(
                    Lucide.FolderOpen,
                    contentDescription = null,
                    tint = cs.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            IosSwitch(value = value, onValueChanged = onChange)
        }
        Text(
            text = subtitle,
            style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.65f), lineHeight = 15.sp),
        )
    }
}

package com.psyche.memo.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.BadgeInfo
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Import
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Share2
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.withAlpha
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.X
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ui.snackbar.AppNotification

/**
 * legacy_memory_page.dart 1:1 (mobile branches) — read-only legacy memories
 * from LegacyMemoryStore (§14.5 / D-29) with search, copy, export and the
 * migration entry sheet. The migration's LLM execution (batch rewrite via the
 * chosen model) belongs to a later TTS/LLM-service batch; the start action
 * reports the generic error toast for now.
 */
@Composable
fun LegacyMemoryScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current
    val context = LocalContext.current
    val store = remember { LegacyMemoryStore(container.preferenceRepository) }
    val assistants = remember { loadAssistantsSync(container) }

    androidx.compose.runtime.LaunchedEffect(Unit) { store.initialize() }

    var rev by remember { mutableStateOf(0) }
    var search by remember { mutableStateOf("") }
    var migrationSheet by remember { mutableStateOf(false) }

    val q = search.trim().lowercase()
    val memories = remember(rev, q) {
        store.memories.filter { m ->
            q.isEmpty() || m.content.lowercase().contains(q)
        }
    }
    val byAssistant = remember(memories) {
        memories.groupBy { it.assistantId }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // AppBar with export + migrate actions (L40-84).
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(Lucide.ArrowLeft, contentDescription = stringResource(UiR.string.settings_page_back_button), tint = cs.onSurface, modifier = Modifier.size(22.dp))
            }
            Text(
                text = stringResource(UiR.string.legacy_memory_page_title),
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    // exportAll (L125-148): write a text file, share via ACTION_SEND.
                    val text = buildExportText(
                        exportTitle = context.getString(UiR.string.legacy_memory_export_title),
                        memories = store.memories,
                        assistantName = { id -> assistants.firstOrNull { it.id == id }?.name ?: id },
                    )
                    runCatching {
                        val dir = context.cacheDir
                        val file = File(dir, "memo-legacy-memory-${System.currentTimeMillis()}.txt")
                        file.writeText(text)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    }
                },
                modifier = Modifier.size(44.dp),
            ) {
                Icon(Lucide.Share2, contentDescription = stringResource(UiR.string.legacy_memory_export), tint = cs.onSurface, modifier = Modifier.size(20.dp))
            }
            IconButton(
                onClick = {
                    // showMigration (L150-176).
                    if (store.memories.isEmpty()) {
                        SnackbarManager.show(
                            AppNotification(
                                message = context.getString(UiR.string.legacy_memory_empty),
                                type = NotificationType.INFO,
                            ),
                        )
                    } else {
                        migrationSheet = true
                    }
                },
                modifier = Modifier.size(44.dp),
            ) {
                Icon(Lucide.Import, contentDescription = stringResource(UiR.string.legacy_memory_migrate), tint = cs.primary, modifier = Modifier.size(20.dp))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        ) {
            // Banner (L224-247).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(withAlpha(cs.secondaryContainer, 0.35), RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                Icon(Lucide.BadgeInfo, contentDescription = null, modifier = Modifier.size(18.dp), tint = cs.secondary)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(UiR.string.legacy_memory_banner),
                    style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, color = withAlpha(cs.onSurface, 0.8)),
                )
            }
            Spacer(Modifier.height(12.dp))

            MemorySearchField(
                value = search,
                onValueChange = { search = it },
                hintText = stringResource(UiR.string.legacy_memory_search_hint),
            )
            Spacer(Modifier.height(12.dp))

            if (byAssistant.isEmpty()) {
                Text(
                    stringResource(UiR.string.legacy_memory_empty),
                    style = TextStyle(color = withAlpha(cs.onSurface, 0.55)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                )
            } else {
                byAssistant.forEach { (assistantId, list) ->
                    Text(
                        stringResource(
                            UiR.string.legacy_memory_assistant_header,
                            assistants.firstOrNull { it.id == assistantId }?.name ?: assistantId,
                        ),
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp, end = 4.dp, bottom = 6.dp),
                    )
                    list.forEach { m ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .background(app.surfaceCard, RoundedCornerShape(14.dp))
                                .border(0.6.dp, withAlpha(cs.outlineVariant, if (app.isDark) 0.08 else 0.06), RoundedCornerShape(14.dp))
                                .padding(start = 12.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
                        ) {
                            Text(
                                m.content,
                                style = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, color = cs.onSurface),
                                modifier = Modifier.weight(1f),
                            )
                            TactileRow(onTap = {
                                // Copy + toast (L304-323).
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("memory", m.content))
                                SnackbarManager.show(
                                    AppNotification(
                                        message = context.getString(UiR.string.legacy_memory_copied),
                                        type = NotificationType.SUCCESS,
                                    ),
                                )
                            }, haptics = false) { pressed ->
                                Icon(
                                    Lucide.Copy,
                                    contentDescription = stringResource(UiR.string.legacy_memory_copy),
                                    modifier = Modifier
                                        .padding(7.dp)
                                        .size(18.dp),
                                    tint = if (pressed) withAlpha(cs.primary, 0.7) else cs.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Migration sheet (_showLegacyMemoryMigration L335+ / _LegacyMemoryMigrationPanel).
    if (migrationSheet) {
        LegacyMigrationSheet(
            container = container,
            memories = store.memories,
            onDismiss = { migrationSheet = false },
        )
    }
}

/** LegacyMemoryContent.buildExportText (L98-123). */
private fun buildExportText(
    exportTitle: String,
    memories: List<LegacyMemory>,
    assistantName: (String) -> String,
): String {
    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
    val buf = StringBuilder()
    buf.appendLine("# $exportTitle")
    buf.appendLine("# $stamp")
    buf.appendLine()
    for ((assistantId, list) in memories.groupBy { it.assistantId }) {
        buf.appendLine("## ${assistantName(assistantId)}")
        for (m in list) {
            buf.appendLine("- ${m.content}")
        }
        buf.appendLine()
    }
    return buf.toString().trimEnd()
}

/** _LegacyMemoryMigrationPanel — configuration UI; LLM execution is a later batch. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegacyMigrationSheet(
    container: AppContainerImpl,
    memories: List<LegacyMemory>,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current
    val context = LocalContext.current
    val settings = remember { MemorySettingsState(container) }
    var rev by remember { mutableStateOf(0) }

    var modelSheetVisible by remember { mutableStateOf(false) }
    val modelProvider = settings.memoryModelProvider
    val modelId = settings.memoryModelId

    // Content mode: organize (rewrite) / preserve (classify only).
    var preserveMode by remember { mutableStateOf(false) }
    // Target scope: global / current assistant / original assistants.
    var targetGlobal by remember { mutableStateOf(true) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        key(rev) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp),
            ) {
                Text(
                    stringResource(UiR.string.legacy_memory_migration_title),
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(UiR.string.legacy_memory_migration_subtitle, memories.size.toString()),
                    style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp, color = withAlpha(cs.onSurface, 0.62)),
                )
                Spacer(Modifier.height(16.dp))

                // Model row.
                Text(
                    stringResource(UiR.string.legacy_memory_migration_choose_model),
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.8)),
                )
                Spacer(Modifier.height(8.dp))
                SectionCard {
                    TactileRow(onTap = { modelSheetVisible = true }) { pressed ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (pressed) withAlpha(cs.onSurface, 0.05) else Color.Transparent)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val label = if (modelProvider == null || modelId == null) {
                                stringResource(UiR.string.memory_settings_model_unset)
                            } else {
                                val providerName = container.providerConfig(modelProvider)?.name?.trim().takeUnless { it.isNullOrEmpty() } ?: modelProvider
                                "$providerName / $modelId"
                            }
                            Text(
                                label,
                                style = TextStyle(fontSize = 14.sp, color = cs.onSurface),
                                modifier = Modifier.weight(1f),
                            )
                            Icon(Lucide.Import, contentDescription = null, modifier = Modifier.size(18.dp), tint = cs.primary)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                // Content mode.
                Text(
                    stringResource(UiR.string.legacy_memory_migration_content_mode),
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.8)),
                )
                Spacer(Modifier.height(8.dp))
                SectionCard {
                    MigrationChoiceRow(
                        title = stringResource(UiR.string.legacy_memory_migration_content_organize),
                        subtitle = stringResource(UiR.string.legacy_memory_migration_content_organize_description),
                        selected = !preserveMode,
                        onTap = { preserveMode = false },
                    )
                    MigrationChoiceRow(
                        title = stringResource(UiR.string.legacy_memory_migration_content_preserve),
                        subtitle = stringResource(UiR.string.legacy_memory_migration_content_preserve_description),
                        selected = preserveMode,
                        onTap = { preserveMode = true },
                    )
                }
                Spacer(Modifier.height(16.dp))

                // Target scope.
                Text(
                    stringResource(UiR.string.legacy_memory_migration_target),
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.8)),
                )
                Spacer(Modifier.height(8.dp))
                SectionCard {
                    MigrationChoiceRow(
                        title = stringResource(UiR.string.legacy_memory_migration_target_global),
                        selected = targetGlobal,
                        onTap = { targetGlobal = true },
                    )
                    MigrationChoiceRow(
                        title = stringResource(UiR.string.legacy_memory_migration_target_original_assistants),
                        selected = !targetGlobal,
                        onTap = { targetGlobal = false },
                    )
                }

                Spacer(Modifier.height(20.dp))
                IosTileButton(
                    label = stringResource(UiR.string.legacy_memory_migration_start),
                    icon = Lucide.Import,
                    backgroundColor = cs.primary,
                    onClick = {
                        // TODO(later batch): drive the actual migration through
                        // the memory pipeline (legacy_memory_migration.dart).
                        // The pipeline does not run on this side yet.
                        SnackbarManager.show(
                            AppNotification(
                                message = context.getString(UiR.string.legacy_memory_migration_error_other),
                                type = NotificationType.ERROR,
                            ),
                        )
                    },
                )
                Spacer(Modifier.height(10.dp))
                IosTileButton(
                    label = stringResource(UiR.string.legacy_memory_migration_close),
                    icon = com.composables.icons.lucide.Lucide.X,
                    onClick = onDismiss,
                )
            }
        }
    }

    if (modelSheetVisible) {
        val options = remember {
            com.psyche.memo.data.db.PayloadEntityDao(
                container.database.readableDatabase,
                "provider_rows",
                primaryKey = "provider_key",
            ).getAll().flatMap { row ->
                val config = runCatching {
                    com.psyche.memo.data.model.ProviderConfig.fromJsonString(
                        kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
                        row.payload,
                    )
                }.getOrNull() ?: return@flatMap emptyList()
                config.models.map { id ->
                    ModelOption(
                        providerId = config.id,
                        providerName = config.name,
                        modelId = id,
                        selected = id == settings.memoryModelId && config.id == settings.memoryModelProvider,
                    )
                }
            }
        }
        ModelSelectSheet(
            container = container,
            options = options,
            onSelect = { sel ->
                settings.setMemoryModel(sel.providerId, sel.modelId)
                rev++
                modelSheetVisible = false
            },
            onDismiss = { modelSheetVisible = false },
        )
    }
}

@Composable
private fun MigrationChoiceRow(title: String, subtitle: String? = null, selected: Boolean, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    TactileRow(onTap = onTap, haptics = false) { pressed ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (pressed) withAlpha(cs.onSurface, 0.04) else androidx.compose.ui.graphics.Color.Transparent)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.9)),
                )
                if (!subtitle.isNullOrEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        style = TextStyle(fontSize = 12.5.sp, lineHeight = 16.sp, color = withAlpha(cs.onSurface, 0.6)),
                    )
                }
            }
            if (selected) {
                Icon(com.composables.icons.lucide.Lucide.Check, contentDescription = null, modifier = Modifier.size(18.dp), tint = cs.primary)
            }
        }
    }
}

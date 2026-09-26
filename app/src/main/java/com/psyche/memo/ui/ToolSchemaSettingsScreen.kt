package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.X
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ui.R as UiR
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 1:1 port of lib/features/settings/widgets/tool_schema_ui.dart — the icon
 * mapping, first-line summary helper, reset-all confirm dialog, modified
 * badge and the settings-style tool row.
 */

/**
 * toolSchemaIconFor L15-63 —— **本工程把它做成 [toolIconFor] 的别名**。
 *
 * 上游在这份列表里有自己的一张 when 表；我们第一版照抄了一张，于是同一批工具在两个界面
 * 可能长得不一样 —— 而且**真的漏过**：工作区那批工具加了图标后，这里仍然全是 `Wrench`
 * （用户 2026-09-23「工具描述里面的图标怎么没有变呀」），接着记忆全族又共用一个图标
 * （同日「记忆工具里的图标也改一下吧，很多也一样呀」）。两份表维护不出好处，
 * 只留聊天工具卡那一份（含上游那批 + 我们的工具），这里直接转发。
 */
fun toolSchemaIconFor(name: String): androidx.compose.ui.graphics.vector.ImageVector =
    com.psyche.memo.ui.chat.toolIconFor(name)
/** toolSchemaFirstLine L65-69. */
fun toolSchemaFirstLine(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ""
    return trimmed.split("\n", "\r\n").first()
}

/** tool_schema_ui.dart confirmResetAllToolSchemas L71-137. */
@Composable
fun ToolSchemaResetAllDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(cs.surface, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 12.dp),
        ) {
            Text(
                text = stringResource(UiR.string.tool_schema_settings_reset_all_title),
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(UiR.string.tool_schema_settings_reset_all_message),
                style = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, color = cs.onSurface.copy(alpha = 0.8f)),
            )
            Spacer(Modifier.height(16.dp))
            Row {
                Box(modifier = Modifier.weight(1f)) {
                ToolSchemaDialogButton(
                    icon = Lucide.X,
                    tint = cs.onSurface,
                    label = stringResource(UiR.string.tool_schema_settings_cancel),
                    onClick = onDismiss,
                )
                }
                Spacer(Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f)) {
                    ToolSchemaDialogButton(
                        icon = Lucide.RotateCcw,
                        label = stringResource(UiR.string.tool_schema_settings_reset_all_confirm),
                        tint = cs.error,
                        onClick = onConfirm,
                    )
                }
            }
        }
    }
}

/** IosTileButton as used inside the reset dialog (L111-125). */
@Composable
private fun ToolSchemaDialogButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(cs.surface.copy(alpha = 0.0f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = tint),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** tool_schema_ui.dart ToolSchemaModifiedBadge L139-163. */
@Composable
fun ToolSchemaModifiedBadge(label: String) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .background(cs.primary.copy(alpha = 0.12f), RoundedCornerShape(MemoRadius.PILL_DP.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = cs.primary),
        )
    }
}

/**
 * tool_schema_ui.dart ToolSchemaToolRow L167-277 — settings-style tool row:
 * icon, title, summary, optional modified badge, chevron.
 */
@Composable
fun ToolSchemaToolRow(
    entry: BuiltInToolCatalogEntry,
    schemaOverride: ToolSchemaOverride?,
    onTap: () -> Unit,
    selected: Boolean = false,
    showChevron: Boolean = true,
    compact: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val modified = schemaOverride != null && !schemaOverride.isEmpty
    val effective = if (modified && !schemaOverride.description.isNullOrBlank()) {
        schemaOverride.description
    } else {
        entry.defaultDescription ?: ""
    }
    val summary = toolSchemaFirstLine(effective)
    val iconSize = if (compact) 18.dp else 20.dp
    val titleSize = if (compact) 13.5.sp else 15.sp
    val subtitleSize = if (compact) 11.sp else 12.sp
    val padH = if (compact) 10.dp else 12.dp
    val padV = if (compact) 9.dp else 11.dp
    val base = if (selected) cs.primary.copy(alpha = 0.10f) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(base, if (compact) RoundedCornerShape(MemoRadius.INNER_DP.dp) else RoundedCornerShape(0.dp))
            .clickable(onClick = onTap)
            .padding(start = padH, top = padV, end = padH, bottom = padV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(if (compact) 26.dp else 36.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                toolSchemaIconFor(entry.name),
                contentDescription = null,
                tint = if (selected) cs.primary else cs.onSurface.copy(alpha = 0.9f),
                modifier = Modifier.size(iconSize),
            )
        }
        Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = TextStyle(
                    fontSize = titleSize,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) cs.primary else cs.onSurface,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = summary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontSize = subtitleSize,
                        lineHeight = subtitleSize * 1.25f,
                        color = cs.onSurface.copy(alpha = 0.55f),
                    ),
                )
            }
        }
        if (modified) {
            Spacer(Modifier.width(8.dp))
            ToolSchemaModifiedBadge(label = stringResource(UiR.string.tool_schema_settings_modified))
        }
        if (showChevron) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Lucide.ChevronRight,
                contentDescription = null,
                tint = cs.onSurface.copy(alpha = 0.35f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * 1:1 port of lib/features/settings/pages/tool_schema_settings_page.dart —
 * grouped catalog (search / memory / local) of editable tool descriptions,
 * with a reset-all action.
 */
@Composable
fun ToolSchemaSettingsScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
    onOpenEditor: (entry: BuiltInToolCatalogEntry, override: ToolSchemaOverride?) -> Unit,
) {
    val cs = MaterialTheme.colorScheme

    // settings_provider: memory_prompt_lang_v1 ('auto'|'zh'|'en') +
    // memory_legacy_mode_v1, resolved like resolvedMemoryPromptLang.
    val storedLang = container.preferenceRepository.readJson("memory_prompt_lang_v1")
        ?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() } ?: "auto"
        val catalog = remember(storedLang) {
        BuiltInToolCatalog.entries(resolvedMemoryPromptLang(storedLang))
    }
    var overrides by remember { mutableStateOf(readOverrides(container)) }
    var resetDialogVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        MemoTopBar(
            title = stringResource(UiR.string.tool_schema_settings_page_title),
            onBack = onBack,
        ) {
            // L63-76 — reset-all toolbar action.
            IconButton(onClick = { resetDialogVisible = true }, modifier = Modifier.size(44.dp)) {
                Icon(
                    Lucide.RotateCcw,
                    contentDescription = stringResource(UiR.string.tool_schema_settings_reset_all),
                    tint = cs.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 32.dp),
        ) {
            for (group in BuiltInToolGroup.entries) {
                val entries = catalog.filter { it.group == group }
                if (entries.isEmpty()) continue
                val key = group.name
                item(key = "$key-header") {
                    // L107-130 — group header + memory language note.
                    Text(
                        text = stringResource(
                            when (group) {
                                BuiltInToolGroup.SEARCH -> UiR.string.tool_schema_settings_group_search
                                BuiltInToolGroup.MEMORY -> UiR.string.tool_schema_settings_group_memory
                                BuiltInToolGroup.LOCAL -> UiR.string.tool_schema_settings_group_local
                                BuiltInToolGroup.SKILL -> UiR.string.tool_schema_settings_group_skill
                                BuiltInToolGroup.WORKSPACE -> UiR.string.tool_schema_settings_group_workspace
                                BuiltInToolGroup.BROWSER -> UiR.string.tool_schema_settings_group_browser
                                BuiltInToolGroup.GENERATION -> UiR.string.tool_schema_settings_group_generation
                            },
                        ),
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = cs.onSurface.copy(alpha = 0.8f),
                        ),
                    )
                    if (group == BuiltInToolGroup.MEMORY) {
                        Text(
                            text = stringResource(UiR.string.tool_schema_settings_memory_lang_note),
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                            style = TextStyle(
                                fontSize = 12.sp,
                                lineHeight = 16.2.sp,
                                color = cs.onSurface.copy(alpha = 0.55f),
                            ),
                        )
                    }
                }
                item(key = "$key-card") {
                    SettingsSectionCard {
                        entries.forEachIndexed { index, entry ->
                            if (index > 0) SettingsIosDivider()
                            ToolSchemaToolRow(
                                entry = entry,
                                schemaOverride = overrides[entry.name],
                                onTap = {
                                    onOpenEditor(entry, overrides[entry.name])
                                    // Refresh overrides when returning from the editor.
                                    overrides = readOverrides(container)
                                },
                            )
                        }
                    }
                }
                item(key = "$key-spacer") { Spacer(Modifier.height(18.dp)) }
            }
        }
    }

    if (resetDialogVisible) {
        ToolSchemaResetAllDialog(
            onConfirm = {
                resetDialogVisible = false
                container.preferenceRepository.writeJson("tool_schema_overrides_v1", "{}")
                overrides = emptyMap()
            },
            onDismiss = { resetDialogVisible = false },
        )
    }
}

/** tool_schema_overrides_v1 — map<toolName, ToolSchemaOverride> (models/tool_schema_override.dart). */
internal fun readOverrides(container: AppContainerImpl): Map<String, ToolSchemaOverride> =
    runCatching {
        val raw = container.preferenceRepository.readJson("tool_schema_overrides_v1") ?: return emptyMap()
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
        obj.mapNotNull { (name, value) ->
            (value as? kotlinx.serialization.json.JsonObject)?.let { name to ToolSchemaOverride.fromJson(it) }
        }.toMap()
    }.getOrDefault(emptyMap())

internal fun writeOverride(container: AppContainerImpl, name: String, override: ToolSchemaOverride?) {
    val current = readOverrides(container).toMutableMap()
    if (override == null || override.isEmpty) current.remove(name) else current[name] = override
    val obj = kotlinx.serialization.json.buildJsonObject {
        current.forEach { (toolName, value) -> put(toolName, value.toJson()) }
    }
    container.preferenceRepository.writeJson("tool_schema_overrides_v1", obj.toString())
}

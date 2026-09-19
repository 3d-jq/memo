package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.Lucide
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ui.R as UiR
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 1:1 port of lib/features/settings/pages/tool_schema_editor_page.dart +
 * lib/features/settings/widgets/tool_schema_editor_form.dart.
 * Editing covers descriptions only: the top-level function description and
 * flattened parameter path descriptions. Saving writes tool_schema_overrides_v1
 * (settings_provider.setToolSchemaOverride); blank / default-equal values mean
 * "use built-in default" and are dropped.
 */
@Composable
fun ToolSchemaEditorScreen(
    container: AppContainerImpl,
    toolName: String,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme

    // Locate the catalog entry (default definition) for this tool.
    val storedLang = container.preferenceRepository.readJson("memory_prompt_lang_v1")
        ?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() } ?: "auto"
        val entry = remember(toolName, storedLang) {
        BuiltInToolCatalog.entries(resolvedMemoryPromptLang(storedLang))
            .firstOrNull { it.name == toolName }
    }
    val initialOverride = remember(toolName) { readOverrides(container)[toolName] }

    val defaultDescription = entry?.defaultDescription ?: ""
    val params = remember(entry) { entry?.let { BuiltInToolCatalog.describeParams(it.defaultDefinition) } ?: emptyList() }

    fun effective(value: String?, fallback: String) =
        if (!value.isNullOrBlank()) value else fallback

    var descText by remember { mutableStateOf(effective(initialOverride?.description, defaultDescription)) }
    val paramTexts = remember(params, initialOverride) {
        params.associate { p ->
            p.path to mutableStateOf(effective(initialOverride?.paramDescriptions?.get(p.path), p.defaultDescription ?: ""))
        }
    }
    var paramsExpanded by remember { mutableStateOf(false) }

    // editor_form _currentOverride L78-89: blank / default-equal values are null.
    fun currentOverride(): ToolSchemaOverride {
        fun overrideOrNull(text: String, defaultText: String): String? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            if (text == defaultText) return null
            return text
        }
        val desc = overrideOrNull(descText, defaultDescription)
        val paramValues = LinkedHashMap<String, String>()
        for (p in params) {
            val value = overrideOrNull(paramTexts[p.path]?.value ?: "", p.defaultDescription ?: "")
            if (value != null) paramValues[p.path] = value
        }
        return ToolSchemaOverride(description = desc, paramDescriptions = paramValues)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        MemoTopBar(
            title = stringResource(UiR.string.tool_schema_editor_page_title),
            onBack = onBack,
        ) {
            // L60-72 — save (check) toolbar action.
            IconButton(
                onClick = {
                    writeOverride(container, toolName, currentOverride())
                    onBack()
                },
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    Lucide.Check,
                    contentDescription = stringResource(UiR.string.search_services_edit_dialog_save),
                    tint = cs.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 32.dp),
        ) {
            item {
                // form L112-151 — tool name card.
                Text(
                    text = stringResource(UiR.string.tool_schema_settings_tool_name),
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSurface.copy(alpha = 0.8f),
                    ),
                )
                SettingsSectionCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
                            Icon(
                                toolSchemaIconFor(toolName),
                                contentDescription = null,
                                tint = cs.onSurface.copy(alpha = 0.9f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = toolName,
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface),
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(18.dp)) }
            item {
                // form L153-163 — description field.
                Text(
                    text = stringResource(UiR.string.tool_schema_settings_description_label),
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSurface.copy(alpha = 0.8f),
                    ),
                )
                OutlinedTextField(
                    value = descText,
                    onValueChange = { descText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 12,
                )
            }
            if (params.isNotEmpty()) {
                item { Spacer(Modifier.height(18.dp)) }
                item {
                    // form L166-201 — expandable param descriptions header.
                    val hasParamOverrides = currentOverride().paramDescriptions.isNotEmpty()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { paramsExpanded = !paramsExpanded }
                            .padding(start = 4.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                UiR.string.tool_schema_settings_param_descriptions,
                                params.size.toString(),
                            ),
                            style = TextStyle(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = cs.onSurface.copy(alpha = 0.9f),
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        if (hasParamOverrides) {
                            Spacer(Modifier.width(8.dp))
                            ToolSchemaModifiedBadge(label = stringResource(UiR.string.tool_schema_settings_modified))
                        }
                        Icon(
                            if (paramsExpanded) Lucide.ChevronDown else Lucide.ChevronRight,
                            contentDescription = null,
                            tint = cs.onSurface.copy(alpha = 0.35f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                if (paramsExpanded) {
                    items(params.size, key = { params[it].path }) { index ->
                        val p = params[index]
                        // form L223-282 — _paramEditor.
                        Column(
                            modifier = Modifier.padding(bottom = 12.dp),
                            horizontalAlignment = Alignment.Start,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = p.path,
                                    style = TextStyle(
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = cs.onSurface,
                                    ),
                                )
                                val tags = buildList {
                                    p.type?.let { add(it) }
                                    if (!p.enumValues.isNullOrEmpty()) add(p.enumValues.joinToString(" | "))
                                }
                                for (tag in tags) {
                                    Box(
                                        modifier = Modifier
                                            .background(cs.surfaceCardColorCompat(), RoundedCornerShape(MemoRadius.PILL_DP.dp))
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            text = tag,
                                            style = TextStyle(
                                                fontSize = 11.sp,
                                                color = cs.onSurface.copy(alpha = 0.65f),
                                            ),
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = paramTexts[p.path]?.value ?: "",
                                onValueChange = { paramTexts[p.path]?.value = it },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                maxLines = 8,
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
            item {
                // form L210-218 — restore defaults IosTileButton.
                IosTileButton(
                    icon = Lucide.RotateCcw,
                    label = stringResource(UiR.string.tool_schema_settings_reset_default),
                    onClick = {
                        descText = defaultDescription
                        for (p in params) {
                            paramTexts[p.path]?.value = p.defaultDescription ?: ""
                        }
                    },
                )
            }
        }
    }
}

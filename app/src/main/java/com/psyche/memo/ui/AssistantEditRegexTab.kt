package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.X
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.Haptics
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.data.model.AssistantRegex
import com.psyche.memo.data.model.AssistantRegexScope
import com.psyche.memo.ui.reorder.ReorderableColumn
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Port of assistant_regex_tab.dart: per-assistant regex rewrite rules —
 * drag-reorder cards with an enable switch, scope pills and delete, plus the
 * bottom-sheet editor (name/pattern/replacement + scope chips with pattern
 * validation).
 */
@Composable
fun AssistantEditRegexTab(
    container: AppContainerImpl,
    assistant: Assistant,
    onEdit: ((Assistant) -> Assistant) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    var reload by remember { mutableIntStateOf(0) }
    val rules = remember(reload, assistant.id) { assistant.regexRules.mapNotNull { it.toRegex() } }
    var editing by remember { mutableStateOf<AssistantRegex?>(null) }
    var adding by remember { mutableStateOf(false) }

    fun persist(next: List<AssistantRegex>) {
        onEdit { it.copy(regexRules = next.map { rule -> rule.toJsonElement() }) }
        reload++
    }

    if (rules.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp),
            ) {
                Icon(
                    Lucide.Sparkles,
                    contentDescription = null,
                    tint = cs.primary.copy(alpha = 0.6f),
                    modifier = Modifier.size(64.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.assistant_edit_regex_description),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.7f)),
                )
                Spacer(Modifier.height(24.dp))
                IosButton(
                    label = stringResource(R.string.assistant_edit_add_regex_button),
                    icon = Lucide.Plus,
                    filled = true,
                    neutral = false,
                    onTap = {
                        Haptics.light(view)
                        adding = true
                    },
                )
            }
        }
        if (adding) {
            RegexEditorSheet(
                rule = null,
                onDismiss = { adding = false },
                onSave = { data ->
                    persist(rules + data.copy(id = UUID.randomUUID().toString()))
                    adding = false
                },
            )
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ReorderableColumn(
            items = rules,
            keyOf = { it.id },
            onMove = { from, to ->
                val newIndex = if (to > from) to + 1 else to
                val next = rules.toMutableList()
                val item = next.removeAt(from)
                next.add(newIndex, item)
                persist(next)
            },
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 80.dp),
        ) { rule, _ ->
            RegexRuleCard(
                rule = rule,
                onTap = {
                    Haptics.light(view)
                    editing = rule
                },
                onToggle = { enabled ->
                    Haptics.light(view)
                    persist(rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it })
                },
                onDelete = {
                    Haptics.light(view)
                    persist(rules.filterNot { it.id == rule.id })
                },
            )
            Spacer(Modifier.height(10.dp))
        }

        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 60.dp), contentAlignment = Alignment.BottomCenter) {
            GlassCircleButton(
                icon = Lucide.Plus,
                color = cs.primary,
                onClick = {
                    Haptics.light(view)
                    adding = true
                },
            )
        }
    }

    if (adding || editing != null) {
        RegexEditorSheet(
            rule = editing,
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = { data ->
                val existing = editing
                val next = if (existing == null) {
                    rules + data.copy(id = UUID.randomUUID().toString())
                } else {
                    rules.map { if (it.id == existing.id) data.copy(id = existing.id, enabled = existing.enabled) else it }
                }
                persist(next)
                adding = false
                editing = null
            },
        )
    }
}

/** _RegexRuleCard L422-583 — name + switch, scope pills + delete. */
@Composable
private fun RegexRuleCard(
    rule: AssistantRegex,
    onTap: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val isDark = semantic.isDark
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(0.7.dp, cs.outlineVariant.copy(alpha = if (isDark) 0.08f else 0.06f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .clickable(onClick = onTap)
            .padding(14.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = rule.name.trim().ifEmpty { stringResource(R.string.assistant_regex_untitled) },
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                IosSwitch(value = rule.enabled, onValueChanged = onToggle)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val pills = buildList {
                    if (AssistantRegexScope.USER in rule.scopesOf()) add(stringResource(R.string.assistant_regex_scope_user))
                    if (AssistantRegexScope.ASSISTANT in rule.scopesOf()) add(stringResource(R.string.assistant_regex_scope_assistant))
                    if (rule.visualOnly) add(stringResource(R.string.assistant_regex_scope_visual_only))
                    if (rule.replaceOnly) add(stringResource(R.string.assistant_regex_scope_replace_only))
                }
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    pills.forEach { pill ->
                        Box(
                            modifier = Modifier
                                .background(
                                    if (isDark) cs.onSurface.copy(alpha = 0.06f) else cs.primary.copy(alpha = 0.10f),
                                    RoundedCornerShape(MemoRadius.PILL_DP.dp),
                                )
                                .border(1.dp, cs.primary.copy(alpha = 0.35f), RoundedCornerShape(MemoRadius.PILL_DP.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = pill,
                                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cs.primary),
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable(onClick = onDelete)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Icon(
                        Lucide.Trash2,
                        contentDescription = null,
                        tint = cs.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.assistant_regex_delete_button),
                        style = TextStyle(color = cs.error, fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
    }
}

/** _showRegexBottomSheet L680-912 + scope chips + validation. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RegexEditorSheet(
    rule: AssistantRegex?,
    onDismiss: () -> Unit,
    onSave: (AssistantRegex) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val view = LocalView.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var name by remember(rule?.id) { mutableStateOf(rule?.name ?: "") }
    var pattern by remember(rule?.id) { mutableStateOf(rule?.pattern ?: "") }
    var replacement by remember(rule?.id) { mutableStateOf(rule?.replacement ?: "") }
    var scopes by remember(rule?.id) {
        mutableStateOf((rule?.scopesOf()?.toSet() ?: setOf(AssistantRegexScope.USER)).toMutableSet())
    }
    var visualOnly by remember(rule?.id) { mutableStateOf(rule?.visualOnly ?: false) }
    var replaceOnly by remember(rule?.id) { mutableStateOf(rule?.replaceOnly ?: false) }

    fun submit() {
        val trimmedName = name.trim()
        val trimmedPattern = pattern.trim()
        if (trimmedName.isEmpty() || trimmedPattern.isEmpty() || scopes.isEmpty()) {
            SnackbarManager.show(
                AppNotification(context.getString(R.string.assistant_regex_validation_error), NotificationType.WARNING),
            )
            return
        }
        val ok = runCatching { Pattern.compile(trimmedPattern) }.isSuccess
        if (!ok) {
            SnackbarManager.show(
                AppNotification(context.getString(R.string.assistant_regex_invalid_pattern), NotificationType.WARNING),
            )
            return
        }
        onSave(
            AssistantRegex(
                id = rule?.id ?: UUID.randomUUID().toString(),
                name = trimmedName,
                pattern = trimmedPattern,
                replacement = replacement,
                scopes = AssistantRegexScope.entries.filter { it in scopes }.map { it.json },
                visualOnly = visualOnly,
                replaceOnly = replaceOnly,
                enabled = rule?.enabled ?: true,
            ),
        )
    }

    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        containerColor = semantic.overlaySurface(cs),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        dragHandle = null,
    ) {
        Column(modifier = Modifier.fillMaxWidth().imePadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IosIconButton(
                    icon = Lucide.X,
                    onTap = onDismiss,
                    color = cs.onSurface,
                    size = 20.dp,
                    contentPadding = 12.dp,
                    minSize = 44.dp,
                )
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(if (rule == null) R.string.assistant_regex_add_title else R.string.assistant_regex_edit_title),
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    )
                }
                Text(
                    text = stringResource(if (rule == null) R.string.assistant_regex_add_action else R.string.assistant_regex_save_action),
                    style = TextStyle(color = cs.primary, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier
                        .clickable {
                            Haptics.light(view)
                            submit()
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 18.dp),
            ) {
                RegexTextField(
                    label = stringResource(R.string.assistant_regex_name_label),
                    text = name,
                    onTextChange = { name = it },
                    autofocus = rule == null,
                )
                Spacer(Modifier.height(12.dp))
                RegexTextField(
                    label = stringResource(R.string.assistant_regex_pattern_label),
                    text = pattern,
                    onTextChange = { pattern = it },
                )
                Spacer(Modifier.height(12.dp))
                RegexTextField(
                    label = stringResource(R.string.assistant_regex_replacement_label),
                    text = replacement,
                    onTextChange = { replacement = it },
                    multiline = true,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.assistant_regex_scope_label),
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ScopeChoiceCard(
                        label = stringResource(R.string.assistant_regex_scope_user),
                        selected = AssistantRegexScope.USER in scopes,
                        onTap = {
                            if (AssistantRegexScope.USER in scopes) scopes.remove(AssistantRegexScope.USER)
                            else scopes.add(AssistantRegexScope.USER)
                        },
                    )
                    ScopeChoiceCard(
                        label = stringResource(R.string.assistant_regex_scope_assistant),
                        selected = AssistantRegexScope.ASSISTANT in scopes,
                        onTap = {
                            if (AssistantRegexScope.ASSISTANT in scopes) scopes.remove(AssistantRegexScope.ASSISTANT)
                            else scopes.add(AssistantRegexScope.ASSISTANT)
                        },
                    )
                    ScopeChoiceCard(
                        label = stringResource(R.string.assistant_regex_scope_visual_only),
                        selected = visualOnly,
                        onTap = {
                            visualOnly = !visualOnly
                            if (visualOnly) replaceOnly = false
                        },
                    )
                    ScopeChoiceCard(
                        label = stringResource(R.string.assistant_regex_scope_replace_only),
                        selected = replaceOnly,
                        onTap = {
                            replaceOnly = !replaceOnly
                            if (replaceOnly) visualOnly = false
                        },
                    )
                }
            }
        }
    }
}

/** _RegexTextField L1169-1211 — filled surfaceFill, r12, outline/primary borders. */
@Composable
private fun RegexTextField(
    label: String,
    text: String,
    onTextChange: (String) -> Unit,
    autofocus: Boolean = false,
    multiline: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        label = { Text(label) },
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        maxLines = if (multiline) 8 else 1,
        shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = semantic.surfaceFill,
            unfocusedContainerColor = semantic.surfaceFill,
            focusedBorderColor = cs.primary.copy(alpha = 0.5f),
            unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.4f),
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** _ScopeChoiceCard L1213-1276 — selected primary 16%/55% border, else surfaceFill. */
@Composable
private fun ScopeChoiceCard(
    label: String,
    selected: Boolean,
    onTap: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val isDark = semantic.isDark
    Box(
        modifier = Modifier
            .background(
                if (selected) cs.primary.copy(alpha = 0.16f) else semantic.surfaceFill,
                RoundedCornerShape(MemoRadius.INNER_DP.dp),
            )
            .border(
                1.dp,
                if (selected) cs.primary.copy(alpha = 0.55f) else cs.outlineVariant.copy(alpha = if (isDark) 0.14f else 0.12f),
                RoundedCornerShape(MemoRadius.INNER_DP.dp),
            )
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) cs.primary else cs.onSurface.copy(alpha = 0.8f),
            ),
        )
    }
}

private val REGEX_JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** assistant_regex.dart fromJson — tolerant parse of the raw assistant payload. */
internal fun JsonElement.toRegex(): AssistantRegex? =
    runCatching {
        if (this is kotlinx.serialization.json.JsonObject) {
            REGEX_JSON.decodeFromString(AssistantRegex.serializer(), toString())
        } else null
    }.getOrNull()

internal fun AssistantRegex.toJsonElement(): JsonElement =
    REGEX_JSON.parseToJsonElement(REGEX_JSON.encodeToString(AssistantRegex.serializer(), this))

package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.db.PayloadEntityDao
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 1:1 port of user_profile_page.dart — structured user profile fields
 * (§14.4/§5.7) backed by the drift-compatible `user_profile_field_rows`
 * table (payload: {id, value, source, updatedAt}, source 'manual'),
 * known keys first, then the custom.* section with an add row, and the
 * field form as a bottom sheet with save/clear/cancel (L88-163, 195-257).
 */
data class ProfileField(val key: String, val value: String)

object UserProfileRepository {
    /** user_profile_field.dart isValidKey pattern. */
    private val validKeyPattern = Regex(
        "^(preferred_name|gender|pronouns|preferred_language|timezone|occupation|location|custom\\.[A-Za-z0-9_\\-]{1,32})$",
    )

    /** user_profile_field.dart knownKeys order. */
    val knownKeys = listOf(
        "preferred_name", "gender", "pronouns",
        "preferred_language", "timezone", "occupation", "location",
    )

    fun isValidKey(key: String): Boolean = validKeyPattern.matches(key)

    fun fields(container: AppContainerImpl): List<ProfileField> =
        PayloadEntityDao(container.database.writableDatabase, "user_profile_field_rows")
            .getAll()
            .mapNotNull { row ->
                runCatching {
                    val obj = row.payload.jsonObjectSafe()
                    ProfileField(
                        key = obj["id"] ?: row.id,
                        value = obj["value"] ?: "",
                    )
                }.getOrNull()
            }

    fun put(
        container: AppContainerImpl,
        key: String,
        value: String,
        source: String = "manual",
    ) {
        val dao = PayloadEntityDao(container.database.writableDatabase, "user_profile_field_rows")
        val existing = dao.get(key)
        val payload = buildJsonObject {
            put("id", key)
            put("value", value)
            put("source", source)
            put("updatedAt", System.currentTimeMillis() * 1000L)
        }
        dao.upsert(key, payload.toString(), sortOrder = existing?.sortOrder ?: dao.nextSortOrder())
    }

    fun remove(container: AppContainerImpl, key: String) {
        PayloadEntityDao(container.database.writableDatabase, "user_profile_field_rows").delete(key)
    }
}

private fun String.jsonObjectSafe(): Map<String, String> {
    val obj = kotlinx.serialization.json.Json.parseToJsonElement(this)
    val out = mutableMapOf<String, String>()
    (obj as? kotlinx.serialization.json.JsonObject)?.forEach { (k, v) ->
        (v as? kotlinx.serialization.json.JsonPrimitive)?.let { out[k] = it.content }
    }
    return out
}

@Composable
fun UserProfileScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    var fields by remember { mutableStateOf<List<ProfileField>>(emptyList()) }
    var editTarget by remember { mutableStateOf<EditTarget?>(null) }
    // Captured in composable scope: onSave's lambda is not @Composable.
    val invalidKeyMessage = stringResource(UiR.string.user_profile_invalid_key)

    LaunchedEffect(Unit) {
        fields = withContext(Dispatchers.IO) { UserProfileRepository.fields(container) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        MemoTopBar(
            title = stringResource(UiR.string.user_profile_page_title),
            onBack = onBack,
            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        ) {
            val byKey = fields.associateBy { it.key }
            val customKeys = fields.map { it.key }.filter { it.startsWith("custom.") }

            item {
                SectionCard {
                    UserProfileRepository.knownKeys.forEachIndexed { i, key ->
                        ProfileRow(
                            title = profileKeyLabel(key),
                            value = byKey[key]?.value,
                            onTap = { editTarget = EditTarget(key = key, current = byKey[key]?.value, isCustom = false, isNewCustom = false) },
                        )
                        if (i != UserProfileRepository.knownKeys.lastIndex) DividerRow()
                    }
                }
            }
            item { Spacer(Modifier.height(18.dp)) }
            item {
                Text(
                    text = stringResource(UiR.string.user_profile_custom_section),
                    modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    ),
                )
            }
            item {
                SectionCard {
                    customKeys.forEachIndexed { i, key ->
                        ProfileRow(
                            title = key,
                            value = byKey[key]?.value,
                            onTap = { editTarget = EditTarget(key = key, current = byKey[key]?.value, isCustom = true, isNewCustom = false) },
                        )
                        DividerRow()
                    }
                    // L231-253: add-custom row.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                editTarget = EditTarget(key = "", current = null, isCustom = true, isNewCustom = true)
                            }
                            .padding(start = 14.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Lucide.Plus, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(UiR.string.user_profile_add_custom),
                            style = TextStyle(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            }
        }
    }

    editTarget?.let { target ->
        ProfileFieldSheet(
            target = target,
            onDismiss = { editTarget = null },
            onSave = { key, value, cleared ->
                val finalKey = if (target.isNewCustom) key.trim() else target.key
                val finalValue = value.trim()
                if (cleared || finalValue.isEmpty()) {
                    if (UserProfileRepository.isValidKey(finalKey)) {
                        UserProfileRepository.remove(container, finalKey)
                    }
                    fields = UserProfileRepository.fields(container)
                    editTarget = null
                } else if (!UserProfileRepository.isValidKey(finalKey)) {
                    SnackbarManager.show(
                        AppNotification(
                            message = invalidKeyMessage,
                            type = NotificationType.ERROR,
                        ),
                    )
                } else {
                    UserProfileRepository.put(container, finalKey, finalValue)
                    fields = UserProfileRepository.fields(container)
                    editTarget = null
                }
            },
        )
    }
}

/** Edit target: known key, existing custom key, or a new custom field. */
private data class EditTarget(
    val key: String,
    val current: String?,
    val isCustom: Boolean,
    val isNewCustom: Boolean,
)

/** L67-86 _knownLabel. */
@Composable
private fun profileKeyLabel(key: String): String = when (key) {
    "preferred_name" -> stringResource(UiR.string.user_profile_preferred_name)
    "gender" -> stringResource(UiR.string.user_profile_gender)
    "pronouns" -> stringResource(UiR.string.user_profile_pronouns)
    "preferred_language" -> stringResource(UiR.string.user_profile_preferred_language)
    "timezone" -> stringResource(UiR.string.user_profile_timezone)
    "occupation" -> stringResource(UiR.string.user_profile_occupation)
    "location" -> stringResource(UiR.string.user_profile_location)
    else -> key
}

/** L481-541 _ProfileRow: semibold title + value/empty hint + chevron. */
@Composable
private fun ProfileRow(title: String, value: String?, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val emptyLabel = stringResource(UiR.string.user_profile_empty_value)
    val isEmpty = value.isNullOrEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(start = 14.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface.copy(alpha = 0.9f),
                ),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (isEmpty) emptyLabel else value,
                style = TextStyle(
                    fontSize = 13.5.sp,
                    color = if (isEmpty) cs.onSurface.copy(alpha = 0.4f) else cs.onSurface.copy(alpha = 0.85f),
                ),
            )
        }
        Icon(Lucide.ChevronRight, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.35f), modifier = Modifier.size(18.dp))
    }
}

/** L272-479 _ProfileFieldForm (mobile sheet): key/value fields + actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileFieldSheet(
    target: EditTarget,
    onDismiss: () -> Unit,
    onSave: (key: String, value: String, cleared: Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val title = if (target.isNewCustom) {
        stringResource(UiR.string.user_profile_add_custom)
    } else if (target.isCustom) {
        target.key
    } else {
        profileKeyLabel(target.key)
    }
    var key by remember { mutableStateOf(if (target.isNewCustom) "custom." else target.key) }
    var value by remember { mutableStateOf(target.current ?: "") }
    val canClear = !target.current.isNullOrEmpty()

    ModalBottomSheet(containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
sheetState = rememberMemoSheetState(), onDismissRequest = onDismiss, dragHandle = null) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            MemoSheetHandle()
            Text(
                text = title,
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            if (target.isNewCustom) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(stringResource(UiR.string.memory_ui_custom_key_label)) },
                    placeholder = { Text(stringResource(UiR.string.user_profile_custom_key_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(UiR.string.memory_ui_value_label)) },
                placeholder = { Text(stringResource(UiR.string.user_profile_custom_value_hint)) },
                minLines = 1,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                if (canClear) {
                    TextButton(
                        onClick = { onSave(key, value, true) },
                    ) {
                        Icon(Lucide.Trash2, contentDescription = null, tint = cs.error, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(UiR.string.user_profile_clear), color = cs.error)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(UiR.string.custom_theme_cancel))
                }
                TextButton(onClick = { onSave(key, value, false) }) {
                    Text(stringResource(UiR.string.user_profile_save))
                }
            }
        }
    }
}

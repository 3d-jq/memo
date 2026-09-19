package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.psyche.memo.common.Haptics
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.ui.theme.LocalSemanticColors

/**
 * Port of assistant_settings_edit_custom_request_tab.dart: two section cards
 * (custom headers / custom body) whose key/value rows persist on every edit.
 */
@Composable
fun AssistantEditCustomRequestTab(
    assistant: Assistant,
    onEdit: ((Assistant) -> Assistant) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    var headers by remember(assistant.id) { mutableStateOf(assistant.customHeaders) }
    var body by remember(assistant.id) { mutableStateOf(assistant.customBody) }

    fun setHeaders(next: List<Map<String, String>>) {
        headers = next
        onEdit { it.copy(customHeaders = next) }
    }

    fun setBody(next: List<Map<String, String>>) {
        body = next
        onEdit { it.copy(customBody = next) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = 8.dp, bottom = 16.dp),
    ) {
        // Headers card
        SettingsSectionCard {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.assistant_edit_custom_headers_title),
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            Haptics.light(view)
                            setHeaders(headers + mapOf("name" to "", "value" to ""))
                        }.padding(vertical = 4.dp),
                    ) {
                        Icon(
                            Lucide.Plus,
                            contentDescription = null,
                            tint = cs.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.assistant_edit_custom_headers_add),
                            style = TextStyle(color = cs.primary, fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                headers.forEachIndexed { index, header ->
                    HeaderRow(
                        name = header["name"] ?: "",
                        value = header["value"] ?: "",
                        onChanged = { name, value ->
                            setHeaders(headers.mapIndexed { i, h ->
                                if (i == index) mapOf("name" to name, "value" to value) else h
                            })
                        },
                        onDelete = {
                            Haptics.light(view)
                            setHeaders(headers.filterIndexed { i, _ -> i != index })
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                }
                if (headers.isEmpty()) {
                    Text(
                        text = stringResource(R.string.assistant_edit_custom_headers_empty),
                        style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Body card
        SettingsSectionCard {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.assistant_edit_custom_body_title),
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            Haptics.light(view)
                            setBody(body + mapOf("key" to "", "value" to ""))
                        }.padding(vertical = 4.dp),
                    ) {
                        Icon(
                            Lucide.Plus,
                            contentDescription = null,
                            tint = cs.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.assistant_edit_custom_body_add),
                            style = TextStyle(color = cs.primary, fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                body.forEachIndexed { index, entry ->
                    BodyRow(
                        key = entry["key"] ?: "",
                        value = entry["value"] ?: "",
                        onChanged = { key, value ->
                            setBody(body.mapIndexed { i, e ->
                                if (i == index) mapOf("key" to key, "value" to value) else e
                            })
                        },
                        onDelete = {
                            Haptics.light(view)
                            setBody(body.filterIndexed { i, _ -> i != index })
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                }
                if (body.isEmpty()) {
                    Text(
                        text = stringResource(R.string.assistant_edit_custom_body_empty),
                        style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                    )
                }
            }
        }
    }
}

/** _HeaderRow L232-343 — name + value fields with a delete icon. */
@Composable
private fun HeaderRow(
    name: String,
    value: String,
    onChanged: (String, String) -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CustomRequestField(
                label = stringResource(R.string.assistant_edit_header_name_label),
                text = name,
                singleLine = true,
                onTextChange = { onChanged(it, value) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(
                    Lucide.Trash2,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        CustomRequestField(
            label = stringResource(R.string.assistant_edit_header_value_label),
            text = value,
            singleLine = true,
            onTextChange = { onChanged(name, it) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** _BodyRow L345-459 — key field + multiline value field. */
@Composable
private fun BodyRow(
    key: String,
    value: String,
    onChanged: (String, String) -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CustomRequestField(
                label = stringResource(R.string.assistant_edit_body_key_label),
                text = key,
                singleLine = true,
                onTextChange = { onChanged(it, value) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(
                    Lucide.Trash2,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        CustomRequestField(
            label = stringResource(R.string.assistant_edit_body_value_label),
            text = value,
            singleLine = false,
            minLines = 3,
            maxLines = 6,
            onTextChange = { onChanged(key, it) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** _dec L286-305 — filled surfaceCardFill, r12, transparent border, primary focus. */
@Composable
private fun CustomRequestField(
    label: String,
    text: String,
    singleLine: Boolean,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    maxLines: Int = 1,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        maxLines = if (singleLine) 1 else maxLines,
        shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = semantic.surfaceCardFill,
            unfocusedContainerColor = semantic.surfaceCardFill,
            focusedBorderColor = cs.primary.copy(alpha = 0.4f),
            unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
        modifier = modifier,
    )
}

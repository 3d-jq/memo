package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldDefaults
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
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.DefaultModelPrefs
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.psyche.memo.provider.OcrService
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlinx.serialization.json.JsonPrimitive

/**
 * Port of ocr_prompt_sheet.dart: thinking switch + the OCR system prompt with
 * reset / save. Persists to the same preference keys the default-model page
 * edits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrPromptSheet(
    container: AppContainerImpl,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val prefs = container.preferenceRepository
    var thinking by remember {
        mutableStateOf(OcrService.settingsOf(prefs).thinking)
    }
    var prompt by remember {
        mutableStateOf(OcrService.settingsOf(prefs).prompt)
    }

    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        containerColor = cs.overlaySurfaceColor(),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        thinking = !thinking
                        prefs.writeJson(OcrService.THINKING_KEY, JsonPrimitive(if (thinking) 1 else 0).toString())
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.title_model_thinking_title),
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = cs.onSurface.copy(alpha = 0.92f)),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(16.dp))
                IosSwitch(
                    value = thinking,
                    onValueChanged = { v ->
                        thinking = v
                        prefs.writeJson(OcrService.THINKING_KEY, JsonPrimitive(if (v) 1 else 0).toString())
                    },
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.default_model_page_prompt_label),
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                minLines = 4,
                maxLines = 8,
                placeholder = {
                    Text(
                        stringResource(R.string.default_model_page_ocr_prompt_hint),
                        style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.45f)),
                    )
                },
                shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = semantic.surfaceFill,
                    unfocusedContainerColor = semantic.surfaceFill,
                    focusedBorderColor = cs.primary.copy(alpha = 0.5f),
                    unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.4f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    thinking = false
                    prefs.writeJson(OcrService.THINKING_KEY, JsonPrimitive(0).toString())
                    prompt = DefaultModelPrefs.DEFAULT_OCR_PROMPT
                    prefs.writeJson(OcrService.PROMPT_KEY, JsonPrimitive(DefaultModelPrefs.DEFAULT_OCR_PROMPT).toString())
                }) {
                    Text(stringResource(R.string.default_model_page_reset_default))
                }
                Spacer(Modifier.weight(1f))
                IosTileButton(
                    label = stringResource(R.string.default_model_page_save),
                    icon = Lucide.Check,
                    backgroundColor = cs.primary,
                    onClick = {
                        prefs.writeJson(OcrService.PROMPT_KEY, JsonPrimitive(prompt.trim()).toString())
                        onDismiss()
                    },
                )
            }
        }
    }
}

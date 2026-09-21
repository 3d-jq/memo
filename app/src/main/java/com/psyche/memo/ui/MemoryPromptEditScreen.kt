package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RotateCcw
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.withAlpha
import androidx.compose.foundation.border
import com.psyche.memo.AppContainerImpl

/**
 * memory_settings_page.dart L502-847 — `_MemoryPromptEditPage` (mobile push
 * variant). Edits the prompt template matching
 * [MemorySettingsState.resolvedPromptLang]; Smart Add also edits the batch
 * prompt. Reset restores MemoryPrompts defaults.
 */
@Composable
internal fun MemoryPromptEditOverlay(
    container: AppContainerImpl,
    entry: PromptEntry,
    onClose: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current
    val state = remember { MemorySettingsState(container) }
    // 同屏二级页：系统返回要回到「记忆设置」而不是 pop 掉整条 memory_settings 路由
    //（用户 2026-09-16「提示词模板里面的界面点击没有返回上一级，直接回到主设置界面」）。
    OverlayBackHandler(onClose)

    // didChangeDependencies hydration (L541-553): language resolved once.
    val lang = remember { state.resolvedPromptLang() }
    val isZh = lang == MemoryPromptLang.zh
    val isSmartAdd = entry.kind == MemoryPromptKind.SMART_ADD

    var mainText by remember { mutableStateOf(state.prompt(entry.kind, isZh)) }
    var batchText by remember { mutableStateOf(if (isSmartAdd) state.smartAddBatchPrompt(isZh) else "") }

    fun save() {
        state.setPrompt(entry.kind, isZh, mainText)
        if (isSmartAdd) state.setSmartAddBatchPrompt(isZh, batchText)
        onClose()
    }

    fun reset() {
        state.resetPrompt(entry.kind, isZh)
        mainText = state.prompt(entry.kind, isZh)
        if (isSmartAdd) batchText = state.smartAddBatchPrompt(isZh)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // AppBar (L803-843): back, title, reset + save actions.
        MemoTopBar(
            title = stringResource(entry.titleRes),
            onBack = onClose,
        ) {
            TopBarAction(
                icon = Lucide.RotateCcw,
                label = stringResource(UiR.string.memory_prompt_edit_reset),
                onClick = { reset() },
            )
            TopBarAction(
                icon = Lucide.Check,
                label = stringResource(UiR.string.memory_prompt_edit_save),
                onClick = { save() },
                color = cs.primary,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 32.dp),
        ) {
            // Editor body (L690-731).
            Text(
                stringResource(entry.subtitleRes),
                style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp, color = withAlpha(cs.onSurface, 0.6)),
            )
            Spacer(Modifier.height(14.dp))
            if (isSmartAdd) {
                Text(
                    stringResource(UiR.string.memory_prompt_edit_section_per_item),
                    style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.7)),
                )
                Spacer(Modifier.height(6.dp))
            }
            MemoryPromptField(value = mainText, onValueChange = { mainText = it })
            if (isSmartAdd) {
                Spacer(Modifier.height(18.dp))
                Text(
                    stringResource(UiR.string.memory_prompt_edit_section_batch),
                    style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.7)),
                )
                Spacer(Modifier.height(6.dp))
                MemoryPromptField(value = batchText, onValueChange = { batchText = it })
            }
        }
    }
}

/** _PromptField (L867-896) — 14dp-radius card, hairline border, 13.5sp text. */
@Composable
private fun MemoryPromptField(value: String, onValueChange: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(app.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(1.dp, app.hairlineStrong, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .height(220.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp, color = cs.onSurface),
            cursorBrush = SolidColor(cs.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

package com.psyche.memo.ui.chat

import com.psyche.memo.ui.theme.MemoRadius
import com.psyche.memo.ui.overlaySurfaceColor
import com.psyche.memo.ui.rememberMemoSheetState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.psyche.memo.ui.R as UiR

/**
 * 翻译目标语言选择（language_select_sheet.dart 1:1 移植）：顶部 20dp 圆角
 * sheet + 40×4 拖柄（onSurface@0.2）；9 个语言行（48dp、圆角14 tile、
 * flag 20sp + 10 间距 + 名称 15sp medium、行间 4）+ 清除翻译行
 * （Lucide.X error 色 20 + 文案 15sp medium error，上下 8）。
 * 选择 [CLEAR_TRANSLATION] 表示清空已有翻译（translation_service.dart
 * TranslationResultType.cleared）。
 */
data class TranslateLanguage(
    val code: String,
    val displayName: String,
    val flag: String,
) {
    companion object {
        /** translation_service.dart:110 —— `__clear__` 清除翻译。 */
        const val CLEAR_TRANSLATION = "__clear__"
    }
}

/** language_select_sheet.dart:28-82 supportedLanguages（注释掉的条目不同步）。 */
val supportedLanguages = listOf(
    TranslateLanguage("zh-CN", "Simplified Chinese", "🇨🇳"),
    TranslateLanguage("en", "English", "🇺🇸"),
    TranslateLanguage("zh-TW", "Traditional Chinese", "🇨🇳"),
    TranslateLanguage("ja", "Japanese", "🇯🇵"),
    TranslateLanguage("ko", "Korean", "🇰🇷"),
    TranslateLanguage("fr", "French", "🇫🇷"),
    TranslateLanguage("de", "German", "🇩🇪"),
    TranslateLanguage("it", "Italian", "🇮🇹"),
    TranslateLanguage("es", "Spanish", "🇪🇸"),
)

/** sheet 显示名（_getLanguageDisplayName L305-328 的本地化键映射）。 */
private fun displayNameRes(code: String): Int = when (code) {
    "zh-CN" -> UiR.string.language_display_simplified_chinese
    "en" -> UiR.string.language_display_english
    "zh-TW" -> UiR.string.language_display_traditional_chinese
    "ja" -> UiR.string.language_display_japanese
    "ko" -> UiR.string.language_display_korean
    "fr" -> UiR.string.language_display_french
    "de" -> UiR.string.language_display_german
    "it" -> UiR.string.language_display_italian
    "es" -> UiR.string.language_display_spanish
    else -> UiR.string.language_display_english
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSelectSheet(
    onSelect: (TranslateLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        dragHandle = null, // 原版自绘 40x4 拖柄，禁用 Material 默认 handle
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        containerColor = cs.overlaySurfaceColor(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // language_select_sheet.dart:209 fromLTRB(12,4,12,12)
                .padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Drag handle (L196-206).
            Box(
                modifier = Modifier
                    .padding(top = 6.dp, bottom = 6.dp)
                    .align(Alignment.CenterHorizontally)
                    .size(width = 40.dp, height = 4.dp)
                    .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
            )
            for (lang in supportedLanguages) {
                LanguageRow(lang) { onSelect(lang) }
            }
            Spacer(Modifier.height(8.dp))
            // Clear translation row (L217-255).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(
                        cs.surfaceContainerHigh.copy(alpha = 0.6f),
                        RoundedCornerShape(MemoRadius.INNER_DP.dp),
                    )
                    .clickable { onSelect(TranslateLanguage(TranslateLanguage.CLEAR_TRANSLATION, "", "")) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Lucide.X,
                    contentDescription = null,
                    tint = cs.error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = stringResource(UiR.string.language_select_sheet_clear_button),
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = cs.error,
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun LanguageRow(lang: TranslateLanguage, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(48.dp)
            .background(
                cs.surfaceContainerHigh.copy(alpha = 0.6f),
                RoundedCornerShape(MemoRadius.INNER_DP.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Flag only (L287).
        Text(text = lang.flag, style = androidx.compose.ui.text.TextStyle(fontSize = 20.sp))
        Spacer(Modifier.size(10.dp))
        Text(
            text = stringResource(displayNameRes(lang.code)),
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import com.psyche.memo.ui.slider.MemoSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.CaseSensitive
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Code
import com.composables.icons.lucide.FoldVertical
import com.composables.icons.lucide.Hash
import com.composables.icons.lucide.ListOrdered
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.TextSelect
import com.composables.icons.lucide.WrapText
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ui.R as UiR
import kotlin.math.roundToInt

/**
 * 1:1 port of display_settings_page.dart L1765-1997 — RenderingSettingsPage.
 *
 * 6 switches + conditional number row (auto-collapse threshold, clamp 1-999,
 * default 2 — settings_provider.dart:5307) + Android-only mobile code-block
 * wrap switch. Keys are the original Flutter prefs keys (L268-293).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RenderingSettingsScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme

    fun readBool(key: String, default: Boolean): Boolean =
        container.preferenceRepository.readJson(key)?.let { it == "1" } ?: default
    fun writeBool(key: String, value: Boolean) {
        container.preferenceRepository.writeJson(key, if (value) "1" else "0")
    }

    var dollarLatex by remember { mutableStateOf(readBool("display_enable_dollar_latex_v1", true)) }
    var mathRendering by remember { mutableStateOf(readBool("display_enable_math_rendering_v1", true)) }
    var userMarkdown by remember { mutableStateOf(readBool("display_enable_user_markdown_v1", true)) }
    var reasoningMarkdown by remember { mutableStateOf(readBool("display_enable_reasoning_markdown_v1", true)) }
    var assistantMarkdown by remember { mutableStateOf(readBool("display_enable_assistant_markdown_v1", true)) }
    // settings_provider.dart:5296/5285 — autoCollapse / mobileWrap default false.
    var autoCollapse by remember { mutableStateOf(readBool("display_auto_collapse_code_block_v1", false)) }
    var mobileWrap by remember { mutableStateOf(readBool("display_mobile_code_block_wrap_v1", false)) }
    // 源码 settings_provider.dart:5307 —— int _autoCollapseCodeBlockLines = 2。
    // 已存值与草稿分离：解析失败回退当前已存值（C4）。
    var collapseLines by remember { mutableIntStateOf(2) }
    var collapseLinesText by remember { mutableStateOf("2") }
    LaunchedEffect(Unit) {
        // LaunchedEffect 体默认跑在组合线程上，里面的 readJson 是真会打 SQLite 的
        withContext(Dispatchers.IO) {
            val stored = container.preferenceRepository.readJson("display_auto_collapse_code_block_lines_v1")
            collapseLines = (stored?.toIntOrNull() ?: 2).coerceIn(1, 999)
            collapseLinesText = collapseLines.toString()
        }
}
    // 源码 L1903-1924 —— _commit：解析失败回落当前已存值，clamp 1-999。
    fun commitLines(text: String) {
        val parsed = text.toIntOrNull() ?: run { collapseLinesText = collapseLines.toString(); return }
        collapseLines = parsed.coerceIn(1, 999)
        collapseLinesText = collapseLines.toString()
        container.preferenceRepository.writeJson(
            "display_auto_collapse_code_block_lines_v1",
            collapseLines.toString(),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        MemoTopBar(
            title = stringResource(UiR.string.display_settings_page_rendering_settings_title),
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp,
            ),
        ) {
            // 分类重组（用户要求对齐设置主屏的分组卡片形态）：数学公式 /
            // Markdown 渲染 / 代码块。行顺序与 prefs key 均保持源序不动。
            item(key = "h_math") {
                SectionHeader(stringResource(UiR.string.display_settings_page_section_math), first = true)
            }
            item(key = "c_math") {
                SettingsSectionCard {
                    SettingsSwitchRow(
                        Lucide.Hash,
                        stringResource(UiR.string.display_settings_page_enable_dollar_latex_title),
                        value = dollarLatex,
                        onToggle = { dollarLatex = it; writeBool("display_enable_dollar_latex_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.Code,
                        stringResource(UiR.string.display_settings_page_enable_math_title),
                        value = mathRendering,
                        onToggle = { mathRendering = it; writeBool("display_enable_math_rendering_v1", it) },
                    )
                }
            }
            item(key = "gap_math") { Spacer(Modifier.height(12.dp)) }
            item(key = "h_md") {
                SectionHeader(stringResource(UiR.string.display_settings_page_section_markdown))
            }
            item(key = "c_md") {
                SettingsSectionCard {
                    SettingsSwitchRow(
                        Lucide.TextSelect,
                        stringResource(UiR.string.display_settings_page_enable_user_markdown_title),
                        value = userMarkdown,
                        onToggle = { userMarkdown = it; writeBool("display_enable_user_markdown_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.Brain,
                        stringResource(UiR.string.display_settings_page_enable_reasoning_markdown_title),
                        value = reasoningMarkdown,
                        onToggle = { reasoningMarkdown = it; writeBool("display_enable_reasoning_markdown_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.MessageSquare,
                        stringResource(UiR.string.display_settings_page_enable_assistant_markdown_title),
                        value = assistantMarkdown,
                        onToggle = { assistantMarkdown = it; writeBool("display_enable_assistant_markdown_v1", it) },
                    )
                }
            }
            item(key = "gap_md") { Spacer(Modifier.height(12.dp)) }
            item(key = "h_code") {
                SectionHeader(stringResource(UiR.string.display_settings_page_section_code_block))
            }
            item(key = "c_code") {
                SettingsSectionCard {
                    SettingsSwitchRow(
                        Lucide.FoldVertical,
                        stringResource(UiR.string.display_settings_page_auto_collapse_code_block_title),
                        value = autoCollapse,
                        onToggle = { autoCollapse = it; writeBool("display_auto_collapse_code_block_v1", it) },
                    )
                    // L1927-1929: threshold row only while auto-collapse is on.
                    if (autoCollapse) {
                        SettingsIosDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(modifier = Modifier.width(36.dp)) {
                                Icon(
                                    Lucide.ListOrdered,
                                    contentDescription = null,
                                    tint = cs.onSurface.copy(alpha = 0.9f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = stringResource(UiR.string.display_settings_page_auto_collapse_code_block_lines_title),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                style = TextStyle(fontSize = 15.sp, color = cs.onSurface),
                            )
                            // L1958-1987: 44-80dp number field, digits only;
                            // commits on blur / IME done (C4).
                            val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                            var lineFocused by remember { mutableStateOf(false) }
                            OutlinedTextField(
                                value = collapseLinesText,
                                onValueChange = { v ->
                                    collapseLinesText = v.filter { it.isDigit() }.take(3)
                                },
                                modifier = Modifier
                                    .width(64.dp)
                                    .onFocusChanged {
                                        if (lineFocused && !it.hasFocus) commitLines(collapseLinesText)
                                        lineFocused = it.hasFocus
                                    },
                                singleLine = true,
                                textStyle = TextStyle(
                                    fontSize = 15.sp,
                                    textAlign = TextAlign.Center,
                                    color = cs.onSurface,
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = cs.surfaceCardColorCompat(),
                                    unfocusedContainerColor = cs.surfaceCardColorCompat(),
                                    focusedBorderColor = cs.primary,
                                    unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.18f),
                                ),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(UiR.string.display_settings_page_auto_collapse_code_block_lines_unit),
                                style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                            )
                        }
                    }
                    // L1916-1924: Android/iOS only mobile wrap switch.
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.WrapText,
                        stringResource(UiR.string.display_settings_page_mobile_code_block_wrap_title),
                        value = mobileWrap,
                        onToggle = { mobileWrap = it; writeBool("display_mobile_code_block_wrap_v1", it) },
                    )
                }
            }
            item(key = "tail") { Spacer(Modifier.height(12.dp)) }
        }
    }



}

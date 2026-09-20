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
import com.psyche.memo.ui.chat.ThinkingIndicatorSettings
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
    // 流式等待提示的三个自定义项（我们的新键，不来自原项目）。
    var indicatorFontSize by remember { mutableFloatStateOf(ThinkingIndicatorSettings.DEFAULT_FONT_SP) }
    var indicatorColorArgb by remember { mutableStateOf<Int?>(null) }
    var indicatorPhrases by remember {
        mutableStateOf(com.psyche.memo.ui.chat.ThinkingPhrases.ALL)
    }
    var sizeSheetVisible by remember { mutableStateOf(false) }
    var colorSheetVisible by remember { mutableStateOf(false) }
    var phrasesSheetVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // LaunchedEffect 体默认跑在组合线程上，里面的 readJson 是真会打 SQLite 的
        withContext(Dispatchers.IO) {
            val stored = container.preferenceRepository.readJson("display_auto_collapse_code_block_lines_v1")
            collapseLines = (stored?.toIntOrNull() ?: 2).coerceIn(1, 999)
            collapseLinesText = collapseLines.toString()
            val indicator = ThinkingIndicatorSettings.fromPrefs { key ->
                container.preferenceRepository.readJson(key)
            }
            indicatorFontSize = indicator.fontSizeSp
            indicatorColorArgb = indicator.colorArgb
            indicatorPhrases = indicator.phrases
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
            // 流式等待提示（扫光文字）—— 用户 2026-09-13「加一个设置功能，让用户可以
            // 自定义文字大小、颜色、提示词」。这个指示器本身是有意偏离原版的（原版三点
            // 脉动），所以这三个键在原项目里没有对应物。
            item(key = "h_thinking") {
                SectionHeader(stringResource(UiR.string.display_settings_page_section_thinking_indicator))
            }
            item(key = "c_thinking") {
                SettingsSectionCard {
                    SettingsRow(
                        com.composables.icons.lucide.Lucide.CaseSensitive,
                        stringResource(UiR.string.display_settings_page_thinking_indicator_font_size_title),
                        detailText = "${indicatorFontSize.roundToInt()}",
                        onTap = { sizeSheetVisible = true },
                    )
                    SettingsIosDivider()
                    SettingsRow(
                        com.composables.icons.lucide.Lucide.Palette,
                        stringResource(UiR.string.display_settings_page_thinking_indicator_color_title),
                        detailText = indicatorColorArgb
                            ?.let { com.psyche.memo.ui.chat.ThinkingIndicatorSettings.toHex(it) }
                            ?: stringResource(UiR.string.display_settings_page_thinking_indicator_color_follow_theme),
                        onTap = { colorSheetVisible = true },
                    )
                    SettingsIosDivider()
                    SettingsRow(
                        com.composables.icons.lucide.Lucide.MessageCircle,
                        stringResource(UiR.string.display_settings_page_thinking_indicator_phrases_title),
                        detailText = indicatorPhrases.take(3).joinToString("、") +
                            if (indicatorPhrases.size > 3) " …" else "",
                        onTap = { phrasesSheetVisible = true },
                    )
                }
            }
            item(key = "tail2") { Spacer(Modifier.height(12.dp)) }
        }
    }

    // ---- 文字大小 ----
    if (sizeSheetVisible) {
        ModalBottomSheet(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
sheetState = rememberMemoSheetState(),
            onDismissRequest = { sizeSheetVisible = false },
            dragHandle = null,
        ) {
            MemoSheetHandle()
            var value by remember { mutableFloatStateOf(indicatorFontSize) }
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${ThinkingIndicatorSettings.MIN_FONT_SP.roundToInt()}",
                        style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.7f)),
                    )
                    Spacer(Modifier.width(8.dp))
                    MemoSlider(
                        value = value,
                        onValueChange = { raw ->
                            value = ThinkingIndicatorSettings.clampFontSize(raw.toFloat())
                            indicatorFontSize = value
                            container.preferenceRepository.writeJson(
                                ThinkingIndicatorSettings.FONT_SIZE_KEY,
                                value.toString(),
                            )
                        },
                        valueRange = ThinkingIndicatorSettings.MIN_FONT_SP..ThinkingIndicatorSettings.MAX_FONT_SP,
                        modifier = Modifier.weight(1f),
                        valueLabel = { "${it.roundToInt()}" },
                    )
                    Spacer(Modifier.width(8.dp))
                    com.psyche.memo.ui.slider.SliderValueLabel(
                        text = "${value.roundToInt()}",
                        // 定宽（最宽是最大字号 "28"）：数值变宽不会挤短左边的 slider。
                        widest = "${ThinkingIndicatorSettings.MAX_FONT_SP.roundToInt()}",
                        style = TextStyle(fontSize = 12.sp, color = cs.onSurface),
                    )
                }
                Spacer(Modifier.height(10.dp))
                // 实时预览：真的用那个组件，改完立刻看到效果。
                com.psyche.memo.ui.chat.ThinkingShimmerText(
                    phrases = indicatorPhrases,
                    fontSize = value.sp,
                    colorArgb = indicatorColorArgb,
                )
            }
        }
    }

    // ---- 文字颜色 ----
    if (colorSheetVisible) {
        ModalBottomSheet(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
sheetState = rememberMemoSheetState(),
            onDismissRequest = { colorSheetVisible = false },
            dragHandle = null,
        ) {
            MemoSheetHandle()
            var hex by remember {
                mutableStateOf(indicatorColorArgb?.let { ThinkingIndicatorSettings.toHex(it) } ?: "")
            }
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 18.dp)) {
                // 跟随主题（默认）+ 常用预设色。
                listOf<Pair<String, Int?>>(
                    stringResource(UiR.string.display_settings_page_thinking_indicator_color_follow_theme) to null,
                    "#6750A4" to ThinkingIndicatorSettings.parseColor("#6750A4"),
                    "#1B6EF3" to ThinkingIndicatorSettings.parseColor("#1B6EF3"),
                    "#00897B" to ThinkingIndicatorSettings.parseColor("#00897B"),
                    "#E65100" to ThinkingIndicatorSettings.parseColor("#E65100"),
                    "#C2185B" to ThinkingIndicatorSettings.parseColor("#C2185B"),
                    "#5E35B1" to ThinkingIndicatorSettings.parseColor("#5E35B1"),
                ).forEach { (label, argb) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                indicatorColorArgb = argb
                                if (argb == null) {
                                    container.preferenceRepository.remove(ThinkingIndicatorSettings.COLOR_KEY)
                                } else {
                                    container.preferenceRepository.writeJson(
                                        ThinkingIndicatorSettings.COLOR_KEY,
                                        ThinkingIndicatorSettings.toHex(argb),
                                    )
                                }
                                colorSheetVisible = false
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (argb == null) {
                            Icon(
                                com.composables.icons.lucide.Lucide.Palette,
                                contentDescription = null,
                                tint = cs.onSurface.copy(alpha = 0.9f),
                                modifier = Modifier.size(20.dp),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(Color(argb), androidx.compose.foundation.shape.RoundedCornerShape(MemoRadius.SMALL_DP.dp))
                                    .border(
                                        0.8.dp,
                                        cs.outlineVariant.copy(alpha = 0.42f),
                                        androidx.compose.foundation.shape.RoundedCornerShape(MemoRadius.SMALL_DP.dp),
                                    ),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(label, style = TextStyle(fontSize = 15.sp, color = cs.onSurface))
                        Spacer(Modifier.weight(1f))
                        if (indicatorColorArgb == argb) {
                            Icon(
                                com.composables.icons.lucide.Lucide.Check,
                                contentDescription = null,
                                tint = cs.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // 自定义色：填 #RRGGBB 后回车应用（与本工程既有的颜色输入一致）。
                OutlinedTextField(
                    value = hex,
                    onValueChange = { hex = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("#RRGGBB") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = cs.surfaceCardColorCompat(),
                        unfocusedContainerColor = cs.surfaceCardColorCompat(),
                        focusedBorderColor = cs.primary,
                        unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.18f),
                    ),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            ThinkingIndicatorSettings.parseColor(hex)?.let { argb ->
                                indicatorColorArgb = argb
                                container.preferenceRepository.writeJson(
                                    ThinkingIndicatorSettings.COLOR_KEY,
                                    ThinkingIndicatorSettings.toHex(argb),
                                )
                                colorSheetVisible = false
                            }
                        },
                    ),
                )
            }
        }
    }

    // ---- 提示词 ----
    if (phrasesSheetVisible) {
        ModalBottomSheet(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
sheetState = rememberMemoSheetState(),
            onDismissRequest = { phrasesSheetVisible = false },
            dragHandle = null,
        ) {
            MemoSheetHandle()
            var text by remember { mutableStateOf(indicatorPhrases.joinToString("\n")) }
            fun save(list: List<String>) {
                val cleaned = list.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                val next = cleaned.ifEmpty { com.psyche.memo.ui.chat.ThinkingPhrases.ALL }
                indicatorPhrases = next
                container.preferenceRepository.writeJson(
                    ThinkingIndicatorSettings.PHRASES_KEY,
                    ThinkingIndicatorSettings.encodePhrases(next),
                )
                phrasesSheetVisible = false
            }
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 18.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp),
                    placeholder = { Text(stringResource(UiR.string.display_settings_page_thinking_indicator_phrases_hint)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = cs.surfaceCardColorCompat(),
                        unfocusedContainerColor = cs.surfaceCardColorCompat(),
                        focusedBorderColor = cs.primary,
                        unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.18f),
                    ),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        save(com.psyche.memo.ui.chat.ThinkingPhrases.ALL)
                    }) {
                        Text(
                            stringResource(UiR.string.display_settings_page_thinking_indicator_phrases_reset),
                            color = cs.onSurface.copy(alpha = 0.7f),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { save(text.split('\n', ',', '，', '、')) }) {
                        Text(stringResource(UiR.string.model_detail_sheet_confirm_button), color = cs.primary)
                    }
                }
            }
        }
    }
}

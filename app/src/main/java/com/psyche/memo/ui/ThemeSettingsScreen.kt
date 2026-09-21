package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Settings2
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Copy
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.Palette
import com.psyche.memo.ui.theme.buildCustomThemePalette
import com.psyche.memo.ui.theme.allPalettes
import com.psyche.memo.ui.theme.themeChoices
import kotlinx.coroutines.launch

/**
 * 1:1 port of theme_settings_page.dart — dynamic color (Android), pure
 * background, palette list, custom themes (create/import/copy/edit/delete,
 * editor = SV area + hue bar + hex per color slot + name). The section header
 * trailing actions and row order mirror theme_settings_page.dart L87-165.
 */
@Composable
fun ThemeSettingsScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
    onOpenAdvanced: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val currentLanguage = LocalConfiguration.current.locales[0].language
    // 色卡/名字按当前明暗取色（上游 `LocalDarkMode.current` 的等价物）。
    val currentDark = com.psyche.memo.ui.theme.MemoTheme
        .resolve(paletteId = null, mode = ThemeState.mode, systemDark = isSystemInDarkTheme())
        .second
    // Compose 1.8+：LocalClipboardManager 已废弃——统一走 LocalClipboard + ClipEntry。
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    val copyMsg = stringResource(UiR.string.custom_theme_copied)

    var editorTheme by remember { mutableStateOf<CustomTheme?>(null) }
    var editorVisible by remember { mutableStateOf(false) }
    var importVisible by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<CustomTheme?>(null) }

    // Load once; ThemeState is the reactive store (also observed by root).
    LaunchedEffect(Unit) { ThemeState.load(container) }

    Column(modifier = Modifier.fillMaxSize()) {
        MemoTopBar(
            title = stringResource(UiR.string.display_settings_page_theme_settings_title),
            onBack = onBack,
            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenAdvanced) {
                Icon(
                    Lucide.Settings2,
                    contentDescription = stringResource(UiR.string.theme_advanced_settings_page_title),
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            // Mirrors kelivo's ListView padding: LTRB(16, 12, 16, 16).
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
        ) {
            // L90-108: dynamic color section (Android only upstream; this app
            // is Android-only).
            item { SectionHeader(stringResource(UiR.string.theme_settings_page_dynamic_color_section), first = true) }
            item {
                SectionCard {
                    SettingsSwitchRow(
                        icon = Lucide.Palette,
                        label = stringResource(UiR.string.theme_settings_page_use_dynamic_color_title),
                        tip = stringResource(UiR.string.theme_settings_page_use_dynamic_color_subtitle),
                        value = ThemeState.useDynamicColor,
                        onToggle = { v ->
                            ThemeState.setDynamicColor(container, v)
                            if (v && ThemeState.paletteId == ThemeState.CUSTOM_PALETTE_ID) {
                                ThemeState.setPalette(container, "default")
                            }
                        },
                    )
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
            item {
                SectionCard {
                    // L109-121: pure background switch (Square icon).
                    SettingsSwitchRow(
                        icon = Lucide.Square,
                        label = stringResource(UiR.string.theme_settings_page_use_pure_background_title),
                        tip = stringResource(UiR.string.theme_settings_page_use_pure_background_subtitle),
                        value = ThemeState.usePureBackground,
                        onToggle = { ThemeState.setPureBackground(container, it) },
                    )
                }
            }

            // L124-138: 预设主题 —— 布局/交互 1:1 照 RikkaHub 的 `PresetThemeButtonGroup`
            // （`ui/pages/setting/components/PresetThemeButton.kt`）：四列 FlowRow、
            // 每项是 48dp 圆形「色卡」（primaryContainer 打底 + 右上次色象限 +
            // 右下第三色象限 + 中心 primary 圆点，选中时圆点变大并在其上打勾）、
            // 名字在圆下方用主题色居中；最后一行用 Spacer(weight) 补空位保证列宽一致。
            // 用户 2026-09-14：「跟着人家一比一做 不然做出来不好看」。
            item { Spacer(Modifier.height(12.dp)) }
            item {
                val legacySelected = allPalettes
                    .firstOrNull { it.id == ThemeState.paletteId }
                    ?.takeIf { legacy -> themeChoices.none { it.id == legacy.id } }
                val palettes =
                    if (legacySelected != null) themeChoices + legacySelected else themeChoices
                SectionCard {
                    ThemeSwatchGrid(
                        palettes = palettes,
                        dark = currentDark,
                        selectedId = ThemeState.paletteId,
                        language = currentLanguage,
                        onSelect = { ThemeState.setPalette(container, it) },
                    )
                }
            }

            // L139-161: custom themes header with new/import actions.
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 18.dp, end = 8.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(UiR.string.theme_settings_page_custom_themes_section),
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = settingsSectionHeaderColor(cs),
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { editorTheme = null; editorVisible = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Lucide.Plus,
                            contentDescription = stringResource(UiR.string.custom_theme_new_theme),
                            tint = cs.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = { importVisible = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Lucide.Download,
                            contentDescription = stringResource(UiR.string.custom_theme_import_theme),
                            tint = cs.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            if (ThemeState.customThemes.isNotEmpty()) {
                item {
                    SectionCard {
                        val themes = ThemeState.customThemes
                        val customActive = ThemeState.paletteId == ThemeState.CUSTOM_PALETTE_ID
                        themes.forEachIndexed { i, theme ->
                            CustomThemeRow(
                                theme = theme,
                                dark = currentDark,
                                selected = customActive &&
                                    ThemeState.selectedCustomThemeId == theme.id,
                                nameFor = { t ->
                                    if (t.name.isEmpty()) {
                                        stringResource(UiR.string.theme_settings_page_custom_palette_name)
                                    } else t.name
                                },
                                onTap = { ThemeState.selectCustomTheme(container, theme.id) },
                                onCopy = {
                                    val payload = theme.export()
                                    clipboardScope.launch {
                                        clipboard.setClipEntry(
                                            androidx.compose.ui.platform.ClipEntry(
                                                android.content.ClipData.newPlainText("", payload),
                                            ),
                                        )
                                    }
                                    SnackbarManager.show(
                                        AppNotification(
                                            message = copyMsg,
                                            type = NotificationType.SUCCESS,
                                        ),
                                    )
                                },
                                onEdit = { editorTheme = theme; editorVisible = true },
                                onDelete = { deleteTarget = theme },
                            )
                            if (i != themes.lastIndex) DividerRow()
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    // Editor (new / edit) — bottom sheet like _showAppSheet.
    if (editorVisible) {
        CustomThemeEditorSheet(
            initial = editorTheme,
            onDismiss = { editorVisible = false },
            onSave = { t ->
                ThemeState.saveCustomTheme(container, t)
                editorVisible = false
            },
        )
    }

    // Paste-JSON import sheet.
    if (importVisible) {
        ImportThemeSheet(
            onDismiss = { importVisible = false },
            onImport = { source ->
                runCatching { ThemeState.importCustomTheme(container, source) }
                    .onSuccess { importVisible = false }
            },
        )
    }

    // Delete confirmation dialog.
    deleteTarget?.let { target ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(UiR.string.custom_theme_delete)) },
            text = { Text(stringResource(UiR.string.custom_theme_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        ThemeState.deleteCustomTheme(container, target.id)
                        deleteTarget = null
                    },
                ) {
                    Text(
                        stringResource(UiR.string.custom_theme_delete),
                        color = cs.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(UiR.string.custom_theme_cancel))
                }
            },
        )
    }
}

@Composable
private fun ThemeSwatchCanvas(
    scheme: androidx.compose.material3.ColorScheme,
    selected: Boolean,
    size: androidx.compose.ui.unit.Dp,
) {
    Canvas(
        modifier = Modifier
            .clip(CircleShape)
            .size(size),
    ) {
        // 注意：参数名 size(Dp) 会遮住 DrawScope.size(Size)，这里显式取后者。
        val box = this.size
        drawRect(color = scheme.primaryContainer, size = box)
        drawRect(
            color = scheme.secondaryContainer,
            size = box,
            topLeft = Offset(x = box.width / 2, y = 0f),
        )
        drawRect(
            color = scheme.tertiaryContainer,
            size = box,
            topLeft = Offset(x = box.width / 2, y = box.height / 2),
        )
        drawCircle(
            color = scheme.primary,
            radius = if (selected) 12.dp.toPx() else 8.dp.toPx(),
            center = Offset(x = box.width / 2, y = box.height / 2),
        )
    }
}

/** 四列色卡网格 —— 1:1 照 RikkaHub `PresetThemeButtonGroup`（见上）。 */
private const val THEME_GRID_COLUMNS = 4

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ThemeSwatchGrid(
    palettes: List<Palette>,
    dark: Boolean,
    selectedId: String,
    language: String,
    onSelect: (String) -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = THEME_GRID_COLUMNS,
    ) {
        palettes.forEach { palette ->
            key(palette.id) {
                ThemeSwatchItem(
                    palette = palette,
                    dark = dark,
                    selected = palette.id == selectedId,
                    name = if (language == "zh") palette.zhName else palette.enName,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(palette.id) },
                )
            }
        }
        // 补齐最后一行的空位，让每列宽度保持一致（上游同款）。
        repeat((THEME_GRID_COLUMNS - palettes.size % THEME_GRID_COLUMNS) % THEME_GRID_COLUMNS) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

/** 单项：48dp 色卡 + 下方主题色名字（上游 `PresetThemeButton` 的几何逐条对齐）。 */
@Composable
private fun ThemeSwatchItem(
    palette: Palette,
    dark: Boolean,
    selected: Boolean,
    name: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = if (dark) palette.dark else palette.light
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .clip(RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(8.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            ThemeSwatchCanvas(scheme = scheme, selected = selected, size = 48.dp)
            if (selected) {
                Icon(
                    Lucide.Check,
                    contentDescription = null,
                    tint = scheme.onPrimary,
                )
            }
        }
        Text(
            text = name,
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.primary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            ),
        )
    }
}

/** L210-259: 自定义主题行 —— 色卡 + 名字 + 选中勾 + 复制/编辑/删除（1:1 上游色卡）。 */
@Composable
private fun CustomThemeRow(
    theme: CustomTheme,
    dark: Boolean,
    selected: Boolean,
    nameFor: @Composable (CustomTheme) -> String,
    onTap: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // 上游自定义主题的色卡用的是「这个主题生成出来的色板」（`generateColorScheme`）。
    val swatchScheme = remember(theme, dark) {
        val palette = buildCustomThemePalette(
            id = theme.id,
            name = theme.name,
            primaryArgb = theme.primaryArgb,
            secondaryArgb = theme.secondaryArgb,
            tertiaryArgb = theme.tertiaryArgb,
        )
        if (dark) palette.dark else palette.light
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            ThemeSwatchCanvas(scheme = swatchScheme, selected = selected, size = 40.dp)
            if (selected) {
                Icon(
                    Lucide.Check,
                    contentDescription = null,
                    tint = swatchScheme.onPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = nameFor(theme),
            style = TextStyle(fontSize = 15.sp, color = cs.onSurface.copy(alpha = 0.9f)),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        RowAction(Lucide.Copy, onCopy)
        RowAction(Lucide.Pencil, onEdit)
        RowAction(Lucide.Trash2, onDelete, color = cs.error)
    }
}

@Composable
private fun RowAction(icon: ImageVector, onTap: () -> Unit, color: Color? = null) {
    val cs = MaterialTheme.colorScheme
    IconButton(onClick = onTap, modifier = Modifier.size(28.dp)) {
        Icon(
            icon,
            contentDescription = null,
            tint = color ?: cs.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Editor sheet (custom_theme_widgets.dart CustomThemeEditor, mobile shell):
// name field + per-color SV area / hue bar / hex field, secondary & tertiary
// optional via the "auto" checkbox, save + cancel.
// ---------------------------------------------------------------------------

private class ColorPickerState(argb: Int?) {
    var hasColor by mutableStateOf(argb != null)
    var hsv by mutableStateOf(argb?.let { colorToHsv(it) } ?: floatArrayOf(210f, 0.6f, 0.6f))
    var hex by mutableStateOf(argb?.let { hexOf(it) } ?: "")

    companion object {
        fun colorToHsv(argb: Int): FloatArray {
            val c = Color(argb)
            val hsv = FloatArray(3)
            AndroidColor.RGBToHSV(
                (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt(), hsv,
            )
            return hsv
        }

        fun hexOf(argb: Int): String {
            val c = Color(argb)
            fun hx(v: Float) = ((v * 255).toInt()).toString(16).padStart(2, '0')
            return "${hx(c.red)}${hx(c.green)}${hx(c.blue)}"
        }

        fun parseHex(raw: String): Color? {
            var s = raw.trim().removePrefix("#")
            if (s.length == 6) s = "FF$s"
            if (s.length != 8) return null
            val v = s.toLongOrNull(16) ?: return null
            return Color(v.toInt())
        }
    }

    fun currentColor(): Color {
        val c = AndroidColor.HSVToColor(hsv)
        return Color((0xFF000000.toInt()) or (c and 0xFFFFFF))
    }

    fun setHsv(next: FloatArray, fromHex: Boolean = false) {
        hsv = next
        if (!fromHex) hex = hexOf(currentColor().toArgbCompat())
        hasColor = true
    }

    private fun Color.toArgbCompat(): Int =
        (alpha.toLong().shl(24).toInt()) or (red.toLong().shl(16).toInt()) or (green.toLong().shl(8).toInt()) or blue.toLong().toInt()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomThemeEditorSheet(
    initial: CustomTheme?,
    onDismiss: () -> Unit,
    onSave: (CustomTheme) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val title = stringResource(
        if (initial == null) UiR.string.custom_theme_new_theme else UiR.string.custom_theme_edit_theme,
    )
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var primary by remember { mutableStateOf(ColorPickerState(initial?.primaryArgb ?: 0xFF4D5C92.toInt())) }
    var secondary by remember { mutableStateOf(ColorPickerState(initial?.secondaryArgb)) }
    var tertiary by remember { mutableStateOf(ColorPickerState(initial?.tertiaryArgb)) }

    ModalBottomSheet(containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
sheetState = rememberMemoSheetState(), onDismissRequest = onDismiss, dragHandle = null) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MemoSheetHandle(trailingGap = 0.dp)
            Text(
                text = title,
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(UiR.string.custom_theme_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ColorPickerField(stringResource(UiR.string.custom_theme_primary_color), primary, autoLabel = null)
            ColorPickerField(stringResource(UiR.string.custom_theme_secondary_color), secondary, autoLabel = stringResource(UiR.string.custom_theme_color_auto))
            ColorPickerField(stringResource(UiR.string.custom_theme_tertiary_color), tertiary, autoLabel = stringResource(UiR.string.custom_theme_color_auto))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(UiR.string.custom_theme_cancel))
                }
                TextButton(
                    onClick = {
                        onSave(
                            CustomTheme(
                                id = initial?.id ?: "",
                                name = name.trim(),
                                primaryArgb = primary.currentColor().toArgbCompat2(),
                                secondaryArgb = secondary.let { if (it.hasColor) it.currentColor().toArgbCompat2() else null },
                                tertiaryArgb = tertiary.let { if (it.hasColor) it.currentColor().toArgbCompat2() else null },
                            ),
                        )
                    },
                ) {
                    Text(stringResource(UiR.string.custom_theme_save))
                }
            }
        }
    }
}

private fun Color.toArgbCompat2(): Int =
    ((alpha * 255).toLong().shl(24).toInt()) or
        ((red * 255).toLong().shl(16).toInt()) or
        ((green * 255).toLong().shl(8).toInt()) or
        (blue * 255).toLong().toInt()

@Composable
private fun ColorPickerField(label: String, state: ColorPickerState, autoLabel: String?) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.8f)))
            Spacer(Modifier.weight(1f))
            if (autoLabel != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { state.hasColor = !state.hasColor }.padding(4.dp),
                ) {
                    IosCheckboxSimple(value = state.hasColor)
                    Spacer(Modifier.width(6.dp))
                    Text(autoLabel, style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.7f)))
                }
            }
        }
        if (state.hasColor || autoLabel == null) {
            SvArea(state)
            HueBar(state)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(state.currentColor(), CircleShape)
                        .border(0.6.dp, cs.outlineVariant.copy(alpha = 0.4f), CircleShape),
                )
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = state.hex,
                    onValueChange = { v ->
                        state.hex = v
                        ColorPickerState.parseHex(v)?.let { c ->
                            state.setHsv(ColorPickerState.colorToHsv(c.toArgbCompat2()), fromHex = true)
                        }
                    },
                    label = { Text(stringResource(UiR.string.custom_theme_hex_label)) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** SV 2D area: white→hue horizontal × transparent→black vertical + thumb. */
@Composable
private fun SvArea(state: ColorPickerState) {
    val hueColor = hueColorOf(state.hsv[0])
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(MemoRadius.SMALL_DP.dp))
            .pointerInput(Unit) {
                detectTapGestures { update(state, it, size.width.toFloat(), size.height.toFloat()) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    update(state, change.position, size.width.toFloat(), size.height.toFloat())
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val cx = state.hsv[1] * size.width
        val cy = (1 - state.hsv[2]) * size.height
        drawCircle(state.currentColor(), radius = 9.dp.toPx(), center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5.dp.toPx()))
    }
}

/** Hue bar: 6-segment rainbow + thumb. */
@Composable
private fun HueBar(state: ColorPickerState) {
    val rainbow = listOf(
        Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
        Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
    )
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            .clip(RoundedCornerShape(MemoRadius.SMALL_DP.dp))
            .pointerInput(Unit) {
                detectTapGestures { updateHue(state, it, size.width.toFloat()) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    updateHue(state, change.position, size.width.toFloat())
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(rainbow))
        val cx = (state.hsv[0] / 360f) * size.width
        drawCircle(hueColorOf(state.hsv[0]), radius = 9.dp.toPx(), center = Offset(cx, size.height / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5.dp.toPx()))
    }
}

private fun hueColorOf(hue: Float): Color =
    Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))

private fun update(state: ColorPickerState, pos: Offset, w: Float, h: Float) {
    val s = (pos.x / w).coerceIn(0f, 1f)
    val v = (1 - pos.y / h).coerceIn(0f, 1f)
    state.setHsv(floatArrayOf(state.hsv[0], s, v))
}

private fun updateHue(state: ColorPickerState, pos: Offset, w: Float) {
    val hue = (pos.x / w).coerceIn(0f, 0.9999f) * 360f
    state.setHsv(floatArrayOf(hue, state.hsv[1], state.hsv[2]))
}

/** Minimal circular checkbox for the auto toggles. */
@Composable
private fun IosCheckboxSimple(value: Boolean) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(if (value) cs.primary else Color.Transparent)
            .border(1.dp, if (value) cs.primary else cs.onSurface.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (value) {
            Icon(Lucide.Check, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(12.dp))
        }
    }
}

/** Paste-JSON import sheet (custom_theme_widgets.dart _ImportThemeForm). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportThemeSheet(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    ModalBottomSheet(containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
sheetState = rememberMemoSheetState(), onDismissRequest = onDismiss, dragHandle = null) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MemoSheetHandle(trailingGap = 0.dp)
            Text(
                text = stringResource(UiR.string.custom_theme_import_theme),
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; error = false },
                label = { Text(stringResource(UiR.string.custom_theme_import_hint)) },
                isError = error,
                supportingText = if (error) {
                    { Text(stringResource(UiR.string.custom_theme_import_invalid)) }
                } else null,
                minLines = 4,
                maxLines = 8,
                textStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(UiR.string.custom_theme_cancel))
                }
                TextButton(onClick = { onImport(text) }) {
                    Text(stringResource(UiR.string.custom_theme_import_theme))
                }
            }
        }
    }
}

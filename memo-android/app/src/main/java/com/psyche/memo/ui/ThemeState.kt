package com.psyche.memo.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ui.theme.Palette
import com.psyche.memo.ui.theme.buildCustomThemePalette
import com.psyche.memo.ui.theme.themePaletteById
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Reactive theme state shared by MainActivity and the settings screens —
 * the fix for the known limitation "theme_mode_v1 needs a restart": every
 * writer pushes through here, and the root composable observes these
 * snapshot states, so color mode / palette / advanced switches take effect
 * immediately.
 *
 * Keys mirror settings_provider.dart: theme_mode_v1 / theme_palette_v1
 * (kept on the existing readLocal/writeLocal convention used by
 * MainActivity+SettingsScreen), plus display_use_pure_background_v1 /
 * display_use_layered_surfaces_v1 / display_use_layered_sheet_tiles_v1 /
 * use_dynamic_color_v1 / custom_themes_v1 / custom_theme_selected_v1
 * (PREFERENCE keys → preference_rows JSON, like BusinessSettingsRouter).
 */
object ThemeState {
    const val PURE_BACKGROUND_KEY = "display_use_pure_background_v1"
    const val LAYERED_SURFACES_KEY = "display_use_layered_surfaces_v1"
    const val LAYERED_SHEET_TILES_KEY = "display_use_layered_sheet_tiles_v1"
    const val DYNAMIC_COLOR_KEY = "use_dynamic_color_v1"
    const val CUSTOM_THEMES_KEY = "custom_themes_v1"
    const val CUSTOM_THEME_SELECTED_KEY = "custom_theme_selected_v1"

    /** settings_provider.dart ThemePalettes.customPaletteId. */
    const val CUSTOM_PALETTE_ID = "custom"

    var mode by mutableStateOf("system")
        private set
    var paletteId by mutableStateOf("default")
        private set
    var usePureBackground by mutableStateOf(false)
        private set
    var useLayeredSurfaces by mutableStateOf(false)
        private set
    var useLayeredSheetTiles by mutableStateOf(false)
        private set
    var useDynamicColor by mutableStateOf(false)
        private set
    var customThemes by mutableStateOf<List<CustomTheme>>(emptyList())
        private set
    var selectedCustomThemeId by mutableStateOf<String?>(null)
        private set
    /** `display_app_font_*`：App 字体（null = 跟随主题默认）。 */
    var appFontFamily by mutableStateOf<FontFamily?>(null)
        private set
    /** `display_code_font_*`：代码块字体（默认 Monospace）。 */
    var codeFontFamily: FontFamily by mutableStateOf<FontFamily>(FontFamily.Monospace)
        private set

    private val json = Json { ignoreUnknownKeys = true }

    /** Load once at app start (and after theme edits) from the stores. */
    fun load(container: AppContainerImpl) {
        val prefs = container.preferenceRepository
        mode = prefs.readJson(com.psyche.memo.ui.theme.MemoTheme.MODE_KEY)
            ?.replace("\"", "")?.takeIf { it.isNotEmpty() } ?: "system"
        paletteId = prefs.readJson(com.psyche.memo.ui.theme.MemoTheme.PALETTE_KEY)
            ?.replace("\"", "")?.takeIf { it.isNotEmpty() } ?: "default"
        usePureBackground = readBool(prefs, PURE_BACKGROUND_KEY)
        useLayeredSurfaces = readBool(prefs, LAYERED_SURFACES_KEY)
        useLayeredSheetTiles = readBool(prefs, LAYERED_SHEET_TILES_KEY)
        useDynamicColor = readBool(prefs, DYNAMIC_COLOR_KEY)
        customThemes = runCatching {
            prefs.readJson(CUSTOM_THEMES_KEY)
                ?.let { json.parseToJsonElement(it).jsonArray }
                ?.mapNotNull { el ->
                    runCatching { CustomTheme.fromJson(el.jsonObject) }.getOrNull()
                }
        }.getOrNull() ?: emptyList()
        selectedCustomThemeId = prefs.readJson(CUSTOM_THEME_SELECTED_KEY)
            ?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() }
        loadFonts(container)
    }

    /**
     * 重新解析 App / 代码字体。字体的写入点在显示设置页（不经过本对象），
     * 所以那里改完要显式调一次，否则要等下次冷启动（与 ThemeState 解决
     * 「theme_mode_v1 需要重启」的旧问题同一手法）。
     */
    fun loadFonts(container: AppContainerImpl) {
        val prefs = container.preferenceRepository
        migrateLegacyGoogleFontFlags(prefs)
        val read: (String) -> String? = { prefs.readJson(it) }
        appFontFamily = AppFonts.appFontFamily(read)
        codeFontFamily = AppFonts.codeFontFamily(read)
    }

    /**
     * 上游早已删掉 Google Fonts 选择器（settings_provider.dart:1931-1943 + 1993-1998）：
     * 「族名来自 Google Fonts」的老数据一律退回系统默认（族名丢掉），然后把两个旗标删除。
     * 本工程从来不会写这两个键，所以只有上游备份还原进来的数据会命中。
     */
    private fun migrateLegacyGoogleFontFlags(prefs: com.psyche.memo.data.settings.PreferenceRepository) {
        if (readBool(prefs, AppFonts.APP_IS_GOOGLE_KEY)) prefs.remove(AppFonts.APP_FAMILY_KEY)
        if (readBool(prefs, AppFonts.CODE_IS_GOOGLE_KEY)) prefs.remove(AppFonts.CODE_FAMILY_KEY)
        if (prefs.readJson(AppFonts.APP_IS_GOOGLE_KEY) != null) prefs.remove(AppFonts.APP_IS_GOOGLE_KEY)
        if (prefs.readJson(AppFonts.CODE_IS_GOOGLE_KEY) != null) prefs.remove(AppFonts.CODE_IS_GOOGLE_KEY)
    }

    private fun readBool(
        prefs: com.psyche.memo.data.settings.PreferenceRepository,
        key: String,
    ): Boolean = runCatching {
        prefs.readJson(key)?.let { json.parseToJsonElement(it).jsonPrimitive.booleanOrNull }
    }.getOrNull() ?: false

    fun setMode(container: AppContainerImpl, value: String) {
        container.preferenceRepository.writeJson(com.psyche.memo.ui.theme.MemoTheme.MODE_KEY, value)
        mode = value
    }

    fun setPalette(container: AppContainerImpl, id: String) {
        container.preferenceRepository.writeJson(com.psyche.memo.ui.theme.MemoTheme.PALETTE_KEY, id)
        paletteId = id
    }

    fun setPureBackground(container: AppContainerImpl, value: Boolean) {
        container.preferenceRepository.writeJson(PURE_BACKGROUND_KEY, value.toString())
        usePureBackground = value
    }

    fun setLayeredSurfaces(container: AppContainerImpl, value: Boolean) {
        container.preferenceRepository.writeJson(LAYERED_SURFACES_KEY, value.toString())
        useLayeredSurfaces = value
    }

    fun setLayeredSheetTiles(container: AppContainerImpl, value: Boolean) {
        container.preferenceRepository.writeJson(LAYERED_SHEET_TILES_KEY, value.toString())
        useLayeredSheetTiles = value
    }

    fun setDynamicColor(container: AppContainerImpl, value: Boolean) {
        container.preferenceRepository.writeJson(DYNAMIC_COLOR_KEY, value.toString())
        useDynamicColor = value
    }

    /** selectCustomTheme — empty/'' id selects the custom palette id itself. */
    fun selectCustomTheme(container: AppContainerImpl, id: String) {
        container.preferenceRepository.writeJson(
            com.psyche.memo.ui.theme.MemoTheme.PALETTE_KEY,
            CUSTOM_PALETTE_ID,
        )
        container.preferenceRepository.writeJson(
            CUSTOM_THEME_SELECTED_KEY,
            "\"$id\"",
        )
        selectedCustomThemeId = id
        paletteId = CUSTOM_PALETTE_ID
    }

    fun saveCustomThemes(container: AppContainerImpl, themes: List<CustomTheme>) {
        val arr = buildJsonArray {
            themes.forEach { add(it.toJson()) }
        }
        container.preferenceRepository.writeJson(CUSTOM_THEMES_KEY, arr.toString())
        customThemes = themes
    }

    /** Insert or update; assigns `ct_<micros>` when id is empty. Then selects. */
    fun saveCustomTheme(container: AppContainerImpl, theme: CustomTheme): CustomTheme {
        var t = theme
        if (t.id.isEmpty()) {
            t = t.copy(id = "ct_${System.currentTimeMillis() * 1000L}")
        }
        val next = customThemes.toMutableList()
        val idx = next.indexOfFirst { it.id == t.id }
        if (idx >= 0) next[idx] = t else next.add(t)
        saveCustomThemes(container, next)
        selectCustomTheme(container, t.id)
        return t
    }

    /** importCustomTheme — dedupes ids after parsing. */
    fun importCustomTheme(container: AppContainerImpl, source: String): CustomTheme {
        val parsed = CustomTheme.parse(source)
        var id = parsed.id
        if (id.isEmpty() || customThemes.any { it.id == id }) {
            id = "ct_${System.currentTimeMillis() * 1000L}"
        }
        return saveCustomTheme(container, parsed.copy(id = id))
    }

    /** deleteCustomTheme — deselects and falls back to the default palette. */
    fun deleteCustomTheme(container: AppContainerImpl, id: String): Boolean {
        val before = customThemes
        val next = before.filterNot { it.id == id }
        if (next.size == before.size) return false
        saveCustomThemes(container, next)
        if (selectedCustomThemeId == id) {
            selectedCustomThemeId = null
            container.preferenceRepository.remove(CUSTOM_THEME_SELECTED_KEY)
            if (paletteId == CUSTOM_PALETTE_ID) {
                setPalette(container, "default")
            }
        }
        return true
    }

    /** The runtime palette for the active selection (custom built on demand). */
    fun resolvePalette(): Palette {
        if (paletteId == CUSTOM_PALETTE_ID) {
            val theme = customThemes.firstOrNull { it.id == selectedCustomThemeId }
            if (theme != null) {
                return buildCustomThemePalette(
                    id = CUSTOM_PALETTE_ID,
                    name = theme.name,
                    primaryArgb = theme.primaryArgb,
                    secondaryArgb = theme.secondaryArgb,
                    tertiaryArgb = theme.tertiaryArgb,
                )
            }
        }
        return themePaletteById(paletteId)
    }
}

/**
 * 1:1 port of lib/theme/custom_theme.dart CustomTheme — a user-defined theme:
 * primary color plus optional secondary/tertiary accents, stored/exported as
 * JSON (`primaryColorArgb` etc., RikkaHub-compatible).
 */
data class CustomTheme(
    val id: String,
    val name: String,
    val primaryArgb: Int,
    val secondaryArgb: Int?,
    val tertiaryArgb: Int?,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("primaryColorArgb", primaryArgb)
        if (secondaryArgb != null) put("secondaryColorArgb", secondaryArgb)
        if (tertiaryArgb != null) put("tertiaryColorArgb", tertiaryArgb)
    }

    fun export(): String = toJson().toString()

    companion object {
        /** Accepts this app's export format and RikkaHub's (id optional). */
        fun fromJson(obj: JsonObject): CustomTheme {
            val primary = obj["primaryColorArgb"]?.jsonPrimitive?.intOrNull
                ?: throw IllegalArgumentException("missing primaryColorArgb")
            return CustomTheme(
                id = obj["id"]?.jsonPrimitive?.content ?: "",
                name = obj["name"]?.jsonPrimitive?.content ?: "",
                primaryArgb = primary,
                secondaryArgb = obj["secondaryColorArgb"]?.jsonPrimitive?.intOrNull,
                tertiaryArgb = obj["tertiaryColorArgb"]?.jsonPrimitive?.intOrNull,
            )
        }

        fun parse(source: String): CustomTheme {
            val obj = Json.parseToJsonElement(source.trim()).jsonObject
            return fromJson(obj)
        }
    }
}

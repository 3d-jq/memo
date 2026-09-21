package com.psyche.memo.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import java.io.File

/**
 * App / 代码字体（显示设置 → 字体分组）在 Android 侧的解析。
 *
 * 原版 `settings_provider.dart` 为每个目标存四个键：`_family_v1`（族名）、
 * `_is_google_v1`（族名是否来自 Google Fonts）、`_local_path_v1` / `_local_alias_v1`
 * （用户从文件选的本地字体）。`main.dart:744-764` 只对**系统字体**调
 * `SystemFonts().loadFont(fam)`，本地别名走 GoogleFonts 的已加载族；本工程的选择器
 * 只提供「本地文件 / 重置」两项（与显示设置页的 sheet 一致），所以这里：
 *
 * - 本地文件存在 → `FontFamily(Font(File(path)))`（等价 Flutter 的 `fontFamily: alias`）。
 * - 否则族名非空 → 通用族名映射（`monospace`/`serif`/`sans-serif`/`cursive`），其余族名
 *   落回默认无衬线。
 * - 都没有 → null（跟随主题默认；代码字体回退 `FontFamily.Monospace`）。
 *
 * 已知差异：Google Fonts 族名在 Android 上不下载字体、也不按系统族名解析（Compose 没有
 * 稳定的「按名字取系统族」API），一律落回默认无衬线 —— 与「没有 google_fonts 依赖」
 * 一致；本工程的选择器也只提供「本地文件 / 重置」。
 */
object AppFonts {
    const val APP_FAMILY_KEY = "display_app_font_family_v1"
    const val APP_IS_GOOGLE_KEY = "display_app_font_is_google_v1"
    const val APP_LOCAL_PATH_KEY = "display_app_font_local_path_v1"
    const val APP_LOCAL_ALIAS_KEY = "display_app_font_local_alias_v1"
    const val CODE_FAMILY_KEY = "display_code_font_family_v1"
    const val CODE_IS_GOOGLE_KEY = "display_code_font_is_google_v1"
    const val CODE_LOCAL_PATH_KEY = "display_code_font_local_path_v1"
    const val CODE_LOCAL_ALIAS_KEY = "display_code_font_local_alias_v1"

    fun appFontFamily(
        read: (String) -> String?,
        fileExists: (String) -> Boolean = { File(it).isFile },
    ): FontFamily? = resolve(read(APP_FAMILY_KEY), read(APP_LOCAL_PATH_KEY), fileExists)

    /** 默认 `FontFamily.Monospace`（原版 `resolveCodeFont` 的同款回退）。 */
    fun codeFontFamily(
        read: (String) -> String?,
        fileExists: (String) -> Boolean = { File(it).isFile },
    ): FontFamily =
        resolve(read(CODE_FAMILY_KEY), read(CODE_LOCAL_PATH_KEY), fileExists) ?: FontFamily.Monospace
    /** 存的是裸字符串（本工程写）/ JSON 字符串（上游备份还原）两种形态。 */
    internal fun raw(value: String?): String? =
        value?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }

    internal fun resolve(
        family: String?,
        localPath: String?,
        fileExists: (String) -> Boolean,
        fileFamily: (String) -> FontFamily = { FontFamily(Font(File(it))) },
    ): FontFamily? {
        val path = raw(localPath)
        if (path != null && fileExists(path)) return fileFamily(path)
        val name = raw(family) ?: return null
        return systemFamily(name)
    }

    internal fun systemFamily(name: String): FontFamily = when (name.lowercase()) {
        "monospace", "mono" -> FontFamily.Monospace
        "serif" -> FontFamily.Serif
        "sans-serif", "sans", "sansserif" -> FontFamily.SansSerif
        "cursive" -> FontFamily.Cursive
        // 任意系统族名（含 Google Fonts 名）在 Compose 里没有「按名字查族」的稳定
        // API，落到默认无衬线。未安装的族名本来也只能回退。
        else -> FontFamily.SansSerif
    }
}

package com.psyche.memo.ui

import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Translations of the app/code font resolution rules — `main.dart:744-764` +
 * `settings_provider.dart` `appFontLocalAlias` / `resolveCodeFont`.
 */
class AppFontsTest {

    private val none = { _: String -> false }
    private val someFile: (String) -> FontFamily = { FontFamily.Serif }

    @Test
    fun rawAcceptsBareAndJsonQuotedValues() {
        assertNull(AppFonts.raw(null))
        assertNull(AppFonts.raw("   "))
        assertNull(AppFonts.raw("\"\""))
        assertEquals("monospace", AppFonts.raw(" monospace "))
        assertEquals("Noto Sans", AppFonts.raw("\"Noto Sans\""))
    }

    @Test
    fun genericFamilyNamesMapToTheGenericFamilies() {
        assertEquals(FontFamily.Monospace, AppFonts.systemFamily("monospace"))
        assertEquals(FontFamily.Monospace, AppFonts.systemFamily("Mono"))
        assertEquals(FontFamily.Serif, AppFonts.systemFamily("serif"))
        assertEquals(FontFamily.SansSerif, AppFonts.systemFamily("sans-serif"))
        assertEquals(FontFamily.Cursive, AppFonts.systemFamily("cursive"))
    }

    @Test
    fun unknownFamilyNameFallsBackToSansSerif() {
        // Google Fonts 名不下载也不按系统族名解析（见 AppFonts 的已知差异注释）。
        assertEquals(FontFamily.SansSerif, AppFonts.systemFamily("Noto Sans SC"))
    }

    @Test
    fun localFileWinsOverTheFamilyName() {
        val resolved = AppFonts.resolve(
            family = "ttf",
            localPath = "/data/fonts/user.ttf",
            fileExists = { true },
            fileFamily = someFile,
        )
        assertEquals(FontFamily.Serif, resolved)
    }

    @Test
    fun missingLocalFileFallsBackToTheFamilyName() {
        assertEquals(
            FontFamily.Monospace,
            AppFonts.resolve("monospace", "/data/fonts/gone.ttf", fileExists = none),
        )
    }

    @Test
    fun appFontIsNullWhenNothingIsConfigured() {
        assertNull(AppFonts.resolve(null, null, fileExists = none))
        assertNull(AppFonts.resolve("  ", "", fileExists = none))
    }

    @Test
    fun codeFontAlwaysResolvesToAFamily() {
        // resolve 为 null 时 app 侧跟随主题默认，代码字体回退 Monospace。
        val read: (String) -> String? = { null }
        assertEquals(FontFamily.Monospace, AppFonts.codeFontFamily(read, fileExists = none))
        assertNull(AppFonts.appFontFamily(read, fileExists = none))
    }
}

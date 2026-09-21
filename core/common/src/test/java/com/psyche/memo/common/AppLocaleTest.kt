package com.psyche.memo.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLocaleTest {

    @Test
    fun `unknown or blank tag falls back to system`() {
        assertEquals(AppLocale.SYSTEM, AppLocale.fromTag(null))
        assertEquals(AppLocale.SYSTEM, AppLocale.fromTag(""))
        assertEquals(AppLocale.SYSTEM, AppLocale.fromTag("fr_FR"))
    }

    @Test
    fun `known tags round trip`() {
        assertEquals(AppLocale.SYSTEM, AppLocale.fromTag("system"))
        assertEquals(AppLocale.ZH_CN, AppLocale.fromTag("zh_CN"))
        assertEquals(AppLocale.ZH_HANT, AppLocale.fromTag("zh_Hant"))
        assertEquals(AppLocale.EN_US, AppLocale.fromTag("en_US"))
    }

    @Test
    fun `system resolves to no locale override`() {
        assertNull(AppLocale.SYSTEM.toLocale())
    }

    @Test
    fun `resolved locales carry the right language and script`() {
        assertEquals("zh", AppLocale.ZH_CN.toLocale()?.language)
        assertEquals("zh", AppLocale.ZH_HANT.toLocale()?.language)
        assertEquals("Hant", AppLocale.ZH_HANT.toLocale()?.script)
        assertEquals("en", AppLocale.EN_US.toLocale()?.language)
    }
}

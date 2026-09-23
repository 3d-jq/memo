package com.psyche.memo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 布尔偏好的**唯一解码**（`DisplayPrefs.decodeBool`）。
 *
 * 这条规矩来自一次真实回归：同一个键存在两种存储形态 —— 裸布尔 `true`/`false`（早期写入）
 * 与 `"1"`/`"0"`（行为/启动页写入）。抽屉的日期分组开关只认 `"1"`，于是老键读成关，
 * 用户看到「日期分组不见了」（2026-09-23）。只认一种就会读成恒 false。
 */
class DisplayPrefsTest {

    @Test
    fun `both storage forms decode`() {
        assertTrue(DisplayPrefs.decodeBool("1", false))
        assertTrue(DisplayPrefs.decodeBool("true", false))
        assertTrue(DisplayPrefs.decodeBool("TRUE", false))
        assertTrue(DisplayPrefs.decodeBool(" true ", false))
        assertFalse(DisplayPrefs.decodeBool("0", true))
        assertFalse(DisplayPrefs.decodeBool("false", true))
        assertFalse(DisplayPrefs.decodeBool("  False ", true))
    }

    /** 缺失或垃圾值 → 默认（不许静默当成 true）。 */
    @Test
    fun `missing or garbage falls back to the default`() {
        assertEquals(true, DisplayPrefs.decodeBool(null, true))
        assertEquals(false, DisplayPrefs.decodeBool(null, false))
        assertEquals(false, DisplayPrefs.decodeBool("yes", false))
        assertEquals(true, DisplayPrefs.decodeBool("", true))
    }

    /** 键名是稳定契约（改动即等于丢用户设置）。 */
    @Test
    fun `keys are stable`() {
        assertEquals("display_show_chat_list_date_v1", DisplayPrefs.SHOW_CHAT_LIST_DATE)
        assertEquals("display_show_assistant_avatar_v1", DisplayPrefs.SHOW_ASSISTANT_AVATAR)
    }
}

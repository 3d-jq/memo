package com.psyche.memo.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [ChatBackgroundController]'s static mode/notification
 * logic — mirrors settings_provider.dart:1371-1387 (mode read with "off"
 * fallback) and notification_service.dart shouldShowChatCompleted L181-190
 * (notify only when the app is backgrounded or away from the conversation).
 */
class ChatBackgroundControllerTest {

    // ---- modeOf / writeModeValue ----------------------------------------

    @Test
    fun `modeOf maps the three stored values`() {
        assertEquals(
            ChatBackgroundController.AndroidBackgroundChatMode.OFF,
            ChatBackgroundController.modeOf { "off" },
        )
        assertEquals(
            ChatBackgroundController.AndroidBackgroundChatMode.ON,
            ChatBackgroundController.modeOf { "on" },
        )
        assertEquals(
            ChatBackgroundController.AndroidBackgroundChatMode.ON_NOTIFY,
            ChatBackgroundController.modeOf { "on_notify" },
        )
    }

    @Test
    fun `modeOf falls back to off on absent or garbage values`() {
        assertEquals(
            ChatBackgroundController.AndroidBackgroundChatMode.OFF,
            ChatBackgroundController.modeOf { null },
        )
        assertEquals(
            ChatBackgroundController.AndroidBackgroundChatMode.OFF,
            ChatBackgroundController.modeOf { "yes-please" },
        )
        assertEquals(
            ChatBackgroundController.AndroidBackgroundChatMode.OFF,
            ChatBackgroundController.modeOf { "" },
        )
    }

    @Test
    fun `write and read round-trip`() {
        for (mode in ChatBackgroundController.AndroidBackgroundChatMode.entries) {
            val stored = ChatBackgroundController.writeModeValue(mode)
            assertEquals(mode, ChatBackgroundController.modeOf { stored })
        }
    }

    // ---- shouldShowChatCompleted ----------------------------------------

    @Test
    fun `no notification without on_notify mode`() {
        // notifyModeEnabled=false covers both OFF and ON modes.
        assertFalse(
            ChatBackgroundController.shouldShowChatCompleted(
                isAndroid = true, notifyModeEnabled = false,
                appInForeground = false, isCurrentConversation = false,
            ),
        )
    }

    @Test
    fun `no notification on non-android platforms`() {
        assertFalse(
            ChatBackgroundController.shouldShowChatCompleted(
                isAndroid = false, notifyModeEnabled = true,
                appInForeground = false, isCurrentConversation = false,
            ),
        )
    }

    @Test
    fun `no notification while watching the generation`() {
        // Foreground + current conversation = user is looking at it.
        assertFalse(
            ChatBackgroundController.shouldShowChatCompleted(
                isAndroid = true, notifyModeEnabled = true,
                appInForeground = true, isCurrentConversation = true,
            ),
        )
    }

    @Test
    fun `notification when backgrounded or away from the conversation`() {
        assertTrue(
            ChatBackgroundController.shouldShowChatCompleted(
                isAndroid = true, notifyModeEnabled = true,
                appInForeground = false, isCurrentConversation = true,
            ),
        )
        assertTrue(
            ChatBackgroundController.shouldShowChatCompleted(
                isAndroid = true, notifyModeEnabled = true,
                appInForeground = true, isCurrentConversation = false,
            ),
        )
    }

    // ---- shouldKeepAlive / shouldNotifyCompletion ------------------------

    /**
     * 「开」这一档必须也走前台服务：原先保活和通知被写成同一支（只在 ON_NOTIFY 才
     * acquire），选「开」的人既不保活也没通知，设置等于空（用户 2026-09-25
     * 「把实时通知显示那个做完整，就是退出 app 也可以继续那个部分」）。
     */
    @Test
    fun `both non-off modes keep alive but only on_notify notifies`() {
        assertFalse(
            ChatBackgroundController.shouldKeepAlive(
                ChatBackgroundController.AndroidBackgroundChatMode.OFF,
            ),
        )
        assertTrue(
            ChatBackgroundController.shouldKeepAlive(
                ChatBackgroundController.AndroidBackgroundChatMode.ON,
            ),
        )
        assertTrue(
            ChatBackgroundController.shouldKeepAlive(
                ChatBackgroundController.AndroidBackgroundChatMode.ON_NOTIFY,
            ),
        )
        assertFalse(
            ChatBackgroundController.shouldNotifyCompletion(
                ChatBackgroundController.AndroidBackgroundChatMode.ON,
            ),
        )
        assertTrue(
            ChatBackgroundController.shouldNotifyCompletion(
                ChatBackgroundController.AndroidBackgroundChatMode.ON_NOTIFY,
            ),
        )
    }
}

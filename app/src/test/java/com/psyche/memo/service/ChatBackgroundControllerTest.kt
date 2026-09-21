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
}

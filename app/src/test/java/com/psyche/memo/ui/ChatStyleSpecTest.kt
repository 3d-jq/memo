package com.psyche.memo.ui

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chat-page style spec tests — the constants must stay byte-equal to the
 * Flutter source values (chat_message_widget.dart / chat_input_bar.dart /
 * message_list_view.dart); if one of these fails, someone changed the visual
 * spec without re-porting it from the original project.
 */
class ChatStyleSpecTest {

    // ---- Streaming dots painter math (CMW:4176-4191) ----

    @Test
    fun dotState_firstDotAtZeroFraction() {
        // phase = 0 → wave = 0.5 → scale = 0.925, alpha = 0.675
        val s = ChatStyleSpec.dotState(0f, 0)
        assertEquals(0.925f, s.scale, 1e-4f)
        assertEquals(0.675f, s.alpha, 1e-4f)
    }

    @Test
    fun dotState_phaseOffsetPerDot() {
        // fraction=0, i=1: phase = -0.22·2π → sin ≈ -0.98229 → wave ≈ 0.008855
        val s = ChatStyleSpec.dotState(0f, 1)
        assertEquals(0.85133f, s.scale, 1e-3f)
        assertEquals(0.45398f, s.alpha, 1e-3f)
    }

    @Test
    fun dotState_secondDotWrapsFraction() {
        // i=2 wraps below zero: (0 - 0.44) and (1 - 0.44) differ by a full
        // turn, so sin must be (nearly) identical.
        val a = ChatStyleSpec.dotState(0f, 2)
        val b = ChatStyleSpec.dotState(1f, 2)
        assertTrue(abs(a.scale - b.scale) < 1e-3f)
        assertTrue(abs(a.alpha - b.alpha) < 1e-3f)
    }

    @Test
    fun dotState_staysWithinPaintedBounds() {
        // Sweep a full cycle: scale ∈ [0.85, 1.0], alpha ∈ [0.45, 0.90].
        var f = 0f
        while (f <= 1f) {
            for (i in 0 until ChatStyleSpec.DOTS_COUNT) {
                val s = ChatStyleSpec.dotState(f, i)
                assertTrue(s.scale in 0.85f..1.0f)
                assertTrue(s.alpha in 0.45f..0.90f)
            }
            f += 0.05f
        }
    }

    @Test
    fun dotsCanvasDimensions() {
        // CMW:4148 — width = 9·3 + 6·2 = 39, height 16, 1100ms, phase 0.22.
        assertEquals(39f, ChatStyleSpec.DOTS_WIDTH_DP, 0f)
        assertEquals(16f, ChatStyleSpec.DOTS_HEIGHT_DP, 0f)
        assertEquals(1100, ChatStyleSpec.DOTS_DURATION_MS)
        assertEquals(0.22f, ChatStyleSpec.DOTS_PHASE_OFFSET, 0f)
        assertEquals(9f, ChatStyleSpec.DOTS_DOT_DP, 0f)
        assertEquals(6f, ChatStyleSpec.DOTS_GAP_DP, 0f)
        assertEquals(3, ChatStyleSpec.DOTS_COUNT)
    }

    // ---- Message list (CMW:1590-2850, MLV:1684-1690) ----

    @Test
    fun messageListSpec() {
        assertEquals(16f, ChatStyleSpec.USER_MESSAGE_HORIZONTAL_DP, 0f)
        assertEquals(20f, ChatStyleSpec.ASSISTANT_MESSAGE_HORIZONTAL_DP, 0f)
        assertEquals(12f, ChatStyleSpec.MESSAGE_VERTICAL_DP, 0f)
        assertEquals(8f, ChatStyleSpec.LIST_TOP_PADDING_DP, 0f)
        assertEquals(16f, ChatStyleSpec.LIST_BOTTOM_PADDING_DP, 0f)
        assertEquals(2f, ChatStyleSpec.NAME_TIME_GAP_DP, 0f)
        assertEquals(8f, ChatStyleSpec.HEADER_CONTENT_GAP_DP, 0f)
        assertEquals(8f, ChatStyleSpec.ASSISTANT_AVATAR_NAME_GAP_DP, 0f)
    }

    @Test
    fun userAvatarSpec() {
        // CMW:1590-1659 — 32 circle primary@0.1 with an 18 icon.
        assertEquals(32f, ChatStyleSpec.AVATAR_SIZE_DP, 0f)
        assertEquals(0.1f, ChatStyleSpec.AVATAR_BG_ALPHA, 0f)
        assertEquals(18f, ChatStyleSpec.AVATAR_ICON_DP, 0f)
    }

    @Test
    fun userBubbleSpec() {
        // CMW:1833/2389-2398/2046-2054.
        assertEquals(0.75f, ChatStyleSpec.USER_MAX_WIDTH_RATIO, 0f)
        assertEquals(16f, ChatStyleSpec.BUBBLE_CORNER_DP, 0f)
        assertEquals(12f, ChatStyleSpec.BUBBLE_PADDING_DP, 0f)
        assertEquals(0.08f, ChatStyleSpec.USER_BUBBLE_ALPHA_LIGHT, 0f)
        assertEquals(0.15f, ChatStyleSpec.USER_BUBBLE_ALPHA_DARK, 0f)
        assertEquals(15.5f, ChatStyleSpec.USER_TEXT_SP, 0f)
        assertEquals(22.475f, ChatStyleSpec.USER_TEXT_LINE_HEIGHT_SP, 1e-4f)
    }

    @Test
    fun actionsRowSpec() {
        // CMW:1847-1974 + ios_tactile.dart:54-59.
        assertEquals(8f, ChatStyleSpec.ACTIONS_TOP_GAP_DP, 0f)
        assertEquals(28f, ChatStyleSpec.ACTION_SLOT_DP, 0f)
        assertEquals(16f, ChatStyleSpec.ACTION_ICON_DP, 0f)
        assertEquals(0.9f, ChatStyleSpec.ACTION_ICON_ALPHA, 0f)
        assertEquals(6f, ChatStyleSpec.ACTION_GAP_DP, 0f)
        assertEquals(0.405f, ChatStyleSpec.ACTION_DISABLED_ALPHA, 1e-6f)
    }

    // ---- Foreground palette (CMW:3926-3931) ----

    @Test
    fun foregroundPaletteSpec() {
        // base = onSurface; alpha per brightness (CMW:3926-3929).
        assertEquals(0.88f, ChatStyleSpec.FG_STRONG_DARK, 0f)
        assertEquals(0.78f, ChatStyleSpec.FG_STRONG_LIGHT, 0f)
        assertEquals(0.76f, ChatStyleSpec.FG_MEDIUM_DARK, 0f)
        assertEquals(0.66f, ChatStyleSpec.FG_MEDIUM_LIGHT, 0f)
        assertEquals(0.56f, ChatStyleSpec.FG_MUTED_DARK, 0f)
        assertEquals(0.46f, ChatStyleSpec.FG_MUTED_LIGHT, 0f)
        assertEquals(0.72f, ChatStyleSpec.FG_BODY_DARK, 0f)
        assertEquals(0.60f, ChatStyleSpec.FG_BODY_LIGHT, 0f)
    }

    // ---- Tool loading dots (CMW:5407) ----

    @Test
    fun toolLoadingDotsSpec() {
        assertEquals(3f, ChatStyleSpec.TOOL_LOADING_DOTS_DOT_DP, 0f)
        assertEquals(2f, ChatStyleSpec.TOOL_LOADING_DOTS_GAP_DP, 0f)
        assertEquals(12f, ChatStyleSpec.TOOL_LOADING_DOTS_HEIGHT_DP, 0f)
    }

    // ---- Input bar (CIB) ----

    @Test
    fun sendButtonSpec() {
        // CIB:3264-3315 — 32 circle (18 icon + 7 padding), disabled colors.
        assertEquals(32f, ChatStyleSpec.SEND_BUTTON_DP, 0f)
        assertEquals(18f, ChatStyleSpec.SEND_ICON_DP, 0f)
        assertEquals(0.12f, ChatStyleSpec.SEND_DISABLED_BG_ALPHA, 0f)
        assertEquals(0.38f, ChatStyleSpec.SEND_DISABLED_FG_ALPHA, 0f)
    }

    @Test
    fun inputComposerSpec() {
        assertEquals(15f, ChatStyleSpec.INPUT_TEXT_SP, 0f)
        assertEquals(0.45f, ChatStyleSpec.INPUT_HINT_ALPHA, 0f)
        assertEquals(0.54f, ChatStyleSpec.COMPACT_ICON_ALPHA_LIGHT, 0f)
        assertEquals(0.70f, ChatStyleSpec.COMPACT_ICON_ALPHA_DARK, 0f)
        assertEquals(8f, ChatStyleSpec.INPUT_ACTIONS_GAP_DP, 0f)
    }

    // ---- Top bar (home_page.dart mobile) ----

    @Test
    fun topBarSpec() {
        assertEquals(20f, ChatStyleSpec.TOP_BAR_MAP_ICON_DP, 0f)
        assertEquals(4f, ChatStyleSpec.TOP_BAR_TRAILING_GAP_DP, 0f)
    }
}

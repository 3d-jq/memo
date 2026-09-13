package com.psyche.memo.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keyboard-open pinning of the chat timeline.
 *
 * Ports `pinBottomDuringViewportResizeIfNeeded`
 * (`scroll_controller.dart` L312-321), called from `home_page.dart`
 * L763-771 `didChangeMetrics`. The regression it prevents: opening the
 * keyboard shrinks the timeline viewport, so a list that was parked on its last
 * message suddenly has a larger maxScrollExtent and the message the user was
 * reading slides behind the input bar.
 */
class ChatViewportFollowTest {

    /** Keyboard opening, no finger on the list, timeline following. */
    private fun pin(
        previousImeBottomPx: Int = 0,
        nextImeBottomPx: Int = 900,
        pointerDown: Boolean = false,
        following: Boolean = true,
    ) = shouldPinTimelineOnImeRise(previousImeBottomPx, nextImeBottomPx, pointerDown, following)

    @Test
    fun `keyboard opening pins a following timeline`() {
        assertTrue(pin())
    }

    @Test
    fun `a taller inset on an already open keyboard pins again`() {
        // The IME inset animates, so each step upwards is its own rise; pinning
        // on every step is what makes the content ride up with the keyboard.
        assertTrue(pin(previousImeBottomPx = 400, nextImeBottomPx = 700))
    }

    @Test
    fun `closing the keyboard does not pin`() {
        // The viewport grows back; LazyList clamps pixels to the new
        // maxScrollExtent on its own and stays bottom-aligned.
        assertFalse(pin(previousImeBottomPx = 900, nextImeBottomPx = 0))
    }

    @Test
    fun `an unchanged inset does not pin`() {
        assertFalse(pin(previousImeBottomPx = 900, nextImeBottomPx = 900))
    }

    @Test
    fun `never pins while a finger is on the timeline`() {
        assertFalse(pin(pointerDown = true))
        // Even a genuine rise stays ignored until the finger lifts.
        assertFalse(pin(previousImeBottomPx = 400, nextImeBottomPx = 900, pointerDown = true))
    }

    @Test
    fun `reading history is left alone`() {
        // `following` false = the user scrolled away from the tail. The
        // keyboard may cover part of the timeline, but the view must not jump
        // to the bottom (the original's isNearBottom(24) guard).
        assertFalse(pin(following = false))
    }
}

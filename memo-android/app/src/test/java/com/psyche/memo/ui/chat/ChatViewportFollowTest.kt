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

    /** Keyboard opening, no finger on the list, tail parked at the bottom. */
    private fun pin(
        previousImeBottomPx: Int = 0,
        nextImeBottomPx: Int = 900,
        pointerDown: Boolean = false,
        tailNearBottom: Boolean = true,
    ) = shouldPinTimelineOnImeRise(previousImeBottomPx, nextImeBottomPx, pointerDown, tailNearBottom)

    @Test
    fun `keyboard opening pins a bottom-aligned timeline`() {
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
        // The tail is not at the bottom, so the keyboard only covers part of
        // the timeline and the view must not jump (the original's
        // isNearBottom(24) guard). This is the position verdict, not the sticky
        // "is following" flag — a programmatic jump into history never clears
        // that flag, which is why it must not be used here.
        assertFalse(pin(tailNearBottom = false))
        // ...and it stays put for every step of the keyboard animation.
        assertFalse(pin(previousImeBottomPx = 400, nextImeBottomPx = 700, tailNearBottom = false))
    }

    // ── ImePinTracker ─────────────────────────────────────────────────────────

    private fun tracker(initialImeBottomPx: Int = 0) = ImePinTracker(initialImeBottomPx)

    @Test
    fun `a recorded rise is consumed exactly once`() {
        val tracker = tracker()
        tracker.record(imeBottomPx = 900, shouldPin = true)
        assertTrue(tracker.consume(900))
        // The effect may recompose again for the same inset; it must not pin twice.
        assertFalse(tracker.consume(900))
    }

    @Test
    fun `only the rise carrying the request can consume it`() {
        val tracker = tracker()
        tracker.record(imeBottomPx = 900, shouldPin = true)
        assertFalse(tracker.consume(400))
        assertTrue(tracker.consume(900))
    }

    @Test
    fun `a later rise without a request cannot be consumed`() {
        val tracker = tracker()
        tracker.record(imeBottomPx = 400, shouldPin = false)
        assertFalse(tracker.consume(400))
        // The keyboard closes; nothing was requested for that value either.
        tracker.record(imeBottomPx = 0, shouldPin = false)
        assertFalse(tracker.consume(0))
    }

    @Test
    fun `tracker reports the previous frame's inset`() {
        val tracker = tracker(initialImeBottomPx = 250)
        assertTrue(tracker.previousImeBottomPx == 250)
        tracker.record(imeBottomPx = 700, shouldPin = true)
        assertTrue(tracker.previousImeBottomPx == 700)
        // Seeding with the current inset is what stops "keyboard already open
        // at startup" from being read as a rise.
        assertFalse(
            shouldPinTimelineOnImeRise(
                previousImeBottomPx = 700,
                nextImeBottomPx = 700,
                pointerDown = false,
                tailNearBottom = true,
            ),
        )
    }
}

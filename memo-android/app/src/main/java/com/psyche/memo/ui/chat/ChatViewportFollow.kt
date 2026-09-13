package com.psyche.memo.ui.chat

/**
 * Viewport-follow rules for the chat timeline.
 *
 * Ports `home_page.dart` L763-771 `didChangeMetrics` →
 * `scroll_controller.dart` L312-321 `pinBottomDuringViewportResizeIfNeeded`:
 * when the software keyboard opens, its bottom inset shrinks the timeline's
 * viewport, and a list that was sitting on its last message must be moved to
 * the new bottom — otherwise the message the user was reading is pushed behind
 * the input bar.
 */

/**
 * Should the timeline be pinned to its bottom because the keyboard is opening?
 *
 * [tailNearBottom] must be the verdict **as of the layout before the rise** —
 * the same thing the original computes with `isNearBottom(24)` inside
 * `didChangeMetrics`, which runs before the new viewport is laid out. In Compose
 * the caller evaluates it during composition, where `layoutInfo` still
 * describes the previous frame, and hands the value in.
 *
 * Passing a sticky "is following" flag instead is wrong: it is never cleared by
 * programmatic jumps (tapping a quote, previous/next message, switching
 * conversations), so the keyboard would also yank a list the user had scrolled
 * back into history. Device-observed and reported 2026-09-13.
 *
 * @param previousImeBottomPx IME bottom inset from the previous frame.
 * @param nextImeBottomPx IME bottom inset now.
 * @param pointerDown whether a finger is currently down on the timeline.
 * @param tailNearBottom whether the last visible message sat at the viewport
 *   bottom before this rise.
 */
internal fun shouldPinTimelineOnImeRise(
    previousImeBottomPx: Int,
    nextImeBottomPx: Int,
    pointerDown: Boolean,
    tailNearBottom: Boolean,
): Boolean {
    // Opening only. Closing the keyboard grows the viewport again and the list
    // clamps itself back to the (now smaller) maxScrollExtent, which leaves it
    // bottom-aligned without any request.
    if (nextImeBottomPx <= previousImeBottomPx) return false
    // Never move the list programmatically while a finger is on it — the hard
    // rule the streaming follow already obeys.
    if (pointerDown) return false
    // Reading history: the keyboard just covers part of the timeline, exactly
    // like the original's isNearBottom guard.
    return tailNearBottom
}

/**
 * Carries the "pin on the next measured frame" request from the composition
 * (where the pre-resize verdict is available) to the effect that performs it.
 *
 * Deliberately a plain object rather than Compose state: [record] runs during
 * composition, and writing observable state there would schedule another
 * recomposition every frame.
 */
internal class ImePinTracker(initialImeBottomPx: Int) {
    private var previous: Int = initialImeBottomPx
    private var pinFor: Int? = null

    /** Previous frame's inset, to feed [shouldPinTimelineOnImeRise]. */
    val previousImeBottomPx: Int get() = previous

    /** Called during composition, once per frame. */
    fun record(imeBottomPx: Int, shouldPin: Boolean) {
        if (shouldPin) pinFor = imeBottomPx
        previous = imeBottomPx
    }

    /** True exactly once for the rise that was recorded. */
    fun consume(imeBottomPx: Int): Boolean {
        if (pinFor != imeBottomPx) return false
        pinFor = null
        return true
    }
}

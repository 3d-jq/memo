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
 * The original asks `isNearBottom(24)` at the moment the metrics change — which
 * it can, because `didChangeMetrics` fires *before* the new viewport is laid
 * out. A Compose effect cannot read that pre-layout geometry reliably, so the
 * caller passes [following] instead: it is the sticky form of the same
 * verdict ("the tail is at the bottom and the user has not taken over"),
 * maintained by the scroll-position logic, and it does not depend on when the
 * effect happens to run.
 *
 * @param previousImeBottomPx IME bottom inset from the previous frame.
 * @param nextImeBottomPx IME bottom inset now.
 * @param pointerDown whether a finger is currently down on the timeline.
 * @param following whether the timeline is meant to stay at the bottom.
 */
internal fun shouldPinTimelineOnImeRise(
    previousImeBottomPx: Int,
    nextImeBottomPx: Int,
    pointerDown: Boolean,
    following: Boolean,
): Boolean {
    // Opening only. Closing the keyboard grows the viewport again and the list
    // clamps itself back to the (now smaller) maxScrollExtent, which leaves it
    // bottom-aligned without any request.
    if (nextImeBottomPx <= previousImeBottomPx) return false
    // Never move the list programmatically while a finger is on it — the hard
    // rule the streaming follow already obeys.
    if (pointerDown) return false
    // Reading history (the user scrolled away): the keyboard just covers part
    // of the timeline, exactly like the original's isNearBottom guard.
    return following
}

package com.psyche.memo.ui.snackbar

import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Queue behaviour of the top snackbar stack.
 *
 * The regression: the auto-dismiss countdown lived in the rendered item, so
 * only [SnackbarManager.MAX_VISIBLE] toasts ever expired. Anything queued
 * behind them stayed in the list forever, then replayed its entrance animation
 * and a fresh countdown when it finally reached the visible window — a burst of
 * toasts looked stuck. The countdown now starts in `show()`, like the original
 * (`snackbar.dart` L78).
 *
 * Robolectric drives `Dispatchers.Main`'s handler, so `idleFor` advances the
 * coroutine `delay` without real waiting. No animation runs here — the fades
 * belong to the item and need a frame clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToastQueueTest {

    /** Comfortably past any countdown + exit fade used by these cases. */
    private val settleMs = 6000L

    private fun idleFor(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    }

    @Before
    fun setUp() = SnackbarManager.resetForTest()

    @After
    fun tearDown() = SnackbarManager.resetForTest()

    @Test
    fun `at most three toasts are visible`() {
        assertEquals(3, SnackbarManager.MAX_VISIBLE)
    }

    @Test
    fun `a burst larger than the visible window still drains`() {
        repeat(6) { index -> SnackbarManager.show(AppNotification("toast $index")) }
        assertEquals(6, SnackbarManager.activeCount)

        idleFor(settleMs)
        // The regression left the entries past MAX_VISIBLE queued forever.
        assertEquals(0, SnackbarManager.activeCount)
    }

    @Test
    fun `nothing is dropped before its countdown ends`() {
        repeat(5) { SnackbarManager.show(AppNotification("t$it", durationMs = 3000)) }
        idleFor(1000)
        assertEquals(5, SnackbarManager.activeCount)
    }

    @Test
    fun `a short toast expires before a long one and both are eventually gone`() {
        SnackbarManager.show(AppNotification("short", durationMs = 500))
        SnackbarManager.show(AppNotification("long", durationMs = 4000))

        idleFor(1500)
        assertEquals(1, SnackbarManager.activeCount)

        idleFor(settleMs)
        assertEquals(0, SnackbarManager.activeCount)
    }

    @Test
    fun `successive bursts do not accumulate`() {
        repeat(3) { SnackbarManager.show(AppNotification("first $it")) }
        idleFor(settleMs)
        assertEquals(0, SnackbarManager.activeCount)

        repeat(4) { SnackbarManager.show(AppNotification("second $it")) }
        assertEquals(4, SnackbarManager.activeCount)
        idleFor(settleMs)
        assertEquals(0, SnackbarManager.activeCount)
    }

    @Test
    fun `the queue head is the newest toast`() {
        SnackbarManager.show(AppNotification("older"))
        SnackbarManager.show(AppNotification("newer"))
        // The overlay renders from index 0, so the newest notification owns the
        // top slot (the original inserts at 0 too).
        assertEquals(listOf("newer", "older"), SnackbarManager.activeMessages)
    }

    @Test
    fun `an expiring toast leaves the queue in order`() {
        SnackbarManager.show(AppNotification("a", durationMs = 500))
        SnackbarManager.show(AppNotification("b", durationMs = 4000))
        idleFor(1500)
        assertEquals(listOf("b"), SnackbarManager.activeMessages)
    }
}

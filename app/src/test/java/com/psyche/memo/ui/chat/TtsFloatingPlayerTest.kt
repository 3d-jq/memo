package com.psyche.memo.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The floating player's interactions.
 *
 * These cover the wiring the pure playback tests cannot see: the pill has to
 * follow a drag (it used to recompute from a composition-time origin and snap
 * back), and the close button has to reach the player (it used to stop into the
 * "ended" state, which keeps the pill on screen).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TtsFloatingPlayerTest {

    @get:Rule
    val compose = createComposeRule()

    private class RecordingActions : TtsPlayerActions {
        var toggles = 0
        var stops = 0
        var back = 0
        var forward = 0
        var speeds = 0

        override fun togglePause() { toggles++ }
        override fun stop() { stops++ }
        override fun seekBackward() { back++ }
        override fun seekForward() { forward++ }
        override fun cycleSpeed() { speeds++ }
    }

    private val playing = TtsPlaybackState(
        status = TtsPlaybackStatus.PLAYING,
        positionMs = 500,
        durationMs = 2_000,
        totalChunks = 4,
        currentChunkIndex = 1,
    )

    @Test
    fun `the pill follows a drag`() {
        val actions = RecordingActions()
        compose.setContent { TtsFloatingPlayer(state = playing, actions = actions) }

        val before = compose.onNodeWithTag(TTS_PLAYER_TAG).getUnclippedBoundsInRoot()
        compose.onNodeWithTag(TTS_PLAYER_TAG).performTouchInput {
            down(center)
            moveBy(Offset(90f, 140f))
            up()
        }
        compose.waitForIdle()
        val after = compose.onNodeWithTag(TTS_PLAYER_TAG).getUnclippedBoundsInRoot()

        assertTrue(
            "expected the pill to move right (was ${before.left}, now ${after.left})",
            after.left > before.left,
        )
        assertTrue(
            "expected the pill to move down (was ${before.top}, now ${after.top})",
            after.top > before.top,
        )
    }

    @Test
    fun `a second drag continues from where the first one ended`() {
        val actions = RecordingActions()
        compose.setContent { TtsFloatingPlayer(state = playing, actions = actions) }

        compose.onNodeWithTag(TTS_PLAYER_TAG).performTouchInput {
            down(center)
            moveBy(Offset(60f, 0f))
            up()
        }
        compose.waitForIdle()
        val afterFirst = compose.onNodeWithTag(TTS_PLAYER_TAG).getUnclippedBoundsInRoot()

        compose.onNodeWithTag(TTS_PLAYER_TAG).performTouchInput {
            down(center)
            moveBy(Offset(60f, 0f))
            up()
        }
        compose.waitForIdle()
        val afterSecond = compose.onNodeWithTag(TTS_PLAYER_TAG).getUnclippedBoundsInRoot()

        assertTrue(
            "the second drag continued from the first (${afterFirst.left} → ${afterSecond.left})",
            afterSecond.left > afterFirst.left,
        )
    }

    @Test
    fun `the close button reaches the player`() {
        val actions = RecordingActions()
        compose.setContent { TtsFloatingPlayer(state = playing, actions = actions) }

        compose.onNodeWithContentDescription("Close player").performClick()

        assertEquals(1, actions.stops)
    }

    @Test
    fun `the play button toggles and the expand chevron reveals the extra controls`() {
        val actions = RecordingActions()
        compose.setContent { TtsFloatingPlayer(state = playing, actions = actions) }

        compose.onNodeWithContentDescription("Pause").performClick()
        assertEquals(1, actions.toggles)

        // Collapsed: no seek controls.
        compose.onNodeWithContentDescription("Back 15 seconds").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand playback controls").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Back 15 seconds").performClick()
        compose.onNodeWithContentDescription("Forward 15 seconds").performClick()
        compose.onNodeWithContentDescription("Playback speed").performClick()

        assertEquals(1, actions.back)
        assertEquals(1, actions.forward)
        assertEquals(1, actions.speeds)
    }

    @Test
    fun `an idle state renders nothing`() {
        compose.setContent { TtsFloatingPlayer(state = TtsPlaybackState(), actions = RecordingActions()) }
        compose.onNodeWithTag(TTS_PLAYER_TAG).assertDoesNotExist()
    }

    @Test
    fun `a finished session keeps the replay button`() {
        var state by mutableStateOf(playing)
        val actions = RecordingActions()
        compose.setContent { TtsFloatingPlayer(state = state, actions = actions) }

        state = state.copy(status = TtsPlaybackStatus.ENDED)
        compose.waitForIdle()

        compose.onNodeWithTag(TTS_PLAYER_TAG).assertExists()
        compose.onNodeWithContentDescription("Replay").performClick()
        assertEquals(1, actions.toggles)
    }
}

package com.psyche.memo.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The playback logic, driven by a fake engine.
 *
 * These are the tests the shipped bugs were missing: the pure chunker/timeline
 * tests say nothing about *how* the player talks to the engine, and that is where
 * the close button did nothing (a stop that reported "ended", which the pill
 * treats as still visible), where the chat action row kept showing its stop icon
 * after a stop (a late `onStart` from the cancelled session), and where an
 * ambiguous utterance id made that late callback indistinguishable from a live
 * one.
 */
class TtsPlaybackControllerTest {

    /** Records what was spoken and lets a test fire the engine's callbacks. */
    private class FakeEngine : TtsEngine {
        override var listener: TtsEngine.Listener? = null
        val spoken = mutableListOf<Triple<String, String, Float>>()
        var stopped = 0
        var shutdown = 0
        var failNextSpeak = false
        private var ready = true

        fun pending(): Boolean = false

        override fun prepare(onReady: (Boolean) -> Unit) {
            if (ready) onReady(true)
        }

        override fun speak(text: String, utteranceId: String, rate: Float) {
            if (failNextSpeak) {
                failNextSpeak = false
                error("engine refused")
            }
            spoken.add(Triple(text, utteranceId, rate))
        }

        override fun stop() {
            stopped++
        }

        override fun shutdown() {
            shutdown++
        }

        /** Ids as the engine would report them. */
        fun startLast() = spoken.last().let { listener?.onStart(it.second) }
        fun doneLast() = spoken.last().let { listener?.onDone(it.second) }
        fun rangeLast(start: Int) = spoken.last().let { listener?.onRangeStart(it.second, start, start + 1) }
        fun errorLast(code: Int = -1) = spoken.last().let { listener?.onError(it.second, code) }
    }

    /** A small chunk size keeps the multi-chunk paths reachable in tests. */
    private fun controller(
        engine: FakeEngine = FakeEngine(),
        chunkMaxLength: Int = 360,
    ) = engine to TtsPlaybackController(engine, chunkMaxLength)

    // ---- the close button ----------------------------------------------------

    @Test
    fun `stop resets to idle and hides the pill`() {
        val (engine, player) = controller()
        player.speak("Hello there.")
        engine.startLast()
        assertTrue(player.state.value.isPlayerVisible)
        assertTrue(player.speaking.value)

        player.stop()

        assertEquals(TtsPlaybackStatus.IDLE, player.state.value.status)
        assertFalse("the pill must disappear", player.state.value.isPlayerVisible)
        assertFalse("the chat icon must flip back", player.speaking.value)
        assertEquals(1, engine.stopped)
    }

    @Test
    fun `a late start from the cancelled session is ignored`() {
        val (engine, player) = controller()
        player.speak("Hello there.")
        val cancelledId = engine.spoken.single().second

        player.stop()
        // The engine delivers the start of the utterance we already cancelled.
        engine.listener?.onStart(cancelledId)

        assertEquals(TtsPlaybackStatus.IDLE, player.state.value.status)
        assertFalse(player.speaking.value)
    }

    @Test
    fun `a late completion from the cancelled session is ignored`() {
        val (engine, player) = controller()
        player.speak("Hello there.")
        val cancelledId = engine.spoken.single().second

        player.stop()
        engine.listener?.onDone(cancelledId)
        engine.listener?.onError(cancelledId, 5)

        assertEquals(TtsPlaybackStatus.IDLE, player.state.value.status)
        assertFalse(player.state.value.isPlayerVisible)
    }

    @Test
    fun `a finished session stays visible and replays`() {
        val (engine, player) = controller()
        player.speak("Hello there.")
        engine.startLast()
        engine.doneLast()

        // Natural end: the pill stays so the replay button is reachable...
        assertEquals(TtsPlaybackStatus.ENDED, player.state.value.status)
        assertTrue(player.state.value.isPlayerVisible)
        // ...but the chat icon is back to "speak".
        assertFalse(player.speaking.value)

        player.togglePause()

        assertEquals(TtsPlaybackStatus.PLAYING, player.state.value.status)
        assertTrue(player.speaking.value)
        assertEquals(2, engine.spoken.size)
        assertEquals(engine.spoken[0].first, engine.spoken[1].first)
    }

    // ---- chunk sequencing ----------------------------------------------------

    @Test
    fun `long text is spoken chunk by chunk and ends after the last one`() {
        val (engine, player) = controller(chunkMaxLength = 12)
        // Two sentences that cannot merge at this chunk size.
        player.speak("aaaaaaaaaa. bbbbbbbbbb.")
        assertEquals(2, player.state.value.totalChunks)
        assertEquals(1, engine.spoken.size)

        engine.startLast()
        engine.doneLast()
        assertEquals(2, engine.spoken.size)
        assertEquals(TtsPlaybackStatus.PLAYING, player.state.value.status)

        engine.startLast()
        engine.doneLast()
        assertEquals(TtsPlaybackStatus.ENDED, player.state.value.status)
    }

    @Test
    fun `pause stops the engine and resume restarts the current chunk`() {
        val (engine, player) = controller()
        player.speak("Hello there. Second sentence.")
        engine.startLast()

        player.togglePause()
        assertEquals(TtsPlaybackStatus.PAUSED, player.state.value.status)
        assertTrue(player.speaking.value)
        assertEquals(1, engine.stopped)

        player.togglePause()
        assertEquals(TtsPlaybackStatus.PLAYING, player.state.value.status)
        assertEquals(2, engine.spoken.size)
    }

    @Test
    fun `seeking forward and back moves to the chunk that holds the time`() {
        val (engine, player) = controller(chunkMaxLength = 12)
        // Five 10-character chunks, 2 s each, so +15 s lands in the last one.
        player.speak("aaaaaaaaaa. bbbbbbbbbb. cccccccccc. dddddddddd. eeeeeeeeee.")
        engine.startLast()
        val total = player.state.value.totalChunks
        assertTrue(total >= 3)

        player.seekForward()
        assertEquals(total - 1, player.state.value.currentChunkIndex)

        player.seekBackward()
        assertEquals(0, player.state.value.currentChunkIndex)
    }

    @Test
    fun `the speed pill sets a rate on the engine and restarts the chunk`() {
        val (engine, player) = controller()
        player.speak("Hello there.")
        engine.startLast()
        val firstId = engine.spoken.single().second

        player.cyclePlaybackSpeed()

        assertEquals(1.2, player.state.value.speed, 0.0001)
        assertEquals(2, engine.spoken.size)
        // 1.2 / 2 = the engine's own rate axis.
        assertEquals(0.6f, engine.spoken.last().third, 0.0001f)
        // The restarted utterance carries the same chunk id (same session).
        assertEquals(firstId, engine.spoken.last().second)
    }

    @Test
    fun `progress follows the reported character range`() {
        val (engine, player) = controller()
        player.speak("abcdefghij.")
        engine.startLast()
        engine.rangeLast(start = 5)

        val state = player.state.value
        assertTrue("position should have advanced", state.positionMs > 0)
        assertTrue("position stays inside the session", state.positionMs <= state.durationMs)
    }

    @Test
    fun `an engine failure reports an error instead of a silent stop`() {
        val (engine, player) = controller()
        engine.failNextSpeak = true
        player.speak("Hello there.")

        assertEquals(TtsPlaybackStatus.ERROR, player.state.value.status)
        assertFalse(player.speaking.value)
        assertEquals("engine refused", player.state.value.errorMessage)
    }

    // ---- who owns the playback ------------------------------------------------

    @Test
    fun `the owner travels with the session and is cleared by stop`() {
        val (engine, player) = controller()
        player.speak("Hello there.", ownerId = "msg-1")
        assertEquals("msg-1", player.state.value.ownerId)
        assertTrue(player.state.value.isOwnedBy("msg-1"))
        assertFalse("other messages must not react", player.state.value.isOwnedBy("msg-2"))

        engine.startLast()
        engine.doneLast()
        // A finished session keeps its owner so the replay is still "that" message.
        assertEquals("msg-1", player.state.value.ownerId)

        player.stop()
        assertEquals(null, player.state.value.ownerId)
        assertFalse(player.state.value.isOwnedBy("msg-1"))
    }

    @Test
    fun `pausing keeps the owner and reports resume for it`() {
        val (engine, player) = controller()
        player.speak("Hello there.", ownerId = "msg-1")
        engine.startLast()

        player.togglePause()

        assertEquals("msg-1", player.state.value.ownerId)
        assertEquals(MessageTtsAction.RESUME, messageTtsAction(player.state.value, "msg-1"))
        assertEquals(MessageTtsAction.SPEAK, messageTtsAction(player.state.value, "msg-2"))
    }

    @Test
    fun `the action row only offers stop to the owner`() {
        val (engine, player) = controller()
        player.speak("Hello there.", ownerId = "msg-1")
        assertEquals(MessageTtsAction.STOP, messageTtsAction(player.state.value, "msg-1"))
        assertEquals(MessageTtsAction.SPEAK, messageTtsAction(player.state.value, "msg-2"))
        assertEquals(MessageTtsAction.SPEAK, messageTtsAction(player.state.value, null))

        // A finished session is back to "speak" for its owner too.
        engine.startLast()
        engine.doneLast()
        assertEquals(MessageTtsAction.SPEAK, messageTtsAction(player.state.value, "msg-1"))
    }

    @Test
    fun `playback without an owner leaves every message alone`() {
        val (engine, player) = controller()
        // The text_to_speech tool card replays raw text: no message owns it.
        player.speak("Hello there.")
        engine.startLast()
        assertEquals(MessageTtsAction.SPEAK, messageTtsAction(player.state.value, "msg-1"))
        assertEquals(MessageTtsAction.SPEAK, messageTtsAction(player.state.value, null))
    }

    // ---- the id codec these callbacks are routed by ---------------------------

    @Test
    fun `the utterance id keeps the session and the chunk apart`() {
        assertEquals("memo_tts_0_0", ttsUtteranceId(0, 0))
        assertEquals("memo_tts_1_2", ttsUtteranceId(1, 2))
        assertEquals(0 to 0, parseTtsUtteranceId("memo_tts_0_0"))
        // The case a plain concatenation got wrong: session 1, chunk 2 must not
        // read as chunk 12 of session 0.
        assertEquals(1 to 2, parseTtsUtteranceId("memo_tts_1_2"))
        assertEquals(12 to 30, parseTtsUtteranceId("memo_tts_12_30"))
        assertEquals(null, parseTtsUtteranceId("memo_tts_chunk_12"))
        assertEquals(null, parseTtsUtteranceId("something_else"))
        assertEquals(null, parseTtsUtteranceId(null))
    }
}

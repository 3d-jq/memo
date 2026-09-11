package com.psyche.memo.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tts_text_chunker.dart / tts_playback_models.dart — chunk splitting, the
 * estimated timeline the progress ring and the ±15 s seek are built on, and the
 * speed presets.
 */
class TtsPlaybackTest {

    // ---- chunker -------------------------------------------------------------

    @Test
    fun `whitespace is normalized and blank input yields nothing`() {
        assertEquals(emptyList<TtsTextChunk>(), TtsTextChunker.split("   "))
        val single = TtsTextChunker.split("Hello   there\nworld")
        assertEquals(1, single.size)
        assertEquals("Hello there world", single.single().text)
    }

    @Test
    fun `sentence boundaries split and stay merged while they fit`() {
        val chunks = TtsTextChunker.split("One. Two. Three.", maxChunkLength = 220)
        // All three sentences fit into one merged chunk.
        assertEquals(1, chunks.size)
        assertEquals("One. Two. Three.", chunks.single().text)
    }

    @Test
    fun `sentences are packed until the next one would overflow`() {
        val chunks = TtsTextChunker.split("aaaa. bbbb. cccc.", maxChunkLength = 12)
        assertTrue(chunks.size >= 2)
        chunks.forEach { assertTrue("chunk too long: ${it.text}", it.text.length <= 12) }
        // Offsets run contiguously from zero.
        assertEquals(0, chunks.first().startOffset)
        chunks.zipWithNext().forEach { (a, b) -> assertEquals(a.endOffset, b.startOffset) }
    }

    @Test
    fun `an oversized sentence is hard-split at the limit`() {
        val chunks = TtsTextChunker.split("x".repeat(25), maxChunkLength = 10)
        assertEquals(listOf(10, 10, 5), chunks.map { it.text.length })
    }

    @Test
    fun `cjk boundaries split without a separating space`() {
        // Two 3-character sentences merge while they fit (6 <= 6) and split when
        // they do not — and CJK never gains a separating space.
        assertEquals(1, TtsTextChunker.split("你好。世界。", maxChunkLength = 6).size)
        val chunks = TtsTextChunker.split("你好。世界。", maxChunkLength = 4)
        assertEquals(listOf("你好。", "世界。"), chunks.map { it.text })
    }

    @Test
    fun `an ascii boundary keeps its separating space when merged`() {
        // 'Hello.' + ' World' → the chunker re-adds the needed space.
        val chunks = TtsTextChunker.split("Hello. World", maxChunkLength = 220)
        assertEquals("Hello. World", chunks.single().text)
    }

    // ---- timeline ------------------------------------------------------------

    private fun timeline(text: String, max: Int = 220) = TtsPlaybackTimeline(TtsTextChunker.split(text, max))

    @Test
    fun `chunk durations are estimated from the character count`() {
        val chunk = TtsTextChunk(index = 0, text = "x".repeat(10), startOffset = 0)
        assertEquals(2_000L, TtsPlaybackTimeline(listOf(chunk)).durationForChunk(chunk))
        // Clamped to at least a second and at most a minute.
        assertEquals(1_000L, TtsPlaybackTimeline(emptyList()).durationForChunk(TtsTextChunk(0, "a", 0)))
        assertEquals(
            60_000L,
            TtsPlaybackTimeline(emptyList()).durationForChunk(TtsTextChunk(0, "x".repeat(1_000), 0)),
        )
    }

    @Test
    fun `the timeline sums its chunks and offsets each one`() {
        val chunks = TtsTextChunker.split("aaaa. bbbb. cccc.", maxChunkLength = 12)
        val line = TtsPlaybackTimeline(chunks)
        assertEquals(chunks.sumOf { line.durationForChunk(it) }, line.estimatedDurationMs)
        assertEquals(0L, line.offsetForChunk(0))
        assertEquals(line.durationForChunk(chunks.first()), line.offsetForChunk(1))
    }

    @Test
    fun `a seek lands on the chunk that holds the target time`() {
        val chunks = listOf(
            TtsTextChunk(0, "x".repeat(10), 0), // 2s
            TtsTextChunk(1, "y".repeat(10), 10), // 2s
            TtsTextChunk(2, "z".repeat(10), 20), // 2s
        )
        val line = TtsPlaybackTimeline(chunks)
        assertEquals(6_000L, line.estimatedDurationMs)

        val forward = line.seekTarget(currentPositionMs = 0L, deltaMs = 3_000L)
        assertEquals(1, forward.chunkIndex)
        assertEquals(1_000L, forward.offsetInChunkMs)
        assertEquals(3_000L, forward.positionMs)

        val backward = line.seekTarget(currentPositionMs = 3_000L, deltaMs = -3_000L)
        assertEquals(0, backward.chunkIndex)
        assertEquals(0L, backward.positionMs)
    }

    @Test
    fun `seeking past the ends is clamped`() {
        val chunks = listOf(TtsTextChunk(0, "x".repeat(10), 0))
        val line = TtsPlaybackTimeline(chunks)

        val past = line.seekTarget(currentPositionMs = 0L, deltaMs = 60_000L)
        assertEquals(2_000L, past.positionMs)
        assertEquals(0, past.chunkIndex)

        val before = line.seekTarget(currentPositionMs = 1_000L, deltaMs = -60_000L)
        assertEquals(0L, before.positionMs)
    }

    @Test
    fun `position for chunk progress adds the earlier chunks`() {
        val chunks = listOf(
            TtsTextChunk(0, "x".repeat(10), 0),
            TtsTextChunk(1, "y".repeat(10), 10),
        )
        val line = TtsPlaybackTimeline(chunks)
        assertEquals(2_500L, line.positionForChunkProgress(chunkIndex = 1, chunkPositionMs = 500L))
        // Out-of-range indices clamp instead of throwing.
        assertEquals(4_000L, line.positionForChunkProgress(chunkIndex = 9, chunkPositionMs = 9_999L))
    }

    // ---- state + speed -------------------------------------------------------

    @Test
    fun `the player is visible while active and after it ends`() {
        assertFalse(TtsPlaybackState().isPlayerVisible)
        assertTrue(TtsPlaybackState(status = TtsPlaybackStatus.PLAYING).isPlayerVisible)
        assertTrue(TtsPlaybackState(status = TtsPlaybackStatus.PAUSED).isActive)
        assertTrue(TtsPlaybackState(status = TtsPlaybackStatus.ENDED).isPlayerVisible)
        assertFalse(TtsPlaybackState(status = TtsPlaybackStatus.ENDED).isActive)
    }

    @Test
    fun `progress and chunk progress are clamped`() {
        val state = TtsPlaybackState(positionMs = 500, durationMs = 2_000, currentChunkIndex = 1, totalChunks = 4)
        assertEquals(0.25, state.progress, 0.0001)
        assertEquals(0.25, state.chunkProgress, 0.0001)
        assertEquals(0.0, TtsPlaybackState(durationMs = 0, positionMs = 10).progress, 0.0001)
        assertEquals(0.0, TtsPlaybackState(currentChunkIndex = 3, totalChunks = 0).chunkProgress, 0.0001)
    }

    @Test
    fun `the speed pill cycles through the presets and wraps`() {
        assertEquals(listOf(0.8, 1.0, 1.2, 1.5, 2.0), TtsPlaybackSpeed.values)
        assertEquals(1.2, TtsPlaybackSpeed.next(1.0), 0.0001)
        assertEquals(0.8, TtsPlaybackSpeed.next(2.0), 0.0001)
        // An unknown value restarts the cycle.
        assertEquals(0.8, TtsPlaybackSpeed.next(1.7), 0.0001)
        assertEquals(0.8, TtsPlaybackSpeed.normalize(0.1), 0.0001)
        assertEquals(2.0, TtsPlaybackSpeed.normalize(9.0), 0.0001)
        // The engine's own rate axis is half the displayed speed.
        assertEquals(0.5, TtsPlaybackSpeed.toSystemRate(1.0), 0.0001)
    }
}

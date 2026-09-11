package com.psyche.memo.ui.chat

/**
 * tts_text_chunker.dart — splits the text being read into speakable chunks.
 *
 * The split is what makes pause/resume and ±15 s seeking possible at all: the
 * engine speaks one chunk at a time, so a "position" is a chunk index plus an
 * offset inside it.
 */
data class TtsTextChunk(
    val index: Int,
    val text: String,
    val startOffset: Int,
) {
    val endOffset: Int get() = startOffset + text.length
}

object TtsTextChunker {

    /** Sentence/line marks that end a chunk (CJK and ASCII). */
    private val BOUNDARIES = setOf('。', '！', '？', '；', '!', '?', ';', '.', '\n')

    private const val WHITESPACE = "\\s+"

    fun split(text: String, maxChunkLength: Int = 220): List<TtsTextChunk> {
        val normalized = text.replace(Regex(WHITESPACE), " ").trim()
        if (normalized.isEmpty()) return emptyList()

        // Upstream keeps a `maxChunkLength < 40 ? maxChunkLength : maxChunkLength`
        // guard, which is a no-op; mirrored here as the plain value.
        val safeMax = maxChunkLength
        val segments = splitIntoSegments(normalized, safeMax)
        val chunks = mutableListOf<String>()

        for (segment in segments) {
            if (chunks.isEmpty()) {
                chunks.add(segment)
                continue
            }
            val merged = joinForSpeech(chunks.last(), segment)
            if (merged.length <= safeMax) {
                chunks[chunks.lastIndex] = merged
            } else {
                chunks.add(segment)
            }
        }

        var offset = 0
        return chunks.mapIndexed { index, value ->
            TtsTextChunk(index = index, text = value, startOffset = offset).also {
                offset += value.length
            }
        }
    }

    private fun splitIntoSegments(text: String, maxChunkLength: Int): List<String> {
        val segments = mutableListOf<String>()
        val buffer = StringBuilder()

        fun flush() {
            val value = buffer.toString().trim()
            buffer.clear()
            if (value.isEmpty()) return
            if (value.length <= maxChunkLength) {
                segments.add(value)
                return
            }
            var start = 0
            while (start < value.length) {
                val end = (start + maxChunkLength).coerceIn(0, value.length)
                segments.add(value.substring(start, end))
                start += maxChunkLength
            }
        }

        for (char in text) {
            buffer.append(char)
            if (char in BOUNDARIES) {
                flush()
            } else if (buffer.length >= maxChunkLength) {
                flush()
            }
        }
        flush()
        return segments
    }

    /**
     * A sentence boundary already ends the previous chunk; when an ASCII
     * boundary meets an ASCII word the two need a separating space
     * (`Hello.World` → `Hello. World`), CJK does not.
     */
    private fun joinForSpeech(first: String, second: String): String {
        if (first.isEmpty()) return second
        if (second.isEmpty()) return first
        val needsSpace = isAsciiBoundary(first.last().code) && isAsciiWord(second.first().code)
        return if (needsSpace) "$first $second" else first + second
    }

    private fun isAsciiBoundary(code: Int): Boolean =
        code == 0x2e || code == 0x21 || code == 0x3f || code == 0x3b || code == 0x3a || code == 0x2c

    private fun isAsciiWord(code: Int): Boolean =
        code in 0x30..0x39 || code in 0x41..0x5a || code in 0x61..0x7a
}

/** tts_playback_models.dart `TtsPlaybackStatus`. */
enum class TtsPlaybackStatus { IDLE, BUFFERING, PLAYING, PAUSED, ENDED, ERROR }

/**
 * `TtsPlaybackState` — what the floating player renders. `isPlayerVisible`
 * keeps the pill on screen after playback ends so the replay button is
 * reachable.
 */
data class TtsPlaybackState(
    val status: TtsPlaybackStatus = TtsPlaybackStatus.IDLE,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Double = 1.0,
    val currentChunkIndex: Int = 0,
    val totalChunks: Int = 0,
    val errorMessage: String? = null,
    val usingNetwork: Boolean = false,
) {
    val isActive: Boolean
        get() = status == TtsPlaybackStatus.BUFFERING ||
            status == TtsPlaybackStatus.PLAYING ||
            status == TtsPlaybackStatus.PAUSED

    val isPlayerVisible: Boolean get() = isActive || status == TtsPlaybackStatus.ENDED

    val progress: Double
        get() = if (durationMs <= 0) 0.0 else (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0)

    val chunkProgress: Double
        get() = if (totalChunks <= 0) 0.0 else (currentChunkIndex.toDouble() / totalChunks).coerceIn(0.0, 1.0)
}

/** Where a seek lands: a chunk plus an offset inside it. */
data class TtsSeekTarget(
    val chunkIndex: Int,
    val offsetInChunkMs: Long,
    val positionMs: Long,
)

/**
 * `TtsPlaybackTimeline` — estimated timings, since the system engine reports no
 * duration. 200 ms per character is upstream's constant (chunk durations clamp
 * to 1–60 s), which is what the ring and the ±15 s seek are computed from.
 */
class TtsPlaybackTimeline(
    chunks: List<TtsTextChunk>,
    private val millisecondsPerCharacter: Int = 200,
) {
    val chunks: List<TtsTextChunk> = chunks.toList()

    val estimatedDurationMs: Long
        get() = this.chunks.sumOf { durationForChunk(it) }

    fun positionForChunkProgress(chunkIndex: Int, chunkPositionMs: Long, chunkDurationMs: Long? = null): Long {
        val clampedIndex = clampChunkIndex(chunkIndex)
        var elapsed = 0L
        for (i in 0 until clampedIndex) elapsed += durationForChunk(this.chunks[i])
        val duration = chunkDurationMs ?: durationForChunk(this.chunks[clampedIndex])
        return (elapsed + chunkPositionMs.coerceIn(0L, duration)).coerceIn(0L, estimatedDurationMs)
    }

    fun seekTarget(currentPositionMs: Long, deltaMs: Long): TtsSeekTarget {
        val target = (currentPositionMs + deltaMs).coerceIn(0L, estimatedDurationMs)
        var remaining = target
        for (i in this.chunks.indices) {
            val duration = durationForChunk(this.chunks[i])
            if (remaining <= duration || i == this.chunks.lastIndex) {
                return TtsSeekTarget(
                    chunkIndex = i,
                    offsetInChunkMs = remaining.coerceIn(0L, duration),
                    positionMs = target,
                )
            }
            remaining -= duration
        }
        return TtsSeekTarget(0, 0L, 0L)
    }

    fun offsetForChunk(chunkIndex: Int): Long {
        val clampedIndex = clampChunkIndex(chunkIndex)
        var elapsed = 0L
        for (i in 0 until clampedIndex) elapsed += durationForChunk(this.chunks[i])
        return elapsed
    }

    fun durationForChunk(chunk: TtsTextChunk): Long =
        (chunk.text.length.toLong() * millisecondsPerCharacter).coerceIn(1_000L, 60_000L)

    private fun clampChunkIndex(index: Int): Int =
        if (this.chunks.isEmpty()) 0 else index.coerceIn(0, this.chunks.lastIndex)
}

/** `TtsPlaybackSpeed` — the presets the speed pill cycles through. */
object TtsPlaybackSpeed {

    val values = listOf(0.8, 1.0, 1.2, 1.5, 2.0)

    fun next(current: Double): Double {
        val index = values.indexOfFirst { kotlin.math.abs(it - current) < 0.01 }
        if (index == -1 || index == values.lastIndex) return values.first()
        return values[index + 1]
    }

    fun normalize(value: Double): Double = value.coerceIn(values.first(), values.last())

    /** The engine's own rate axis runs 0.1–1.0, i.e. half the displayed speed. */
    fun toSystemRate(speed: Double): Double = (speed / 2).coerceIn(0.1, 1.0)
}

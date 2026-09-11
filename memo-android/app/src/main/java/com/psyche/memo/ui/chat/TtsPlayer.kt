package com.psyche.memo.ui.chat

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.psyche.memo.ui.R as UiR
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * System-engine TTS playback for the chat (chat_message_widget.dart
 * `_replayTextToSpeech` + `tts_provider.dart` system path).
 *
 * Long text is split into chunks and spoken one at a time, which is what makes
 * pause/resume and ±15 s seeking possible on an engine that cannot pause an
 * utterance: "pause" stops the engine, "resume" restarts at the current chunk,
 * and a seek moves the chunk cursor. Positions are estimated from the played
 * character offsets (see [TtsPlaybackTimeline]); network TTS voices are a later
 * batch, so [TtsPlaybackState.usingNetwork] stays false and the player hides its
 * save button exactly as upstream does when no network audio is available.
 */
object TtsPlayer {

    /** tts_provider.dart `_systemChunkMaxLength`. */
    private const val SYSTEM_CHUNK_MAX_LENGTH = 360

    @Volatile private var engine: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var pendingText: String? = null

    private val _state = MutableStateFlow(TtsPlaybackState())
    val state: StateFlow<TtsPlaybackState> = _state

    /** The chat action row's Speak/Stop icon follows this (isActive). */
    private val speakingFlow = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> get() = speakingFlow

    private var chunks: List<TtsTextChunk> = emptyList()
    private var timeline: TtsPlaybackTimeline? = null
    private var currentChunk = 0
    private var chunkOffsetMs = 0L
    private var session = 0
    private var paused = false

    fun speak(context: Context, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val split = TtsTextChunker.split(trimmed, SYSTEM_CHUNK_MAX_LENGTH)
        if (split.isEmpty()) return
        session++
        chunks = split
        timeline = TtsPlaybackTimeline(split)
        currentChunk = 0
        chunkOffsetMs = 0L
        paused = false
        publish(status = TtsPlaybackStatus.BUFFERING, positionMs = 0L, chunkIndex = 0)

        val existing = engine
        if (existing != null) {
            if (ready) playCurrent(existing) else pendingText = trimmed
            return
        }
        pendingText = trimmed
        // android.jar's TextToSpeech has no setOnInitListener — the callback only
        // comes through the constructor.
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            val queued = pendingText
            pendingText = null
            val initialized = engine
            if (ready && queued != null && initialized != null) {
                playCurrent(initialized)
            } else if (!ready) {
                finish(TtsPlaybackStatus.ERROR, "tts_unavailable")
            }
        }
        engine?.setOnUtteranceProgressListener(utteranceListener)
    }

    /** Pause stops the engine; resume restarts at the current chunk. */
    fun togglePause() {
        if (!_state.value.isActive) return
        val engineRef = engine ?: return
        if (paused) {
            paused = false
            publish(status = TtsPlaybackStatus.PLAYING, positionMs = currentPosition(), chunkIndex = currentChunk)
            playCurrent(engineRef)
        } else {
            paused = true
            runCatching { engineRef.stop() }
            publish(status = TtsPlaybackStatus.PAUSED, positionMs = currentPosition(), chunkIndex = currentChunk)
        }
        syncSpeaking()
    }

    /** Ends the session but keeps the pill visible so it can be replayed. */
    fun stop() {
        session++
        runCatching { engine?.stop() }
        pendingText = null
        paused = false
        finish(TtsPlaybackStatus.ENDED, null)
    }

    fun seekBackward() = seekRelative(-SEEK_STEP_MS)

    fun seekForward() = seekRelative(SEEK_STEP_MS)

    fun seekRelative(deltaMs: Long) {
        val engineRef = engine ?: return
        val timelineRef = timeline ?: return
        if (chunks.isEmpty() || !_state.value.isActive) return
        val target = timelineRef.seekTarget(currentPosition(), deltaMs)
        currentChunk = target.chunkIndex
        chunkOffsetMs = target.offsetInChunkMs
        publish(status = TtsPlaybackStatus.PLAYING, positionMs = target.positionMs, chunkIndex = currentChunk)
        paused = false
        playCurrent(engineRef)
    }

    /** `tts.speak` on the current chunk, optionally skipping into it. */
    fun replay() = speakFromStart()

    fun speakFromStart() {
        val engineRef = engine ?: return
        if (chunks.isEmpty()) return
        currentChunk = 0
        chunkOffsetMs = 0L
        paused = false
        publish(status = TtsPlaybackStatus.PLAYING, positionMs = 0L, chunkIndex = 0)
        playCurrent(engineRef)
    }

    fun cyclePlaybackSpeed() {
        setPlaybackSpeed(TtsPlaybackSpeed.next(_state.value.speed))
    }

    fun setPlaybackSpeed(speed: Double) {
        val normalized = TtsPlaybackSpeed.normalize(speed)
        _state.value = _state.value.copy(speed = normalized)
        val engineRef = engine ?: return
        runCatching { engineRef.setSpeechRate(TtsPlaybackSpeed.toSystemRate(normalized).toFloat()) }
        // The rate only applies to utterances started after it was set.
        if (_state.value.isActive && !paused) playCurrent(engineRef)
    }

    /** Releases the engine (called when the app's root composition goes away). */
    fun shutdown() {
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
        pendingText = null
        chunks = emptyList()
        timeline = null
        session++
        _state.value = TtsPlaybackState()
        syncSpeaking()
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private fun playCurrent(tts: TextToSpeech) {
        val chunk = chunks.getOrNull(currentChunk) ?: run {
            finish(TtsPlaybackStatus.ENDED, null)
            return
        }
        runCatching {
            tts.language = Locale.getDefault()
            tts.setSpeechRate(TtsPlaybackSpeed.toSystemRate(_state.value.speed).toFloat())
            // A seek into the middle of a chunk restarts that chunk: the engine
            // speaks from its beginning, which is the granularity it offers.
            tts.speak(chunk.text, TextToSpeech.QUEUE_FLUSH, null, utteranceId(currentChunk))
        }.onFailure {
            finish(TtsPlaybackStatus.ERROR, it.message)
            return
        }
        publish(status = TtsPlaybackStatus.PLAYING, positionMs = currentPosition(), chunkIndex = currentChunk)
        syncSpeaking()
    }

    private fun utteranceId(index: Int) = "memo_tts_chunk_$session$index"

    private fun chunkIndexOf(utteranceId: String?): Int {
        val suffix = utteranceId?.substringAfterLast("_") ?: return -1
        if (utteranceId?.startsWith("memo_tts_chunk_") != true) return -1
        return suffix.toIntOrNull() ?: -1
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val index = chunkIndexOf(utteranceId)
            if (index >= 0) currentChunk = index
            publish(status = TtsPlaybackStatus.PLAYING, positionMs = currentPosition(), chunkIndex = currentChunk)
            syncSpeaking()
        }

        /**
         * Word-level offsets within the current utterance give a smooth ring:
         * the estimated chunk duration is scaled by how far into the text the
         * engine is.
         */
        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            val index = chunkIndexOf(utteranceId)
            val chunk = chunks.getOrNull(index) ?: return
            val duration = timeline?.durationForChunk(chunk) ?: return
            val fraction = if (chunk.text.isEmpty()) 0.0 else (start.toDouble() / chunk.text.length).coerceIn(0.0, 1.0)
            chunkOffsetMs = (duration * fraction).toLong()
            publish(status = TtsPlaybackStatus.PLAYING, positionMs = currentPosition(), chunkIndex = index)
        }

        override fun onDone(utteranceId: String?) {
            val index = chunkIndexOf(utteranceId)
            if (index < 0 || index != currentChunk) return
            chunkOffsetMs = 0L
            if (currentChunk >= chunks.lastIndex) {
                finish(TtsPlaybackStatus.ENDED, null)
                return
            }
            currentChunk += 1
            playCurrent(engine ?: return)
        }

        override fun onError(utteranceId: String?) {
            finish(TtsPlaybackStatus.ERROR, "tts_playback_failed")
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            finish(TtsPlaybackStatus.ERROR, "tts_playback_failed:$errorCode")
        }
    }

    private fun currentPosition(): Long {
        val timelineRef = timeline ?: return 0L
        val chunk = chunks.getOrNull(currentChunk) ?: return 0L
        return timelineRef.positionForChunkProgress(currentChunk, chunkOffsetMs, timelineRef.durationForChunk(chunk))
    }

    private fun publish(status: TtsPlaybackStatus, positionMs: Long, chunkIndex: Int) {
        _state.value = _state.value.copy(
            status = status,
            positionMs = positionMs,
            durationMs = timeline?.estimatedDurationMs ?: 0L,
            currentChunkIndex = chunkIndex,
            totalChunks = chunks.size,
        )
    }

    private fun finish(status: TtsPlaybackStatus, error: String?) {
        _state.value = _state.value.copy(
            status = status,
            positionMs = if (status == TtsPlaybackStatus.ENDED) _state.value.durationMs else _state.value.positionMs,
            durationMs = timeline?.estimatedDurationMs ?: 0L,
            totalChunks = chunks.size,
            errorMessage = error,
        )
        syncSpeaking()
    }

    private fun syncSpeaking() {
        speakingFlow.value = _state.value.isActive
    }

    /** tts_provider.dart `_seekStep`. */
    const val SEEK_STEP_MS: Long = 15_000L
}

/**
 * chat_message_widget.dart:554 `_buildTextToSpeechReplayRow` — up to [maxLines]
 * lines of text plus a replay button (RefreshCw 14dp in a 30dp hit area) that
 * reads it aloud with the system engine.
 */
@Composable
fun TextToSpeechReplayRow(
    text: String,
    textColor: Color,
    buttonColor: Color,
    fontSize: Int = 12,
    maxLines: Int = 2,
) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = fontSize.sp,
                lineHeight = (fontSize * 1.4).sp,
                color = textColor,
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(30.dp)
                .clickable { TtsPlayer.speak(context, text) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Lucide.RefreshCw,
                contentDescription = androidx.compose.ui.res.stringResource(
                    UiR.string.tts_floating_replay_tooltip,
                ),
                tint = buttonColor,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** chat_message_widget.dart `_textToSpeechToolText` — arguments['text']. */
fun textToSpeechToolText(args: kotlinx.serialization.json.JsonObject?): String =
    args?.get("text")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        .orEmpty().trim()

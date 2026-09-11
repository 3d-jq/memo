package com.psyche.memo.ui.chat

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * The slice of `android.speech.tts.TextToSpeech` the player needs.
 *
 * Exists so [TtsPlaybackController] — the part that decides what to speak, when
 * to advance and what the UI shows — can be driven by a fake engine in tests.
 * The three bugs that shipped (the pill refusing to move, the close button doing
 * nothing, the chat icon not reverting after a stop) all lived in that layer,
 * where the pure timeline/chunker tests could not see them.
 */
interface TtsEngine {

    /** Callbacks the controller listens to; ids come from [ttsUtteranceId]. */
    interface Listener {
        fun onStart(utteranceId: String)
        fun onRangeStart(utteranceId: String, start: Int, end: Int)
        fun onDone(utteranceId: String)
        fun onError(utteranceId: String, errorCode: Int)
        fun onStop(utteranceId: String, interrupted: Boolean)
    }

    var listener: Listener?

    /** Creates the engine; [onReady] reports whether it can speak. */
    fun prepare(onReady: (Boolean) -> Unit)

    /** Speaks [text] at [rate] (the engine's own 0.1–1.0 axis), replacing anything queued. */
    fun speak(text: String, utteranceId: String, rate: Float)

    fun stop()

    fun shutdown()
}

/** The real engine, backed by the platform's text-to-speech service. */
class SystemTtsEngine(private val context: Context) : TtsEngine {

    private var engine: TextToSpeech? = null

    override var listener: TtsEngine.Listener? = null
        set(value) {
            field = value
            engine?.setOnUtteranceProgressListener(value?.let { adapt(it) })
        }

    override fun prepare(onReady: (Boolean) -> Unit) {
        if (engine != null) {
            onReady(true)
            return
        }
        // android.jar's TextToSpeech has no setOnInitListener — the callback only
        // comes through the constructor.
        engine = TextToSpeech(context.applicationContext) { status ->
            listener?.let { engine?.setOnUtteranceProgressListener(adapt(it)) }
            onReady(status == TextToSpeech.SUCCESS)
        }
    }

    override fun speak(text: String, utteranceId: String, rate: Float) {
        val tts = engine ?: return
        tts.language = Locale.getDefault()
        tts.setSpeechRate(rate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    override fun stop() {
        engine?.stop()
    }

    override fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
    }

    /** `UtteranceProgressListener` is an abstract class, so bridge it here. */
    private fun adapt(target: TtsEngine.Listener) = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            if (utteranceId != null) target.onStart(utteranceId)
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            if (utteranceId != null) target.onRangeStart(utteranceId, start, end)
        }

        override fun onDone(utteranceId: String?) {
            if (utteranceId != null) target.onDone(utteranceId)
        }

        override fun onError(utteranceId: String?) {
            if (utteranceId != null) target.onError(utteranceId, -1)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            if (utteranceId != null) target.onError(utteranceId, errorCode)
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            if (utteranceId != null) target.onStop(utteranceId, interrupted)
        }
    }
}

/**
 * The playback logic behind [TtsPlayer]: which chunk is speaking, what the pill
 * shows, and how pause / seek / speed / stop change that.
 *
 * Positions are estimated from the played character range against
 * [TtsPlaybackTimeline]; the engine's rate axis is 0.1–1.0, so a displayed speed
 * maps through [TtsPlaybackSpeed.toSystemRate].
 */
class TtsPlaybackController(
    private val engine: TtsEngine,
    private val chunkMaxLength: Int = SYSTEM_CHUNK_MAX_LENGTH,
) : TtsEngine.Listener {

    private val _state = MutableStateFlow(TtsPlaybackState())
    val state: StateFlow<TtsPlaybackState> = _state

    /** The chat action row's Speak/Stop icon follows this (`isActive`). */
    private val speakingFlow = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> get() = speakingFlow

    private var chunks: List<TtsTextChunk> = emptyList()
    private var timeline: TtsPlaybackTimeline? = null
    private var currentChunk = 0
    private var chunkOffsetMs = 0L
    private var session = 0
    private var paused = false
    private var prepared = false
    private var pendingText: String? = null
    private var lastOwnerId: String? = null

    init {
        engine.listener = this
    }

    /** Starts (or restarts) a session with [text], owned by [ownerId] when given. */
    fun speak(text: String, ownerId: String? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val split = TtsTextChunker.split(trimmed, chunkMaxLength)
        if (split.isEmpty()) return
        session++
        chunks = split
        timeline = TtsPlaybackTimeline(split)
        currentChunk = 0
        chunkOffsetMs = 0L
        paused = false
        lastOwnerId = ownerId
        publish(status = TtsPlaybackStatus.BUFFERING, positionMs = 0L, chunkIndex = 0)

        if (prepared) {
            playCurrent()
            return
        }
        pendingText = trimmed
        engine.prepare { ready ->
            prepared = ready
            val queued = pendingText
            pendingText = null
            if (ready && queued != null) {
                playCurrent()
            } else if (!ready) {
                finish(TtsPlaybackStatus.ERROR, "tts_unavailable")
            }
        }
    }

    /**
     * Pause stops the engine; resume restarts at the current chunk — an engine
     * that cannot pause an utterance is restarted instead. A finished session is
     * replayed (upstream `togglePause` delegates to `replay()` when ended).
     */
    fun togglePause() {
        if (_state.value.status == TtsPlaybackStatus.ENDED) {
            replay()
            return
        }
        if (!_state.value.isActive) return
        if (paused) {
            paused = false
            playCurrent()
        } else {
            paused = true
            runCatching { engine.stop() }
            publish(status = TtsPlaybackStatus.PAUSED, positionMs = currentPosition(), chunkIndex = currentChunk)
        }
    }

    /**
     * The close button: ends the session and resets to idle, which also hides the
     * pill (`isPlayerVisible`). A session that finished on its own instead moves
     * to `ended` and stays on screen with a replay button.
     */
    fun stop() {
        session++
        runCatching { engine.stop() }
        pendingText = null
        chunks = emptyList()
        timeline = null
        currentChunk = 0
        chunkOffsetMs = 0L
        paused = false
        lastOwnerId = null
        setState(TtsPlaybackState(speed = _state.value.speed))
    }

    /** Restarts the session from its first chunk. */
    fun replay() {
        if (chunks.isEmpty()) return
        currentChunk = 0
        chunkOffsetMs = 0L
        paused = false
        publish(status = TtsPlaybackStatus.PLAYING, positionMs = 0L, chunkIndex = 0)
        playCurrent()
    }

    fun seekBackward() = seekRelative(-SEEK_STEP_MS)

    fun seekForward() = seekRelative(SEEK_STEP_MS)

    fun seekRelative(deltaMs: Long) {
        val timelineRef = timeline ?: return
        if (chunks.isEmpty() || !_state.value.isActive) return
        val target = timelineRef.seekTarget(currentPosition(), deltaMs)
        currentChunk = target.chunkIndex
        chunkOffsetMs = target.offsetInChunkMs
        paused = false
        publish(status = TtsPlaybackStatus.PLAYING, positionMs = target.positionMs, chunkIndex = currentChunk)
        playCurrent()
    }

    fun cyclePlaybackSpeed() {
        setPlaybackSpeed(TtsPlaybackSpeed.next(_state.value.speed))
    }

    fun setPlaybackSpeed(speed: Double) {
        val normalized = TtsPlaybackSpeed.normalize(speed)
        _state.value = _state.value.copy(speed = normalized)
        // The rate only applies to utterances started after it was set.
        if (_state.value.isActive && !paused) playCurrent()
    }

    /** Releases the engine. */
    fun shutdown() {
        runCatching { engine.shutdown() }
        prepared = false
        pendingText = null
        chunks = emptyList()
        timeline = null
        lastOwnerId = null
        session++
        setState(TtsPlaybackState())
    }

    // ── engine callbacks ──────────────────────────────────────────────────────

    override fun onStart(utteranceId: String) {
        val index = callbackTarget(utteranceId) ?: return
        currentChunk = index
        publish(status = TtsPlaybackStatus.PLAYING, positionMs = currentPosition(), chunkIndex = currentChunk)
    }

    override fun onRangeStart(utteranceId: String, start: Int, end: Int) {
        val index = callbackTarget(utteranceId) ?: return
        if (index != currentChunk) return
        val chunk = chunks.getOrNull(index) ?: return
        val duration = timeline?.durationForChunk(chunk) ?: return
        val fraction = if (chunk.text.isEmpty()) 0.0 else (start.toDouble() / chunk.text.length).coerceIn(0.0, 1.0)
        chunkOffsetMs = (duration * fraction).toLong()
        publish(status = TtsPlaybackStatus.PLAYING, positionMs = currentPosition(), chunkIndex = index)
    }

    override fun onDone(utteranceId: String) {
        val index = callbackTarget(utteranceId) ?: return
        if (index != currentChunk) return
        chunkOffsetMs = 0L
        if (currentChunk >= chunks.lastIndex) {
            finish(TtsPlaybackStatus.ENDED, null)
            return
        }
        currentChunk += 1
        playCurrent()
    }

    override fun onError(utteranceId: String, errorCode: Int) {
        if (callbackTarget(utteranceId) == null) return
        finish(TtsPlaybackStatus.ERROR, "tts_playback_failed:$errorCode")
    }

    override fun onStop(utteranceId: String, interrupted: Boolean) = Unit

    // ── internals ─────────────────────────────────────────────────────────────

    private fun playCurrent() {
        val chunk = chunks.getOrNull(currentChunk) ?: run {
            finish(TtsPlaybackStatus.ENDED, null)
            return
        }
        runCatching {
            // A seek into the middle of a chunk restarts that chunk: speaking from
            // its beginning is the granularity the engine offers.
            engine.speak(chunk.text, ttsUtteranceId(session, currentChunk), TtsPlaybackSpeed.toSystemRate(_state.value.speed).toFloat())
        }.onFailure {
            finish(TtsPlaybackStatus.ERROR, it.message)
            return
        }
        publish(status = TtsPlaybackStatus.PLAYING, positionMs = currentPosition(), chunkIndex = currentChunk)
    }

    /**
     * A callback belongs to this session's chunk only when both parts of its id
     * match. The engine can deliver an `onStart` for an utterance that [stop]
     * already cancelled; without this check that late callback pushed the state
     * back to "playing" and the chat action row kept showing its stop icon.
     */
    private fun callbackTarget(utteranceId: String): Int? {
        val (owner, index) = parseTtsUtteranceId(utteranceId) ?: return null
        if (owner != session) return null
        return index
    }

    private fun currentPosition(): Long {
        val timelineRef = timeline ?: return 0L
        val chunk = chunks.getOrNull(currentChunk) ?: return 0L
        return timelineRef.positionForChunkProgress(currentChunk, chunkOffsetMs, timelineRef.durationForChunk(chunk))
    }

    /** The single writer of [state]; keeps the derived speaking flag in step. */
    private fun setState(next: TtsPlaybackState) {
        _state.value = next
        speakingFlow.value = next.isActive
    }

    private fun publish(status: TtsPlaybackStatus, positionMs: Long, chunkIndex: Int) {
        setState(
            _state.value.copy(
                status = status,
                positionMs = positionMs,
                durationMs = timeline?.estimatedDurationMs ?: 0L,
                currentChunkIndex = chunkIndex,
                totalChunks = chunks.size,
                ownerId = lastOwnerId,
            ),
        )
    }

    private fun finish(status: TtsPlaybackStatus, error: String?) {
        setState(
            _state.value.copy(
                status = status,
                ownerId = lastOwnerId,
                positionMs = if (status == TtsPlaybackStatus.ENDED) _state.value.durationMs else _state.value.positionMs,
                durationMs = timeline?.estimatedDurationMs ?: 0L,
                totalChunks = chunks.size,
                errorMessage = error,
            ),
        )
    }

    companion object {
        /** tts_provider.dart `_systemChunkMaxLength`. */
        const val SYSTEM_CHUNK_MAX_LENGTH = 360

        /** tts_provider.dart `_seekStep`. */
        const val SEEK_STEP_MS: Long = 15_000L
    }
}

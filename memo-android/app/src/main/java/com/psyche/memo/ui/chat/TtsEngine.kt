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

    /**
     * 当前引擎产出的音频是不是**网络合成**的。悬浮播放器据此显示「保存音频」
     * （原版 `tts.canSaveNetworkAudio`：系统 TTS 拿不到音频字节，所以没有保存钮）。
     */
    val isNetwork: Boolean get() = false

    /** Creates the engine; [onReady] reports whether it can speak. */
    fun prepare(onReady: (Boolean) -> Unit)

    /** Speaks [text] at [rate] (the engine's own 0.1–1.0 axis), replacing anything queued. */
    fun speak(
        text: String,
        utteranceId: String,
        rate: Float,
        allowCachedAudio: Boolean = true,
    )

    fun stop()

    fun shutdown()

    /**
     * 把偏好里改过的语速/音调/引擎/语言重新下发（原版 `_applyConfig`）。
     * 返回 **true** 表示实例已被换掉（换语音引擎只能重建），调用方要重新 [prepare]。
     */
    fun syncConfig(): Boolean = false

    /** 当前系统语音配置（网络引擎没有 → null）。 */
    fun systemConfig(): SystemTtsConfig? = null

    /** 原版 `listEngines()`：可枚举的语音引擎包名。 */
    fun listEngines(): List<String> = emptyList()

    /** 原版 `listLanguages()`：当前引擎支持的语音语言标签。 */
    fun listLanguages(): List<String> = emptyList()
}

/**
 * 系统语音的运行时配置 —— 上游 `TtsProvider` 的 `_speechRate` / `_pitch` /
 * `_engineId` / `_languageTag` 四个字段。
 *
 * [speechRate] 是上游那根 flutter_tts 轴（**0.5 才是正常语速**），显示倍速要 ×2。
 */
data class SystemTtsConfig(
    val speechRate: Double = DEFAULT_SPEECH_RATE,
    val pitch: Double = DEFAULT_PITCH,
    val engineId: String? = null,
    val languageTag: String? = null,
) {
    /** 悬浮播放器显示的倍速（原版 `_init` L132-134、`setSpeechRate` L339-343）。 */
    val displayedSpeed: Double get() = TtsPlaybackSpeed.normalize(speechRate * 2)

    companion object {
        const val RATE_KEY = "tts_speech_rate_v1"
        const val PITCH_KEY = "tts_pitch_v1"
        const val ENGINE_KEY = "tts_engine_v1"
        const val LANGUAGE_KEY = "tts_language_v1"
        const val CACHE_REPLAY_KEY = "tts_cache_network_audio_for_replay_v1"

        /** 「朗读取哪部分文本」：存 `TtsTextSelectionMode.name`（settings_provider.dart:401）。 */
        const val TEXT_SELECTION_KEY = "tts_text_selection_mode_v1"

        /** `tts_provider.dart:79` 「flutter_tts platform value, 0.5 is normal」。 */
        const val DEFAULT_SPEECH_RATE = 0.5
        const val DEFAULT_PITCH = 1.0
    }
}

/** 偏好 JSON → 配置：区间与默认值逐字照 `tts_provider.dart` `_init` L122-131。 */
fun parseSystemTtsConfig(
    rateJson: String?,
    pitchJson: String?,
    engineJson: String?,
    languageJson: String?,
): SystemTtsConfig = SystemTtsConfig(
    speechRate = (rateJson?.let { unquoteJson(it)?.toDoubleOrNull() } ?: SystemTtsConfig.DEFAULT_SPEECH_RATE)
        .coerceIn(0.1, 1.0),
    pitch = (pitchJson?.let { unquoteJson(it)?.toDoubleOrNull() } ?: SystemTtsConfig.DEFAULT_PITCH)
        .coerceIn(0.5, 2.0),
    engineId = engineJson?.let { unquoteJson(it) },
    languageTag = languageJson?.let { unquoteJson(it) },
)

/** JSON 载荷去引号（备份恢复可能写成 `"zh-CN"`，数字则是裸 `0.5`）；空串算没设。 */
private fun unquoteJson(raw: String): String? =
    raw.trim().removeSurrounding("\"").takeIf { it.isNotEmpty() }

/** `_localeToTag` L976-981：`语言-国家`，没有国家就单语言码。 */
fun localeToTag(language: String, country: String?): String =
    if (country.isNullOrEmpty()) language else "$language-$country"

/**
 * 这次该说哪种语言（`_applyConfig` L251-264）：偏好里的标签优先，其次设备语言；
 * 两者引擎都不支持时回落 `zh-CN`/`en-US`，还不支持就返回 null（上游此时什么都不设）。
 */
fun resolveSystemLanguage(
    config: SystemTtsConfig,
    deviceTag: String,
    deviceLanguage: String,
    available: (String) -> Boolean,
): String? {
    val tag = config.languageTag ?: deviceTag
    if (available(tag)) return tag
    val fallback = if (deviceLanguage.lowercase().startsWith("zh")) "zh-CN" else "en-US"
    return fallback.takeIf { available(it) }
}

/**
 * 用哪个语音引擎（`_applyConfig` L246-250 + `_selectEngine` L315-333）：用户点名的优先；
 * 没点名时**优先名字含 google 的引擎，否则第一个** —— 不是「留着系统默认」。
 */
fun preferredSystemEngine(engines: List<String>, selected: String?): String? =
    selected?.takeIf { it.isNotEmpty() }
        ?: engines.firstOrNull { it.contains("google", ignoreCase = true) }
        ?: engines.firstOrNull()

/** The real engine, backed by the platform's text-to-speech service. */
class SystemTtsEngine(
    private val context: Context,
    private val configProvider: () -> SystemTtsConfig = { SystemTtsConfig() },
) : TtsEngine {

    private var engine: TextToSpeech? = null

    /**
     * 构造 TextToSpeech 是异步的（就绪只从构造函数的回调来），而 [prepare] 可能被
     * 好几路同时要到（启动预热、第一次朗读、换引擎后的重建），所以这里把等待者排队，
     * 就绪那一次一起结算 —— 否则后来的人会看到「已就绪」而对着一个还没绑定的实例说话。
     */
    private val lock = Any()
    private val waiting = mutableListOf<(Boolean) -> Unit>()
    @Volatile private var ready = false
    @Volatile private var binding = false

    /** 这个实例是**带着哪个引擎名**构造出来的（null = 让系统挑）。 */
    @Volatile private var boundEngine: String? = null

    override var listener: TtsEngine.Listener? = null
        set(value) {
            field = value
            engine?.setOnUtteranceProgressListener(value?.let { adapt(it) })
        }

    override fun prepare(onReady: (Boolean) -> Unit) {
        synchronized(lock) {
            if (ready) {
                onReady(true)
                return
            }
            waiting.add(onReady)
            if (binding) return
            binding = true
        }
        bind(configProvider().engineId)
    }

    /**
     * android.jar 的 TextToSpeech 没有「换引擎」的方法 —— 只能带引擎名重新构造
     * （flutter_tts 的 `setEngine` 在原生侧就是这么做的）。没点名时也要**钉到**
     * `_selectEngine` 算出的那一个（优先 google 否则第一个），而不是留着系统默认，
     * 所以流程是「先建默认实例 → 枚举引擎 → 带着选中的名字重建」。
     */
    private fun bind(engineName: String?) {
        val wanted = engineName?.ifEmpty { null }
        val app = context.applicationContext
        val initListener = TextToSpeech.OnInitListener { status -> onBound(status, wanted) }
        boundEngine = wanted
        engine = if (wanted == null) TextToSpeech(app, initListener)
        else TextToSpeech(app, initListener, wanted)
    }

    private fun onBound(status: Int, wanted: String?) {
        val tts = engine
        if (tts == null || status != TextToSpeech.SUCCESS) {
            synchronized(lock) { ready = false }
            settle(false)
            return
        }
        listener?.let { tts.setOnUtteranceProgressListener(adapt(it)) }
        if (wanted == null) {
            val preferred = preferredSystemEngine(engineNamesOf(tts), null)
            if (preferred != null && preferred != boundEngine) {
                runCatching { tts.shutdown() }
                engine = null
                bind(preferred)
                return
            }
        }
        applyVoice(tts, configProvider())
        synchronized(lock) { ready = true }
        settle(true)
    }

    private fun settle(value: Boolean) {
        val callbacks: List<(Boolean) -> Unit>
        synchronized(lock) {
            binding = false
            callbacks = waiting.toList()
            waiting.clear()
        }
        callbacks.forEach { it(value) }
    }

    override fun speak(
        text: String,
        utteranceId: String,
        rate: Float,
        allowCachedAudio: Boolean,
    ) {
        val tts = engine ?: return
        // rate 是内部轴（显示倍速 / 2）—— Android 的 1.0 才是正常语速，必须还原，
        // 否则一律半速播放（见 TtsPlaybackSpeed.toAndroidSpeechRate）。
        // 语言/音调是引擎级设置，在 bind/applyVoice 时下发，不能在这里按设备语言覆盖
        // （原版 `_applyConfig` 只在配置变化时设语言，`_trySpeak` 每块只重设语速）。
        tts.setSpeechRate(TtsPlaybackSpeed.toAndroidSpeechRate(rate.toDouble()))
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /** 原版 `_applyConfig` L237-265：音调 + 语言（带 zh-CN/en-US 回落）下发到引擎实例。 */
    override fun syncConfig(): Boolean {
        val tts = engine ?: return false
        val config = configProvider()
        val preferred = preferredSystemEngine(engineNamesOf(tts), config.engineId)
        if (preferred != null && preferred != boundEngine) {
            runCatching { tts.shutdown() }
            engine = null
            boundEngine = null
            synchronized(lock) { ready = false; binding = false }
            return true
        }
        applyVoice(tts, config)
        return false
    }

    override fun systemConfig(): SystemTtsConfig = configProvider()

    override fun listEngines(): List<String> = engine?.let { engineNamesOf(it) } ?: emptyList()

    override fun listLanguages(): List<String> =
        engine?.let { tts ->
            runCatching { tts.availableLanguages?.map { it.toLanguageTag() } ?: emptyList() }
                .getOrDefault(emptyList())
        } ?: emptyList()

    private fun applyVoice(tts: TextToSpeech, config: SystemTtsConfig) {
        runCatching { tts.setPitch(config.pitch.toFloat()) }
        val locale = Locale.getDefault()
        val tag = resolveSystemLanguage(
            config = config,
            deviceTag = localeToTag(locale.language, locale.country),
            deviceLanguage = locale.language,
        ) { candidate ->
            runCatching { tts.isLanguageAvailable(Locale.forLanguageTag(candidate)) >= 0 }
                .getOrDefault(false)
        }
        if (tag != null) runCatching { tts.language = Locale.forLanguageTag(tag) }
    }

    private fun engineNamesOf(tts: TextToSpeech): List<String> =
        runCatching { tts.engines?.map { it.name } ?: emptyList() }.getOrDefault(emptyList())

    override fun stop() {
        engine?.stop()
    }

    override fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        boundEngine = null
        synchronized(lock) { ready = false }
        settle(false)
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

        @Deprecated("Use onError(utteranceId, errorCode).", level = DeprecationLevel.WARNING)
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

    /**
     * 显示倍速的起点：偏好里的 `tts_speech_rate_v1`（×2 后夹进档位区间），原版
     * `_init` L132-134。没有系统配置可读（网络引擎、单测）时按 1.0×。
     */
    private val seededSpeed = engine.systemConfig()?.displayedSpeed ?: 1.0

    private val _state = MutableStateFlow(TtsPlaybackState(speed = seededSpeed))
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
    private var pendingSession = -1
    private var lastOwnerId: String? = null
    private var allowCachedAudio = true

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
        allowCachedAudio = true
        publish(status = TtsPlaybackStatus.BUFFERING, positionMs = 0L, chunkIndex = 0)
        playCurrent()
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
        pendingSession = -1
        chunks = emptyList()
        timeline = null
        currentChunk = 0
        chunkOffsetMs = 0L
        paused = false
        lastOwnerId = null
        setState(TtsPlaybackState(speed = _state.value.speed))
    }

    /**
     * Restarts the session from its first chunk. [allowCachedAudio] is the
     * 「使用缓存复播」开关（原版 `replay` 的 `reuseResolvedNetworkAudio`）：
     * 关掉时整段重新请求语音服务，而不是复用已合成的音频。
     */
    fun replay(allowCachedAudio: Boolean = true) {
        if (chunks.isEmpty()) return
        currentChunk = 0
        chunkOffsetMs = 0L
        paused = false
        this.allowCachedAudio = allowCachedAudio
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

    /**
     * 偏好里的语速/音调/引擎/语言变了，重新下发给引擎（原版 `_applyConfig`）。
     *
     * 换引擎在 Android 上只能重建实例，所以 [prepared] 要清掉等下一次 prepare；
     * 正在播的会话立刻重说当前块，否则会停在一个被 shutdown 杀掉的语音上不出声。
     * 显示倍速只在**没在播**时跟着语速偏好走（原版 `setSpeechRate` L339 的判定）。
     */
    fun reloadSystemConfig() {
        val config = engine.systemConfig()
        val rebuilt = engine.syncConfig()
        if (rebuilt) {
            prepared = false
            if (_state.value.isActive && !paused) playCurrent()
        }
        if (config != null && !_state.value.isActive) {
            _state.value = _state.value.copy(speed = config.displayedSpeed)
        }
    }

    /** 原版 `listEngines()`：可枚举的语音引擎。 */
    fun systemEngines(): List<String> = engine.listEngines()

    /** 原版 `listLanguages()`：当前引擎支持的语言标签。 */
    fun systemLanguages(): List<String> = engine.listLanguages()

    /** 原版 `_kickEngine`：先把引擎构造出来（设置页枚举引擎/语言要用）。 */
    fun prepareEngine() {
        engine.prepare { prepared = it }
    }

    /** Releases the engine. */
    fun shutdown() {
        runCatching { engine.shutdown() }
        prepared = false
        pendingSession = -1
        chunks = emptyList()
        timeline = null
        lastOwnerId = null
        session++
        setState(TtsPlaybackState(speed = seededSpeed))
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
        if (!prepared) {
            // 引擎还没就绪（首次朗读、或换语音引擎后重建）：就绪那一次再说**当时**的
            // 那一块（其间可能已经 seek/stop，会话号不一致就整个丢掉）。
            val target = session
            pendingSession = target
            engine.prepare { ready ->
                prepared = ready
                if (pendingSession != target) return@prepare
                pendingSession = -1
                if (ready) playCurrent() else finish(TtsPlaybackStatus.ERROR, "tts_unavailable")
            }
            return
        }
        val chunk = chunks.getOrNull(currentChunk) ?: run {
            finish(TtsPlaybackStatus.ENDED, null)
            return
        }
        runCatching {
            // A seek into the middle of a chunk restarts that chunk: speaking from
            // its beginning is the granularity the engine offers.
            engine.speak(
                chunk.text,
                ttsUtteranceId(session, currentChunk),
                TtsPlaybackSpeed.toSystemRate(_state.value.speed).toFloat(),
                allowCachedAudio,
            )
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
                usingNetwork = engine.isNetwork,
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
                usingNetwork = engine.isNetwork,
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

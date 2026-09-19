package com.psyche.memo.ui.chat

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.psyche.memo.provider.NetworkTts
import com.psyche.memo.provider.NetworkTtsResult
import com.psyche.memo.ui.TtsServiceOptions
import com.psyche.memo.ui.TtsServicesStore
import java.io.File
import java.security.MessageDigest
import okhttp3.OkHttpClient

/**
 * 网络 TTS 音频缓存（`tts_provider.dart` 的合成缓存）：
 * `filesDir/tts_cache/<sha1(kind + options + text)>.<ext>`，命中就复用同一段音频，
 * 避免同一条消息反复花钱合成；超过 [MAX_FILES] 个按最后访问时间清理。
 */
class TtsAudioCache(private val context: Context, private val maxFiles: Int = MAX_FILES) {

    fun fileFor(
        options: TtsServiceOptions,
        text: String,
        readCache: Boolean = true,
        synth: () -> NetworkTtsResult,
    ): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val key = keyFor(options, text)
        val hit = if (readCache) dir.listFiles()?.firstOrNull { it.name.startsWith("$key.") } else null
        if (hit != null && hit.length() > 0) {
            hit.setLastModified(System.currentTimeMillis())
            return hit
        }
        val result = synth()
        val file = File(dir, "$key.${result.extension}")
        file.writeBytes(result.bytes)
        prune(dir, keep = file)
        return file
    }

    private fun prune(dir: File, keep: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        if (files.size <= maxFiles) return
        files.drop(maxFiles).forEach { if (it != keep) runCatching { it.delete() } }
    }

    internal fun keyFor(options: TtsServiceOptions, text: String): String =
        sha1(options.kind.wire + options.toJson().toString() + "\u0000" + text)

    private fun sha1(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DIR = "tts_cache"
        const val MAX_FILES = 40
    }
}

/**
 * [TtsEngine] 的网络实现：把「朗读一块文本」映射成「合成到缓存文件 → MediaPlayer
 * 播放」，并回调与系统引擎同一组 Listener（`onStart` 在开播前、`onRangeStart` 按
 * 播放进度**估算**字符位置、`onDone` 在播完时）。
 *
 * 位置估算：网络音频没有逐字回调，这里用 `MediaPlayer.currentPosition()/duration`
 * 比例乘上本块文本长度，语义与系统引擎的 `onRangeStart`（字符区间）一致，
 * [TtsPlaybackController] 的进度/时间轴逻辑因此完全不用改。
 *
 * 线程：合成与 `prepareAsync` 都在后台线程，回调统一 post 回主线程（Listener 的
 * 接收方是 Compose 状态机）。
 */
class NetworkTtsEngine(
    context: Context,
    private val client: OkHttpClient,
    private val optionsProvider: () -> TtsServiceOptions?,
    private val cache: TtsAudioCache = TtsAudioCache(context),
) : TtsEngine {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var ticker: Runnable? = null
    private var worker: Thread? = null

    @Volatile private var stopped = true
    @Volatile private var currentText: String = ""

    override var listener: TtsEngine.Listener? = null

    /** 网络音频可以保存（悬浮播放器的保存钮据此显示）。 */
    override val isNetwork: Boolean get() = true

    override fun prepare(onReady: (Boolean) -> Unit) {
        onReady(optionsProvider() != null)
    }

    override fun speak(
        text: String,
        utteranceId: String,
        rate: Float,
        allowCachedAudio: Boolean,
    ) {
        stopInternal()
        stopped = false
        currentText = text
        val thread = Thread {
            val options = optionsProvider()
            if (options == null) {
                post { fail(utteranceId, 1) }
                return@Thread
            }
            // 显示倍速 → 引擎 rate 是 speed/2，这里还原（见 TtsPlaybackSpeed.toSystemRate）。
            val speed = mediaPlayerSpeed(rate)
            val file = runCatching {
                // 「使用缓存复播」关掉时重播要真的重新请求服务（原版 replay 清 `_resolvedNetworkChunks`）。
                cache.fileFor(options, text, readCache = allowCachedAudio) {
                    NetworkTts.synthesize(client, options, text)
                }
            }.getOrElse {
                post { fail(utteranceId, 2) }
                return@Thread
            }
            if (stopped) return@Thread
            post { startPlayback(file, utteranceId, speed) }
        }
        worker = thread
        thread.start()
    }

    private fun startPlayback(file: File, utteranceId: String, speed: Float) {
        if (stopped) return
        runCatching {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { prepared ->
                if (stopped) {
                    prepared.release()
                    return@setOnPreparedListener
                }
                runCatching {
                    prepared.playbackParams = prepared.playbackParams.setSpeed(speed)
                }
                listener?.onStart(utteranceId)
                prepared.start()
                startTicker(utteranceId, prepared)
            }
            mp.setOnCompletionListener { completed ->
                stopTicker()
                if (!stopped) listener?.onDone(utteranceId)
            }
            mp.setOnErrorListener { _, _, _ ->
                stopTicker()
                if (!stopped) fail(utteranceId, 3)
                true
            }
            player?.release()
            player = mp
            mp.prepareAsync()
        }.onFailure {
            fail(utteranceId, 4)
        }
    }

    /** 每 200ms 把播放比例换算成字符区间回调（网络音频没有逐字回调）。 */
    private fun startTicker(utteranceId: String, mp: MediaPlayer) {
        stopTicker()
        val runnable = object : Runnable {
            override fun run() {
                if (stopped) return
                val duration = runCatching { mp.duration }.getOrDefault(-1)
                val position = runCatching { mp.currentPosition }.getOrDefault(0)
                if (duration > 0 && currentText.isNotEmpty()) {
                    val end = ((position.toDouble() / duration) * currentText.length)
                        .toInt().coerceIn(0, currentText.length)
                    listener?.onRangeStart(utteranceId, end, currentText.length)
                }
                main.postDelayed(this, TICK_MS)
            }
        }
        ticker = runnable
        main.postDelayed(runnable, TICK_MS)
    }

    private fun stopTicker() {
        ticker?.let { main.removeCallbacks(it) }
        ticker = null
    }

    override fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        stopped = true
        stopTicker()
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        worker = null
    }

    override fun shutdown() {
        stopInternal()
        listener = null
    }

    private fun fail(utteranceId: String, code: Int) {
        stopInternal()
        listener?.onError(utteranceId, code)
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** 这段文本的合成结果（保存音频/导出用），走同一份缓存。 */
    fun synthesizeAll(text: String): NetworkTtsResult? {
        val options = optionsProvider() ?: return null
        return runCatching { NetworkTts.synthesize(client, options, text) }.getOrNull()
    }

    /** 整段合成（原版 `synthesizeAllAndCollect`）：网络语音未选中时返回 null。 */
    fun collectNetworkAudio(text: String): NetworkTtsResult? = synthesizeAll(text)

    companion object {
        /** MediaPlayer 的倍速区间。 */
        const val MIN_PLAYBACK_SPEED = 0.5f
        const val MAX_PLAYBACK_SPEED = 3.0f
        private const val TICK_MS = 200L
    }
}

/**
 * 引擎 rate（= 显示倍速 / 2）→ MediaPlayer 的倍速。
 * 显示档位 0.8/1.0/1.2/1.5/2.0 对应 rate 0.4/0.5/0.6/0.75/1.0，还原后与显示一致。
 */
internal fun mediaPlayerSpeed(rate: Float): Float =
    (rate * 2f).coerceIn(NetworkTtsEngine.MIN_PLAYBACK_SPEED, NetworkTtsEngine.MAX_PLAYBACK_SPEED)

/** 该服务是否走网络引擎：没选 / 已停用 / 未接的 provider（qwenAudio）都退回系统引擎。 */
internal fun shouldUseNetworkEngine(options: TtsServiceOptions?): Boolean =
    options != null && NetworkTts.isSupported(options.kind)

/**
 * 按当前设置把「说一段」分派给系统引擎或网络引擎。
 *
 * 存在的理由：`TtsPlayer` 的文档要求控制器与 flow 身份稳定（不能按设置重建控制器），
 * 所以引擎做成可切换的代理 —— 每次 [speak] 现读设置选一个委托，并把 Listener 同时
 * 挂到两个委托上（[TtsPlaybackController] 只认识一个引擎实例）。
 */
/**
 * 单次朗读会话的引擎覆盖 —— 对应原版 `tts_provider.dart` 的两个入口：
 * `speakSystem(text)`（→ `_speakQueued(text, flush)`，**networkService = null**，即强制系统
 * 引擎）与 `speakWithNetworkService(service, text)`（用**指定**服务，不看当前选中项）。
 *
 * 用在「语音服务」页的「听测试」：让测试播放走同一条播放管线，从而和对话里一样出现
 * 悬浮播放胶囊（用户 2026-09-16「语音点击听测试那个没有我们那个胶囊呀」）。
 */
sealed interface TtsSessionOverride {
    /** 本次强制用系统 TTS（原版 `speakSystem`）。 */
    data object System : TtsSessionOverride

    /** 本次用这个网络服务（原版 `speakWithNetworkService`）。 */
    data class Service(val options: TtsServiceOptions) : TtsSessionOverride
}

/**
 * 本次会话该用哪个网络服务（纯函数，好单测）：
 * 覆盖优先 —— [TtsSessionOverride.System] 强制回落系统引擎、[TtsSessionOverride.Service]
 * 直接用指定服务；没有覆盖才按 store 里选中的那一项（停用/找不到都回落系统）。
 */
internal fun resolveSessionService(
    override: TtsSessionOverride?,
    selectedServiceId: String?,
    services: List<TtsServiceOptions>,
): TtsServiceOptions? = when (override) {
    is TtsSessionOverride.System -> null
    is TtsSessionOverride.Service -> override.options
    null -> {
        val id = selectedServiceId ?: return null
        val service = services.firstOrNull { it.id == id } ?: return null
        service.takeIf { it.enabled }
    }
}

class SwitchableTtsEngine(
    context: Context,
    client: OkHttpClient,
    private val store: TtsServicesStore,
    configProvider: () -> SystemTtsConfig = { SystemTtsConfig() },
) : TtsEngine {

    private val system = SystemTtsEngine(context.applicationContext, configProvider)
    private val network = NetworkTtsEngine(
        context,
        client,
        optionsProvider = { selectedNetworkOptions() },
    )

    @Volatile private var active: TtsEngine = system

    override var listener: TtsEngine.Listener?
        get() = active.listener
        set(value) {
            system.listener = value
            network.listener = value
        }

    override val isNetwork: Boolean get() = active.isNetwork

    /** 本次会话的引擎覆盖（on-speak 设置，普通朗读会清掉它）。 */
    @Volatile private var sessionOverride: TtsSessionOverride? = null

    /** 原版两个入口用它把「这次用哪个引擎」钉住；null＝按当前选中项。 */
    fun setSessionOverride(value: TtsSessionOverride?) {
        sessionOverride = value
    }

    /** 选中的网络服务；没选 / 已停用 / 服务不存在 → null（走系统 TTS）。 */
    fun selectedNetworkOptions(): TtsServiceOptions? =
        resolveSessionService(sessionOverride, store.selectedServiceId, store.services)

    override fun prepare(onReady: (Boolean) -> Unit) {
        // 网络服务就绪时不必等系统引擎；否则按系统引擎的可用性。
        val options = selectedNetworkOptions()
        if (options != null) {
            active = network
            network.prepare(onReady)
            return
        }
        active = system
        system.prepare(onReady)
    }

    override fun speak(
        text: String,
        utteranceId: String,
        rate: Float,
        allowCachedAudio: Boolean,
    ) {
        val options = selectedNetworkOptions()
        if (shouldUseNetworkEngine(options)) {
            active = network
            network.speak(text, utteranceId, rate, allowCachedAudio)
        } else {
            // 未接的 provider（qwenAudio）退回系统引擎，而不是静默不出声。
            active = system
            system.speak(text, utteranceId, rate, allowCachedAudio)
        }
    }

    override fun syncConfig(): Boolean = system.syncConfig()

    override fun systemConfig(): SystemTtsConfig? = system.systemConfig()

    override fun listEngines(): List<String> = system.listEngines()

    override fun listLanguages(): List<String> = system.listLanguages()

    override fun stop() {
        system.stop()
        network.stop()
    }

    /** 整段合成结果（保存音频）；非网络服务 → null。 */
    fun collectNetworkAudio(text: String): NetworkTtsResult? {
        if (selectedNetworkOptions() == null) return null
        return network.collectNetworkAudio(text)
    }

    override fun shutdown() {
        system.shutdown()
        network.shutdown()
    }
}

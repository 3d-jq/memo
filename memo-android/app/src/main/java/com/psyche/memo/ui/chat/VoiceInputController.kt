package com.psyche.memo.ui.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 语音输入控制器 —— chat_input_bar.dart _startVoiceInput 的 Android 执行器。
 *
 * 两条路（原版 `asr_selected_service_id_v1` 的分派，cloud_asr_service / system_asr_service）：
 * - **[cloudOptions] 返回一个已配置的云端服务**（MiMo / Step 等 HTTP provider）：
 *   用 [AsrRecorder] 采 PCM16 喂给 [CloudAsrService] 的会话，partial 实时回填输入框，
 *   停止时 `finish()` 拿最终转写；
 * - **否则走系统 [SpeechRecognizer]**（原行为，不变）。
 *
 * 状态机（CIB:852-932 录音行）：
 * - [State.Listening]：识别中，partial 文本实时进输入框，电平驱动波形；
 * - [State.Transcribing]：识别结束（停止按钮触发），等待最终结果；
 * - 停止（sendAfter=false）→ 最终文本留在输入框；
 * - 发送（sendAfter=true）→ 最终文本回调给发送链路；
 * - 取消 → 丢弃全部结果。
 *
 * 云端会话的 `addPcm16` 会同步发 HTTP，所以采集块先进队列、由单独的工作线程消费 ——
 * 直接在采集线程里发请求会把麦克风读空。
 */
class VoiceInputController(
    private val context: Context,
    /** 当前选中的云端 ASR 服务；null / 未配置 = 用系统识别。 */
    private val cloudOptions: () -> com.psyche.memo.ui.AsrServiceOptions? = { null },
    /** 本机系统语音识别是否可用（测试注入用；真机上查 `SpeechRecognizer`）。 */
    private val systemRecognizerAvailable: () -> Boolean = {
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
    },
    private val httpClient: okhttp3.OkHttpClient? = null,
) {

    sealed interface State {
        data object Idle : State

        /** 识别中：[partial] 为实时转写（空字符串表示还没有结果）。 */
        data class Listening(val partial: String) : State

        /** 已停止、等待最终结果的收尾（chatInputBarVoiceTranscribing）。 */
        data object Transcribing : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** 波形样本（CIB _voiceLevels，最新在末尾；1..0 区间）。 */
    private val _levels = MutableStateFlow(List(WAVE_BAR_COUNT) { 0f })
    val levels: StateFlow<List<Float>> = _levels

    private var recognizer: SpeechRecognizer? = null
    private var finalText: String = ""

    // ---- 云端路径的状态 ----
    private var recorder: AsrRecorder? = null
    private var cloudSession: com.psyche.memo.provider.CloudAsrSession? = null
    private var cloudWorker: Thread? = null
    private val cloudQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    @Volatile private var cloudTranscript = ""
    private var mainHandler: android.os.Handler? = null

    /**
     * **每代会话独立的取消标志**（原全局 `cloudRunning` 有跨代竞态：停止后 UI 立即回
     * Idle，用户马上再点麦克风会开出新一代会话，老一代收尾线程把全局标志翻 false
     * 会误杀新一代的 worker 循环）。worker 循环、isCancelled、回调守卫都认它。
     */
    private var cloudActive: java.util.concurrent.atomic.AtomicBoolean? = null

    /** 会话工厂 —— 测试注入 fake 用；null 时走生产的
     * [com.psyche.memo.provider.CloudAsrService.startSession]。 */
    internal var sessionFactory: (
        (okhttp3.OkHttpClient, com.psyche.memo.ui.AsrServiceOptions) -> com.psyche.memo.provider.CloudAsrSession?
    )? = null

    /** 采集器工厂 —— 测试注入 fake 用（Robolectric 下真 AudioRecord 不可靠）。 */
    internal var recorderFactory: (
        (Int, (ByteArray) -> Unit, (Float) -> Unit) -> AsrRecorder?
    )? = null

    val isActive: Boolean
        get() = _state.value != State.Idle

    /**
     * 麦克风按钮的可见条件（CIB:2542-2546 `showVoiceInput`）—— **上游要求先选中一个
     * ASR 服务**：`selectedAsrService != null && asr.canUse(selectedAsrService)`，
     * 而 `selectedAsrService` 在 `asrServices` 为空时就是 null（settings_provider.dart:440-447
     * 与 1525-1528）。
     *
     * 这里原先写的是「或者本机有系统识别就行」，于是在从没配过语音输入的手机上也常驻
     * 一颗麦克风（用户 2026-09-25「我都没有设置呀，怎么还是要显示输入框里面图标呀」）。
     * 选中的是 `system` 那一类时，可用性才等于「本机识别器在不在」。
     */
    fun canUse(): Boolean {
        val selected = cloudOptions() ?: return false
        return if (selected.kind == com.psyche.memo.ui.AsrServiceKind.system) {
            systemRecognizerAvailable()
        } else {
            true
        }
    }

    fun start() {
        if (isActive || !canUse()) return
        finalText = ""
        cloudTranscript = ""
        _levels.value = List(WAVE_BAR_COUNT) { 0f }
        val cloud = cloudOptions()
        val client = httpClient
        if (shouldUseCloudAsr(cloud, client)) {
            startCloud(cloud!!, client!!)
        } else {
            startSystem()
        }
    }

    // ------------------------------------------------------------------ 云端

    private fun startCloud(
        options: com.psyche.memo.ui.AsrServiceOptions,
        client: okhttp3.OkHttpClient,
    ) {
        // 本代独立取消标志：isCancelled / worker 循环 / partial 守卫全认它。
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        cloudActive = active
        val session = runCatching {
            sessionFactory?.invoke(client, options)
                ?: com.psyche.memo.provider.CloudAsrService.startSession(
                    client,
                    options,
                    isCancelled = { !active.get() },
                )
        }.getOrNull()
        if (session == null) {
            destroy()
            return
        }
        cloudSession = session
        _state.value = State.Listening("")
        session.observePartials(
            onPartial = { text ->
                cloudTranscript = text
                main().post { if (active.get()) _state.value = State.Listening(text) }
            },
            onError = {
                // 终态错误：回 Idle（原版把错误塞进 partial 的 error 通道，UI 只收起录音行）。
                main().post {
                    if (active.get()) destroy()
                }
            },
        )
        // 队列消费者：串行把音频喂给会话（会话内部按 provider 攒段发 HTTP）。
        cloudWorker = Thread {
            while (active.get()) {
                val chunk = runCatching { cloudQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) }
                    .getOrNull() ?: continue
                runCatching { session.addPcm16(chunk) }
            }
        }.also { it.start() }

        val sampleRate = when (options) {
            is com.psyche.memo.ui.MimoAsrOptions -> options.sampleRate
            is com.psyche.memo.ui.StepAsrOptions -> options.sampleRate
            else -> 16000
        }
        val rec = recorderFactory?.invoke(sampleRate, { chunk ->
            if (active.get()) cloudQueue.offer(chunk)
        }, { level -> pushLevel(level) })
            ?: AsrRecorder(
                sampleRate = sampleRate,
                onChunk = { if (active.get()) cloudQueue.offer(it) },
                onLevel = { level -> pushLevel(level) },
            )
        recorder = rec
        if (!rec.start()) {
            // 权限缺失 / 麦克风被占用：原版同样只是回 Idle。
            active.set(false)
            destroy()
        }
    }

    // ------------------------------------------------------------------ 系统

    private fun startSystem() {
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        sr.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            // 实时 partial（chat_input_bar 的流式转写体验）。
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        _state.value = State.Listening("")
        sr.startListening(intent)
    }

    /** 取消按钮（CIB:859-863）：丢弃结果并回 Idle。 */
    fun cancel() {
        destroy()
    }

    /**
     * 停止/发送按钮（CIB:904-929）。最终文本就绪后回调 [onFinal]（无论后续
     * 是否发送——sendAfter 语义由调用方在回调里处理：文本进输入框，发送再触发）。
     */
    fun finish(onFinal: (String) -> Unit) {
        val session = cloudSession
        if (session != null) {
            // 云端路径：先进「识别中」（用户 2026-09-16：点打勾后转写指示要**马上**
            // 显示，不能等后台 finish() 完成才动），录音行保持展开直到文字回填。
            _state.value = State.Transcribing
            finishCloud(session, onFinal)
            return
        }
        _state.value = State.Transcribing
        val text = finalText
        recognizer?.stopListening()
        if (text.isNotEmpty()) {
            // 已有最终文本（partial 已被提升），直接收尾。
            destroy()
            onFinal(text)
        } else {
            // onResults 异步到达后再回调。
            pendingFinalCallback = onFinal
        }
    }

    /**
     * 云端收尾：点停止后立刻切「识别中」指示（[State.Transcribing] 由 [finish] 设置，
     * 用户 2026-09-16「打勾马上显示这个提示」），最终转写在后台线程 `finish()` 完成
     * 后回填输入框并收起录音行。主线程零阻塞（join / HTTP 都不在主线程）。
     */
    private fun finishCloud(
        session: com.psyche.memo.provider.CloudAsrSession,
        onFinal: (String) -> Unit,
    ) {
        val active = cloudActive
        // **收尾线程里绝不能先翻取消标志**：会话的取消判据就是 `!active.get()`
        //（isCancelled），先翻的话 `session.finish()` 在 ensureActive() 里就被当成
        // 「已取消」直接丢弃 —— 最后一段永远不发，云端识别**永远拿不到文字**
        //（用户 2026-09-16「语音识别根本用不了」；AsrProbe 实测 finish 时连
        // mimo flush 都没打出来）。顺序必须是：停采集 → finish() → 再翻标志。
        recorder?.stop()
        recorder = null
        cloudSession = null
        _levels.value = List(WAVE_BAR_COUNT) { 0f }
        val worker = cloudWorker
        cloudWorker = null
        Thread {
            // 只等 worker 把队列里的最后几块喂完（喂不到也不死等，最终转写
            // 以 partial 累积为准，最后一段由 finish() 冲出去）。
            runCatching { worker?.join(500) }
            val text = runCatching { session.finish() }
                .getOrElse { cloudTranscript }
                .ifBlank { cloudTranscript }
            // finish() 完成后才能翻标志（worker 循环靠它退出）。
            active?.set(false)
            main().post {
                // 文字就绪才收起录音行（回 Idle）——「识别中」期间行保持展开。
                _state.value = State.Idle
                if (text.isNotEmpty()) onFinal(text)
            }
        }.start()
    }

    private var pendingFinalCallback: ((String) -> Unit)? = null

    private fun destroy() {
        cloudActive?.set(false)
        cloudActive = null
        recorder?.stop()
        recorder = null
        runCatching { cloudSession?.cancel() }
        cloudSession = null
        cloudWorker?.join(300)
        cloudWorker = null
        cloudQueue.clear()
        recognizer?.destroy()
        recognizer = null
        pendingFinalCallback = null
        _state.value = State.Idle
        _levels.value = List(WAVE_BAR_COUNT) { 0f }
    }

    private fun main(): android.os.Handler =
        mainHandler ?: android.os.Handler(android.os.Looper.getMainLooper()).also { mainHandler = it }

    /** 波形推进（系统路径用 rmsdB，云端路径用 [AsrRecorder.levelOf] 的结果）。 */
    private fun pushLevel(norm: Float) {
        val next = _levels.value.toMutableList()
        next.removeAt(0)
        next.add(norm.coerceIn(0f, 1f))
        _levels.value = next
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            // 波形电平（_VoiceWaveformPainter 振幅 0..1）：Android rmsdB 典型
            // 区间约 [-2, 10]，线性归一。
            pushLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            _state.value = State.Transcribing
        }

        override fun onError(error: Int) {
            // 无语音/取消类错误静默回 Idle（原版 no-speech 容错）。
            destroy()
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            finalText = text
            val callback = pendingFinalCallback
            destroy()
            if (callback != null && text.isNotEmpty()) callback(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (partial.isNotEmpty()) {
                finalText = partial
                _state.value = State.Listening(partial)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    companion object {
        /** 波形条数（_VoiceWaveformPainter 可视容量近似）。 */
        const val WAVE_BAR_COUNT = 28

        /** 振幅归一化（onRmsChanged → 0..1），供单测。 */
        fun normalizeLevel(rmsdB: Float): Float = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
    }
}

/**
 * 这次录音该不该走云端：选了服务**且**拿到了 OkHttp（生产里由容器注入；测试/降级
 * 场景没有 client 时回系统识别，而不是直接失败）。
 */
internal fun shouldUseCloudAsr(
    options: com.psyche.memo.ui.AsrServiceOptions?,
    client: okhttp3.OkHttpClient?,
): Boolean = options != null && client != null &&
    // `system` 那一类没有 HTTP 形状，走云端只会立刻失败（isConfigured 恒真，
    // 所以光靠「配好了」挡不住）。
    options.kind != com.psyche.memo.ui.AsrServiceKind.system

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
    @Volatile private var cloudRunning = false
    @Volatile private var cloudTranscript = ""
    private var mainHandler: android.os.Handler? = null

    val isActive: Boolean
        get() = _state.value != State.Idle

    /** 麦克风按钮的可见条件（CIB:2542-2546 showVoiceInput）：选中的云端服务可用，
     *  或者系统识别可用。 */
    fun canUse(): Boolean = cloudOptions() != null ||
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

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
        val session = runCatching {
            com.psyche.memo.provider.CloudAsrService.startSession(
                client,
                options,
                isCancelled = { !cloudRunning },
            )
        }.getOrNull()
        if (session == null) {
            destroy()
            return
        }
        cloudSession = session
        cloudRunning = true
        _state.value = State.Listening("")
        session.observePartials(
            onPartial = { text ->
                cloudTranscript = text
                main().post { if (cloudRunning) _state.value = State.Listening(text) }
            },
            onError = {
                // 终态错误：回 Idle（原版把错误塞进 partial 的 error 通道，UI 只收起录音行）。
                main().post {
                    cloudRunning = false
                    destroy()
                }
            },
        )
        // 队列消费者：串行把音频喂给会话（会话内部按 provider 攒段发 HTTP）。
        cloudWorker = Thread {
            while (cloudRunning) {
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
        val rec = AsrRecorder(
            sampleRate = sampleRate,
            onChunk = { if (cloudRunning) cloudQueue.offer(it) },
            onLevel = { level -> pushLevel(level) },
        )
        recorder = rec
        if (!rec.start()) {
            // 权限缺失 / 麦克风被占用：原版同样只是回 Idle。
            cloudRunning = false
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
        _state.value = State.Transcribing
        val session = cloudSession
        if (session != null) {
            finishCloud(session, onFinal)
            return
        }
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

    /** 云端收尾：停采集 → 冲掉队列 → `finish()` 拿最终转写（都在后台线程）。 */
    private fun finishCloud(
        session: com.psyche.memo.provider.CloudAsrSession,
        onFinal: (String) -> Unit,
    ) {
        cloudRunning = false
        recorder?.stop()
        recorder = null
        cloudWorker?.join(500)
        cloudWorker = null
        cloudSession = null
        Thread {
            val text = runCatching { session.finish() }
                .getOrElse { cloudTranscript }
                .ifBlank { cloudTranscript }
            main().post {
                _state.value = State.Idle
                _levels.value = List(WAVE_BAR_COUNT) { 0f }
                if (text.isNotEmpty()) onFinal(text)
            }
        }.start()
    }

    private var pendingFinalCallback: ((String) -> Unit)? = null

    private fun destroy() {
        cloudRunning = false
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
): Boolean = options != null && client != null

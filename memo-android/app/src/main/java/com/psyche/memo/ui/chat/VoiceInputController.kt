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
 * 语音输入控制器 —— chat_input_bar.dart _startVoiceInput 的 Android 系统执行器
 * （system_asr_service.dart 的 SpeechToText 路径；云端 ASR 会话引擎
 * cloud_asr_service.dart 属 ASR 执行器批次，本控制器暂走系统识别，与
 * asr_selected_service_id_v1 的分派逻辑在后续批次接线）。
 *
 * 状态机（CIB:852-932 录音行）：
 * - [State.Listening]：识别中，partial 文本实时进输入框，onRmsChanged 驱动波形；
 * - [State.Transcribing]：识别结束（停止按钮触发），等待最终结果；
 * - 停止（sendAfter=false）→ 最终文本留在输入框；
 * - 发送（sendAfter=true）→ 最终文本回调给发送链路；
 * - 取消 → 丢弃全部结果。
 */
class VoiceInputController(private val context: Context) {

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

    val isActive: Boolean
        get() = _state.value != State.Idle

    /** 麦克风按钮的可见条件（CIB:2542-2546 showVoiceInput 的系统分支）。 */
    fun canUse(): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    fun start() {
        if (isActive || !canUse()) return
        finalText = ""
        _levels.value = List(WAVE_BAR_COUNT) { 0f }
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

    private var pendingFinalCallback: ((String) -> Unit)? = null

    private fun destroy() {
        recognizer?.destroy()
        recognizer = null
        pendingFinalCallback = null
        _state.value = State.Idle
        _levels.value = List(WAVE_BAR_COUNT) { 0f }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            // 波形电平（_VoiceWaveformPainter 振幅 0..1）：Android rmsdB 典型
            // 区间约 [-2, 10]，线性归一。
            val norm = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            val next = _levels.value.toMutableList()
            next.removeAt(0)
            next.add(norm)
            _levels.value = next
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

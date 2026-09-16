package com.psyche.memo.ui.chat

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

/**
 * `AudioRecord` PCM16 单声道采集 —— 云端 ASR 的音频源（原版走 `record` 包的
 * `AudioRecorder`；Android 侧用平台 [AudioRecord]）。
 *
 * 采集在独立线程：每读到一块就回调 [onChunk]（PCM16 小端、单声道、[sampleRate]），
 * 并按 RMS 回调 [onLevel]（0..1，驱动输入栏的波形）。
 */
internal class AsrRecorder(
    private val sampleRate: Int,
    private val onChunk: (ByteArray) -> Unit,
    private val onLevel: (Float) -> Unit,
) {

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile private var running = false

    /** 权限缺失 / 麦克风被占用时返回 false（调用方据此回到 Idle）。 */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false
        // 采集参数对齐 RikkaHub 的 speech 模块（`MiMoASRController.startRecorder`
        // L137-156，用户同一台机器实测好用）：读块 100ms 且 ≥4KB，AudioRecord 缓冲
        // = 读缓冲 × 2。
        val readBuffer = maxOf(minBuffer, sampleRate / 10 * 2, 4096)
        val recorder = runCatching {
            AudioRecord(
                // **别改回 VOICE_RECOGNITION**：小米/MIUI 上这个源常常静音或被限制
                // —— 表现就是「波形不动、识别不出字」（用户 2026-09-16）。RikkaHub
                // 在同一台设备上用 VOICE_COMMUNICATION 是好的。
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                readBuffer * 2,
            )
        }.getOrNull() ?: return false
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }
        record = recorder
        running = true
        recorder.startRecording()
        val buffer = ByteArray(readBuffer)
        thread = Thread {
            while (running) {
                val read = runCatching { recorder.read(buffer, 0, buffer.size) }.getOrDefault(-1)
                if (read <= 0) continue
                val chunk = buffer.copyOf(read)
                onLevel(levelOf(chunk))
                runCatching { onChunk(chunk) }
            }
        }.also { it.start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        record?.let { recorder ->
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
        record = null
        onLevel(0f)
    }

    companion object {
        /** PCM16 的 RMS 归一到 0..1（32767 满量程）。 */
        internal fun levelOf(pcm: ByteArray): Float {
            if (pcm.size < 2) return 0f
            var sum = 0.0
            var index = 0
            var count = 0
            while (index + 1 < pcm.size) {
                val sample = ((pcm[index + 1].toInt() shl 8) or (pcm[index].toInt() and 0xFF)).toShort()
                sum += sample.toDouble() * sample.toDouble()
                index += 2
                count++
            }
            if (count == 0) return 0f
            val rms = sqrt(sum / count)
            return (rms / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
        }
    }
}

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
import java.util.Locale

/**
 * System TTS playback (chat_message_widget.dart _replayTextToSpeech, system
 * engine path). Cloud TTS providers live with the settings batch 2 (tts
 * configuration); this is the minimal Android system-engine player.
 */
object TtsPlayer {
    @Volatile private var engine: TextToSpeech? = null
    @Volatile private var ready = false

    /** 引擎初始化完成前请求的待播文本（chat_message_widget.dart speak-once-warmed 语义）。 */
    @Volatile private var pendingText: String? = null

    fun speak(context: Context, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val existing = engine
        if (existing != null) {
            if (ready) speakNow(existing, trimmed) else pendingText = trimmed
            return
        }
        pendingText = trimmed
        // android.jar 里 TextToSpeech 没有 setOnInitListener —— 初始化回调
        // 只能通过构造器 OnInitListener 传入。
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            val queued = pendingText
            pendingText = null
            val initialized = engine
            if (ready && queued != null && initialized != null) {
                speakNow(initialized, queued)
            }
        }
        engine?.setOnUtteranceProgressListener(utteranceListener)
    }

    /** UtteranceProgressListener 是抽象类不是接口，object 表达式实现三个抽象方法。 */
    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) {}
        override fun onError(utteranceId: String?) {}
    }

    private fun speakNow(tts: TextToSpeech, text: String) {
        try {
            tts.language = Locale.getDefault()
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "memo_tts_replay")
        } catch (e: Exception) {
            // 原版 _replayTextToSpeech 同样不向 UI 抛播放异常。
        }
    }

    fun stop() {
        try {
            engine?.stop()
        } catch (e: Exception) {
        }
    }

    fun shutdown() {
        try {
            engine?.shutdown()
        } catch (e: Exception) {
        }
        engine = null
        ready = false
        pendingText = null
    }
}

/**
 * chat_message_widget.dart:554 _buildTextToSpeechReplayRow — 最多 [maxLines]
 * 行的文本 + 右侧重播按钮（RefreshCw 14dp，命中区 30dp），点击用系统 TTS
 * 朗读。textToSpeechToolText = arguments['text']。
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
    DisposableEffect(Unit) {
        onDispose { TtsPlayer.stop() }
    }
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

/** chat_message_widget.dart _textToSpeechToolText — arguments['text']。 */
fun textToSpeechToolText(args: kotlinx.serialization.json.JsonObject?): String =
    args?.get("text")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        .orEmpty().trim()

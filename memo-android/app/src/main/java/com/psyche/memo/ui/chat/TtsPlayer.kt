package com.psyche.memo.ui.chat

import android.content.Context
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

/**
 * TTS playback for the chat (chat_message_widget.dart `_replayTextToSpeech` +
 * `tts_provider.dart`).
 *
 * Long text is split into chunks and spoken one at a time, which is what makes
 * pause/resume and ±15 s seeking possible on an engine that cannot pause an
 * utterance: "pause" stops the engine, "resume" restarts at the current chunk,
 * and a seek moves the chunk cursor. Positions are estimated from the played
 * character offsets (see [TtsPlaybackTimeline]).
 *
 * The engine is a [SwitchableTtsEngine]: when the selected TTS service is a
 * network one it synthesizes audio (see [NetworkTtsEngine]) and the floating
 * player shows its save button, exactly like `tts.canSaveNetworkAudio`
 * upstream; with the system engine there is no byte stream to save, so the
 * button stays hidden.
 */
object TtsPlayer {

    /**
     * The engine is created once from the application context, so the flows the
     * UI collects never swap identity (a lazily-replaced flow would freeze the
     * chat icon on a stale value — the class of bug this layer exists to avoid).
     */
    @Volatile private var controllerRef: TtsPlaybackController? = null

    @Volatile private var switchableRef: SwitchableTtsEngine? = null

    /** Called from `MemoApplication.onCreate`. */
    fun init(
        context: Context,
        client: okhttp3.OkHttpClient? = null,
        store: com.psyche.memo.ui.TtsServicesStore? = null,
    ) {
        if (controllerRef != null) return
        synchronized(this) {
            if (controllerRef == null) {
                val engine = if (client != null && store != null) {
                    SwitchableTtsEngine(context.applicationContext, client, store)
                        .also { switchableRef = it }
                } else {
                    SystemTtsEngine(context.applicationContext)
                }
                controllerRef = TtsPlaybackController(engine)
            }
        }
    }

    /** 当前是否在用网络语音（悬浮播放器的保存钮据此显示）。 */
    val canSaveNetworkAudio: Boolean get() = state.value.usingNetwork

    /** 当前会话被朗读的整段文本（保存音频要重合成整段）。 */
    @Volatile private var lastText: String = ""

    /** 当前/最近一次朗读的整段文本。 */
    fun currentText(): String = lastText

    /**
     * 整段重新合成并交给 [onResult]（原版 `synthesizeAllAndCollect()`）——
     * 悬浮播放器的「保存音频」用它拿字节，而不是把当前这一块的缓存拼起来。
     * 未使用网络语音 / 合成失败 → null。
     */
    fun collectNetworkAudio(text: String, onResult: (com.psyche.memo.provider.NetworkTtsResult?) -> Unit) {
        val engine = switchableRef
        if (engine == null) {
            onResult(null)
            return
        }
        Thread {
            val result = engine.collectNetworkAudio(text)
            // 合成在后台线程，但回调要落在主线程（调用方要动 Compose 状态 / 起 SAF）。
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(result) }
        }.start()
    }

    /** 「保存音频」：重合成**整段**文本再写文件（原版 `synthesizeAllAndCollect`）。 */
    fun saveAudio(
        text: String,
        onResult: (com.psyche.memo.provider.NetworkTtsResult?) -> Unit,
    ) = collectNetworkAudio(text.ifEmpty { lastText }, onResult)

    private fun controller(context: Context?): TtsPlaybackController? {
        controllerRef?.let { return it }
        if (context != null) init(context)
        return controllerRef
    }

    /** The floating player renders this. */
    val state: StateFlow<TtsPlaybackState>
        get() = controller(null)?.state ?: EMPTY_STATE

    /** The chat action row's Speak/Stop icon follows this. */
    val speaking: StateFlow<Boolean>
        get() = controller(null)?.speaking ?: EMPTY_SPEAKING

    /** [ownerId] is the chat message the playback belongs to, when there is one. */
    fun speak(context: Context, text: String, ownerId: String? = null) {
        lastText = text
        controller(context)?.speak(text, ownerId)
    }

    /** 已初始化后的免 Context 重载（ViewModel 等无 UI 的调用方，如自动播放）。 */
    fun speak(text: String, ownerId: String? = null) {
        lastText = text
        controllerRef?.speak(text, ownerId)
    }

    fun togglePause() {
        controller(null)?.togglePause()
    }

    fun stop() {
        controller(null)?.stop()
    }

    fun replay() {
        controller(null)?.replay()
    }

    fun seekBackward() {
        controller(null)?.seekBackward()
    }

    fun seekForward() {
        controller(null)?.seekForward()
    }

    fun seekRelative(deltaMs: Long) {
        controller(null)?.seekRelative(deltaMs)
    }

    fun cyclePlaybackSpeed() {
        controller(null)?.cyclePlaybackSpeed()
    }

    fun setPlaybackSpeed(speed: Double) {
        controller(null)?.setPlaybackSpeed(speed)
    }

    fun shutdown() {
        controller(null)?.shutdown()
    }

    /** tts_provider.dart `_seekStep`. */
    const val SEEK_STEP_MS: Long = TtsPlaybackController.SEEK_STEP_MS

    /** Only used before [init] has run (an app that never touches the player). */
    private val EMPTY_STATE = MutableStateFlow(TtsPlaybackState())
    private val EMPTY_SPEAKING = MutableStateFlow(false)
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

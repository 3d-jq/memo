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

    /** 系统语音四个偏好键的读写口（[SystemTtsConfig]）；启动早期可能还没接上。 */
    @Volatile private var servicesStoreRef: com.psyche.memo.ui.TtsServicesStore? = null

    /** Called from `MemoApplication.onCreate`. */
    fun init(
        context: Context,
        client: okhttp3.OkHttpClient? = null,
        store: com.psyche.memo.ui.TtsServicesStore? = null,
    ) {
        if (controllerRef != null) return
        synchronized(this) {
            if (controllerRef == null) {
                if (store != null) servicesStoreRef = store
                // 现读：偏好改过之后引擎重建/重下发都拿到最新值。
                val config = { servicesStoreRef?.systemTtsConfig() ?: SystemTtsConfig() }
                val engine = if (client != null && store != null) {
                    SwitchableTtsEngine(context.applicationContext, client, store, config)
                        .also { switchableRef = it }
                } else {
                    SystemTtsEngine(context.applicationContext, config)
                }
                controllerRef = TtsPlaybackController(engine).also {
                    // 原版 `_init` 的 `_kickEngine` + `_ensureBound`：启动就把引擎构造出来，
                    // 「语音服务」页开的时候才枚举得到引擎与语言。
                    it.prepareEngine()
                }
            }
        }
    }

    /** 偏好写完后重新下发（语速/音调/引擎/语言）。 */
    fun reloadSystemConfig() {
        controller(null)?.reloadSystemConfig()
    }

    /** 原版 `listEngines()`。引擎还没就绪时为空表。 */
    fun listEngines(): List<String> = controller(null)?.systemEngines() ?: emptyList()

    /** 原版 `listLanguages()`。 */
    fun listLanguages(): List<String> = controller(null)?.systemLanguages() ?: emptyList()

    /** 让引擎先起来（枚举要用它），配合 [listEngines] 的有界轮询用。 */
    fun prepareSystemEngine() {
        controller(null)?.prepareEngine()
    }

    /** 「使用缓存复播」（tts_settings_page.dart 的那颗开关）。 */
    val cacheNetworkAudioForReplay: Boolean
        get() = servicesStoreRef?.cacheNetworkAudioForReplay ?: false

    fun setCacheNetworkAudioForReplay(value: Boolean) {
        servicesStoreRef?.cacheNetworkAudioForReplay = value
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
        // 普通朗读按**当前选中**的服务：清掉上一次「听测试」留下的会话覆盖。
        switchableRef?.setSessionOverride(null)
        speakNow(context, text, ownerId)
    }

    /**
     * 用**系统引擎**朗读（原版 `tts_provider.dart:413` `speakSystem(text, flush)` →
     * `_speakQueued(text, flush)`，networkService = null）。
     *
     * 「语音服务」页系统行的「听测试」走这里 —— 和对话里同一条播放管线，所以会出
     * 悬浮播放胶囊（用户 2026-09-16「语音点击听测试那个没有我们那个胶囊呀」）。
     */
    fun speakSystem(context: Context, text: String) {
        switchableRef?.setSessionOverride(TtsSessionOverride.System)
        speakNow(context, text, ownerId = null)
    }

    /**
     * 用**指定的**网络服务朗读（原版 `speakWithNetworkService(service, text)`）：
     * 不看当前选中项 —— 服务行自己的「听测试」就该用这一行的服务试。
     */
    fun speakWithService(
        context: Context,
        text: String,
        service: com.psyche.memo.ui.TtsServiceOptions,
    ) {
        switchableRef?.setSessionOverride(TtsSessionOverride.Service(service))
        speakNow(context, text, ownerId = null)
    }

    private fun speakNow(context: Context, text: String, ownerId: String?) {
        // 原版 `_speakQueued` 第一件事就是 `_stripMarkdown(text).trim()`，空则什么都不做
        // （tts_provider.dart:430-432）。所有朗读入口共用这条路，所以选取（只给助手消息）
        // 在调用方做，剥 markdown 在这里做。
        val content = stripMarkdownForTts(text).trim()
        if (content.isEmpty()) return
        lastText = content
        controller(context)?.speak(content, ownerId)
    }

    /** 已初始化后的免 Context 重载（ViewModel 等无 UI 的调用方，如自动播放）。 */
    fun speak(text: String, ownerId: String? = null) {
        val content = stripMarkdownForTts(text).trim()
        if (content.isEmpty()) return
        lastText = content
        controllerRef?.speak(content, ownerId)
    }

    /**
     * 朗读一条**助手消息**（原版 `_speakAssistantMessage` →
     * `TtsTextSelection.apply(message.content, mode: settings.ttsTextSelectionMode)`，
     * home_page_controller.dart:1818）：模式在点的这一刻现读，所以设置页改完立刻生效。
     */
    fun speakAssistantReply(context: Context, content: String, ownerId: String? = null) {
        speak(context, assistantReplyForTts(servicesStoreRef?.textSelectionMode(), content), ownerId)
    }

    fun togglePause() {
        controller(null)?.togglePause()
    }

    fun stop() {
        controller(null)?.stop()
    }

    fun replay() {
        // 「使用缓存复播」决定重播要不要再花一次合成（原版 replay 的 reuseResolvedNetworkAudio）。
        controller(null)?.replay(allowCachedAudio = cacheNetworkAudioForReplay)
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

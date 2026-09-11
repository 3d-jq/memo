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
 * System-engine TTS playback for the chat (chat_message_widget.dart
 * `_replayTextToSpeech` + `tts_provider.dart` system path).
 *
 * Long text is split into chunks and spoken one at a time, which is what makes
 * pause/resume and ±15 s seeking possible on an engine that cannot pause an
 * utterance: "pause" stops the engine, "resume" restarts at the current chunk,
 * and a seek moves the chunk cursor. Positions are estimated from the played
 * character offsets (see [TtsPlaybackTimeline]); network TTS voices are a later
 * batch, so [TtsPlaybackState.usingNetwork] stays false and the player hides its
 * save button exactly as upstream does when no network audio is available.
 */
object TtsPlayer {

    /**
     * The engine is created once from the application context, so the flows the
     * UI collects never swap identity (a lazily-replaced flow would freeze the
     * chat icon on a stale value — the class of bug this layer exists to avoid).
     */
    @Volatile private var controllerRef: TtsPlaybackController? = null

    /** Called from `MemoApplication.onCreate`. */
    fun init(context: Context) {
        if (controllerRef != null) return
        synchronized(this) {
            if (controllerRef == null) {
                controllerRef = TtsPlaybackController(SystemTtsEngine(context.applicationContext))
            }
        }
    }

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
        controller(context)?.speak(text, ownerId)
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

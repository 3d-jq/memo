package com.psyche.memo.ui.chat

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.FastForward
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Rewind
import com.composables.icons.lucide.X
import com.psyche.memo.ui.theme.withAlpha
import kotlin.math.max
import java.util.Locale
import kotlin.math.roundToInt
import com.psyche.memo.ui.R as UiR

/**
 * `tts_floating_player.dart` — the draggable playback pill that floats over every
 * screen while a text-to-speech session is live (upstream mounts it in
 * `app_overlays`).
 *
 * Collapsed it shows the circular play/pause button (with the estimated-progress
 * ring), the stop button and the expand chevron; expanded it also reveals
 * rewind / speed / fast-forward, with the surface animating its width between the
 * two states. It fades in with a session and stays visible after playback ends so
 * the replay button is reachable.
 *
 * Network voices (and with them the "save audio" button) are a later batch, so
 * the save control is absent — exactly what upstream does when no network audio
 * is available.
 */
private val COLLAPSED_WIDTH = 120.dp
private val EXPANDED_WIDTH = 232.dp
private val HORIZONTAL_MARGIN = 12.dp
private val TOP_MARGIN = 12.dp
private val INITIAL_TOP_OFFSET = 68.dp
private val SURFACE_PADDING = 3.dp
private val EXPANDED_CONTROLS_WIDTH = 112.dp
private const val SURFACE_ANIMATION_MS = 220
private const val FADE_MS = 160

/** `tts_floating_player.dart:37 _saveButtonDelta` —— 保存钮带来的额外宽度。 */
private val SAVE_BUTTON_DELTA = 34.dp
private val BOTTOM_CLEARANCE = 64.dp

/** What the pill's buttons do; swapped out in tests. */
interface TtsPlayerActions {
    fun togglePause()
    fun stop()
    fun seekBackward()
    fun seekForward()
    fun cycleSpeed()

    companion object {
        val Default = object : TtsPlayerActions {
            override fun togglePause() = TtsPlayer.togglePause()
            override fun stop() = TtsPlayer.stop()
            override fun seekBackward() = TtsPlayer.seekBackward()
            override fun seekForward() = TtsPlayer.seekForward()
            override fun cycleSpeed() = TtsPlayer.cyclePlaybackSpeed()
        }
    }
}

/** The pill's drag target, so a UI test can grab it. */
const val TTS_PLAYER_TAG = "ttsFloatingPlayer"

@Composable
fun TtsFloatingPlayer(
    modifier: Modifier = Modifier,
    state: TtsPlaybackState = TtsPlayer.state.collectAsState().value,
    actions: TtsPlayerActions = TtsPlayerActions.Default,
) {
    val visible = state.isPlayerVisible

    var expanded by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf<Offset?>(null) }
    // A new session starts collapsed (upstream resets the flag on becoming visible).
    var wasVisible by remember { mutableStateOf(false) }
    if (visible && !wasVisible) expanded = false
    wasVisible = visible

    val fade by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(FADE_MS, easing = EaseOutCubic),
        label = "ttsPlayerFade",
    )
    if (!visible && fade == 0f) return

    val density = LocalDensity.current
    // 网络语音才有的「保存音频」钮（原版 `tts.canSaveNetworkAudio` 决定宽度 +34）。
    val canSave = TtsPlayer.canSaveNetworkAudio
    val saveDelta = if (canSave) SAVE_BUTTON_DELTA else 0.dp
    val targetWidth = (if (expanded) EXPANDED_WIDTH else COLLAPSED_WIDTH) + saveDelta
    val animatedWidth by animateFloatAsState(
        targetValue = targetWidth.value,
        animationSpec = tween(SURFACE_ANIMATION_MS, easing = EaseOutCubic),
        label = "ttsPlayerWidth",
    )

    // 保存音频：先整段重合成，再让用户选落点（SAF CreateDocument），与
    // `tts_floating_player.dart:373-421` 的 `_save()` 一致。
    val saveContext = LocalContext.current
    var pendingAudio by remember { mutableStateOf<com.psyche.memo.provider.NetworkTtsResult?>(null) }
    var saving by remember { mutableStateOf(false) }
    val saveLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("audio/mpeg"),
    ) { uri ->
        val audio = pendingAudio
        pendingAudio = null
        if (uri != null && audio != null) {
            runCatching {
                saveContext.contentResolver.openOutputStream(uri)?.use { it.write(audio.bytes) }
            }
        }
        saving = false
    }
    val onSave: () -> Unit = {
        if (!saving) {
            saving = true
            TtsPlayer.saveAudio(TtsPlayer.currentText()) { audio ->
                if (audio == null) {
                    saving = false
                } else {
                    pendingAudio = audio
                    saveLauncher.launch(
                        "memo_tts_${System.currentTimeMillis()}.${audio.extension}",
                    )
                }
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .alpha(fade),
    ) {
        val marginPx = with(density) { HORIZONTAL_MARGIN.toPx() }
        val topMarginPx = with(density) { TOP_MARGIN.toPx() }
        val bottomClearancePx = with(density) { BOTTOM_CLEARANCE.toPx() }
        val availableWidth = max(0f, constraints.maxWidth.toFloat() - marginPx * 2)
        val widthPx = with(density) { animatedWidth.dp.toPx() }.coerceIn(0f, availableWidth)
        val heightPx = constraints.maxHeight.toFloat()

        // The status-bar inset is already applied by the modifier above, so the
        // safe top is zero here.
        val bounds = PlayerBounds(
            maxWidth = constraints.maxWidth.toFloat(),
            maxHeight = heightPx,
            width = widthPx,
            safeTop = 0f,
            horizontalMargin = marginPx,
            topMargin = topMarginPx,
            bottomClearance = bottomClearancePx,
            initial = Offset(marginPx, topMarginPx + with(density) { INITIAL_TOP_OFFSET.toPx() }),
        )
        val currentBounds by rememberUpdatedState(bounds)
        val displayed = clampPosition(position ?: bounds.initial, bounds)

        Box(
            modifier = Modifier
                .offset { IntOffset(displayed.x.roundToInt(), displayed.y.roundToInt()) }
                .width(with(density) { widthPx.toDp() })
                .testTag(TTS_PLAYER_TAG)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        // `position` is the state object itself, so this reads the
                        // latest value on every event; using the composition-time
                        // position here would recompute from a stale origin and the
                        // pill would refuse to move.
                        val latest = currentBounds
                        val from = position ?: latest.initial
                        position = clampPosition(from + dragAmount, latest)
                    }
                },
        ) {
            PlayerSurface(
                state = state,
                expanded = expanded,
                actions = actions,
                canSave = canSave,
                saving = saving,
                onSave = onSave,
                onToggleExpanded = { expanded = !expanded },
            )
        }
    }
}

/** What the pill may move within, in pixels. */
private data class PlayerBounds(
    val maxWidth: Float,
    val maxHeight: Float,
    val width: Float,
    val safeTop: Float,
    val horizontalMargin: Float,
    val topMargin: Float,
    val bottomClearance: Float,
    val initial: Offset,
)

/** `_clampPosition` — keeps the pill on screen, below the status bar. */
private fun clampPosition(value: Offset, bounds: PlayerBounds): Offset {
    val minY = bounds.safeTop + bounds.topMargin
    val maxX = max(bounds.horizontalMargin, bounds.maxWidth - bounds.width - bounds.horizontalMargin)
    val maxY = max(minY, bounds.maxHeight - bounds.bottomClearance)
    return Offset(
        value.x.coerceIn(bounds.horizontalMargin, maxX),
        value.y.coerceIn(minY, maxY),
    )
}

/** `_FloatingPlayerSurface`. */
@Composable
private fun PlayerSurface(
    state: TtsPlaybackState,
    expanded: Boolean,
    actions: TtsPlayerActions,
    canSave: Boolean,
    saving: Boolean,
    onSave: () -> Unit,
    onToggleExpanded: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val showPlayIcon = state.status == TtsPlaybackStatus.PAUSED || state.status == TtsPlaybackStatus.ENDED
    val playTooltip = when {
        state.status == TtsPlaybackStatus.ENDED -> stringResource(UiR.string.tts_floating_replay_tooltip)
        showPlayIcon -> stringResource(UiR.string.tts_floating_resume_tooltip)
        else -> stringResource(UiR.string.tts_floating_pause_tooltip)
    }
    val playerLabel = stringResource(UiR.string.tts_floating_player_label)

    val controlsWidth by animateFloatAsState(
        targetValue = if (expanded) EXPANDED_CONTROLS_WIDTH.value else 0f,
        animationSpec = tween(SURFACE_ANIMATION_MS, easing = EaseOutCubic),
        label = "ttsControlsWidth",
    )

    Row(
        modifier = Modifier
            // Upstream: shadow alpha 0.14, blur 16, offset (0, 7). Compose's
            // basic shadow overload has no colour knobs, so the drop shadow uses
            // the platform default tone at the same elevation.
            .shadow(elevation = 7.dp, shape = RoundedCornerShape(MemoRadius.PILL_DP.dp))
            .clip(RoundedCornerShape(MemoRadius.PILL_DP.dp))
            .background(cs.surface.copy(alpha = 0.96f))
            .padding(horizontal = SURFACE_PADDING, vertical = 4.dp)
            .semantics {
                contentDescription = playerLabel
                stateDescription = formatDuration(state.positionMs) + " / " + formatDuration(state.durationMs)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularPlayButton(
            tooltip = playTooltip,
            showPlayIcon = showPlayIcon,
            progress = state.progress,
            chunkProgress = state.chunkProgress,
            onTap = { actions.togglePause() },
        )
        Spacer(Modifier.width(2.dp))
        PlayerToolIcon(
            tooltip = stringResource(UiR.string.tts_floating_close_tooltip),
            icon = Lucide.X,
            onClick = { actions.stop() },
        )
        Spacer(Modifier.width(2.dp))
        if (controlsWidth > 0.5f) {
            Row(
                modifier = Modifier.width(controlsWidth.dp).height(32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                PlayerToolIcon(
                    tooltip = stringResource(UiR.string.tts_floating_rewind15_tooltip),
                    icon = Lucide.Rewind,
                    onClick = { actions.seekBackward() },
                )
                Spacer(Modifier.width(2.dp))
                SpeedButton(speed = state.speed, onClick = { actions.cycleSpeed() })
                Spacer(Modifier.width(2.dp))
                PlayerToolIcon(
                    tooltip = stringResource(UiR.string.tts_floating_forward15_tooltip),
                    icon = Lucide.FastForward,
                    onClick = { actions.seekForward() },
                )
                // 保存音频（`tts_floating_player.dart:301-303`）：只有网络语音才有
                // 音频字节可存，系统 TTS 时整钮不渲染。
                if (canSave) {
                    Spacer(Modifier.width(2.dp))
                    PlayerToolIcon(
                        tooltip = stringResource(UiR.string.tts_floating_save_tooltip),
                        icon = Lucide.Download,
                        onClick = onSave,
                    )
                }
            }
        }
        Spacer(Modifier.width(2.dp))
        PlayerToolIcon(
            tooltip = stringResource(
                if (expanded) UiR.string.tts_floating_collapse_tooltip else UiR.string.tts_floating_expand_tooltip,
            ),
            icon = if (expanded) Lucide.ChevronLeft else Lucide.ChevronRight,
            onClick = onToggleExpanded,
        )
    }
}

/**
 * `_CircularPlayButton` — 42dp ring: 2.2dp track, 2.6dp progress arc and a 2.0dp
 * inner arc for the chunk cursor, around a 32dp play/pause button.
 */
@Composable
private fun CircularPlayButton(
    tooltip: String,
    showPlayIcon: Boolean,
    progress: Double,
    chunkProgress: Double,
    onTap: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerRadius = (minOf(size.width, size.height) - 4f) / 2f
            val innerRadius = outerRadius - 5f
            drawCircle(
                color = cs.outlineVariant.copy(alpha = 0.32f),
                radius = outerRadius,
                center = center,
                style = Stroke(width = 2.2f, cap = StrokeCap.Round),
            )
            if (progress > 0.0) {
                drawArc(
                    color = cs.primary,
                    startAngle = -90f,
                    sweepAngle = (progress.coerceIn(0.0, 1.0) * 360.0).toFloat(),
                    useCenter = false,
                    topLeft = Offset(center.x - outerRadius, center.y - outerRadius),
                    size = Size(outerRadius * 2f, outerRadius * 2f),
                    style = Stroke(width = 2.6f, cap = StrokeCap.Round),
                )
            }
            if (chunkProgress > 0.0) {
                drawArc(
                    color = cs.tertiary.copy(alpha = 0.82f),
                    startAngle = -90f,
                    sweepAngle = (chunkProgress.coerceIn(0.0, 1.0) * 360.0).toFloat(),
                    useCenter = false,
                    topLeft = Offset(center.x - innerRadius, center.y - innerRadius),
                    size = Size(innerRadius * 2f, innerRadius * 2f),
                    style = Stroke(width = 2.0f, cap = StrokeCap.Round),
                )
            }
        }
        PressableCircle(
            size = 32.dp,
            background = cs.primaryContainer.copy(alpha = 0.72f),
            tooltip = tooltip,
            onTap = onTap,
        ) {
            Icon(
                if (showPlayIcon) Lucide.Play else Lucide.Pause,
                contentDescription = null,
                tint = cs.onPrimaryContainer,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

/** `_SpeedButton` — the `x1.2` capsule. */
@Composable
private fun SpeedButton(speed: Double, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tooltip = stringResource(UiR.string.tts_floating_speed_tooltip)
    Pressable(
        background = cs.primaryContainer.copy(alpha = 0.44f),
        shape = RoundedCornerShape(MemoRadius.PILL_DP.dp),
        tooltip = tooltip,
        onTap = onClick,
        modifier = Modifier.width(44.dp).height(32.dp),
    ) {
        Text(
            text = "x" + String.format(Locale.US, "%.1f", speed),
            maxLines = 1,
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = cs.onPrimaryContainer),
        )
    }
}

/** `_ToolIcon` — a 32dp round icon button. */
@Composable
private fun PlayerToolIcon(tooltip: String, icon: ImageVector, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    PressableCircle(
        size = 32.dp,
        background = cs.surfaceContainerHighest.copy(alpha = 0.38f),
        tooltip = tooltip,
        onTap = onClick,
    ) {
        Icon(icon, contentDescription = null, tint = withAlpha(cs.onSurface, 0.86), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun PressableCircle(
    size: androidx.compose.ui.unit.Dp,
    background: androidx.compose.ui.graphics.Color,
    tooltip: String,
    onTap: () -> Unit,
    content: @Composable () -> Unit,
) {
    Pressable(
        background = background,
        shape = RoundedCornerShape(MemoRadius.PILL_DP.dp),
        tooltip = tooltip,
        onTap = onTap,
        modifier = Modifier.size(size),
    ) { content() }
}

/**
 * Flutter's `IosCardPress`: press scale 0.98, no Material ripple, no haptics
 * (upstream passes `haptics: false` for every player button).
 */
@Composable
private fun Pressable(
    background: androidx.compose.ui.graphics.Color,
    shape: androidx.compose.ui.graphics.Shape,
    tooltip: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = tween(90),
        label = "ttsPress",
    )
    Box(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .background(background)
            .clickable(interactionSource = interaction, indication = null, onClick = onTap)
            .semantics { contentDescription = tooltip },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** `_formatDuration` — `m:ss`, capped at a day. */
private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceIn(0L, 24L * 60 * 60)
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

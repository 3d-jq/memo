package com.psyche.memo.ui

import kotlin.math.PI
import kotlin.math.sin

/**
 * Chat-page style spec — every constant mirrors the Flutter source with a
 * `file:line` reference (CMW = lib/features/chat/widgets/chat_message_widget.dart,
 * CIB = lib/features/home/widgets/chat_input_bar.dart, MLV =
 * lib/features/home/widgets/message_list_view.dart) so visual diffs can be
 * audited 1:1 against the original project.
 */
object ChatStyleSpec {

    // ------------------------------------------------------------------
    // Message list
    // ------------------------------------------------------------------

    /** CMW:1768 / 2780 — per-message horizontal padding (user / assistant). */
    const val USER_MESSAGE_HORIZONTAL_DP = 16f
    const val ASSISTANT_MESSAGE_HORIZONTAL_DP = 20f

    /** CMW:1768 / 2780 — per-message vertical padding; MLV:1684-1690 list insets. */
    const val MESSAGE_VERTICAL_DP = 12f
    const val LIST_TOP_PADDING_DP = 8f
    const val LIST_BOTTOM_PADDING_DP = 16f

    /** CMW:1792 — gap between the name and timestamp lines. */
    const val NAME_TIME_GAP_DP = 2f

    /** CMW:1810 / 2846 — header → content gap (user + assistant). */
    const val HEADER_CONTENT_GAP_DP = 8f

    /** CMW:2789 — assistant avatar → name column gap. */
    const val ASSISTANT_AVATAR_NAME_GAP_DP = 8f

    /** CMW:1803-1807 + 1590-1659 — user avatar: 32 circle primary@0.1, icon 18. */
    const val AVATAR_SIZE_DP = 32f
    const val AVATAR_BG_ALPHA = 0.1f
    const val AVATAR_ICON_DP = 18f

    /** CMW:1833/1853 — user bubble & actions max width = 75% of screen width. */
    const val USER_MAX_WIDTH_RATIO = 0.75f

    /** CMW:2389/2393 — bubble corner radius 16, padding all 12. */
    const val BUBBLE_CORNER_DP = 16f
    const val BUBBLE_PADDING_DP = 12f

    /** CMW:2394-2398 — user bubble primary alpha per brightness. */
    const val USER_BUBBLE_ALPHA_LIGHT = 0.08f
    const val USER_BUBBLE_ALPHA_DARK = 0.15f

    /** CMW:2046-2054 — user text 15.5sp, markdown line-height 1.45×15.5. */
    const val USER_TEXT_SP = 15.5f
    const val USER_TEXT_LINE_HEIGHT_SP = 22.475f

    // ------------------------------------------------------------------
    // Message actions row (CMW:1847-1974)
    // ------------------------------------------------------------------

    /** CMW:1848 — gap above the actions row (showUserActions branch). */
    const val ACTIONS_TOP_GAP_DP = 8f

    /** CMW:1859-1959 — 28×28 slot with a bare 16 icon (no background), gap 6. */
    const val ACTION_SLOT_DP = 28f
    const val ACTION_ICON_DP = 16f
    const val ACTION_ICON_ALPHA = 0.9f
    const val ACTION_GAP_DP = 6f

    /** ios_tactile.dart:54-59 — disabled alpha = base.a × 0.45 (0.9×0.45). */
    const val ACTION_DISABLED_ALPHA = 0.405f

    // ------------------------------------------------------------------
    // Streaming dots (CMW:4105-4196)
    // ------------------------------------------------------------------

    const val DOTS_DURATION_MS = 1100
    const val DOTS_PHASE_OFFSET = 0.22f
    const val DOTS_MIN_SCALE = 0.85f
    const val DOTS_SCALE_AMPLITUDE = 0.15f
    const val DOTS_MIN_ALPHA = 0.45f
    const val DOTS_ALPHA_AMPLITUDE = 0.45f
    const val DOTS_DOT_DP = 9f
    const val DOTS_GAP_DP = 6f
    const val DOTS_HEIGHT_DP = 16f

    /** Number of dots in the indicator (CMW:4175). */
    const val DOTS_COUNT = 3

    /** Canvas width: dot×3 + gap×2 (CMW:4148). */
    const val DOTS_WIDTH_DP = DOTS_DOT_DP * 3 + DOTS_GAP_DP * 2

    /** Painter math for one dot: phase = (t − i·0.22)·2π; wave = (sin+1)/2;
     *  scale = 0.85 + 0.15·wave; alpha = 0.45 + 0.45·wave (CMW:4176-4179). */
    fun dotState(fraction: Float, index: Int): DotState {
        val phase = ((fraction - index * DOTS_PHASE_OFFSET) * 2.0 * PI).toFloat()
        val wave = (sin(phase) + 1f) / 2f
        return DotState(
            scale = DOTS_MIN_SCALE + DOTS_SCALE_AMPLITUDE * wave,
            alpha = DOTS_MIN_ALPHA + DOTS_ALPHA_AMPLITUDE * wave,
        )
    }

    data class DotState(val scale: Float, val alpha: Float)

    // ------------------------------------------------------------------
    // Foreground palette (CMW:3926-3931) — alpha per brightness, base onSurface
    // ------------------------------------------------------------------

    /** fg.strong = onSurface α(dark 0.88 / light 0.78). CMW:3926. */
    const val FG_STRONG_DARK = 0.88f
    const val FG_STRONG_LIGHT = 0.78f

    /** fg.medium = onSurface α(dark 0.76 / light 0.66). CMW:3927. */
    const val FG_MEDIUM_DARK = 0.76f
    const val FG_MEDIUM_LIGHT = 0.66f

    /** fg.muted = onSurface α(dark 0.56 / light 0.46). CMW:3928. */
    const val FG_MUTED_DARK = 0.56f
    const val FG_MUTED_LIGHT = 0.46f

    /** fg.body = onSurface α(dark 0.72 / light 0.60). CMW:3929. */
    const val FG_BODY_DARK = 0.72f
    const val FG_BODY_LIGHT = 0.60f

    // ------------------------------------------------------------------
    // Tool loading dots (CMW:5407 — LoadingIndicator height 12, dot 3, gap 2)
    // ------------------------------------------------------------------

    const val TOOL_LOADING_DOTS_DOT_DP = 3f
    const val TOOL_LOADING_DOTS_GAP_DP = 2f
    const val TOOL_LOADING_DOTS_HEIGHT_DP = 12f

    // ------------------------------------------------------------------
    // Tool result image strips (CMW:107-109)
    // ------------------------------------------------------------------

    /** kToolImageCardHeight = 180 —— boxed 工具卡图片条高度。 */
    const val TOOL_IMAGE_CARD_HEIGHT_DP = 180f

    /** kToolImageCardMaxWidth = 320 —— boxed 工具卡单图最大宽度。 */
    const val TOOL_IMAGE_CARD_MAX_WIDTH_DP = 320f

    /** kToolImageTimelineHeight = 120 —— 时间线工具步图片条高度。 */
    const val TOOL_IMAGE_TIMELINE_HEIGHT_DP = 120f

    /** kToolImageTimelineMaxWidth = 240 —— 时间线工具步单图最大宽度。 */
    const val TOOL_IMAGE_TIMELINE_MAX_WIDTH_DP = 240f

    // ------------------------------------------------------------------
    // Chain-of-thought timeline (CMW:4445-4452 / 4665-4671 / 5148-5167)
    // ------------------------------------------------------------------

    /** 步骤头行上下内边距 8（_timelineStepPaddingV）。 */
    const val TIMELINE_STEP_PADDING_V_DP = 8f

    /** 图标 18 放在宽 24 的轨道列里（_timelineIconSize / _timelineIconColumnWidth）。 */
    const val TIMELINE_ICON_DP = 18f
    const val TIMELINE_ICON_COLUMN_WIDTH_DP = 24f

    /** 轨道列 → 正文的水平间距 8（_timelineGap）。 */
    const val TIMELINE_GAP_DP = 8f

    /** 连接线在图标上下各留 3px 缺口，线宽 1，x=(24-1)/2=11.5。 */
    const val TIMELINE_LINE_GAP_DP = 3f
    const val TIMELINE_LINE_WIDTH_DP = 1f
    const val TIMELINE_LINE_X_DP = 11.5f

    /** 正文缩进 = 轨道宽 + 间距（CMW:4913）。 */
    const val TIMELINE_CONTENT_INSET_DP = 32f

    /** 思考卡容器：r16、h8/v4、primaryContainer α0.25(dark)/0.30(light)。 */
    const val TIMELINE_CARD_CORNER_DP = 16f
    const val TIMELINE_CARD_PADDING_H_DP = 8f
    const val TIMELINE_CARD_PADDING_V_DP = 4f
    const val TIMELINE_CARD_ALPHA_DARK = 0.25f
    const val TIMELINE_CARD_ALPHA_LIGHT = 0.30f

    /** 「深度思考 / 工具」标题 13 SemiBold；思考正文 12.5 / 行高 1.32。 */
    const val TIMELINE_LABEL_SP = 13f
    const val TIMELINE_BODY_SP = 12.5f
    const val TIMELINE_BODY_LINE_HEIGHT_SP = 16.5f

    /** 折叠预览高度 100，顶部淡出 12、底部淡出 28（CMW:5167-5173）。 */
    const val TIMELINE_PREVIEW_MAX_HEIGHT_DP = 100f
    const val TIMELINE_PREVIEW_FADE_TOP_DP = 12f
    const val TIMELINE_PREVIEW_FADE_BOTTOM_DP = 28f

    // ------------------------------------------------------------------
    // Input bar (CIB)
    // ------------------------------------------------------------------

    /** CIB:3264-3315 — send button 32 circle (icon 18 + padding 7×2). */
    const val SEND_BUTTON_DP = 32f
    const val SEND_ICON_DP = 18f
    const val SEND_DISABLED_BG_ALPHA = 0.12f
    const val SEND_DISABLED_FG_ALPHA = 0.38f

    /** CIB:3264-3327 `AnimatedSwitcher` — 200ms scale+fade between send/stop. */
    const val SEND_ICON_SWITCH_MS = 200

    /**
     * `assets/icons/stop.svg` — a 14×14 rounded square (rx 2) inside a 24
     * viewBox. Kept as SVG-space numbers so the shape scales exactly like the
     * original instead of being approximated by a stock icon.
     */
    const val STOP_SVG_VIEWBOX_DP = 24f
    const val STOP_SVG_SIDE_DP = 14f
    const val STOP_SVG_RADIUS_DP = 2f

    /** CIB composer — text 15sp (mobile), hint onSurface@0.45. */
    const val INPUT_TEXT_SP = 15f
    const val INPUT_HINT_ALPHA = 0.45f

    /** CIB:3211-3213 — compact icon color: onSurface@0.70 dark / 0.54 light. */
    const val COMPACT_ICON_ALPHA_LIGHT = 0.54f
    const val COMPACT_ICON_ALPHA_DARK = 0.70f

    /** CIB:2891+2912+2928 — 8px gaps between action icons / right-side buttons. */
    const val INPUT_ACTIONS_GAP_DP = 8f

    // ------------------------------------------------------------------
    // Top bar (home_page.dart mobile)
    // ------------------------------------------------------------------

    /** Mini-map icon renders at 20px (new-chat stays 22). */
    const val TOP_BAR_MAP_ICON_DP = 20f

    /** Trailing 4px tail after the last top-bar action. */
    const val TOP_BAR_TRAILING_GAP_DP = 4f

    // ------------------------------------------------------------------
    // Voice waveform (chat_input_bar.dart _VoiceWaveformPainter:3400-3459)
    // ------------------------------------------------------------------

    const val WAVE_BAR_WIDTH_DP = 3f
    const val WAVE_BAR_GAP_DP = 3.5f

    /** maxH = size.height * 0.92 (CIB:3417). */
    const val WAVE_MAX_HEIGHT_RATIO = 0.92f

    /** Bars never render shorter than 2px (CIB:3447). */
    const val WAVE_MIN_BAR_HEIGHT_DP = 2f

    /**
     * 录音行波形槽位高度 —— 上游容器的 SizedBox(height: 32)（CIB:866-870），且
     * AnimatedSwitcher 用 StackFit.expand 把内容撑满。Compose 的 Canvas 本体是
     * Spacer：高度约束宽松（min=0）时测量为 0 → maxH=0 → 所有条贴 2px 最小值，
     * 视觉就是「波形出现但永远不动」（用户 2026-09-16 二次实测）。组件内以此
     * 兜底高度；调用方显式给 height 时其约束优先生效。
     */
    const val WAVE_SLOT_HEIGHT_DP = 32f

    /**
     * Capsule cross-section envelope (CIB:3433-3446): with r = maxBarHeight/2,
     * return sqrt(max(0, r² − (r − dCenter)²)) / r when dCenter < r else 1.
     * [dCenter] is the bar-center distance to the nearest edge in px.
     */
    fun voiceWaveformEnvelope(dCenter: Float, maxBarHeightPx: Float): Float {
        val radius = maxBarHeightPx / 2f
        if (dCenter >= radius) return 1f
        val inner = radius * radius - (radius - dCenter) * (radius - dCenter)
        return kotlin.math.sqrt(kotlin.math.max(0f, inner)) / radius
    }

    /** Bar height = max(minPx, maxH · level · envelope(dCenter, maxH)) (CIB:3447). */
    fun voiceWaveformBarHeight(level: Float, dCenter: Float, maxBarHeightPx: Float, minBarHeightPx: Float): Float {
        val clamped = level.coerceIn(0f, 1f)
        return kotlin.math.max(minBarHeightPx, maxBarHeightPx * clamped * voiceWaveformEnvelope(dCenter, maxBarHeightPx))
    }
}

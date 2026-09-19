package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psyche.memo.common.Haptics
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.alphaBlend
import com.psyche.memo.ui.theme.alphaBlendTranslucent
import com.psyche.memo.ui.theme.estimateBrightnessIsLight
import com.psyche.memo.ui.theme.lerpColor
import com.psyche.memo.ui.theme.withAlpha
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.psyche.memo.ui.theme.AppFontWeights

/**
 * Port of `lib/shared/widgets/ios_switch.dart` — 44x26 track, 20 thumb, 220ms
 * easeOutCubic, no Material ripple, press scale 0.98.
 *
 * The source animates the decoration (`AnimatedContainer`) and the thumb
 * (`AnimatedAlign`) on the same duration and curve, so one progress value drives
 * the track color, the OFF ring and the thumb offset here.
 */
@Composable
fun IosSwitch(
    value: Boolean,
    onValueChanged: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    width: Dp = 44.dp,
    height: Dp = 26.dp,
    activeColor: Color? = null,
    inactiveColor: Color? = null,
    thumbColor: Color? = null,
    enableHaptics: Boolean = true,
    semanticLabel: String? = null,
    hitTestSize: Dp = 44.dp,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = LocalSemanticColors.current.isDark
    val view = LocalView.current
    val settings = LocalHapticsSettings.current
    val enabled = onValueChanged != null

    val onTrack = activeColor ?: cs.primary
    val offTrack = inactiveColor ?: IosSwitchColors.offTrack(cs, isDark)
    val ring = IosSwitchColors.trackBorder(cs, isDark, enabled)
    val thumb = thumbColor ?: IosSwitchColors.thumb(cs, isDark, value)

    val radius = height / 2
    val thumbSize = height - 6.dp
    val travel = width - 6.dp - thumbSize

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.98f else 1f,
        animationSpec = tween(durationMillis = 100, easing = EaseOut),
        label = "iosSwitchPress",
    )
    val progress by animateFloatAsState(
        targetValue = if (value) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = EaseOutCubic),
        label = "iosSwitchTrack",
    )
    val ringWidth = 1f - progress

    Box(
        modifier = modifier
            .size(maxOf(width, hitTestSize), maxOf(height, hitTestSize))
            .toggleable(
                value = value,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interaction,
                indication = null,
                onValueChange = { next ->
                    // ios_switch.dart L172-183: the soft tick is gated by the
                    // per-widget flag *and* the settings toggle.
                    if (enableHaptics && settings.iosSwitch) Haptics.soft(view)
                    onValueChanged?.invoke(next)
                },
            )
            .then(
                if (semanticLabel != null) {
                    Modifier.semantics { contentDescription = semanticLabel }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(radius)
        Box(
            modifier = Modifier
                .size(width, height)
                .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                .background(lerpColor(offTrack, onTrack, progress.toDouble()), shape)
                // BoxDecoration.lerp scales a null border to BorderSide.none, so
                // the ring shrinks to nothing rather than staying at 1dp.
                .then(
                    if (ringWidth > 0f) {
                        Modifier.border(BorderStroke(ringWidth.dp, ring), shape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .padding(3.dp)
                    // Lambda overload: the offset is re-read per frame without
                    // recomposing the thumb on every progress tick.
                    .offset { IntOffset((travel * progress).roundToPx(), 0) }
                    .size(thumbSize)
                    .background(
                        if (enabled) thumb else withAlpha(thumb, 0.7),
                        CircleShape,
                    ),
            )
        }
    }
}

/**
 * Port of `lib/shared/widgets/ios_checkbox.dart` — circular, 22 visual / 32 hit
 * by default, check path drawn progressively over 220ms easeOutBack.
 */
@Composable
fun IosCheckbox(
    value: Boolean,
    onValueChanged: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    activeColor: Color? = null,
    borderColor: Color? = null,
    checkmarkColor: Color? = null,
    semanticLabel: String? = null,
    enableHaptics: Boolean = true,
    hitTestSize: Dp = 32.dp,
    borderWidth: Dp = 2.dp,
    interactive: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = LocalSemanticColors.current.isDark
    val view = LocalView.current
    val enabled = onValueChanged != null

    val active = activeColor ?: IosCheckboxColors.cupertinoPrimary(isDark)
    val ring = borderColor ?: IosCheckboxColors.ring(cs, isDark)
    val check = IosCheckboxColors.checkmark(cs, activeColor, checkmarkColor)

    val fill = if (enabled) active else withAlpha(active, 0.5)
    val ringOff = if (enabled) ring else withAlpha(ring, 0.5)
    val checkColor = withAlpha(check, if (enabled) 1.0 else 0.5)

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.95f else 1f,
        animationSpec = tween(durationMillis = 100, easing = EaseOut),
        label = "iosCheckboxPress",
    )
    val progress by animateFloatAsState(
        targetValue = if (value) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = EaseOutCubic),
        label = "iosCheckboxFill",
    )

    Box(
        modifier = modifier
            .size(maxOf(hitTestSize, size))
            // `interactive = false` is the source's `IgnorePointer` wrapper
            // (sidebar_selection_bars.dart L85, side_drawer.dart L4425): the
            // checkbox keeps a non-null onChanged so its colors stay at full
            // enabled opacity, but the tap belongs to the row behind it.
            .then(
                if (interactive) {
                    Modifier.toggleable(
                        value = value,
                        enabled = enabled,
                        role = Role.Checkbox,
                        interactionSource = interaction,
                        indication = null,
                        onValueChange = { next ->
                            // ios_checkbox.dart L139-144: lightImpact, *not* gated
                            // by a settings toggle the way IosSwitch is.
                            if (enableHaptics) Haptics.light(view)
                            onValueChanged?.invoke(next)
                        },
                    )
                } else {
                    Modifier
                },
            )
            .then(
                if (semanticLabel != null) {
                    Modifier.semantics { contentDescription = semanticLabel }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                .background(lerpColor(Color.Transparent, fill, progress.toDouble()), CircleShape)
                .border(
                    BorderStroke(borderWidth, lerpColor(ringOff, fill, progress.toDouble())),
                    CircleShape,
                ),
        ) {
            AnimatedCheck(
                show = value,
                color = checkColor,
                strokeWidth = maxOf(2.dp, borderWidth + 0.5.dp),
            )
        }
    }
}

/// `_AnimatedCheck` + `_CheckPainter` (ios_checkbox.dart L147-227).
@Composable
private fun AnimatedCheck(show: Boolean, color: Color, strokeWidth: Dp) {
    val progress by animateFloatAsState(
        targetValue = if (show) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = EaseOutBack),
        label = "iosCheckboxCheck",
    )
    if (progress <= 0f) return
    val measure = remember { PathMeasure() }
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val scale = 0.9f + 0.1f * progress
                scaleX = scale
                scaleY = scale
            },
    ) {
        val path = Path().apply {
            moveTo(0.28f * size.width, 0.52f * size.height)
            lineTo(0.46f * size.width, 0.70f * size.height)
            lineTo(0.75f * size.width, 0.34f * size.height)
        }
        measure.setPath(path, false)
        val length = measure.length
        if (length <= 0f) return@Canvas
        val segment = Path()
        // easeOutBack overshoots past 1; getSegment clamps to the path length.
        measure.getSegment(0f, length * progress, segment, true)
        drawPath(
            path = segment,
            color = color,
            style = Stroke(
                width = strokeWidth.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

/**
 * Port of `lib/shared/widgets/ios_tile_button.dart` — icon + label pill, radius
 * 12, 160ms easeOutCubic press wash, light haptic on tap.
 */
@Composable
fun IosTileButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fontSize: TextUnit = 14.sp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    backgroundColor: Color? = null,
    foregroundColor: Color? = null,
    borderColor: Color? = null,
) {
    val cs = MaterialTheme.colorScheme
    val appColors = LocalSemanticColors.current
    val view = LocalView.current

    val tinted = backgroundColor != null
    val tint = backgroundColor ?: cs.primary
    val base = IosTileColors.background(appColors.surfaceFill, appColors.isDark, tint, tinted)
    val pressedBackground = IosTileColors.pressed(cs, appColors.isDark, tint, tinted, base)
    val foreground = IosTileColors.foreground(cs, tinted, tint, foregroundColor)
    val outline = IosTileColors.border(cs, appColors.isDark, tinted, tint, borderColor)

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val progress by animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = tween(durationMillis = 160, easing = EaseOutCubic),
        label = "iosTilePress",
    )

    val shape = RoundedCornerShape(MemoRadius.PILL_DP.dp)
    val content = if (enabled) foreground else withAlpha(foreground, 0.45)
    Row(
        modifier = modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    Haptics.light(view)
                    onClick()
                },
            )
            .background(lerpColor(base, pressedBackground, progress.toDouble()), shape)
            .border(
                BorderStroke(1.dp, if (enabled) outline else withAlpha(outline, 0.45)),
                shape,
            )
            .padding(contentPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier
                .padding(start = 2.dp)
                .size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = TextStyle(
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                color = content,
            ),
        )
    }
}

/**
 * `_TactileIconButton` (providers_page.dart L1752-1806): icon-only AppBar
 * button, no ripple, scale 0.95 + alpha 0.7 on press, light haptic on tap.
 */
@Composable
fun TactileIconButton(
    icon: ImageVector,
    color: Color,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 100, easing = EaseOut),
        label = "tactileIconScale",
    )
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = {
                    Haptics.light(view)
                    onTap()
                },
            )
            .padding(6.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (pressed) withAlpha(color, 0.7) else color,
            modifier = Modifier.size(size),
        )
    }
}

/**
 * `_TactileRow` (providers_page.dart L1808-1849): row wrapper with no ripple,
 * color-only press feedback and a soft haptic gated by `hapticsOnListItemTap`.
 *
 * The source also declares `pressedScale` but never reads it — its build method
 * has no scale transform — so it is not ported.
 */
@Composable
fun TactileRow(
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    haptics: Boolean = true,
    content: @Composable (pressed: Boolean) -> Unit,
) {
    val view = LocalView.current
    val settings = LocalHapticsSettings.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier.then(
            if (onTap == null) {
                Modifier
            } else {
                Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = {
                        if (haptics && settings.onListItemTap) Haptics.soft(view)
                        onTap()
                    },
                )
            },
        ),
    ) {
        content(pressed)
    }
}

/**
 * `_AnimatedPressColor` (providers_page.dart L1851-1873): tweens [base] 55% of
 * the way to the scheme surface while pressed, 220ms easeOutCubic.
 */
@Composable
fun AnimatedPressColor(
    pressed: Boolean,
    base: Color,
    content: @Composable (Color) -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surface
    val progress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = EaseOutCubic),
        label = "animatedPressColor",
    )
    content(lerpColor(base, lerpColor(base, surface, 0.55), progress.toDouble()))
}

/** ios_switch.dart L71-75 / L93-100 / L107-119. */
internal object IosSwitchColors {
    fun offTrack(cs: ColorScheme, isDark: Boolean): Color =
        if (isDark) {
            alphaBlend(cs.onSurface, 0.02, cs.surface)
        } else {
            withAlpha(cs.onSurface, 0.08)
        }

    fun thumb(cs: ColorScheme, isDark: Boolean, on: Boolean): Color = when {
        !isDark -> cs.surfaceContainerLowest
        on -> alphaBlend(cs.onSurface, 0.02, cs.surface)
        else -> alphaBlend(cs.onSurface, 0.36, cs.surface)
    }

    fun trackBorder(cs: ColorScheme, isDark: Boolean, enabled: Boolean): Color =
        withAlpha(
            cs.onSurface,
            (if (isDark) 0.24 else 0.20) * (if (enabled) 0.65 else 0.35),
        )
}

/** ios_checkbox.dart L51-83. */
internal object IosCheckboxColors {
    /**
     * `CupertinoTheme.of(context).primaryColor` with no `CupertinoTheme`
     * ancestor — the app never installs one — resolves through
     * `_kDefaultTheme.primaryColor` to `CupertinoColors.systemBlue`.
     */
    fun cupertinoPrimary(isDark: Boolean): Color =
        if (isDark) Color(0xFF0A84FF) else Color(0xFF007AFF)

    fun ring(cs: ColorScheme, isDark: Boolean): Color =
        withAlpha(cs.onSurface, if (isDark) 0.24 else 0.20)

    fun checkmark(cs: ColorScheme, activeColor: Color?, checkmarkColor: Color?): Color =
        checkmarkColor ?: (activeColor?.let(::contrastOn) ?: cs.onPrimary)

    /// `contrastOn` (ios_checkbox.dart L68-74).
    fun contrastOn(background: Color): Color =
        if (estimateBrightnessIsLight(background)) Color.Black else Color.White
}

/** ios_tile_button.dart L42-63. */
internal object IosTileColors {
    fun background(surfaceFill: Color, isDark: Boolean, tint: Color, tinted: Boolean): Color =
        if (tinted) withAlpha(tint, if (isDark) 0.20 else 0.12) else surfaceFill

    /**
     * `Color.alphaBlend(overlay, baseBg)` — [base] is [background]'s result, and
     * the tinted variant is translucent so the general blend branch applies.
     */
    fun pressed(
        cs: ColorScheme,
        isDark: Boolean,
        tint: Color,
        tinted: Boolean,
        base: Color,
    ): Color {
        val overlayAlpha = if (isDark) 0.06 else 0.05
        return if (tinted) {
            alphaBlendTranslucent(
                cs.onSurface,
                overlayAlpha,
                tint,
                if (isDark) 0.20 else 0.12,
            )
        } else {
            alphaBlend(cs.onSurface, overlayAlpha, base)
        }
    }

    fun foreground(
        cs: ColorScheme,
        tinted: Boolean,
        tint: Color,
        foregroundColor: Color?,
    ): Color = foregroundColor ?: (if (tinted) tint else withAlpha(cs.onSurface, 0.9))

    fun border(
        cs: ColorScheme,
        isDark: Boolean,
        tinted: Boolean,
        tint: Color,
        borderColor: Color?,
    ): Color = borderColor
        ?: (
            if (tinted) {
                withAlpha(tint, if (isDark) 0.55 else 0.45)
            } else {
                withAlpha(cs.outlineVariant, 0.35)
            }
            )
}

/**
 * Port of `_IosButton` (assistant_settings_edit_page.dart L1436-1530): r12
 * surfaceFill pill (primary when `filled`) with a 0.35 outlineVariant ring, or
 * 0.45 primary when `neutral` is false; 18dp icon + 8 gap + semibold label at
 * 14sp (13sp when `dense`, which also tightens padding to 12/8). Press scales to
 * 0.97 over 110ms easeOutCubic and fires a soft haptic.
 */
@Composable
fun IosButton(
    label: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    filled: Boolean = false,
    neutral: Boolean = true,
    dense: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(durationMillis = 110, easing = EaseOutCubic),
        label = "iosButtonScale",
    )
    val background = if (filled) cs.primary else semantic.surfaceFill
    val foreground = if (filled) {
        cs.onPrimary
    } else if (neutral) {
        withAlpha(cs.onSurface, 0.9)
    } else {
        cs.primary
    }
    val iconColor = if (filled) {
        cs.onPrimary
    } else if (neutral) {
        withAlpha(cs.onSurface, 0.75)
    } else {
        cs.primary
    }
    val ringColor = if (neutral) withAlpha(cs.outlineVariant, 0.35) else withAlpha(cs.primary, 0.45)
    var row: Modifier = Modifier
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .background(background, RoundedCornerShape(MemoRadius.PILL_DP.dp))
    if (!filled) row = row.border(BorderStroke(1.dp, ringColor), RoundedCornerShape(MemoRadius.PILL_DP.dp))
    Row(
        modifier = modifier
            .then(row)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = {
                    Haptics.soft(view)
                    onTap()
                },
            )
            .padding(
                horizontal = if (dense) 12.dp else 16.dp,
                vertical = if (dense) 8.dp else 12.dp,
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = label,
            maxLines = 1,
            style = TextStyle(
                fontSize = if (dense) 13.sp else 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = foreground,
            ),
        )
    }
}

/**
 * Port of the per-page `_IosOutlineButton` / `_IosFilledButton` pairs that the
 * sheets declare locally (quick_phrases_page.dart L573-680,
 * instruction_injection_page.dart L836-940, world_book_page.dart L1999-2107).
 * They share the r12 / vertical-12 padding / 0.97 press-scale shape but differ
 * in ring and label colour, so the variants are knobs instead of separate
 * widgets: [accentOutline] = primary ring + primary label (quick phrase),
 * [useSurfaceFill] = surfaceFill fill + outlineVariant ring + onSurface label
 * (world book), plain = transparent + outlineVariant ring (instruction
 * injection). The caller stretches it (Expanded in the source), so it has no
 * intrinsic width.
 */
@Composable
fun IosSheetButton(
    label: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    accentOutline: Boolean = false,
    useSurfaceFill: Boolean = false,
    fontSize: TextUnit = 14.sp,
    enabled: Boolean = true,
    lightHaptic: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = tween(durationMillis = 110, easing = EaseOutCubic),
        label = "iosSheetButtonScale",
    )
    val background = when {
        filled -> if (enabled) cs.primary else withAlpha(cs.primary, 0.4)
        useSurfaceFill -> semantic.surfaceFill
        else -> Color.Transparent
    }
    val foreground = when {
        filled -> withAlpha(cs.onPrimary, if (enabled) 1.0 else 0.6)
        accentOutline -> cs.primary
        else -> cs.onSurface
    }
    val ringColor = when {
        filled -> null
        accentOutline -> withAlpha(cs.primary, 0.5)
        useSurfaceFill -> withAlpha(cs.outlineVariant, 0.35)
        else -> withAlpha(cs.outlineVariant, 0.4)
    }
    var box: Modifier = Modifier
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .background(background, RoundedCornerShape(MemoRadius.PILL_DP.dp))
    if (ringColor != null) box = box.border(BorderStroke(1.dp, ringColor), RoundedCornerShape(MemoRadius.PILL_DP.dp))
    Box(
        modifier = modifier
            .then(box)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = {
                    if (lightHaptic) Haptics.light(view) else Haptics.soft(view)
                    onTap()
                },
            )
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            maxLines = 1,
            style = TextStyle(
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                color = foreground,
            ),
        )
    }
}

/**
 * Port of `IosIconButton` (lib/shared/widgets/ios_tactile.dart L9-160), mobile
 * subset: the desktop hover ring and mouse cursor are not reachable by touch.
 *
 * Press shifts the icon 35% toward onSurface over 200ms easeOutCubic and tints an
 * r8 background to onSurface 8% (light) / 12% (dark) over 160ms — the source has
 * no scale transform here, unlike [TactileIconButton].
 */
@Composable
fun IosIconButton(
    icon: ImageVector,
    onTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    size: Dp = 20.dp,
    contentPadding: Dp = 6.dp,
    minSize: Dp? = null,
    semanticLabel: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val clickable = onTap != null
    val tint by animateColorAsState(
        targetValue = if (pressed && clickable) lerpColor(color, cs.onSurface, 0.35) else color,
        animationSpec = tween(durationMillis = 200, easing = EaseOutCubic),
        label = "iosIconColor",
    )
    val backdrop by animateColorAsState(
        targetValue = if (pressed && clickable) {
            withAlpha(cs.onSurface, if (isDark) 0.12 else 0.08)
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 160, easing = EaseOutCubic),
        label = "iosIconBackdrop",
    )
    Box(
        modifier = modifier
            .then(if (minSize != null) Modifier.defaultMinSize(minWidth = minSize, minHeight = minSize) else Modifier)
            .background(backdrop, RoundedCornerShape(MemoRadius.SMALL_DP.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                enabled = clickable,
                onClick = { onTap?.invoke() },
            )
            .semantics { this.contentDescription = semanticLabel.orEmpty() }
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size),
        )
    }
}

/**
 * IosIconButton's `builder:` variant (ios_tactile.dart L9-160): identical press
 * feedback, but the caller draws the content — used by the translate app bar's
 * model brand logo, which is an asset rather than a vector icon.
 */
@Composable
fun IosIconContentButton(
    onTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    contentPadding: Dp = 6.dp,
    semanticLabel: String? = null,
    content: @Composable (Color) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val clickable = onTap != null
    val tint by animateColorAsState(
        targetValue = if (pressed && clickable) lerpColor(color, cs.onSurface, 0.35) else color,
        animationSpec = tween(durationMillis = 200, easing = EaseOutCubic),
        label = "iosIconContentColor",
    )
    val backdrop by animateColorAsState(
        targetValue = if (pressed && clickable) {
            withAlpha(cs.onSurface, if (isDark) 0.12 else 0.08)
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 160, easing = EaseOutCubic),
        label = "iosIconContentBackdrop",
    )
    Box(
        modifier = modifier
            .background(backdrop, RoundedCornerShape(MemoRadius.SMALL_DP.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                enabled = clickable,
                onClick = { onTap?.invoke() },
            )
            .semantics { this.contentDescription = semanticLabel.orEmpty() }
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        content(tint)
    }
}

/**
 * `IosFormTextField` (shared/widgets/ios_form_text_field.dart) — the filled
 * label/value field every settings sheet uses. [inline] is the single-line
 * layout (label left, value box right); `inline = false` stacks the label above
 * a 12dp box that grows with the text (used by the multi-line editors).
 *
 * `_useInlineLabel` in the source defaults to `maxLines == 1`, and the value
 * colour/hint alpha, the 12/10 outer padding and the 15sp `w400` (the Android
 * normalization of `w500`) text style match it.
 */
@Composable
internal fun IosFormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    inline: Boolean = true,
    fieldWidth: Dp? = null,
    minLines: Int = 1,
    maxLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    autofocus: Boolean = false,
    hint: String? = null,
    textAlign: TextAlign = TextAlign.Start,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(autofocus) {
        if (autofocus) runCatching { focusRequester.requestFocus() }
    }

    val field: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = maxLines == 1,
                    minLines = minLines,
                    maxLines = maxLines,
                    textStyle = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = AppFontWeights.medium,
                        color = withAlpha(cs.onSurface, 0.92),
                        lineHeight = if (maxLines > 1) 18.75.sp else 17.25.sp,
                        textAlign = textAlign,
                    ),
                    cursorBrush = SolidColor(cs.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    visualTransformation = visualTransformation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (autofocus) Modifier.focusRequester(focusRequester) else Modifier),
                )
                if (hint != null && value.isEmpty()) {
                    Text(
                        text = hint,
                        maxLines = maxLines,
                        style = TextStyle(
                            fontSize = 15.sp,
                            fontWeight = AppFontWeights.medium,
                            color = withAlpha(cs.onSurface, if (semantic.isDark) 0.42 else 0.46),
                        ),
                    )
                }
            }
            trailing?.let {
                Spacer(Modifier.width(6.dp))
                it()
            }
        }
    }

    if (inline) {
        Row(
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = AppFontWeights.medium,
                    color = withAlpha(cs.onSurface, 0.85),
                ),
                modifier = if (fieldWidth == null) Modifier.weight(3f) else Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = (if (fieldWidth == null) Modifier.weight(8f) else Modifier.width(fieldWidth))
                    .heightIn(min = 40.dp)
                    .background(semantic.surfaceCardFill, RoundedCornerShape(MemoRadius.SMALL_DP.dp))
                    .padding(
                        horizontal = if (fieldWidth != null && fieldWidth <= 60.dp) 10.dp else 12.dp,
                        vertical = 9.dp,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                field()
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (label.isNotEmpty()) {
                Text(
                    text = label,
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = AppFontWeights.semibold,
                        color = withAlpha(cs.onSurface, 0.85),
                    ),
                )
                Spacer(Modifier.height(6.dp))
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(semantic.surfaceCardFill, RoundedCornerShape(MemoRadius.CARD_DP.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                field()
            }
        }
    }
}

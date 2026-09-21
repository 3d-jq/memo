package com.psyche.memo.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * dart:ui `Color` arithmetic, ported channel-by-channel instead of delegated to
 * Compose: `lerp` and `compositeOver` mix in float32 and land a channel off the
 * engine's double-precision result (0.05 over 0xFFF7F7F7 gives 235 instead of
 * 236), which is visible as a seam between two surfaces that Flutter paints
 * identically.
 */
private fun channel(color: Color, shift: Int): Int = (color.toArgb() shr shift) and 0xFF

private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Color =
    Color((alpha shl 24) or (red shl 16) or (green shl 8) or blue)

/**
 * dart:ui `Color.withValues(alpha:)` — replaces alpha and keeps the channels.
 * Used wherever the Flutter widgets dim a color instead of fading it.
 */
fun withAlpha(base: Color, alpha: Double): Color =
    Color((base.toArgb() and 0x00FFFFFF) or (quantize(alpha) shl 24))

/** dart:ui `Color.alphaBlend(fg, bg)` (painting.dart L448), opaque-background branch. */
fun alphaBlend(fg: Color, fgAlpha: Double, bg: Color): Color {
    if (fgAlpha == 0.0) return bg
    val invAlpha = 1.0 - fgAlpha
    return argb(
        0xFF,
        mix(fg, bg, 16) { f, b -> fgAlpha * f + invAlpha * b },
        mix(fg, bg, 8) { f, b -> fgAlpha * f + invAlpha * b },
        mix(fg, bg, 0) { f, b -> fgAlpha * f + invAlpha * b },
    )
}

/**
 * dart:ui `Color.alphaBlend(fg, bg)` (painting.dart L448), general branch: both
 * operands carry alpha, so the channels are divided back out of the result.
 * [fgAlpha] and [bgAlpha] are passed separately because `withValues(alpha: 0.05)`
 * keeps the literal 0.05 while an 8-bit round trip would hand the blend
 * 13/255 = 0.05098 and shift the mixed channels by one.
 */
fun alphaBlendTranslucent(fg: Color, fgAlpha: Double, bg: Color, bgAlpha: Double): Color {
    if (fgAlpha == 0.0) return withAlpha(bg, bgAlpha)
    if (bgAlpha == 1.0) return alphaBlend(fg, fgAlpha, bg)
    val backAlpha = bgAlpha * (1.0 - fgAlpha)
    val outAlpha = fgAlpha + backAlpha
    return argb(
        quantize(outAlpha),
        mix(fg, bg, 16) { f, b -> (f * fgAlpha + b * backAlpha) / outAlpha },
        mix(fg, bg, 8) { f, b -> (f * fgAlpha + b * backAlpha) / outAlpha },
        mix(fg, bg, 0) { f, b -> (f * fgAlpha + b * backAlpha) / outAlpha },
    )
}

/**
 * 多选动作芯片的底色：上游 `sidebar_selection_bars.dart:231-234` 与
 * `chat_selection_export_bar.dart:169-172` 同一条式子
 * `Color.alphaBlend(onSurface.withValues(0.04), color.withValues(isDark ? 0.18 : 0.14))`。
 * 两个操作数都带 alpha，所以结果是一颗**半透明**淡染色，叠在栏底（78% surface）上；
 * 染色后的字与图标仍用原色，因此底色绝不能是不透明的深色——那样同色系的文字就糊了。
 */
fun selectionChipColor(onSurface: Color, color: Color, isDark: Boolean): Color =
    alphaBlendTranslucent(
        fg = onSurface,
        fgAlpha = 0.04,
        bg = color,
        bgAlpha = if (isDark) 0.18 else 0.14,
    )

/** dart:ui `Color.lerp(a, b, t)` (painting.dart L393) — linear per channel, alpha included. */
fun lerpColor(a: Color, b: Color, t: Double): Color =
    argb(
        mix(a, b, 24) { x, y -> x + (y - x) * t },
        mix(a, b, 16) { x, y -> x + (y - x) * t },
        mix(a, b, 8) { x, y -> x + (y - x) * t },
        mix(a, b, 0) { x, y -> x + (y - x) * t },
    )

private inline fun mix(a: Color, b: Color, shift: Int, blend: (Double, Double) -> Double): Int =
    quantize(blend(component(a, shift), component(b, shift)))

private fun component(color: Color, shift: Int): Double = channel(color, shift) / 255.0

private fun quantize(component: Double): Int =
    (component * 255).roundToInt().coerceIn(0, 255)

/// dart:ui `Color._linearizeColorComponent` (painting.dart L371).
private fun linearize(component: Double): Double =
    if (component <= 0.03928) component / 12.92 else ((component + 0.055) / 1.055).pow(2.4)

/**
 * `ThemeData.estimateBrightnessForColor` (theme_data.dart L1773) — true when the
 * color is light enough to carry black text. `ios_checkbox.dart`'s `contrastOn`
 * branches on this to pick the checkmark color for a caller-supplied tint.
 */
fun estimateBrightnessIsLight(color: Color): Boolean {
    val luminance = 0.2126 * linearize(channel(color, 16) / 255.0) +
        0.7152 * linearize(channel(color, 8) / 255.0) +
        0.0722 * linearize(channel(color, 0) / 255.0)
    return (luminance + 0.05) * (luminance + 0.05) > 0.15
}

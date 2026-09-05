package com.psyche.memo.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.psyche.memo.ui.theme.hct.Hct

/** Shift [base] in HCT tone space, keeping hue and chroma. */
internal fun shiftTone(base: Color, delta: Double): Color {
    val hct = Hct.fromArgb(base.toArgb())
    return Color(Hct.from(hct.hue, hct.chroma, (hct.tone + delta).coerceIn(0.0, 100.0)).toArgb())
}

/** HCT tone of [color], 0–100. */
internal fun surfaceTone(color: Color): Double = Hct.fromArgb(color.toArgb()).tone

/** Set the HCT tone of [base], keeping hue and chroma. */
internal fun atTone(base: Color, tone: Double): Color {
    val hct = Hct.fromArgb(base.toArgb())
    return Color(Hct.from(hct.hue, hct.chroma, tone.coerceIn(0.0, 100.0)).toArgb())
}

/** Interpolate HCT tone from [from] toward [to], keeping [from]'s hue+chroma. */
internal fun lerpTone(from: Color, to: Color, t: Double): Color =
    atTone(from, surfaceTone(from) + (surfaceTone(to) - surfaceTone(from)) * t)

/**
 * Port of `lib/theme/surface_ladder.dart`.
 *
 * The palette-declared `surface` is the card; the page is that color sunk 4
 * tones in light layered mode. All tokens and the `surfaceContainer*` roles are
 * derived from the **page** `ColorScheme.surface` after that rewrite.
 */
internal class SurfaceLadder(
    val page: Color,
    val card: Color,
    val surfaceFill: Color,
    val surfaceCardFill: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
) {
    companion object {
        fun fromScheme(cs: ColorScheme, dark: Boolean, layered: Boolean = false): SurfaceLadder {
            val page = cs.surface

            if (!layered) {
                fun over(c: Color, a: Double): Color = alphaBlend(c, a, page)
                val card = over(Color.White, if (dark) 0.10 else 0.96)
                val surfaceFill = over(cs.onSurface, if (dark) 0.16 else 0.05)
                return SurfaceLadder(
                    page = page,
                    card = card,
                    surfaceFill = surfaceFill,
                    surfaceCardFill = surfaceFill,
                    hairline = cs.outlineVariant.copy(alpha = if (dark) 0.08f else 0.06f),
                    hairlineStrong = cs.outlineVariant.copy(alpha = if (dark) 0.26f else 0.38f),
                    surfaceContainerLowest =
                        if (dark) over(Color.Black, 0.28) else over(Color.White, 0.72),
                    surfaceContainerLow = over(Color.White, if (dark) 0.03 else 0.55),
                    surfaceContainer = over(Color.White, if (dark) 0.045 else 0.35),
                    surfaceContainerHigh = over(Color.White, if (dark) 0.06 else 0.85),
                    surfaceContainerHighest =
                        if (dark) over(Color.White, 0.09) else over(cs.onSurface, 0.05),
                )
            }

            val card = if (dark) {
                shiftTone(page, 11.0)
            } else {
                val headroom = 100.0 - surfaceTone(page)
                if (headroom >= 4.5) shiftTone(page, 4.0) else shiftTone(page, -3.5)
            }
            val surfaceCardFill = shiftTone(card, if (dark) 9.0 else -3.2)
            val surfaceFill = shiftTone(page, if (dark) 9.0 else -2.5)
            val hairline = cs.onSurface.copy(alpha = if (dark) 0.12f else 0.08f)
            val hairlineStrong = cs.onSurface.copy(alpha = if (dark) 0.26f else 0.20f)

            return if (dark) {
                SurfaceLadder(
                    page = page,
                    card = card,
                    surfaceFill = surfaceFill,
                    surfaceCardFill = surfaceCardFill,
                    hairline = hairline,
                    hairlineStrong = hairlineStrong,
                    surfaceContainerLowest = shiftTone(page, -3.0),
                    surfaceContainerLow = shiftTone(page, 4.0),
                    surfaceContainer = shiftTone(page, 7.0),
                    surfaceContainerHigh = shiftTone(page, 11.0),
                    surfaceContainerHighest = shiftTone(page, 14.0),
                )
            } else {
                SurfaceLadder(
                    page = page,
                    card = card,
                    surfaceFill = surfaceFill,
                    surfaceCardFill = surfaceCardFill,
                    hairline = hairline,
                    hairlineStrong = hairlineStrong,
                    surfaceContainerLowest = atTone(page, 100.0),
                    surfaceContainerLow = lerpTone(page, card, 0.50),
                    surfaceContainer = lerpTone(page, card, 0.75),
                    surfaceContainerHigh = card,
                    surfaceContainerHighest = surfaceFill,
                )
            }
        }
    }
}

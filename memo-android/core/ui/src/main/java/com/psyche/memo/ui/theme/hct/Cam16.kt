package com.psyche.memo.ui.theme.hct

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Port of the forward CAM16 transform in `hct/cam16.dart`
 * (material_color_utilities 0.13.0), trimmed to the two components HCT reads.
 *
 * The inverse (`viewed` / `xyzInViewingConditions`), the CAM16-UCS coordinates
 * and `distance` are not ported: Memo only converts ARGB → HCT and back through
 * [HctSolver], never through CAM16.
 */
internal class Cam16 private constructor(
    val hue: Double,
    val chroma: Double,
) {
    companion object {
        fun fromInt(argb: Int): Cam16 = fromIntInViewingConditions(argb, ViewingConditions.SRGB)

        fun fromIntInViewingConditions(argb: Int, viewingConditions: ViewingConditions): Cam16 {
            val xyz = ColorUtils.xyzFromArgb(argb)
            return fromXyzInViewingConditions(xyz[0], xyz[1], xyz[2], viewingConditions)
        }

        fun fromXyzInViewingConditions(
            x: Double,
            y: Double,
            z: Double,
            viewingConditions: ViewingConditions,
        ): Cam16 {
            // Transform XYZ to 'cone'/'rgb' responses
            val rC = 0.401288 * x + 0.650173 * y - 0.051461 * z
            val gC = -0.250268 * x + 1.204414 * y + 0.045854 * z
            val bC = -0.002079 * x + 0.048952 * y + 0.953127 * z

            // Discount illuminant
            val rD = viewingConditions.rgbD[0] * rC
            val gD = viewingConditions.rgbD[1] * gC
            val bD = viewingConditions.rgbD[2] * bC

            // Chromatic adaptation
            val rAF = (viewingConditions.fl * abs(rD) / 100.0).pow(0.42)
            val gAF = (viewingConditions.fl * abs(gD) / 100.0).pow(0.42)
            val bAF = (viewingConditions.fl * abs(bD) / 100.0).pow(0.42)
            val rA = MathUtils.signum(rD) * 400.0 * rAF / (rAF + 27.13)
            val gA = MathUtils.signum(gD) * 400.0 * gAF / (gAF + 27.13)
            val bA = MathUtils.signum(bD) * 400.0 * bAF / (bAF + 27.13)

            // Redness-greenness / yellowness-blueness
            val a = (11.0 * rA + -12.0 * gA + bA) / 11.0
            val b = (rA + gA - 2.0 * bA) / 9.0

            // Auxiliary components
            val u = (20.0 * rA + 20.0 * gA + 21.0 * bA) / 20.0
            val p2 = (40.0 * rA + 20.0 * gA + bA) / 20.0

            val atan2Value = atan2(b, a)
            val atanDegrees = atan2Value * 180.0 / PI
            val hue = when {
                atanDegrees < 0 -> atanDegrees + 360.0
                atanDegrees >= 360 -> atanDegrees - 360
                else -> atanDegrees
            }

            // Achromatic response to color
            val ac = p2 * viewingConditions.nbb

            // CAM16 lightness
            val j = 100.0 * (ac / viewingConditions.aw).pow(
                viewingConditions.c * viewingConditions.z,
            )

            val huePrime = if (hue < 20.14) hue + 360 else hue
            val eHue = 0.25 * (cos(huePrime * PI / 180.0 + 2.0) + 3.8)
            val p1 = 50000.0 / 13.0 * eHue * viewingConditions.nC * viewingConditions.ncb
            val t = p1 * sqrt(a * a + b * b) / (u + 0.305)
            val alpha = t.pow(0.9) *
                (1.64 - 0.29.pow(viewingConditions.backgroundYTowhitePointY)).pow(0.73)

            // CAM16 chroma
            val c = alpha * sqrt(j / 100.0)
            return Cam16(hue, c)
        }
    }
}

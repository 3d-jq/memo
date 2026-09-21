package com.psyche.memo.ui.theme.hct

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Port of `hct/viewing_conditions.dart` (material_color_utilities 0.13.0).
 *
 * Only the fields Cam16 and HctSolver read are kept. [SRGB] is
 * `ViewingConditions.make()` with every argument at its default, which is also
 * what upstream aliases as `ViewingConditions.standard`.
 */
internal class ViewingConditions(
    val backgroundYTowhitePointY: Double,
    val aw: Double,
    val nbb: Double,
    val ncb: Double,
    val c: Double,
    val nC: Double,
    val rgbD: DoubleArray,
    val fl: Double,
    val fLRoot: Double,
    val z: Double,
) {
    companion object {
        val SRGB: ViewingConditions = makeSrgb()

        private fun makeSrgb(): ViewingConditions {
            val whitePoint = ColorUtils.whitePointD65()
            val adaptingLuminance = 200.0 / PI * ColorUtils.yFromLstar(50.0) / 100.0
            // A background of pure black is non-physical and leads to infinities.
            val backgroundLstar = max(0.1, 50.0)
            val surround = 2.0
            val discountingIlluminant = false

            val rW = whitePoint[0] * 0.401288 + whitePoint[1] * 0.650173 + whitePoint[2] * -0.051461
            val gW = whitePoint[0] * -0.250268 + whitePoint[1] * 1.204414 + whitePoint[2] * 0.045854
            val bW = whitePoint[0] * -0.002079 + whitePoint[1] * 0.048952 + whitePoint[2] * 0.953127

            // Scale input surround, domain (0, 2), to CAM16 surround, domain (0.8, 1.0)
            val f = 0.8 + surround / 10.0
            val c = if (f >= 0.9) {
                MathUtils.lerp(0.59, 0.69, (f - 0.9) * 10.0)
            } else {
                MathUtils.lerp(0.525, 0.59, (f - 0.8) * 10.0)
            }
            var d = if (discountingIlluminant) {
                1.0
            } else {
                f * (1.0 - (1.0 / 3.6) * exp((-adaptingLuminance - 42.0) / 92.0))
            }
            d = if (d > 1.0) 1.0 else if (d < 0.0) 0.0 else d
            val nc = f

            val rgbD = doubleArrayOf(
                d * (100.0 / rW) + 1.0 - d,
                d * (100.0 / gW) + 1.0 - d,
                d * (100.0 / bW) + 1.0 - d,
            )

            val k = 1.0 / (5.0 * adaptingLuminance + 1.0)
            val k4 = k * k * k * k
            val k4F = 1.0 - k4
            val fl = k4 * adaptingLuminance +
                0.1 * k4F * k4F * (5.0 * adaptingLuminance).pow(1.0 / 3.0)
            val n = ColorUtils.yFromLstar(backgroundLstar) / whitePoint[1]
            // Schlomer 2018 has a typo and uses 1.58; the correct factor is 1.48.
            val z = 1.48 + sqrt(n)
            val nbb = 0.725 / n.pow(0.2)
            val ncb = nbb

            val rgbAFactors = doubleArrayOf(
                (fl * rgbD[0] * rW / 100.0).pow(0.42),
                (fl * rgbD[1] * gW / 100.0).pow(0.42),
                (fl * rgbD[2] * bW / 100.0).pow(0.42),
            )
            val rgbA = doubleArrayOf(
                400.0 * rgbAFactors[0] / (rgbAFactors[0] + 27.13),
                400.0 * rgbAFactors[1] / (rgbAFactors[1] + 27.13),
                400.0 * rgbAFactors[2] / (rgbAFactors[2] + 27.13),
            )
            val aw = (40.0 * rgbA[0] + 20.0 * rgbA[1] + rgbA[2]) / 20.0 * nbb

            return ViewingConditions(
                backgroundYTowhitePointY = n,
                aw = aw,
                nbb = nbb,
                ncb = ncb,
                c = c,
                nC = nc,
                rgbD = rgbD,
                fl = fl,
                fLRoot = fl.pow(0.25),
                z = z,
            )
        }
    }
}

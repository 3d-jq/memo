package com.psyche.memo.ui.theme.hct

import kotlin.math.pow
import kotlin.math.round

/** Port of `utils/color_utils.dart` (material_color_utilities 0.13.0). */
internal object ColorUtils {
    private val SRGB_TO_XYZ = arrayOf(
        doubleArrayOf(0.41233895, 0.35762064, 0.18051042),
        doubleArrayOf(0.2126, 0.7152, 0.0722),
        doubleArrayOf(0.01932141, 0.11916382, 0.95034478),
    )

    private val XYZ_TO_SRGB = arrayOf(
        doubleArrayOf(3.2413774792388685, -1.5376652402851851, -0.49885366846268053),
        doubleArrayOf(-0.9691452513005321, 1.8758853451067872, 0.04156585616912061),
        doubleArrayOf(0.05562093689691305, -0.20395524564742123, 1.0571799111220335),
    )

    private val WHITE_POINT_D65 = doubleArrayOf(95.047, 100.0, 108.883)

    fun argbFromRgb(red: Int, green: Int, blue: Int): Int =
        (255 shl 24) or ((red and 255) shl 16) or ((green and 255) shl 8) or (blue and 255)

    fun argbFromLinrgb(linrgb: DoubleArray): Int =
        argbFromRgb(delinearized(linrgb[0]), delinearized(linrgb[1]), delinearized(linrgb[2]))

    fun redFromArgb(argb: Int): Int = (argb shr 16) and 255

    fun greenFromArgb(argb: Int): Int = (argb shr 8) and 255

    fun blueFromArgb(argb: Int): Int = argb and 255

    fun argbFromXyz(x: Double, y: Double, z: Double): Int {
        val m = XYZ_TO_SRGB
        return argbFromRgb(
            delinearized(m[0][0] * x + m[0][1] * y + m[0][2] * z),
            delinearized(m[1][0] * x + m[1][1] * y + m[1][2] * z),
            delinearized(m[2][0] * x + m[2][1] * y + m[2][2] * z),
        )
    }

    fun xyzFromArgb(argb: Int): DoubleArray {
        val r = linearized(redFromArgb(argb))
        val g = linearized(greenFromArgb(argb))
        val b = linearized(blueFromArgb(argb))
        return MathUtils.matrixMultiply(doubleArrayOf(r, g, b), SRGB_TO_XYZ)
    }

    fun argbFromLstar(lstar: Double): Int {
        val component = delinearized(yFromLstar(lstar))
        return argbFromRgb(component, component, component)
    }

    fun lstarFromArgb(argb: Int): Double = 116.0 * labF(xyzFromArgb(argb)[1] / 100.0) - 16.0

    fun yFromLstar(lstar: Double): Double = 100.0 * labInvf((lstar + 16.0) / 116.0)

    fun lstarFromY(y: Double): Double = labF(y / 100.0) * 116.0 - 16.0

    fun whitePointD65(): DoubleArray = WHITE_POINT_D65

    fun linearized(rgbComponent: Int): Double {
        val normalized = rgbComponent / 255.0
        return if (normalized <= 0.040449936) {
            normalized / 12.92 * 100.0
        } else {
            ((normalized + 0.055) / 1.055).pow(2.4) * 100.0
        }
    }

    fun delinearized(rgbComponent: Double): Int {
        val normalized = rgbComponent / 100.0
        val delinearized = if (normalized <= 0.0031308) {
            normalized * 12.92
        } else {
            1.055 * normalized.pow(1.0 / 2.4) - 0.055
        }
        // Dart rounds halves away from zero; Kotlin rounds toward +inf. Only
        // negative inputs differ, and clampInt maps those to 0 either way.
        return MathUtils.clampInt(0, 255, round(delinearized * 255.0).toInt())
    }

    private fun labF(t: Double): Double {
        val e = 216.0 / 24389.0
        val kappa = 24389.0 / 27.0
        return if (t > e) t.pow(1.0 / 3.0) else (kappa * t + 16) / 116
    }

    private fun labInvf(ft: Double): Double {
        val e = 216.0 / 24389.0
        val kappa = 24389.0 / 27.0
        val ft3 = ft * ft * ft
        return if (ft3 > e) ft3 else (116 * ft - 16) / kappa
    }
}

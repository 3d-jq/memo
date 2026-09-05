package com.psyche.memo.ui.theme.hct

import kotlin.math.PI
import kotlin.math.abs

/**
 * Port of `utils/math_utils.dart` from material_color_utilities 0.13.0, the
 * version pinned by pubspec.yaml. Compose's material3 artifact does not bundle
 * the color utilities, so the numeric core is ported verbatim to keep Memo's
 * harmonized semantic colors bit-identical to the Flutter app.
 */
internal object MathUtils {
    fun signum(num: Double): Int = if (num < 0.0) -1 else if (num == 0.0) 0 else 1

    fun lerp(start: Double, stop: Double, amount: Double): Double =
        (1.0 - amount) * start + amount * stop

    fun clampInt(min: Int, max: Int, input: Int): Int =
        if (input < min) min else if (input > max) max else input

    fun sanitizeDegreesDouble(degrees: Double): Double = euclideanMod(degrees, 360.0)

    fun rotationDirection(from: Double, to: Double): Double =
        if (sanitizeDegreesDouble(to - from) <= 180.0) 1.0 else -1.0

    fun differenceDegrees(a: Double, b: Double): Double = 180.0 - abs(abs(a - b) - 180.0)

    fun sanitizeRadians(angle: Double): Double = euclideanMod(angle + PI * 8, PI * 2)

    fun matrixMultiply(row: DoubleArray, matrix: Array<DoubleArray>): DoubleArray = doubleArrayOf(
        row[0] * matrix[0][0] + row[1] * matrix[0][1] + row[2] * matrix[0][2],
        row[0] * matrix[1][0] + row[1] * matrix[1][1] + row[2] * matrix[1][2],
        row[0] * matrix[2][0] + row[1] * matrix[2][1] + row[2] * matrix[2][2],
    )

    /**
     * Dart's `%` on doubles is Euclidean: the result takes the divisor's sign and
     * `-0.0` is normalized to `0.0`. Kotlin's `%` follows the dividend, so hue
     * values below zero would come out negative here.
     */
    private fun euclideanMod(a: Double, b: Double): Double {
        val r = a % b
        // `+ 0.0` turns -0.0 into 0.0 without touching any other value.
        return if (r < 0.0) r + b else r + 0.0
    }
}

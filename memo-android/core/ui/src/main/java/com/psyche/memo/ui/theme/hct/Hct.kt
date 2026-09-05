package com.psyche.memo.ui.theme.hct

import kotlin.math.min

/**
 * Port of `hct/hct.dart` (material_color_utilities 0.13.0). Dart's mutable
 * `hue`/`chroma`/`tone` setters are dropped — Memo only constructs and reads.
 *
 * `toInt` is named [toArgb] here to match Compose's `Color.toArgb`.
 */
internal class Hct private constructor(private val argb: Int) {
    /** 0 <= hue < 360. */
    val hue: Double

    /** Colorfulness; the requested chroma may be reduced to fit sRGB. */
    val chroma: Double

    /** L* from L*a*b*, 0..100. */
    val tone: Double

    init {
        val cam16 = Cam16.fromInt(argb)
        hue = cam16.hue
        chroma = cam16.chroma
        tone = ColorUtils.lstarFromArgb(argb)
    }

    fun toArgb(): Int = argb

    override fun equals(other: Any?): Boolean = other is Hct && other.argb == argb

    override fun hashCode(): Int = argb.hashCode()

    override fun toString(): String =
        "H${hue.toInt()} C${chroma.toInt()} T${tone.toInt()}"

    companion object {
        fun from(hue: Double, chroma: Double, tone: Double): Hct =
            Hct(HctSolver.solveToInt(hue, chroma, tone))

        fun fromArgb(argb: Int): Hct = Hct(argb)
    }
}

/** Port of `blend/blend.dart`; only [harmonize] is used by Memo's theme. */
internal object Blend {
    /**
     * Rotates [designColor]'s HCT hue toward [sourceColor]'s by at most 15
     * degrees, keeping chroma and tone — the behavior of
     * `dynamic_color`'s `Color.harmonizeWith`.
     */
    fun harmonize(designColor: Int, sourceColor: Int): Int {
        val fromHct = Hct.fromArgb(designColor)
        val toHct = Hct.fromArgb(sourceColor)
        val differenceDegrees = MathUtils.differenceDegrees(fromHct.hue, toHct.hue)
        val rotationDegrees = min(differenceDegrees * 0.5, 15.0)
        val outputHue = MathUtils.sanitizeDegreesDouble(
            fromHct.hue + rotationDegrees * MathUtils.rotationDirection(fromHct.hue, toHct.hue),
        )
        return Hct.from(outputHue, fromHct.chroma, fromHct.tone).toArgb()
    }
}

package com.psyche.memo.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.psyche.memo.ui.theme.hct.ColorUtils
import com.psyche.memo.ui.theme.hct.Hct
import com.psyche.memo.ui.theme.hct.MathUtils

/**
 * 1:1 port of lib/theme/custom_theme.dart's generator pipeline: the
 * material_color_utilities subset needed by `customThemeColorScheme` —
 * TonalPalette (+KeyColor), a TONAL_SPOT DynamicScheme at contrastLevel 0,
 * and the MaterialDynamicColors roles kelivo reads. Tone math (contrast
 * curves, tone delta pairs, the 50-59 awkward-zone fix) follows
 * dynamic_color.dart `getTone` exactly; contrastLevel is fixed at 0.0 so the
 * ContrastCurve/monochrome/fidelity branches collapse to their baseline
 * values (the variant is always TONAL_SPOT here).
 */
internal class TonalPalette private constructor(
    val hue: Double,
    val chroma: Double,
    private val keyColorFromHct: Hct?,
) {
    val keyColor: Hct = keyColorFromHct ?: KeyColor(hue, chroma).create()

    fun tone(tone: Double): Int = Hct.from(hue, chroma, tone).toArgb()

    companion object {
        fun of(hue: Double, chroma: Double): TonalPalette =
            TonalPalette(hue, chroma, keyColorFromHct = null)

        fun fromHct(hct: Hct): TonalPalette =
            TonalPalette(hct.hue, hct.chroma, keyColorFromHct = hct)
    }

    /** Port of material_color_utilities KeyColor.create() binary search. */
    private class KeyColor(private val hue: Double, private val requestedChroma: Double) {
        private val chromaCache = HashMap<Int, Double>()

        fun create(): Hct {
            val pivotTone = 50
            val toneStepSize = 1
            val epsilon = 0.01
            var lowerTone = 0
            var upperTone = 100
            while (lowerTone < upperTone) {
                val midTone = (lowerTone + upperTone) / 2
                val isAscending = maxChroma(midTone) < maxChroma(midTone + toneStepSize)
                val sufficientChroma = maxChroma(midTone) >= requestedChroma - epsilon
                if (sufficientChroma) {
                    if (Math.abs(lowerTone - pivotTone) < Math.abs(upperTone - pivotTone)) {
                        upperTone = midTone
                    } else {
                        if (lowerTone == midTone) {
                            return Hct.from(hue, requestedChroma, lowerTone.toDouble())
                        }
                        lowerTone = midTone
                    }
                } else {
                    if (isAscending) {
                        lowerTone = midTone + toneStepSize
                    } else {
                        upperTone = midTone
                    }
                }
            }
            return Hct.from(hue, requestedChroma, lowerTone.toDouble())
        }

        private fun maxChroma(tone: Int): Double =
            chromaCache.getOrPut(tone) { Hct.from(hue, 200.0, tone.toDouble()).chroma }
    }
}

/** Contrast helpers from contrast.dart. */
internal object McuContrast {
    fun ratioOfTones(toneAIn: Double, toneBIn: Double): Double {
        val toneA = toneAIn.coerceIn(0.0, 100.0)
        val toneB = toneBIn.coerceIn(0.0, 100.0)
        val y1 = ColorUtils.yFromLstar(toneA)
        val y2 = ColorUtils.yFromLstar(toneB)
        val lighter = maxOf(y1, y2)
        val darker = if (lighter == y2) y1 else y2
        return (lighter + 5.0) / (darker + 5.0)
    }

    fun lighter(tone: Double, ratio: Double): Double {
        if (tone < 0.0 || tone > 100.0) return -1.0
        val darkY = ColorUtils.yFromLstar(tone)
        val lightY = ratio * (darkY + 5.0) - 5.0
        val realContrast = ratioOfYs(lightY, darkY)
        val delta = Math.abs(realContrast - ratio)
        if (realContrast < ratio && delta > 0.04) return -1.0
        val returnValue = ColorUtils.lstarFromY(lightY) + 0.4
        if (returnValue < 0 || returnValue > 100) return -1.0
        return returnValue
    }

    fun darker(tone: Double, ratio: Double): Double {
        if (tone < 0.0 || tone > 100.0) return -1.0
        val lightY = ColorUtils.yFromLstar(tone)
        val darkY = ((lightY + 5.0) / ratio) - 5.0
        val realContrast = ratioOfYs(lightY, darkY)
        val delta = Math.abs(realContrast - ratio)
        if (realContrast < ratio && delta > 0.04) return -1.0
        val returnValue = ColorUtils.lstarFromY(darkY) - 0.4
        if (returnValue < 0 || returnValue > 100) return -1.0
        return returnValue
    }

    fun lighterUnsafe(tone: Double, ratio: Double): Double =
        lighter(tone, ratio).let { if (it < 0.0) 100.0 else it }

    fun darkerUnsafe(tone: Double, ratio: Double): Double =
        darker(tone, ratio).let { if (it < 0.0) 0.0 else it }

    private fun ratioOfYs(y1: Double, y2: Double): Double {
        val lighter = maxOf(y1, y2)
        val darker = if (lighter == y2) y1 else y2
        return (lighter + 5.0) / (darker + 5.0)
    }
}

/** dynamic_color.dart TonePolarity/ToneDeltaPair subset. */
internal enum class TonePolarity { NEARER, LIGHTER, DARKER }

internal class ToneDeltaPair(
    val roleA: McuDynamicColor,
    val roleB: McuDynamicColor,
    val delta: Double,
    val polarity: TonePolarity,
    val stayTogether: Boolean,
)

/** One dynamic color role: base tone + background/contrast/tone-delta rules. */
internal class McuDynamicColor(
    val name: String,
    private val palette: (McuScheme) -> TonalPalette,
    private val tone: (McuScheme) -> Double,
    private val background: ((McuScheme) -> McuDynamicColor)? = null,
    private val secondBackground: ((McuScheme) -> McuDynamicColor)? = null,
    private val contrastCurve: DoubleArray? = null, // [lo, normal, hi, max] — only [normal] used at level 0
    private val isBackground: Boolean = false,
    private val toneDeltaPair: ((McuScheme) -> ToneDeltaPair)? = null,
) {
    fun argb(s: McuScheme): Int = palette(s).tone(getTone(s))

    fun getTone(s: McuScheme): Double {
        // Case 1: tone delta pair.
        toneDeltaPair?.let { pairFn ->
            val pair = pairFn(s)
            val roleA = pair.roleA
            val roleB = pair.roleB
            val delta = pair.delta
            val aIsNearer = pair.polarity == TonePolarity.NEARER ||
                (pair.polarity == TonePolarity.LIGHTER && !s.isDark) ||
                (pair.polarity == TonePolarity.DARKER && s.isDark)
            val nearer = if (aIsNearer) roleA else roleB
            val farther = if (aIsNearer) roleB else roleA
            val amNearer = name == nearer.name
            val expansionDir = if (s.isDark) 1.0 else -1.0

            val bgTone = background!!.invoke(s).getTone(s)
            val nContrast = nearer.contrastCurve!![1]
            val fContrast = farther.contrastCurve!![1]

            var nTone = nearer.tone(s)
            if (McuContrast.ratioOfTones(bgTone, nTone) < nContrast) {
                nTone = foregroundTone(bgTone, nContrast)
            }
            var fTone = farther.tone(s)
            if (McuContrast.ratioOfTones(bgTone, fTone) < fContrast) {
                fTone = foregroundTone(bgTone, fContrast)
            }

            if ((fTone - nTone) * expansionDir < delta) {
                fTone = (nTone + delta * expansionDir).coerceIn(0.0, 100.0)
                if ((fTone - nTone) * expansionDir < delta) {
                    nTone = (fTone - delta * expansionDir).coerceIn(0.0, 100.0)
                }
            }

            // Avoids the 50-59 awkward zone.
            if (nTone in 50.0..60.0 && nTone < 60.0) {
                if (expansionDir > 0) {
                    nTone = 60.0
                    fTone = maxOf(fTone, nTone + delta * expansionDir)
                } else {
                    nTone = 49.0
                    fTone = minOf(fTone, nTone + delta * expansionDir)
                }
            } else if (fTone in 50.0..60.0 && fTone < 60.0) {
                if (pair.stayTogether) {
                    if (expansionDir > 0) {
                        nTone = 60.0
                        fTone = maxOf(fTone, nTone + delta * expansionDir)
                    } else {
                        nTone = 49.0
                        fTone = minOf(fTone, nTone + delta * expansionDir)
                    }
                } else {
                    fTone = if (expansionDir > 0) 60.0 else 49.0
                }
            }
            return if (amNearer) nTone else fTone
        }

        // Case 2: solve for itself.
        var answer = tone(s)
        val bg = background ?: return answer
        val bgTone = bg(s).getTone(s)
        val desiredRatio = contrastCurve?.get(1) ?: 1.0
        if (McuContrast.ratioOfTones(bgTone, answer) < desiredRatio) {
            answer = foregroundTone(bgTone, desiredRatio)
        }
        if (isBackground && answer >= 50.0 && answer < 60.0) {
            answer = if (McuContrast.ratioOfTones(49.0, bgTone) >= desiredRatio) 49.0 else 60.0
        }

        // Case 3: dual backgrounds.
        secondBackground?.let { secondBg ->
            val bgTone1 = bgTone
            val bgTone2 = secondBg(s).getTone(s)
            val upper = maxOf(bgTone1, bgTone2)
            val lower = minOf(bgTone1, bgTone2)
            if (McuContrast.ratioOfTones(upper, answer) >= desiredRatio &&
                McuContrast.ratioOfTones(lower, answer) >= desiredRatio
            ) {
                return answer
            }
            val lightOption = McuContrast.lighter(upper, desiredRatio)
            val darkOption = McuContrast.darker(lower, desiredRatio)
            val prefersLight = tonePrefersLightForeground(bgTone1) ||
                tonePrefersLightForeground(bgTone2)
            if (prefersLight) return if (lightOption < 0) 100.0 else lightOption
            val availables = buildList {
                if (lightOption >= 0) add(lightOption)
                if (darkOption >= 0) add(darkOption)
            }
            if (availables.size == 1) return availables[0]
            return if (darkOption < 0) 0.0 else darkOption
        }

        return answer
    }

    companion object {
        /** dynamic_color.dart foregroundTone. */
        fun foregroundTone(bgTone: Double, ratio: Double): Double {
            val lighterTone = McuContrast.lighterUnsafe(bgTone, ratio)
            val darkerTone = McuContrast.darkerUnsafe(bgTone, ratio)
            val lighterRatio = McuContrast.ratioOfTones(lighterTone, bgTone)
            val darkerRatio = McuContrast.ratioOfTones(darkerTone, bgTone)
            val preferLighter = tonePrefersLightForeground(bgTone)
            return if (preferLighter) {
                val negligibleDifference =
                    Math.abs(lighterRatio - darkerRatio) < 0.1 &&
                        lighterRatio < ratio && darkerRatio < ratio
                if (lighterRatio >= ratio || lighterRatio >= darkerRatio || negligibleDifference) {
                    lighterTone
                } else {
                    darkerTone
                }
            } else {
                if (darkerRatio >= ratio || darkerRatio >= lighterRatio) darkerTone else lighterTone
            }
        }

        fun tonePrefersLightForeground(tone: Double): Boolean = tone.toLong() < 60
    }
}

/** DynamicScheme subset: explicit TONAL_SPOT palettes at contrastLevel 0. */
internal class McuScheme(
    val isDark: Boolean,
    val primaryPalette: TonalPalette,
    val secondaryPalette: TonalPalette,
    val tertiaryPalette: TonalPalette,
    val neutralPalette: TonalPalette,
    val neutralVariantPalette: TonalPalette,
) {
    val errorPalette: TonalPalette = TonalPalette.of(25.0, 84.0)
}

/** The MaterialDynamicColors roles custom_theme.dart reads. */
internal object McuColors {
    private fun highestSurface(s: McuScheme) = if (s.isDark) surfaceBright else surfaceDim

    val surfaceDim = McuDynamicColor("surface_dim", { s -> s.neutralPalette }, { s -> if (s.isDark) 6.0 else 87.0 }, isBackground = true)
    val surfaceBright = McuDynamicColor("surface_bright", { s -> s.neutralPalette }, { s -> if (s.isDark) 24.0 else 98.0 }, isBackground = true)
    val surface = McuDynamicColor("surface", { s -> s.neutralPalette }, { s -> if (s.isDark) 6.0 else 98.0 }, isBackground = true)
    val surfaceContainerLowest = McuDynamicColor("surface_container_lowest", { s -> s.neutralPalette }, { s -> if (s.isDark) 4.0 else 100.0 }, isBackground = true)
    val surfaceContainerLow = McuDynamicColor("surface_container_low", { s -> s.neutralPalette }, { s -> if (s.isDark) 10.0 else 96.0 }, isBackground = true)
    val surfaceContainer = McuDynamicColor("surface_container", { s -> s.neutralPalette }, { s -> if (s.isDark) 12.0 else 94.0 }, isBackground = true)
    val surfaceContainerHigh = McuDynamicColor("surface_container_high", { s -> s.neutralPalette }, { s -> if (s.isDark) 17.0 else 92.0 }, isBackground = true)
    val surfaceContainerHighest = McuDynamicColor("surface_container_highest", { s -> s.neutralPalette }, { s -> if (s.isDark) 22.0 else 90.0 }, isBackground = true)
    val onSurface = McuDynamicColor(
        "on_surface", { s -> s.neutralPalette }, { s -> if (s.isDark) 90.0 else 10.0 },
        background = ::highestSurface, contrastCurve = doubleArrayOf(4.5, 7.0, 11.0, 21.0),
    )
    val surfaceVariant = McuDynamicColor("surface_variant", { s -> s.neutralVariantPalette }, { s -> if (s.isDark) 30.0 else 90.0 }, isBackground = true)
    val onSurfaceVariant = McuDynamicColor(
        "on_surface_variant", { s -> s.neutralVariantPalette }, { s -> if (s.isDark) 80.0 else 30.0 },
        background = ::highestSurface, contrastCurve = doubleArrayOf(3.0, 4.5, 7.0, 11.0),
    )
    val inverseSurface = McuDynamicColor("inverse_surface", { s -> s.neutralPalette }, { s -> if (s.isDark) 90.0 else 20.0 })
    val inverseOnSurface = McuDynamicColor(
        "inverse_on_surface", { s -> s.neutralPalette }, { s -> if (s.isDark) 20.0 else 95.0 },
        background = { inverseSurface }, contrastCurve = doubleArrayOf(4.5, 7.0, 11.0, 21.0),
    )
    val outline = McuDynamicColor(
        "outline", { s -> s.neutralVariantPalette }, { s -> if (s.isDark) 60.0 else 50.0 },
        background = ::highestSurface, contrastCurve = doubleArrayOf(1.5, 3.0, 4.5, 7.0),
    )
    val outlineVariant = McuDynamicColor(
        "outline_variant", { s -> s.neutralVariantPalette }, { s -> if (s.isDark) 30.0 else 80.0 },
        background = ::highestSurface, contrastCurve = doubleArrayOf(1.0, 1.0, 3.0, 4.5),
    )
    val shadow = McuDynamicColor("shadow", { s -> s.neutralPalette }, { _ -> 0.0 })
    val scrim = McuDynamicColor("scrim", { s -> s.neutralPalette }, { _ -> 0.0 })
    val surfaceTint = McuDynamicColor("surface_tint", { s -> s.primaryPalette }, { s -> if (s.isDark) 80.0 else 40.0 }, isBackground = true)

    val primary: McuDynamicColor = McuDynamicColor(
        "primary", { s -> s.primaryPalette }, { s -> if (s.isDark) 80.0 else 40.0 },
        isBackground = true, background = ::highestSurface, contrastCurve = doubleArrayOf(3.0, 4.5, 7.0, 7.0),
        toneDeltaPair = { ToneDeltaPair(primaryContainer, primary, 10.0, TonePolarity.NEARER, false) },
    )
    val onPrimary = McuDynamicColor(
        "on_primary", { s -> s.primaryPalette }, { s -> if (s.isDark) 20.0 else 100.0 },
        background = { primary }, contrastCurve = doubleArrayOf(4.5, 7.0, 11.0, 21.0),
    )
    val primaryContainer: McuDynamicColor = McuDynamicColor(
        "primary_container", { s -> s.primaryPalette }, { s -> if (s.isDark) 30.0 else 90.0 },
        isBackground = true, background = ::highestSurface, contrastCurve = doubleArrayOf(1.0, 1.0, 3.0, 4.5),
        toneDeltaPair = { ToneDeltaPair(primaryContainer, primary, 10.0, TonePolarity.NEARER, false) },
    )
    val onPrimaryContainer = McuDynamicColor(
        "on_primary_container", { s -> s.primaryPalette }, { s -> if (s.isDark) 90.0 else 30.0 },
        background = { primaryContainer }, contrastCurve = doubleArrayOf(3.0, 4.5, 7.0, 11.0),
    )
    val inversePrimary = McuDynamicColor(
        "inverse_primary", { s -> s.primaryPalette }, { s -> if (s.isDark) 40.0 else 80.0 },
        background = { inverseSurface }, contrastCurve = doubleArrayOf(3.0, 4.5, 7.0, 7.0),
    )

    val secondary: McuDynamicColor = McuDynamicColor(
        "secondary", { s -> s.secondaryPalette }, { s -> if (s.isDark) 80.0 else 40.0 },
        isBackground = true, background = ::highestSurface, contrastCurve = doubleArrayOf(3.0, 4.5, 7.0, 7.0),
        toneDeltaPair = { ToneDeltaPair(secondaryContainer, secondary, 10.0, TonePolarity.NEARER, false) },
    )
    val onSecondary = McuDynamicColor(
        "on_secondary", { s -> s.secondaryPalette }, { s -> if (s.isDark) 20.0 else 100.0 },
        background = { secondary }, contrastCurve = doubleArrayOf(4.5, 7.0, 11.0, 21.0),
    )
    val secondaryContainer: McuDynamicColor = McuDynamicColor(
        "secondary_container", { s -> s.secondaryPalette }, { s -> if (s.isDark) 30.0 else 90.0 },
        isBackground = true, background = ::highestSurface, contrastCurve = doubleArrayOf(1.0, 1.0, 3.0, 4.5),
        toneDeltaPair = { ToneDeltaPair(secondaryContainer, secondary, 10.0, TonePolarity.NEARER, false) },
    )
    val onSecondaryContainer = McuDynamicColor(
        "on_secondary_container", { s -> s.secondaryPalette }, { s -> if (s.isDark) 90.0 else 30.0 },
        background = { secondaryContainer }, contrastCurve = doubleArrayOf(3.0, 4.5, 7.0, 11.0),
    )

    val tertiary: McuDynamicColor = McuDynamicColor(
        "tertiary", { s -> s.tertiaryPalette }, { s -> if (s.isDark) 80.0 else 40.0 },
        isBackground = true, background = ::highestSurface, contrastCurve = doubleArrayOf(3.0, 4.5, 7.0, 7.0),
        toneDeltaPair = { ToneDeltaPair(tertiaryContainer, tertiary, 10.0, TonePolarity.NEARER, false) },
    )
    val onTertiary = McuDynamicColor(
        "on_tertiary", { s -> s.tertiaryPalette }, { s -> if (s.isDark) 20.0 else 100.0 },
        background = { tertiary }, contrastCurve = doubleArrayOf(4.5, 7.0, 11.0, 21.0),
    )
    val tertiaryContainer: McuDynamicColor = McuDynamicColor(
        "tertiary_container", { s -> s.tertiaryPalette }, { s -> if (s.isDark) 30.0 else 90.0 },
        isBackground = true, background = ::highestSurface, contrastCurve = doubleArrayOf(1.0, 1.0, 3.0, 4.5),
        toneDeltaPair = { ToneDeltaPair(tertiaryContainer, tertiary, 10.0, TonePolarity.NEARER, false) },
    )
    val onTertiaryContainer = McuDynamicColor(
        "on_tertiary_container", { s -> s.tertiaryPalette }, { s -> if (s.isDark) 90.0 else 30.0 },
        background = { tertiaryContainer }, contrastCurve = doubleArrayOf(3.0, 4.5, 7.0, 11.0),
    )

    val error: McuDynamicColor = McuDynamicColor(
        "error", { s -> s.errorPalette }, { s -> if (s.isDark) 80.0 else 40.0 },
        isBackground = true, background = ::highestSurface, contrastCurve = doubleArrayOf(3.0, 4.5, 7.0, 7.0),
        toneDeltaPair = { ToneDeltaPair(errorContainer, error, 10.0, TonePolarity.NEARER, false) },
    )
    val onError = McuDynamicColor(
        "on_error", { s -> s.errorPalette }, { s -> if (s.isDark) 20.0 else 100.0 },
        background = { error }, contrastCurve = doubleArrayOf(4.5, 7.0, 11.0, 21.0),
    )
    val errorContainer: McuDynamicColor = McuDynamicColor(
        "error_container", { s -> s.errorPalette }, { s -> if (s.isDark) 30.0 else 90.0 },
        isBackground = true, background = ::highestSurface, contrastCurve = doubleArrayOf(1.0, 1.0, 3.0, 4.5),
        toneDeltaPair = { ToneDeltaPair(errorContainer, error, 10.0, TonePolarity.NEARER, false) },
    )
    val onErrorContainer = McuDynamicColor(
        "on_error_container", { s -> s.errorPalette }, { s -> if (s.isDark) 90.0 else 30.0 },
        background = { errorContainer }, contrastCurve = doubleArrayOf(3.0, 4.5, 7.0, 11.0),
    )
}

private fun mcuColor(c: McuDynamicColor, s: McuScheme): Color = Color(c.argb(s))

/**
 * Port of custom_theme.dart `customThemeColorScheme`: a Material You
 * TONAL_SPOT scheme whose primary (and optional secondary/tertiary) tonal
 * palettes come from the picked colors via HCT.
 */
fun customThemeColorScheme(
    primaryArgb: Int,
    secondaryArgb: Int?,
    tertiaryArgb: Int?,
    dark: Boolean,
): ColorScheme {
    val sourceHct = Hct.fromArgb(primaryArgb)
    val scheme = McuScheme(
        isDark = dark,
        primaryPalette = TonalPalette.fromHct(sourceHct),
        secondaryPalette = secondaryArgb?.let { TonalPalette.fromHct(Hct.fromArgb(it)) }
            ?: TonalPalette.of(sourceHct.hue, 16.0),
        tertiaryPalette = tertiaryArgb?.let { TonalPalette.fromHct(Hct.fromArgb(it)) }
            ?: TonalPalette.of(MathUtils.sanitizeDegreesDouble(sourceHct.hue + 60.0), 24.0),
        neutralPalette = TonalPalette.of(sourceHct.hue, 3.5),
        neutralVariantPalette = TonalPalette.of(sourceHct.hue, 5.0),
    )
    val c = McuColors
    return if (dark) {
        darkColorScheme(
            primary = mcuColor(c.primary, scheme),
            onPrimary = mcuColor(c.onPrimary, scheme),
            primaryContainer = mcuColor(c.primaryContainer, scheme),
            onPrimaryContainer = mcuColor(c.onPrimaryContainer, scheme),
            secondary = mcuColor(c.secondary, scheme),
            onSecondary = mcuColor(c.onSecondary, scheme),
            secondaryContainer = mcuColor(c.secondaryContainer, scheme),
            onSecondaryContainer = mcuColor(c.onSecondaryContainer, scheme),
            tertiary = mcuColor(c.tertiary, scheme),
            onTertiary = mcuColor(c.onTertiary, scheme),
            tertiaryContainer = mcuColor(c.tertiaryContainer, scheme),
            onTertiaryContainer = mcuColor(c.onTertiaryContainer, scheme),
            error = mcuColor(c.error, scheme),
            onError = mcuColor(c.onError, scheme),
            errorContainer = mcuColor(c.errorContainer, scheme),
            onErrorContainer = mcuColor(c.onErrorContainer, scheme),
            surface = mcuColor(c.surface, scheme),
            onSurface = mcuColor(c.onSurface, scheme),
            surfaceVariant = mcuColor(c.surfaceVariant, scheme),
            onSurfaceVariant = mcuColor(c.onSurfaceVariant, scheme),
            outline = mcuColor(c.outline, scheme),
            outlineVariant = mcuColor(c.outlineVariant, scheme),
            scrim = mcuColor(c.scrim, scheme),
            inverseSurface = mcuColor(c.inverseSurface, scheme),
            inverseOnSurface = mcuColor(c.inverseOnSurface, scheme),
            inversePrimary = mcuColor(c.inversePrimary, scheme),
            surfaceDim = mcuColor(c.surfaceDim, scheme),
            surfaceBright = mcuColor(c.surfaceBright, scheme),
            surfaceContainerLowest = mcuColor(c.surfaceContainerLowest, scheme),
            surfaceContainerLow = mcuColor(c.surfaceContainerLow, scheme),
            surfaceContainer = mcuColor(c.surfaceContainer, scheme),
            surfaceContainerHigh = mcuColor(c.surfaceContainerHigh, scheme),
            surfaceContainerHighest = mcuColor(c.surfaceContainerHighest, scheme),
        )
    } else {
        lightColorScheme(
            primary = mcuColor(c.primary, scheme),
            onPrimary = mcuColor(c.onPrimary, scheme),
            primaryContainer = mcuColor(c.primaryContainer, scheme),
            onPrimaryContainer = mcuColor(c.onPrimaryContainer, scheme),
            secondary = mcuColor(c.secondary, scheme),
            onSecondary = mcuColor(c.onSecondary, scheme),
            secondaryContainer = mcuColor(c.secondaryContainer, scheme),
            onSecondaryContainer = mcuColor(c.onSecondaryContainer, scheme),
            tertiary = mcuColor(c.tertiary, scheme),
            onTertiary = mcuColor(c.onTertiary, scheme),
            tertiaryContainer = mcuColor(c.tertiaryContainer, scheme),
            onTertiaryContainer = mcuColor(c.onTertiaryContainer, scheme),
            error = mcuColor(c.error, scheme),
            onError = mcuColor(c.onError, scheme),
            errorContainer = mcuColor(c.errorContainer, scheme),
            onErrorContainer = mcuColor(c.onErrorContainer, scheme),
            surface = mcuColor(c.surface, scheme),
            onSurface = mcuColor(c.onSurface, scheme),
            surfaceVariant = mcuColor(c.surfaceVariant, scheme),
            onSurfaceVariant = mcuColor(c.onSurfaceVariant, scheme),
            outline = mcuColor(c.outline, scheme),
            outlineVariant = mcuColor(c.outlineVariant, scheme),
            scrim = mcuColor(c.scrim, scheme),
            inverseSurface = mcuColor(c.inverseSurface, scheme),
            inverseOnSurface = mcuColor(c.inverseOnSurface, scheme),
            inversePrimary = mcuColor(c.inversePrimary, scheme),
            surfaceDim = mcuColor(c.surfaceDim, scheme),
            surfaceBright = mcuColor(c.surfaceBright, scheme),
            surfaceContainerLowest = mcuColor(c.surfaceContainerLowest, scheme),
            surfaceContainerLow = mcuColor(c.surfaceContainerLow, scheme),
            surfaceContainer = mcuColor(c.surfaceContainer, scheme),
            surfaceContainerHigh = mcuColor(c.surfaceContainerHigh, scheme),
            surfaceContainerHighest = mcuColor(c.surfaceContainerHighest, scheme),
        )
    }
}

/** Port of custom_theme.dart `buildCustomThemePalette`. */
fun buildCustomThemePalette(
    id: String,
    name: String,
    primaryArgb: Int,
    secondaryArgb: Int?,
    tertiaryArgb: Int?,
): Palette = Palette(
    id = id,
    zhName = name.ifEmpty { "自定义" },
    enName = name.ifEmpty { "Custom" },
    light = customThemeColorScheme(primaryArgb, secondaryArgb, tertiaryArgb, dark = false),
    dark = customThemeColorScheme(primaryArgb, secondaryArgb, tertiaryArgb, dark = true),
)

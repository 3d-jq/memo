package com.psyche.memo.ui.theme.hct

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spot checks against `material_color_utilities` 0.13.0 running in Dart: the
 * solver has two paths (a Newton-Raphson descent and a bisection fallback that
 * reads CRITICAL_PLANES) and the tone<->lstar pair has its own rounding, so the
 * probes below pin both.
 *
 * The tables below are a fixture snapshot of that Dart run, not hand-written.
 */
class HctTest {

    private class Probe(
        val hue: Double,
        val chroma: Double,
        val tone: Double,
        val argb: Long,
        val resolvedHue: Double,
        val resolvedChroma: Double,
        val resolvedTone: Double,
    )

    private val probes = listOf(
        Probe(203.0, 3.0, 100.0, 0xFFFFFFFFL, 209.49195947383808, 2.869035203677399, 100.0),
        Probe(0.0, 0.0, 0.0, 0xFF000000L, 0.0, 0.0, 0.0),
        Probe(0.0, 0.0, 100.0, 0xFFFFFFFFL, 209.49195947383808, 2.869035203677399, 100.0),
        Probe(359.9, 130.0, 45.0, 0xFFD00070L, 359.9254773170529, 90.29542671742153, 45.05313975515047),
        Probe(145.0, 999.0, 60.0, 0xFF00A829L, 144.96697028831863, 77.28946042375371, 60.037836821307636),
        Probe(60.0, 40.0, 90.0, 0xFFFFDCC1L, 60.46094162970029, 15.393953840469486, 89.9968977613676),
        Probe(275.0, 12.5, 33.3, 0xFF4B4E5CL, 273.32151457977625, 12.163335879003986, 33.38714086543199),
        Probe(17.25, 0.00005, 50.0, 0xFF777777L, 209.49395905400092, 1.8156010041998947, 50.034438792538225),
        Probe(180.0, 25.0, 5.0, 0xFF001410L, 181.56997310156945, 14.923757004804886, 4.857225504493741),
        Probe(95.5, 70.25, 77.125, 0xFFE1BB00L, 95.5061626631582, 59.19649725298699, 77.00989533196548),
    )

    private class LstarProbe(val lstar: Double, val argb: Long, val resolved: Double)

    private val lstarProbes = listOf(
        LstarProbe(0.0, 0xFF000000L, 0.0),
        LstarProbe(1.0, 0xFF040404L, 1.0966992002626057),
        LstarProbe(25.0, 0xFF3B3B3BL, 24.869669873093272),
        LstarProbe(50.0, 0xFF777777L, 50.034438792538225),
        LstarProbe(73.5, 0xFFB5B5B5L, 73.68010466135591),
        LstarProbe(99.0, 0xFFFCFCFCL, 98.96399275988763),
        LstarProbe(100.0, 0xFFFFFFFFL, 100.0),
    )

    @Test
    fun solverReproducesMaterialColorUtilities() {
        for (probe in probes) {
            val label = "HCT(%s, %s, %s)".format(probe.hue, probe.chroma, probe.tone)
            val hct = Hct.from(probe.hue, probe.chroma, probe.tone)
            assertEquals(label + " argb", probe.argb, hct.toArgb().toLong() and 0xFFFFFFFFL)
            // Round-tripping the solved color must land back on the same
            // coordinates Dart reports for it.
            val back = Hct.fromArgb(hct.toArgb())
            assertEquals(label + " hue", probe.resolvedHue, back.hue, 1e-9)
            assertEquals(label + " chroma", probe.resolvedChroma, back.chroma, 1e-9)
            assertEquals(label + " tone", probe.resolvedTone, back.tone, 1e-9)
        }
    }

    @Test
    fun lstarRoundTripsMatchMaterialColorUtilities() {
        for (probe in lstarProbes) {
            val argb = ColorUtils.argbFromLstar(probe.lstar)
            assertEquals(
                "argbFromLstar(%s)".format(probe.lstar),
                probe.argb,
                argb.toLong() and 0xFFFFFFFFL,
            )
            assertEquals(
                "lstarFromArgb(%s)".format(probe.lstar),
                probe.resolved,
                ColorUtils.lstarFromArgb(argb),
                1e-9,
            )
        }
    }

    @Test
    fun solvingAColorsOwnCoordinatesReturnsTheSameColor() {
        val argbs = listOf(
            0xFF4D5C92.toInt(),
            0xFFB6C4FF.toInt(),
            0xFF00B96B.toInt(),
            0xFF121213.toInt(),
            0xFFF7F7F7.toInt(),
            0xFF2E7D32.toInt(),
            0xFFF57C00.toInt(),
            0xFF855304.toInt(),
            0xFF8B4E3B.toInt(),
            0xFFFFFFFF.toInt(),
            0xFF000000.toInt(),
            0xFF777777.toInt(),
        )
        for (argb in argbs) {
            val hct = Hct.fromArgb(argb)
            val same = Hct.from(hct.hue, hct.chroma, hct.tone)
            assertEquals("0x%08X".format(argb), argb, same.toArgb())
            assertEquals(hct, same)
        }
    }

    @Test
    fun harmonizeRotatesHueTowardTheSource() {
        // dynamic_color's Color.harmonizeWith == Blend.harmonize on ARGB ints;
        // these are the `success` tokens of the default light / dark palettes.
        assertEquals(0xFF007E4E.toInt(), Blend.harmonize(0xFF2E7D32.toInt(), 0xFF4D5C92.toInt()))
        assertEquals(0xFF6AC99A.toInt(), Blend.harmonize(0xFF81C784.toInt(), 0xFFB6C4FF.toInt()))
    }
}

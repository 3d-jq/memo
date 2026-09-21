package com.psyche.memo.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.psyche.memo.ui.theme.hct.Hct
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Token values as computed by `material_color_utilities` 0.13.0 on the Dart side
 * from the very same Palettes.kt this module ships (scratch generator run
 * 2026-09-05). lib/theme/app_semantic_colors.dart + surface_ladder.dart are the
 * source of truth, so the port has to reproduce every one of these exactly.
 *
 * The table below is a fixture snapshot, not hand-written: it was parsed out
 * of the Dart run so no value was transcribed.
 */
class SemanticColorsTest {

    private class Row(
        val label: String,
        val palette: Palette,
        val dark: Boolean,
        val hue: Double,
        val chroma: Double,
        val tone: Double,
        val success: Long,
        val successContainer: Long,
        val warning: Long,
        val warningContainer: Long,
        val flatCard: Long,
        val flatFill: Long,
        val flatCardFill: Long,
        val flatContainers: List<Long>,
        val layeredCard: Long,
        val layeredFill: Long,
        val layeredCardFill: Long,
        val layeredContainers: List<Long>,
    ) {
        val scheme get() = if (dark) palette.dark else palette.light

        fun colors(layered: Boolean) = AppSemanticColors.of(scheme, dark, layered)

        fun containers(layered: Boolean) =
            MemoTheme.withDerivedSurfaceContainers(scheme, dark, layered)
    }

    private val groundTruth = listOf(
        Row(
            label = "default light",
            palette = paletteById("default"),
            dark = false,
            hue = 274.15144293475873,
            chroma = 35.8490643447912,
            tone = 40.09345430755351,
            success = 0xFF007E4EL,
            successContainer = 0xFF99D7B7L,
            warning = 0xFFFE743EL,
            warningContainer = 0xFFFFDFC3L,
            flatCard = 0xFFFFFFFFL,
            flatFill = 0xFFECECECL,
            flatCardFill = 0xFFECECECL,
            flatContainers = listOf(0xFFFDFDFDL, 0xFFFBFBFBL, 0xFFFAFAFAL, 0xFFFEFEFEL, 0xFFECECECL),
            layeredCard = 0xFFEDEDEDL,
            layeredFill = 0xFFF0F0F0L,
            layeredCardFill = 0xFFE4E4E4L,
            layeredContainers = listOf(0xFFFFFFFFL, 0xFFF2F2F2L, 0xFFEFF0F0L, 0xFFEDEDEDL, 0xFFF0F0F0L),
        ),
        Row(
            label = "default dark",
            palette = paletteById("default"),
            dark = true,
            hue = 274.4503625098131,
            chroma = 34.79448763207743,
            tone = 79.97947282970401,
            success = 0xFF6AC99AL,
            successContainer = 0xFF005E39L,
            warning = 0xFFFFB578L,
            warningContainer = 0xFF94491DL,
            flatCard = 0xFF2A2A2BL,
            flatFill = 0xFF373738L,
            flatCardFill = 0xFF373738L,
            flatContainers = listOf(0xFF0D0D0EL, 0xFF19191AL, 0xFF1D1D1EL, 0xFF202021L, 0xFF272728L),
            layeredCard = 0xFF29292AL,
            layeredFill = 0xFF252426L,
            layeredCardFill = 0xFF3D3D3EL,
            layeredContainers = listOf(0xFF09090AL, 0xFF1A1A1BL, 0xFF212021L, 0xFF29292AL, 0xFF2F2F30L),
        ),
        Row(
            label = "blue light",
            palette = paletteById("blue"),
            dark = false,
            hue = 265.218286372875,
            chroma = 39.78302091584282,
            tone = 40.07549566880485,
            success = 0xFF007E4EL,
            successContainer = 0xFF99D7B7L,
            warning = 0xFFFE743EL,
            warningContainer = 0xFFFFDFC3L,
            flatCard = 0xFFFFFFFFL,
            flatFill = 0xFFF2F0F4L,
            flatCardFill = 0xFFF2F0F4L,
            flatContainers = listOf(0xFFFEFEFFL, 0xFFFEFDFFL, 0xFFFEFCFFL, 0xFFFFFEFFL, 0xFFF2F0F4L),
            layeredCard = 0xFFF3F1F5L,
            layeredFill = 0xFFF6F4F8L,
            layeredCardFill = 0xFFEAE8ECL,
            layeredContainers = listOf(0xFFFFFFFFL, 0xFFF8F6FAL, 0xFFF5F4F8L, 0xFFF3F1F5L, 0xFFF6F4F8L),
        ),
        Row(
            label = "blue dark",
            palette = paletteById("blue"),
            dark = true,
            hue = 264.6355229989935,
            chroma = 35.31854139351498,
            tone = 80.08811075700379,
            success = 0xFF6AC99AL,
            successContainer = 0xFF005E39L,
            warning = 0xFFFFB578L,
            warningContainer = 0xFF94491DL,
            flatCard = 0xFF323234L,
            flatFill = 0xFF3B3B3DL,
            flatCardFill = 0xFF3B3B3DL,
            flatContainers = listOf(0xFF131315L, 0xFF222224L, 0xFF252527L, 0xFF29292BL, 0xFF303031L),
            layeredCard = 0xFF323234L,
            layeredFill = 0xFF2E2E30L,
            layeredCardFill = 0xFF464648L,
            layeredContainers = listOf(0xFF151517L, 0xFF232325L, 0xFF2A292BL, 0xFF323234L, 0xFF39383BL),
        ),
        Row(
            label = "green light",
            palette = paletteById("green"),
            dark = false,
            hue = 161.78514465878425,
            chroma = 40.31264424764761,
            tone = 40.16485467942387,
            success = 0xFF057F40L,
            successContainer = 0xFFA0D7ADL,
            warning = 0xFFDE8A00L,
            warningContainer = 0xFFF5E3B2L,
            flatCard = 0xFFFFFFFFL,
            flatFill = 0xFFF0F2EDL,
            flatCardFill = 0xFFF0F2EDL,
            flatContainers = listOf(0xFFFEFEFDL, 0xFFFDFEFCL, 0xFFFCFEFAL, 0xFFFEFFFEL, 0xFFF0F2EDL),
            layeredCard = 0xFFF1F3EEL,
            layeredFill = 0xFFF4F6F1L,
            layeredCardFill = 0xFFE8EAE5L,
            layeredContainers = listOf(0xFFFFFFFFL, 0xFFF6F8F3L, 0xFFF3F6F1L, 0xFFF1F3EEL, 0xFFF4F6F1L),
        ),
        Row(
            label = "green dark",
            palette = paletteById("green"),
            dark = true,
            hue = 162.04777436295743,
            chroma = 40.27622090769788,
            tone = 79.9846205633112,
            success = 0xFF77C88EL,
            successContainer = 0xFF005F2EL,
            warning = 0xFFEDBF48L,
            warningContainer = 0xFF805500L,
            flatCard = 0xFF303331L,
            flatFill = 0xFF393C3AL,
            flatCardFill = 0xFF393C3AL,
            flatContainers = listOf(0xFF121413L, 0xFF202321L, 0xFF232624L, 0xFF272A28L, 0xFF2E302FL),
            layeredCard = 0xFF303331L,
            layeredFill = 0xFF2C2F2CL,
            layeredCardFill = 0xFF444745L,
            layeredContainers = listOf(0xFF131614L, 0xFF212422L, 0xFF272A28L, 0xFF303331L, 0xFF373A37L),
        ),
        Row(
            label = "purple light",
            palette = paletteById("purple"),
            dark = false,
            hue = 290.9315516468755,
            chroma = 39.97869163892023,
            tone = 39.9979388128154,
            success = 0xFF007E4EL,
            successContainer = 0xFF99D7B7L,
            warning = 0xFFFE743EL,
            warningContainer = 0xFFFFDFC3L,
            flatCard = 0xFFFFFFFFL,
            flatFill = 0xFFF4F0F4L,
            flatCardFill = 0xFFF4F0F4L,
            flatContainers = listOf(0xFFFFFEFFL, 0xFFFFFDFFL, 0xFFFFFCFFL, 0xFFFFFEFFL, 0xFFF4F0F4L),
            layeredCard = 0xFFF5F1F5L,
            layeredFill = 0xFFF8F4F8L,
            layeredCardFill = 0xFFECE8ECL,
            layeredContainers = listOf(0xFFFFFFFFL, 0xFFFAF6FAL, 0xFFF7F4F8L, 0xFFF5F1F5L, 0xFFF8F4F8L),
        ),
        Row(
            label = "purple dark",
            palette = paletteById("purple"),
            dark = true,
            hue = 292.06536308466514,
            chroma = 35.38273543347698,
            tone = 80.04495903956658,
            success = 0xFF6AC99AL,
            successContainer = 0xFF005E39L,
            warning = 0xFFFFB578L,
            warningContainer = 0xFF94491DL,
            flatCard = 0xFF333235L,
            flatFill = 0xFF3C3B3FL,
            flatCardFill = 0xFF3C3B3FL,
            flatContainers = listOf(0xFF141316L, 0xFF232226L, 0xFF262529L, 0xFF2A292CL, 0xFF303033L),
            layeredCard = 0xFF333236L,
            layeredFill = 0xFF2F2E32L,
            layeredCardFill = 0xFF47464AL,
            layeredContainers = listOf(0xFF161519L, 0xFF242327L, 0xFF2B292EL, 0xFF333236L, 0xFF3A383DL),
        ),
        Row(
            label = "yellow light",
            palette = paletteById("yellow"),
            dark = false,
            hue = 69.54496388927059,
            chroma = 39.824381886654535,
            tone = 39.88503710461402,
            success = 0xFF517914L,
            successContainer = 0xFFB4D497L,
            warning = 0xFFE88500L,
            warningContainer = 0xFFFFDFB9L,
            flatCard = 0xFFFFFFFFL,
            flatFill = 0xFFF4F0EEL,
            flatCardFill = 0xFFF4F0EEL,
            flatContainers = listOf(0xFFFFFEFDL, 0xFFFFFDFCL, 0xFFFFFCFBL, 0xFFFFFEFEL, 0xFFF4F0EEL),
            layeredCard = 0xFFF5F1EFL,
            layeredFill = 0xFFF8F4F2L,
            layeredCardFill = 0xFFECE8E6L,
            layeredContainers = listOf(0xFFFFFFFFL, 0xFFFAF6F4L, 0xFFF7F4F2L, 0xFFF5F1EFL, 0xFFF8F4F2L),
        ),
        Row(
            label = "yellow dark",
            palette = paletteById("yellow"),
            dark = true,
            hue = 69.19935915687955,
            chroma = 39.96134964085582,
            tone = 79.93138643993342,
            success = 0xFF99C46EL,
            successContainer = 0xFF3A5B04L,
            warning = 0xFFFFB756L,
            warningContainer = 0xFF895000L,
            flatCard = 0xFF35322DL,
            flatFill = 0xFF403B35L,
            flatCardFill = 0xFF403B35L,
            flatContainers = listOf(0xFF161310L, 0xFF26221DL, 0xFF292520L, 0xFF2C2924L, 0xFF33302BL),
            layeredCard = 0xFF37322CL,
            layeredFill = 0xFF322E28L,
            layeredCardFill = 0xFF4C4640L,
            layeredContainers = listOf(0xFF191510L, 0xFF27231EL, 0xFF2E2924L, 0xFF37322CL, 0xFF3D3833L),
        ),
        Row(
            label = "smoky_rose light",
            palette = paletteById("smoky_rose"),
            dark = false,
            hue = 346.8783896945558,
            chroma = 31.70080322838695,
            tone = 40.04366827103231,
            success = 0xFF517914L,
            successContainer = 0xFFB4D497L,
            warning = 0xFFFE743EL,
            warningContainer = 0xFFFFDFC3L,
            flatCard = 0xFFFFFFFFL,
            flatFill = 0xFFF4F0F4L,
            flatCardFill = 0xFFF4F0F4L,
            flatContainers = listOf(0xFFFFFEFFL, 0xFFFFFDFFL, 0xFFFFFCFFL, 0xFFFFFEFFL, 0xFFF4F0F4L),
            layeredCard = 0xFFF5F1F5L,
            layeredFill = 0xFFF8F4F8L,
            layeredCardFill = 0xFFECE8ECL,
            layeredContainers = listOf(0xFFFFFFFFL, 0xFFFAF6FAL, 0xFFF7F4F8L, 0xFFF5F1F5L, 0xFFF8F4F8L),
        ),
        Row(
            label = "smoky_rose dark",
            palette = paletteById("smoky_rose"),
            dark = true,
            hue = 347.1071777096593,
            chroma = 32.1935512578501,
            tone = 79.90190647794849,
            success = 0xFF99C46EL,
            successContainer = 0xFF3A5B04L,
            warning = 0xFFFFB578L,
            warningContainer = 0xFF94491DL,
            flatCard = 0xFF363134L,
            flatFill = 0xFF413A3DL,
            flatCardFill = 0xFF413A3DL,
            flatContainers = listOf(0xFF171315L, 0xFF272124L, 0xFF2A2427L, 0xFF2D282BL, 0xFF342F31L),
            layeredCard = 0xFF383134L,
            layeredFill = 0xFF332D30L,
            layeredCardFill = 0xFF4D4548L,
            layeredContainers = listOf(0xFF1A1417L, 0xFF282225L, 0xFF2F282BL, 0xFF383134L, 0xFF3F373BL),
        ),
        Row(
            label = "terracotta light",
            palette = paletteById("terracotta"),
            dark = false,
            hue = 35.44850635277512,
            chroma = 32.17810891083903,
            tone = 40.004212257792105,
            success = 0xFF517914L,
            successContainer = 0xFFB4D497L,
            warning = 0xFFFB772CL,
            warningContainer = 0xFFFFDFC3L,
            flatCard = 0xFFFFFFFFL,
            flatFill = 0xFFF4F0F3L,
            flatCardFill = 0xFFF4F0F3L,
            flatContainers = listOf(0xFFFFFEFFL, 0xFFFFFDFFL, 0xFFFFFCFFL, 0xFFFFFEFFL, 0xFFF4F0F3L),
            layeredCard = 0xFFF5F1F5L,
            layeredFill = 0xFFF8F4F8L,
            layeredCardFill = 0xFFECE8ECL,
            layeredContainers = listOf(0xFFFFFFFFL, 0xFFFAF6FAL, 0xFFF7F4F8L, 0xFFF5F1F5L, 0xFFF8F4F8L),
        ),
        Row(
            label = "terracotta dark",
            palette = paletteById("terracotta"),
            dark = true,
            hue = 36.04110892082154,
            chroma = 29.605376807800962,
            tone = 80.05364750355525,
            success = 0xFF99C46EL,
            successContainer = 0xFF3A5B04L,
            warning = 0xFFFFB578L,
            warningContainer = 0xFF934A18L,
            flatCard = 0xFF38312FL,
            flatFill = 0xFF433A37L,
            flatCardFill = 0xFF433A37L,
            flatContainers = listOf(0xFF181311L, 0xFF29211FL, 0xFF2C2422L, 0xFF2F2826L, 0xFF362F2DL),
            layeredCard = 0xFF3A312FL,
            layeredFill = 0xFF362D2AL,
            layeredCardFill = 0xFF4F4543L,
            layeredContainers = listOf(0xFF1C1412L, 0xFF2B2220L, 0xFF312826L, 0xFF3A312FL, 0xFF413735L),
        ),
        Row(
            label = "monochrome light",
            palette = paletteById("monochrome"),
            dark = false,
            hue = 0.0,
            chroma = 0.0,
            tone = 0.0,
            success = 0xFF517914L,
            successContainer = 0xFFB4D497L,
            warning = 0xFFFE743EL,
            warningContainer = 0xFFFFDFC3L,
            flatCard = 0xFFFFFFFFL,
            flatFill = 0xFFECECECL,
            flatCardFill = 0xFFECECECL,
            flatContainers = listOf(0xFFFDFDFDL, 0xFFFBFBFBL, 0xFFFAFAFAL, 0xFFFEFEFEL, 0xFFECECECL),
            layeredCard = 0xFFEDEDEDL,
            layeredFill = 0xFFF0F0F0L,
            layeredCardFill = 0xFFE4E4E4L,
            layeredContainers = listOf(0xFFFFFFFFL, 0xFFF2F2F2L, 0xFFEFF0F0L, 0xFFEDEDEDL, 0xFFF0F0F0L),
        ),
        Row(
            label = "monochrome dark",
            palette = paletteById("monochrome"),
            dark = true,
            hue = 209.49195947383808,
            chroma = 2.869035203677399,
            tone = 100.0,
            success = 0xFF6AC99AL,
            successContainer = 0xFF005E39L,
            warning = 0xFFEDBF48L,
            warningContainer = 0xFF805500L,
            flatCard = 0xFF2A2A2BL,
            flatFill = 0xFF373738L,
            flatCardFill = 0xFF373738L,
            flatContainers = listOf(0xFF0D0D0EL, 0xFF19191AL, 0xFF1D1D1EL, 0xFF202021L, 0xFF272728L),
            layeredCard = 0xFF29292AL,
            layeredFill = 0xFF252426L,
            layeredCardFill = 0xFF3D3D3EL,
            layeredContainers = listOf(0xFF09090AL, 0xFF1A1A1BL, 0xFF212021L, 0xFF29292AL, 0xFF2F2F30L),
        ),
        Row(
            label = "doc_theme light",
            palette = paletteById("doc_theme"),
            dark = false,
            hue = 157.12129126482407,
            chroma = 63.46357517760821,
            tone = 66.33572402903415,
            success = 0xFF177E3CL,
            successContainer = 0xFFA2D6ABL,
            warning = 0xFFDE8A00L,
            warningContainer = 0xFFF5E3B2L,
            flatCard = 0xFFFFFFFFL,
            flatFill = 0xFFECECECL,
            flatCardFill = 0xFFECECECL,
            flatContainers = listOf(0xFFFDFDFDL, 0xFFFBFBFBL, 0xFFFAFAFAL, 0xFFFEFEFEL, 0xFFECECECL),
            layeredCard = 0xFFEDEDEDL,
            layeredFill = 0xFFF0F0F0L,
            layeredCardFill = 0xFFE4E4E4L,
            layeredContainers = listOf(0xFFFFFFFFL, 0xFFF2F2F2L, 0xFFEFF0F0L, 0xFFEDEDEDL, 0xFFF0F0F0L),
        ),
        Row(
            label = "doc_theme dark",
            palette = paletteById("doc_theme"),
            dark = true,
            hue = 157.12129126482407,
            chroma = 63.46357517760821,
            tone = 66.33572402903415,
            success = 0xFF7AC88AL,
            successContainer = 0xFF015F28L,
            warning = 0xFFEDBF48L,
            warningContainer = 0xFF805500L,
            flatCard = 0xFF2A2A2BL,
            flatFill = 0xFF373738L,
            flatCardFill = 0xFF373738L,
            flatContainers = listOf(0xFF0D0D0EL, 0xFF19191AL, 0xFF1D1D1EL, 0xFF202021L, 0xFF272728L),
            layeredCard = 0xFF29292AL,
            layeredFill = 0xFF252426L,
            layeredCardFill = 0xFF3D3D3EL,
            layeredContainers = listOf(0xFF09090AL, 0xFF1A1A1BL, 0xFF212021L, 0xFF29292AL, 0xFF2F2F30L),
        ),
    )

    @Test
    fun primaryHctCoordinatesMatchFlutter() {
        for (row in groundTruth) {
            val hct = Hct.fromArgb(row.scheme.primary.toArgb())
            assertEquals(row.label + " hue", row.hue, hct.hue, 1e-9)
            assertEquals(row.label + " chroma", row.chroma, hct.chroma, 1e-9)
            assertEquals(row.label + " tone", row.tone, hct.tone, 1e-9)
        }
    }

    @Test
    fun harmonizedStatusTokensMatchFlutter() {
        for (row in groundTruth) {
            val colors = row.colors(layered = false)
            assertEquals(row.label + " success", Color(row.success), colors.success)
            assertEquals(
                row.label + " successContainer",
                Color(row.successContainer),
                colors.successContainer,
            )
            assertEquals(row.label + " warning", Color(row.warning), colors.warning)
            assertEquals(
                row.label + " warningContainer",
                Color(row.warningContainer),
                colors.warningContainer,
            )
        }
    }

    @Test
    fun flatLadderMatchesFlutter() {
        for (row in groundTruth) {
            val colors = row.colors(layered = false)
            assertEquals(row.label + " flat card", Color(row.flatCard), colors.surfaceCard)
            assertEquals(row.label + " flat fill", Color(row.flatFill), colors.surfaceFill)
            assertEquals(
                row.label + " flat cardFill",
                Color(row.flatCardFill),
                colors.surfaceCardFill,
            )
        }
    }

    @Test
    fun layeredLadderMatchesFlutter() {
        for (row in groundTruth) {
            val colors = row.colors(layered = true)
            assertEquals(row.label + " layered card", Color(row.layeredCard), colors.surfaceCard)
            assertEquals(row.label + " layered fill", Color(row.layeredFill), colors.surfaceFill)
            assertEquals(
                row.label + " layered cardFill",
                Color(row.layeredCardFill),
                colors.surfaceCardFill,
            )
            assertEquals(true, colors.layered)
        }
    }

    @Test
    fun derivedSurfaceContainersMatchFlutter() {
        for (row in groundTruth) {
            for (layered in listOf(false, true)) {
                val mode = if (layered) "layered" else "flat"
                val expected = if (layered) row.layeredContainers else row.flatContainers
                val scheme = row.containers(layered)
                val names = listOf(
                    "surfaceContainerLowest" to scheme.surfaceContainerLowest,
                    "surfaceContainerLow" to scheme.surfaceContainerLow,
                    "surfaceContainer" to scheme.surfaceContainer,
                    "surfaceContainerHigh" to scheme.surfaceContainerHigh,
                    "surfaceContainerHighest" to scheme.surfaceContainerHighest,
                )
                for ((index, pair) in names.withIndex()) {
                    assertEquals(
                        row.label + " " + mode + " " + pair.first,
                        Color(expected[index]),
                        pair.second,
                    )
                }
            }
        }
    }

    @Test
    fun overlaySurfaceFollowsLayeredMode() {
        for (row in groundTruth) {
            assertEquals(
                row.label + " flat overlay",
                row.scheme.surface,
                row.colors(layered = false).overlaySurface(row.scheme),
            )
            assertEquals(
                row.label + " layered overlay",
                Color(row.layeredCard),
                row.colors(layered = true).overlaySurface(row.scheme),
            )
        }
    }

    @Test
    fun hairlinesUseTheSourceAlphas() {
        for (row in groundTruth) {
            val flat = row.colors(layered = false)
            val layered = row.colors(layered = true)
            val outlineVariant = row.scheme.outlineVariant
            val onSurface = row.scheme.onSurface
            val hairlineAlpha = if (row.dark) 0.08f else 0.06f
            val strongAlpha = if (row.dark) 0.26f else 0.38f
            val layeredHairlineAlpha = if (row.dark) 0.12f else 0.08f
            val layeredStrongAlpha = if (row.dark) 0.26f else 0.20f
            assertEquals(
                row.label + " flat hairline",
                outlineVariant.copy(alpha = hairlineAlpha),
                flat.hairline,
            )
            assertEquals(
                row.label + " flat hairlineStrong",
                outlineVariant.copy(alpha = strongAlpha),
                flat.hairlineStrong,
            )
            assertEquals(
                row.label + " layered hairline",
                onSurface.copy(alpha = layeredHairlineAlpha),
                layered.hairline,
            )
            assertEquals(
                row.label + " layered hairlineStrong",
                onSurface.copy(alpha = layeredStrongAlpha),
                layered.hairlineStrong,
            )
        }
    }

    @Test
    fun unharmonizedTokensAreTheSourceConstants() {
        val light = AppSemanticColors.light(paletteById("default").light)
        assertEquals(Color(0xFF1B5E20), light.onSuccessContainer)
        assertEquals(Color(0xFF4E2600), light.onWarningContainer)
        assertEquals(Color(0xFFFFD700).copy(alpha = 0.55f), light.searchHighlight)
        assertEquals(
            listOf(
                Color(0xFF2563EB),
                Color(0xFF0F8F83),
                Color(0xFFEA580C),
                Color(0xFF8B5CF6),
                Color(0xFFE11D48),
                Color(0xFF16A34A),
                Color(0xFFCA8A04),
                Color(0xFF0891B2),
                Color(0xFFDB2777),
                Color(0xFF4F46E5),
            ),
            light.chartSeries,
        )

        val dark = AppSemanticColors.dark(paletteById("default").dark)
        assertEquals(Color(0xFFC8E6C9), dark.onSuccessContainer)
        assertEquals(Color(0xFFFFE8CC), dark.onWarningContainer)
        assertEquals(Color(0xFFB8860B).copy(alpha = 0.55f), dark.searchHighlight)
        assertEquals(
            listOf(
                Color(0xFF60A5FA),
                Color(0xFF5EEAD4),
                Color(0xFFFB923C),
                Color(0xFFA78BFA),
                Color(0xFFFB7185),
                Color(0xFF86EFAC),
                Color(0xFFFACC15),
                Color(0xFF67E8F9),
                Color(0xFFF472B6),
                Color(0xFF818CF8),
            ),
            dark.chartSeries,
        )
    }

    @Test
    fun ofDispatchesOnTheResolvedBrightness() {
        val palette = paletteById("green")
        assertEquals(
            AppSemanticColors.light(palette.light),
            AppSemanticColors.of(palette.light, dark = false),
        )
        assertEquals(
            AppSemanticColors.dark(palette.dark),
            AppSemanticColors.of(palette.dark, dark = true),
        )
        assertEquals(
            AppSemanticColors.dark(palette.dark, layered = true),
            AppSemanticColors.of(palette.dark, dark = true, layered = true),
        )
        assertEquals(false, AppSemanticColors.of(palette.light, dark = false).isDark)
        assertEquals(true, AppSemanticColors.of(palette.dark, dark = true).isDark)
    }
}

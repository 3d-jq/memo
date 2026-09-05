package com.psyche.memo.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.psyche.memo.ui.theme.hct.Blend

/**
 * Port of `lib/theme/app_semantic_colors.dart`: colors that have no dedicated
 * [ColorScheme] role. Every value is derived from (or harmonized with) the
 * active scheme so palette changes flow through automatically.
 *
 * Compose's [ColorScheme] carries no `brightness`, so [isDark] holds the
 * resolved brightness Flutter would read off `Theme.of(context)`.
 */
data class AppSemanticColors(
    val surfaceFill: Color,
    val surfaceCard: Color,
    val surfaceCardFill: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val searchHighlight: Color,
    val chartSeries: List<Color>,
    val layered: Boolean = false,
    val isDark: Boolean = false,
) {
    /**
     * Dialog / sheet / menu panel fill. Layered mode sits on the bright card
     * layer; the flat mode uses the page surface so overlays match the old look.
     */
    fun overlaySurface(scheme: ColorScheme): Color = if (layered) surfaceCard else scheme.surface

    companion object {
        fun light(cs: ColorScheme, layered: Boolean = false): AppSemanticColors {
            val ladder = SurfaceLadder.fromScheme(cs, dark = false, layered = layered)
            return AppSemanticColors(
                surfaceFill = ladder.surfaceFill,
                surfaceCard = ladder.card,
                surfaceCardFill = ladder.surfaceCardFill,
                hairline = ladder.hairline,
                hairlineStrong = ladder.hairlineStrong,
                success = harmonizeWith(Color(0xFF2E7D32), cs.primary),
                successContainer = harmonizeWith(Color(0xFFA5D6A7), cs.primary),
                onSuccessContainer = Color(0xFF1B5E20),
                warning = harmonizeWith(Color(0xFFF57C00), cs.primary),
                warningContainer = harmonizeWith(Color(0xFFFFE0B2), cs.primary),
                onWarningContainer = Color(0xFF4E2600),
                searchHighlight = Color(0xFFFFD700).copy(alpha = 0.55f),
                chartSeries = listOf(
                    Color(0xFF2563EB),
                    Color(0xFF0F8F83),
                    Color(0xFFEA580C),
                    Color(0xFF8B5CF6),
                    Color(0xFFE11D48),
                    Color(0xFF16A34A),
                    Color(0xFFCA8A04),
                    Color(0xFF0891B2),
                ),
                layered = layered,
            )
        }

        fun dark(cs: ColorScheme, layered: Boolean = false): AppSemanticColors {
            val ladder = SurfaceLadder.fromScheme(cs, dark = true, layered = layered)
            return AppSemanticColors(
                surfaceFill = ladder.surfaceFill,
                surfaceCard = ladder.card,
                surfaceCardFill = ladder.surfaceCardFill,
                hairline = ladder.hairline,
                hairlineStrong = ladder.hairlineStrong,
                success = harmonizeWith(Color(0xFF81C784), cs.primary),
                successContainer = harmonizeWith(Color(0xFF1B5E20), cs.primary),
                onSuccessContainer = Color(0xFFC8E6C9),
                warning = harmonizeWith(Color(0xFFFFB74D), cs.primary),
                warningContainer = harmonizeWith(Color(0xFF8D4E00), cs.primary),
                onWarningContainer = Color(0xFFFFE8CC),
                searchHighlight = Color(0xFFB8860B).copy(alpha = 0.55f),
                chartSeries = listOf(
                    Color(0xFF60A5FA),
                    Color(0xFF5EEAD4),
                    Color(0xFFFB923C),
                    Color(0xFFA78BFA),
                    Color(0xFFFB7185),
                    Color(0xFF86EFAC),
                    Color(0xFFFACC15),
                    Color(0xFF67E8F9),
                ),
                layered = layered,
                isDark = true,
            )
        }

        fun of(cs: ColorScheme, dark: Boolean, layered: Boolean = false): AppSemanticColors =
            if (dark) dark(cs, layered) else light(cs, layered)

        /** `dynamic_color`'s `Color.harmonizeWith`, i.e. HCT hue rotation toward [source]. */
        private fun harmonizeWith(design: Color, source: Color): Color =
            Color(Blend.harmonize(design.toArgb(), source.toArgb()))
    }
}

val LocalSemanticColors = staticCompositionLocalOf {
    AppSemanticColors.light(lightColorScheme())
}

@Composable
fun ProvideSemanticColors(
    scheme: ColorScheme,
    dark: Boolean,
    layered: Boolean = false,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalSemanticColors provides AppSemanticColors.of(scheme, dark, layered),
        content = content,
    )
}

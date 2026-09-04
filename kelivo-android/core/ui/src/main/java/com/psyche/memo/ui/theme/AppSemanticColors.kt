package com.psyche.memo.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Semantic colors ported from lib/theme/app_semantic_colors.dart plus the
 * non-layered branch of SurfaceLadder.fromScheme.
 *
 * Memo paints UI through these tokens instead of raw Material roles, so the
 * native port resolves them the same way: the card sits one step above the page
 * surface and hairlines are `outlineVariant` at a fixed alpha.
 */
data class AppSemanticColors(
    val surfaceCard: Color,
    val hairline: Color,
    val hairlineStrong: Color,
) {
    companion object {
        fun of(scheme: ColorScheme, dark: Boolean): AppSemanticColors {
            val page = scheme.surface
            return AppSemanticColors(
                // Color.alphaBlend(white @ a, page) with an opaque page is a
                // plain sRGB mix, exactly what Color.lerp does.
                surfaceCard = lerp(page, Color.White, if (dark) 0.10f else 0.96f),
                hairline = scheme.outlineVariant.copy(alpha = if (dark) 0.08f else 0.06f),
                hairlineStrong = scheme.outlineVariant.copy(alpha = if (dark) 0.26f else 0.38f),
            )
        }
    }
}

val LocalSemanticColors = staticCompositionLocalOf {
    AppSemanticColors(Color.Unspecified, Color.Unspecified, Color.Unspecified)
}

@Composable
fun ProvideSemanticColors(scheme: ColorScheme, dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalSemanticColors provides AppSemanticColors.of(scheme, dark),
        content = content,
    )
}

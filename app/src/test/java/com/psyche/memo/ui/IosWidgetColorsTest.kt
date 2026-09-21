package com.psyche.memo.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.psyche.memo.ui.theme.lerpColor
import com.psyche.memo.ui.theme.withAlpha
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every derived color the iOS widgets compute at runtime, pinned to the value
 * dart:ui produces. The table is machine-generated (a pure-Dart harness that
 * ports painting.dart alphaBlend / Color.lerp / estimateBrightnessForColor and
 * reads the same Palettes.kt this app ships) so no literal was transcribed by
 * hand. `flutter test` cannot run here (Dart 3.11.5 vs the required ^3.12.1),
 * which is exactly why the numbers are frozen into a fixture.
 */
class IosWidgetColorsTest {

    private class Row(
        val palette: String,
        val dark: Boolean,
        val primary: Long,
        val onPrimary: Long,
        val surface: Long,
        val onSurface: Long,
        val surfaceContainerLowest: Long,
        val outlineVariant: Long,
        val swOffTrack: Long,
        val swThumbOn: Long,
        val swThumbOff: Long,
        val swBorderEnabled: Long,
        val swBorderDisabled: Long,
        val cbBorder: Long,
        val cbDisabledFill: Long,
        val cbDisabledCheck: Long,
        val surfaceFill: Long,
        val tileNeutralPressed: Long,
        val tileNeutralFg: Long,
        val tileNeutralBorder: Long,
        val tileTintBg: Long,
        val tileTintPressed: Long,
        val tileTintBorder: Long,
        val contrastOnPrimary: Long,
        val pressColor: Long,
    ) {
        val cs
            get() = if (dark) {
                darkColorScheme(
                    primary = Color(primary),
                    onPrimary = Color(onPrimary),
                    surface = Color(surface),
                    onSurface = Color(onSurface),
                    surfaceContainerLowest = Color(surfaceContainerLowest),
                    outlineVariant = Color(outlineVariant),
                )
            } else {
                lightColorScheme(
                    primary = Color(primary),
                    onPrimary = Color(onPrimary),
                    surface = Color(surface),
                    onSurface = Color(onSurface),
                    surfaceContainerLowest = Color(surfaceContainerLowest),
                    outlineVariant = Color(outlineVariant),
                )
            }

        val label get() = "$palette ${if (dark) "dark" else "light"}"
    }

    private val groundTruth = listOf(
        Row(
            palette = "default",
            dark = false,
            primary = 0xFF4D5C92L,
            onPrimary = 0xFFFFFFFFL,
            surface = 0xFFF7F7F7L,
            onSurface = 0xFF202020L,
            surfaceContainerLowest = 0xFFFDFDFDL,
            outlineVariant = 0xFF000000L,
            swOffTrack = 0x14202020L,
            swThumbOn = 0xFFFDFDFDL,
            swThumbOff = 0xFFFDFDFDL,
            swBorderEnabled = 0x21202020L,
            swBorderDisabled = 0x12202020L,
            cbBorder = 0x33202020L,
            cbDisabledFill = 0x804D5C92L,
            cbDisabledCheck = 0x80FFFFFFL,
            surfaceFill = 0xFFECECECL,
            tileNeutralPressed = 0xFFE2E2E2L,
            tileNeutralFg = 0xE6202020L,
            tileNeutralBorder = 0x59000000L,
            tileTintBg = 0x1F4D5C92L,
            tileTintPressed = 0x2A3F4A6FL,
            tileTintBorder = 0x734D5C92L,
            contrastOnPrimary = 0xFFFFFFFFL,
            pressColor = 0xFF969696L,
        ),
        Row(
            palette = "default",
            dark = true,
            primary = 0xFFB6C4FFL,
            onPrimary = 0xFF1D2D61L,
            surface = 0xFF121213L,
            onSurface = 0xFFF9F9F9L,
            surfaceContainerLowest = 0xFF0D0D0EL,
            outlineVariant = 0xFFFFFFFFL,
            swOffTrack = 0xFF171718L,
            swThumbOn = 0xFF171718L,
            swThumbOff = 0xFF656566L,
            swBorderEnabled = 0x28F9F9F9L,
            swBorderDisabled = 0x15F9F9F9L,
            cbBorder = 0x3DF9F9F9L,
            cbDisabledFill = 0x80B6C4FFL,
            cbDisabledCheck = 0x801D2D61L,
            surfaceFill = 0xFF373738L,
            tileNeutralPressed = 0xFF434344L,
            tileNeutralFg = 0xE6F9F9F9L,
            tileNeutralBorder = 0x59FFFFFFL,
            tileTintBg = 0x33B6C4FFL,
            tileTintPressed = 0x3FC6D1FEL,
            tileTintBorder = 0x8CB6C4FFL,
            contrastOnPrimary = 0xFF000000L,
            pressColor = 0xFF7A7A7AL,
        ),
        Row(
            palette = "blue",
            dark = false,
            primary = 0xFF3E5E98L,
            onPrimary = 0xFFFFFFFFL,
            surface = 0xFFFDFBFFL,
            onSurface = 0xFF1B1B1DL,
            surfaceContainerLowest = 0xFFFEFEFFL,
            outlineVariant = 0xFFC4C6D0L,
            swOffTrack = 0x141B1B1DL,
            swThumbOn = 0xFFFEFEFFL,
            swThumbOff = 0xFFFEFEFFL,
            swBorderEnabled = 0x211B1B1DL,
            swBorderDisabled = 0x121B1B1DL,
            cbBorder = 0x331B1B1DL,
            cbDisabledFill = 0x803E5E98L,
            cbDisabledCheck = 0x80FFFFFFL,
            surfaceFill = 0xFFF2F0F4L,
            tileNeutralPressed = 0xFFE7E5E9L,
            tileNeutralFg = 0xE61B1B1DL,
            tileNeutralBorder = 0x59C4C6D0L,
            tileTintBg = 0x1F3E5E98L,
            tileTintPressed = 0x2A334A73L,
            tileTintBorder = 0x733E5E98L,
            contrastOnPrimary = 0xFFFFFFFFL,
            pressColor = 0xFF979699L,
        ),
        Row(
            palette = "blue",
            dark = true,
            primary = 0xFFACC7FFL,
            onPrimary = 0xFF032F67L,
            surface = 0xFF1B1B1DL,
            onSurface = 0xFFE3E1E6L,
            surfaceContainerLowest = 0xFF131315L,
            outlineVariant = 0xFF44464EL,
            swOffTrack = 0xFF1F1F21L,
            swThumbOn = 0xFF1F1F21L,
            swThumbOff = 0xFF636265L,
            swBorderEnabled = 0x28E3E1E6L,
            swBorderDisabled = 0x15E3E1E6L,
            cbBorder = 0x3DE3E1E6L,
            cbDisabledFill = 0x80ACC7FFL,
            cbDisabledCheck = 0x80032F67L,
            surfaceFill = 0xFF3B3B3DL,
            tileNeutralPressed = 0xFF454547L,
            tileNeutralFg = 0xE6E3E1E6L,
            tileNeutralBorder = 0x5944464EL,
            tileTintBg = 0x33ACC7FFL,
            tileTintPressed = 0x3FB9CDF9L,
            tileTintBorder = 0x8CACC7FFL,
            contrastOnPrimary = 0xFF000000L,
            pressColor = 0xFF757477L,
        ),
        Row(
            palette = "green",
            dark = false,
            primary = 0xFF166C47L,
            onPrimary = 0xFFFFFFFFL,
            surface = 0xFFFBFDF8L,
            onSurface = 0xFF191C1AL,
            surfaceContainerLowest = 0xFFFEFEFDL,
            outlineVariant = 0xFFC0C9C0L,
            swOffTrack = 0x14191C1AL,
            swThumbOn = 0xFFFEFEFDL,
            swThumbOff = 0xFFFEFEFDL,
            swBorderEnabled = 0x21191C1AL,
            swBorderDisabled = 0x12191C1AL,
            cbBorder = 0x33191C1AL,
            cbDisabledFill = 0x80166C47L,
            cbDisabledCheck = 0x80FFFFFFL,
            surfaceFill = 0xFFF0F2EDL,
            tileNeutralPressed = 0xFFE5E7E2L,
            tileNeutralFg = 0xE6191C1AL,
            tileNeutralBorder = 0x59C0C9C0L,
            tileTintBg = 0x1F166C47L,
            tileTintPressed = 0x2A175439L,
            tileTintBorder = 0x73166C47L,
            contrastOnPrimary = 0xFFFFFFFFL,
            pressColor = 0xFF959894L,
        ),
        Row(
            palette = "green",
            dark = true,
            primary = 0xFF87D7AAL,
            onPrimary = 0xFF003921L,
            surface = 0xFF191C1AL,
            onSurface = 0xFFE1E3DFL,
            surfaceContainerLowest = 0xFF121413L,
            outlineVariant = 0xFF404942L,
            swOffTrack = 0xFF1D201EL,
            swThumbOn = 0xFF1D201EL,
            swThumbOff = 0xFF616461L,
            swBorderEnabled = 0x28E1E3DFL,
            swBorderDisabled = 0x15E1E3DFL,
            cbBorder = 0x3DE1E3DFL,
            cbDisabledFill = 0x8087D7AAL,
            cbDisabledCheck = 0x80003921L,
            surfaceFill = 0xFF393C3AL,
            tileNeutralPressed = 0xFF434644L,
            tileNeutralFg = 0xE6E1E3DFL,
            tileNeutralBorder = 0x59404942L,
            tileTintBg = 0x3387D7AAL,
            tileTintPressed = 0x3F9DDAB7L,
            tileTintBorder = 0x8C87D7AAL,
            contrastOnPrimary = 0xFF000000L,
            pressColor = 0xFF737673L,
        ),
        Row(
            palette = "purple",
            dark = false,
            primary = 0xFF5D5698L,
            onPrimary = 0xFFFFFFFFL,
            surface = 0xFFFFFBFFL,
            onSurface = 0xFF1C1B1FL,
            surfaceContainerLowest = 0xFFFFFEFFL,
            outlineVariant = 0xFFC9C4CFL,
            swOffTrack = 0x141C1B1FL,
            swThumbOn = 0xFFFFFEFFL,
            swThumbOff = 0xFFFFFEFFL,
            swBorderEnabled = 0x211C1B1FL,
            swBorderDisabled = 0x121C1B1FL,
            cbBorder = 0x331C1B1FL,
            cbDisabledFill = 0x805D5698L,
            cbDisabledCheck = 0x80FFFFFFL,
            surfaceFill = 0xFFF4F0F4L,
            tileNeutralPressed = 0xFFE9E5E9L,
            tileNeutralFg = 0xE61C1B1FL,
            tileNeutralBorder = 0x59C9C4CFL,
            tileTintBg = 0x1F5D5698L,
            tileTintPressed = 0x2A494473L,
            tileTintBorder = 0x735D5698L,
            contrastOnPrimary = 0xFFFFFFFFL,
            pressColor = 0xFF99969AL,
        ),
        Row(
            palette = "purple",
            dark = true,
            primary = 0xFFC8BFFFL,
            onPrimary = 0xFF2E2766L,
            surface = 0xFF1C1B1FL,
            onSurface = 0xFFE6E1E6L,
            surfaceContainerLowest = 0xFF141316L,
            outlineVariant = 0xFF47464FL,
            swOffTrack = 0xFF201F23L,
            swThumbOn = 0xFF201F23L,
            swThumbOff = 0xFF656267L,
            swBorderEnabled = 0x28E6E1E6L,
            swBorderDisabled = 0x15E6E1E6L,
            cbBorder = 0x3DE6E1E6L,
            cbDisabledFill = 0x80C8BFFFL,
            cbDisabledCheck = 0x802E2766L,
            surfaceFill = 0xFF3C3B3FL,
            tileNeutralPressed = 0xFF464549L,
            tileNeutralFg = 0xE6E6E1E6L,
            tileNeutralBorder = 0x5947464FL,
            tileTintBg = 0x33C8BFFFL,
            tileTintPressed = 0x3FCFC7F9L,
            tileTintBorder = 0x8CC8BFFFL,
            contrastOnPrimary = 0xFF000000L,
            pressColor = 0xFF777479L,
        ),
        Row(
            palette = "yellow",
            dark = false,
            primary = 0xFF855304L,
            onPrimary = 0xFFFFFFFFL,
            surface = 0xFFFFFBF9L,
            onSurface = 0xFF1F1B16L,
            surfaceContainerLowest = 0xFFFFFEFDL,
            outlineVariant = 0xFFD4C4B4L,
            swOffTrack = 0x141F1B16L,
            swThumbOn = 0xFFFFFEFDL,
            swThumbOff = 0xFFFFFEFDL,
            swBorderEnabled = 0x211F1B16L,
            swBorderDisabled = 0x121F1B16L,
            cbBorder = 0x331F1B16L,
            cbDisabledFill = 0x80855304L,
            cbDisabledCheck = 0x80FFFFFFL,
            surfaceFill = 0xFFF4F0EEL,
            tileNeutralPressed = 0xFFE9E5E3L,
            tileNeutralFg = 0xE61F1B16L,
            tileNeutralBorder = 0x59D4C4B4L,
            tileTintBg = 0x1F855304L,
            tileTintPressed = 0x2A664209L,
            tileTintBorder = 0x73855304L,
            contrastOnPrimary = 0xFFFFFFFFL,
            pressColor = 0xFF9A9693L,
        ),
        Row(
            palette = "yellow",
            dark = true,
            primary = 0xFFFDB967L,
            onPrimary = 0xFF482A00L,
            surface = 0xFF1F1B16L,
            onSurface = 0xFFEBE0D9L,
            surfaceContainerLowest = 0xFF161310L,
            outlineVariant = 0xFF4F4539L,
            swOffTrack = 0xFF231F1AL,
            swThumbOn = 0xFF231F1AL,
            swThumbOff = 0xFF68625CL,
            swBorderEnabled = 0x28EBE0D9L,
            swBorderDisabled = 0x15EBE0D9L,
            cbBorder = 0x3DEBE0D9L,
            cbDisabledFill = 0x80FDB967L,
            cbDisabledCheck = 0x80482A00L,
            surfaceFill = 0xFF403B35L,
            tileNeutralPressed = 0xFF4A453FL,
            tileNeutralFg = 0xE6EBE0D9L,
            tileNeutralBorder = 0x594F4539L,
            tileTintBg = 0x33FDB967L,
            tileTintPressed = 0x3FF9C283L,
            tileTintBorder = 0x8CFDB967L,
            contrastOnPrimary = 0xFF000000L,
            pressColor = 0xFF7B746EL,
        ),
        Row(
            palette = "smoky_rose",
            dark = false,
            primary = 0xFF824E6CL,
            onPrimary = 0xFFFFFFFFL,
            surface = 0xFFFFFBFFL,
            onSurface = 0xFF201A1DL,
            surfaceContainerLowest = 0xFFFFFEFFL,
            outlineVariant = 0xFFD4C2C9L,
            swOffTrack = 0x14201A1DL,
            swThumbOn = 0xFFFFFEFFL,
            swThumbOff = 0xFFFFFEFFL,
            swBorderEnabled = 0x21201A1DL,
            swBorderDisabled = 0x12201A1DL,
            cbBorder = 0x33201A1DL,
            cbDisabledFill = 0x80824E6CL,
            cbDisabledCheck = 0x80FFFFFFL,
            surfaceFill = 0xFFF4F0F4L,
            tileNeutralPressed = 0xFFE9E5E9L,
            tileNeutralFg = 0xE6201A1DL,
            tileNeutralBorder = 0x59D4C2C9L,
            tileTintBg = 0x1F824E6CL,
            tileTintPressed = 0x2A643E54L,
            tileTintBorder = 0x73824E6CL,
            contrastOnPrimary = 0xFFFFFFFFL,
            pressColor = 0xFF9B9699L,
        ),
        Row(
            palette = "smoky_rose",
            dark = true,
            primary = 0xFFF5B3D6L,
            onPrimary = 0xFF4E203CL,
            surface = 0xFF201A1DL,
            onSurface = 0xFFECDFE3L,
            surfaceContainerLowest = 0xFF171315L,
            outlineVariant = 0xFF504349L,
            swOffTrack = 0xFF241E21L,
            swThumbOn = 0xFF241E21L,
            swThumbOff = 0xFF696164L,
            swBorderEnabled = 0x28ECDFE3L,
            swBorderDisabled = 0x15ECDFE3L,
            cbBorder = 0x3DECDFE3L,
            cbDisabledFill = 0x80F5B3D6L,
            cbDisabledCheck = 0x804E203CL,
            surfaceFill = 0xFF413A3DL,
            tileNeutralPressed = 0xFF4B4447L,
            tileNeutralFg = 0xE6ECDFE3L,
            tileNeutralBorder = 0x59504349L,
            tileTintBg = 0x33F5B3D6L,
            tileTintPressed = 0x3FF3BED9L,
            tileTintBorder = 0x8CF5B3D6L,
            contrastOnPrimary = 0xFF000000L,
            pressColor = 0xFF7C7376L,
        ),
        Row(
            palette = "terracotta",
            dark = false,
            primary = 0xFF8B4E3BL,
            onPrimary = 0xFFFFFFFFL,
            surface = 0xFFFFFBFFL,
            onSurface = 0xFF221A18L,
            surfaceContainerLowest = 0xFFFFFEFFL,
            outlineVariant = 0xFFD9C2BBL,
            swOffTrack = 0x14221A18L,
            swThumbOn = 0xFFFFFEFFL,
            swThumbOff = 0xFFFFFEFFL,
            swBorderEnabled = 0x21221A18L,
            swBorderDisabled = 0x12221A18L,
            cbBorder = 0x33221A18L,
            cbDisabledFill = 0x808B4E3BL,
            cbDisabledCheck = 0x80FFFFFFL,
            surfaceFill = 0xFFF4F0F3L,
            tileNeutralPressed = 0xFFEAE5E8L,
            tileNeutralFg = 0xE6221A18L,
            tileNeutralBorder = 0x59D9C2BBL,
            tileTintBg = 0x1F8B4E3BL,
            tileTintPressed = 0x2A6B3E30L,
            tileTintBorder = 0x738B4E3BL,
            contrastOnPrimary = 0xFFFFFFFFL,
            pressColor = 0xFF9C9697L,
        ),
        Row(
            palette = "terracotta",
            dark = true,
            primary = 0xFFFFB59EL,
            onPrimary = 0xFF522211L,
            surface = 0xFF221A18L,
            onSurface = 0xFFEFDFDBL,
            surfaceContainerLowest = 0xFF181311L,
            outlineVariant = 0xFF53433EL,
            swOffTrack = 0xFF261E1CL,
            swThumbOn = 0xFF261E1CL,
            swThumbOff = 0xFF6C615EL,
            swBorderEnabled = 0x28EFDFDBL,
            swBorderDisabled = 0x15EFDFDBL,
            cbBorder = 0x3DEFDFDBL,
            cbDisabledFill = 0x80FFB59EL,
            cbDisabledCheck = 0x80522211L,
            surfaceFill = 0xFF433A37L,
            tileNeutralPressed = 0xFF4D4441L,
            tileNeutralFg = 0xE6EFDFDBL,
            tileNeutralBorder = 0x5953433EL,
            tileTintBg = 0x33FFB59EL,
            tileTintPressed = 0x3FFBBFADL,
            tileTintBorder = 0x8CFFB59EL,
            contrastOnPrimary = 0xFF000000L,
            pressColor = 0xFF7E7370L,
        ),
        Row(
            palette = "monochrome",
            dark = false,
            primary = 0xFF000000L,
            onPrimary = 0xFFFFFFFFL,
            surface = 0xFFF7F7F7L,
            onSurface = 0xFF202020L,
            surfaceContainerLowest = 0xFFFDFDFDL,
            outlineVariant = 0xFF000000L,
            swOffTrack = 0x14202020L,
            swThumbOn = 0xFFFDFDFDL,
            swThumbOff = 0xFFFDFDFDL,
            swBorderEnabled = 0x21202020L,
            swBorderDisabled = 0x12202020L,
            cbBorder = 0x33202020L,
            cbDisabledFill = 0x80000000L,
            cbDisabledCheck = 0x80FFFFFFL,
            surfaceFill = 0xFFECECECL,
            tileNeutralPressed = 0xFFE2E2E2L,
            tileNeutralFg = 0xE6202020L,
            tileNeutralBorder = 0x59000000L,
            tileTintBg = 0x1F000000L,
            tileTintPressed = 0x2A0A0A0AL,
            tileTintBorder = 0x73000000L,
            contrastOnPrimary = 0xFFFFFFFFL,
            pressColor = 0xFF969696L,
        ),
        Row(
            palette = "monochrome",
            dark = true,
            primary = 0xFFFFFFFFL,
            onPrimary = 0xFF000000L,
            surface = 0xFF121213L,
            onSurface = 0xFFF9F9F9L,
            surfaceContainerLowest = 0xFF0D0D0EL,
            outlineVariant = 0xFFFFFFFFL,
            swOffTrack = 0xFF171718L,
            swThumbOn = 0xFF171718L,
            swThumbOff = 0xFF656566L,
            swBorderEnabled = 0x28F9F9F9L,
            swBorderDisabled = 0x15F9F9F9L,
            cbBorder = 0x3DF9F9F9L,
            cbDisabledFill = 0x80FFFFFFL,
            cbDisabledCheck = 0x80000000L,
            surfaceFill = 0xFF373738L,
            tileNeutralPressed = 0xFF434344L,
            tileNeutralFg = 0xE6F9F9F9L,
            tileNeutralBorder = 0x59FFFFFFL,
            tileTintBg = 0x33FFFFFFL,
            tileTintPressed = 0x3FFEFEFEL,
            tileTintBorder = 0x8CFFFFFFL,
            contrastOnPrimary = 0xFF000000L,
            pressColor = 0xFF7A7A7AL,
        ),
        Row(
            palette = "doc_theme",
            dark = false,
            primary = 0xFF00B96BL,
            onPrimary = 0xFFFFFFFFL,
            surface = 0xFFF7F7F7L,
            onSurface = 0xFF202020L,
            surfaceContainerLowest = 0xFFFDFDFDL,
            outlineVariant = 0xFF000000L,
            swOffTrack = 0x14202020L,
            swThumbOn = 0xFFFDFDFDL,
            swThumbOff = 0xFFFDFDFDL,
            swBorderEnabled = 0x21202020L,
            swBorderDisabled = 0x12202020L,
            cbBorder = 0x33202020L,
            cbDisabledFill = 0x8000B96BL,
            cbDisabledCheck = 0x80FFFFFFL,
            surfaceFill = 0xFFECECECL,
            tileNeutralPressed = 0xFFE2E2E2L,
            tileNeutralFg = 0xE6202020L,
            tileNeutralBorder = 0x59000000L,
            tileTintBg = 0x1F00B96BL,
            tileTintPressed = 0x2A0A8A54L,
            tileTintBorder = 0x7300B96BL,
            contrastOnPrimary = 0xFF000000L,
            pressColor = 0xFF969696L,
        ),
        Row(
            palette = "doc_theme",
            dark = true,
            primary = 0xFF00B96BL,
            onPrimary = 0xFFFFFFFFL,
            surface = 0xFF121213L,
            onSurface = 0xFFF9F9F9L,
            surfaceContainerLowest = 0xFF0D0D0EL,
            outlineVariant = 0xFFFFFFFFL,
            swOffTrack = 0xFF171718L,
            swThumbOn = 0xFF171718L,
            swThumbOff = 0xFF656566L,
            swBorderEnabled = 0x28F9F9F9L,
            swBorderDisabled = 0x15F9F9F9L,
            cbBorder = 0x3DF9F9F9L,
            cbDisabledFill = 0x8000B96BL,
            cbDisabledCheck = 0x80FFFFFFL,
            surfaceFill = 0xFF373738L,
            tileNeutralPressed = 0xFF434344L,
            tileNeutralFg = 0xE6F9F9F9L,
            tileNeutralBorder = 0x59FFFFFFL,
            tileTintBg = 0x3300B96BL,
            tileTintPressed = 0x3F3CC88DL,
            tileTintBorder = 0x8C00B96BL,
            contrastOnPrimary = 0xFF000000L,
            pressColor = 0xFF7A7A7AL,
        ),
    )

    private fun argb(color: Color): Long = color.toArgb().toLong() and 0xFFFFFFFFL

    private fun assertArgb(expected: Long, actual: Color, what: String, row: Row) {
        assertEquals("${row.label}: $what", expected, argb(actual))
    }

    @Test
    fun switchTrackThumbAndBorderMatchDartUi() {
        for (row in groundTruth) {
            assertArgb(row.swOffTrack, IosSwitchColors.offTrack(row.cs, row.dark), "offTrack", row)
            assertArgb(row.swThumbOn, IosSwitchColors.thumb(row.cs, row.dark, on = true), "thumbOn", row)
            assertArgb(row.swThumbOff, IosSwitchColors.thumb(row.cs, row.dark, on = false), "thumbOff", row)
            assertArgb(
                row.swBorderEnabled,
                IosSwitchColors.trackBorder(row.cs, row.dark, enabled = true),
                "trackBorderEnabled",
                row,
            )
            assertArgb(
                row.swBorderDisabled,
                IosSwitchColors.trackBorder(row.cs, row.dark, enabled = false),
                "trackBorderDisabled",
                row,
            )
        }
    }

    @Test
    fun checkboxRingDisabledFillsAndContrastMatchDartUi() {
        for (row in groundTruth) {
            assertArgb(row.cbBorder, IosCheckboxColors.ring(row.cs, row.dark), "ring", row)
            // ios_checkbox.dart L51-67 dims fill and check to 0.5 when onChanged
            // is null; the call sites tint with the palette primary.
            assertArgb(row.cbDisabledFill, withAlpha(row.cs.primary, 0.5), "disabledFill", row)
            assertArgb(row.cbDisabledCheck, withAlpha(row.cs.onPrimary, 0.5), "disabledCheck", row)
            assertArgb(
                row.contrastOnPrimary,
                IosCheckboxColors.contrastOn(row.cs.primary),
                "contrastOnPrimary",
                row,
            )
            assertArgb(
                row.onPrimary,
                IosCheckboxColors.checkmark(row.cs, activeColor = null, checkmarkColor = null),
                "checkmarkFallback",
                row,
            )
        }
    }

    @Test
    fun cupertinoSystemBlueBacksTheUntintedCheckbox() {
        // cupertino/theme.dart L22 -> CupertinoColors.systemBlue. The app never
        // installs a CupertinoTheme, so _kDefaultTheme answers.
        assertEquals(0xFF007AFFL, argb(IosCheckboxColors.cupertinoPrimary(isDark = false)))
        assertEquals(0xFF0A84FFL, argb(IosCheckboxColors.cupertinoPrimary(isDark = true)))
    }

    @Test
    fun tileButtonColorsMatchDartUi() {
        for (row in groundTruth) {
            val surfaceFill = Color(row.surfaceFill)
            val tint = row.cs.primary

            assertArgb(
                row.surfaceFill,
                IosTileColors.background(surfaceFill, row.dark, tint, tinted = false),
                "neutralBackground",
                row,
            )
            assertArgb(
                row.tileTintBg,
                IosTileColors.background(surfaceFill, row.dark, tint, tinted = true),
                "tintedBackground",
                row,
            )
            assertArgb(
                row.tileNeutralPressed,
                IosTileColors.pressed(row.cs, row.dark, tint, tinted = false, base = surfaceFill),
                "neutralPressed",
                row,
            )
            assertArgb(
                row.tileTintPressed,
                IosTileColors.pressed(
                    row.cs,
                    row.dark,
                    tint,
                    tinted = true,
                    base = IosTileColors.background(surfaceFill, row.dark, tint, tinted = true),
                ),
                "tintedPressed",
                row,
            )
            assertArgb(
                row.tileNeutralFg,
                IosTileColors.foreground(row.cs, tinted = false, tint = tint, foregroundColor = null),
                "neutralForeground",
                row,
            )
            assertArgb(
                row.primary,
                IosTileColors.foreground(row.cs, tinted = true, tint = tint, foregroundColor = null),
                "tintedForeground",
                row,
            )
            assertArgb(
                row.tileNeutralBorder,
                IosTileColors.border(row.cs, row.dark, tinted = false, tint = tint, borderColor = null),
                "neutralBorder",
                row,
            )
            assertArgb(
                row.tileTintBorder,
                IosTileColors.border(row.cs, row.dark, tinted = true, tint = tint, borderColor = null),
                "tintedBorder",
                row,
            )
        }
    }

    @Test
    fun animatedPressColorLerpMatchesDartUi() {
        for (row in groundTruth) {
            assertArgb(
                row.pressColor,
                lerpColor(row.cs.onSurface, row.cs.surface, 0.55),
                "pressLerp",
                row,
            )
        }
        val a = Color(0xFF112233)
        val b = Color(0xFFDDEEFF)
        assertEquals(a, lerpColor(a, b, 0.0))
        assertEquals(b, lerpColor(a, b, 1.0))
    }

    @Test
    fun callerOverridesWinOverEveryDerivedColor() {
        val row = groundTruth.first()
        val override = Color(0xFF123456)
        assertEquals(override, IosTileColors.foreground(row.cs, true, row.cs.primary, override))
        assertEquals(override, IosTileColors.border(row.cs, row.dark, true, row.cs.primary, override))
        assertEquals(override, IosCheckboxColors.checkmark(row.cs, row.cs.primary, override))
    }
}

package com.psyche.memo.ui.chat

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Translations of the `chat_bubble_style.dart` resolver / override-bag cases —
 * the fallbacks must match the pre-style hardcoded frosted/solid branch so that
 * an all-null override bag keeps today's pixels.
 */
class ChatBubbleStyleTest {

    private val cs = lightColorScheme(
        surfaceContainerHigh = Color(0xFF112233),
        outlineVariant = Color(0xFF445566),
        onSurface = Color(0xFF778899),
    )

    // ---- ChatBubbleStyle.fromWire (settings_provider 的枚举名) ----

    @Test
    fun fromWireMapsTheThreeNamesAndFallsBackToDefault() {
        assertEquals(ChatBubbleStyle.DEFAULT, ChatBubbleStyle.fromWire("default"))
        assertEquals(ChatBubbleStyle.FROSTED, ChatBubbleStyle.fromWire("frosted"))
        assertEquals(ChatBubbleStyle.SOLID, ChatBubbleStyle.fromWire("solid"))
        assertEquals(ChatBubbleStyle.DEFAULT, ChatBubbleStyle.fromWire(null))
        assertEquals(ChatBubbleStyle.DEFAULT, ChatBubbleStyle.fromWire("nonsense"))
    }

    // ---- BubbleOverrides ----

    @Test
    fun overrideJsonRoundTripsOnlyTheSetFields() {
        val overrides = BubbleOverrides(
            backgroundArgbDark = 0xFF010203.toInt(),
            borderWidth = 1.5,
            cornerRadius = 20.0,
        )
        assertEquals(overrides, BubbleOverrides.fromJson(overrides.toJson()))
    }

    @Test
    fun emptyAndMalformedJsonResolveToNone() {
        assertEquals(BubbleOverrides.NONE, BubbleOverrides.fromJson(null))
        assertEquals(BubbleOverrides.NONE, BubbleOverrides.fromJson(""))
        assertEquals(BubbleOverrides.NONE, BubbleOverrides.fromJson("{}"))
        assertEquals(BubbleOverrides.NONE, BubbleOverrides.fromJson("not json"))
        assertTrue(BubbleOverrides.NONE.isDefault)
    }

    @Test
    fun hasTextOverrideIsPerBrightness() {
        val light = BubbleOverrides(textArgbLight = 1)
        assertTrue(light.hasTextOverride(dark = false))
        assertFalse(light.hasTextOverride(dark = true))
        assertFalse(BubbleOverrides.NONE.hasTextOverride(dark = true))
    }

    // ---- resolveBubbleStyle (chat_bubble_style.dart:192-226) ----

    @Test
    fun resolveFallsBackToThemeAndHardcodedGeometry() {
        val solid = resolveBubbleStyle(cs, isDark = false, style = ChatBubbleStyle.SOLID, overrides = BubbleOverrides.NONE)
        assertEquals(cs.surfaceContainerHigh, solid.background)
        assertEquals(cs.outlineVariant.copy(alpha = 0.16f), solid.border)
        assertEquals(cs.onSurface, solid.text)
        assertEquals(0.8, solid.borderWidth, 0.0)
        assertEquals(16.0, solid.radius, 0.0)
        assertEquals(14.0, solid.blurSigma, 0.0)
    }

    @Test
    fun resolveFrostedUsesItsOwnOpacityAndBorderLadder() {
        val frosted = resolveBubbleStyle(cs, isDark = false, style = ChatBubbleStyle.FROSTED, overrides = BubbleOverrides.NONE)
        assertEquals(cs.surfaceContainerHigh.copy(alpha = 0.66f), frosted.background)
        assertEquals(cs.outlineVariant.copy(alpha = 0.14f), frosted.border)
    }

    @Test
    fun resolveHonoursOverridesAndBrightness() {
        val dark = BubbleOverrides(
            backgroundArgbLight = 0xFF111111.toInt(),
            backgroundArgbDark = 0xFF222222.toInt(),
            textArgbDark = 0xFF333333.toInt(),
            borderOpacity = 0.5,
            borderWidth = 2.0,
            cornerRadius = 4.0,
            blurSigma = 0.0,
            solidOpacity = 0.9,
        )
        val resolved = resolveBubbleStyle(cs, isDark = true, style = ChatBubbleStyle.SOLID, overrides = dark)
        assertEquals(Color(0xFF222222).copy(alpha = 0.9f), resolved.background)
        assertEquals(Color(0xFF333333), resolved.text)
        assertEquals(cs.outlineVariant.copy(alpha = 0.5f), resolved.border)
        assertEquals(2.0, resolved.borderWidth, 0.0)
        assertEquals(4.0, resolved.radius, 0.0)
        assertEquals(0.0, resolved.blurSigma, 0.0)
    }

    // ---- ChatBubbleStyles / palette (CMW:3898-3933) ----

    @Test
    fun overridesForSelectsTheRole() {
        val styles = ChatBubbleStyles(
            style = ChatBubbleStyle.SOLID,
            assistantOverrides = BubbleOverrides(cornerRadius = 4.0),
            userOverrides = BubbleOverrides(cornerRadius = 12.0),
        )
        assertEquals(4.0, styles.overridesFor(isUser = false).cornerRadius!!, 0.0)
        assertEquals(12.0, styles.overridesFor(isUser = true).cornerRadius!!, 0.0)
    }

    @Test
    fun defaultStylePaletteFollowsTheThemeNotTheOverrides() {
        val palette = computeChatSurfaceFg(
            cs,
            isDark = false,
            isUser = false,
            styles = ChatBubbleStyles(style = ChatBubbleStyle.DEFAULT),
        )
        assertEquals(defaultChatSurfaceFg(cs, isDark = false), palette)
    }

    @Test
    fun customStylePaletteUsesTheResolvedTextColorLadder() {
        val styles = ChatBubbleStyles(
            style = ChatBubbleStyle.SOLID,
            assistantOverrides = BubbleOverrides(textArgbDark = 0xFF00FF00.toInt()),
        )
        val palette = computeChatSurfaceFg(cs, isDark = true, isUser = false, styles = styles)
        val base = Color(0xFF00FF00)
        assertEquals(base.copy(alpha = 0.88f), palette.strong)
        assertEquals(base.copy(alpha = 0.76f), palette.medium)
        assertEquals(base.copy(alpha = 0.56f), palette.muted)
        assertEquals(base.copy(alpha = 0.72f), palette.body)
        assertEquals(base.copy(alpha = 0.16f), palette.divider)
        assertEquals(base.copy(alpha = 0.84f), palette.accent)
    }

    @Test
    fun customStyleWithoutTextOverrideFallsBackToOnSurface() {
        val palette = computeChatSurfaceFg(
            cs,
            isDark = false,
            isUser = true,
            styles = ChatBubbleStyles(style = ChatBubbleStyle.FROSTED),
        )
        assertEquals(cs.onSurface.copy(alpha = 0.78f), palette.strong)
        assertEquals(cs.onSurface.copy(alpha = 0.46f), palette.muted)
    }
}

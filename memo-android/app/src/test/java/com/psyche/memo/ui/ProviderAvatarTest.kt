package com.psyche.memo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 供应商头像（`provider_avatar.dart`）：取值 → 渲染来源的纯逻辑 + 内置图标白名单。
 *
 * 关键约束：`avatarValue` 必须存 **Dart 的 asset 串**（`assets/icons/openai.svg`），
 * 这样 Flutter 备份里的供应商头像在 Android 上照样能显示、反向也兼容。
 */
class ProviderAvatarTest {

    @Test
    fun emojiUrlFileAndLobehubResolveAsThemselves() {
        assertEquals(
            ProviderAvatarSource.Emoji("🙂"),
            providerAvatarSource("emoji", "🙂"),
        )
        assertEquals(
            ProviderAvatarSource.Url("https://x.test/a.png"),
            providerAvatarSource("url", "https://x.test/a.png"),
        )
        assertEquals(
            ProviderAvatarSource.File("/data/user/0/com.psyche.memo/files/avatars/a.png"),
            providerAvatarSource("file", "/data/user/0/com.psyche.memo/files/avatars/a.png"),
        )
        assertEquals(
            ProviderAvatarSource.Lobehub("OpenAI"),
            providerAvatarSource("lobehub", "OpenAI"),
        )
    }

    @Test
    fun iconTypeOnlyAcceptsWhitelistedAssets() {
        assertEquals(
            ProviderAvatarSource.Asset("assets/icons/openai.svg"),
            providerAvatarSource("icon", "assets/icons/openai.svg"),
        )
        // 白名单外（含只给文件名的旧值）一律回落到品牌图。
        assertEquals(ProviderAvatarSource.Brand, providerAvatarSource("icon", "openai.svg"))
        assertEquals(ProviderAvatarSource.Brand, providerAvatarSource("icon", "assets/icons/nope.svg"))
    }

    @Test
    fun emptyOrUnknownValuesFallBackToBrand() {
        assertEquals(ProviderAvatarSource.Brand, providerAvatarSource(null, null))
        assertEquals(ProviderAvatarSource.Brand, providerAvatarSource("emoji", ""))
        assertEquals(ProviderAvatarSource.Brand, providerAvatarSource("weird", "x"))
    }

    @Test
    fun catalogMatchesTheOriginalSelectableList() {
        // 59 项（brand_assets.dart BrandAssets.selectableIcons），全部指向随包资源。
        assertEquals(59, BrandIconCatalog.icons.size)
        assertEquals(59, BrandIconCatalog.icons.map { it.asset }.distinct().size)
        assertTrue(BrandIconCatalog.icons.all { it.asset.startsWith("assets/icons/") })
        assertEquals("assets/icons/openai.svg", BrandIconCatalog.icons.first().asset)
        assertNull(BrandIconCatalog.assetOrNull(""))
        assertNull(BrandIconCatalog.assetOrNull("assets/icons/nope.svg"))
        assertEquals(
            "assets/icons/gemini-color.svg",
            BrandIconCatalog.assetOrNull("assets/icons/gemini-color.svg"),
        )
    }

    @Test
    fun coilModelAndLobehubUrlsMatchTheDartHelpers() {
        assertEquals(
            "file:///android_asset/icons/openai.svg",
            BrandIconCatalog.coilModel("assets/icons/openai.svg"),
        )
        assertEquals(
            "https://unpkg.com/@lobehub/icons-static-svg@latest/icons/openai.svg",
            BrandIconCatalog.lobehubIconUrl(" OpenAI "),
        )
    }
}

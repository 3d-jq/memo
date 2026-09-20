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
    fun catalogMatchesTheOriginalSelectableListMinusTheBrandRow() {
        // 上游 selectableIcons 是 59 项，我们按品牌红线删掉 `("kelivo","Kelivo",
        // "assets/icons/kelivo.png")` 剩 **58 项**（那颗既把 "Kelivo" 显示给用户，
        // 指向的文件我们仓库里也没有 —— 选上就是一格打不开的空图）。
        assertEquals(58, BrandIconCatalog.icons.size)
        assertEquals(58, BrandIconCatalog.icons.map { it.asset }.distinct().size)
        assertTrue(BrandIconCatalog.icons.all { it.asset.startsWith("assets/icons/") })
        assertEquals("assets/icons/openai.svg", BrandIconCatalog.icons.first().asset)
        assertNull(BrandIconCatalog.assetOrNull(""))
        assertNull(BrandIconCatalog.assetOrNull("assets/icons/nope.svg"))
        assertEquals(
            "assets/icons/gemini-color.svg",
            BrandIconCatalog.assetOrNull("assets/icons/gemini-color.svg"),
        )
    }

    /**
     * 防再犯：表里每一颗都得**真在随包资源里**，且用户可见的 id/label 不许带 kelivo。
     * 之前那次就是表里有 `kelivo.png` 而 `assets/icons/` 里只有 `memo.png`。
     */
    @Test
    fun everyCatalogAssetExistsAndCarriesNoUpstreamBrand() {
        val icons = java.io.File("src/main/assets/icons")
        assertTrue("找不到随包图标目录 $icons", icons.isDirectory)
        val missing = BrandIconCatalog.icons.filter { !java.io.File(icons, it.asset.substringAfterLast('/')).isFile }
        assertEquals("这些头像资源不在仓库里：${missing.map { it.asset }}", emptyList<Any>(), missing)
        val leaked = BrandIconCatalog.icons.filter {
            it.id.contains("kelivo", true) || it.label.contains("kelivo", true) ||
                it.asset.contains("kelivo", true)
        }
        assertEquals("用户可见的头像表里不许有 kelivo：${leaked.map { it.id }}", emptyList<Any>(), leaked)
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

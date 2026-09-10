package com.psyche.memo.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [BrandAssets] after the icon set was synced wholesale from RikkaHub
 * (LobeHub icon collection). Three things this test guards against:
 *
 * 1. A mapping entry that resolves to a file which does not ship in
 *    `app/src/main/assets/icons/` — the avatar silently falls back to the
 *    initial letter, which is exactly the "ugly icon" bug we just fixed.
 * 2. Dark-theme tinting applied to a coloured brand logo (would erase the
 *    brand colour and show a flat monochrome glyph).
 * 3. Match ordering: a broad pattern earlier in the list shadowing a more
 *    specific one added later.
 */
class BrandAssetsTest {

    private val assetsDir = File("src/main/assets/icons")

    @Test
    fun `assets directory is reachable from the test working dir`() {
        assertTrue(
            "expected ${assetsDir.absolutePath} to exist — check the Gradle test working dir",
            assetsDir.isDirectory,
        )
    }

    @Test
    fun `every mapping target ships a real asset file`() {
        BrandAssets.debugMappingTargets().forEach { (pattern, asset) ->
            assertTrue(
                "mapping /$pattern/ -> $asset has no file on disk",
                File(assetsDir, asset).isFile,
            )
        }
    }

    @Test
    fun `no asset svg is empty or a mislabelled png`() {
        assetsDir.listFiles { f -> f.name.endsWith(".svg") }.orEmpty().forEach { svg ->
            val head = svg.readText().take(400)
            assertTrue("${svg.name} does not look like SVG", head.contains("<svg"))
        }
    }

    @Test
    fun `known providers resolve to their brand asset`() {
        val cases = mapOf(
            "OpenAI" to "openai.svg",
            "Claude" to "claude-color.svg",
            "DeepSeek" to "deepseek-color.svg",
            "Nvidia" to "nvidia-color.svg",
            "Cerebras" to "cerebras-color.svg",
            "Groq" to "groq.svg",
            "PPIO 派欧云" to "ppio-color.svg",
            "Vercel" to "vercel.svg",
            "TokenPony 小马算力" to "tokenpony.svg",
            "ElevenLabs" to "elevenlabs.svg",
            "LongCat" to "longcat-color.svg",
            "硅基流动" to "siliconflow.svg",
            "阶跃星辰" to "stepfun-color.svg",
            "小米 MiMo" to "xiaomimimo.svg",
            "302.AI" to "302ai.svg",
        )
        cases.forEach { (name, expected) ->
            assertEquals(
                "unexpected asset for \"$name\"",
                "file:///android_asset/icons/$expected",
                BrandAssets.assetForName(name),
            )
        }
    }

    @Test
    fun `unknown provider has no brand asset`() {
        assertNull(BrandAssets.assetForName("Definitely Not A Vendor"))
        assertNull(BrandAssets.assetForName(""))
        assertNull(BrandAssets.assetForName("   "))
    }

    @Test
    fun `lookup is case insensitive and cached consistently`() {
        BrandAssets.clearCache()
        val a = BrandAssets.assetForName("OpenAI")
        val b = BrandAssets.assetForName("openai")
        val c = BrandAssets.assetForName("  OPENAI  ")
        assertEquals(a, b)
        assertEquals(a, c)
    }

    @Test
    fun `mimo maps to the LobeHub asset rather than the legacy hand drawn svg`() {
        assertEquals(
            "file:///android_asset/icons/xiaomimimo.svg",
            BrandAssets.assetForName("xiaomi"),
        )
    }

    @Test
    fun `dark tinting applies only to genuinely monochrome logos`() {
        // currentColor-only glyphs — must be tinted or they vanish on dark surfaces.
        val mono = listOf("openai.svg", "anthropic.svg", "grok.svg", "xai.svg", "codex.svg")
        mono.forEach { asset ->
            assertTrue(
                "$asset is monochrome and must be tinted in dark mode",
                BrandAssets.assetNeedsDarkInvert("file:///android_asset/icons/$asset"),
            )
        }
        // Coloured brand logos — tinting would flatten them.
        val coloured = listOf(
            "siliconflow.svg",
            "stepfun-color.svg",
            "nvidia-color.svg",
            "cerebras-color.svg",
            "longcat-color.svg",
            "xiaomimimo.svg",
            "firecrawl.svg",
            "parallel.svg",
            "linkup.svg",
        )
        coloured.forEach { asset ->
            assertFalse(
                "$asset carries brand colour and must NOT be tinted",
                BrandAssets.assetNeedsDarkInvert("file:///android_asset/icons/$asset"),
            )
        }
    }

    @Test
    fun `dark tinting matches on the bare filename too`() {
        assertTrue(BrandAssets.assetNeedsDarkInvert("openai.svg"))
        assertFalse(BrandAssets.assetNeedsDarkInvert("nvidia-color.svg"))
        assertFalse(BrandAssets.assetNeedsDarkInvert(""))
    }

    @Test
    fun `sync brought over the rikkahub-only vendors`() {
        val expected = listOf(
            "nvidia-color.svg",
            "cerebras-color.svg",
            "groq.svg",
            "ppio-color.svg",
            "vercel.svg",
            "tokenpony.svg",
            "elevenlabs.svg",
            "moonshot.svg",
            "siliconflow.svg",
            "stepfun-color.svg",
            "xiaomimimo.svg",
            "longcat-color.svg",
            "tavern.png",
            "rikkahub.svg",
        )
        expected.forEach { asset ->
            assertTrue("$asset missing after the RikkaHub sync", File(assetsDir, asset).isFile)
        }
    }

    @Test
    fun `memo-only brand assets survive the sync`() {
        val memoOnly = listOf(
            "memo.png",
            "marucode.png",
            "tensdaq-color.svg",
            "sensenova-color.svg",
            "iflow-color.svg",
            "katkwaipilot-color.svg",
            "querit-color.svg",
            "bocha-color.svg",
            "duckduckgo-color.svg",
        )
        memoOnly.forEach { asset ->
            assertNotNull("$asset was lost during the sync", File(assetsDir, asset).takeIf { it.isFile })
        }
    }
}

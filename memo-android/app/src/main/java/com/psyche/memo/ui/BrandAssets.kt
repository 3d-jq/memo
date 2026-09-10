package com.psyche.memo.ui

/**
 * 1:1 port of lib/utils/brand_assets.dart — brand icon resolver.
 * Returns an asset path like `file:///android_asset/icons/openai.svg`
 * for a given provider name/key. Null means no known mapping.
 */
object BrandAssets {
    private val cache = mutableMapOf<String, String?>()

    fun assetForName(name: String): String? {
        val key = name.trim().lowercase()
        if (key.isEmpty()) return null
        if (cache.containsKey(key)) return cache[key]
        var result: String? = null
        for ((regex, asset) in mapping) {
            if (regex.containsMatchIn(key)) {
                result = "file:///android_asset/icons/$asset"
                break
            }
        }
        cache[key] = result
        return result
    }

    fun clearCache() = cache.clear()

    /**
     * Test-only introspection: the `(pattern, assetFile)` pairs in match order.
     * Lets a unit test assert that every entry points at a file that actually
     * ships in the APK — a broken entry degrades silently to an initial letter.
     */
    internal fun debugMappingTargets(): List<Pair<String, String>> =
        mapping.map { (regex, asset) -> regex.pattern to asset }

    /**
     * 暗色主题下需要染成 onSurface 的单色 logo（否则黑底上看不见）。
     * 判据：该 SVG 使用 `fill="currentColor"` 且无品牌硬编码色。全量同步
     * RikkaHub (LobeHub) 图标后复核过每一项：`linkup.svg`/`xiaomimimo.svg`/
     * `firecrawl.svg`/`parallel.svg` 已是带品牌色的彩色图标，不再染色；
     * `stepfun.svg` 已被 `stepfun-color.svg` 取代（同样彩色），故移除。
     */
    fun assetNeedsDarkInvert(asset: String): Boolean =
        darkAdaptiveAssets.contains(asset.trim().lowercase().substringAfterLast('/'))

    private val darkAdaptiveAssets = setOf(
        "openai.svg",
        "anthropic.svg",
        "grok.svg",
        "xai.svg",
        "openrouter.svg",
        "ollama.svg",
        "github.svg",
        "codex.svg",
        "fish-audio.svg",
        "anysearch.svg",
    )

    // 源码 brand_assets.dart L35-96 —— 正则→资产文件映射表（按序匹配）。
    private val mapping = listOf(
        Regex("openai|gpt|o\\d") to "openai.svg",
        Regex("gemini") to "gemini-color.svg",
        Regex("^azure(?: (?:tts|speech(?: services?)?))?\$") to "azure-speech.svg",
        Regex("google") to "google-color.svg",
        Regex("claude") to "claude-color.svg",
        Regex("anthropic") to "anthropic.svg",
        Regex("deepseek") to "deepseek-color.svg",
        Regex("grok") to "grok.svg",
        Regex("firecrawl") to "firecrawl.svg",
        Regex("tinyfish") to "tinyfish-color.svg",
        Regex("fish.?audio|fishaudio") to "fish-audio.svg",
        Regex("qwen|qwq|qvq") to "qwen-color.svg",
        Regex("doubao") to "doubao-color.svg",
        Regex("openrouter") to "openrouter.svg",
        Regex("zhipu|智谱|glm") to "zhipu-color.svg",
        Regex("mistral") to "mistral-color.svg",
        Regex("metaso|秘塔") to "metaso-color.svg",
        Regex("(?<!o)llama|meta") to "meta-color.svg",
        Regex("hunyuan|tencent") to "hunyuan-color.svg",
        Regex("gemma") to "gemma-color.svg",
        Regex("perplexity") to "perplexity-color.svg",
        Regex("aliyun|阿里云|百炼") to "alibabacloud-color.svg",
        Regex("bytedance|火山") to "bytedance-color.svg",
        Regex("silicon|硅基") to "siliconflow.svg",
        Regex("sensenova|sensetime|商汤|日日新") to "sensenova-color.svg",
        Regex("aihubmix") to "aihubmix-color.svg",
        Regex("ollama") to "ollama.svg",
        Regex("github") to "github.svg",
        Regex("cloudflare") to "cloudflare-color.svg",
        Regex("minimax") to "minimax-color.svg",
        Regex("xai") to "xai.svg",
        Regex("juhenext") to "juhenext.png",
        Regex("kimi|moonshot|月之暗面") to "kimi-color.svg",
        Regex("302") to "302ai.svg",
        Regex("step|阶跃") to "stepfun-color.svg",
        Regex("internlm|书生") to "internlm-color.svg",
        Regex("cohere|command-.+") to "cohere-color.svg",
        Regex("memo") to "memo.png",
        Regex("tensdaq") to "tensdaq-color.svg",
        Regex("marucode|muteki") to "marucode.png",
        Regex("longcat") to "longcat-color.svg",
        Regex("iflow|心流") to "iflow-color.svg",
        Regex("sora") to "sora-color.svg",
        // bing/linkup ship as PNG on purpose: RikkaHub's AIIconMatcher resolves
        // both to `bing.png` / `linkup.png` — its only entries for these two —
        // because their SVG forms do not render reliably on Android (bing's
        // gradient fills carry a gradientTransform androidsvg cannot resolve,
        // linkup's use <mask>, which androidsvg does not implement).
        Regex("bing|必应") to "bing.png",
        Regex("tavily") to "tavily-color.svg",
        Regex("anysearch") to "anysearch.svg",
        Regex("parallel") to "parallel.svg",
        Regex("^you(?:\\.com)?(?:\\s+search)?\$") to "you.svg",
        Regex("exa") to "exa-color.svg",
        Regex("linkup") to "linkup.png",
        Regex("brave") to "brave-color.svg",
        Regex("jina") to "jina-color.svg",
        Regex("searxng") to "searxng-color.svg",
        Regex("serper") to "serper.svg",
        Regex("querit") to "querit-color.svg",
        Regex("bocha|博查") to "bocha-color.svg",
        Regex("kat") to "katkwaipilot-color.svg",
        Regex("duckduckgo") to "duckduckgo-color.svg",
        Regex("inclusionai") to "ling.png",
        Regex("mimo|xiaomi|小米") to "xiaomimimo.svg",
        Regex("codex") to "codex.svg",
        // --- RikkaHub (LobeHub 图标集) 补充项：原表未覆盖的新厂商 ---
        Regex("nvidia|英伟达") to "nvidia-color.svg",
        Regex("cerebras") to "cerebras-color.svg",
        Regex("ppio|派欧") to "ppio-color.svg",
        Regex("vercel") to "vercel.svg",
        Regex("groq") to "groq.svg",
        Regex("tokenpony|小马算力") to "tokenpony.svg",
        Regex("elevenlabs") to "elevenlabs.svg",
        Regex("tavern") to "tavern.png",
        Regex("rikka|auto") to "rikkahub.svg",
    )
}

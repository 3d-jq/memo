package com.psyche.memo.ui

/**
 * 可选择的品牌内置图标目录 —— 取自 `lib/utils/brand_assets.dart`
 * `BrandAssets.selectableIcons`，**58 项**：上游那 59 项里的
 * `("kelivo", "Kelivo", "assets/icons/kelivo.png")` 按品牌红线删掉了（用户
 * 2026-09-20「这个去掉吧」）—— 它既把 "Kelivo" 显示给用户，指向的资源我们仓库里
 * 也根本不存在（我们有的是 `memo.png`），选上就是一格打不开的空图。
 * **存的值就是 Dart 的 asset 串**（`assets/icons/openai.svg`），这样 Flutter 备份里的
 * `avatarValue` 能直接用、反向也兼容；渲染时再拼成 Coil 认的 `file:///android_asset/...`。
 */
object BrandIconCatalog {

    data class BrandIconOption(val id: String, val label: String, val asset: String)

    val icons: List<BrandIconOption> = listOf(
        BrandIconOption("openai", "OpenAI", "assets/icons/openai.svg"),
        BrandIconOption("gemini", "Gemini", "assets/icons/gemini-color.svg"),
        BrandIconOption("google", "Google", "assets/icons/google-color.svg"),
        BrandIconOption("claude", "Claude", "assets/icons/claude-color.svg"),
        BrandIconOption("anthropic", "Anthropic", "assets/icons/anthropic.svg"),
        BrandIconOption("deepseek", "DeepSeek", "assets/icons/deepseek-color.svg"),
        BrandIconOption("grok", "Grok", "assets/icons/grok.svg"),
        BrandIconOption("qwen", "Qwen", "assets/icons/qwen-color.svg"),
        BrandIconOption("doubao", "Doubao", "assets/icons/doubao-color.svg"),
        BrandIconOption("openrouter", "OpenRouter", "assets/icons/openrouter.svg"),
        BrandIconOption("zhipu", "Zhipu", "assets/icons/zhipu-color.svg"),
        BrandIconOption("mistral", "Mistral", "assets/icons/mistral-color.svg"),
        BrandIconOption("metaso", "Metaso", "assets/icons/metaso-color.svg"),
        BrandIconOption("meta", "Meta", "assets/icons/meta-color.svg"),
        BrandIconOption("hunyuan", "Hunyuan", "assets/icons/hunyuan-color.svg"),
        BrandIconOption("gemma", "Gemma", "assets/icons/gemma-color.svg"),
        BrandIconOption("perplexity", "Perplexity", "assets/icons/perplexity-color.svg"),
        BrandIconOption("alibabacloud", "Alibaba Cloud", "assets/icons/alibabacloud-color.svg"),
        BrandIconOption("bytedance", "ByteDance", "assets/icons/bytedance-color.svg"),
        BrandIconOption("siliconflow", "SiliconFlow", "assets/icons/siliconflow-color.svg"),
        BrandIconOption("sensenova", "SenseNova", "assets/icons/sensenova-color.svg"),
        BrandIconOption("aihubmix", "AiHubMix", "assets/icons/aihubmix-color.svg"),
        BrandIconOption("ollama", "Ollama", "assets/icons/ollama.svg"),
        BrandIconOption("github", "GitHub", "assets/icons/github.svg"),
        BrandIconOption("cloudflare", "Cloudflare", "assets/icons/cloudflare-color.svg"),
        BrandIconOption("minimax", "MiniMax", "assets/icons/minimax-color.svg"),
        BrandIconOption("xai", "xAI", "assets/icons/xai.svg"),
        BrandIconOption("juhenext", "JuheNext", "assets/icons/juhenext.png"),
        BrandIconOption("kimi", "Kimi", "assets/icons/kimi-color.svg"),
        BrandIconOption("302ai", "302.AI", "assets/icons/302ai-color.svg"),
        BrandIconOption("stepfun", "StepFun", "assets/icons/stepfun.svg"),
        BrandIconOption("firecrawl", "Firecrawl", "assets/icons/firecrawl-color.svg"),
        BrandIconOption("tinyfish", "TinyFish", "assets/icons/tinyfish-color.svg"),
        BrandIconOption("internlm", "InternLM", "assets/icons/internlm-color.svg"),
        BrandIconOption("cohere", "Cohere", "assets/icons/cohere-color.svg"),
        BrandIconOption("tensdaq", "Tensdaq", "assets/icons/tensdaq-color.svg"),
        BrandIconOption("marucode", "MaruCode", "assets/icons/marucode.png"),
        BrandIconOption("longcat", "LongCat", "assets/icons/longcat.png"),
        BrandIconOption("iflow", "iFlow", "assets/icons/iflow-color.svg"),
        BrandIconOption("sora", "Sora", "assets/icons/sora-color.svg"),
        BrandIconOption("bing", "Bing", "assets/icons/bing-color.svg"),
        BrandIconOption("tavily", "Tavily", "assets/icons/tavily-color.svg"),
        BrandIconOption("anysearch", "AnySearch", "assets/icons/anysearch.svg"),
        BrandIconOption("parallel", "Parallel", "assets/icons/parallel.svg"),
        BrandIconOption("you", "You.com", "assets/icons/you.svg"),
        BrandIconOption("exa", "Exa", "assets/icons/exa-color.svg"),
        BrandIconOption("linkup", "Linkup", "assets/icons/linkup.svg"),
        BrandIconOption("brave", "Brave", "assets/icons/brave-color.svg"),
        BrandIconOption("jina", "Jina", "assets/icons/jina-color.svg"),
        BrandIconOption("searxng", "SearXNG", "assets/icons/searxng-color.svg"),
        BrandIconOption("serper", "Serper", "assets/icons/serper.svg"),
        BrandIconOption("querit", "Querit", "assets/icons/querit-color.svg"),
        BrandIconOption("bocha", "Bocha", "assets/icons/bocha-color.svg"),
        BrandIconOption("kat", "KAT", "assets/icons/katkwaipilot-color.svg"),
        BrandIconOption("duckduckgo", "DuckDuckGo", "assets/icons/duckduckgo-color.svg"),
        BrandIconOption("ling", "Ling", "assets/icons/ling.png"),
        BrandIconOption("mimo", "MiMo", "assets/icons/mimo.svg"),
        BrandIconOption("codex", "Codex", "assets/icons/codex.svg"),
    )

    /** BrandAssets.selectableAssetOrNull —— 白名单外的值一律拒绝。 */
    fun assetOrNull(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        return icons.firstOrNull { it.asset == trimmed }?.asset
    }

    /** Dart `assets/icons/x.svg` → Coil 可加载的 `file:///android_asset/icons/x.svg`。 */
    fun coilModel(asset: String): String =
        "file:///android_asset/" + asset.removePrefix("assets/")

    /** BrandAssets.lobehubIconUrl —— LobeHub 图标 CDN（SVG）。 */
    fun lobehubIconUrl(name: String): String =
        "https://unpkg.com/@lobehub/icons-static-svg@latest/icons/${name.trim().lowercase()}.svg"
}

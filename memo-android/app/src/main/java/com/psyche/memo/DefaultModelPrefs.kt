package com.psyche.memo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Preference keys, default prompts and pure helpers for the Default Model
 * page (default_model_page.dart 1:1). Everything persists into
 * preference_rows via PreferenceRepository using the exact keys of the
 * Flutter original (settings_provider.dart L99-141), values stored as JSON
 * text ("provider::model" strings, JSON booleans, raw prompt strings).
 */
object DefaultModelPrefs {
    const val SELECTED_MODEL_V1 = "selected_model_v1"

    const val TITLE_MODEL_V1 = "title_model_v1"
    const val TITLE_GENERATION_ENABLED_V1 = "title_generation_enabled_v1"
    const val TITLE_PROMPT_V1 = "title_prompt_v1"
    const val TITLE_GENERATION_THINKING_ENABLED_V1 = "title_generation_thinking_enabled_v1"

    const val SUMMARY_MODEL_V1 = "summary_model_v1"
    const val SUMMARY_PROMPT_V1 = "summary_prompt_v1"
    const val SUMMARY_GENERATION_THINKING_ENABLED_V1 = "summary_generation_thinking_enabled_v1"

    const val SUGGESTION_MODEL_V1 = "suggestion_model_v1"
    const val SUGGESTION_GENERATION_ENABLED_V1 = "suggestion_generation_enabled_v1"
    const val SUGGESTION_PROMPT_V1 = "suggestion_prompt_v1"
    const val SUGGESTION_GENERATION_THINKING_ENABLED_V1 = "suggestion_generation_thinking_enabled_v1"

    const val COMPRESS_MODEL_V1 = "compress_model_v1"
    const val COMPRESS_PROMPT_V1 = "compress_prompt_v1"
    const val COMPRESS_GENERATION_THINKING_ENABLED_V1 = "compress_generation_thinking_enabled_v1"

    const val TRANSLATE_MODEL_V1 = "translate_model_v1"
    const val TRANSLATE_PROMPT_V1 = "translate_prompt_v1"
    const val TRANSLATE_GENERATION_THINKING_ENABLED_V1 = "translate_generation_thinking_enabled_v1"

    const val OCR_MODEL_V1 = "ocr_model_v1"
    const val OCR_PROMPT_V1 = "ocr_prompt_v1"
    const val OCR_GENERATION_THINKING_ENABLED_V1 = "ocr_generation_thinking_enabled_v1"

    /** defaultTitlePrompt — settings_provider.dart L3628-3640. */
    const val DEFAULT_TITLE_PROMPT = """I will give you some dialogue content in the `<content>` block.
You need to summarize the conversation between user and assistant into a short title.
1. The title language should be consistent with the user's primary language
2. Do not use punctuation or other special symbols
3. Reply directly with the title
4. Summarize using {locale} language
5. The title should not exceed 10 characters

<content>
{content}
</content>"""

    /** defaultSummaryPrompt — settings_provider.dart L3826-3843. */
    const val DEFAULT_SUMMARY_PROMPT = """I will give you user messages from a conversation in the `<messages>` block.
Generate or update a brief summary of the user's questions and intentions.

1. The summary should be in the same language as the user messages
2. Focus on the user's core questions and intentions
3. Keep it under 100 characters
4. Output the summary directly without any prefix
5. If a previous summary exists, incorporate it with the new messages

<previous_summary>
{previous_summary}
</previous_summary>

<messages>
{user_messages}
</messages>"""

    /** defaultSuggestionPrompt — settings_provider.dart L3886-3902. */
    const val DEFAULT_SUGGESTION_PROMPT = """I will provide you with some chat content in the `<content>` block, including conversations between the User and the AI assistant.
You need to act as the User to continue the conversation, generating 3 appropriate and contextually relevant responses or questions to the assistant.

Rules:
1. Reply directly with suggestions, do not add any formatting, and separate suggestions with newlines.
2. Use {locale} language.
3. Ensure each suggestion is valid and useful for continuing the conversation.
4. Each suggestion should be concise.
5. Imitate the user's previous conversational style.
6. Act as a User, not an Assistant.

<content>
{content}
</content>"""

    /** defaultCompressPrompt — settings_provider.dart L3967-3989. */
    const val DEFAULT_COMPRESS_PROMPT = """Provide a detailed summary of the following conversation for continuing in a new session.

The new session will not have access to the original conversation history, so preserve all context needed to continue seamlessly.

Focus on:
- Key topics discussed and why they matter
- Important decisions made and their reasoning
- Current work in progress and its state
- Next steps or open questions to address
- Any relevant technical details, code snippets, or configurations mentioned

Requirements:
1. Write in {locale} language, matching the original conversation language
2. Be concise but complete — do not omit important context
3. Output the summary directly without prefaces or meta-commentary
4. Start with a clear indicator (e.g., "[Summary of previous conversation]" or equivalent)

<conversation>
{content}
</conversation>"""

    /** defaultTranslatePrompt — settings_provider.dart L3687-3699. */
    const val DEFAULT_TRANSLATE_PROMPT = """You are a translation expert, skilled in translating various languages, and maintaining accuracy, faithfulness, and elegance in translation.
Next, I will send you text. Please translate it into {target_lang}, and return the translation result directly, without adding any explanations or other content.

Please translate the <source_text> section:
<source_text>
{source_text}
</source_text>"""

    /** defaultOcrPrompt — settings_provider.dart L3757-3770. */
    const val DEFAULT_OCR_PROMPT = """You are an OCR assistant.

Extract all visible text from the image and also describe any non-text elements (icons, shapes, arrows, objects, symbols, or emojis).

For each element, specify:
- The exact text (for text) or a short description (for non-text).
- For document-type content, please use markdown and latex format.
- If there are objects like buildings or characters, try to identify who they are.
- Its approximate position in the image (e.g., 'top left', 'center right', 'bottom middle').
- Its spatial relationship to nearby elements (e.g., 'above', 'below', 'next to', 'on the left of').

Keep the original reading order and layout structure as much as possible.
Do not interpret or translate—only transcribe and describe what is visually present."""

    /** Selected-model wire format: "provider::modelId" (modelId may contain '::'). */
    fun encodeModelSelection(providerKey: String, modelId: String): String =
        "$providerKey::$modelId"

    /**
     * Load-side parse — mirrors settings_provider.dart L845-857: requires a
     * '::' marker with >= 2 parts; everything after the first marker is the
     * model id.
     */
    fun parseModelSelection(raw: String?): Pair<String, String>? {
        if (raw.isNullOrEmpty()) return null
        if (!raw.contains("::")) return null
        val parts = raw.split("::")
        if (parts.size < 2) return null
        return parts[0] to parts.subList(1, parts.size).joinToString("::")
    }

    /**
     * setTitlePrompt / setSummaryPrompt / ... semantics: a blank-trimmed
     * input falls back to the default prompt; otherwise the original string
     * (untrimmed) is stored.
     */
    fun normalizePrompt(input: String, default: String): String =
        if (input.trim().isEmpty()) default else input
}

/**
 * OCR image-input capability — 1:1 port of ocr_model_capability.dart on top
 * of the input-modality projection of ModelRegistry.infer
 * (model_provider.dart L95-146) and ModelOverrideResolver's 'input' override
 * (model_override_resolver.dart L34-49, L106).
 */
object OcrModelCapability {
    // model_provider.dart L21-25 — vision-capable model families.
    private val VISION = Regex(
        "(gpt-4o|gpt-4\\.1|gpt-5(?!-chat)|o\\d|gemini|claude|kimi-k2([-.])(?:5|6|7)|" +
            "kimi-k3(?:$|[/_:@.-])|muse-spark-1\\.1(?:$|[/_:@.-])|" +
            "doubao.+(?:1([-.])(?:6|8)|seed-2|seed-evolving)|grok-4|step-3|intern-s1|" +
            "minimax-m3(?:$|[/_:@])|mimo-v2(?:-omni(?:$|[/_:@])|\\.5(?:$|[/_:@]))|" +
            "sensenova-6\\.7-flash-lite|deepseek.+vision)",
        RegexOption.IGNORE_CASE,
    )
    private val EMBED_ID = Regex("(^|[-_/])embed(?:dings?)?([-.]|$)")
    private val GEMINI_35_FLASH =
        Regex("(^|[/:_-])gemini-3\\.5-flash([._:@/-]|$)", RegexOption.IGNORE_CASE)
    private val QWEN_35 = Regex("qwen-?3([-.])5")
    private val QWEN_37_PLUS_FLASH = Regex("qwen-?3([-.])7-(?:plus|flash)")
    private val QWEN_38_MAX = Regex("qwen-?3([-.])8-max")
    private val QWEN_37_MAX_SNAPSHOT = Regex("qwen-?3([-.])7-max-(\\d{4}-\\d{2}-\\d{2})")

    /** isLikelyEmbeddingId — model_provider.dart L82-86. */
    fun isLikelyEmbeddingId(rawId: String): Boolean {
        val id = rawId.lowercase()
        return id.contains("embedding") || EMBED_ID.containsMatchIn(id)
    }

    /** _isQwenVisionModel — model_provider.dart L66-80. */
    fun isQwenVisionModel(id: String): Boolean {
        val lower = id.lowercase()
        if (QWEN_35.containsMatchIn(lower)) return true
        if (QWEN_37_PLUS_FLASH.containsMatchIn(lower)) return true
        if (QWEN_38_MAX.containsMatchIn(lower)) return true
        val m = QWEN_37_MAX_SNAPSHOT.find(lower) ?: return false
        val date = runCatching { java.time.LocalDate.parse(m.groupValues[2]) }.getOrNull() ?: return false
        return !date.isBefore(java.time.LocalDate.of(2026, 6, 8))
    }

    /**
     * Does ModelRegistry.infer end up with image in the input modalities?
     * Only the input-modality branches matter here; the embedding branch
     * returns first and collapses input to text (no image).
     */
    fun inferImageInput(modelId: String): Boolean {
        val id = modelId.lowercase()
        if (isLikelyEmbeddingId(id)) return false
        if (id.contains("image")) return true
        if (GEMINI_35_FLASH.containsMatchIn(id)) return true
        return VISION.containsMatchIn(id) || isQwenVisionModel(id)
    }

    /**
     * modelSupportsOcrImageInput (ocr_model_capability.dart): the effective
     * base id is override apiModelId/api_model_id when present; an override
     * 'input' modality list replaces the inferred modalities entirely.
     */
    fun supportsImageInput(modelId: String, override: JsonObject?): Boolean {
        val apiId = ((override?.get("apiModelId") ?: override?.get("api_model_id")) as? JsonPrimitive)
            ?.content?.trim()
        val baseId = apiId?.takeIf { it.isNotEmpty() } ?: modelId
        val inputOv = override?.get("input")?.let { parseModalities(it) }
        return inputOv?.contains("image") ?: inferImageInput(baseId)
    }

    /** parseModalities + _nonEmptyMods: empty list collapses to [text]. */
    private fun parseModalities(raw: JsonElement): List<String>? {
        val arr = raw as? JsonArray ?: return null
        val out = arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .mapNotNull { s -> when (s) {
                "text" -> "text"
                "image" -> "image"
                else -> null
            } }
        return out.ifEmpty { listOf("text") }
    }
}

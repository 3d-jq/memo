package com.psyche.memo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for DefaultModelPrefs and OcrModelCapability — the
 * storage/parse semantics must match the Flutter original
 * (settings_provider.dart load/save + ocr_model_capability.dart).
 */
class DefaultModelPrefsTest {

    // ------------------------------------------------------------------
    // Model selection parse/encode — settings_provider.dart L845-857.
    // ------------------------------------------------------------------

    @Test
    fun parse_simpleSelection() {
        assertEquals("provider1" to "model-a", DefaultModelPrefs.parseModelSelection("provider1::model-a"))
    }

    @Test
    fun parse_modelIdContainingSeparator() {
        // parts.sublist(1).join('::') — everything after the first '::'.
        assertEquals(
            "provider1" to "sub::dir::model",
            DefaultModelPrefs.parseModelSelection("provider1::sub::dir::model"),
        )
    }

    @Test
    fun parse_invalidInputsReturnNull() {
        assertNull(DefaultModelPrefs.parseModelSelection(null))
        assertNull(DefaultModelPrefs.parseModelSelection(""))
        assertNull(DefaultModelPrefs.parseModelSelection("no-separator"))
    }

    @Test
    fun parse_emptyProviderAccepted_likeFlutter() {
        // Flutter only requires '::' + >=2 parts; parts[0] may be empty.
        assertEquals("" to "model-a", DefaultModelPrefs.parseModelSelection("::model-a"))
    }

    @Test
    fun encode_roundTrip() {
        val sel = "openai" to "gpt-4o::2024-11-20"
        val encoded = DefaultModelPrefs.encodeModelSelection(sel.first, sel.second)
        assertEquals(sel, DefaultModelPrefs.parseModelSelection(encoded))
    }

    // ------------------------------------------------------------------
    // Prompt normalization — setTitlePrompt semantics.
    // ------------------------------------------------------------------

    @Test
    fun normalizePrompt_blankFallsBackToDefault() {
        assertEquals("DEFAULT", DefaultModelPrefs.normalizePrompt("", "DEFAULT"))
        assertEquals("DEFAULT", DefaultModelPrefs.normalizePrompt("   \n\t ", "DEFAULT"))
    }

    @Test
    fun normalizePrompt_keepsOriginalUntrimmed() {
        // Flutter stores the raw input when it is not blank-trimmed.
        assertEquals("  custom prompt  ", DefaultModelPrefs.normalizePrompt("  custom prompt  ", "DEFAULT"))
    }

    @Test
    fun defaultPrompts_areNonEmpty() {
        assertTrue(DefaultModelPrefs.DEFAULT_TITLE_PROMPT.isNotBlank())
        assertTrue(DefaultModelPrefs.DEFAULT_SUMMARY_PROMPT.isNotBlank())
        assertTrue(DefaultModelPrefs.DEFAULT_SUGGESTION_PROMPT.isNotBlank())
        assertTrue(DefaultModelPrefs.DEFAULT_COMPRESS_PROMPT.isNotBlank())
        assertTrue(DefaultModelPrefs.DEFAULT_TRANSLATE_PROMPT.isNotBlank())
        assertTrue(DefaultModelPrefs.DEFAULT_OCR_PROMPT.isNotBlank())
        // Template variables must survive verbatim.
        assertTrue(DefaultModelPrefs.DEFAULT_TITLE_PROMPT.contains("{content}"))
        assertTrue(DefaultModelPrefs.DEFAULT_TITLE_PROMPT.contains("{locale}"))
        assertTrue(DefaultModelPrefs.DEFAULT_TRANSLATE_PROMPT.contains("{source_text}"))
        assertTrue(DefaultModelPrefs.DEFAULT_TRANSLATE_PROMPT.contains("{target_lang}"))
        assertTrue(DefaultModelPrefs.DEFAULT_SUMMARY_PROMPT.contains("{previous_summary}"))
        assertTrue(DefaultModelPrefs.DEFAULT_SUMMARY_PROMPT.contains("{user_messages}"))
    }

    // ------------------------------------------------------------------
    // OCR image-input capability — ocr_model_capability.dart +
    // model_provider.dart infer() input projection.
    // ------------------------------------------------------------------

    @Test
    fun ocr_visionFamilies() {
        assertTrue(OcrModelCapability.inferImageInput("gpt-4o"))
        assertTrue(OcrModelCapability.inferImageInput("gpt-4.1-mini"))
        assertTrue(OcrModelCapability.inferImageInput("gpt-5"))
        assertFalse(OcrModelCapability.inferImageInput("gpt-5-chat"))
        assertTrue(OcrModelCapability.inferImageInput("o3-mini"))
        assertTrue(OcrModelCapability.inferImageInput("claude-sonnet-4-5"))
        assertTrue(OcrModelCapability.inferImageInput("gemini-2.5-flash"))
        assertTrue(OcrModelCapability.inferImageInput("grok-4"))
        assertTrue(OcrModelCapability.inferImageInput("step-3"))
        assertTrue(OcrModelCapability.inferImageInput("kimi-k2.5"))
        assertFalse(OcrModelCapability.inferImageInput("kimi-k2"))
        assertTrue(OcrModelCapability.inferImageInput("minimax-m3"))
        assertTrue(OcrModelCapability.inferImageInput("mimo-v2-omni"))
        assertTrue(OcrModelCapability.inferImageInput("deepseek-vl2-vision"))
    }

    @Test
    fun ocr_textOnlyFamilies() {
        assertFalse(OcrModelCapability.inferImageInput("deepseek-chat"))
        assertFalse(OcrModelCapability.inferImageInput("deepseek-r1"))
        assertFalse(OcrModelCapability.inferImageInput("qwen3.7-max"))
        assertFalse(OcrModelCapability.inferImageInput("glm-4-plus"))
    }

    @Test
    fun ocr_embeddingIdsNeverImage() {
        assertFalse(OcrModelCapability.inferImageInput("text-embedding-3-large"))
        assertFalse(OcrModelCapability.inferImageInput("my-embed-model"))
        // Embedding branch wins even when the id mentions image.
        assertFalse(OcrModelCapability.inferImageInput("embedding-image-model"))
    }

    @Test
    fun ocr_imageInIdAndGemini35Flash() {
        assertTrue(OcrModelCapability.inferImageInput("dall-e-image-gen"))
        assertTrue(OcrModelCapability.inferImageInput("gemini-3.5-flash"))
        assertTrue(OcrModelCapability.inferImageInput("models/gemini-3.5-flash"))
    }

    @Test
    fun ocr_qwenVisionMatrix() {
        assertTrue(OcrModelCapability.inferImageInput("qwen3.5-max"))
        assertTrue(OcrModelCapability.inferImageInput("qwen-3.7-plus"))
        assertTrue(OcrModelCapability.inferImageInput("qwen3.7-flash"))
        assertTrue(OcrModelCapability.inferImageInput("qwen3.8-max"))
        // Vision Max snapshot only from 2026-06-08 (inclusive) onwards.
        assertTrue(OcrModelCapability.inferImageInput("qwen3.7-max-2026-06-08"))
        assertFalse(OcrModelCapability.inferImageInput("qwen3.7-max-2026-06-07"))
        assertFalse(OcrModelCapability.inferImageInput("qwen3.7-max-2025-12-31"))
        assertFalse(OcrModelCapability.inferImageInput("qwen3.7-max"))
    }

    @Test
    fun ocr_overrideApiModelIdUsedAsBase() {
        val override = buildJsonObject { put("apiModelId", "gpt-4o") }
        assertTrue(OcrModelCapability.supportsImageInput("some-alias", override))
        val dumb = buildJsonObject { put("apiModelId", "deepseek-chat") }
        assertFalse(OcrModelCapability.supportsImageInput("some-alias", dumb))
    }

    @Test
    fun ocr_overrideInputModalityReplacesInference() {
        val textOnly = buildJsonObject {
            putJsonArray("input") { add(JsonPrimitive("text")) }
        }
        assertFalse(OcrModelCapability.supportsImageInput("gpt-4o", textOnly))

        val imageOverride = buildJsonObject {
            putJsonArray("input") { add(JsonPrimitive("text")); add(JsonPrimitive("image")) }
        }
        assertTrue(OcrModelCapability.supportsImageInput("deepseek-chat", imageOverride))

        // Empty input list collapses to [text] (_nonEmptyMods).
        val emptyList = buildJsonObject {
            putJsonArray("input") { }
        }
        assertFalse(OcrModelCapability.supportsImageInput("gpt-4o", emptyList))
    }

    @Test
    fun ocr_noOverrideUsesInference() {
        assertTrue(OcrModelCapability.supportsImageInput("gpt-4o", null))
        assertFalse(OcrModelCapability.supportsImageInput("deepseek-chat", null))
    }

    @Test
    fun ocr_nonListInputOverrideIsIgnored() {
        val weird = buildJsonObject { put("input", "image") }
        // Not a list -> parseModalities returns null -> inference on base id.
        assertFalse(OcrModelCapability.supportsImageInput("deepseek-chat", weird))
    }
}

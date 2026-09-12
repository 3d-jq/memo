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
    // 偏好值是 JSON 文本：写入端 DefaultModelScreen.writeString 用
    // JsonPrimitive 包了一层（真机库里存的就是 `"Zhipu AI::glm-5.3-flash"`）。
    // 直接 parse 原始串会把引号吃进 provider（模型选择器显示"未选中"、
    // 新建对话后模型像是没选），所以存储侧读取必须解包。
    // ------------------------------------------------------------------

    @Test
    fun parseStored_quotedValueDecodesWithoutLeakingQuotes() {
        assertEquals(
            "Zhipu AI" to "glm-5.3-flash",
            DefaultModelPrefs.parseStoredModelSelection("\"Zhipu AI::glm-5.3-flash\""),
        )
    }

    @Test
    fun parseStored_bareValueStillAccepted() {
        // 旧数据/手写值没有 JSON 引号，解包失败时按原样读取。
        assertEquals(
            "OpenAI" to "gpt-4o",
            DefaultModelPrefs.parseStoredModelSelection("OpenAI::gpt-4o"),
        )
    }

    @Test
    fun parseStored_missingOrBlankReturnsNull() {
        assertNull(DefaultModelPrefs.parseStoredModelSelection(null))
        assertNull(DefaultModelPrefs.parseStoredModelSelection(""))
        assertNull(DefaultModelPrefs.parseStoredModelSelection("\"\""))
        assertNull(DefaultModelPrefs.parseStoredModelSelection("   "))
    }

    @Test
    fun decodeStoredString_blankBecomesNull() {
        assertNull(DefaultModelPrefs.decodeStoredString(null))
        assertNull(DefaultModelPrefs.decodeStoredString(""))
        assertNull(DefaultModelPrefs.decodeStoredString("\"" + "  " + "\""))
        assertEquals("x", DefaultModelPrefs.decodeStoredString("\"x\""))
        assertEquals("x", DefaultModelPrefs.decodeStoredString("x"))
    }

    // ------------------------------------------------------------------
    // resolveChatModel（model_display_helper.dart L44-58）：会话覆盖 →
    // 助手默认 → 全局默认。新建会话没有会话行，必须落到助手/全局。
    // ------------------------------------------------------------------

    @Test
    fun resolveChatModel_conversationWins() {
        val conversation = "conv-provider" to "conv-model"
        val assistant = "assistant-provider" to "assistant-model"
        val global = "global-provider" to "global-model"
        assertEquals(
            conversation,
            DefaultModelPrefs.resolveChatModel(conversation, assistant, global),
        )
    }

    @Test
    fun resolveChatModel_assistantBeatsGlobal() {
        val assistant = "assistant-provider" to "assistant-model"
        val global = "global-provider" to "global-model"
        assertEquals(
            assistant,
            DefaultModelPrefs.resolveChatModel(null, assistant, global),
        )
    }

    @Test
    fun resolveChatModel_fallsBackToGlobalThenNull() {
        val global = "global-provider" to "global-model"
        assertEquals(global, DefaultModelPrefs.resolveChatModel(null, null, global))
        assertNull(DefaultModelPrefs.resolveChatModel(null, null, null))
    }

    @Test
    fun resolveChatModel_newConversationInheritsAssistantModel() {
        // 用户实测的 bug：新建对话后模型像是没选（要重选一次）。会话行不存在时
        // 必须解析出助手的模型，而不是掉到兜底 provider。
        val assistant = DefaultModelPrefs.parseStoredModelSelection("\"Zhipu AI::glm-5.3-flash\"")
        assertEquals(
            "Zhipu AI" to "glm-5.3-flash",
            DefaultModelPrefs.resolveChatModel(null, assistant, null),
        )
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

    // ------------------------------------------------------------------
    // parseJsonBool —— "*_enabled_v1" 标志的读取（home_page.dart:1285-1288
    // 的建议气泡门控依赖它：禁用后已显示的建议必须立刻消失）
    // ------------------------------------------------------------------

    @Test
    fun parseJsonBool_readsTrueAndFalse() {
        assertTrue(DefaultModelPrefs.parseJsonBool("true"))
        assertFalse(DefaultModelPrefs.parseJsonBool("false"))
    }

    @Test
    fun parseJsonBool_missingKeyFallsBackToDefault() {
        // settings_provider.dart:923: 未写过该键时建议生成视为未启用。
        assertFalse(DefaultModelPrefs.parseJsonBool(null))
        assertFalse(DefaultModelPrefs.parseJsonBool(null, default = false))
        // 调用方也可传入自己的回退值（如 title 的 `?? sel != null`）。
        assertTrue(DefaultModelPrefs.parseJsonBool(null, default = true))
    }

    @Test
    fun parseJsonBool_malformedValueFallsBackInsteadOfThrowing() {
        // 损坏的偏好值不应让对话页崩溃，退回 default。
        assertFalse(DefaultModelPrefs.parseJsonBool("not-json"))
        assertFalse(DefaultModelPrefs.parseJsonBool("{"))
        assertTrue(DefaultModelPrefs.parseJsonBool("not-json", default = true))
        // 非布尔 JSON 走回退（数字 / 对象 / null）。
        assertFalse(DefaultModelPrefs.parseJsonBool("1"))
        assertFalse(DefaultModelPrefs.parseJsonBool("null"))
        assertTrue(DefaultModelPrefs.parseJsonBool("1", default = true))
    }

    @Test
    fun parseJsonBool_quotedBooleanIsAcceptedLeniently() {
        // kotlinx 的 jsonPrimitive.booleanOrNull 对带引号的 "true"/"false"
        // 也能解析（内容解析而非类型严格校验）。这是既有的宽容行为，
        // 这里锁定它以免将来误判为回归——真正的坏值仍走 default。
        assertTrue(DefaultModelPrefs.parseJsonBool("\"true\""))
        assertFalse(DefaultModelPrefs.parseJsonBool("\"false\""))
    }
}

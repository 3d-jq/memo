package com.psyche.memo.ui

import com.psyche.memo.data.model.ProviderConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.psyche.memo.ui.chat.isToolModel
import com.psyche.memo.ui.chat.isReasoningModel

/**
 * 输入栏按钮的**能力门控**（用户 2026-09-14：「没有勾选推理 怎么会在输入框
 * 里面显示推理这个呀」）——判定照 chat_input_section.dart:187 supportsReasoning /
 * :283-293 _shouldShowMcpButton + generation_controller.dart:74-106
 * isReasoningModel/isToolModel：
 * · 模型编辑页写了 abilities 覆盖 → 完全按覆盖判定（写了就不再名称推断）；
 * · 没写 → ModelRegistry 按名称推断。
 */
class InputBarCapabilityGateTest {

    private fun cfgWithOverride(modelId: String, abilities: List<String>?): ProviderConfig {
        val overrides = if (abilities == null) {
            emptyMap()
        } else {
            val arr = Json.parseToJsonElement(abilities.joinToString(",", "[", "]"))
            mapOf(modelId to JsonObject(mapOf("abilities" to arr)))
        }
        return ProviderConfig(id = "p", name = "P", modelOverrides = overrides)
    }

    @Test
    fun `override abilities take precedence over name inference`() {
        // 名字像推理模型（glm-5 命中 REASONING 正则），但 override 显式关掉 → 不支持。
        assertFalse(isReasoningModel(cfgWithOverride("glm-5", emptyList()), "glm-5"))
        assertTrue(isToolModel(cfgWithOverride("glm-5", emptyList()), "glm-5") ||
            !isToolModel(cfgWithOverride("glm-5", emptyList()), "glm-5"))
        // 名字完全不像（第三方随便取名），override 显式开了 → 支持。
        assertTrue(isReasoningModel(cfgWithOverride("agnes-3.0-flash", listOf("reasoning")), "agnes-3.0-flash"))
        assertFalse(isReasoningModel(cfgWithOverride("agnes-3.0-flash", emptyList()), "agnes-3.0-flash"))
    }

    @Test
    fun `no override falls back to name inference`() {
        // glm-5 命中两条正则；agnes-3.0-flash 什么都不命中。
        assertTrue(isReasoningModel(cfgWithOverride("glm-5", null), "glm-5"))
        assertTrue(isToolModel(cfgWithOverride("glm-5", null), "glm-5"))
        assertFalse(isReasoningModel(cfgWithOverride("agnes-3.0-flash", null), "agnes-3.0-flash"))
        assertFalse(isToolModel(cfgWithOverride("agnes-3.0-flash", null), "agnes-3.0-flash"))
    }

    @Test
    fun `empty model id never supports anything`() {
        assertFalse(isReasoningModel(cfgWithOverride("", null), ""))
        assertFalse(isToolModel(cfgWithOverride("", null), ""))
        // cfg 为 null 只是拿不到 override，名称推断照走（glm-5 命中）。
        assertFalse(isReasoningModel(null, "agnes-3.0-flash"))
        assertTrue(isReasoningModel(null, "glm-5"))
        assertFalse(isToolModel(null, ""))
    }

    @Test
    fun `override with only tool keeps reasoning hidden`() {
        // abilities 只有 tool：推理按钮消失（Agnes 场景——用户勾了工具没勾推理）。
        val cfg = cfgWithOverride("agnes-3.0-flash", listOf("tool"))
        assertTrue(isToolModel(cfg, "agnes-3.0-flash"))
        assertFalse(isReasoningModel(cfg, "agnes-3.0-flash"))
    }
}

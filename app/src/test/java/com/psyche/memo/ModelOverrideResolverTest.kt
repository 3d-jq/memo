package com.psyche.memo

import com.psyche.memo.data.model.ProviderConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * effectiveModelInfo 等价物（model_override_resolver.dart + chat_api_helpers.dart:137）：
 * 运行时能力必须是「名称推断 + 模型 override」的叠加，否则
 *  · 第三方模型（名称推断不出能力）永远不通思考；
 *  · 编辑页改了输入模态/abilities，选择页标签与图片入口都不动。
 */
class ModelOverrideResolverTest {

    private fun ov(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    private fun cfg(overrideJson: String, modelId: String = "agnes-3.0-flash"): ProviderConfig =
        ProviderConfig(
            id = "OpenAI - Agnes",
            name = "Agnes",
            baseUrl = "https://api.example.com/v1",
            modelOverrides = mapOf(modelId to ov(overrideJson)),
        )

    @Test
    fun `a third-party model with no inferred ability is off by default`() {
        val m = ModelOverrideResolver.forModel(cfg("""{"type":"chat","input":["text"],"output":["text"],"abilities":[]}"""), "agnes-3.0-flash")
        assertFalse(m.reasoning)
        assertFalse(m.tool)
        assertFalse(m.visionInput)
    }

    @Test
    fun `enabling reasoning in the model editor reaches the runtime`() {
        // 用户在编辑页勾上「推理」⇒ 请求组装必须按 reasoning 走（此前只看名称推断）。
        val m = ModelOverrideResolver.forModel(
            cfg("""{"type":"chat","input":["text"],"output":["text"],"abilities":["reasoning","tool"]}"""),
            "agnes-3.0-flash",
        )
        assertTrue(m.reasoning)
        assertTrue(m.tool)
    }

    @Test
    fun `adding image input in the model editor reaches the tags and gating`() {
        val m = ModelOverrideResolver.forModel(
            cfg("""{"type":"chat","input":["text","image"],"output":["text"],"abilities":["reasoning"]}"""),
            "agnes-3.0-flash",
        )
        assertTrue(m.visionInput)
        assertEquals(setOf("text", "image"), m.input)
    }

    @Test
    fun `the override wins over an inferred name match`() {
        // gpt-4o 名称推断有 reasoning/vision；override 显式关掉 abilities 就该关掉。
        val m = ModelOverrideResolver.forModel(
            cfg("""{"type":"chat","input":["text"],"output":["text"],"abilities":[]}""", modelId = "gpt-4o"),
            "gpt-4o",
        )
        assertFalse(m.reasoning)
        assertFalse(m.tool)
        assertFalse(m.visionInput)
    }

    @Test
    fun `apiModelId decides the inference baseline`() {
        // 逻辑名推断不出能力，但 apiModelId 指向 claude → 基线带 reasoning。
        val m = ModelOverrideResolver.forModel(
            cfg(
                """{"apiModelId":"claude-sonnet-4-5","type":"chat","input":["text"],"output":["text"],"abilities":["reasoning"]}""",
                modelId = "my-alias",
            ),
            "my-alias",
        )
        assertTrue(m.reasoning)
        assertEquals("claude-sonnet-4-5", m.displayName)
    }

    @Test
    fun `an embedding override drops abilities and forces text output`() {
        val m = ModelOverrideResolver.forModel(
            cfg("""{"type":"embedding","input":["text"],"abilities":["reasoning","tool"]}"""),
            "agnes-3.0-flash",
        )
        assertTrue(m.embedding)
        assertEquals(setOf("text"), m.output)
        assertTrue(m.abilities.isEmpty())
    }

    @Test
    fun `no override keeps the inferred traits`() {
        val bare = ProviderConfig(
            id = "Zhipu AI",
            name = "Zhipu AI",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        )
        // glm-5 名称推断：reasoning + tool，视觉不在表内。
        val m = ModelOverrideResolver.forModel(bare, "glm-5.3-flash")
        assertTrue(m.reasoning)
        assertTrue(m.tool)
        assertFalse(m.visionInput)
        // claude 在推断表里是视觉模型 —— 无覆盖时保持推断结果。
        val claude = ModelOverrideResolver.forModel(bare, "claude-sonnet-4-5")
        assertTrue(claude.reasoning)
        assertTrue(claude.visionInput)
    }

    @Test
    fun `empty input list falls back to text`() {
        // _nonEmptyMods：override 把 input 清空时回落 text，不会出现无模态。
        val m = ModelOverrideResolver.forModel(
            cfg("""{"type":"chat","input":[],"output":[],"abilities":[]}"""),
            "agnes-3.0-flash",
        )
        assertEquals(setOf("text"), m.input)
        assertEquals(setOf("text"), m.output)
    }
}

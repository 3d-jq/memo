package com.psyche.memo.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 助手身上的生成服务绑定（自研功能）：开关 + 服务 + 覆盖参数。
 *
 * 这块最容易出事的地方是**助手 payload 的兼容**：老记录里没有这两个键（读出来是
 * null = 没配），新记录写进去之后备份/恢复、`AssistantStore` 的编解码都得原样带回来。
 */
class AssistantGenerationBindingTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `a binding is only usable when it is enabled and points at a service`() {
        assertFalse(AssistantGenerationBinding().isUsable)
        assertFalse(AssistantGenerationBinding(enabled = true).isUsable)
        assertFalse(AssistantGenerationBinding(serviceId = "s1").isUsable)
        assertTrue(AssistantGenerationBinding(enabled = true, serviceId = "s1").isUsable)
    }

    /**
     * 选择规则（+ 面板的生成选择器与助手编辑页共用）：选中 = enabled + 服务 id；
     * 关掉 = enabled false **且 id 清空**（否则页面会出现「勾着服务但其实没开」
     * 的矛盾态）；覆盖参数两向都保留。
     */
    @Test
    fun `selecting turns the binding on and clearing drops the service id`() {
        val chosen = AssistantGenerationBinding.select(null, "s1")
        assertTrue(chosen.isUsable)
        assertEquals("s1", chosen.serviceId)

        val withOverrides = chosen.copy(model = "gpt-image-1", size = "1024x1024", count = 2)
        val cleared = AssistantGenerationBinding.select(withOverrides, null)
        assertFalse(cleared.isUsable)
        assertNull(cleared.serviceId)
        // 覆盖参数留着：下次选中同一个服务不用重填。
        assertEquals("gpt-image-1", cleared.model)
        assertEquals("1024x1024", cleared.size)
        assertEquals(2, cleared.count)
    }

    @Test
    fun `the per kind helpers read and write the right binding`() {
        val assistant = Assistant(
            id = "a1",
            imageGeneration = AssistantGenerationBinding(enabled = true, serviceId = "img-1"),
        )
        assertEquals("img-1", assistant.generationBinding(GenerationKind.IMAGE)?.serviceId)
        assertNull(assistant.generationBinding(GenerationKind.VIDEO))

        val patched = assistant.withGenerationBinding(
            GenerationKind.VIDEO,
            AssistantGenerationBinding.select(null, "vid-1"),
        )
        assertEquals("vid-1", patched.videoGeneration?.serviceId)
        // 另一类不受影响。
        assertEquals("img-1", patched.imageGeneration?.serviceId)

        // 未知 kind 一律当图片（GenerationKind.normalize 的口径）。
        assertEquals("img-1", patched.generationBinding("nonsense")?.serviceId)
    }

    @Test
    fun `an assistant without the keys reads as unconfigured`() {
        val decoded = json.decodeFromString(Assistant.serializer(), """{"id":"a1","name":"管家"}""")
        assertNull(decoded.imageGeneration)
        assertNull(decoded.videoGeneration)
    }

    @Test
    fun `bindings survive an assistant round-trip`() {
        val assistant = Assistant(
            id = "a1",
            name = "管家",
            imageGeneration = AssistantGenerationBinding(
                enabled = true,
                serviceId = "img-1",
                model = "gpt-image-1",
                size = "1024x1024",
                count = 2,
            ),
            videoGeneration = AssistantGenerationBinding(
                enabled = true,
                serviceId = "vid-1",
                durationSeconds = 8,
                size = "1280x720",
            ),
        )
        val encoded = json.encodeToString(Assistant.serializer(), assistant)
        val decoded = json.decodeFromString(Assistant.serializer(), encoded)
        assertEquals(assistant.imageGeneration, decoded.imageGeneration)
        assertEquals(assistant.videoGeneration, decoded.videoGeneration)
        assertEquals(2, decoded.imageGeneration?.count)
        assertEquals(8, decoded.videoGeneration?.durationSeconds)
    }
}

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

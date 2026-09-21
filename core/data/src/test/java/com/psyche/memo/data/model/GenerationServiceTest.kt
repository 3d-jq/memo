package com.psyche.memo.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生成服务记录（自研功能，上游 kelivo 没有）的纯逻辑：类型归一、端点拼装、参数夹取、
 * JSON 往返。存储那半在 [com.psyche.memo.data.generation.GenerationServiceStore]。
 */
class GenerationServiceTest {

    @Test
    fun `kind falls back to image`() {
        assertEquals(GenerationKind.IMAGE, GenerationKind.normalize(null))
        assertEquals(GenerationKind.IMAGE, GenerationKind.normalize(""))
        assertEquals(GenerationKind.IMAGE, GenerationKind.normalize("VIDEO?"))
        assertEquals(GenerationKind.VIDEO, GenerationKind.normalize(" Video "))
        assertEquals(GenerationKind.IMAGE, GenerationKind.normalize("image"))
    }

    @Test
    fun `base url falls back to the official endpoint and endpoints hang off it`() {
        val blank = GenerationService(id = "s1", kind = GenerationKind.IMAGE, apiKey = "k", model = "gpt-image-1")
        assertEquals("https://api.openai.com/v1", blank.resolvedBaseUrl)
        assertEquals("https://api.openai.com/v1/images/generations", blank.imageEndpoint)

        val custom = blank.copy(baseUrl = " https://api.agnes-ai.cn/v1/ ")
        assertEquals("https://api.agnes-ai.cn/v1", custom.resolvedBaseUrl)
        assertEquals("https://api.agnes-ai.cn/v1/images/generations", custom.imageEndpoint)

        val video = GenerationService(id = "s2", kind = GenerationKind.VIDEO, baseUrl = "https://x.test/v1", apiKey = "k", model = "sora-2")
        assertEquals("https://x.test/v1/videos", video.videosEndpoint)
        assertEquals("https://x.test/v1/videos/task-1", video.videoTaskEndpoint(" task-1 "))
        assertEquals("https://x.test/v1/videos/task-1/content", video.videoContentEndpoint("task-1"))
    }

    @Test
    fun `display name falls back to the model then to a placeholder`() {
        val base = GenerationService(id = "s", model = "gpt-image-1")
        assertEquals("gpt-image-1", base.displayName)
        assertEquals("我的画图", base.copy(name = " 我的画图 ").displayName)
        assertEquals("未命名", base.copy(name = "  ", model = " ").displayName)
    }

    @Test
    fun `a service needs a key and a model`() {
        val service = GenerationService(id = "s", model = "gpt-image-1", apiKey = "sk-1")
        assertTrue(service.isConfigured())
        assertFalse(service.copy(apiKey = " ").isConfigured())
        assertFalse(service.copy(model = "").isConfigured())
    }

    @Test
    fun `normalize trims, clamps and stamps timestamps once`() {
        val raw = GenerationService(
            id = " s ",
            kind = "VIDEO",
            name = "  我的视频  ",
            baseUrl = "https://x.test/v1/",
            apiKey = "  sk-1 ",
            model = " sora-2 ",
            count = 99,
            durationSeconds = 999,
            size = " 1280x720 ",
        )
        val at = 1_700_000_000_000L
        val normalized = raw.normalized(at)
        assertEquals(GenerationKind.VIDEO, normalized.kind)
        assertEquals("我的视频", normalized.name)
        assertEquals("https://x.test/v1", normalized.baseUrl)
        assertEquals("sk-1", normalized.apiKey)
        assertEquals("sora-2", normalized.model)
        assertEquals(GenerationService.MAX_IMAGE_COUNT, normalized.count)
        assertEquals(GenerationService.MAX_VIDEO_SECONDS, normalized.durationSeconds)
        assertEquals("1280x720", normalized.size)
        assertEquals(at, normalized.createdAt)
        assertEquals(at, normalized.updatedAt)

        // 二次归一不能把 createdAt 推后（它只在 0 时补）。
        val again = normalized.normalized(at + 5000)
        assertEquals(at, again.createdAt)
        assertEquals(at + 5000, again.updatedAt)

        // 下限也夹：张数至少 1，时长 0 是合法的「不传」。
        val low = raw.copy(count = 0, durationSeconds = -3).normalized(at)
        assertEquals(GenerationService.MIN_IMAGE_COUNT, low.count)
        assertEquals(0, low.durationSeconds)
    }

    @Test
    fun `json round-trips and tolerates unknown or missing keys`() {
        val service = GenerationService(
            id = "s1",
            kind = GenerationKind.VIDEO,
            name = "视频服务",
            baseUrl = "https://x.test/v1",
            apiKey = "sk-1",
            model = "sora-2",
            durationSeconds = 8,
            size = "1280x720",
            createdAt = 1,
            updatedAt = 2,
        )
        val encoded = GenerationService.encode(service)
        assertEquals(service, GenerationService.decode(encoded))

        // 旧版本写的 payload（缺字段）照旧能读；未来版本多写的键被忽略。
        val sparse = GenerationService.decode("""{"id":"s1","kind":"image"}""")
        assertEquals("s1", sparse?.id)
        assertEquals(GenerationKind.IMAGE, sparse?.kind)
        assertEquals(1, sparse?.count)
        assertEquals(
            "s2",
            GenerationService.decode("""{"id":"s2","kind":"image","futureKey":"x"}""")?.id,
        )
        assertEquals(null, GenerationService.decode("{oops"))
    }
}

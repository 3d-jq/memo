package com.psyche.memo.provider.generation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.data.model.GenerationKind
import com.psyche.memo.data.model.GenerationService
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * 图片生成客户端（自研功能）：OpenAI 兼容 `POST {base}/images/generations`。
 * 钉住请求体形状、b64 与 url 两种响应、错误文案（HTTP 码 + 响应体）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageGenerationClientTest {

    private val server = MockWebServer()
    private val client = OkHttpClient()
    private lateinit var store: GeneratedMediaStore
    private lateinit var subject: ImageGenerationClient

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = GeneratedMediaStore(context)
        subject = ImageGenerationClient(client, store)
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        runCatching { store.imagesDir().listFiles()?.forEach { it.delete() } }
    }

    private fun service(baseUrl: String = server.url("/v1").toString().trimEnd('/')) = GenerationService(
        id = "img",
        kind = GenerationKind.IMAGE,
        name = "画图",
        baseUrl = baseUrl,
        apiKey = "sk-test",
        model = "gpt-image-1",
        size = "1024x1024",
        count = 1,
    )

    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    @Test
    fun `posts the openai image payload and writes the base64 result to disk`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"created":1,"data":[{"b64_json":"${b64("PNGDATA")}"}]}"""),
        )
        val media = subject.generate(service(), service().imageRequest("一只猫"))

        assertEquals(1, media.size)
        assertEquals("image/png", media.single().mimeType)
        assertEquals("PNGDATA", media.single().file.readText())
        assertEquals("images", media.single().file.parentFile?.name)

        val recorded = server.takeRequest()
        assertEquals("/v1/images/generations", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body, body.contains("\"model\":\"gpt-image-1\""))
        assertTrue(body, body.contains("\"prompt\":\"一只猫\""))
        assertTrue(body, body.contains("\"n\":1"))
        assertTrue(body, body.contains("\"size\":\"1024x1024\""))
    }

    @Test
    fun `override wins over the service defaults`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"${b64("A")}"},{"b64_json":"${b64("B")}"}]}"""))
        val media = subject.generate(
            service(),
            service().imageRequest("x", ImageGenOverrides(model = "gpt-image-1-mini", count = 2, size = "512x512")),
        )
        assertEquals(2, media.size)
        // 同一毫秒内两张不能互相覆盖：文件名带递增的 stamp。
        assertTrue(media[0].path != media[1].path)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"model\":\"gpt-image-1-mini\""))
        assertTrue(body, body.contains("\"n\":2"))
        assertTrue(body, body.contains("\"size\":\"512x512\""))
    }

    @Test
    fun `a url response is downloaded and stored`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"url":"${server.url("/files/a.png")}"}]}"""))
        server.enqueue(MockResponse().setBody("URLBYTES").setHeader("Content-Type", "image/webp"))
        val media = subject.generate(service(), service().imageRequest("x"))
        assertEquals("URLBYTES", media.single().file.readText())
        assertEquals("/v1/images/generations", server.takeRequest().path)
        assertEquals("/files/a.png", server.takeRequest().path)
    }

    /**
     * 真实形状（2026-09-17 拿 Agnes 的线上响应实测）：`b64_json` 是**空串**、
     * 真图在 `url` 里。空串必须当「没有」处理、回落到 url —— 否则会拿空 base64
     * 去解码（一条服务看起来很对但永远失败的坑）。
     */
    @Test
    fun `an empty b64 field falls back to the url`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"created":1,"data":[{"b64_json":"","url":"${server.url("/files/agnes.png")}","revised_prompt":""}]}""",
            ),
        )
        server.enqueue(MockResponse().setBody("AGNESBYTES").setHeader("Content-Type", "image/png"))
        val media = subject.generate(service(), service().imageRequest("一只猫"))
        assertEquals("AGNESBYTES", media.single().file.readText())
        assertEquals("/v1/images/generations", server.takeRequest().path)
        assertEquals("/files/agnes.png", server.takeRequest().path)
    }

    @Test
    fun `http failures carry the status and the response body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"rate limited"}}"""))
        val error = runCatching { subject.generate(service(), service().imageRequest("x")) }.exceptionOrNull()
        assertTrue(error is GenerationException)
        assertTrue(error!!.message!!, error.message!!.contains("HTTP 429"))
        assertTrue(error.message!!, error.message!!.contains("rate limited"))
    }

    @Test
    fun `an unconfigured service or an empty prompt fails before any request`() = runBlocking {
        val missingKey = service().copy(apiKey = " ")
        assertTrue(
            runCatching { subject.generate(missingKey, missingKey.imageRequest("x")) }
                .exceptionOrNull() is GenerationException,
        )
        assertTrue(
            runCatching { subject.generate(service(), service().imageRequest("   ")) }
                .exceptionOrNull() is GenerationException,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an empty data array is reported instead of returning nothing`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        val error = runCatching { subject.generate(service(), service().imageRequest("x")) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is GenerationException)
    }
}

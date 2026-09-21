package com.psyche.memo.provider.generation

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.data.assistant.AssistantStore
import com.psyche.memo.data.db.AssistantCache
import com.psyche.memo.data.db.loadSchemaStatements
import com.psyche.memo.data.generation.GenerationServiceStore
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.data.model.AssistantGenerationBinding
import com.psyche.memo.data.model.GenerationKind
import com.psyche.memo.data.model.GenerationService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * 助手的生成工具（自研功能）：`generate_image` / `generate_video`。
 *
 * 两条规矩要钉住：**助手没选服务就不提供工具**（模型看不到也就不会瞎调）、
 * 工具参数覆盖服务默认值。执行路径打 MockWebServer 走真请求。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GenerationToolsTest {

    private val server = MockWebServer()
    private val client = OkHttpClient()
    private lateinit var db: SQLiteDatabase
    private lateinit var repo: GenerationServiceRepository
    private lateinit var store: GeneratedMediaStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = SQLiteDatabase.create(null)
        for (statement in loadSchemaStatements(context)) db.execSQL(statement)
        AssistantCache.invalidate()
        repo = GenerationServiceRepository(GenerationServiceStore(db), AssistantStore(db))
        store = GeneratedMediaStore(context)
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        runCatching { store.imagesDir().listFiles()?.forEach { it.delete() } }
        runCatching { store.videosDir().listFiles()?.forEach { it.delete() } }
        AssistantCache.invalidate()
        db.close()
    }

    private fun service(kind: String) = GenerationService(
        id = "",
        kind = kind,
        name = if (kind == GenerationKind.IMAGE) "画图" else "视频",
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = "sk-test",
        model = if (kind == GenerationKind.IMAGE) "gpt-image-1" else "sora-2",
        size = if (kind == GenerationKind.IMAGE) "1024x1024" else "1280x720",
        count = 1,
        durationSeconds = 4,
    )

    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    @Test
    fun `no service means no tool`() = runBlocking {
        val assistant = Assistant(id = "a", name = "管家")
        assertEquals(0, GenerationTools.buildDefinitions(repo, assistant).size)
        // 开关打开但服务被删了 → 同样不提供（不能给模型一个必然失败的工具）。
        val image = repo.create(service(GenerationKind.IMAGE))
        val bound = assistant.copy(
            imageGeneration = AssistantGenerationBinding(enabled = true, serviceId = image.id),
        )
        assertEquals(1, GenerationTools.buildDefinitions(repo, bound).size)
        repo.delete(image.id)
        assertEquals(0, GenerationTools.buildDefinitions(repo, bound).size)
        assertNull(GenerationTools.serviceFor(repo, bound.imageGeneration))
    }

    @Test
    fun `both bindings offer both tools and the catalog lists them`() = runBlocking {
        val image = repo.create(service(GenerationKind.IMAGE))
        val video = repo.create(service(GenerationKind.VIDEO))
        val assistant = Assistant(
            id = "a",
            imageGeneration = AssistantGenerationBinding(enabled = true, serviceId = image.id),
            videoGeneration = AssistantGenerationBinding(enabled = true, serviceId = video.id),
        )
        val names = GenerationTools.buildDefinitions(repo, assistant).map { it.name }
        assertEquals(listOf(GenerationTools.GENERATE_IMAGE, GenerationTools.GENERATE_VIDEO), names)
        assertEquals(setOf("generate_image", "generate_video"), GenerationTools.ALL_TOOL_NAMES)
        assertEquals(names.toSet(), GenerationTools.catalogDefinitions().map { it.name }.toSet())
        val catalog = com.psyche.memo.ui.BuiltInToolCatalog
            .allBuiltInNames(com.psyche.memo.ui.MemoryPromptLang.zh)
        assertTrue(catalog.containsAll(GenerationTools.ALL_TOOL_NAMES))
    }

    @Test
    fun `image execution posts the prompt and returns the stored paths`() = runBlocking {
        val image = repo.create(service(GenerationKind.IMAGE))
        server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"${b64("IMG")}"}]}"""))
        val result = GenerationTools.execute(
            service = image,
            binding = AssistantGenerationBinding(enabled = true, serviceId = image.id, count = 1),
            name = GenerationTools.GENERATE_IMAGE,
            args = Json.parseToJsonElement("""{"prompt":"一只猫"}""").jsonObject,
            clients = GenerationTools.Clients(
                images = ImageGenerationClient(client, store),
                videos = VideoGenerationClient(client, store),
            ),
        )
        assertEquals(1, result.imagePaths.size)
        assertEquals("IMG", java.io.File(result.imagePaths.single()).readText())
        assertNull(result.videoPath)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"prompt\":\"一只猫\""))
        assertTrue(body, body.contains("\"model\":\"gpt-image-1\""))
        val json = Json.parseToJsonElement(result.json).jsonObject
        assertEquals("succeeded", json["status"]!!.jsonPrimitive.content)
        assertEquals(1, json["count"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `tool arguments win over the service defaults`() = runBlocking {
        val image = repo.create(service(GenerationKind.IMAGE))
        server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"${b64("A")}"},{"b64_json":"${b64("B")}"}]}"""))
        GenerationTools.execute(
            service = image,
            binding = AssistantGenerationBinding(enabled = true, serviceId = image.id, count = 4, size = "512x512"),
            name = GenerationTools.GENERATE_IMAGE,
            args = Json.parseToJsonElement("""{"prompt":"x","count":2,"size":"256x256"}""").jsonObject,
            clients = GenerationTools.Clients(
                images = ImageGenerationClient(client, store),
                videos = VideoGenerationClient(client, store),
            ),
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"n\":2"))
        assertTrue(body, body.contains("\"size\":\"256x256\""))
    }

    @Test
    fun `video execution polls to the terminal state and downloads the clip`() = runBlocking {
        val video = repo.create(service(GenerationKind.VIDEO))
        server.enqueue(MockResponse().setBody("""{"id":"v1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"id":"v1","status":"completed"}"""))
        server.enqueue(MockResponse().setBody("MP4"))
        val result = GenerationTools.execute(
            service = video,
            binding = AssistantGenerationBinding(enabled = true, serviceId = video.id),
            name = GenerationTools.GENERATE_VIDEO,
            args = Json.parseToJsonElement("""{"prompt":"一只猫在弹钢琴"}""").jsonObject,
            clients = GenerationTools.Clients(
                images = ImageGenerationClient(client, store),
                videos = VideoGenerationClient(client, store),
            ),
        )
        assertEquals("MP4", java.io.File(result.videoPath!!).readText())
        assertTrue(result.imagePaths.isEmpty())
        assertEquals("/v1/videos", server.takeRequest().path)
        assertEquals("/v1/videos/v1", server.takeRequest().path)
        assertEquals("/v1/videos/v1/content", server.takeRequest().path)
        val json = Json.parseToJsonElement(result.json).jsonObject
        assertEquals("succeeded", json["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a failed video task surfaces the provider message`() = runBlocking {
        val video = repo.create(service(GenerationKind.VIDEO))
        server.enqueue(MockResponse().setBody("""{"id":"v1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"id":"v1","status":"failed","error":{"message":"prompt rejected"}}"""))
        val error = runCatching {
            GenerationTools.execute(
                service = video,
                binding = null,
                name = GenerationTools.GENERATE_VIDEO,
                args = Json.parseToJsonElement("""{"prompt":"x"}""").jsonObject,
                clients = GenerationTools.Clients(
                    images = ImageGenerationClient(client, store),
                    videos = VideoGenerationClient(client, store),
                ),
            )
        }.exceptionOrNull()
        assertTrue(error is GenerationException)
        assertTrue(error!!.message!!, error.message!!.contains("prompt rejected"))
    }

    @Test
    fun `an empty prompt is rejected before any request`() = runBlocking {
        val image = repo.create(service(GenerationKind.IMAGE))
        val error = runCatching {
            GenerationTools.execute(
                service = image,
                binding = null,
                name = GenerationTools.GENERATE_IMAGE,
                args = Json.parseToJsonElement("""{"prompt":"  "}""").jsonObject,
                clients = GenerationTools.Clients(
                    images = ImageGenerationClient(client, store),
                    videos = VideoGenerationClient(client, store),
                ),
            )
        }.exceptionOrNull()
        assertTrue(error is GenerationException)
        assertEquals(0, server.requestCount)
    }
}

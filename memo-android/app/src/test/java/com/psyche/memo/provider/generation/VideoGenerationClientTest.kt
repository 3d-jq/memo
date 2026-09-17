package com.psyche.memo.provider.generation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.data.model.GenerationKind
import com.psyche.memo.data.model.GenerationService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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

/**
 * 视频生成客户端（自研功能）：OpenAI 兼容 `POST {base}/videos` 提交 +
 * `GET {base}/videos/{id}` 轮询 + `GET {base}/videos/{id}/content` 下载。
 * 顺带钉住「中转站字段名不一样」时的容错解析。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoGenerationClientTest {

    private val server = MockWebServer()
    private val client = OkHttpClient()
    private lateinit var store: GeneratedMediaStore
    private lateinit var subject: VideoGenerationClient

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = GeneratedMediaStore(context)
        subject = VideoGenerationClient(client, store)
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        runCatching { store.videosDir().listFiles()?.forEach { it.delete() } }
    }

    private fun service() = GenerationService(
        id = "vid",
        kind = GenerationKind.VIDEO,
        name = "视频",
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = "sk-test",
        model = "sora-2",
        size = "1280x720",
        durationSeconds = 8,
    )

    @Test
    fun `create posts seconds as a string and reads the task id`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"video_123","status":"queued","progress":0}"""))
        val task = subject.create(service(), service().videoRequest("一只猫在弹钢琴"))

        assertEquals("video_123", task.id)
        assertEquals(VideoStatus.QUEUED, task.status)
        assertEquals(0, task.progress)

        val recorded = server.takeRequest()
        assertEquals("/v1/videos", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body, body.contains("\"model\":\"sora-2\""))
        assertTrue(body, body.contains("\"seconds\":\"8\""))
        assertTrue(body, body.contains("\"size\":\"1280x720\""))
    }

    @Test
    fun `query maps the documented statuses`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"video_123","status":"in_progress","progress":42}"""))
        val running = subject.query(service(), "video_123")
        assertEquals(VideoStatus.RUNNING, running.status)
        assertEquals(42, running.progress)
        assertEquals("/v1/videos/video_123", server.takeRequest().path)

        server.enqueue(MockResponse().setBody("""{"id":"video_123","status":"completed","progress":100}"""))
        val done = subject.query(service(), "video_123")
        assertEquals(VideoStatus.SUCCEEDED, done.status)
        assertTrue(done.status.isTerminal)
    }

    @Test
    fun `a failed task surfaces the provider message`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"id":"video_1","status":"failed","error":{"code":"x","message":"prompt rejected"}}"""),
        )
        val task = subject.query(service(), "video_1")
        assertEquals(VideoStatus.FAILED, task.status)
        assertEquals("prompt rejected", task.error)
    }

    @Test
    fun `watch polls until the task reaches a terminal state`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"v1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"id":"v1","status":"in_progress"}"""))
        server.enqueue(MockResponse().setBody("""{"id":"v1","status":"completed","url":"https://cdn.test/v1.mp4"}"""))

        val seen = subject.watch(service(), "v1", intervalMs = 1_000).toList()
        assertEquals(
            listOf(VideoStatus.QUEUED, VideoStatus.RUNNING, VideoStatus.SUCCEEDED),
            seen.map { it.status },
        )
        assertEquals("https://cdn.test/v1.mp4", seen.last().url)
    }

    @Test
    fun `download prefers the content endpoint and writes the file`() = runBlocking {
        server.enqueue(MockResponse().setBody("MP4BYTES"))
        val media = subject.download(
            service(),
            VideoTask(id = "v1", status = VideoStatus.SUCCEEDED),
        )
        assertEquals("/v1/videos/v1/content", server.takeRequest().path)
        assertEquals("MP4BYTES", media.file.readText())
        assertEquals("video/mp4", media.mimeType)
        assertEquals("videos", media.file.parentFile?.name)
        assertNull(media.thumbnailPath)
    }

    @Test
    fun `download falls back to the url carried by the task`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"no content route"}"""))
        server.enqueue(MockResponse().setBody("FALLBACK"))
        val media = subject.download(
            service(),
            VideoTask(id = "v1", status = VideoStatus.SUCCEEDED, url = server.url("/cdn/v1.mp4").toString()),
        )
        assertEquals("FALLBACK", media.file.readText())
        assertEquals("/v1/videos/v1/content", server.takeRequest().path)
        assertEquals("/cdn/v1.mp4", server.takeRequest().path)
    }

    @Test
    fun `download stores the thumbnail when the task offers one`() = runBlocking {
        server.enqueue(MockResponse().setBody("MP4"))
        server.enqueue(MockResponse().setBody("COVER"))
        val media = subject.download(
            service(),
            VideoTask(id = "v1", status = VideoStatus.SUCCEEDED, thumbnailUrl = server.url("/cdn/v1.jpg").toString()),
        )
        assertEquals("MP4", media.file.readText())
        assertEquals("COVER", media.thumbnailFile!!.readText())
    }

    @Test
    fun `tolerant parsing accepts aggregator field names`() {
        val task = subject.parseTask(
            """{"task_id":"agg-1","state":"success","output":["https://x.test/a.mp4"],"duration":5}""",
            "Video generation",
        )
        assertEquals("agg-1", task.id)
        assertEquals(VideoStatus.SUCCEEDED, task.status)
        assertEquals("https://x.test/a.mp4", task.url)
        assertEquals(5, task.seconds)

        val nested = subject.parseTask("""{"id":"v2","status":"completed","data":{"video_url":"https://x.test/b.mp4"}}""", "Video status")
        assertEquals("https://x.test/b.mp4", nested.url)

        // 没有 status 但有 error → 当作失败；没有 id → 直接报错。
        assertEquals(VideoStatus.FAILED, subject.parseTask("""{"id":"v3","error":"boom"}""", "Video status").status)
        assertTrue(
            runCatching { subject.parseTask("""{"status":"queued"}""", "Video status") }
                .exceptionOrNull() is GenerationException,
        )
    }

    @Test
    fun `status aliases cover the common shapes`() {
        assertEquals(VideoStatus.QUEUED, VideoStatus.parse("PENDING"))
        assertEquals(VideoStatus.QUEUED, VideoStatus.parse("submitted"))
        assertEquals(VideoStatus.RUNNING, VideoStatus.parse("processing"))
        assertEquals(VideoStatus.SUCCEEDED, VideoStatus.parse("done"))
        assertEquals(VideoStatus.FAILED, VideoStatus.parse("error"))
        assertEquals(VideoStatus.CANCELLED, VideoStatus.parse("canceled"))
        assertEquals(VideoStatus.UNKNOWN, VideoStatus.parse(null))
        assertTrue(VideoStatus.FAILED.isTerminal)
        assertTrue(!VideoStatus.UNKNOWN.isTerminal)
    }
}

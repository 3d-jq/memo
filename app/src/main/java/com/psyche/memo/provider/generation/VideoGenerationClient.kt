package com.psyche.memo.provider.generation

import com.psyche.memo.data.model.GenerationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** 视频任务状态（OpenAI `/videos` 的形状 + 中转站常见的别名）。 */
enum class VideoStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED,
    UNKNOWN;

    val isTerminal: Boolean get() = this in TERMINAL

    companion object {
        private val TERMINAL = setOf(SUCCEEDED, FAILED, CANCELLED, EXPIRED)

        fun parse(raw: String?): VideoStatus = when (raw?.trim()?.lowercase()) {
            null, "" -> UNKNOWN
            "queued", "pending", "submitted", "created", "waiting", "in_queue" -> QUEUED
            "in_progress", "processing", "running", "generating" -> RUNNING
            "completed", "complete", "succeeded", "success", "done", "finished" -> SUCCEEDED
            "failed", "failure", "error" -> FAILED
            "cancelled", "canceled" -> CANCELLED
            "expired" -> EXPIRED
            else -> UNKNOWN
        }
    }
}

/**
 * 一个视频任务（提交后轮询用）。字段按 OpenAI 的形状命名，解析时容忍别名 ——
 * 这条链路要面对各种 OpenAI 兼容中转，字段名不完全一样是常态。
 */
data class VideoTask(
    val id: String,
    val status: VideoStatus,
    val progress: Int? = null,
    /** 有些服务直接给可下载地址（就不用再打 `/content`）。 */
    val url: String? = null,
    val thumbnailUrl: String? = null,
    val error: String? = null,
    val seconds: Int? = null,
    val size: String? = null,
)

/**
 * 视频生成客户端（自研功能）—— OpenAI 兼容形状：
 *  · 提交 `POST {base}/videos` `{"model","prompt","seconds":"8","size":"1280x720"}`
 *  · 查询 `GET  {base}/videos/{id}`
 *  · 下载 `GET  {base}/videos/{id}/content`（失败再退回任务里的 url）
 *
 * 生成是**异步长任务**（几十秒到几分钟），所以 [watch] 用轮询把状态发出来，
 * UI 先占位再回填；协程取消即停止轮询。
 */
class VideoGenerationClient(
    private val client: OkHttpClient,
    private val mediaStore: GeneratedMediaStore,
) {

    data class VideoRequest(
        val prompt: String,
        val model: String,
        val seconds: Int = 0,
        val size: String = "",
    )

    /** 提交任务。 */
    suspend fun create(
        service: GenerationService,
        request: VideoRequest,
        timeoutMs: Long = GenerationHttp.POLL_TIMEOUT_MS,
    ): VideoTask = withContext(Dispatchers.IO) {
        if (!service.isConfigured()) throw GenerationException("Video service is not configured")
        val prompt = request.prompt.trim()
        if (prompt.isEmpty()) throw GenerationException("Prompt is required")
        val model = request.model.trim().ifEmpty { service.model.trim() }

        val payload = buildJsonObject {
            put("model", model)
            put("prompt", prompt)
            val seconds = if (request.seconds > 0) request.seconds else service.durationSeconds
            if (seconds > 0) put("seconds", seconds.toString())
            val size = request.size.trim().ifEmpty { service.size.trim() }
            if (size.isNotEmpty()) put("size", size)
        }.toString()

        val httpRequest = Request.Builder()
            .url(service.videosEndpoint)
            .header("Content-Type", "application/json")
            .auth(service.apiKey)
            .post(payload.toRequestBody(JSON))
            .build()

        val body = execute(httpRequest, "Video generation", timeoutMs)
        parseTask(body, "Video generation")
    }

    /** 查询任务。 */
    suspend fun query(
        service: GenerationService,
        taskId: String,
        timeoutMs: Long = GenerationHttp.POLL_TIMEOUT_MS,
    ): VideoTask = withContext(Dispatchers.IO) {
        val httpRequest = Request.Builder()
            .url(service.videoTaskEndpoint(taskId))
            .auth(service.apiKey)
            .get()
            .build()
        parseTask(execute(httpRequest, "Video status", timeoutMs), "Video status")
    }

    /** 下载成品（`/content`；没有再退回任务里的 url）。 */
    suspend fun download(
        service: GenerationService,
        task: VideoTask,
        timeoutMs: Long = GenerationHttp.DOWNLOAD_TIMEOUT_MS,
    ): GeneratedMedia = withContext(Dispatchers.IO) {
        val bytes = contentBytes(service, task, timeoutMs)
        if (bytes.isEmpty()) throw GenerationException("The provider returned an empty video")
        val cover = task.thumbnailUrl?.let { url ->
            runCatching {
                val request = Request.Builder().url(url).auth(service.apiKey).get().build()
                client.callWithTimeout(request, timeoutMs).execute().use { response ->
                    if (response.isSuccessful) response.body?.bytes() else null
                }
            }.getOrNull()
        }
        mediaStore.saveVideo(bytes, thumbnail = cover)
    }

    /**
     * 轮询直到终态。默认 15 秒一次（跟 RikkaHub 的 `videogen` 轮询节奏一致），
     * 但第一次立刻查一次 —— 提交后马上看一眼能省掉一个周期的白等。
     */
    fun watch(
        service: GenerationService,
        taskId: String,
        intervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    ): Flow<VideoTask> = flow {
        while (true) {
            val task = query(service, taskId)
            emit(task)
            if (task.status.isTerminal) return@flow
            delay(intervalMs.coerceAtLeast(1_000L))
        }
    }

    private suspend fun contentBytes(service: GenerationService, task: VideoTask, timeoutMs: Long): ByteArray {
        val direct = task.url
        val candidates = buildList {
            add(service.videoContentEndpoint(task.id))
            if (!direct.isNullOrBlank()) add(direct)
        }
        var last: GenerationException? = null
        for (url in candidates) {
            try {
                val request = Request.Builder().url(url).auth(service.apiKey).get().build()
                return client.callWithTimeout(request, timeoutMs).execute().use { response ->
                    if (!response.isSuccessful) throw GenerationHttp.failure("Video download", response)
                    response.body?.bytes() ?: ByteArray(0)
                }
            } catch (e: GenerationException) {
                last = e
            } catch (e: IOException) {
                last = GenerationHttp.ioFailure("Video download", e)
            }
        }
        throw last ?: GenerationException("Video download failed")
    }

    private fun execute(request: Request, what: String, timeoutMs: Long): String = try {
        client.callWithTimeout(request, timeoutMs).execute().use { response ->
            if (!response.isSuccessful) throw GenerationHttp.failure(what, response)
            response.body?.string().orEmpty()
        }
    } catch (e: IOException) {
        throw GenerationHttp.ioFailure(what, e)
    }

    /** 容错解析：`id`/`task_id`/`video_id`、`status`/`state`、`progress` 三态、嵌套 url。 */
    internal fun parseTask(body: String, what: String): VideoTask {
        val root = GenerationHttp.parseObject(body)
        val id = root.str("id", "task_id", "video_id", "request_id")
            ?: throw GenerationException("$what failed: the provider returned no task id")
        val error = root.errorMessage()
        val status = VideoStatus.parse(root.str("status", "state", "task_status"))
        return VideoTask(
            id = id,
            status = if (status == VideoStatus.UNKNOWN && error != null) VideoStatus.FAILED else status,
            progress = root.int("progress", "percent", "percentage"),
            url = findUrl(root),
            thumbnailUrl = findThumbnail(root),
            error = error,
            seconds = root.int("seconds", "duration"),
            size = root.str("size", "resolution"),
        )
    }

    private fun findUrl(root: JsonObject): String? {
        root.str("url", "video_url", "download_url", "output_url", "content_url")?.let { return it }
        val output = root["output"]
        if (output is JsonPrimitive) output.contentOrNullSafe()?.let { return it }
        if (output is JsonArray) {
            output.firstOrNull()?.let { first ->
                if (first is JsonPrimitive) first.contentOrNullSafe()?.let { return it }
                if (first is JsonObject) first.str("url", "video_url")?.let { return it }
            }
        }
        val data = root["data"]
        if (data is JsonObject) data.str("url", "video_url")?.let { return it }
        if (data is JsonArray) {
            data.firstOrNull()?.let { first ->
                if (first is JsonObject) first.str("url", "video_url")?.let { return it }
            }
        }
        (root["content"] as? JsonObject)?.str("url", "video_url")?.let { return it }
        return null
    }

    private fun findThumbnail(root: JsonObject): String? =
        root.str("thumbnail_url", "cover_url", "poster_url", "thumbnail")
            ?: (root["thumbnail"] as? JsonObject)?.str("url")

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val DEFAULT_POLL_INTERVAL_MS = 15_000L
    }
}

/** 便捷：把服务里配置的默认值铺进请求。 */
fun GenerationService.videoRequest(prompt: String, override: VideoGenOverrides = VideoGenOverrides()): VideoGenerationClient.VideoRequest =
    VideoGenerationClient.VideoRequest(
        prompt = prompt,
        model = override.model ?: model,
        seconds = override.durationSeconds ?: durationSeconds,
        size = override.size ?: size,
    )

/** 助手/➕ 面板对服务默认值的覆盖（null = 用服务里的）。 */
data class VideoGenOverrides(
    val model: String? = null,
    val durationSeconds: Int? = null,
    val size: String? = null,
)

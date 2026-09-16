package com.psyche.memo.provider.generation

import com.psyche.memo.data.model.Assistant
import com.psyche.memo.data.model.AssistantGenerationBinding
import com.psyche.memo.data.model.GenerationService
import com.psyche.memo.llm.client.LlmToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 助手的生成工具（自研功能）：`generate_image` / `generate_video`。
 *
 * 与 `search_web` 同一套语义：**助手在「生成图片 / 生成视频」tab 里选了服务才提供**
 * 对应工具；没选就不出现在请求里（模型看不到、也就不会瞎调）。服务本身的 key /
 * 地址 / 模型在设置里全局一份，助手只带「用哪个 + 覆盖哪些参数」。
 *
 * 视频是异步长任务，所以 [executeVideo] 会一直轮询到终态（或超时）再返回 ——
 * 工具调用期间对话在等，这在现有工具面里是唯一可行的形态（工具结果一次性写回）；
 * 轮询上限 [VIDEO_TIMEOUT_MS] 兜底，超时如实报 `video_timeout` 让模型自己决定。
 */
object GenerationTools {

    const val GENERATE_IMAGE = "generate_image"
    const val GENERATE_VIDEO = "generate_video"

    val ALL_TOOL_NAMES = setOf(GENERATE_IMAGE, GENERATE_VIDEO)

    /** 视频工具最长等多久（10 分钟；超时返回 tool_error，不无限挂着对话）。 */
    const val VIDEO_TIMEOUT_MS = 10 * 60 * 1000L

    private val IMAGE_DESCRIPTION = """
        Generate one or more images from a text prompt.
        Use this when the user asks you to draw, create, or generate a picture.
        The generated images are attached to the conversation automatically; do not
        describe them in detail, just tell the user what you made.
    """.trimIndent().replace("\n", " ")

    private val VIDEO_DESCRIPTION = """
        Generate a short video from a text prompt.
        Use this when the user asks you to create or generate a video or animation.
        Generation takes a while (typically 1-5 minutes); the video is attached to the
        conversation automatically once it is ready.
    """.trimIndent().replace("\n", " ")

    /** `generate_image` 的参数（prompt 必填，其余覆盖服务默认值）。 */
    fun imageParametersJson(): String = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "prompt",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "What the image should show. Be specific.")
                    },
                )
                put(
                    "count",
                    buildJsonObject {
                        put("type", "integer")
                        put("description", "How many images to generate (1-4). Omit to use the default.")
                    },
                )
                put(
                    "size",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Image size such as 1024x1024. Omit to use the default.")
                    },
                )
            },
        )
        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("prompt"))))
    }.toString()

    /** `generate_video` 的参数。 */
    fun videoParametersJson(): String = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "prompt",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "What the video should show, including camera motion.")
                    },
                )
                put(
                    "seconds",
                    buildJsonObject {
                        put("type", "integer")
                        put("description", "Clip length in seconds. Omit to use the default.")
                    },
                )
                put(
                    "size",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Video size such as 1280x720. Omit to use the default.")
                    },
                )
            },
        )
        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("prompt"))))
    }.toString()

    /** 助手选的服务（没选 / 服务已删 → null = 不提供这个工具）。 */
    fun serviceFor(
        repo: GenerationServiceRepository,
        binding: AssistantGenerationBinding?,
    ): GenerationService? {
        if (binding?.isUsable != true) return null
        return repo.get(binding.serviceId!!)?.takeIf { it.isConfigured() }
    }

    fun buildDefinitions(
        repo: GenerationServiceRepository,
        assistant: Assistant?,
    ): List<LlmToolSpec> {
        if (assistant == null) return emptyList()
        val out = mutableListOf<LlmToolSpec>()
        if (serviceFor(repo, assistant.imageGeneration) != null) {
            out.add(LlmToolSpec(GENERATE_IMAGE, IMAGE_DESCRIPTION, imageParametersJson()))
        }
        if (serviceFor(repo, assistant.videoGeneration) != null) {
            out.add(LlmToolSpec(GENERATE_VIDEO, VIDEO_DESCRIPTION, videoParametersJson()))
        }
        return out
    }

    /** 工具目录（设置 → 工具描述）用：两种定义都给，不看助手配没配。 */
    fun catalogDefinitions(): List<LlmToolSpec> = listOf(
        LlmToolSpec(GENERATE_IMAGE, IMAGE_DESCRIPTION, imageParametersJson()),
        LlmToolSpec(GENERATE_VIDEO, VIDEO_DESCRIPTION, videoParametersJson()),
    )

    /** 执行结果：写回模型的 JSON 文本 + 要挂进工具 part 的产出（图片 / 视频）。 */
    data class Result(
        val json: String,
        val imagePaths: List<String> = emptyList(),
        val videoPath: String? = null,
    )

    suspend fun execute(
        service: GenerationService,
        binding: AssistantGenerationBinding?,
        name: String,
        args: JsonObject,
        clients: Clients,
    ): Result = when (name) {
        GENERATE_IMAGE -> executeImage(service, binding, args, clients)
        GENERATE_VIDEO -> executeVideo(service, binding, args, clients)
        else -> error("Unknown generation tool: $name")
    }

    /** 两个客户端打包传入（工具执行只有容器能造它们）。 */
    data class Clients(
        val images: ImageGenerationClient,
        val videos: VideoGenerationClient,
    )

    suspend fun executeImage(
        service: GenerationService,
        binding: AssistantGenerationBinding?,
        args: JsonObject,
        clients: Clients,
    ): Result {
        val prompt = args.str("prompt")?.trim().orEmpty()
        if (prompt.isEmpty()) throw GenerationException("prompt is required")
        val count = args.int("count") ?: binding?.count ?: service.count
        val size = args.str("size") ?: binding?.size ?: service.size
        val media = clients.images.generate(
            service = service,
            request = ImageGenerationClient.ImageRequest(
                prompt = prompt,
                model = binding?.model ?: service.model,
                count = count.coerceIn(1, 4),
                size = size.orEmpty(),
            ),
        )
        return Result(
            json = buildJsonObject {
                put("type", "image_generation_result")
                put("status", "succeeded")
                put("model", binding?.model ?: service.model)
                put("count", media.size)
                put("paths", kotlinx.serialization.json.JsonArray(media.map { JsonPrimitive(it.path) }))
            }.toString(),
            imagePaths = media.map { it.path },
        )
    }

    suspend fun executeVideo(
        service: GenerationService,
        binding: AssistantGenerationBinding?,
        args: JsonObject,
        clients: Clients,
    ): Result = withContext(Dispatchers.IO) {
        val prompt = args.str("prompt")?.trim().orEmpty()
        if (prompt.isEmpty()) throw GenerationException("prompt is required")
        val seconds = args.int("seconds") ?: binding?.durationSeconds ?: service.durationSeconds
        val size = args.str("size") ?: binding?.size ?: service.size
        val request = VideoGenerationClient.VideoRequest(
            prompt = prompt,
            model = binding?.model ?: service.model,
            seconds = seconds,
            size = size.orEmpty(),
        )
        val created = clients.videos.create(service, request)
        val deadline = System.currentTimeMillis() + VIDEO_TIMEOUT_MS
        var latest = created
        while (!latest.status.isTerminal && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(POLL_INTERVAL_MS)
            latest = clients.videos.query(service, created.id)
        }
        if (!latest.status.isTerminal) {
            throw GenerationException(
                "video_timeout: task ${created.id} is still ${latest.status.name}",
            )
        }
        if (latest.status != VideoStatus.SUCCEEDED) {
            throw GenerationException(latest.error ?: "video ${latest.status.name.lowercase()}")
        }
        val media = clients.videos.download(service, latest)
        Result(
            json = buildJsonObject {
                put("type", "video_generation_result")
                put("status", "succeeded")
                put("model", binding?.model ?: service.model)
                put("path", media.path)
                media.thumbnailPath?.let { put("thumbnail_path", it) }
            }.toString(),
            videoPath = media.path,
        )
    }

    private const val POLL_INTERVAL_MS = 10_000L

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.trim()?.toIntOrNull()
}

package com.psyche.memo.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 生成服务类型（设置里两个入口：生成图片 / 生成视频）。 */
object GenerationKind {
    const val IMAGE = "image"
    const val VIDEO = "video"

    fun normalize(raw: String?): String = when (raw?.trim()?.lowercase()) {
        VIDEO -> VIDEO
        else -> IMAGE
    }
}

/**
 * 「测试连接」的结果三态（自研功能）。
 *
 * 为什么必须三态：**生成类中转大多不提供 `/models` 列表接口**（回 404/405）——
 * 服务本身是好的，只是没有这个探针接口。两态（成功/失败）会把这类服务直接标成
 * 「连接失败」（用户 2026-09-17「视频、图片显示连接失败，但服务是能用的」就是
 * 这条），所以「接口可达但没有 /models」要单列 [REACHABLE]，与真失败区分开。
 */
object GenerationTestState {
    const val OK = "ok"
    const val REACHABLE = "reachable"
    const val FAILED = "failed"

    /** 认不出的值一律当「没测过」——免得脏数据在列表里显示成怪状态。 */
    fun normalize(raw: String?): String? = when (raw?.trim()?.lowercase()) {
        OK -> OK
        REACHABLE -> REACHABLE
        FAILED -> FAILED
        else -> null
    }
}

/**
 * 生成服务（自研功能，上游 kelivo 没有）：一份「OpenAI 兼容」的图片/视频生成端点配置。
 *
 * 存储：drift v3 的通用表 `extension_entity_rows`（`kind = "generation_service"`），
 * **不能给 services 加表** —— schema 是 drift 生成 + 门禁校验零 diff（同工作区）。
 * 图片与视频共用一条记录类型，靠 [kind] 区分，`size/count`（图片）与
 * `durationSeconds/resolution/aspectRatio/generateAudio`（视频）各自只用一半。
 *
 * 为什么是「OpenAI 兼容」：用户确认两类服务都按 OpenAI 的接口形状打 ——
 * 图片 `POST {base}/images/generations`，视频 `POST {base}/videos` 提交 +
 * `GET {base}/videos/{id}` 轮询。这样任何 OpenAI 兼容中转（Agnes / 随想 / 官方…）
 * 都能直接用，不用为每家写适配器。
 */
@Serializable
data class GenerationService(
    val id: String,
    val kind: String = GenerationKind.IMAGE,
    val name: String = "",
    /** 形如 `https://api.openai.com/v1`；空 → [DEFAULT_BASE_URL]。 */
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    // ---- 图片 ----
    /** 图片尺寸 "1024x1024" / 视频尺寸 "1280x720"；空 = 不传，用服务端默认。 */
    val size: String = "",
    val count: Int = 1,
    // ---- 视频 ----
    /** 秒数；0 = 不传（视频用 [size] 表示尺寸）。 */
    val durationSeconds: Int = 0,
    // ---- 元信息 ----
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** 最近一次「测试连接」的结果（[GenerationTestState] 三态之一）；null = 没测过。 */
    val lastTestState: String? = null,
    val lastTestAt: Long = 0,
) {
    val isImage: Boolean get() = kind == GenerationKind.IMAGE

    /** 端点基地址（trim + 去尾斜杠 + 空串回落官方）。 */
    val resolvedBaseUrl: String
        get() = baseUrl.trim().trimEnd('/').ifEmpty { DEFAULT_BASE_URL }

    /** 图片生成：`POST {base}/images/generations`。 */
    val imageEndpoint: String get() = resolvedBaseUrl + "/images/generations"

    /** 视频提交：`POST {base}/videos`。 */
    val videosEndpoint: String get() = resolvedBaseUrl + "/videos"

    /** 视频查询：`GET {base}/videos/{id}`。 */
    fun videoTaskEndpoint(taskId: String): String = videosEndpoint + "/" + taskId.trim()

    /** 视频下载（Sora 形状）：`GET {base}/videos/{id}/content`。 */
    fun videoContentEndpoint(taskId: String): String = videoTaskEndpoint(taskId) + "/content"

    /** 配置齐了没有（key + 模型；地址留空算用官方）。 */
    fun isConfigured(): Boolean = apiKey.trim().isNotEmpty() && model.trim().isNotEmpty()

    /** 列表里显示的名字：没填就用模型名，再没有就「未命名」。 */
    val displayName: String
        get() = name.trim().ifEmpty { model.trim().ifEmpty { untitled } }

    /**
     * 保存前归一：trim、类型归一、参数夹取（图片 1..4 张、视频 0..60 秒）。
     * 越界的输入一律**夹**而不是报错 —— 这些值来自输入框，用户不该被一个
     * 手滑的数字卡住。
     */
    fun normalized(now: Long = System.currentTimeMillis()): GenerationService = copy(
        kind = GenerationKind.normalize(kind),
        name = name.trim(),
        baseUrl = baseUrl.trim().trimEnd('/'),
        apiKey = apiKey.trim(),
        model = model.trim(),
        size = size.trim(),
        count = count.coerceIn(MIN_IMAGE_COUNT, MAX_IMAGE_COUNT),
        durationSeconds = durationSeconds.coerceIn(0, MAX_VIDEO_SECONDS),
        lastTestState = GenerationTestState.normalize(lastTestState),
        createdAt = if (createdAt == 0L) now else createdAt,
        updatedAt = now,
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val MIN_IMAGE_COUNT = 1
        const val MAX_IMAGE_COUNT = 4
        const val MAX_VIDEO_SECONDS = 60
        private const val untitled = "未命名"

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(service: GenerationService): String = json.encodeToString(serializer(), service)

        fun decode(payload: String): GenerationService? =
            runCatching { json.decodeFromString(serializer(), payload) }.getOrNull()
    }
}

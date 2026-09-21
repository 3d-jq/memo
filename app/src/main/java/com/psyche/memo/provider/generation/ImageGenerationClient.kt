package com.psyche.memo.provider.generation

import com.psyche.memo.data.model.GenerationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64

/**
 * 图片生成客户端（自研功能）—— OpenAI 兼容 `POST {base}/images/generations`：
 *
 * ```json
 * {"model":"gpt-image-1","prompt":"...","n":1,"size":"1024x1024"}
 * ```
 * 响应按上游两种形态解析：`data[].b64_json`（+ `output_format`）或 `data[].url`
 * （再下载成字节）。两条路径都返回 [GeneratedMedia]（已落盘）。
 */
class ImageGenerationClient(
    private val client: OkHttpClient,
    private val mediaStore: GeneratedMediaStore,
) {

    data class ImageRequest(
        val prompt: String,
        val model: String,
        val count: Int = 1,
        val size: String = "",
    )

    suspend fun generate(
        service: GenerationService,
        request: ImageRequest,
        timeoutMs: Long = GenerationHttp.DEFAULT_TIMEOUT_MS,
    ): List<GeneratedMedia> = withContext(Dispatchers.IO) {
        if (!service.isConfigured()) throw GenerationException("Image service is not configured")
        val prompt = request.prompt.trim()
        if (prompt.isEmpty()) throw GenerationException("Prompt is required")
        val model = request.model.trim().ifEmpty { service.model.trim() }

        val payload = buildJsonObject {
            put("model", model)
            put("prompt", prompt)
            put("n", request.count.coerceIn(1, 4))
            val size = request.size.trim().ifEmpty { service.size.trim() }
            if (size.isNotEmpty()) put("size", size)
        }.toString()

        val httpRequest = Request.Builder()
            .url(service.imageEndpoint)
            .header("Content-Type", "application/json")
            .auth(service.apiKey)
            .post(payload.toRequestBody(JSON))
            .build()

        val body = try {
            client.callWithTimeout(httpRequest, timeoutMs).execute().use { response ->
                if (!response.isSuccessful) throw GenerationHttp.failure("Image generation", response)
                response.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw GenerationHttp.ioFailure("Image generation", e)
        }

        val items = parseItems(service, body)
        if (items.isEmpty()) throw GenerationException("The provider returned no image")
        items.mapIndexed { index, item ->
            val bytes = when {
                item.base64 != null -> decode(item.base64)
                item.url != null -> download(item.url, timeoutMs)
                else -> throw GenerationException("The provider returned no image data")
            }
            mediaStore.saveImage(bytes, item.mimeType, stamp = System.currentTimeMillis() + index)
        }
    }

    internal data class Item(val base64: String?, val url: String?, val mimeType: String)

    /** `data[]` 两种形态（b64_json / url）；顺带认顶层 `output_format` 默认值。 */
    internal fun parseItems(service: GenerationService, body: String): List<Item> {
        val root = GenerationHttp.parseObject(body)
        val defaultFormat = root.str("output_format") ?: "png"
        val data = root["data"] as? JsonArray
            ?: (root["images"] as? JsonArray)
            ?: throw GenerationException("The provider returned an invalid image response")
        return data.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val b64 = obj.str("b64_json", "b64", "base64")
            val url = obj.str("url", "image_url")
            if (b64 == null && url == null) return@mapNotNull null
            val format = obj.str("output_format") ?: defaultFormat
            Item(base64 = b64, url = url, mimeType = mimeFor(format))
        }
    }

    private fun download(url: String, timeoutMs: Long): ByteArray {
        val request = Request.Builder().url(url).get().build()
        return try {
            client.callWithTimeout(request, timeoutMs).execute().use { response ->
                if (!response.isSuccessful) throw GenerationHttp.failure("Image download", response)
                response.body?.bytes() ?: ByteArray(0)
            }
        } catch (e: IOException) {
            throw GenerationHttp.ioFailure("Image download", e)
        }
    }

    private fun decode(base64: String): ByteArray = runCatching {
        Base64.getDecoder().decode(base64.trim())
    }.getOrElse { throw GenerationException("The provider returned an invalid base64 image") }

    private fun mimeFor(format: String): String = when (format.lowercase().trim()) {
        "jpg", "jpeg", "image/jpeg" -> "image/jpeg"
        "webp", "image/webp" -> "image/webp"
        else -> "image/png"
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** 便捷：把服务里配置的默认值铺进请求。 */
fun GenerationService.imageRequest(prompt: String, override: ImageGenOverrides = ImageGenOverrides()): ImageGenerationClient.ImageRequest =
    ImageGenerationClient.ImageRequest(
        prompt = prompt,
        model = override.model ?: model,
        count = override.count ?: count,
        size = override.size ?: size,
    )

/** 助手/➕ 面板对服务默认值的覆盖（null = 用服务里的）。 */
data class ImageGenOverrides(
    val model: String? = null,
    val count: Int? = null,
    val size: String? = null,
)

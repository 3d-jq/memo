package com.psyche.memo.provider.generation

import com.psyche.memo.data.model.GenerationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 生成服务的「测试连接」（自研功能）。
 *
 * **不发真实生成请求**：那要花钱、还要等几十秒。这里打 OpenAI 兼容的
 * `GET {base}/models`（同一个 key/地址，能验出「地址对不对、key 有没有效」），
 * 顺便数一下模型数量。有的中转不实现 `/models`，那就如实说「接口可达但没有
 * /models 列表」——不去猜、也不假装成功。
 */
object GenerationServiceTester {

    sealed interface Result {
        /** 通，且拿到了模型列表。 */
        data class Ok(val modelCount: Int) : Result

        /** 通，但这个端点没有 `/models`。 */
        data object ReachableWithoutModels : Result

        data class Failed(val message: String) : Result
    }

    suspend fun test(
        service: GenerationService,
        client: OkHttpClient,
        timeoutMs: Long = 20_000L,
    ): Result = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(service.resolvedBaseUrl + "/models")
            .auth(service.apiKey)
            .get()
            .build()
        try {
            client.newCall(request).apply {
                timeout().timeout(timeoutMs.coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
            }.execute().use { response ->
                val body = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                when {
                    response.code == 404 || response.code == 405 -> Result.ReachableWithoutModels
                    !response.isSuccessful ->
                        Result.Failed("HTTP ${response.code}: ${body.trim().take(200)}")
                    else -> Result.Ok(countModels(body))
                }
            }
        } catch (e: IOException) {
            Result.Failed(e.message ?: e.toString())
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.toString())
        }
    }

    /** `{"data":[…]}`（OpenAI 形状）或裸数组都数；解析不了就当 0。 */
    internal fun countModels(body: String): Int {
        val root = runCatching { GenerationHttp.json.parseToJsonElement(body) }.getOrNull() ?: return 0
        val array = when (root) {
            is JsonArray -> root
            is kotlinx.serialization.json.JsonObject -> root["data"] as? JsonArray
            else -> null
        }
        return array?.size ?: 0
    }
}

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
 * 顺便数一下模型数量。
 *
 * **三类服务三种待遇**（这是 2026-09-17 用户报「视频、图片显示连接失败」的根因）：
 * 生成类中转很多**根本不提供 `/models`**，回 404/405 —— 服务明明能用，却被两态
 * （成功/失败）判成「连接失败」。所以 404/405 归 [Result.ReachableWithoutModels]，
 * 落库时记 [com.psyche.memo.data.model.GenerationTestState.REACHABLE]（可达），
 * **不再标红**。失败文案一律带上探测的 URL，方便对着地址排查。
 */
object GenerationServiceTester {

    sealed interface Result {
        /** 落库用的三态（[com.psyche.memo.data.model.GenerationTestState]）。 */
        val state: String

        /** 通，且拿到了模型列表。 */
        data class Ok(val modelCount: Int) : Result {
            override val state: String get() = com.psyche.memo.data.model.GenerationTestState.OK
        }

        /** 通，但这个端点没有 `/models`（生成类中转的常态）——**不是失败**。 */
        data object ReachableWithoutModels : Result {
            override val state: String get() = com.psyche.memo.data.model.GenerationTestState.REACHABLE
        }

        data class Failed(val message: String) : Result {
            override val state: String get() = com.psyche.memo.data.model.GenerationTestState.FAILED
        }
    }

    suspend fun test(
        service: GenerationService,
        client: OkHttpClient,
        timeoutMs: Long = 20_000L,
    ): Result = withContext(Dispatchers.IO) {
        val url = service.resolvedBaseUrl + "/models"
        val request = Request.Builder()
            .url(url)
            .auth(service.apiKey)
            .get()
            .build()
        try {
            client.newCall(request).apply {
                timeout().timeout(timeoutMs.coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
            }.execute().use { response ->
                val body = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                when {
                    // 404/405 = 这个中转没有 /models（或不允许 GET），不代表服务不可用。
                    response.code == 404 || response.code == 405 -> Result.ReachableWithoutModels
                    // key 类错误单独说清楚，不然用户只会看到一串 HTTP 码。
                    response.code == 401 || response.code == 403 ->
                        Result.Failed("HTTP ${response.code} (key 无效或没有权限) $url${bodySuffix(body)}")
                    !response.isSuccessful ->
                        Result.Failed("HTTP ${response.code} $url${bodySuffix(body)}")
                    else -> Result.Ok(countModels(body))
                }
            }
        } catch (e: IOException) {
            Result.Failed("${e.message ?: e.toString()} ($url)")
        } catch (e: Exception) {
            Result.Failed("${e.message ?: e.toString()} ($url)")
        }
    }

    private fun bodySuffix(body: String): String {
        val detail = body.trim().take(200)
        return if (detail.isEmpty()) "" else ": $detail"
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

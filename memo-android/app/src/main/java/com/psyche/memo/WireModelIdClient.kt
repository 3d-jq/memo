package com.psyche.memo

import com.psyche.memo.llm.client.LlmClient
import com.psyche.memo.llm.client.LlmModelInfo
import com.psyche.memo.llm.client.LlmRequest
import com.psyche.memo.llm.client.LlmTextResult
import com.psyche.memo.llm.stream.StreamChunk
import kotlinx.coroutines.flow.Flow

/**
 * 把请求里的**逻辑**模型 id 换成上游 id 再交给真正的客户端
 * （`chat_api_helpers.dart:44-53 apiModelId`）。
 *
 * 只在 id 真的变了时 `copy`，其余情况原样透传；`listModels` / `supports` 直接转发
 * （列表接口不带模型 id）。
 */
internal class WireModelIdClient(
    private val delegate: LlmClient,
    /** 该 provider 的 Claude 提示词缓存（开关 + 已归一的 '5m'/'1h'）。 */
    private val promptCaching: () -> Pair<Boolean, String> = { false to "5m" },
    private val wireModelId: (String) -> String,
) : LlmClient {

    private fun LlmRequest.mapped(): LlmRequest {
        val caching = promptCaching()
        val wire = wireModelId(modelId)
        val stamped = copy(
            claudePromptCaching = caching.first,
            claudePromptCachingTtl = caching.second,
        )
        return if (wire == modelId || wire.isEmpty()) stamped else stamped.copy(modelId = wire)
    }

    override fun supports(providerId: String): Boolean = delegate.supports(providerId)

    override fun streamChat(request: LlmRequest): Flow<StreamChunk> =
        delegate.streamChat(request.mapped())

    override fun completeAsChunks(request: LlmRequest): Flow<StreamChunk> =
        delegate.completeAsChunks(request.mapped())

    override suspend fun complete(request: LlmRequest): LlmTextResult =
        delegate.complete(request.mapped())

    override suspend fun listModels(baseUrl: String, apiKey: String): List<LlmModelInfo> =
        delegate.listModels(baseUrl, apiKey)
}

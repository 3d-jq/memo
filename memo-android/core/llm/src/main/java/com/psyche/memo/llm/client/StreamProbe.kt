package com.psyche.memo.llm.client

import com.psyche.memo.llm.stream.StreamChunk
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 流式自检 —— 等价于原版 `ProviderManager.testConnection(cfg, modelId,
 * useStream: true)`（model_provider.dart L497-503）：发一次真正的流式请求，只有
 * 服务端确实回了 SSE 数据才算通过（原版检查 `text/event-stream` 与空 body）。
 *
 * 拿到**第一块**就返回：`firstOrNull` 会取消剩下的流，不会陪模型把整个回复
 * 跑完（省钱、也快）。服务端一直不回块时按 [timeoutMs] 判失败。
 */
suspend fun LlmClient.probeStream(request: LlmRequest, timeoutMs: Long = 60_000L): Boolean =
    withTimeoutOrNull(timeoutMs) {
        streamChat(request).firstOrNull { it !is StreamChunk.Error && it !is StreamChunk.RetryPending }
    } != null

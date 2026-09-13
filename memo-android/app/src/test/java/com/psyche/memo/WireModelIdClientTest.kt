package com.psyche.memo

import com.psyche.memo.llm.client.LlmClient
import com.psyche.memo.llm.client.LlmMessage
import com.psyche.memo.llm.client.LlmModelInfo
import com.psyche.memo.llm.client.LlmRequest
import com.psyche.memo.llm.client.LlmTextResult
import com.psyche.memo.llm.stream.StreamChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `chat_api_helpers.dart:44-53 apiModelId` —— 出网请求必须带**上游**模型 id，
 * 而厂商启发式（Claude thinking / Gemini 判定 / GLM OCR）也看这个值。
 */
class WireModelIdClientTest {

    private class Recorder : LlmClient {
        var lastStream: LlmRequest? = null
        var lastNonStream: LlmRequest? = null
        var lastComplete: LlmRequest? = null
        var listModelsCalls = 0

        override fun supports(providerId: String): Boolean = providerId == "openai"
        override fun streamChat(request: LlmRequest): Flow<StreamChunk> {
            lastStream = request
            return emptyFlow()
        }

        override fun completeAsChunks(request: LlmRequest): Flow<StreamChunk> {
            lastNonStream = request
            return emptyFlow()
        }

        override suspend fun complete(request: LlmRequest): LlmTextResult {
            lastComplete = request
            return LlmTextResult(parts = emptyList(), usage = null, finishReason = null)
        }

        override suspend fun listModels(baseUrl: String, apiKey: String): List<LlmModelInfo> {
            listModelsCalls++
            return emptyList()
        }
    }

    private fun request(modelId: String) = LlmRequest(
        providerId = "openai",
        modelId = modelId,
        messages = listOf(LlmMessage(role = "user", content = "hi")),
        apiKey = "k",
        baseUrl = "https://example.test",
    )

    @Test
    fun streamRewritesTheLogicalIdToTheUpstreamId() {
        val delegate = Recorder()
        val client = WireModelIdClient(delegate) { "upstream-42" }
        client.streamChat(request("my-alias"))
        assertEquals("upstream-42", delegate.lastStream?.modelId)
        // 其它字段原样保留。
        assertEquals("openai", delegate.lastStream?.providerId)
        assertEquals("k", delegate.lastStream?.apiKey)
    }

    @Test
    fun completeIsRewrittenToo() = runBlocking {
        val delegate = Recorder()
        val client = WireModelIdClient(delegate) { "upstream-42" }
        client.complete(request("my-alias"))
        assertEquals("upstream-42", delegate.lastComplete?.modelId)
    }

    @Test
    fun nonStreamChatIsRewrittenToo() {
        val delegate = Recorder()
        val client = WireModelIdClient(delegate) { "upstream-42" }
        client.completeAsChunks(request("my-alias"))
        assertEquals("upstream-42", delegate.lastNonStream?.modelId)
    }

    @Test
    fun identicalOrEmptyMappingKeepsTheLogicalId() {
        val delegate = Recorder()
        WireModelIdClient(delegate) { "same" }.streamChat(request("same"))
        assertEquals("same", delegate.lastStream?.modelId)

        WireModelIdClient(delegate) { "" }.streamChat(request("my-alias"))
        assertEquals("my-alias", delegate.lastStream?.modelId)
    }

    @Test
    fun listModelsAndSupportsDelegate() = runBlocking {
        val delegate = Recorder()
        val client = WireModelIdClient(delegate) { "x" }
        assertTrue(client.supports("openai"))
        client.listModels("https://example.test", "k")
        assertEquals(1, delegate.listModelsCalls)
    }
}

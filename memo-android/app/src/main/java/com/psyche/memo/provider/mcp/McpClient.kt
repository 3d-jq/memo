package com.psyche.memo.provider.mcp

import com.psyche.memo.data.model.McpServerConfig
import com.psyche.memo.llm.stream.SseEventParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit

class McpException(message: String) : Exception(message)

/** One tool advertised by the server (tools/list). */
data class McpRemoteTool(
    val name: String,
    val description: String?,
    val inputSchema: JsonObject?,
)

/**
 * Minimal MCP client — JSON-RPC 2.0 over the Streamable HTTP and SSE
 * transports (spec 2025-03-26+): initialize handshake, `mcp-session-id`
 * capture, `MCP-Protocol-Version` on post-handshake requests, tools/list and
 * tools/call. STDIO is desktop-only upstream and stays out.
 */
class McpClient(
    private val server: McpServerConfig,
    private val http: OkHttpClient,
    /** `mcp_request_timeout_ms_v1`，由调用方注入；缺省 60s（上游默认）。 */
    private val requestTimeoutMs: Long = DEFAULT_RESPONSE_TIMEOUT_MS,
) : Closeable {

    private val json = Json { ignoreUnknownKeys = true }
    private var sessionId: String? = null
    private var negotiatedVersion: String? = null
    private var nextId = 1

    /** SSE transport: the POST endpoint advertised by the `endpoint` event. */
    private var sseEndpoint: String? = null
    private var sseReader: Thread? = null
    private val sseMessages = java.util.concurrent.LinkedBlockingQueue<JsonObject>()
    @Volatile private var sseClosed = false

    suspend fun initialize() = withContext(Dispatchers.IO) {
        val params = buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", buildJsonObject {})
            put("clientInfo", buildJsonObject {
                put("name", "memo")
                put("version", "1.0.0")
            })
        }
        val result = rpc("initialize", params)
        negotiatedVersion = result.string("protocolVersion")
        notify("notifications/initialized", buildJsonObject {})
    }

    suspend fun listTools(): List<McpRemoteTool> = withContext(Dispatchers.IO) {
        val result = rpc("tools/list", buildJsonObject {})
        val tools = result["tools"] as? JsonArray ?: return@withContext emptyList()
        tools.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            McpRemoteTool(
                name = obj.string("name") ?: return@mapNotNull null,
                description = obj.string("description"),
                inputSchema = obj["inputSchema"] as? JsonObject,
            )
        }
    }

    /**
     * tools/call → 服务端返回的 result 对象原样交给调用方。
     *
     * content 数组的展开（文本 / 图片 / 资源 / 音频）在 `flattenMcpToolResult`，
     * 那里才需要落盘图片的目录；`isError` 也在那里按上游语义**不**当异常抛。
     */
    suspend fun callTool(name: String, arguments: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        rpc(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            },
        )
    }

    override fun close() {
        sseClosed = true
        sseReader?.interrupt()
        sessionId?.let { id ->
            runCatching {
                http.newCall(
                    Request.Builder()
                        .url(server.url)
                        .delete()
                        .header("mcp-session-id", id)
                        .build(),
                ).execute().close()
            }
        }
    }

    // ------------------------------------------------------------------- rpc

    private fun rpc(method: String, params: JsonObject): JsonObject {
        val id = synchronized(this) { nextId++ }
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val response = when (server.transport) {
            "sse" -> postSse(payload, id)
            else -> postHttp(payload, id, retryOnExpiredSession = true)
        }
        val error = response["error"] as? JsonObject
        if (error != null) {
            throw McpException(error.string("message") ?: "MCP error")
        }
        return response["result"] as? JsonObject ?: JsonObject(emptyMap())
    }

    private fun notify(method: String, params: JsonObject) {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }
        runCatching {
            when (server.transport) {
                "sse" -> postSse(payload, null)
                else -> postHttp(payload, null, retryOnExpiredSession = false)
            }
        }
    }

    private fun baseRequest(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        for ((key, value) in server.headers) builder.header(key, value)
        negotiatedVersion?.takeIf { it >= "2025-06-18" }?.let { builder.header("MCP-Protocol-Version", it) }
        sessionId?.let { builder.header("mcp-session-id", it) }
        return builder
    }

    /** Streamable HTTP: JSON or SSE response, session id from the header. */
    private fun postHttp(payload: JsonObject, expectId: Int?, retryOnExpiredSession: Boolean): JsonObject {
        val request = baseRequest(server.url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        val response = http.newCall(request).execute()
        response.use { res ->
            res.header("mcp-session-id")?.takeIf { it.isNotEmpty() }?.let { sessionId = it }
            if (res.code == 404 && sessionId != null && retryOnExpiredSession) {
                // Session expired: re-initialize once, like the upstream transport.
                sessionId = null
                initializeBlocking()
                return postHttp(payload, expectId, retryOnExpiredSession = false)
            }
            if (!res.isSuccessful) {
                throw McpException("HTTP ${res.code}: ${res.body?.string().orEmpty().take(200)}")
            }
            if (expectId == null) return JsonObject(emptyMap())
            val contentType = res.header("Content-Type").orEmpty()
            if (contentType.contains("text/event-stream")) {
                return readSseResponse(res, expectId)
            }
            val text = res.body?.string().orEmpty()
            return runCatching { json.parseToJsonElement(text).jsonObject }
                .getOrElse { throw McpException("Invalid MCP response: ${text.take(200)}") }
        }
    }

    private fun readSseResponse(response: Response, expectId: Int): JsonObject {
        val source = response.body?.source() ?: throw McpException("MCP response has no body")
        val parser = SseEventParser()
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            for (event in parser.add(line + "\n")) {
                val obj = parseEventPayload(event.data) ?: continue
                if ((obj["id"] as? JsonPrimitive)?.content?.toIntOrNull() == expectId) return obj
            }
        }
        for (event in parser.close()) {
            val obj = parseEventPayload(event.data) ?: continue
            if ((obj["id"] as? JsonPrimitive)?.content?.toIntOrNull() == expectId) return obj
        }
        throw McpException("MCP stream ended before the response arrived")
    }

    // -------------------------------------------------------------------- SSE

    private fun ensureSseConnected() {
        if (sseEndpoint != null) return
        val parser = SseEventParser()
        val endpointReady = java.util.concurrent.CountDownLatch(1)
        val request = baseRequest(server.url)
            .header("Accept", "text/event-stream")
            .get()
            .build()
        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw McpException("HTTP ${response.code} opening the SSE endpoint")
        }
        sseReader = Thread {
            try {
                val source = response.body?.source() ?: return@Thread
                while (!sseClosed && !source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    for (event in parser.add(line + "\n")) {
                        when (event.event) {
                            "endpoint" -> {
                                sseEndpoint = resolveUrl(server.url, event.data.trim())
                                endpointReady.countDown()
                            }
                            "message" -> parseEventPayload(event.data)?.let { sseMessages.offer(it) }
                        }
                    }
                }
            } catch (_: Exception) {
                // The stream closed; callers surface the failure.
            } finally {
                response.close()
                endpointReady.countDown()
            }
        }.apply {
            isDaemon = true
            start()
        }
        if (!endpointReady.await(SSE_ENDPOINT_TIMEOUT_SECONDS, TimeUnit.SECONDS) || sseEndpoint == null) {
            throw McpException("SSE endpoint was not announced")
        }
    }

    /** Legacy SSE transport: POST to the announced endpoint, read on the stream. */
    private fun postSse(payload: JsonObject, expectId: Int?): JsonObject {
        ensureSseConnected()
        val endpoint = sseEndpoint ?: throw McpException("SSE endpoint unavailable")
        val request = baseRequest(endpoint)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { res ->
            if (!res.isSuccessful && res.code != 202) {
                throw McpException("HTTP ${res.code} posting to the SSE endpoint")
            }
        }
        if (expectId == null) return JsonObject(emptyMap())
        val deadline = System.currentTimeMillis() + requestTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            val message = sseMessages.poll(500, TimeUnit.MILLISECONDS) ?: continue
            if ((message["id"] as? JsonPrimitive)?.content?.toIntOrNull() == expectId) return message
        }
        throw McpException("Timed out waiting for the SSE response")
    }

    private fun initializeBlocking() {
        val params = buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", buildJsonObject {})
            put("clientInfo", buildJsonObject {
                put("name", "memo")
                put("version", "1.0.0")
            })
        }
        val id = synchronized(this) { nextId++ }
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "initialize")
            put("params", params)
        }
        val result = postHttp(payload, id, retryOnExpiredSession = false)
        negotiatedVersion = (result["result"] as? JsonObject)?.string("protocolVersion")
        notify("notifications/initialized", buildJsonObject {})
    }

    private fun parseEventPayload(data: String): JsonObject? =
        runCatching { json.parseToJsonElement(data.trim()).jsonObject }.getOrNull()

    private fun resolveUrl(base: String, endpoint: String): String =
        runCatching { base.toHttpUrl().resolve(endpoint)?.toString() }
            .getOrNull() ?: endpoint

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

    companion object {
        const val PROTOCOL_VERSION = "2025-11-25"
        private const val SSE_ENDPOINT_TIMEOUT_SECONDS = 15L

        /** 上游 `mcp_client.dart` 的默认响应超时；`mcp_request_timeout_ms_v1` 覆盖它。 */
        const val DEFAULT_RESPONSE_TIMEOUT_MS = 60_000L
        private val JSON = "application/json".toMediaType()
    }
}

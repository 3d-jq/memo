package com.psyche.memo.provider.mcp

import com.psyche.memo.data.model.McpServerConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Protocol coverage for the MCP client (Streamable HTTP + SSE transports). */
class McpClientTest {

    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun config(transport: String = "http") = McpServerConfig(
        id = "s1",
        name = "Test",
        transport = transport,
        url = server.url("/mcp").toString(),
    )

    private fun jsonRpcResponse(id: Int, result: String) =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"jsonrpc":"2.0","id":$id,"result":$result}""")

    @Test
    fun `initialize captures the session id and negotiates the version`() = runBlocking {
        server.enqueue(
            jsonRpcResponse(1, """{"protocolVersion":"2025-06-18","capabilities":{}}""")
                .setHeader("mcp-session-id", "abc123"),
        )
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(
            jsonRpcResponse(
                2,
                """{"tools":[{"name":"echo","description":"Echo","inputSchema":{"type":"object"}}]}""",
            ),
        )

        val client = McpClient(config(), http)
        client.initialize()
        val tools = client.listTools()

        assertEquals(listOf("echo"), tools.map { it.name })
        assertEquals("Echo", tools[0].description)

        // initialize request
        val init = server.takeRequest()
        assertEquals("/mcp", init.path)
        assertTrue(init.body.readUtf8().contains("\"method\":\"initialize\""))
        assertTrue(init.getHeader("Accept").orEmpty().contains("text/event-stream"))

        // notifications/initialized
        server.takeRequest()

        // tools/list carries the session + negotiated protocol version.
        val list = server.takeRequest()
        assertEquals("abc123", list.getHeader("mcp-session-id"))
        assertEquals("2025-06-18", list.getHeader("MCP-Protocol-Version"))
    }

    @Test
    fun `tools call joins text content and surfaces isError`() = runBlocking {
        server.enqueue(
            jsonRpcResponse(1, """{"protocolVersion":"2025-11-25"}""")
                .setHeader("mcp-session-id", "s"),
        )
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(
            jsonRpcResponse(2, """{"content":[{"type":"text","text":"hello"},{"type":"text","text":"world"}]}"""),
        )
        val client = McpClient(config(), http)
        client.initialize()
        // callTool 只回 result；content 的展开在 flattenMcpToolResult（图片要落盘才需要 filesDir）。
        assertEquals(
            "hello\nworld",
            flattenMcpToolResult(client.callTool("echo", buildJsonObject { put("q", "x") })) { _, _ -> null },
        )
        server.takeRequest(); server.takeRequest()
        val call = server.takeRequest()
        assertTrue(call.body.readUtf8().contains("\"method\":\"tools/call\""))

        // isError 不是异常：上游把错误文本当普通工具结果发给模型（mcp_tool_service.dart:248）。
        server.enqueue(
            jsonRpcResponse(3, """{"content":[{"type":"text","text":"boom"}],"isError":true}"""),
        )
        val outcome = runCatching { client.callTool("echo", buildJsonObject {}) }
        assertTrue("工具结果不该抛", outcome.isSuccess)
        assertEquals("boom", flattenMcpToolResult(outcome.getOrThrow()) { _, _ -> null })
    }

    @Test
    fun `sse response body is parsed from the event stream`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-11-25\"}}\n\n")
                .setHeader("mcp-session-id", "sse-session"),
        )
        server.enqueue(MockResponse().setResponseCode(202))
        val client = McpClient(config(), http)
        client.initialize()
        // The SSE body carried the handshake result, so the initialized
        // notification must carry the negotiated protocol version.
        server.takeRequest()
        val notification = server.takeRequest()
        assertTrue(notification.body.readUtf8().contains("notifications/initialized"))
        assertEquals("2025-11-25", notification.getHeader("MCP-Protocol-Version"))
    }

    @Test
    fun `expired session retries after re-initializing`() = runBlocking {
        // initialize
        server.enqueue(
            jsonRpcResponse(1, """{"protocolVersion":"2025-11-25"}""")
                .setHeader("mcp-session-id", "old"),
        )
        server.enqueue(MockResponse().setResponseCode(202))
        // tools/list → 404 (session expired)
        server.enqueue(MockResponse().setResponseCode(404))
        // re-initialize
        server.enqueue(
            jsonRpcResponse(2, """{"protocolVersion":"2025-11-25"}""")
                .setHeader("mcp-session-id", "new"),
        )
        server.enqueue(MockResponse().setResponseCode(202))
        // retried tools/list succeeds
        server.enqueue(jsonRpcResponse(3, """{"tools":[]}"""))

        val client = McpClient(config(), http)
        client.initialize()
        assertEquals(emptyList<McpRemoteTool>(), client.listTools())
        server.takeRequest(); server.takeRequest(); server.takeRequest(); server.takeRequest()
        val retried = server.takeRequest()
        assertEquals("new", retried.getHeader("mcp-session-id"))
    }

    @Test
    fun `json rpc errors become mcp exceptions`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found"}}"""),
        )
        val client = McpClient(config(), http)
        val error = runCatching { client.initialize() }.exceptionOrNull()
        assertTrue(error is McpException)
        assertEquals("Method not found", error!!.message)
    }

    @Test
    fun `custom headers are sent on every request`() = runBlocking {
        server.enqueue(
            jsonRpcResponse(1, """{"protocolVersion":"2025-11-25"}""")
                .setHeader("mcp-session-id", "s"),
        )
        server.enqueue(MockResponse().setResponseCode(202))
        val client = McpClient(
            config().copy(headers = mapOf("X-Api-Key" to "secret")),
            http,
        )
        client.initialize()
        val request = server.takeRequest()
        assertEquals("secret", request.getHeader("X-Api-Key"))
    }
}

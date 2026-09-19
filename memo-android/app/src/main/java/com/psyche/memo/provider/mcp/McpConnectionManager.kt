package com.psyche.memo.provider.mcp

import com.psyche.memo.data.model.McpServerConfig
import com.psyche.memo.data.repo.McpRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime MCP connections — port of McpProvider's connection/status surface
 * (mcp_provider.dart): one client per server, connect runs the handshake and
 * refreshes tools/list, failures surface as an error status + message.
 *
 * OAuth authorization states are not ported; a server that requires OAuth
 * fails the handshake and reports the transport error.
 */
class McpConnectionManager(
    private val repository: McpRepository,
    private val http: OkHttpClient,
) {

    enum class Status { idle, connecting, connected, error }

    data class ConnectionState(
        val status: Status = Status.idle,
        val tools: List<McpRemoteTool> = emptyList(),
        val error: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = ConcurrentHashMap<String, McpClient>()
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _states = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    val states: StateFlow<Map<String, ConnectionState>> = _states

    fun servers(): List<McpServerConfig> = repository.servers()

    fun statusFor(serverId: String): Status = _states.value[serverId]?.status ?: Status.idle

    fun errorFor(serverId: String): String? = _states.value[serverId]?.error

    fun toolsFor(serverId: String): List<McpRemoteTool> = _states.value[serverId]?.tools ?: emptyList()

    fun isConnected(serverId: String): Boolean = statusFor(serverId) == Status.connected

    fun connect(server: McpServerConfig) {
        if (!server.isHttpLike) return
        jobs[server.id]?.cancel()
        jobs[server.id] = scope.launch {
            update(server.id) { it.copy(status = Status.connecting, error = null) }
            runCatching {
                clients.remove(server.id)?.close()
                // MCP-3 OAuth：令牌快照存 server.oauth；到期先刷再连，有效则附
                // Authorization 头（`mcp_client.dart` 的 OAuth HTTP 客户端等价）。
                val effective = withOAuth(refreshIfNeeded(server))
                val client = McpClient(effective, http)
                client.initialize()
                val tools = client.listTools()
                clients[server.id] = client
                tools
            }.onSuccess { tools ->
                update(server.id) { it.copy(status = Status.connected, tools = tools, error = null) }
            }.onFailure { error ->
                update(server.id) {
                    it.copy(status = Status.error, tools = emptyList(), error = error.message ?: error.toString())
                }
            }
        }
    }

    /**
     * MCP-3：发起 OAuth 授权（系统浏览器 + 本地回环回调），成功后把令牌写进
     * `server.oauth` 并重连。
     */
    suspend fun authorizeOAuth(
        server: McpServerConfig,
        context: android.content.Context,
    ): Result<McpOAuthState> = runCatching {
        val state = McpOAuthService.authorize(
            serverUrl = server.url,
            serverName = server.name,
            client = http,
            headers = server.headers,
            launchAuthorizationUrl = { uri ->
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW)
                            .setData(android.net.Uri.parse(uri.toString()))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.isSuccess
            },
        )
        val updated = server.copy(oauth = state.toJson())
        repository.save(updated)
        connect(updated)
        state
    }

    /** 令牌过期（含 1 分钟余量）时先刷新；失败保持旧值让连接报错。 */
    private suspend fun refreshIfNeeded(server: McpServerConfig): McpServerConfig {
        val state = McpOAuthState.tryFromJson(server.oauth?.toString()) ?: return server
        if (!state.shouldRefresh()) return server
        return runCatching {
            val next = McpOAuthService.refresh(state, http)
            val updated = server.copy(oauth = next.toJson())
            repository.save(updated)
            updated
        }.getOrElse { server }
    }

    private fun withOAuth(server: McpServerConfig): McpServerConfig {
        val state = McpOAuthState.tryFromJson(server.oauth?.toString()) ?: return server
        if (state.shouldRefresh()) return server
        return server.copy(headers = server.headers + ("Authorization" to state.authorizationHeader))
    }

    /** Connects every enabled server (McpProvider.initConnectedServers). */
    fun connectEnabled() {
        for (server in repository.enabledServers()) {
            if (!isConnected(server.id) && statusFor(server.id) != Status.connecting) connect(server)
        }
    }

    fun reconnect(serverId: String) {
        repository.server(serverId)?.let { connect(it) }
    }

    fun disconnect(serverId: String) {
        jobs[serverId]?.cancel()
        clients.remove(serverId)?.close()
        update(serverId) { ConnectionState() }
    }

    /** tools/call on a connected server; throws when not connected. */
    suspend fun callTool(serverId: String, toolName: String, arguments: kotlinx.serialization.json.JsonObject): String {
        val client = clients[serverId] ?: throw McpException("MCP server is not connected")
        return client.callTool(toolName, arguments)
    }

    fun shutdown() {
        jobs.values.forEach { it.cancel() }
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
    }

    private fun update(serverId: String, transform: (ConnectionState) -> ConnectionState) {
        _states.value = _states.value.toMutableMap().apply {
            put(serverId, transform(this[serverId] ?: ConnectionState()))
        }
    }
}

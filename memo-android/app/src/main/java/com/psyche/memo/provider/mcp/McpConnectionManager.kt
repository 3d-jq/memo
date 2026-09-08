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
                val client = McpClient(server, http)
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

package com.psyche.memo.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * MCP server DTOs — mirror Flutter mcp_provider.dart's McpServerConfig /
 * McpToolConfig / McpParamSpec JSON shapes (stored in mcp_server_rows.payload).
 * OAuth blobs are kept as raw JSON: the OAuth flow itself is not ported yet.
 */
@Serializable
data class McpParamSpec(
    val name: String = "",
    val required: Boolean = false,
    val type: String? = null,
    @SerialName("default") val defaultValue: JsonElement? = null,
)

@Serializable
data class McpToolConfig(
    val enabled: Boolean = true,
    val name: String = "",
    val description: String? = null,
    val params: List<McpParamSpec> = emptyList(),
    val schema: JsonObject? = null,
    val needsApproval: Boolean = false,
)

@Serializable
data class McpServerConfig(
    val id: String = "",
    val enabled: Boolean = true,
    val name: String = "",
    /** sse | http | stdio | inmemory (upstream enum names). */
    val transport: String = "sse",
    val url: String = "",
    val tools: List<McpToolConfig> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val oauth: JsonElement? = null,
    val oauthClient: JsonElement? = null,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val workingDirectory: String? = null,
) {
    val isHttpLike: Boolean get() = transport == "http" || transport == "sse"

    fun toolByName(name: String): McpToolConfig? = tools.firstOrNull { it.name == name }
}

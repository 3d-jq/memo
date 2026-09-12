package com.psyche.memo.provider

import com.psyche.memo.data.model.ProviderConfig
import com.psyche.memo.data.repo.ApiKeyManager
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.PasswordAuthentication
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Port of lib/core/services/provider_balance_service.dart: a GET against the
 * provider's balance endpoint whose JSON answer is read through a dot/bracket
 * result path (with optional `a - b` subtraction), formatted to 2 decimals.
 *
 * Upstream also injects OpenRouter referer/title headers here; those carry
 * upstream branding and are intentionally not carried over (port rules).
 */
object ProviderBalanceService {

    class BalanceException(message: String) : Exception(message)

    fun fetchBalance(config: ProviderConfig, client: OkHttpClient): String {
        if (config.classifiedKind() != "openai") {
            throw BalanceException("Balance is only supported for OpenAI-compatible providers")
        }
        if (config.balanceEnabled != true) {
            throw BalanceException("Balance query is disabled")
        }

        val apiPath = (config.balanceApiPath ?: "/credits").trim()
        val resultPath = (config.balanceResultPath ?: "data.total_usage").trim()
        val url = balanceUri(config.baseUrl, apiPath)

        val apiKey = effectiveApiKey(config)
        val builder = Request.Builder().url(url).get()
        if (apiKey.isNotEmpty()) builder.header("Authorization", "Bearer $apiKey")
        config.customHeaders.forEach { h ->
            val name = h["name"]?.trim().orEmpty()
            val value = h["value"].orEmpty()
            if (name.isNotEmpty()) builder.header(name, value)
        }

        val http = clientFor(config, client)
        http.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code < 200 || response.code >= 300) {
                throw BalanceException("HTTP ${response.code}: $body")
            }
            val decoded = try {
                Json.parseToJsonElement(body)
            } catch (e: Exception) {
                throw BalanceException("Invalid balance response JSON: ${e.message}")
            }
            return BalanceValueParser.format(decoded, resultPath)
        }
    }

    /** _effectiveApiKey: multi-key selection through the strategy manager. */
    fun effectiveApiKey(config: ProviderConfig): String {
        if (config.multiKeyEnabled == true && !config.apiKeys.isNullOrEmpty()) {
            return ApiKeyManager.selectForProvider(config).key?.key ?: config.apiKey
        }
        return config.apiKey
    }

    /** _balanceUri: absolute apiPath wins; otherwise base (no trailing /) + path. */
    fun balanceUri(baseUrl: String, apiPath: String): String {
        if (apiPath.isEmpty()) throw BalanceException("Balance API path is empty")
        val absolute = runCatching { java.net.URI(apiPath) }.getOrNull()
        if (absolute != null && absolute.scheme != null) return apiPath
        val base = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
        val path = if (apiPath.startsWith("/")) apiPath else "/$apiPath"
        return "$base$path"
    }

    /** _clientFor: honor the provider proxy (http/socks) when configured. */
    private fun clientFor(config: ProviderConfig, base: OkHttpClient): OkHttpClient {
        val enabled = config.proxyEnabled == true
        val host = (config.proxyHost ?: "").trim()
        val portStr = (config.proxyPort ?: "").trim()
        if (!enabled || host.isEmpty() || portStr.isEmpty()) return base
        val port = portStr.toIntOrNull() ?: 8080
        val type = if ((config.proxyType ?: "").contains("sock", ignoreCase = true)) {
            java.net.Proxy.Type.SOCKS
        } else {
            java.net.Proxy.Type.HTTP
        }
        val builder = base.newBuilder()
            .proxy(java.net.Proxy(type, java.net.InetSocketAddress(host, port)))
        val user = (config.proxyUsername ?: "").trim()
        val pass = (config.proxyPassword ?: "").trim()
        if (user.isNotEmpty()) {
            if (type == java.net.Proxy.Type.SOCKS) {
                java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication =
                        PasswordAuthentication(user, pass.toCharArray())
                })
            } else {
                val credential = okhttp3.Credentials.basic(user, pass)
                builder.proxyAuthenticator { _, response ->
                    response.request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }
            }
        }
        return builder.build()
    }
}

/**
 * Port of ProviderBalanceValueParser — reads `a.b[0].c` style paths out of a
 * decoded JSON tree; `\s-\s` splits the expression into a subtraction.
 */
object BalanceValueParser {

    fun format(json: JsonElement, expression: String): String {
        val expr = expression.trim()
        if (expr.isEmpty()) throw ProviderBalanceService.BalanceException("Balance result path is empty")

        val minus = Regex("\\s-\\s").find(expr)
        if (minus != null) {
            val left = readNumber(json, expr.substring(0, minus.range.first))
            val right = readNumber(json, expr.substring(minus.range.last + 1))
            return formatValue(left - right)
        }
        return formatValue(readPath(json, expr))
    }

    private fun readNumber(json: JsonElement, path: String): Double {
        val value = readPath(json, path)
        val parsed = (value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
            ?: (value as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        if (parsed == null) {
            throw ProviderBalanceService.BalanceException(
                "Balance value at \"${path.trim()}\" is not numeric",
            )
        }
        return parsed
    }

    private fun readPath(json: JsonElement, path: String): JsonElement {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) {
            throw ProviderBalanceService.BalanceException("Balance result path is empty")
        }
        var current: JsonElement = json
        for (part in trimmed.split(".")) {
            if (part.isBlank()) {
                throw ProviderBalanceService.BalanceException("Invalid balance result path: $path")
            }
            current = readPart(current, part.trim(), path)
        }
        return current
    }

    private fun readPart(current: JsonElement, part: String, fullPath: String): JsonElement {
        val match = Regex("^([^\\[\\]]+)((?:\\[\\d+\\])*)$").find(part)
            ?: throw ProviderBalanceService.BalanceException("Invalid balance result path: $fullPath")
        val key = match.groupValues[1]
        if (current !is JsonObject || !current.containsKey(key)) {
            throw ProviderBalanceService.BalanceException("Balance path not found: $fullPath")
        }
        var node: JsonElement = current[key]!!

        val indexes = Regex("\\[(\\d+)\\]").findAll(match.groupValues[2])
        for (indexMatch in indexes) {
            val index = indexMatch.groupValues[1].toInt()
            if (node !is JsonArray || index < 0 || index >= node.size) {
                throw ProviderBalanceService.BalanceException("Balance path not found: $fullPath")
            }
            node = node[index]
        }
        return node
    }

    private fun formatValue(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)

    private fun formatValue(element: JsonElement): String {
        if (element is JsonNull) return "null"
        val primitive = element as? JsonPrimitive ?: return element.toString()
        if (!primitive.isString) {
            primitive.content.toDoubleOrNull()?.let { return formatValue(it) }
        }
        primitive.content.toDoubleOrNull()?.let { return formatValue(it) }
        return primitive.content
    }
}

package com.psyche.memo.provider.mcp

import com.psyche.memo.data.settings.PreferenceRepository
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * MCP OAuth（MCP-3）—— 1:1 port of `mcp_oauth_service.dart` +
 * `mcp_oauth_callback*.dart`：RFC 9728 受保护资源元数据发现 → RFC 8414 授权服务器
 * 元数据 → 动态客户端注册（DCR）→ PKCE(S256) 授权（系统浏览器 + 本地回环回调）→
 * 令牌交换 / 刷新。安全边界照抄上游：
 *
 *  - 发现 / 令牌端点必须 HTTPS 且**不得**是本地 / 私有地址（防 SSRF；回环仅当
 *    服务器本身是回环时放行）。
 *  - 对已发现的目标在请求前做 DNS 全地址解析校验（`validateMcpOAuthPublicUri`）。
 *  - 回调地址固定 127.0.0.1 一次性本地 HTTP 服务器，5 分钟超时。
 */

enum class McpOAuthFailureKind { authorizationRequired, transient, invalidResponse }

enum class McpOAuthClientRegistrationSource { preRegistered, cimd, dcr }

class McpOAuthException(
    message: String,
    val kind: McpOAuthFailureKind = McpOAuthFailureKind.invalidResponse,
    val statusCode: Int? = null,
    val oauthError: String? = null,
) : Exception(message)

data class McpOAuthClientRegistration(
    val clientId: String,
    val clientSecret: String? = null,
    val tokenEndpointAuthMethod: String = "none",
    val authorizationServer: String? = null,
    val redirectUri: String? = null,
    val registrationSource: McpOAuthClientRegistrationSource =
        McpOAuthClientRegistrationSource.preRegistered,
)

data class McpOAuthDiscovery(
    val authorizationServer: URI,
    val authorizationEndpoint: URI,
    val tokenEndpoint: URI,
    val registrationEndpoint: URI?,
    val resource: URI,
    val scopes: List<String>,
    val authorizationResponseIssParameterSupported: Boolean,
    val clientIdMetadataDocumentSupported: Boolean,
)

data class McpOAuthState(
    val clientId: String,
    val clientSecret: String? = null,
    val authorizationServer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val registrationEndpoint: String? = null,
    val serverUrl: String? = null,
    val resource: String,
    val scope: String? = null,
    val tokenEndpointAuthMethod: String = "none",
    val registrationSource: McpOAuthClientRegistrationSource = McpOAuthClientRegistrationSource.dcr,
    val redirectUri: String? = null,
    val accessToken: String,
    val tokenType: String = "Bearer",
    val refreshToken: String? = null,
    val expiresAt: Long? = null,
) {
    val authorizationHeader: String
        get() = "${if (tokenType.lowercase() == "bearer") "Bearer" else tokenType} $accessToken"

    fun shouldRefresh(leewayMs: Long = 60_000): Boolean =
        expiresAt != null && System.currentTimeMillis() + leewayMs >= expiresAt &&
            !refreshToken.isNullOrEmpty()

    fun toJson(): JsonObject = buildJsonObject {
        put("clientId", clientId)
        clientSecret?.let { put("clientSecret", it) }
        put("authorizationServer", authorizationServer)
        put("authorizationEndpoint", authorizationEndpoint)
        put("tokenEndpoint", tokenEndpoint)
        registrationEndpoint?.let { put("registrationEndpoint", it) }
        serverUrl?.let { put("serverUrl", it) }
        put("resource", resource)
        scope?.let { put("scope", it) }
        put("tokenEndpointAuthMethod", tokenEndpointAuthMethod)
        put("registrationSource", registrationSource.name)
        redirectUri?.let { put("redirectUri", it) }
        put("accessToken", accessToken)
        put("tokenType", tokenType)
        refreshToken?.let { put("refreshToken", it) }
        expiresAt?.let { put("expiresAt", it) }
    }

    companion object {
        private val JSON = Json { isLenient = true; ignoreUnknownKeys = true }

        fun tryFromJson(raw: String?): McpOAuthState? = try {
            val obj = JSON.parseToJsonElement(raw.orEmpty()).jsonObject
            McpOAuthState(
                clientId = obj.getValue("clientId").jsonPrimitive.content,
                clientSecret = obj["clientSecret"]?.jsonPrimitive?.contentOrNull,
                authorizationServer = obj.getValue("authorizationServer").jsonPrimitive.content,
                authorizationEndpoint = obj.getValue("authorizationEndpoint").jsonPrimitive.content,
                tokenEndpoint = obj.getValue("tokenEndpoint").jsonPrimitive.content,
                registrationEndpoint = obj["registrationEndpoint"]?.jsonPrimitive?.contentOrNull,
                serverUrl = obj["serverUrl"]?.jsonPrimitive?.contentOrNull,
                resource = obj.getValue("resource").jsonPrimitive.content,
                scope = obj["scope"]?.jsonPrimitive?.contentOrNull,
                tokenEndpointAuthMethod = obj["tokenEndpointAuthMethod"]?.jsonPrimitive?.contentOrNull ?: "none",
                registrationSource = obj["registrationSource"]?.jsonPrimitive?.contentOrNull
                    ?.let { runCatching { McpOAuthClientRegistrationSource.valueOf(it) }.getOrNull() }
                    ?: McpOAuthClientRegistrationSource.dcr,
                redirectUri = obj["redirectUri"]?.jsonPrimitive?.contentOrNull,
                accessToken = obj.getValue("accessToken").jsonPrimitive.content,
                tokenType = obj["tokenType"]?.jsonPrimitive?.contentOrNull ?: "Bearer",
                refreshToken = obj["refreshToken"]?.jsonPrimitive?.contentOrNull,
                expiresAt = obj["expiresAt"]?.jsonPrimitive?.content?.toLongOrNull(),
            )
        } catch (_: Exception) {
            null
        }
    }
}

/** 已解析的 Bearer 挑战（RFC 6750），参数含带引号的值。 */
data class BearerChallenge(val parameters: Map<String, String>) {
    fun hasParameter(name: String): Boolean = parameters.containsKey(name.lowercase())
    fun parameter(name: String): String? = parameters[name.lowercase()]
}

object McpOAuthParsing {

    /** `_splitUnquotedSegments` + `_parseBearerChallenges`：按未被引号包裹的逗号切段。 */
    fun parseBearerChallenges(header: String): List<BearerChallenge> {
        val segments = splitUnquotedSegments(header) ?: return emptyList()
        val paramStrings = mutableListOf<String>()
        for (segment in segments) {
            val trimmed = segment.trim()
            val space = trimmed.indexOf(' ')
            // 新挑战 = 「scheme 参数」形态（首 token 后是空格且不带 =）；
            // 形如 key=value 的段是上一个 Bearer 挑战的延续参数。
            val firstToken = if (space < 0) trimmed else trimmed.substring(0, space)
            val startsChallenge = !firstToken.contains('=') && space > 0
            if (startsChallenge || paramStrings.isEmpty()) {
                paramStrings.add(trimmed)
            } else {
                paramStrings[paramStrings.lastIndex] = "${paramStrings.last()}, $trimmed"
            }
        }
        val out = mutableListOf<BearerChallenge>()
        for (paramString in paramStrings) {
            val parts = paramString.split(' ', limit = 2)
            if (parts.isEmpty()) continue
            if (parts[0].lowercase() != "bearer") continue
            val params = LinkedHashMap<String, String>()
            if (parts.size == 2) {
                val rest = parts[1].trim()
                var index = 0
                while (index < rest.length) {
                    val eq = rest.indexOf('=', index)
                    if (eq < 0) break
                    val name = rest.substring(index, eq).trim().lowercase()
                    var cursor = eq + 1
                    var value: String
                    if (cursor < rest.length && rest[cursor] == '"') {
                        val sb = StringBuilder()
                        cursor++
                        while (cursor < rest.length) {
                            val ch = rest[cursor]
                            if (ch == '\\' && cursor + 1 < rest.length) {
                                sb.append(rest[cursor + 1]); cursor += 2; continue
                            }
                            if (ch == '"') { cursor++; break }
                            sb.append(ch); cursor++
                        }
                        value = sb.toString()
                    } else {
                        val end = rest.indexOf(',', cursor).let { if (it < 0) rest.length else it }
                        value = rest.substring(cursor, end).trim()
                        cursor = end
                    }
                    if (name.isNotEmpty()) params[name] = value
                    index = rest.indexOf(',', cursor).let { if (it < 0) rest.length else it + 1 }
                    if (index >= rest.length) break
                }
            }
            out.add(BearerChallenge(params))
        }
        return out
    }

    private fun splitUnquotedSegments(header: String): List<String>? {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var escaped = false
        for (ch in header) {
            if (escaped) { current.append(ch); escaped = false; continue }
            if (inQuotes) {
                when (ch) {
                    '\\' -> { current.append(ch); escaped = true }
                    '"' -> { current.append(ch); inQuotes = false }
                    else -> current.append(ch)
                }
                continue
            }
            when (ch) {
                '"' -> { current.append(ch); inQuotes = true }
                ',' -> { segments.add(current.toString()); current.setLength(0) }
                else -> current.append(ch)
            }
        }
        segments.add(current.toString())
        if (segments.any { it.trim().isEmpty() }) return null
        return segments
    }
}

/** 本地回环回调：一次性 HTTP 服务器接 `?code=..&state=..`（mcp_oauth_callback_io）。 */
class McpOAuthLoopbackCallback(
    private val launchAuthorizationUrl: (URI) -> Boolean,
) {
    private val serverSocket: ServerSocket = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        .also { it.reuseAddress = true }

    val localPort: Int get() = serverSocket.localPort

    val redirectUri: URI
        get() = URI("http", null, "127.0.0.1", localPort, "/callback", null, null)

    /**
     * 打开浏览器并等待回调。返回回调 URI；[timeoutMs] 内未完成抛
     * [McpOAuthException]（kind=transient）。
     */
    suspend fun authorize(authorizationUrl: URI, timeoutMs: Long = 5 * 60_000): URI =
        withContext(Dispatchers.IO) {
            val socket = serverSocket
            socket.soTimeout = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (!launchAuthorizationUrl(authorizationUrl)) {
                socket.close()
                throw McpOAuthException("could not launch the authorization URL")
            }
            try {
                socket.accept().use { connection ->
                    val reader = BufferedReader(
                        InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII),
                    )
                    val requestLine = reader.readLine().orEmpty()
                    val target = requestLine.split(" ").getOrNull(1).orEmpty()
                    // 排空请求头后响应，浏览器才会认为请求完成。
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val body = "<html><body><h3>Authorization received.</h3>" +
                        "<p>You can close this window and return to the app.</p></body></html>"
                    val payload = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}\r\n" +
                        "Connection: close\r\n\r\n$body"
                    connection.getOutputStream().use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
                    val query = target.substringAfter('?', "")
                    if (query.isEmpty()) throw McpOAuthException("authorization callback had no query")
                    val parameters = query.split('&').mapNotNull { pair ->
                        val idx = pair.indexOf('=')
                        if (idx <= 0) return@mapNotNull null
                        // String 重载（API 1）——Charset 重载要 API 33，minSdk 26 会 NoSuchMethodError。
                        URLDecoder.decode(pair.substring(0, idx), "UTF-8") to
                            URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                    }.toMap()
                    val redirectBase = "http://127.0.0.1:${socket.localPort}/callback"
                    URI("$redirectBase?$query").let { base ->
                        URI(
                            base.scheme, base.authority, base.path,
                            parameters.entries.joinToString("&") { (k, v) ->
                                "${java.net.URLEncoder.encode(k, "UTF-8")}=" +
                                    java.net.URLEncoder.encode(v, "UTF-8")
                            },
                            null,
                        )
                    }
                }
            } catch (e: McpOAuthException) {
                throw e
            } catch (e: Exception) {
                throw McpOAuthException("authorization callback failed: $e", kind = McpOAuthFailureKind.transient)
            }
        }

    fun close() {
        runCatching { serverSocket.close() }
    }
}

/**
 * OAuth 引擎。令牌 / 发现已按服务器持久化在 [McpOAuthStore]；一个实例进程级
 * 共享（缓存语义与上游一致）。
 */
object McpOAuthService {

    private val JSON = Json { isLenient = true; ignoreUnknownKeys = true }
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val FORM_MEDIA = "application/x-www-form-urlencoded".toMediaType()
    private const val REQUEST_TIMEOUT_MS = 20_000L
    private const val CALLBACK_TIMEOUT_MS = 5 * 60_000L
    private const val MAX_RESPONSE_BYTES = 1024L * 1024

    private val discoveryCache = ConcurrentHashMap<String, McpOAuthDiscovery>()
    private val dynamicRegistrationCache = ConcurrentHashMap<String, McpOAuthClientRegistration>()
    private val publicTargetValidations = ConcurrentHashMap<String, Boolean>()

    // ── discovery（RFC 9728 + RFC 8414）────────────────────────────────────

    fun discover(
        serverUrl: String,
        client: OkHttpClient,
        headers: Map<String, String> = emptyMap(),
        wwwAuthenticate: List<String> = emptyList(),
    ): McpOAuthDiscovery {
        val server = requireServerUri(serverUrl, label = "MCP server URL")
        val cacheKey = discoveryCacheKey(serverUrl, headers, wwwAuthenticate)
        discoveryCache[cacheKey]?.let { return it }

        var challenges = wwwAuthenticate
        if (challenges.isEmpty()) {
            val probe = runCatching {
                send("GET", server, headers + mapOf("Accept" to "application/json, text/event-stream"), client)
            }.getOrNull()
            if (probe?.code == 401) {
                probe.header("WWW-Authenticate")?.takeIf { it.isNotEmpty() }?.let { challenges = listOf(it) }
            }
        }

        val discoveryChallenge = discoveryBearerChallenge(challenges)
        val challengedMetadataUrl = discoveryChallenge?.parameter("resource_metadata")
        if (discoveryChallenge?.hasParameter("resource_metadata") == true &&
            challengedMetadataUrl.isNullOrEmpty()
        ) {
            throw McpOAuthException("WWW-Authenticate resource_metadata parameter is invalid")
        }
        val challengedScope = discoveryChallenge?.parameter("scope")
        val targetResource = server
        val candidates: List<Pair<URI, URI>> = if (discoveryChallenge?.hasParameter("resource_metadata") == true) {
            listOf(
                requireProtectedResourceMetadataUri(
                    server.resolve(challengedMetadataUrl!!),
                    localServer = isLoopbackHost(server.host),
                ) to targetResource,
            )
        } else {
            protectedResourceMetadataCandidates(server)
        }

        var protectedResource: JsonObject? = null
        var protectedResourceUri: URI? = null
        var transientFailure: McpOAuthException? = null
        var resourceMismatch = false
        for ((metadataUri, expectedResource) in distinctMetadataCandidates(candidates)) {
            val response = try {
                send(
                    "GET", metadataUri,
                    headers = if (sameOrigin(server, metadataUri)) headers else mapOf("Accept" to "application/json"),
                    client = client,
                )
            } catch (e: McpOAuthException) {
                if (e.kind == McpOAuthFailureKind.transient) transientFailure = transientFailure ?: e
                null
            } catch (_: Exception) {
                null
            } ?: continue
            if (response.code !in 200..299) continue
            val value = runCatching {
                JSON.parseToJsonElement(response.body!!.string()).jsonObject
            }.getOrNull() ?: continue
            if ((value["resource"]?.jsonPrimitive?.contentOrNull) != expectedResource.toString()) {
                resourceMismatch = true
                continue
            }
            val authorizationServers = value["authorization_servers"]
            if (authorizationServers is kotlinx.serialization.json.JsonArray &&
                authorizationServers.any { (it as? JsonPrimitive)?.isString == true }
            ) {
                protectedResource = value
                protectedResourceUri = expectedResource
                break
            }
        }
        if (protectedResource == null || protectedResourceUri == null) {
            if (resourceMismatch) {
                throw McpOAuthException(
                    "protected resource metadata resource does not match the requested resource",
                )
            }
            transientFailure?.let { throw it }
            throw McpOAuthException("protected resource metadata could not be discovered")
        }

        val rawAuthorizationServers =
            (protectedResource["authorization_servers"] as kotlinx.serialization.json.JsonArray)
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        var authorizationMetadata: JsonObject? = null
        var authorizationServer: URI? = null
        var issuerMismatch = false
        var withoutPkce = false
        for (rawIssuer in rawAuthorizationServers) {
            val issuer = try {
                requireAuthorizationServerUri(rawIssuer, label = "authorization server")
            } catch (_: Exception) {
                continue
            }
            for (candidate in authorizationMetadataUris(issuer)) {
                val response = try {
                    send("GET", candidate, mapOf("Accept" to "application/json"), client)
                } catch (e: McpOAuthException) {
                    if (e.kind == McpOAuthFailureKind.transient) transientFailure = transientFailure ?: e
                    null
                } catch (_: Exception) {
                    null
                } ?: continue
                if (response.code !in 200..299) continue
                val value = runCatching {
                    JSON.parseToJsonElement(response.body!!.string()).jsonObject
                }.getOrNull() ?: continue
                if ((value["issuer"]?.jsonPrimitive?.contentOrNull) != issuer.toString()) {
                    issuerMismatch = true
                    continue
                }
                if (value["authorization_endpoint"]?.jsonPrimitive?.contentOrNull == null ||
                    value["token_endpoint"]?.jsonPrimitive?.contentOrNull == null
                ) continue
                val pkce = (value["code_challenge_methods_supported"] as? kotlinx.serialization.json.JsonArray)
                    ?.any { (it as? JsonPrimitive)?.content == "S256" } == true
                if (!pkce) {
                    withoutPkce = true
                    continue
                }
                authorizationMetadata = value
                authorizationServer = issuer
                break
            }
            if (authorizationMetadata != null) break
        }
        val asMetadata = authorizationMetadata
            ?: run {
                if (issuerMismatch) {
                    throw McpOAuthException(
                        "authorization server metadata issuer is missing or does not match discovery",
                    )
                }
                if (withoutPkce) {
                    throw McpOAuthException("authorization server does not advertise PKCE S256 support")
                }
                transientFailure?.let { throw it }
                throw McpOAuthException("authorization server metadata could not be discovered")
            }

        val challengedScopes = if (validScope(challengedScope)) splitScopes(challengedScope) else emptyList()
        val resourceScopes = stringList(protectedResource["scopes_supported"])
        val authorizationEndpoint = requireDiscoveredHttpsUri(
            asMetadata.getValue("authorization_endpoint").jsonPrimitive.content,
            label = "authorization endpoint",
        )
        val tokenEndpoint = requireDiscoveredHttpsUri(
            asMetadata.getValue("token_endpoint").jsonPrimitive.content,
            label = "token endpoint",
        )
        val registrationEndpoint = asMetadata["registration_endpoint"]
            ?.jsonPrimitive?.contentOrNull
            ?.let { requireDiscoveredHttpsUri(it, label = "registration endpoint") }
        validatePublicTargets(listOfNotNull(authorizationEndpoint, tokenEndpoint, registrationEndpoint))

        val discovery = McpOAuthDiscovery(
            authorizationServer = authorizationServer!!,
            authorizationEndpoint = authorizationEndpoint,
            tokenEndpoint = tokenEndpoint,
            registrationEndpoint = registrationEndpoint,
            resource = protectedResourceUri,
            scopes = challengedScopes.ifEmpty { resourceScopes },
            authorizationResponseIssParameterSupported =
                asMetadata["authorization_response_iss_parameter_supported"]
                    ?.jsonPrimitive?.contentOrNull == "true",
            clientIdMetadataDocumentSupported =
                asMetadata["client_id_metadata_document_supported"]
                    ?.jsonPrimitive?.contentOrNull == "true",
        )
        discoveryCache[cacheKey] = discovery
        return discovery
    }

    // ── authorize / refresh ─────────────────────────────────────────────────

    /**
     * 完整授权流程。回调由 [McpOAuthLoopbackCallback]（一次性本地服务器）承接，
     * [launchAuthorizationUrl] 交给系统浏览器。
     */
    suspend fun authorize(
        serverUrl: String,
        serverName: String,
        client: OkHttpClient,
        headers: Map<String, String> = emptyMap(),
        wwwAuthenticate: List<String> = emptyList(),
        additionalScopes: List<String> = emptyList(),
        clientRegistration: McpOAuthClientRegistration? = null,
        launchAuthorizationUrl: (URI) -> Boolean,
    ): McpOAuthState {
        val callback = McpOAuthLoopbackCallback(launchAuthorizationUrl)
        try {
            val discovery = discover(serverUrl, client, headers, wwwAuthenticate)
            val scopes = (discovery.scopes + additionalScopes).distinct()
            var registration = clientRegistration
            if (registration?.registrationSource == McpOAuthClientRegistrationSource.dcr &&
                (registration.authorizationServer != discovery.authorizationServer.toString() ||
                    registration.redirectUri != callback.redirectUri.toString())
            ) {
                registration = null
            }
            registration = registration ?: dynamicRegister(discovery, callback.redirectUri, serverName.ifBlank { "Memo" }, scopes)
            validateClientRegistration(registration)
            if (registration.registrationSource != McpOAuthClientRegistrationSource.cimd &&
                registration.authorizationServer != null &&
                registration.authorizationServer != discovery.authorizationServer.toString()
            ) {
                throw McpOAuthException("configured OAuth client belongs to a different authorization server")
            }

            val verifier = randomBase64Url(32)
            val challenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(
                    MessageDigest.getInstance("SHA-256")
                        .digest(verifier.toByteArray(StandardCharsets.US_ASCII)),
                )
            val state = randomBase64Url(16)
            val authorizationUrl = discovery.authorizationEndpoint.let { endpoint ->
                val query = buildList {
                    endpoint.query?.takeIf { it.isNotEmpty() }?.let { add(it) }
                    add("response_type=code")
                    add("client_id=${encodeQuery(registration.clientId)}")
                    add("redirect_uri=${encodeQuery(callback.redirectUri.toString())}")
                    add("code_challenge=$challenge")
                    add("code_challenge_method=S256")
                    add("state=$state")
                    add("resource=${encodeQuery(discovery.resource.toString())}")
                    if (scopes.isNotEmpty()) add("scope=${encodeQuery(scopes.joinToString(" "))}")
                }.joinToString("&")
                URI(
                    endpoint.scheme, endpoint.authority, endpoint.path,
                    query, null,
                )
            }

            val callbackUri = callback.authorize(authorizationUrl, CALLBACK_TIMEOUT_MS)
            if (!sameRedirectTarget(callbackUri, callback.redirectUri)) {
                throw McpOAuthException("authorization callback redirect URI mismatch")
            }
            val callbackQuery = parseQuery(callbackUri.rawQuery)
            if (callbackQuery["state"] != state) {
                throw McpOAuthException("authorization callback state mismatch")
            }
            val callbackIssuer = callbackQuery["iss"]
            if (discovery.authorizationResponseIssParameterSupported && callbackIssuer == null) {
                throw McpOAuthException("authorization callback did not include the required issuer")
            }
            if (callbackIssuer != null && callbackIssuer != discovery.authorizationServer.toString()) {
                throw McpOAuthException("authorization callback issuer mismatch")
            }
            callbackQuery["error"]?.let { oauthError ->
                val description = callbackQuery["error_description"]
                throw McpOAuthException(
                    if (description == null) oauthError else "$oauthError: $description",
                    kind = McpOAuthFailureKind.authorizationRequired,
                    oauthError = oauthError,
                )
            }
            val code = callbackQuery["code"]
            if (code.isNullOrEmpty()) {
                throw McpOAuthException("authorization callback did not include a code")
            }

            val token = try {
                requestToken(
                    discovery.tokenEndpoint,
                    linkedMapOf(
                        "grant_type" to "authorization_code",
                        "code" to code,
                        "redirect_uri" to callback.redirectUri.toString(),
                        "code_verifier" to verifier,
                        "resource" to discovery.resource.toString(),
                    ),
                    registration = registration,
                    client = client,
                )
            } catch (e: McpOAuthException) {
                if (e.oauthError == "invalid_client" &&
                    registration.registrationSource == McpOAuthClientRegistrationSource.dcr
                ) {
                    dynamicRegistrationCache.remove(
                        dynamicRegistrationCacheKey(discovery, callback.redirectUri, scopes),
                    )
                }
                throw e
            }
            val result = stateFromToken(token, discovery, registration, scopes, canonicalResource(
                requireServerUri(serverUrl, label = "MCP server URL"),
            ).toString())
            discoveryCache.remove(discoveryCacheKey(serverUrl, headers, wwwAuthenticate))
            return result
        } finally {
            callback.close()
        }
    }

    fun refresh(state: McpOAuthState, client: OkHttpClient): McpOAuthState {
        val refreshToken = state.refreshToken
        if (refreshToken.isNullOrEmpty()) {
            throw McpOAuthException("no refresh token is available", kind = McpOAuthFailureKind.authorizationRequired)
        }
        val registration = McpOAuthClientRegistration(
            clientId = state.clientId,
            clientSecret = state.clientSecret,
            tokenEndpointAuthMethod = state.tokenEndpointAuthMethod,
            authorizationServer = state.authorizationServer,
            redirectUri = state.redirectUri,
            registrationSource = state.registrationSource,
        )
        validateClientRegistration(registration)
        val form = linkedMapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "resource" to state.resource,
        )
        state.scope?.takeIf { it.isNotEmpty() }?.let { form["scope"] = it }
        val token = requestToken(
            requireDiscoveredHttpsUri(state.tokenEndpoint, label = "token endpoint"),
            form,
            registration = registration,
            client = client,
        )
        val next = McpOAuthState(
            clientId = state.clientId,
            clientSecret = state.clientSecret,
            authorizationServer = state.authorizationServer,
            authorizationEndpoint = state.authorizationEndpoint,
            tokenEndpoint = state.tokenEndpoint,
            registrationEndpoint = state.registrationEndpoint,
            serverUrl = state.serverUrl,
            resource = state.resource,
            scope = token["scope"] as? String ?: state.scope,
            tokenEndpointAuthMethod = state.tokenEndpointAuthMethod,
            registrationSource = state.registrationSource,
            redirectUri = state.redirectUri,
            accessToken = token["access_token"] as? String ?: throw McpOAuthException("token response did not include access_token"),
            tokenType = token["token_type"] as? String ?: "Bearer",
            refreshToken = token["refresh_token"] as? String ?: state.refreshToken,
            expiresAt = expiresAt(token["expires_in"]),
        )
        return next
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun dynamicRegister(
        discovery: McpOAuthDiscovery,
        redirectUri: URI,
        clientName: String,
        scopes: List<String>,
    ): McpOAuthClientRegistration {
        val endpoint = discovery.registrationEndpoint
            ?: throw McpOAuthException(
                if (discovery.clientIdMetadataDocumentSupported) {
                    "configure a pre-registered client ID or Client ID Metadata Document URL"
                } else {
                    "configure a pre-registered client ID; the authorization server does not support dynamic client registration"
                },
            )
        val body = buildJsonObject {
            put("client_name", clientName)
            put("redirect_uris", buildJsonArray { add(JsonPrimitive(redirectUri.toString())) })
            put("grant_types", buildJsonArray {
                add(JsonPrimitive("authorization_code")); add(JsonPrimitive("refresh_token"))
            })
            put("response_types", buildJsonArray { add(JsonPrimitive("code")) })
            put("token_endpoint_auth_method", "none")
            put("application_type", "native")
            if (scopes.isNotEmpty()) put("scope", scopes.joinToString(" "))
        }
        val client = OkHttpClient()
        val response = send("POST", endpoint, mapOf("Accept" to "application/json", "Content-Type" to "application/json"), client, body = body.toString())
        val registration = decodeSuccessfulJson(response, "client registration")
        val clientId = registration["client_id"] as? String
        if (clientId.isNullOrEmpty()) {
            throw McpOAuthException("dynamic client registration did not return client_id")
        }
        return McpOAuthClientRegistration(
            clientId = clientId,
            clientSecret = registration["client_secret"] as? String,
            tokenEndpointAuthMethod = (registration["token_endpoint_auth_method"] as? String) ?: "none",
            authorizationServer = discovery.authorizationServer.toString(),
            redirectUri = redirectUri.toString(),
            registrationSource = McpOAuthClientRegistrationSource.dcr,
        )
    }

    private fun requestToken(
        endpoint: URI,
        form: Map<String, String>,
        registration: McpOAuthClientRegistration,
        client: OkHttpClient,
    ): Map<String, Any?> {
        val body = LinkedHashMap<String, String>()
        body.putAll(form)
        val headers = linkedMapOf("Accept" to "application/json")
        when (registration.tokenEndpointAuthMethod) {
            "none" -> body["client_id"] = registration.clientId
            "client_secret_post" -> {
                body["client_id"] = registration.clientId
                body["client_secret"] = registration.clientSecret.orEmpty()
            }
            "client_secret_basic" -> {
                val encoded = encodeQuery(registration.clientId) + ":" + encodeQuery(registration.clientSecret.orEmpty())
                headers["Authorization"] = "Basic " +
                    Base64.getEncoder().encodeToString(encoded.toByteArray(StandardCharsets.UTF_8))
            }
        }
        val encodedBody = body.filterValues { it != null }.entries.joinToString("&") { (k, v) ->
            "${encodeQuery(k)}=${encodeQuery(v!!)}"
        }
        val response = send(
            "POST", endpoint,
            headers + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            client, body = encodedBody,
        )
        val token = decodeSuccessfulJson(response, "token request")
        if ((token["access_token"] as? String).isNullOrEmpty()) {
            throw McpOAuthException("token response did not include access_token")
        }
        return token
    }

    private fun stateFromToken(
        token: Map<String, Any?>,
        discovery: McpOAuthDiscovery,
        registration: McpOAuthClientRegistration,
        scopes: List<String>,
        serverUrl: String,
    ): McpOAuthState = McpOAuthState(
        clientId = registration.clientId,
        clientSecret = registration.clientSecret,
        authorizationServer = discovery.authorizationServer.toString(),
        authorizationEndpoint = discovery.authorizationEndpoint.toString(),
        tokenEndpoint = discovery.tokenEndpoint.toString(),
        registrationEndpoint = discovery.registrationEndpoint?.toString(),
        serverUrl = serverUrl,
        resource = discovery.resource.toString(),
        scope = token["scope"] as? String ?: scopes.takeIf { it.isNotEmpty() }?.joinToString(" "),
        tokenEndpointAuthMethod = registration.tokenEndpointAuthMethod,
        registrationSource = registration.registrationSource,
        redirectUri = registration.redirectUri,
        accessToken = token["access_token"] as String,
        tokenType = token["token_type"] as? String ?: "Bearer",
        refreshToken = token["refresh_token"] as? String,
        expiresAt = expiresAt(token["expires_in"]),
    )

    private fun validateClientRegistration(registration: McpOAuthClientRegistration) {
        if (registration.clientId.isEmpty()) {
            throw McpOAuthException("OAuth client ID is empty")
        }
        if (registration.registrationSource == McpOAuthClientRegistrationSource.dcr &&
            registration.authorizationServer == null
        ) {
            throw McpOAuthException("dynamic OAuth client is not bound to an authorization server")
        }
        when (registration.tokenEndpointAuthMethod) {
            "none" -> return
            "client_secret_basic", "client_secret_post" -> {
                if (!registration.clientSecret.isNullOrEmpty()) return
                throw McpOAuthException(
                    "${registration.tokenEndpointAuthMethod} requires a client secret",
                )
            }
            else -> throw McpOAuthException(
                "unsupported token endpoint authentication method: ${registration.tokenEndpointAuthMethod}",
            )
        }
    }

    private fun decodeSuccessfulJson(response: okhttp3.Response, operation: String): Map<String, Any?> {
        val bodyText = runCatching { response.body!!.string() }.getOrDefault("")
        val decoded = runCatching {
            @Suppress("UNCHECKED_CAST")
            anyFromJsonIgnore(Json.parseToJsonElement(bodyText))
        }.getOrNull()
        if (response.code !in 200..299) {
            @Suppress("UNCHECKED_CAST")
            val map = decoded as? Map<String, Any?>
            val oauthError = map?.get("error") as? String
            val description = map?.get("error_description") ?: oauthError
            val kind = when {
                oauthError == "invalid_grant" -> McpOAuthFailureKind.authorizationRequired
                response.code == 408 || response.code == 429 || response.code >= 500 ->
                    McpOAuthFailureKind.transient
                else -> McpOAuthFailureKind.invalidResponse
            }
            throw McpOAuthException(
                "$operation failed with HTTP ${response.code}" +
                    (if (description == null) "" else ": $description"),
                kind = kind,
                statusCode = response.code,
                oauthError = oauthError,
            )
        }
        @Suppress("UNCHECKED_CAST")
        return decoded as? Map<String, Any?>
            ?: throw McpOAuthException("$operation returned invalid JSON")
    }

    private fun anyFromJsonIgnore(element: kotlinx.serialization.json.JsonElement?): Any? = jsonToAny(element)

    private fun jsonToAny(element: kotlinx.serialization.json.JsonElement?): Any? = when (element) {
        null -> null
        is kotlinx.serialization.json.JsonNull -> null
        is kotlinx.serialization.json.JsonObject -> {
            val map = LinkedHashMap<String, Any?>()
            element.forEach { (k, v) -> map[k] = jsonToAny(v) }
            map
        }
        is kotlinx.serialization.json.JsonArray -> element.map { jsonToAny(it) }
        is kotlinx.serialization.json.JsonPrimitive ->
            if (element.isString) element.content
            else element.content.toLongOrNull() ?: element.content.toDoubleOrNull() ?: element.content
    }

    /** OkHttp 单发：不跟随重定向 + SSRF 解析校验 + 超时。 */
    private fun send(
        method: String,
        uri: URI,
        headers: Map<String, String>,
        client: OkHttpClient,
        body: String? = null,
    ): okhttp3.Response {
        validatePublicTarget(uri)
        val media = if (headers["Content-Type"]?.contains("json") == true) JSON_MEDIA else FORM_MEDIA
        val builder = Request.Builder()
            .url(uri.toURL())
            .method(method, body?.toRequestBody(media))
        for ((key, value) in headers) builder.header(key, value)
        val call = client.newBuilder()
            .followRedirects(false)
            .callTimeout(REQUEST_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
            .newCall(builder.build())
        return call.execute()
    }

    /** SSRF：发现的目标必须解析到公网地址（`validateMcpOAuthPublicUri`）。 */
    fun validatePublicTarget(uri: URI) {
        val key = "${uri.scheme.lowercase()}://${uri.host.lowercase()}:${effectivePort(uri)}"
        if (publicTargetValidations.containsKey(key)) return
        if (uri.scheme.lowercase() != "https") {
            throw McpOAuthException("target must use HTTPS: $uri")
        }
        if (isNonPublicHost(uri.host)) {
            throw McpOAuthException("target must not be a local or private host: $uri")
        }
        val addresses = runCatching { InetAddress.getAllByName(uri.host).toList() }.getOrElse {
            throw McpOAuthException("failed to resolve host ${uri.host}: $it")
        }
        for (address in addresses) {
            if (!isPublicAddress(address)) {
                throw McpOAuthException("target resolved to a non-public address: $address")
            }
        }
        publicTargetValidations[key] = true
    }

    private fun validatePublicTargets(uris: List<URI>) {
        for (uri in uris) {
            try {
                validatePublicTarget(uri)
            } catch (e: McpOAuthException) {
                throw McpOAuthException(
                    "authorization server endpoint resolved to a non-public address: ${e.message}",
                )
            }
        }
    }

    private fun isPublicAddress(address: InetAddress): Boolean = !(
        address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        )

    // ── URI / scope / challenge 帮手 ────────────────────────────────────────

    fun canonicalResource(server: URI): URI {
        val path = if ((server.path.isEmpty() || server.path == "/") && server.rawQuery == null) "" else server.path
        return URI(
            server.scheme.lowercase(), null, server.host.lowercase(),
            if (server.port != -1) server.port else -1,
            path, server.rawQuery, null,
        )
    }

    fun bearerChallengeParameter(challenges: List<String>, name: String): String? =
        challenges.flatMap { McpOAuthParsing.parseBearerChallenges(it) }
            .firstNotNullOfOrNull { it.parameter(name) }

    fun bearerChallengeHasError(challenges: List<String>, error: String): Boolean =
        challenges.flatMap { McpOAuthParsing.parseBearerChallenges(it) }
            .any { it.parameter("error") == error }

    private fun discoveryBearerChallenge(headers: List<String>): BearerChallenge? {
        val challenges = headers.flatMap { McpOAuthParsing.parseBearerChallenges(it) }
        return challenges.firstOrNull { it.hasParameter("resource_metadata") } ?: challenges.firstOrNull()
    }

    private fun requireServerUri(raw: String, label: String): URI {
        val uri = parseAbsoluteUri(raw, label)
        if (uri.scheme.lowercase() != "https" && !(uri.scheme.lowercase() == "http" && isLoopbackHost(uri.host))) {
            throw McpOAuthException("$label must use HTTPS")
        }
        return uri
    }

    private fun requireProtectedResourceMetadataUri(uri: URI, localServer: Boolean): URI =
        if (localServer) {
            requireServerUri(uri.toString(), label = "protected resource metadata URL")
        } else {
            requireDiscoveredHttpsUri(uri.toString(), label = "protected resource metadata URL")
        }

    private fun requireAuthorizationServerUri(raw: String, label: String): URI {
        val uri = requireDiscoveredHttpsUri(raw, label)
        if (uri.rawQuery != null) throw McpOAuthException("$label must not contain a query")
        return uri
    }

    private fun requireDiscoveredHttpsUri(raw: String, label: String): URI {
        val uri = parseAbsoluteUri(raw, label)
        if (uri.scheme.lowercase() != "https") throw McpOAuthException("$label must use HTTPS")
        if (isNonPublicHost(uri.host)) {
            throw McpOAuthException("$label must not target a local or private host")
        }
        return uri
    }

    private fun parseAbsoluteUri(raw: String, label: String): URI {
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            throw McpOAuthException("$label is invalid")
        }
        if (uri.scheme.isNullOrEmpty() || uri.host.isNullOrEmpty()) {
            throw McpOAuthException("$label is invalid")
        }
        if (uri.fragment != null) throw McpOAuthException("$label must not contain a fragment")
        if (!uri.userInfo.isNullOrEmpty()) throw McpOAuthException("$label must not contain user information")
        return uri
    }

    internal fun isLoopbackHost(host: String): Boolean {
        val normalized = host.lowercase()
        return normalized == "localhost" || normalized.endsWith(".localhost") ||
            normalized == "127.0.0.1" || normalized == "::1"
    }

    internal fun isNonPublicHost(host: String): Boolean {
        val normalized = host.lowercase()
        if (isLoopbackHost(normalized) || normalized.endsWith(".local") || normalized.endsWith(".internal")) {
            return true
        }
        val ipv4 = normalized.split(".")
        if (ipv4.size == 4 && ipv4.all { it.toIntOrNull() != null }) {
            val a = ipv4[0].toInt()
            val b = ipv4[1].toInt()
            return a == 0 || a == 10 || a == 127 ||
                (a == 100 && b >= 64 && b <= 127) ||
                (a == 169 && b == 254) ||
                (a == 172 && b >= 16 && b <= 31) ||
                (a == 192 && b == 168) ||
                (a == 198 && (b == 18 || b == 19)) ||
                a >= 224
        }
        if (normalized.contains(':')) {
            return normalized == "::" || normalized.startsWith("fc") || normalized.startsWith("fd") ||
                Regex("^fe[89ab]", RegexOption.IGNORE_CASE).containsMatchIn(normalized) ||
                normalized.startsWith("ff") || normalized.startsWith("::ffff:127.") ||
                normalized.startsWith("::ffff:10.") || normalized.startsWith("::ffff:192.168.")
        }
        return false
    }

    private fun protectedResourceMetadataCandidates(server: URI): List<Pair<URI, URI>> {
        val targetResource = server
        val path = when {
            server.path.isEmpty() || server.path == "/" -> ""
            server.path.startsWith("/") -> server.path
            else -> "/${server.path}"
        }
        val rootResource = canonicalResource(originUri(server, ""))
        return listOf(
            originUri(server, "/.well-known/oauth-protected-resource$path", server.rawQuery) to targetResource,
            originUri(server, "/.well-known/oauth-protected-resource") to rootResource,
        )
    }

    private fun authorizationMetadataUris(issuer: URI): List<URI> {
        val path = when {
            issuer.path.isEmpty() || issuer.path == "/" -> ""
            issuer.path.startsWith("/") -> issuer.path
            else -> "/${issuer.path}"
        }
        return if (path.isNotEmpty()) {
            listOf(
                originUri(issuer, "/.well-known/oauth-authorization-server$path"),
                originUri(issuer, "/.well-known/openid-configuration$path"),
                originUri(issuer, "$path/.well-known/openid-configuration"),
            )
        } else {
            listOf(
                originUri(issuer, "/.well-known/oauth-authorization-server"),
                originUri(issuer, "/.well-known/openid-configuration"),
            )
        }
    }

    private fun originUri(source: URI, path: String, query: String? = null): URI =
        URI(source.scheme, source.authority, path, query, null)

    private fun distinctMetadataCandidates(
        values: List<Pair<URI, URI>>,
    ): List<Pair<URI, URI>> {
        val seen = HashSet<String>()
        return values.filter { seen.add("${it.first}\n${it.second}") }
    }

    private fun splitScopes(value: String?): List<String> =
        value?.trim()?.takeIf { it.isNotEmpty() }?.split(Regex("\\s+")) ?: emptyList()

    private fun validScope(scope: String?): Boolean =
        !scope.isNullOrEmpty() && scope.split(" ").all { it.isNotEmpty() && it == it.trim() }

    private fun stringList(raw: Any?): List<String> =
        (raw as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?: emptyList()

    private fun sameOrigin(a: URI, b: URI): Boolean =
        a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) &&
            effectivePort(a) == effectivePort(b)

    private fun effectivePort(uri: URI): Int = when {
        uri.port != -1 -> uri.port
        uri.scheme.equals("https", true) -> 443
        else -> 80
    }

    private fun sameRedirectTarget(callback: URI, redirect: URI): Boolean =
        callback.scheme == redirect.scheme && callback.authority == redirect.authority &&
            callback.path == redirect.path

    // 一律用 String 重载（API 1）：Charset 重载要 API 33，minSdk 26 上会 NoSuchMethodError。
    private fun parseQuery(rawQuery: String?): Map<String, String> =
        rawQuery.orEmpty().split('&').filter { it.contains('=') }.associate { pair ->
            val idx = pair.indexOf('=')
            URLDecoder.decode(pair.substring(0, idx), "UTF-8") to
                URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
        }

    private fun encodeQuery(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    private fun randomBase64Url(bytes: Int): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(bytes).also { SecureRandom().nextBytes(it) })

    private fun expiresAt(expiresIn: Any?): Long? =
        (expiresIn as? Number)?.let { System.currentTimeMillis() + it.toLong() * 1000 }

    private fun discoveryCacheKey(serverUrl: String, headers: Map<String, String>, challenges: List<String>): String =
        "$serverUrl\n${headers.entries.sortedWith(compareBy { it.key }).joinToString("|") { "${it.key}=${it.value}" }}\n${challenges.joinToString("|")}"

    private fun dynamicRegistrationCacheKey(
        discovery: McpOAuthDiscovery,
        redirectUri: URI,
        scopes: List<String>,
    ): String = "${discovery.authorizationServer}|$redirectUri|${scopes.joinToString(" ")}"
}

/** 令牌持久化（每服务器一条 preference；键路由归 LOCAL_ONLY/DB 由仓库决定）。 */
class McpOAuthStore(private val preferenceRepository: PreferenceRepository) {

    fun load(serverId: String): McpOAuthState? =
        McpOAuthState.tryFromJson(preferenceRepository.readJson(key(serverId)))

    fun save(state: McpOAuthState, serverId: String) {
        preferenceRepository.writeJson(key(serverId), state.toJson().toString())
    }

    fun clear(serverId: String) {
        preferenceRepository.writeJson(key(serverId), "null")
    }

    private fun key(serverId: String) = "mcp_oauth_state_$serverId"
}

package com.psyche.memo.provider.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/**
 * The pure half of the MCP OAuth engine (MCP-3): Bearer challenge parsing,
 * SSRF host classification, discovery candidate URLs, canonical resource and
 * the token state (de)serialization.
 */
class McpOAuthTest {

    // ── Bearer challenge parsing ───────────────────────────────────────────

    @Test
    fun `bearer challenge parses quoted and bare parameters`() {
        val challenges = McpOAuthParsing.parseBearerChallenges(
            "Bearer realm=\"mcp\", resource_metadata=\"https://srv.example.com/.well-known/oauth-protected-resource/mcp\", scope=\"files read\"",
        )
        assertEquals(1, challenges.size)
        val challenge = challenges[0]
        assertEquals("mcp", challenge.parameter("realm"))
        assertEquals(
            "https://srv.example.com/.well-known/oauth-protected-resource/mcp",
            challenge.parameter("resource_metadata"),
        )
        assertEquals("files read", challenge.parameter("scope"))
        assertTrue(challenge.hasParameter("resource_metadata"))
        assertNull(challenge.parameter("error"))
    }

    @Test
    fun `bearer challenge handles multiple challenges and error codes`() {
        val header = "Bearer error=\"insufficient_scope\", error_description=\"more scope\", Basic realm=\"x\""
        // 只有 Bearer 挑战进列表（`_parseBearerChallenges` 丢弃其余 scheme）。
        val challenges = McpOAuthParsing.parseBearerChallenges(header)
        assertEquals(1, challenges.size)
        assertTrue(McpOAuthService.bearerChallengeHasError(listOf(header), "insufficient_scope"))
        assertEquals(
            "more scope",
            McpOAuthService.bearerChallengeParameter(listOf(header), "error_description"),
        )
    }

    // ── SSRF host classification ───────────────────────────────────────────

    @Test
    fun `loopback hosts are recognized`() {
        assertTrue(McpOAuthService.isLoopbackHost("localhost"))
        assertTrue(McpOAuthService.isLoopbackHost("api.localhost"))
        assertTrue(McpOAuthService.isLoopbackHost("127.0.0.1"))
        assertTrue(McpOAuthService.isLoopbackHost("::1"))
        assertFalse(McpOAuthService.isLoopbackHost("mcp.example.com"))
    }

    @Test
    fun `non-public hosts cover the upstream private ranges`() {
        for (host in listOf(
            "10.1.2.3", "127.0.0.1", "172.16.0.1", "172.31.255.255",
            "192.168.1.1", "169.254.10.10", "100.64.0.1", "100.127.255.255",
            "0.1.2.3", "224.0.0.1", "metadata.internal", "printer.local",
            "fd00::1", "fe80::1", "ff02::1", "::ffff:10.0.0.1", "::ffff:192.168.0.1",
        )) {
            assertTrue("expected non-public: $host", McpOAuthService.isNonPublicHost(host))
        }
        for (host in listOf(
            "8.8.8.8", "1.1.1.1", "172.32.0.1", "100.128.0.1", "192.169.0.1",
            "mcp.example.com", "2606:4700::1",
        )) {
            assertFalse("expected public: $host", McpOAuthService.isNonPublicHost(host))
        }
    }

    // ── discovery candidate URLs ───────────────────────────────────────────

    @Test
    fun `protected resource candidates cover path and root locations`() {
        val server = URI("https://mcp.example.com/mcp")
        val viaPrivate = invokeCandidates(server)
        assertEquals(2, viaPrivate.size)
        assertEquals(
            "https://mcp.example.com/.well-known/oauth-protected-resource/mcp",
            viaPrivate[0].first.toString(),
        )
        assertEquals("https://mcp.example.com/mcp", viaPrivate[0].second.toString())
        assertEquals(
            "https://mcp.example.com/.well-known/oauth-protected-resource",
            viaPrivate[1].first.toString(),
        )
        assertEquals("https://mcp.example.com", viaPrivate[1].second.toString())

        // A root-path server collapses to the same location for both.
        val root = URI("https://mcp.example.com")
        assertEquals(2, invokeCandidates(root).size)
    }

    @Test
    fun `authorization server metadata uses rfc8414 and oidc locations`() {
        val issuer = URI("https://auth.example.com/realms/main")
        val uris = invokeAuthorizationMetadataUris(issuer)
        assertEquals(
            listOf(
                "https://auth.example.com/.well-known/oauth-authorization-server/realms/main",
                "https://auth.example.com/.well-known/openid-configuration/realms/main",
                "https://auth.example.com/realms/main/.well-known/openid-configuration",
            ),
            uris.map { it.toString() },
        )
        val root = invokeAuthorizationMetadataUris(URI("https://auth.example.com"))
        assertEquals(
            listOf(
                "https://auth.example.com/.well-known/oauth-authorization-server",
                "https://auth.example.com/.well-known/openid-configuration",
            ),
            root.map { it.toString() },
        )
    }

    // ── canonical resource ─────────────────────────────────────────────────

    @Test
    fun `canonical resource lowercases the origin and keeps the path`() {
        // An explicit port is preserved (`server.hasPort ? server.port : null`).
        assertEquals(
            "https://mcp.example.com:443/mcp",
            McpOAuthService.canonicalResource(URI("https://MCP.Example.COM:443/mcp")).toString(),
        )
        // Root path with no query drops the trailing slash.
        assertEquals(
            "https://mcp.example.com",
            McpOAuthService.canonicalResource(URI("https://mcp.example.com/")).toString(),
        )
        assertEquals(
            "http://127.0.0.1:8080",
            McpOAuthService.canonicalResource(URI("http://127.0.0.1:8080/")).toString(),
        )
    }

    // ── token state (de)serialization ──────────────────────────────────────

    @Test
    fun `state json round-trips every field`() {
        val state = McpOAuthState(
            clientId = "client-1",
            clientSecret = null,
            authorizationServer = "https://auth.example.com",
            authorizationEndpoint = "https://auth.example.com/authorize",
            tokenEndpoint = "https://auth.example.com/token",
            registrationEndpoint = null,
            serverUrl = "https://mcp.example.com/mcp",
            resource = "https://mcp.example.com/mcp",
            scope = "files read",
            tokenEndpointAuthMethod = "none",
            registrationSource = McpOAuthClientRegistrationSource.dcr,
            redirectUri = "http://127.0.0.1:0/callback",
            accessToken = "at",
            tokenType = "bearer",
            refreshToken = "rt",
            expiresAt = 4102444800000L,
        )
        val parsed = McpOAuthState.tryFromJson(state.toJson().toString())
        assertEquals(state, parsed)
        assertEquals("Bearer at", parsed!!.authorizationHeader)
        assertFalse(parsed.shouldRefresh(leewayMs = 0))
    }

    @Test
    fun `state refresh window follows the expiry`() {
        val expiring = McpOAuthState(
            clientId = "c",
            authorizationServer = "https://auth.example.com",
            authorizationEndpoint = "https://auth.example.com/authorize",
            tokenEndpoint = "https://auth.example.com/token",
            resource = "https://mcp.example.com",
            accessToken = "at",
            refreshToken = "rt",
            expiresAt = System.currentTimeMillis() + 30_000,
        )
        assertTrue(expiring.shouldRefresh())
        val valid = expiring.copy(expiresAt = System.currentTimeMillis() + 600_000)
        assertFalse(valid.shouldRefresh())
        // No refresh token → nothing to refresh even when close to expiry.
        assertFalse(valid.copy(refreshToken = null).shouldRefresh())
    }

    @Test
    fun `malformed state json yields null instead of throwing`() {
        assertNull(McpOAuthState.tryFromJson("not json"))
        assertNull(McpOAuthState.tryFromJson("{\"clientId\":\"x\"}"))
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun invokeCandidates(server: URI): List<Pair<URI, URI>> {
        val method = McpOAuthService::class.java.getDeclaredMethod(
            "protectedResourceMetadataCandidates", URI::class.java,
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(McpOAuthService, server) as List<Pair<URI, URI>>
    }

    private fun invokeAuthorizationMetadataUris(issuer: URI): List<URI> {
        val method = McpOAuthService::class.java.getDeclaredMethod(
            "authorizationMetadataUris", URI::class.java,
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(McpOAuthService, issuer) as List<URI>
    }
}

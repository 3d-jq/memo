package com.psyche.memo

import com.psyche.memo.data.settings.PreferenceRepository
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * 全局网络代理的读取与应用（network_proxy_page.dart 的七个
 * `global_proxy_*_v1` 键 → `dio_http_client.dart` L79-140 的 dio 应用方式）。
 *
 * 上游是「每次建 dio 客户端时读当前设置」，Android 这边用一个每次连接都读
 * 当前配置的 [ProxySelector] 达到同样的"改了立即生效"，不用重建 OkHttp。
 * http/https 都走 [Proxy.Type.HTTP]（https 由 OkHttp 做 CONNECT 隧道），
 * socks5 走 [Proxy.Type.SOCKS]（Java 层不支持 SOCKS 用户名密码 → 上游支持，
 * 这是已知偏差）。
 *
 * 绕过规则（`global_proxy_bypass_v1`，逗号分隔）上游 dio **没有消费**；这里
 * 实现了精确/后缀/CIDR 匹配——本地模型服务器（127.0.0.1:11434 之类）在挂
 * 代理时不能被劫走。
 */
object GlobalProxy {

    private const val KEY_ENABLED = "global_proxy_enabled_v1"
    private const val KEY_TYPE = "global_proxy_type_v1"
    private const val KEY_HOST = "global_proxy_host_v1"
    private const val KEY_PORT = "global_proxy_port_v1"
    private const val KEY_USERNAME = "global_proxy_username_v1"
    private const val KEY_PASSWORD = "global_proxy_password_v1"
    private const val KEY_BYPASS = "global_proxy_bypass_v1"

    data class Settings(
        val type: String,
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val bypass: String,
    ) {
        val isValid: Boolean get() = host.isNotBlank() && port in 1..65535
    }

    /** 存储形态：字符串键值带 JSON 引号，enabled 是裸 true/false。 */
    fun read(prefs: PreferenceRepository): Settings? {
        if (stripQuotes(prefs.readJson(KEY_ENABLED)) != "true") return null
        val host = stripQuotes(prefs.readJson(KEY_HOST)).orEmpty().trim()
        val port = stripQuotes(prefs.readJson(KEY_PORT))?.trim()?.toIntOrNull() ?: 0
        val type = stripQuotes(prefs.readJson(KEY_TYPE))?.trim().orEmpty().ifEmpty { "http" }
        val settings = Settings(
            type = type,
            host = host,
            port = port,
            username = stripQuotes(prefs.readJson(KEY_USERNAME)).orEmpty().trim(),
            password = stripQuotes(prefs.readJson(KEY_PASSWORD)).orEmpty(),
            bypass = stripQuotes(prefs.readJson(KEY_BYPASS)).orEmpty(),
        )
        return if (settings.isValid) settings else null
    }

    private fun stripQuotes(raw: String?): String? {
        raw ?: return null
        val trimmed = raw.trim()
        if (trimmed == "null") return null
        return if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
    }

    /** 绕过判定：精确 host、`.domain`/`domain` 后缀、IP 段（CIDR）。 */
    fun isBypassed(host: String, bypass: String): Boolean {
        val hostLower = host.trim().lowercase()
        if (hostLower.isEmpty()) return false
        for (rawRule in bypass.split(',')) {
            val rule = rawRule.trim().lowercase()
            if (rule.isEmpty()) continue
            if (rule == hostLower) return true
            if (rule.startsWith(".") && (hostLower.endsWith(rule) || hostLower == rule.substring(1))) return true
            if (hostLower.endsWith(".$rule")) return true
            if (rule.contains('/')) {
                val ip = parseInet4(hostLower) ?: continue
                if (inCidr(ip, rule)) return true
            }
        }
        return false
    }

    /** 供 AppContainer 的 OkHttp 使用：每次连接都读当前配置（改了立即生效）。 */
    fun selector(prefs: PreferenceRepository): ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            if (uri == null) return listOf(Proxy.NO_PROXY)
            val settings = read(prefs) ?: return listOf(Proxy.NO_PROXY)
            if (isBypassed(uri.host.orEmpty(), settings.bypass)) return listOf(Proxy.NO_PROXY)
            val type = if (settings.type == "socks5") Proxy.Type.SOCKS else Proxy.Type.HTTP
            return listOf(Proxy(type, InetSocketAddress.createUnresolved(settings.host, settings.port)))
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) {}
    }

    /** http 代理的 Basic 认证（上游 `addProxyCredentials` L120-124）。 */
    fun credentialsFor(prefs: PreferenceRepository): String? {
        val settings = read(prefs) ?: return null
        if (settings.type == "socks5") return null
        if (settings.username.isEmpty()) return null
        return okhttp3.Credentials.basic(settings.username, settings.password)
    }

    // — CIDR —

    private fun parseInet4(host: String): Int? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        var value = 0
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet
        }
        return value
    }

    private fun inCidr(ip: Int, rule: String): Boolean {
        val slash = rule.indexOf('/')
        if (slash <= 0) return false
        val base = parseInet4(rule.substring(0, slash).trim()) ?: return false
        val prefix = rule.substring(slash + 1).trim().toIntOrNull() ?: return false
        if (prefix !in 0..32) return false
        val mask = if (prefix == 0) 0 else -(1 shl (32 - prefix))
        return (ip and mask) == (base and mask)
    }
}

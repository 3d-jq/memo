package com.psyche.memo.common.logging

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 1:1 port of lib/core/services/logging/log_redactor.dart.
 *
 * Strips API keys / tokens / signatures / passwords / user-info from strings
 * (headers, URLs, bodies, free text) before they are written to a log file. The
 * redactor is intentionally conservative — false positives (over-redacting) are
 * preferred over leaking secrets.
 *
 * All entry points are pure functions; safe to call from any thread.
 */
object LogRedactor {

    private const val MAX_JSON_BODY_CHARS = 256 * 1024
    private const val SHORT_SECRET_LIMIT = 16

    private val EXACT_SENSITIVE_HEADERS = setOf(
        "authorization",
        "proxy-authorization",
        "x-api-key",
        "api-key",
        "x-goog-api-key",
        "x-subscription-token",
        "x-auth-token",
        "x-goog-iam-authorization-token",
        "cookie",
        "set-cookie",
    )

    private val SENSITIVE_NAME_NEEDLES = listOf(
        "key", "token", "secret", "auth", "credential", "password", "signature", "session",
    )

    private val BODY_IGNORED_NAMES = setOf(
        "token", "tokens", "key", "keys", "session", "author", "authors",
    )

    private val BODY_STRONG_NEEDLES = listOf(
        "secret", "password", "credential", "signature",
    )

    private val SENSITIVE_QUERY_KEYS = setOf(
        "key", "api_key", "apikey", "access_token", "token", "sig",
        "signature", "password", "x-amz-signature", "x-amz-credential",
    )

    private val SCHEME_RE = Regex("""^(Bearer|Basic|Token)(\s+)(.*)$""", RegexOption.IGNORE_CASE)
    private val ALREADY_MASKED_RE = Regex("""^(?:\*\*\*\(len=\d+\)|.{3}\*\*\*.{4}\(len=\d+\))$""")
    private val USER_INFO_RE = Regex("""(://)([^/@\s?#&]+)@""")
    private val URL_QUERY_RE = Regex(
        """([?&](?:x-amz-signature|x-amz-credential|access_token|api_key|apikey|password|signature|token|key|sig)=)([^&\s]*)""",
        RegexOption.IGNORE_CASE,
    )
    private val BODY_STRING_FIELD_RE = Regex("""("([^"]+)"\s*:\s*")([^"]*)(")""")
    private val KNOWN_PREFIX_RE = Regex("""(?<![A-Za-z0-9_\-])(?:sk-ant-|sk-|AIza|xai-|gsk_|hf_|AKIA)[A-Za-z0-9_\-]+""")
    private val CAMEL_CASE_RE = Regex("""(?<=[a-z0-9])(?=[A-Z])""")
    private val NAME_SEP_RE = Regex("""[-_.\s]+""")

    /** Returns a new headers map with sensitive values replaced by masked form. */
    fun redactHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (name, value) ->
            if (isSensitiveName(name)) maskSecret(value) else value
        }

    /**
     * Redacts the user-info segment and sensitive query params (key=xxx,
     * signature=xxx, …) in [url]. Falls back to regex on parse failure.
     */
    fun redactUrl(url: String): String {
        val stripped = redactUserInfo(url)
        val uri = runCatching { java.net.URI(stripped) }.getOrNull()
        if (uri == null) return redactUrlByRegex(stripped)
        val queryKeys = uri.query
            ?.split('&')
            ?.mapNotNull { kv ->
                val eq = kv.indexOf('=')
                if (eq <= 0) null else kv.substring(0, eq).lowercase()
            }
            .orEmpty()
        if (queryKeys.isEmpty()) return stripped
        val hasSensitive = queryKeys.any { it in SENSITIVE_QUERY_KEYS }
        return if (hasSensitive) redactUrlByRegex(stripped) else stripped
    }

    /**
     * Redacts a JSON body. If the body parses as JSON and is < [MAX_JSON_BODY_CHARS]
     * characters, walks the tree and masks string leaves whose key is sensitive.
     * Otherwise falls back to a regex that scans `"name": "value"` pairs.
     */
    fun redactBody(body: String): String {
        if (body.length < MAX_JSON_BODY_CHARS) {
            try {
                val decoded = JSON_PARSER.parseToJsonElement(body)
                val walker = JsonWalker()
                val redacted = walker.walk(decoded)
                val encoded = if (walker.changed) redacted.toString() else body
                return redactKnownPrefixes(encoded)
            } catch (_: Exception) {
                // not JSON or malformed; fall through to regex pass
            }
        }
        return redactKnownPrefixes(redactBodyKeys(body))
    }

    /** Redacts free text: known API key prefixes + URL user-info + sensitive query params. */
    fun redactText(text: String): String =
        redactKnownPrefixes(redactUrlByRegex(redactUserInfo(text)))

    /**
     * Masks a raw secret value. Keeps `Bearer / Basic / Token` schemes, masks the
     * payload. Already-masked inputs are returned unchanged.
     */
    fun maskSecret(value: String): String {
        val scheme = SCHEME_RE.find(value)
        return if (scheme != null) {
            "${scheme.groupValues[1]}${scheme.groupValues[2]}${maskRaw(scheme.groupValues[3])}"
        } else {
            maskRaw(value)
        }
    }

    private fun maskRaw(value: String): String {
        if (ALREADY_MASKED_RE.matches(value)) return value
        if (value.length < SHORT_SECRET_LIMIT) return "***(len=${value.length})"
        return "${value.substring(0, 3)}***${value.substring(value.length - 4)}(len=${value.length})"
    }

    private fun isSensitiveName(name: String): Boolean {
        val lower = name.lowercase()
        if (lower in EXACT_SENSITIVE_HEADERS) return true
        for (needle in SENSITIVE_NAME_NEEDLES) {
            if (lower.contains(needle)) return true
        }
        return false
    }

    private fun isSensitiveBodyName(name: String): Boolean {
        val lower = name.lowercase()
        if (lower in EXACT_SENSITIVE_HEADERS) return true
        if (lower in BODY_IGNORED_NAMES) return false
        if (lower == "apikey") return true
        for (needle in BODY_STRONG_NEEDLES) {
            if (lower.contains(needle)) return true
        }
        for (part in splitNameParts(name)) {
            if (part.isEmpty()) continue
            val word = part.lowercase()
            if (word == "key" || word == "token") return true
            if (word in EXACT_SENSITIVE_HEADERS) return true
        }
        return false
    }

    private fun splitNameParts(name: String): List<String> =
        name.split(CAMEL_CASE_RE).flatMap { it.split(NAME_SEP_RE) }

    private fun redactUserInfo(text: String): String =
        USER_INFO_RE.replace(text) { it.groupValues[1] }

    private fun redactUrlByRegex(text: String): String =
        URL_QUERY_RE.replace(text) { "${it.groupValues[1]}${maskSecret(it.groupValues[2])}" }

    private fun redactBodyKeys(body: String): String =
        BODY_STRING_FIELD_RE.replace(body) { m ->
            val name = m.groupValues[2]
            if (!isSensitiveBodyName(name)) m.value
            else "${m.groupValues[1]}${maskSecret(m.groupValues[3])}${m.groupValues[4]}"
        }

    private fun redactKnownPrefixes(text: String): String =
        KNOWN_PREFIX_RE.replace(text) { maskSecret(it.value) }

    private val JSON_PARSER = Json { ignoreUnknownKeys = true }

    /** Walks a parsed JSON tree, redacting string leaves whose key is sensitive. */
    private class JsonWalker {
        var changed: Boolean = false
            private set

        fun walk(value: JsonElement, key: String? = null): JsonElement {
            if (value is JsonObject) {
                val newEntries = value.entries.map { (k, v) -> k to walk(v, k) }
                if (newEntries.any { (k, v) -> v !== value[k] }) {
                    changed = true
                    return JsonObject(newEntries.associate { it })
                }
                return value
            }
            if (value is JsonArray) {
                val newItems = value.map { walk(it, key) }
                if (newItems.withIndex().any { (i, v) -> v !== value[i] }) {
                    changed = true
                    return JsonArray(newItems)
                }
                return value
            }
            if (value is JsonPrimitive && value.isString && key != null) {
                if (isSensitiveBodyName(key)) {
                    val masked = maskSecret(value.content)
                    if (masked != value.content) {
                        changed = true
                        return JsonPrimitive(masked)
                    }
                }
            }
            return value
        }
    }
}

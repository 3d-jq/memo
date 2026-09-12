package com.psyche.memo.data.backup

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.net.URI
import java.time.ZonedDateTime

/**
 * `S3Config` (`lib/core/models/backup.dart` L83-180) — the S3 backup target,
 * stored as the single preference key `s3_config_v1` (Flutter
 * `settings_provider.dart` L381). Field names and defaults mirror the Dart
 * JSON shape so a config copied between the two apps round-trips.
 */
data class S3Config(
    val endpoint: String = "",
    val region: String = "us-east-1",
    val bucket: String = "",
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
    val sessionToken: String = "",
    val prefix: String = DEFAULT_PREFIX,
    val pathStyle: Boolean = true,
    val userAgent: String = "",
    val includeChats: Boolean = true,
    val includeFiles: Boolean = true,
) {
    companion object {
        /**
         * The upstream default prefix is `kelivo_backups`; ours is
         * brand-renamed (a user-visible object-key prefix). The remote prefix
         * is per-device state — nothing is format-compatible here.
         */
        const val DEFAULT_PREFIX = "memo_backups"

        /** Preference key holding the JSON form (Flutter `_s3ConfigKey`). */
        const val PREF_KEY = "s3_config_v1"

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

        private fun JsonObject.bool(key: String, fallback: Boolean): Boolean =
            (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toBooleanStrictOrNull()
                ?: fallback

        /** `S3Config.fromJson` — trims, and falls back on blank region/prefix. */
        fun fromJson(obj: JsonObject): S3Config = S3Config(
            endpoint = obj.str("endpoint")?.trim().orEmpty(),
            region = obj.str("region")?.trim().orEmpty().ifEmpty { "us-east-1" },
            bucket = obj.str("bucket")?.trim().orEmpty(),
            accessKeyId = obj.str("accessKeyId")?.trim().orEmpty(),
            secretAccessKey = obj.str("secretAccessKey").orEmpty(),
            sessionToken = obj.str("sessionToken").orEmpty(),
            prefix = obj.str("prefix")?.trim().orEmpty().ifEmpty { DEFAULT_PREFIX },
            pathStyle = obj.bool("pathStyle", true),
            userAgent = obj.str("userAgent").orEmpty(),
            includeChats = obj.bool("includeChats", true),
            includeFiles = obj.bool("includeFiles", true),
        )

        /** Lenient parse of the stored JSON string; garbage yields defaults. */
        fun fromJsonString(raw: String?): S3Config {
            if (raw.isNullOrBlank()) return S3Config()
            return runCatching {
                fromJson(
                    kotlinx.serialization.json.Json.parseToJsonElement(raw) as JsonObject,
                )
            }.getOrDefault(S3Config())
        }
    }

    fun toJson(): JsonObject = buildJsonObject {
        fun field(name: String, value: String) = put(name, JsonPrimitive(value))
        field("endpoint", endpoint)
        field("region", region)
        field("bucket", bucket)
        field("accessKeyId", accessKeyId)
        field("secretAccessKey", secretAccessKey)
        field("sessionToken", sessionToken)
        field("prefix", prefix)
        put("pathStyle", JsonPrimitive(pathStyle))
        field("userAgent", userAgent)
        put("includeChats", JsonPrimitive(includeChats))
        put("includeFiles", JsonPrimitive(includeFiles))
    }

    /** `_validateConfigBasics` — throws with the first missing field. */
    fun validate() {
        if (endpoint.trim().isEmpty()) throw S3Exception("S3 endpoint is required")
        if (region.trim().isEmpty()) throw S3Exception("S3 region is required")
        if (bucket.trim().isEmpty()) throw S3Exception("S3 bucket is required")
        if (accessKeyId.trim().isEmpty()) throw S3Exception("S3 accessKeyId is required")
        if (secretAccessKey.isEmpty()) throw S3Exception("S3 secretAccessKey is required")
    }

    /** `_normalizeEndpoint` — allow a bare host, trim, keep the scheme. */
    internal fun normalizedEndpoint(): URI {
        var raw = endpoint.trim()
        if (raw.isEmpty()) throw S3Exception("S3 endpoint is empty")
        if (!raw.contains("://")) raw = "https://$raw"
        return URI(raw)
    }

    internal fun isHttps(): Boolean = normalizedEndpoint().scheme == "https"

    /**
     * Endpoint path segments, minus a trailing segment that repeats the bucket
     * — `_normalizedBasePathSegments`. Some users paste a full bucket URL as
     * the endpoint; dropping the duplicate keeps path-style keys correct.
     */
    internal fun basePathSegments(): List<String> {
        val uri = normalizedEndpoint()
        val segments = (uri.path ?: "").split('/').map { it.trim() }.filter { it.isNotEmpty() }
        val bucketName = bucket.trim()
        if (!pathStyle || bucketName.isEmpty() || segments.isEmpty()) return segments
        return if (segments.last() == bucketName) segments.dropLast(1) else segments
    }

    /**
     * Host header value — path-style keeps the endpoint host, vhost style
     * prefixes the bucket. Mirrors `_hostHeader`: a port equal to the scheme
     * default (443/80) is omitted, because that is what HTTP clients —
     * OkHttp included — actually put on the wire, and the signed Host must
     * match byte-for-byte or S3 answers `SignatureDoesNotMatch`.
     */
    internal fun hostHeader(): String {
        val uri = normalizedEndpoint()
        val base = uri.host ?: throw S3Exception("S3 endpoint is not a valid URL")
        val scheme = uri.scheme.orEmpty().lowercase()
        val port = uri.port
        val isDefaultPort = (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
        val withPort = if (port > 0 && !isDefaultPort) "$base:$port" else base
        return if (pathStyle) withPort else "${bucket.trim()}.$withPort"
    }
}

/** One listed remote archive (`BackupFileItem`), keyed by its S3 object key. */
data class S3FileItem(
    val key: String,
    val displayName: String,
    val size: Long,
    val lastModified: ZonedDateTime?,
)

class S3Exception(message: String, val statusCode: Int = 0, val responseBody: String = "") :
    Exception(message)

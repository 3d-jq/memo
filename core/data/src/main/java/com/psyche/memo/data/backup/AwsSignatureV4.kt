package com.psyche.memo.data.backup

import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 signer for single-request S3 calls — the native twin
 * of `S3BackupClient._sendSigned` / `_sendSignedStreamedFile`
 * (`lib/core/services/backup/s3_client.dart` L244-543), with the request shape
 * following RikkaHub's `AwsSignatureV4` (same AGPL project family).
 *
 * Only what the backup flow needs is implemented: header-signed GET/PUT/DELETE
 * and paginated bucket listing. Chunked (streaming) signatures are not used —
 * large uploads sign with [UNSIGNED_PAYLOAD], which S3 accepts for a plain PUT
 * over HTTPS and avoids hashing a multi-hundred-MB archive.
 *
 * The clock is a parameter so the AWS test vector can pin a timestamp.
 */
internal object AwsSignatureV4 {

    const val ALGORITHM = "AWS4-HMAC-SHA256"

    /** Streaming uploads sign the literal string `UNSIGNED-PAYLOAD`. */
    const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"

    const val EMPTY_PAYLOAD_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    private const val SERVICE = "s3"

    private val DATE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    private const val HEX = "0123456789abcdef"

    /**
     * Percent-encoding uses **uppercase** hex digits (AWS "UriEncode", and
     * what Dart's `Uri.encodeComponent` / Java's `URLEncoder` emit); the
     * signature itself is lowercase hex. Mixing these up produces a valid
     * looking request that S3 rejects with `SignatureDoesNotMatch`.
     */
    private const val PERCENT_HEX = "0123456789ABCDEF"

    data class SignedRequest(
        val url: String,
        val headers: Map<String, String>,
    )

    /**
     * @param canonicalPath percent-encoded path including the bucket for
     *   path-style endpoints (no query string).
     * @param query query parameters, **unencoded** — they are canonicalized and
     *   encoded here.
     * @param extraHeaders additional signed headers (`content-type`, …).
     * @param payloadHash hex SHA-256 of the body, or [UNSIGNED_PAYLOAD].
     */
    fun sign(
        config: S3Config,
        method: String,
        canonicalPath: String,
        query: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
        payloadHash: String,
        contentLength: Long? = null,
        contentMd5: String? = null,
        at: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC),
    ): SignedRequest {
        val utc = at.withZoneSameInstant(ZoneOffset.UTC)
        val dateStamp = utc.format(DATE_STAMP)
        val amzDate = utc.format(AMZ_DATE)

        val host = config.hostHeader()
        val allHeaders = LinkedHashMap<String, String>()
        allHeaders["host"] = host
        allHeaders["x-amz-content-sha256"] = payloadHash
        allHeaders["x-amz-date"] = amzDate
        contentLength?.let { allHeaders["content-length"] = it.toString() }
        contentMd5?.let { allHeaders["content-md5"] = it }
        if (config.sessionToken.trim().isNotEmpty()) {
            allHeaders["x-amz-security-token"] = config.sessionToken.trim()
        }
        if (config.userAgent.trim().isNotEmpty()) {
            allHeaders["user-agent"] = config.userAgent.trim()
        }
        // Lower-case and let explicit headers win (Flutter spreads `...?headers`
        // last, so `content-type` lands after the defaults).
        extraHeaders.forEach { (k, v) -> allHeaders[k.lowercase().trim()] = v }

        val canonicalQuery = canonicalQueryString(query)
        val signedHeaders = allHeaders.keys.sorted().joinToString(";")
        val canonicalHeaders = allHeaders.entries
            .sortedBy { it.key }
            .joinToString("") { (k, v) -> "$k:${v.trim().replace(Regex("\\s+"), " ")}\n" }

        val canonicalRequest = buildString {
            append(method).append('\n')
            append(canonicalPath).append('\n')
            append(canonicalQuery).append('\n')
            append(canonicalHeaders).append('\n')
            append(signedHeaders).append('\n')
            append(payloadHash)
        }

        val scope = "$dateStamp/${config.region.trim()}/$SERVICE/aws4_request"
        val stringToSign = "$ALGORITHM\n$amzDate\n$scope\n${sha256Hex(canonicalRequest)}"
        val signature = hex(
            hmacSha256(signingKey(config.secretAccessKey, dateStamp, config.region.trim()), stringToSign),
        )
        val authorization =
            "$ALGORITHM Credential=${config.accessKeyId.trim()}/$scope, " +
                "SignedHeaders=$signedHeaders, Signature=$signature"

        val scheme = if (config.isHttps()) "https" else "http"
        val url = buildString {
            append(scheme).append("://").append(host).append(canonicalPath)
            if (canonicalQuery.isNotEmpty()) append('?').append(canonicalQuery)
        }

        return SignedRequest(
            url = url,
            headers = LinkedHashMap(allHeaders).apply { put("authorization", authorization) },
        )
    }

    // ── primitives ────────────────────────────────────────────────────────────

    /**
     * AWS UriEncode: RFC 3986 with `A-Za-z0-9-_.~` unreserved. Note this
     * encodes `/` as `%2F`, so callers encode path *segments*, never a whole
     * path. (Dart's `Uri.encodeComponent` leaves `!*'()` literal, which is not
     * RFC 3986 — this is deliberately stricter.)
     */
    fun awsEncode(value: String): String {
        val out = StringBuilder(value.length * 3)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xFF
            val unreserved = c in 'A'.code..'Z'.code ||
                c in 'a'.code..'z'.code ||
                c in '0'.code..'9'.code ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
            if (unreserved) {
                out.append(c.toChar())
            } else {
                out.append('%').append(PERCENT_HEX[c ushr 4]).append(PERCENT_HEX[c and 0x0F])
            }
        }
        return out.toString()
    }

    /**
     * `_canonicalQuery` — pairs sorted by encoded key then encoded value, so
     * the signature matches regardless of map iteration order.
     */
    fun canonicalQueryString(query: Map<String, String>): String =
        query.entries
            .map { awsEncode(it.key) to awsEncode(it.value) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }

    private fun signingKey(secretAccessKey: String, dateStamp: String, region: String): ByteArray {
        val kDate = hmacSha256("AWS4$secretAccessKey".toByteArray(Charsets.UTF_8), dateStamp)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, SERVICE)
        return hmacSha256(kService, "aws4_request")
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    fun sha256Hex(value: String): String = sha256Hex(value.toByteArray(Charsets.UTF_8))

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return hex(digest.digest(bytes))
    }

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            out.append(HEX[c ushr 4]).append(HEX[c and 0x0F])
        }
        return out.toString()
    }
}

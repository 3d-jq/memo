package com.psyche.memo.data.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * S3 backup client — the native twin of `S3BackupClient`
 * (`lib/core/services/backup/s3_client.dart`, 1144 lines). That file is the
 * source of truth for *semantics*; the request shape follows RikkaHub's
 * `S3Client`/`AwsSignatureV4` (same project family, AGPL-3.0), except we use
 * the container's OkHttp instead of Ktor.
 *
 * Ported behaviour worth calling out:
 * - **Object keys are the identity.** A remote archive is addressed by its S3
 *   key; `displayName`/`size`/`lastModified` are presentation.
 * - **The manifest is a cache, not the source of truth.** [list] reads the
 *   manifest *and* the paginated bucket listing, merges them (the listing wins
 *   when it succeeded), then rewrites the manifest only if it changed.
 * - **Streamed uploads sign `UNSIGNED-PAYLOAD`.** Hashing a multi-hundred-MB
 *   archive before the PUT defeats the point of streaming, and S3 accepts an
 *   unsigned payload for a plain PUT over HTTPS.
 * - **A cancelled upload deletes its partial object.**
 * - **Path-style endpoints repeat the bucket** in the canonical path; virtual
 *   host style prefixes the bucket onto the Host instead.
 */
class S3Client(
    private val httpClient: OkHttpClient,
    private val config: S3Config,
) {

    companion object {
        /**
         * The upstream manifest object is `.kelivo_backups_manifest.json`.
         * Ours is brand-renamed (the object name is visible in the user's
         * bucket). It is a cache only — losing it costs one extra bucket
         * listing, and [list] rewrites it from the authoritative listing.
         */
        const val MANIFEST_OBJECT_NAME = ".memo_backups_manifest.json"

        const val MANIFEST_CONTENT_TYPE = "application/json"
        const val ARCHIVE_CONTENT_TYPE = "application/zip"

        private const val MAX_KEYS = 1000
        private const val BUFFER_SIZE = 64 * 1024
        private const val ERROR_SNIPPET = 4096L

        private val JSON_MEDIA = MANIFEST_CONTENT_TYPE.toMediaType()
    }

    /**
     * A fully-buffered response. Everything except the streamed download only
     * needs the status and the (small) error document, so buffering here keeps
     * the call sites free of `Response` lifetime management.
     */
    data class ApiResponse(
        val code: Int,
        val body: String,
        val regionHint: String,
    ) {
        val isSuccess: Boolean get() = code in 200..299

        /** `_extractErrorMessage` — `Code - Message - Bucket region`, else `HTTP n`. */
        fun errorSummary(): String {
            val errorCode = s3ErrorCode(body)
            val errorMessage = s3ErrorMessage(body)
            val parts = buildList {
                if (errorCode.isNotEmpty()) add(errorCode)
                if (errorMessage.isNotEmpty()) add(errorMessage)
                if (regionHint.isNotEmpty()) add("Bucket region: $regionHint")
            }
            // The Dart original has an extra `if (regionHint.isNotEmpty) return
            // "HTTP $code. Bucket region: …"` after this line, but `parts`
            // already contains the hint, so that branch can never be reached —
            // dropped here rather than mirrored as dead code.
            if (parts.isNotEmpty()) return parts.joinToString(" - ")
            return "HTTP $code"
        }

        /** `_isMissingObjectResponse` — 404, or an S3 error document with `NoSuchKey`. */
        val isMissingObject: Boolean
            get() = code == 404 || s3ErrorCode(body) == "NoSuchKey"
    }

    // ── key / path layout ─────────────────────────────────────────────────────

    /** `_normalizePrefix` — strip leading slashes, guarantee a trailing one. */
    internal fun normalizePrefix(prefix: String = config.prefix): String {
        var value = prefix.trim().trimStart('/')
        if (value.isEmpty()) return ""
        if (!value.endsWith('/')) value = "$value/"
        return value
    }

    /** `_manifestKey` — the prefix plus the manifest object name. */
    internal fun manifestKey(): String = normalizePrefix() + MANIFEST_OBJECT_NAME

    /** Encoded canonical path of the bucket root (used by the listing). */
    internal fun bucketPath(): String = encodePath(pathBase())

    /** Encoded canonical path of one object. */
    internal fun objectPath(key: String): String {
        val segments = pathBase().toMutableList()
        segments += key.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        return encodePath(segments)
    }

    /** Endpoint base segments, plus the bucket for path-style endpoints. */
    private fun pathBase(): List<String> =
        config.basePathSegments().toMutableList().apply {
            if (config.pathStyle) add(config.bucket.trim())
        }

    /** AWS UriEncode is applied per segment, so `/` stays a separator. */
    private fun encodePath(segments: List<String>): String {
        val encoded = segments.filter { it.isNotEmpty() }
            .joinToString("/") { AwsSignatureV4.awsEncode(it) }
        return if (encoded.isEmpty()) "/" else "/$encoded"
    }

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * `S3BackupClient.test` — the manifest is the cheaper probe (a 200 or a
     * "no such object" both prove the credentials); a fresh bucket with no
     * manifest still validates through a `max-keys=1` listing.
     */
    fun test() {
        config.validate()
        val manifest = call(
            method = "GET",
            canonicalPath = objectPath(manifestKey()),
            extraHeaders = mapOf("accept" to "application/json"),
        )
        if (manifest.code == 200 || manifest.isMissingObject) return

        val listing = callBucketList(
            mapOf("list-type" to "2", "prefix" to normalizePrefix(), "max-keys" to "1"),
        )
        if (!listing.isSuccess) {
            throw S3Exception("S3 test failed: ${listing.errorSummary()}", listing.code, listing.body)
        }
    }

    /**
     * `uploadFile` — streamed PUT of [file] under [key], then mirror it into
     * the manifest. A cancelled upload removes the partial remote object.
     */
    fun upload(
        file: File,
        key: String = normalizePrefix() + file.name,
        contentType: String = ARCHIVE_CONTENT_TYPE,
        onProgress: (processed: Long, total: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ) {
        config.validate()
        val length = file.length()
        try {
            val response = send(
                method = "PUT",
                canonicalPath = objectPath(key),
                payloadHash = AwsSignatureV4.UNSIGNED_PAYLOAD,
                contentLength = length,
                extraHeaders = mapOf("content-type" to contentType),
                body = streamedFileBody(file, contentType, length) { processed ->
                    onProgress(processed, length)
                },
            )
            response.use { res ->
                if (isCancelled()) throw BackupCancelledException()
                if (!res.isSuccessful) {
                    val summary = readError(res)
                    throw S3Exception("S3 upload failed: $summary", res.code)
                }
            }
        } catch (error: Throwable) {
            if (error is BackupCancelledException || isCancelled()) {
                // A cancelled upload must not leave a partial remote object.
                deleteQuietly(key)
                throw BackupCancelledException()
            }
            throw error
        }
        if (isCancelled()) {
            deleteQuietly(key)
            throw BackupCancelledException()
        }
        upsertManifestItem(key = key, size = length, lastModified = ZonedDateTime.now(ZoneOffset.UTC))
    }

    /** `uploadObject` — buffered PUT; used for the manifest itself. */
    fun uploadObject(key: String, bytes: ByteArray, contentType: String) {
        config.validate()
        val response = call(
            method = "PUT",
            canonicalPath = objectPath(key),
            payloadHash = AwsSignatureV4.sha256Hex(bytes),
            contentLength = bytes.size.toLong(),
            extraHeaders = mapOf("content-type" to contentType),
            body = bytes.toRequestBody(contentType.toMediaType()),
        )
        if (!response.isSuccess) {
            throw S3Exception("S3 upload failed: ${response.errorSummary()}", response.code, response.body)
        }
    }

    /**
     * `downloadToFile` — streamed GET into [destination]. [expectedSize] (the
     * listed size) drives the progress denominator when the response carries no
     * Content-Length of its own.
     */
    fun download(
        key: String,
        destination: File,
        expectedSize: Long? = null,
        onProgress: (processed: Long, total: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ) {
        config.validate()
        val response = send(method = "GET", canonicalPath = objectPath(key))
        response.use { res ->
            if (!res.isSuccessful) {
                val summary = readError(res)
                throw S3Exception("S3 download failed: $summary", res.code)
            }
            val body = res.body ?: throw S3Exception("S3 download failed: empty body", res.code)
            val total = if (expectedSize != null && expectedSize > 0) expectedSize else body.contentLength()
            var written = 0L
            body.byteStream().use { input ->
                destination.outputStream().buffered(BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (isCancelled()) throw BackupCancelledException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) onProgress(written, total)
                    }
                }
            }
        }
    }

    /** `deleteObject` — DELETE the object, then drop it from the manifest. */
    fun delete(key: String) {
        config.validate()
        val response = call(method = "DELETE", canonicalPath = objectPath(key))
        if (!response.isSuccess) {
            throw S3Exception("S3 delete failed: ${response.errorSummary()}", response.code, response.body)
        }
        removeManifestItem(key)
    }

    /** `_deleteRemoteQuietly` — best-effort cleanup of a cancelled upload. */
    internal fun deleteQuietly(key: String) {
        runCatching {
            call(method = "DELETE", canonicalPath = objectPath(key))
        }
    }

    /**
     * `listObjects` — reconcile the manifest with the paginated bucket listing
     * and return the merged, newest-first archive list.
     *
     * The listing is authoritative when it succeeds (it sees objects the
     * manifest may not know about), and then the manifest is rewritten from it.
     * When the listing fails but the manifest parsed, the stale manifest still
     * wins over showing the user nothing.
     */
    fun list(): List<S3FileItem> {
        config.validate()
        var manifestItems: List<S3FileItem> = emptyList()
        var manifestExists = false
        var manifestError: Throwable? = null
        try {
            val manifest = readManifest()
            if (manifest != null) {
                manifestItems = manifest
                manifestExists = true
            }
        } catch (error: BackupCancelledException) {
            throw error
        } catch (error: Throwable) {
            manifestError = error
        }

        var bucketItems: List<S3FileItem> = emptyList()
        var bucketError: Throwable? = null
        var bucketListSucceeded = false
        try {
            bucketItems = listBucketObjects()
            bucketListSucceeded = true
        } catch (error: BackupCancelledException) {
            throw error
        } catch (error: Throwable) {
            bucketError = error
        }

        val merged = mergeBackupItems(manifestItems, bucketItems, bucketIsAuthoritative = bucketListSucceeded)
        if (bucketListSucceeded) {
            writeManifestIfChanged(manifestExists, manifestItems, merged)
            if (merged.isNotEmpty() || manifestError == null) return merged
            throw manifestError
        }
        if (merged.isNotEmpty()) return merged
        manifestError?.let { throw it }
        bucketError?.let { throw it }
        return emptyList()
    }

    // ── manifest ──────────────────────────────────────────────────────────────

    /** `_readManifest` — null when the object does not exist yet. */
    internal fun readManifest(): List<S3FileItem>? {
        val response = call(
            method = "GET",
            canonicalPath = objectPath(manifestKey()),
            extraHeaders = mapOf("accept" to "application/json"),
        )
        if (response.isMissingObject) return null
        if (response.code != 200) {
            throw S3Exception("S3 manifest read failed: ${response.errorSummary()}", response.code, response.body)
        }
        return decodeManifest(response.body)
    }

    /** `_writeManifest` — `{version: 1, items: [...]}`. */
    internal fun writeManifest(items: List<S3FileItem>) {
        val payload = encodeManifest(items).toByteArray(Charsets.UTF_8)
        val response = call(
            method = "PUT",
            canonicalPath = objectPath(manifestKey()),
            payloadHash = AwsSignatureV4.sha256Hex(payload),
            contentLength = payload.size.toLong(),
            extraHeaders = mapOf("content-type" to MANIFEST_CONTENT_TYPE),
            body = payload.toRequestBody(JSON_MEDIA),
        )
        if (!response.isSuccess) {
            throw S3Exception("S3 manifest write failed: ${response.errorSummary()}", response.code, response.body)
        }
    }

    /** `_upsertManifestItem` — the new entry first, any same-key entry replaced. */
    internal fun upsertManifestItem(key: String, size: Long, lastModified: ZonedDateTime) {
        val current = runCatching { readManifest() }.getOrNull().orEmpty()
        val next = buildList {
            add(S3FileItem(key = key, displayName = s3DisplayNameFromKey(key), size = size, lastModified = lastModified))
            addAll(current.filter { it.key != key })
        }
        writeManifest(next)
    }

    /** `_removeManifestItem`. */
    internal fun removeManifestItem(key: String) {
        val current = runCatching { readManifest() }.getOrNull() ?: return
        writeManifest(current.filter { it.key != key })
    }

    /** `_writeManifestIfChanged` — skip the write when nothing moved. */
    private fun writeManifestIfChanged(
        manifestExists: Boolean,
        current: List<S3FileItem>,
        reconciled: List<S3FileItem>,
    ) {
        if (!manifestExists || sameBackupItems(current, reconciled)) return
        writeManifest(reconciled)
    }

    // ── listing ───────────────────────────────────────────────────────────────

    /** `_listBucketObjects` — every page, `.zip` objects only. */
    internal fun listBucketObjects(): List<S3FileItem> {
        val prefix = normalizePrefix()
        val items = mutableListOf<S3FileItem>()
        var continuationToken: String? = null

        do {
            val query = LinkedHashMap<String, String>().apply {
                put("list-type", "2")
                if (prefix.isNotEmpty()) put("prefix", prefix)
                put("max-keys", MAX_KEYS.toString())
                continuationToken?.let { put("continuation-token", it) }
            }
            val page = callBucketList(query)
            if (!page.isSuccess) {
                throw S3Exception("S3 list failed: ${page.errorSummary()}", page.code, page.body)
            }
            val parsed = parseListBucket(page.body)
            items += parsed.objects
            continuationToken = parsed.nextContinuationToken
                .takeIf { parsed.isTruncated && it.isNotEmpty() }
        } while (continuationToken != null)

        return items
    }

    data class BucketPage(
        val objects: List<S3FileItem>,
        val isTruncated: Boolean,
        val nextContinuationToken: String,
    )

    /**
     * Parses a `ListBucketResult`. XmlPullParser (RikkaHub's approach) keeps
     * this namespace-agnostic — S3-compatible providers disagree on prefixes.
     * Public because its test lives in the app module (Robolectric supplies a
     * working XmlPullParser; plain JUnit only has the stub).
     */
    fun parseListBucket(xml: String): BucketPage {
        val objects = mutableListOf<S3FileItem>()
        var isTruncated = false
        var nextToken = ""

        var inContents = false
        var currentKey = ""
        var currentSize = 0L
        var currentModified: ZonedDateTime? = null
        var currentTag = ""

        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(StringReader(xml))
        }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    if (parser.name == "Contents") {
                        inContents = true
                        currentKey = ""
                        currentSize = 0L
                        currentModified = null
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        if (inContents) {
                            when (currentTag) {
                                "Key" -> currentKey = text
                                "Size" -> currentSize = text.toLongOrNull() ?: 0L
                                "LastModified" -> currentModified = parseS3DateTime(text)
                            }
                        } else {
                            when (currentTag) {
                                "IsTruncated" -> isTruncated = text.equals("true", ignoreCase = true)
                                "NextContinuationToken" -> nextToken = text
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "Contents") {
                        val name = s3DisplayNameFromKey(currentKey)
                        if (currentKey.isNotEmpty() && name.lowercase().endsWith(".zip")) {
                            objects += S3FileItem(
                                key = currentKey,
                                displayName = name,
                                size = currentSize,
                                lastModified = currentModified,
                            )
                        }
                        inContents = false
                    }
                    currentTag = ""
                }
            }
            event = parser.next()
        }
        return BucketPage(objects, isTruncated, nextToken)
    }

    // ── merge ─────────────────────────────────────────────────────────────────

    /**
     * `_mergeBackupItems` — key-wise union preferring the entry with the newer
     * timestamp (or a non-zero size when timestamps tie or are missing),
     * newest first.
     */
    internal fun mergeBackupItems(
        manifestItems: List<S3FileItem>,
        bucketItems: List<S3FileItem>,
        bucketIsAuthoritative: Boolean,
    ): List<S3FileItem> {
        val merged = LinkedHashMap<String, S3FileItem>()

        fun upsert(item: S3FileItem) {
            val current = merged[item.key]
            if (current == null) {
                merged[item.key] = item
                return
            }
            val currentTime = current.lastModified
            val nextTime = item.lastModified
            when {
                currentTime == null && nextTime != null -> merged[item.key] = item
                currentTime != null && nextTime != null && nextTime.isAfter(currentTime) ->
                    merged[item.key] = item

                current.size == 0L && item.size > 0L -> merged[item.key] = item
            }
        }

        if (!bucketIsAuthoritative) manifestItems.forEach(::upsert)
        bucketItems.forEach(::upsert)

        return merged.values.sortedByDescending {
            it.lastModified?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE
        }
    }

    /** `_sameBackupItems` — field-wise equality, order-sensitive. */
    internal fun sameBackupItems(a: List<S3FileItem>, b: List<S3FileItem>): Boolean {
        if (a.size != b.size) return false
        return a.indices.all { i -> sameBackupItem(a[i], b[i]) }
    }

    internal fun sameBackupItem(a: S3FileItem, b: S3FileItem): Boolean =
        a.key == b.key &&
            a.displayName == b.displayName &&
            a.size == b.size &&
            sameInstant(a.lastModified, b.lastModified)

    private fun sameInstant(a: ZonedDateTime?, b: ZonedDateTime?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return a.toInstant() == b.toInstant()
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    /**
     * Buffered call: the response body is read in full and closed. The body is
     * needed whole — a bucket listing is an XML document far larger than any
     * error snippet.
     */
    private fun call(
        method: String,
        canonicalPath: String,
        query: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
        payloadHash: String = AwsSignatureV4.EMPTY_PAYLOAD_SHA256,
        contentLength: Long? = null,
        body: RequestBody? = null,
    ): ApiResponse = send(
        method = method,
        canonicalPath = canonicalPath,
        query = query,
        extraHeaders = extraHeaders,
        payloadHash = payloadHash,
        contentLength = contentLength,
        body = body,
    ).use { response ->
        ApiResponse(
            code = response.code,
            body = runCatching { response.body?.string() }.getOrNull().orEmpty(),
            regionHint = response.header("x-amz-bucket-region").orEmpty(),
        )
    }

    /**
     * `_sendSignedBucketListRequest` — some S3-compatible providers answer the
     * bucket root only with a trailing slash, so that shape is retried on a
     * 404. Non-200 responses are returned as-is; the caller decides whether it
     * was a connection test or a real listing.
     */
    private fun callBucketList(query: Map<String, String>): ApiResponse {
        val path = bucketPath()
        val first = call(
            method = "GET",
            canonicalPath = path,
            query = query,
            extraHeaders = mapOf("accept" to "application/xml"),
        )
        if (first.isSuccess || path.endsWith("/") || first.code != 404) return first
        return call(
            method = "GET",
            canonicalPath = "$path/",
            query = query,
            extraHeaders = mapOf("accept" to "application/xml"),
        )
    }

    /** Raw signed request — the caller owns the response (and must close it). */
    private fun send(
        method: String,
        canonicalPath: String,
        query: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
        payloadHash: String = AwsSignatureV4.EMPTY_PAYLOAD_SHA256,
        contentLength: Long? = null,
        body: RequestBody? = null,
    ): Response {
        val signed = AwsSignatureV4.sign(
            config = config,
            method = method,
            canonicalPath = canonicalPath,
            query = query,
            extraHeaders = extraHeaders,
            payloadHash = payloadHash,
            contentLength = contentLength,
        )
        val builder = Request.Builder().url(signed.url).method(method, body)
        signed.headers.forEach { (name, value) ->
            // `host` comes from the URL and `content-length` from the body —
            // OkHttp emits both, and the signature uses the same values.
            // Setting them by hand would duplicate the header, which S3
            // rejects outright.
            if (name == "host" || name == "content-length") return@forEach
            builder.header(name, value)
        }
        return httpClient.newCall(builder.build()).execute()
    }

    /** Reads an error document from a streaming response without consuming it. */
    private fun readError(response: Response): String =
        ApiResponse(
            code = response.code,
            body = runCatching { response.peekBody(ERROR_SNIPPET).string() }.getOrNull().orEmpty(),
            regionHint = response.header("x-amz-bucket-region").orEmpty(),
        ).errorSummary()

    private fun streamedFileBody(
        file: File,
        contentType: String,
        length: Long,
        onProgress: (Long) -> Unit,
    ): RequestBody = object : RequestBody() {
        override fun contentType() = contentType.toMediaType()
        override fun contentLength() = length
        override fun writeTo(sink: BufferedSink) {
            var written = 0L
            file.inputStream().buffered(BUFFER_SIZE).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    written += read
                    onProgress(written)
                }
            }
        }
    }
}

// ── manifest codec ────────────────────────────────────────────────────────────
// Pure, so the round-trip is unit-testable without a bucket.

/** `_displayNameFromKey` — the last non-empty key segment. */
internal fun s3DisplayNameFromKey(key: String): String =
    key.split('/').lastOrNull { it.isNotEmpty() } ?: key

/** `_writeManifest` payload — `{version: 1, items: [{key, displayName, size, lastModified}]}`. */
internal fun encodeManifest(items: List<S3FileItem>): String = buildJsonObject {
    put("version", JsonPrimitive(1))
    put(
        "items",
        buildJsonArray {
            items.forEach { item ->
                add(
                    buildJsonObject {
                        put("key", JsonPrimitive(item.key))
                        put("displayName", JsonPrimitive(item.displayName))
                        put("size", JsonPrimitive(item.size))
                        item.lastModified?.let {
                            put("lastModified", JsonPrimitive(it.toInstant().toString()))
                        }
                    },
                )
            }
        },
    )
}.toString()

/**
 * `_readManifest` parsing: keeps only `.zip` entries, fills a missing
 * `displayName` from the key, tolerates `size` as a number or a string, and
 * sorts newest first. Throws [S3Exception] on a malformed document (matching
 * the Dart messages so the UI text stays comparable).
 */
internal fun decodeManifest(text: String): List<S3FileItem> {
    val json = Json { ignoreUnknownKeys = true }
    val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        ?: throw S3Exception("S3 manifest read failed: invalid manifest format")
    val rawItems = runCatching { root["items"]?.jsonArray }.getOrNull()
        ?: throw S3Exception("S3 manifest read failed: invalid manifest items")
    return rawItems
        .mapNotNull { it as? JsonObject }
        .mapNotNull(::itemFromManifestEntry)
        .filter { it.key.lowercase().endsWith(".zip") }
        .sortedByDescending { it.lastModified?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE }
}

/** `_itemFromManifestEntry` — null when the key is missing/blank. */
private fun itemFromManifestEntry(entry: JsonObject): S3FileItem? {
    val key = (entry["key"] as? JsonPrimitive)?.content?.trim().orEmpty()
    if (key.isEmpty()) return null
    val declaredName = (entry["displayName"] as? JsonPrimitive)?.content?.trim()
    val rawSize = (entry["size"] as? JsonPrimitive)?.content
    val size = rawSize?.toLongOrNull() ?: rawSize?.toDoubleOrNull()?.toLong() ?: 0L
    return S3FileItem(
        key = key,
        displayName = declaredName?.takeIf { it.isNotEmpty() } ?: s3DisplayNameFromKey(key),
        size = size,
        lastModified = parseS3DateTime((entry["lastModified"] as? JsonPrimitive)?.content),
    )
}

/** `_parseDateTime` — tolerant ISO-8601 (with or without an offset). */
internal fun parseS3DateTime(raw: String?): ZonedDateTime? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    return runCatching { ZonedDateTime.parse(value) }
        .recoverCatching { Instant.parse(value).atZone(ZoneOffset.UTC) }
        .getOrNull()
}

// ── S3 error documents ────────────────────────────────────────────────────────
// File-level so the nested ApiResponse can use them regardless of class scope.
/** `_extractErrorCode` — the `<Code>` of an S3 error document. */
internal fun s3ErrorCode(xml: String): String = s3XmlTag(xml, "Code")

/** `_extractErrorMessage` — the `<Message>` of an S3 error document. */
internal fun s3ErrorMessage(xml: String): String = s3XmlTag(xml, "Message")

/** First non-blank text of [tag]; namespace-agnostic, never throws. */
private fun s3XmlTag(xml: String, tag: String): String {
    if (xml.isBlank()) return ""
    return runCatching {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(StringReader(xml))
        }
        var event = parser.eventType
        var seen = false
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> seen = parser.name == tag
                XmlPullParser.TEXT -> if (seen) {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty()) return@runCatching text
                }
            }
            event = parser.next()
        }
        ""
    }.getOrDefault("")
}

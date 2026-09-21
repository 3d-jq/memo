package com.psyche.memo.data.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSink
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** `WebDavConfig` (`core/models/backup.dart`) — stored as `webdav_config_v1`. */
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "memo_backups",
    val userAgent: String = "",
    /**
     * Backup content switches. The original keeps them on both remote configs
     * because the Backup-page toggles write WebDAV and S3 in one callback.
     */
    val includeChats: Boolean = true,
    val includeFiles: Boolean = true,
) {
    fun isValid(): Boolean = url.trim().isNotEmpty()

    companion object {
        /**
         * The upstream default path is `kelivo_backups`; ours is brand-renamed
         * (a user-visible default directory name). The remote directory is
         * per-device state, so nothing is format-compatible here.
         */
        const val DEFAULT_PATH = "memo_backups"

        fun fromJson(obj: JsonObject): WebDavConfig = WebDavConfig(
            url = obj.str("url").orEmpty(),
            username = obj.str("username").orEmpty(),
            password = obj.str("password").orEmpty(),
            path = obj.str("path").orEmpty().ifEmpty { DEFAULT_PATH },
            userAgent = obj.str("userAgent").orEmpty(),
            includeChats = obj.bool("includeChats", true),
            includeFiles = obj.bool("includeFiles", true),
        )

        private fun JsonObject.str(key: String): String? =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

        private fun JsonObject.bool(key: String, fallback: Boolean): Boolean =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                ?.content?.toBooleanStrictOrNull() ?: fallback
    }

    fun toJson(): JsonObject = kotlinx.serialization.json.buildJsonObject {
        fun field(name: String, raw: String) =
            put(name, kotlinx.serialization.json.JsonPrimitive(raw))
        field("url", url)
        field("username", username)
        field("password", password)
        field("path", path)
        field("userAgent", userAgent)
        put("includeChats", kotlinx.serialization.json.JsonPrimitive(includeChats))
        put("includeFiles", kotlinx.serialization.json.JsonPrimitive(includeFiles))
    }
}

/** One entry of a remote backup directory (`BackupFileItem`). */
data class WebDavFileItem(
    val href: String,
    val displayName: String,
    val size: Long,
    val lastModified: ZonedDateTime?,
)

class WebDavException(message: String, val statusCode: Int = 0) : Exception(message)

/**
 * WebDAV protocol client for the backup sub-block 5, porting
 * `DataSync`'s WebDAV helpers (`data_sync.dart` L325-470, L1408-1935):
 * collection URI with a trailing slash, per-segment PROPFIND→MKCOL creation,
 * depth-1 PROPFIND listing (207 Multi-Status accepted), streamed PUT/GET with
 * progress, and DELETE. The XML parsing follows RikkaHub's `WebDavClient`
 * (XmlPullParser over the multistatus, namespace-suffix matched).
 */
class WebDavClient(
    private val httpClient: OkHttpClient,
    private val config: WebDavConfig,
) {

    companion object {
        private val XML_MEDIA = "application/xml; charset=utf-8".toMediaType()
        private const val PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n" +
                "<d:propfind xmlns:d=\"DAV:\">\n" +
                "  <d:prop>\n" +
                "    <d:displayname/>\n" +
                "    <d:getcontentlength/>\n" +
                "    <d:getlastmodified/>\n" +
                "  </d:prop>\n" +
                "</d:propfind>"

        /**
         * Fallback mtime from the archive file name — the original matches
         * `kelivo_backup_...`; ours writes `memo_backup_...` with the same
         * stamp shape, so both spellings are recognized.
         */
        private val NAME_STAMP = Regex(
            "(?:memo|kelivo)_backup_(\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2}\\.\\d+)\\.zip",
        )
    }

    /** `_collectionUri` — base + path with a trailing slash. */
    fun collectionUrl(): String {
        var base = config.url.trim()
        while (base.endsWith("/")) base = base.substring(0, base.length - 1)
        val trimmedPath = config.path.trim().trim('/')
        val pathPart = if (trimmedPath.isEmpty()) "" else "/$trimmedPath"
        return "$base$pathPart/"
    }

    /** `_fileUri` — the collection URL plus a child name. */
    fun fileUrl(childName: String): String =
        collectionUrl() + childName.trimStart('/')

    private fun request(method: String, url: String, body: RequestBody? = null, depth: Int? = null): Request.Builder {
        val builder = Request.Builder().url(url).method(method, body)
        if (config.username.trim().isNotEmpty()) {
            val credential = okhttp3.Credentials.basic(config.username, config.password)
            builder.header("Authorization", credential)
        }
        if (config.userAgent.trim().isNotEmpty()) {
            builder.header("User-Agent", config.userAgent.trim())
        }
        depth?.let { builder.header("Depth", it.toString()) }
        return builder
    }

    private fun Response.bodyText(): String = runCatching { body?.string() }.getOrNull().orEmpty()

    // ── public API ────────────────────────────────────────────────────────────

    /** `testWebdav` — depth-1 PROPFIND on the collection; 2xx/207 is OK. */
    fun test() {
        httpClient.newCall(
            request("PROPFIND", collectionUrl(), PROPFIND_BODY.toRequestBodyXml(), depth = 1).build(),
        ).execute().use { response ->
            if (response.code != 207 && (response.code < 200 || response.code >= 300)) {
                throw WebDavException("WebDAV test failed: ${response.code}", response.code)
            }
        }
    }

    /**
     * `_ensureCollection` — walk every path segment, PROPFIND depth 0, create
     * missing levels with MKCOL (200/201/405 accepted, 401 = bad credentials).
     */
    fun ensureCollection() {
        val base = config.url.trim().trimEnd('/')
        val segments = config.path.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        var acc = base
        for (segment in segments) {
            acc = "$acc/$segment"
            val url = "$acc/"
            httpClient.newCall(
                request("PROPFIND", url, PROPFIND_BODY.toRequestBodyXml(), depth = 0).build(),
            ).execute().use { probe ->
                when {
                    probe.code == 404 -> {
                        httpClient.newCall(
                            request("MKCOL", url).build(),
                        ).execute().use { made ->
                            if (made.code != 201 && made.code != 200 && made.code != 405) {
                                throw WebDavException("MKCOL failed at $url: ${made.code}", made.code)
                            }
                        }
                    }
                    probe.code == 401 -> throw WebDavException("Unauthorized", 401)
                    probe.code != 207 && (probe.code < 200 || probe.code >= 400) ->
                        throw WebDavException("PROPFIND error at $url: ${probe.code}", probe.code)
                }
            }
        }
    }

    /**
     * `listBackupFiles` — depth-1 PROPFIND, skip the collection itself and
     * directories, fall back to the file-name stamp when the server gives no
     * mtime, newest first.
     */
    fun list(): List<WebDavFileItem> {
        ensureCollection()
        val base = collectionUrl()
        val response = httpClient.newCall(
            request("PROPFIND", base, PROPFIND_BODY.toRequestBodyXml(), depth = 1).build(),
        ).execute()
        if (response.code < 200 || response.code >= 300) {
            val code = response.code
            response.bodyText()
            throw WebDavException("PROPFIND failed: $code", code)
        }
        val body = response.bodyText()
        val items = mutableListOf<WebDavFileItem>()
        for (entry in parseMultistatus(body)) {
            val href = entry.href ?: continue
            val absolute = if (java.net.URI(href).isAbsolute) href else resolveHref(base, href)
            if (absolute == base) continue
            if (absolute.endsWith("/")) continue
            val name = entry.displayName ?: absolute.trimEnd('/').substringAfterLast('/')
            var mtime = entry.lastModified
            if (mtime == null) {
                NAME_STAMP.find(name)?.groupValues?.get(1)?.let { stamp ->
                    val iso = stamp.replace(Regex("T(\\d{2})-(\\d{2})-(\\d{2})"), "T$1:$2:$3")
                    mtime = runCatching {
                        java.time.LocalDateTime.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                            .atZone(java.time.ZoneId.systemDefault())
                    }.getOrNull()
                }
            }
            items.add(
                WebDavFileItem(
                    href = absolute,
                    displayName = name,
                    size = entry.size ?: 0L,
                    lastModified = mtime,
                ),
            )
        }
        return items.sortedWith(
            compareByDescending<WebDavFileItem> { it.lastModified?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE }
                .thenByDescending { it.displayName },
        )
    }

    /** `backupToWebDav` upload half: MKCOL-walk then a streamed PUT. */
    fun upload(file: File, contentType: String = "application/zip", onProgress: (processed: Long, total: Long) -> Unit = { _, _ -> }) {
        val url = fileUrl(file.name)
        val length = file.length()
        val body = object : RequestBody() {
            override fun contentType() = contentType.toMediaType()
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) {
                var written = 0L
                file.inputStream().buffered(DEFAULT_BUFFER_SIZE * 8).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        written += read
                        onProgress(written, length)
                    }
                }
            }
        }
        httpClient.newCall(
            request("PUT", url, body).header("Content-Type", contentType).build(),
        ).execute().use { response ->
            if (response.code < 200 || response.code >= 300) {
                throw WebDavException("Upload failed: ${response.code}", response.code)
            }
        }
    }

    /** `restoreFromWebDav` download half: a streamed GET into [target]. */
    fun download(item: WebDavFileItem, target: File, onProgress: (processed: Long, total: Long) -> Unit = { _, _ -> }) {
        httpClient.newCall(request("GET", item.href).build()).execute().use { response ->
            if (response.code < 200 || response.code >= 300) {
                throw WebDavException("Download failed: ${response.code}", response.code)
            }
            val known = if (item.size > 0) item.size else (response.body?.contentLength() ?: -1L)
            val source = response.body?.byteStream() ?: throw WebDavException("Download failed: empty body")
            var written = 0L
            target.outputStream().buffered(DEFAULT_BUFFER_SIZE * 8).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                    if (known > 0) onProgress(written, known)
                }
            }
        }
    }

    /** `deleteWebDavBackupFile`. */
    fun delete(item: WebDavFileItem) {
        httpClient.newCall(request("DELETE", item.href).build()).execute().use { response ->
            if (response.code < 200 || response.code >= 300) {
                throw WebDavException("Delete failed: ${response.code}", response.code)
            }
        }
    }

    /** `_deleteRemoteQuietly` — best-effort cleanup of a cancelled upload. */
    fun deleteQuietly(url: String) {
        runCatching {
            httpClient.newCall(request("DELETE", url).build()).execute().use { }
        }
    }

    // ── XML ───────────────────────────────────────────────────────────────────

    data class Entry(
        val href: String?,
        val displayName: String?,
        val size: Long?,
        val lastModified: ZonedDateTime?,
    )

    /** RikkaHub's XmlPullParser walk: namespace-suffix matched, per-response. */
    fun parseForTest(xml: String): List<Entry> = parseMultistatus(xml)

    private fun parseMultistatus(xml: String): List<Entry> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(java.io.StringReader(xml))
        val out = mutableListOf<Entry>()
        var href: String? = null
        var displayName: String? = null
        var size: Long? = null
        var mtime: ZonedDateTime? = null
        var tag = ""
        fun reset() {
            href = null; displayName = null; size = null; mtime = null
        }
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            when (event) {
                XmlPullParser.START_TAG -> tag = parser.name.substringAfter(':')
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isEmpty()) continue
                    when (tag) {
                        "href" -> href = text
                        "displayname" -> displayName = text
                        "getcontentlength" -> size = text.toLongOrNull() ?: 0L
                        "getlastmodified" -> mtime = runCatching {
                            ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME)
                        }.getOrNull()
                    }
                }
                XmlPullParser.END_TAG -> {
                    val currentHref = href
                    if (parser.name.substringAfter(':') == "response" && currentHref != null) {
                        // The displayname fallback is the href tail (upstream L1600-1604).
                        val name = displayName?.trim()?.takeIf { it.isNotEmpty() }
                            ?: currentHref.trimEnd('/').substringAfterLast('/')
                        out.add(Entry(currentHref, name, size, mtime))
                        reset()
                    }
                }
            }
        }
        return out
    }

    /** Resolves a server-relative href against the collection URL. */
    private fun resolveHref(base: String, href: String): String {
        if (href.startsWith(base)) return href
        val path = java.net.URI(href).path.orEmpty()
        val trimmed = base.trimEnd('/')
        return trimmed.substringBeforeLast("/", trimmed) + path
    }

    private fun String.toRequestBodyXml(): RequestBody = toRequestBody(XML_MEDIA)
}

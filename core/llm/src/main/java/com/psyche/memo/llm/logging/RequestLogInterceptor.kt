package com.psyche.memo.llm.logging

import com.psyche.memo.common.logging.LogRedactor
import com.psyche.memo.common.logging.RequestLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Interceptor
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.ByteArrayOutputStream

/**
 * 1:1 port of `lib/core/services/network/dio_http_client.dart` L170-347
 * (request + response + error logging). Writes the lines the existing parser
 * in `com.psyche.memo.ui.LogData` understands.
 *
 *  - Request body is **always** logged when [RequestLogger.isEnabled], up to
 *    [LOGGED_BODY_LIMIT] bytes; larger bodies log a `<N bytes, not logged>`
 *    placeholder. This mirrors the Dart `_loggedBodyLimit` (4 MiB) and is
 *    **not** gated on [RequestLogger.saveOutput] — that flag is reserved for
 *    streaming response chunks.
 *  - For 4xx/5xx responses the body is read once (capped at
 *    [MAX_ERROR_BODY_BYTES] = 256 KiB) and written as `[RES n] body=` so the
 *    LogViewer can show API error messages even when streaming is off.
 *  - For non-error responses each chunk goes through
 *    `elidePayloads → redactBody → escape` and is written as `[RES n] chunk=`,
 *    gated on [RequestLogger.saveOutput].
 *  - On throw the message is redacted through `redactText` + `elidePayloads`
 *    before being escaped and written as `[RES n] error=`.
 *
 * Adds a [RequestLogContext] tag to the outgoing request so the streaming
 * code in `OpenAiChatCompletionsClient` / `ClaudeClient` / `GeminiClient`
 * can emit `[RES n] done` with the same id when the flow finishes.
 */
class RequestLogInterceptor : Interceptor {

    private val json = Json { encodeDefaults = true; prettyPrint = false }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!RequestLogger.isEnabled) return chain.proceed(original)

        val id = RequestLogger.nextRequestId()
        val redactedUrl = LogRedactor.redactUrl(original.url.toString())

        RequestLogger.logLine("[REQ $id] ${original.method} $redactedUrl")

        val redactedHeaders = LogRedactor.redactHeaders(original.headers.toMap())
        val headersJson = JsonObject(redactedHeaders.mapValues { JsonPrimitive(it.value) })
        RequestLogger.logLine(
            "[REQ $id] headers=" + json.encodeToString(JsonObject.serializer(), headersJson)
        )

        val newBody = original.body?.let { TeeRequestBody(it, id) }
        val tagged = original.newBuilder()
            .method(original.method, newBody ?: original.body)
            .tag(RequestLogContext::class.java, RequestLogContext(id))
            .build()

        return try {
            val response = chain.proceed(tagged)
            logResponseHeaders(id, response)
            val upstream = response.body
            when {
                upstream == null -> response
                response.code >= 400 ->
                    response.newBuilder().body(ErrorBodyLoggingWrapper(upstream, id)).build()
                RequestLogger.saveOutput ->
                    response.newBuilder().body(ChunkLoggingResponseBody(upstream, id)).build()
                else -> response
            }
        } catch (e: Exception) {
            val redacted = LogRedactor.redactText(
                RequestLogger.elidePayloads(e.message ?: e.javaClass.simpleName)
            )
            RequestLogger.logLine(
                "[RES $id] error=${RequestLogger.escape(redacted)}"
            )
            throw e
        }
    }

    private fun logResponseHeaders(id: Int, response: Response) {
        RequestLogger.logLine("[RES $id] status=${response.code}")
        val redacted = LogRedactor.redactHeaders(response.headers.toMap())
        val json = JsonObject(redacted.mapValues { JsonPrimitive(it.value) })
        RequestLogger.logLine(
            "[RES $id] headers=" + this.json.encodeToString(JsonObject.serializer(), json)
        )
    }
}

/**
 * Carries the log id from the interceptor into the streaming code so the
 * client can log `[RES n] done` with the matching number.
 */
class RequestLogContext(val id: Int)

/** Mirrors `dio_http_client.dart` `_loggedBodyLimit`. */
private const val LOGGED_BODY_LIMIT = 4 * 1024 * 1024
/** Mirrors `dio_http_client.dart` `maxErrorBodyBytes`. */
private const val MAX_ERROR_BODY_BYTES = 256 * 1024

/**
 * Logs the request body once (always, when [RequestLogger.isEnabled]) on the
 * first write, up to [LOGGED_BODY_LIMIT] bytes. Bodies larger than the limit
 * are replaced with a `<N bytes, not logged>` placeholder so the viewer can
 * always open the file. The body is then replayed to the real sink.
 */
private class TeeRequestBody(
    private val delegate: RequestBody,
    private val id: Int,
) : RequestBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        if (!RequestLogger.isEnabled) {
            delegate.writeTo(sink)
            return
        }
        // Buffer the whole body in memory, then replay to the real sink.
        // LLM request bodies are JSON, usually a few KB to ~100KB; the 4 MiB
        // cap below matches the Dart client and is well within reason.
        val tee = Buffer()
        delegate.writeTo(tee)
        sink.writeAll(tee.copy())
        val bytes = tee.readByteArray()
        when {
            bytes.size > LOGGED_BODY_LIMIT ->
                RequestLogger.logLine("[REQ $id] body=<${bytes.size} bytes, not logged>")
            bytes.isEmpty() -> Unit
            else -> {
                val decoded = RequestLogger.safeDecodeUtf8(bytes)
                val processed = if (decoded.isNotEmpty()) {
                    // Elide first: a multi-MB image request drops to a few KB,
                    // which brings it back under redactBody's JSON-parsing limit.
                    LogRedactor.redactBody(RequestLogger.elidePayloads(decoded))
                } else {
                    "base64:${android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)}"
                }
                RequestLogger.logLine(
                    "[REQ $id] body=" + RequestLogger.escape(processed)
                )
            }
        }
    }
}

/**
 * Captures up to [MAX_ERROR_BODY_BYTES] of a 4xx/5xx response, runs the
 * standard `elidePayloads → redactBody → escape` pipeline, and writes
 * `[RES n] body=`. The captured bytes are then replayed to the caller so the
 * usual "read response body" code path keeps working — the Flutter equivalent
 * `_readLimited(body.stream, maxErrorBodyBytes)` does the same.
 */
private class ErrorBodyLoggingWrapper(
    private val upstream: ResponseBody,
    private val id: Int,
) : ResponseBody() {
    private val cached: ByteArray by lazy { readUpstream() }

    override fun contentType() = upstream.contentType()
    override fun contentLength(): Long = cached.size.toLong()
    override fun source(): BufferedSource = Buffer().write(cached).buffer()

    private fun readUpstream(): ByteArray {
        val cap = ByteArrayOutputStream()
        val src = upstream.byteStream()
        try {
            val buf = ByteArray(8192)
            while (cap.size() < MAX_ERROR_BODY_BYTES) {
                val n = src.read(buf)
                if (n <= 0) break
                val toWrite = minOf(n, MAX_ERROR_BODY_BYTES - cap.size())
                cap.write(buf, 0, toWrite)
            }
        } finally {
            src.close()
        }
        val bytes = cap.toByteArray()
        if (bytes.isNotEmpty()) {
            val text = RequestLogger.safeDecodeUtf8(bytes)
            if (text.isNotEmpty()) {
                val processed = LogRedactor.redactBody(RequestLogger.elidePayloads(text))
                RequestLogger.logLine(
                    "[RES $id] body=" + RequestLogger.escape(processed)
                )
            }
        }
        return bytes
    }
}

/**
 * Logs each chunk of a non-error response body (gated on
 * [RequestLogger.saveOutput]) through the same `elidePayloads → redactBody →
 * escape` pipeline as the error body, so a streaming base64 image in a 200
 * response is also elided on disk and any embedded secrets are redacted.
 */
private class ChunkLoggingResponseBody(
    private val delegate: ResponseBody,
    private val id: Int,
) : ResponseBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()

    override fun source(): BufferedSource {
        val src = delegate.source()
        val tee = object : ForwardingSource(src) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val n = super.read(sink, byteCount)
                if (n > 0 && RequestLogger.isEnabled && RequestLogger.saveOutput) {
                    val copy = Buffer()
                    sink.copyTo(copy, 0, n)
                    val text = RequestLogger.safeDecodeUtf8(copy.readByteArray())
                    if (text.isNotEmpty()) {
                        val processed = LogRedactor.redactBody(
                            RequestLogger.elidePayloads(text)
                        )
                        RequestLogger.logLine(
                            "[RES $id] chunk=" + RequestLogger.escape(processed)
                        )
                    }
                }
                return n
            }
        }
        // ForwardingSource is a Source, not a BufferedSource; wrap it.
        return tee.buffer()
    }
}

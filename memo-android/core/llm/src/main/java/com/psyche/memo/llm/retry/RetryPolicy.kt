package com.psyche.memo.llm.retry

import java.io.IOException
import kotlinx.coroutines.CancellationException

/** HTTP status extracted from an error message (mirrors Dart httpStatusFromError). */
fun httpStatusFromError(error: Throwable): Int? {
    val m = Regex("HTTP\\s+(\\d{3})", RegexOption.IGNORE_CASE).find(error.toString())
    return m?.groupValues?.get(1)?.toIntOrNull()
}

fun isCancelError(error: Throwable): Boolean {
    if (error is CancellationException) return true
    val text = error.toString().lowercase()
    return text.contains("dioexceptiontype.cancel") ||
        text.contains("dioexception [cancel]") ||
        text.contains("cancelled") && error is IOException
}

/**
 * Whether [error] is a transport-level failure (timeout, disconnect) that
 * honors retryOnNetworkError and must not match retry keywords first.
 */
private fun isRetryableNetworkError(error: Throwable, status: Int?): Boolean {
    if (error is CancellationException) return false
    if (error is java.net.SocketException ||
        error is java.net.SocketTimeoutException ||
        error is java.net.ConnectException ||
        error is java.io.InterruptedIOException
    ) {
        return true
    }
    if (error is IOException) {
        val msg = error.message?.lowercase() ?: return false
        return msg.contains("connection closed") ||
            msg.contains("while receiving data") ||
            msg.contains("connection reset") ||
            msg.contains("broken pipe") ||
            msg.contains("unexpected end of stream")
    }
    return false
}

private fun containsKeyword(text: String, keywords: List<String>): Boolean {
    if (keywords.isEmpty()) return false
    val haystack = text.lowercase()
    for (raw in keywords) {
        val keyword = raw.trim().lowercase()
        if (keyword.isEmpty()) continue
        if (haystack.contains(keyword)) return true
    }
    return false
}

/**
 * Mirror of Dart shouldRetryError: user cancellation never retries; stop
 * keywords never retry; transport failures honor [retryOnNetworkError];
 * otherwise retry keywords and status codes decide.
 */
fun shouldRetryError(
    error: Throwable,
    options: AutoRetryOptions,
    retryOnNetworkError: Boolean? = null,
): Boolean {
    if (isCancelError(error)) return false
    val text = error.toString()
    if (containsKeyword(text, options.stopKeywords)) return false
    val status = httpStatusFromError(error)
    if (isRetryableNetworkError(error, status)) {
        return retryOnNetworkError ?: options.retryOnNetworkError
    }
    if (containsKeyword(text, options.retryKeywords)) return true
    if (status != null && status in options.retryStatusCodes) return true
    return false
}

/** Runs [attempt]; retries with exponential backoff while the attempt yielded nothing. */
class RetryingPolicy(
    private val options: AutoRetryOptions,
    private val isCancelled: () -> Boolean,
) {
    private val maxRetries: Int = if (options.enabled) options.maxRetries else 0

    /**
     * Invokes [attempt] (attemptIndex, started 0) up to maxRetries+1 times.
     * If the attempt throws and nothing was yielded, and the error is
     * retryable, waits [backoffDelay] and retries. [onRetry] is called between
     * attempts. Returns immediately on success. Throws the last error if all
     * attempts fail, or CancellationException on cancel.
     */
    suspend fun <T> run(attempt: suspend (Int) -> RetryAttempt<T>): T {
        var lastError: Throwable? = null
        for (i in 0..maxRetries) {
            if (isCancelled()) throw kotlinx.coroutines.CancellationException("cancelled")
            var yielded = false
            try {
                val result = attempt(i)
                when (result) {
                    is RetryAttempt.Value -> return result.value
                    is RetryAttempt.Failure -> {
                        lastError = result.error
                        yielded = false
                    }
                    else -> { /* RetryAttempt.NoValue — treat as no-op */ }
                }
            } catch (e: Throwable) {
                lastError = e
                yielded = false
            }
            if (isCancelled()) throw kotlinx.coroutines.CancellationException("cancelled")
            if (i >= maxRetries || !shouldRetryError(lastError!!, options)) throw lastError!!
            val delay = backoffDelay(i, options)
            onRetry?.invoke(i, delay, lastError!!)
            kotlinx.coroutines.delay(delay)
            if (isCancelled()) throw kotlinx.coroutines.CancellationException("cancelled")
        }
        throw lastError!!
    }

    var onRetry: (suspend (Int, Long, Throwable) -> Unit)? = null
}

sealed class RetryAttempt<out T> {
    data class Value<T>(val value: T) : RetryAttempt<T>()
    class Failure(val error: Throwable) : RetryAttempt<Nothing>()
    object NoValue : RetryAttempt<Nothing>()
}

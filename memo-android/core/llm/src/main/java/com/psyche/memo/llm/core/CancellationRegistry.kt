package com.psyche.memo.llm.core

import java.util.concurrent.ConcurrentHashMap

/**
 * Mutable cancellation registry keyed per in-flight request. The UI calls
 * [cancel] with the request id when the user presses Stop; streaming clients
 * poll [isCancelled] between attempts and in the retry loop.
 */
class CancellationRegistry {
    private val cancelledIds = ConcurrentHashMap.newKeySet<String>()

    fun cancel(requestId: String) {
        cancelledIds.add(requestId)
    }

    fun clear(requestId: String) {
        cancelledIds.remove(requestId)
    }

    fun isCancelled(requestId: String): Boolean = requestId in cancelledIds

    fun isCancelled(vararg parts: Any): Boolean =
        cancelledIds.contains(parts.joinToString("|"))
}

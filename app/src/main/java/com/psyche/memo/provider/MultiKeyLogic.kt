package com.psyche.memo.provider

import com.psyche.memo.data.model.ApiKeyConfig
import com.psyche.memo.data.model.ApiKeyStatus

/**
 * Pure helpers behind MultiKeyManagerPage (multi_key_manager_page.dart):
 * key splitting, masking, dedupe-on-import and the per-test status/usage
 * update — kept free of Compose so they unit-test directly.
 */
object MultiKeyLogic {

    /** _splitKeys: commas become spaces, split on any whitespace run. */
    fun splitKeys(raw: String): List<String> =
        raw.replace(',', ' ')
            .trim()
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** mask: first 4 + •••• + last 4 (keys of 8 chars or less stay whole). */
    fun maskKey(key: String): String {
        if (key.length <= 8) return key
        return key.take(4) + "••••" + key.takeLast(4)
    }

    /** _onAddKeys dedupe: drop blanks and keys already stored (trimmed). */
    fun dedupeAdded(existing: List<ApiKeyConfig>, added: List<String>): List<String> {
        val existingSet = existing.map { it.key.trim() }.toSet()
        val unique = ArrayList<String>()
        for (k in added) {
            if (k.isEmpty()) continue
            if (k !in existingSet) unique.add(k)
        }
        return unique
    }

    /**
     * _testKeysAndSave per-key result: status flips active/error, usage
     * counters advance, lastError set/cleared, updatedAt stamped.
     */
    fun applyTestResult(base: ApiKeyConfig, ok: Boolean, nowMs: Long): ApiKeyConfig =
        base.copyWith(
            status = if (ok) ApiKeyStatus.active else ApiKeyStatus.error,
            usage = base.usage.copy(
                totalRequests = base.usage.totalRequests + 1,
                successfulRequests = base.usage.successfulRequests + if (ok) 1 else 0,
                failedRequests = base.usage.failedRequests + if (ok) 0 else 1,
                consecutiveFailures = if (ok) 0 else base.usage.consecutiveFailures + 1,
                lastUsed = nowMs,
            ),
            lastError = if (ok) null else "Test failed",
            lastErrorClear = ok,
            updatedAt = nowMs,
        )
}

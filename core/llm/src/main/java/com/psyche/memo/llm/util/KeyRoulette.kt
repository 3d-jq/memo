package com.psyche.memo.llm.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Multi-key round-robin selection (mirrors RikkaHub KeyRoulette):
 * picks the least-recently-used enabled key per provider, persisted so a
 * restart does not reset the rotation.
 */
interface KeyRoulette {
    /** [keys] may be a single key or split by whitespace/commas. */
    fun next(keys: List<String>, providerId: String): String

    companion object {
        /**
         * [clock] 可注入：LRU 靠时间戳排序，用 `System.currentTimeMillis()` 时同一毫秒内的
         * 两次使用会并列，`minByOrNull` 于是又选中刚用过的那把 —— 单测在 IO 更快的 Linux 上
         * 就是这么翻车的（CI run #6）。生产用默认时钟。
         */
        fun lru(cacheFile: File, clock: () -> Long = System::currentTimeMillis): KeyRoulette =
            LruKeyRoulette(cacheFile, clock)
    }
}

@Serializable
private data class LruCacheMap(
    val providers: Map<String, Map<String, Long>> = emptyMap(),
)

private const val EXPIRE_DURATION_MS = 24 * 60 * 60 * 1000L

private class LruKeyRoulette(private val cacheFile: File, private val clock: () -> Long) : KeyRoulette {

    private val lock = Any()

    override fun next(keys: List<String>, providerId: String): String {
        val keyList = keys.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (keyList.isEmpty()) return ""
        synchronized(lock) {
            val now = clock()
            val allCache = loadCache().toMutableMap()

            val providerCache = (allCache[providerId] ?: emptyMap())
                .filter { (k, lastUsed) -> k in keyList && now - lastUsed < EXPIRE_DURATION_MS }
                .toMutableMap()

            val selected = keyList.firstOrNull { it !in providerCache }
                ?: providerCache.minByOrNull { it.value }!!.key

            providerCache[selected] = now
            allCache[providerId] = providerCache
            saveCache(allCache)
            return selected
        }
    }

    private fun loadCache(): Map<String, Map<String, Long>> = try {
        if (cacheFile.exists()) Json.decodeFromString<LruCacheMap>(cacheFile.readText()).providers
        else emptyMap()
    } catch (e: Exception) {
        emptyMap()
    }

    private fun saveCache(cache: Map<String, Map<String, Long>>) {
        try {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(Json.encodeToString(LruCacheMap(cache)))
        } catch (e: Exception) {
            // best-effort
        }
    }
}

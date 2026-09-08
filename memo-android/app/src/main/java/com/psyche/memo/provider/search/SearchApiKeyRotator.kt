package com.psyche.memo.provider.search

/**
 * Port of lib/core/services/search/search_api_key_rotator.dart — round-robin
 * over `[primary, ...extras]`, cursor kept in memory per service id.
 */
object SearchApiKeyRotator {

    private val indices = HashMap<String, Int>() // serviceId -> next pool index

    fun select(serviceId: String, primary: String, extras: List<String>): String {
        val pool = rotationPool(primary, extras)
        if (pool.isEmpty()) return primary
        if (pool.size == 1) return pool.first()
        val current = indices[serviceId] ?: 0
        val index = current % pool.size
        indices[serviceId] = (index + 1) % pool.size
        return pool[index]
    }

    /** All keys that participate in rotation, in rotation order. */
    fun rotationPool(primary: String, extras: List<String>): List<String> {
        val seen = HashSet<String>()
        val pool = ArrayList<String>()
        fun add(key: String) {
            val trimmed = key.trim()
            if (trimmed.isEmpty() || !seen.add(trimmed)) return
            pool.add(trimmed)
        }
        add(primary)
        extras.forEach { add(it) }
        return pool
    }

    /** Splits a batch paste on newlines/commas/semicolons/whitespace. */
    fun parseBatch(input: String): List<String> {
        val seen = HashSet<String>()
        val keys = ArrayList<String>()
        for (part in input.split(Regex("[\\s,;]+"))) {
            val key = part.trim()
            if (key.isEmpty() || !seen.add(key)) continue
            keys.add(key)
        }
        return keys
    }

    /** Masks a key for display, keeping the first and last four characters. */
    fun mask(key: String): String {
        val trimmed = key.trim()
        if (trimmed.length <= 8) return "••••••••"
        return trimmed.take(4) + "••••" + trimmed.takeLast(4)
    }

    /** Test hook: forget the rotation cursors. */
    internal fun reset() = indices.clear()
}

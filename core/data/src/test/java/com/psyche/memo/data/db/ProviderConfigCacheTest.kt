package com.psyche.memo.data.db

import com.psyche.memo.data.model.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 「打开界面就卡顿」的根因是 `AppContainer.providerConfig()` 每次调用都查库 + 解
 * JSON（组合期高频、单次请求就打 4 次）。缓存本身要保证：命中即不再解析、写入侧
 * 能整表失效、单键失效只影响那一行。
 */
class ProviderConfigCacheTest {

    private fun config(name: String) = ProviderConfig(id = name, name = name)

    @Test
    fun `a stored config is returned as-is`() {
        val config = config("Zhipu AI")
        ProviderConfigCache.put("Zhipu AI", config)
        assertSame(config, ProviderConfigCache.get("Zhipu AI"))
        ProviderConfigCache.invalidate()
    }

    @Test
    fun `unknown keys stay absent`() {
        ProviderConfigCache.invalidate()
        assertNull(ProviderConfigCache.get("never written"))
    }

    @Test
    fun `a table-wide invalidation drops every key`() {
        ProviderConfigCache.put("a", config("a"))
        ProviderConfigCache.put("b", config("b"))
        ProviderConfigCache.invalidate()
        assertNull(ProviderConfigCache.get("a"))
        assertNull(ProviderConfigCache.get("b"))
    }

    @Test
    fun `a single-key invalidation leaves the others alone`() {
        ProviderConfigCache.put("a", config("a"))
        ProviderConfigCache.put("b", config("b"))
        ProviderConfigCache.invalidate("a")
        assertNull(ProviderConfigCache.get("a"))
        assertEquals("b", ProviderConfigCache.get("b")?.name)
        ProviderConfigCache.invalidate()
    }
}

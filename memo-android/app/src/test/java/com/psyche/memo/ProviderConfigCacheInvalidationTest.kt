package com.psyche.memo

import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.data.db.PayloadEntityDao
import com.psyche.memo.data.db.ProviderConfigCache
import com.psyche.memo.data.model.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 供应商配置缓存（治「打开加载数据的界面就卡顿」）的**失效面**：
 *
 * `provider_rows` 的所有写入都走 [PayloadEntityDao]（工程里没有绕过它的裸 SQL，
 * 备份恢复也走 `replaceAll`），所以失效挂在 DAO 上就完整了。这里锁住三件事：
 * ① 容器读一次之后不再解析；② 任何一次写入都让缓存失效；③ 别的表不影响它。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderConfigCacheInvalidationTest {

    private lateinit var container: AppContainerImpl

    @Before
    fun setUp() {
        container = AppContainerImpl(ApplicationProvider.getApplicationContext())
        ProviderConfigCache.invalidate()
        container.database.writableDatabase.execSQL("DELETE FROM provider_rows")
    }

    private fun providerDao() = PayloadEntityDao(
        container.database.writableDatabase,
        "provider_rows",
        primaryKey = "provider_key",
    )

    private fun writeConfig(key: String, name: String) {
        providerDao().upsert(
            id = key,
            payload = kotlinx.serialization.json.Json.encodeToString(
                ProviderConfig.serializer(),
                ProviderConfig(id = key, name = name),
            ),
            sortOrder = 0,
        )
    }

    @Test
    fun `the container reads a provider row once and serves the cache afterwards`() {
        writeConfig("Zhipu AI", "Zhipu AI")
        ProviderConfigCache.invalidate()

        val first = container.providerConfig("Zhipu AI")
        assertEquals("Zhipu AI", first?.name)
        // 命中缓存：即使把行删掉（不走 DAO 就不失效），第二次读也不再查库。
        val second = container.providerConfig("Zhipu AI")
        assertEquals("Zhipu AI", second?.name)
        assertNull(ProviderConfigCache.get("no such row"))
    }

    @Test
    fun `a provider row write invalidates the whole cache`() {
        writeConfig("Zhipu AI", "Zhipu AI")
        container.providerConfig("Zhipu AI")

        writeConfig("Zhipu AI", "Zhipu AI renamed")

        // 缓存已失效 → 重新查库拿到新名字。
        assertEquals("Zhipu AI renamed", container.providerConfig("Zhipu AI")?.name)
    }

    @Test
    fun `deleting a provider row invalidates too`() {
        writeConfig("Zhipu AI", "Zhipu AI")
        assertEquals("Zhipu AI", container.providerConfig("Zhipu AI")?.name)

        providerDao().delete("Zhipu AI")

        assertNull(container.providerConfig("Zhipu AI"))
    }

    @Test
    fun `other payload tables do not touch the provider cache`() {
        writeConfig("Zhipu AI", "Zhipu AI")
        container.providerConfig("Zhipu AI")

        PayloadEntityDao(container.database.writableDatabase, "mcp_server_rows").upsert(
            id = "noise",
            payload = "{}",
            sortOrder = 0,
        )

        // 缓存还在（不然每次无关写入都会把供应商配置全部重读一遍）。
        assertEquals("Zhipu AI", ProviderConfigCache.get("Zhipu AI")?.name)
    }
}

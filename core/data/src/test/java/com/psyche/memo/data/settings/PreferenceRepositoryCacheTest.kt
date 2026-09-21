package com.psyche.memo.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.data.db.MemoDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `PreferenceRepository.readJson` 的进程内读缓存（照 `ProviderConfigCache`）。
 *
 * 为什么需要：`readJson` 每次调用一条 SQLite 查询，而它在 **ui 组合期**被调用约 195 处
 * （`remember { readJson(...) }`）—— 每次进页面/每次重建都在主线程上打一轮 SQLite 往返
 * （用户 2026-09-15「我这个 app 只要遇到加载显示场景就会卡」）。缓存之后这些读取变成
 * 内存查表，代价是**写入侧必须失效**：本测试把「查过没有也缓存」「写/删会失效」
 * 「裸 SQL 改表要显式 [PreferenceRepository.invalidateCache]」三条契约钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreferenceRepositoryCacheTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: MemoDatabase
    private lateinit var repository: PreferenceRepository

    @Before
    fun setUp() {
        db = MemoDatabase(context)
        repository = PreferenceRepository(
            db,
            context.getSharedPreferences("preference_cache_test", Context.MODE_PRIVATE),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertRaw(key: String, value: String) {
        db.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO preference_rows (key, value, updated_at) VALUES (?, ?, ?)",
            arrayOf<Any>(key, value, 1L),
        )
    }

    private fun deleteRaw(key: String) {
        db.writableDatabase.delete("preference_rows", "key = ?", arrayOf(key))
    }

    @Test
    fun `a missing key is cached so a later raw insert is not seen until invalidated`() {
        val key = "cache_probe_missing_v1"
        deleteRaw(key)

        assertNull(repository.readJson(key))
        // 缓存把「查过、库里没有」也记下了 —— 否则未写过的键（默认值那批，恰恰是
        // 大多数）每次都要再查一遍库。
        insertRaw(key, "\"late\"")
        assertNull(repository.readJson(key))

        repository.invalidateCache()
        assertEquals("\"late\"", repository.readJson(key))
    }

    @Test
    fun `writing a key invalidates its cached value`() {
        val key = "cache_probe_write_v1"
        deleteRaw(key)
        assertNull(repository.readJson(key)) // 先让缓存记住「没有」

        repository.writeJson(key, "\"one\"")
        assertEquals("\"one\"", repository.readJson(key))

        repository.writeJson(key, "\"two\"")
        assertEquals("\"two\"", repository.readJson(key))
    }

    @Test
    fun `removing a key invalidates its cached value`() {
        val key = "cache_probe_remove_v1"
        repository.writeJson(key, "\"gone\"")
        assertEquals("\"gone\"", repository.readJson(key))

        repository.remove(key)

        assertNull(repository.readJson(key))
    }

    @Test
    fun `local only keys never go through the row cache`() {
        // display_chat_font_scale_v1 的 KeyDisposition 是 LOCAL_ONLY：写进
        // SharedPreferences，读也必须走 SharedPreferences（别被缓存挡住）。
        val key = "display_chat_font_scale_v1"
        repository.writeJson(key, "1.25")

        assertEquals("1.25", repository.readJson(key))
        assertEquals("1.25", repository.readLocal(key))

        repository.removeLocal(key)
        assertNull(repository.readJson(key))
    }
}

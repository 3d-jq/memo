package com.psyche.memo.data.assistant

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.data.db.AssistantCache
import com.psyche.memo.data.db.loadSchemaStatements
import com.psyche.memo.data.model.Assistant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `AssistantStore.get` 的进程内解码缓存（[AssistantCache]，与 `ProviderConfigCache` 同款）。
 *
 * 为什么：`get(id)` 是「查库 + 解 payload JSON」，而它在**组合期**被密集调用（抽屉当前
 * 助手、消息头归属助手、技能/工作区/MCP 选择 sheet、顶栏助手头像）。缓存之后命中即内存
 * 读，代价同样是**写入侧必须失效**：本测试钉住「查过就缓存」「DAO 写入/更新会失效」
 * 「getAll 顺便预热」三条契约。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantStoreCacheTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: SQLiteDatabase
    private lateinit var store: AssistantStore

    @Before
    fun setUp() {
        db = SQLiteDatabase.create(null)
        for (statement in loadSchemaStatements(context)) db.execSQL(statement)
        AssistantCache.invalidate()
        store = AssistantStore(db)
    }

    @After
    fun tearDown() {
        AssistantCache.invalidate()
        db.close()
    }

    private fun insertRaw(id: String, name: String) {
        db.execSQL(
            "INSERT OR REPLACE INTO assistant_rows (id, sort_order, payload, updated_at) VALUES (?, 0, ?, 1)",
            arrayOf<Any>(id, """{"id":"$id","name":"$name"}"""),
        )
    }

    @Test
    fun `a raw row change is not seen until the cache is invalidated`() {
        insertRaw("a1", "first")
        assertEquals("first", store.get("a1")?.name)

        // 绕过 DAO 的裸 SQL：缓存不会自己知道（这正是「写入必须走 DAO」的原因）。
        insertRaw("a1", "second")
        assertEquals("first", store.get("a1")?.name)

        AssistantCache.invalidate()
        assertEquals("second", store.get("a1")?.name)
    }

    @Test
    fun `updating through the store invalidates the cached copy`() {
        insertRaw("a2", "before")
        val loaded = store.get("a2")!!
        assertEquals("before", loaded.name)

        store.update(loaded.copy(name = "after"))

        assertEquals("after", store.get("a2")?.name)
    }

    @Test
    fun `getAll warms the cache for every row`() {
        insertRaw("a3", "listed")
        store.getAll()

        // 裸 SQL 再改一次：如果 getAll 没预热，这里会读到新值（说明它每次都查库）。
        insertRaw("a3", "changed")
        assertEquals("listed", store.get("a3")?.name)

        AssistantCache.invalidate()
        assertEquals("changed", store.get("a3")?.name)
    }

    @Test
    fun `unknown ids stay unknown`() {
        assertEquals(null, store.get("missing"))
        assertEquals(null, store.get("missing"))
    }

    @Test
    fun `assistant fields round trip through the cache`() {
        val a = Assistant(id = "a4", name = "缓存助手", useAssistantAvatar = true, useAssistantName = true)
        store.update(a)

        val cached = store.get("a4")!!

        assertEquals("缓存助手", cached.name)
        assertEquals(true, cached.useAssistantAvatar)
        assertEquals(true, cached.useAssistantName)
    }
}

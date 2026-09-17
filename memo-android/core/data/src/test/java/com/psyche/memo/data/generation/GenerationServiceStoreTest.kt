package com.psyche.memo.data.generation

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.data.db.loadSchemaStatements
import com.psyche.memo.data.model.GenerationKind
import com.psyche.memo.data.model.GenerationService
import com.psyche.memo.data.model.GenerationTestState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 生成服务记录的持久化（自研功能）：走 drift v3 的通用表 `extension_entity_rows`
 * （`kind = "generation_service"`），**不建新表**。这里钉住 CRUD、两类服务的隔离、
 * 更新时间戳与「测试连接」结果落库。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GenerationServiceStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: SQLiteDatabase
    private lateinit var store: GenerationServiceStore

    @Before
    fun setUp() {
        db = SQLiteDatabase.create(null)
        for (statement in loadSchemaStatements(context)) db.execSQL(statement)
        store = GenerationServiceStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun image(name: String = "画图", model: String = "gpt-image-1") =
        GenerationService(
            id = "",
            kind = GenerationKind.IMAGE,
            name = name,
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-image",
            model = model,
        )

    private fun video(name: String = "视频", model: String = "sora-2") =
        GenerationService(
            id = "",
            kind = GenerationKind.VIDEO,
            name = name,
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-video",
            model = model,
            durationSeconds = 8,
        )

    @Test
    fun `create assigns an id and normalizes the payload`() {
        val created = store.create(image(name = "  画图  "))
        assertTrue(created.id.isNotEmpty())
        assertEquals("画图", created.name)
        assertTrue(created.createdAt > 0)
        assertEquals(created, store.get(created.id))
    }

    @Test
    fun `both kinds live in one entity table and are filtered by kind`() {
        val img = store.create(image())
        val vid = store.create(video())
        assertEquals(2, store.getAll().size)
        assertEquals(listOf(img.id), store.getAll(GenerationKind.IMAGE).map { it.id })
        assertEquals(listOf(vid.id), store.getAll(GenerationKind.VIDEO).map { it.id })

        // 一行 kind 列 = "generation_service"，两类共用（没有各自的表）。
        db.rawQuery(
            "SELECT COUNT(*) FROM extension_entity_rows WHERE kind = ?",
            arrayOf(GenerationServiceStore.KIND),
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
        }
    }

    @Test
    fun `update keeps createdAt and the row position`() {
        val first = store.create(image(name = "A"))
        val second = store.create(image(name = "B"))
        val updated = store.update(second.copy(name = "B2", count = 99))
        assertNotNull(updated)
        assertEquals(second.createdAt, updated!!.createdAt)
        assertEquals(GenerationService.MAX_IMAGE_COUNT, updated.count)
        // 顺序不变：A 仍在 B 前面。
        assertEquals(listOf(first.id, second.id), store.getAll().map { it.id })
        assertNull(store.update(second.copy(id = "missing")))
    }

    @Test
    fun `delete removes the record`() {
        val created = store.create(image())
        assertTrue(store.delete(created.id))
        assertNull(store.get(created.id))
        assertEquals(false, store.delete(created.id))
    }

    @Test
    fun `test result is stored on the record`() {
        val created = store.create(image())
        assertNull(created.lastTestState)
        val ok = store.setTestState(created.id, GenerationTestState.OK, at = 42)
        assertEquals(GenerationTestState.OK, ok?.lastTestState)
        assertEquals(42L, ok?.lastTestAt)
        assertEquals(GenerationTestState.OK, store.get(created.id)?.lastTestState)
        // 「可达」是独立的一态：不能塌成失败（生成类中转没有 /models 的常态）。
        assertEquals(
            GenerationTestState.REACHABLE,
            store.setTestState(created.id, GenerationTestState.REACHABLE)?.lastTestState,
        )
        assertEquals(
            GenerationTestState.FAILED,
            store.setTestState(created.id, GenerationTestState.FAILED)?.lastTestState,
        )
        // 认不出的值当「没测过」，脏数据不至于在列表里变成怪状态。
        assertNull(store.setTestState(created.id, "whatever")?.lastTestState)
        assertNull(store.setTestState("missing", GenerationTestState.OK))
    }
}

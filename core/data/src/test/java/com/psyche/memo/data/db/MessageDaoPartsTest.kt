package com.psyche.memo.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 一页消息的 parts 必须是**一次批量查询**取回（不再是每行一条 `loadParts` —— 40 条的
 * 尾页原先要 41 次查询），而批量之后最容易错的是**顺序与分组**：`IN (...)` 的返回顺序
 * 由 SQLite 决定，parts 必须仍然按各自 revision 的 `ordinal ASC` 落回对应消息。
 *
 * 另外钉住两件容易漏的事：`getAllForConversation` 在长会话上会超过 SQLite 的变量上限
 * （所以分批），以及没有任何 part 行的消息要得到空列表而不是崩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageDaoPartsTest {

    private lateinit var db: SQLiteDatabase
    private lateinit var dao: MessageDao

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = SQLiteDatabase.create(null)
        for (statement in loadSchemaStatements(context)) db.execSQL(statement)
        // 全程一个事务：几百条 insert 走 in-memory SQLite 会快很多。
        db.beginTransaction()
        dao = MessageDao(db)
    }

    @After
    fun tearDown() {
        runCatching { db.endTransaction() }
        db.close()
    }

    private fun insertMessage(id: String, conversationId: String, order: Int) {
        db.execSQL(
            "INSERT INTO message_rows (id, conversation_id, role, timestamp, message_order) " +
                "VALUES (?, ?, 'user', ?, ?)",
            arrayOf<Any?>(id, conversationId, 1_700_000_000_000_000L + order, order),
        )
    }

    private fun insertPart(conversationId: String, revisionId: String, ordinal: Int, text: String) {
        // text part 的 payload 就是**原样正文**（`TextPart.encodePayload() == text`），
        // 不是 JSON —— 别照 `{"text":...}` 写，那样读出来是带引号的原文。
        db.execSQL(
            "INSERT INTO message_part_rows " +
                "(conversation_id, revision_id, ordinal, kind, payload, created_at, updated_at) " +
                "VALUES (?, ?, ?, 'text', ?, 1, 1)",
            arrayOf<Any?>(conversationId, revisionId, ordinal, text),
        )
    }

    private fun texts(message: com.psyche.memo.data.model.ChatMessage): List<String> =
        message.parts.mapNotNull { part ->
            (part as? com.psyche.memo.data.model.TextPart)?.text
        }

    @Test
    fun `parts of each message come back in ordinal order`() {
        // 故意乱序插入：批量查询靠 ORDER BY ordinal 还原顺序，而不是插入顺序。
        insertMessage("m1", "c1", 0)
        insertPart("c1", "m1", 2, "third")
        insertPart("c1", "m1", 0, "first")
        insertPart("c1", "m1", 1, "second")

        val loaded = dao.get("m1")!!

        assertEquals(listOf("first", "second", "third"), texts(loaded))
    }

    @Test
    fun `a page keeps every message's own parts`() {
        for (i in 0 until 5) {
            insertMessage("m$i", "c1", i)
            insertPart("c1", "m$i", 0, "head-$i")
            insertPart("c1", "m$i", 1, "tail-$i")
        }

        val page = dao.getAllForConversation("c1")

        assertEquals(5, page.size)
        for ((i, message) in page.withIndex()) {
            assertEquals("m$i", message.id)
            assertEquals(listOf("head-$i", "tail-$i"), texts(message))
        }
    }

    @Test
    fun `tail page returns the newest rows in wall order with parts attached`() {
        for (i in 0 until 6) {
            insertMessage("m$i", "c1", i)
            insertPart("c1", "m$i", 0, "text-$i")
        }

        val tail = dao.getTail("c1", limit = 3)

        assertEquals(listOf("m3", "m4", "m5"), tail.map { it.id })
        assertEquals(listOf("text-3", "text-4", "text-5"), tail.map { texts(it).single() })
    }

    @Test
    fun `before page stops at the anchor and keeps wall order`() {
        for (i in 0 until 5) {
            insertMessage("m$i", "c1", i)
            insertPart("c1", "m$i", 0, "text-$i")
        }

        val older = dao.getBefore("c1", "m4", limit = 2)

        assertEquals(listOf("m2", "m3"), older.map { it.id })
        assertEquals(listOf("text-2", "text-3"), older.map { texts(it).single() })
    }

    @Test
    fun `empty conversation and partless message are handled`() {
        assertTrue(dao.getAllForConversation("nope").isEmpty())
        assertTrue(dao.getTail("nope").isEmpty())
        assertTrue(dao.getBefore("nope", "missing").isEmpty())

        insertMessage("bare", "c1", 0)
        assertEquals(emptyList<String>(), texts(dao.get("bare")!!))
    }

    /** 超过 `IN (...)` 一批的变量上限时仍要全部取回（走分批）。 */
    @Test
    fun `more rows than one in-clause chunk still load all parts`() {
        val total = MessageDao.SQLITE_IN_CLAUSE_CHUNK + 1
        for (i in 0 until total) {
            insertMessage("m$i", "c1", i)
            insertPart("c1", "m$i", 0, "text-$i")
        }

        val all = dao.getAllForConversation("c1")

        assertEquals(total, all.size)
        assertEquals("text-0", texts(all.first()).single())
        assertEquals("text-${total - 1}", texts(all.last()).single())
        // 每一条都拿得到自己的 parts（跨批不会串行/丢行）。
        assertTrue(all.all { texts(it).size == 1 })
    }

    @Test
    fun `get by ids keeps the requested order and loads parts`() {
        for (i in 0 until 3) {
            insertMessage("m$i", "c1", i)
            insertPart("c1", "m$i", 0, "text-$i")
        }

        val loaded = dao.getByIds(listOf("m2", "m0"))

        assertEquals(listOf("m2", "m0"), loaded.map { it.id })
        assertEquals(listOf("text-2", "text-0"), loaded.map { texts(it).single() })
    }
}

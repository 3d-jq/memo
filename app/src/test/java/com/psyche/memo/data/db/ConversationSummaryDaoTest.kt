package com.psyche.memo.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.data.model.Conversation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 会话总结的读写（chat_service.getConversationsWithSummaryForAssistant /
 * updateConversationSummary / clearConversationSummary）—— 助手记忆 tab 的
 * 「管理总结」列表依赖这里的过滤与排序。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationSummaryDaoTest {

    private lateinit var db: SQLiteDatabase
    private lateinit var dao: ConversationDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = SQLiteDatabase.create(null)
        for (statement in loadSchemaStatements(context)) db.execSQL(statement)
        dao = ConversationDao(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insert(
        id: String,
        title: String,
        assistantId: String?,
        summary: String?,
        updatedAt: Long,
    ) {
        dao.insert(
            Conversation(
                id = id,
                title = title,
                createdAt = 1,
                updatedAt = updatedAt,
                assistantId = assistantId,
                summary = summary,
            ),
        )
    }

    @Test
    fun `lists only this assistant's conversations that really have a summary`() {
        insert("a", "有总结", "as1", "摘要 A", 10)
        insert("b", "空总结", "as1", "", 20)
        insert("c", "只有空白", "as1", "   ", 30)
        insert("d", "没有总结", "as1", null, 40)
        insert("e", "别的助手", "as2", "摘要 E", 50)

        assertEquals(listOf("a"), dao.withSummaryForAssistant("as1").map { it.id })
    }

    @Test
    fun `newest first`() {
        insert("old", "旧", "as1", "一", 10)
        insert("new", "新", "as1", "二", 20)
        assertEquals(listOf("new", "old"), dao.withSummaryForAssistant("as1").map { it.id })
    }

    @Test
    fun `update keeps the summarized message count and clear resets both`() {
        insert("a", "有总结", "as1", "摘要", 10)
        dao.updateSummary("a", "更新后的摘要", lastSummarizedMessageCount = 42)
        assertEquals("更新后的摘要", dao.get("a")!!.summary)
        assertEquals(42, dao.get("a")!!.lastSummarizedMessageCount)
        assertEquals(listOf("a"), dao.withSummaryForAssistant("as1").map { it.id })

        dao.clearSummary("a")
        val cleared = dao.get("a")!!
        assertNull(cleared.summary)
        assertEquals(0, cleared.lastSummarizedMessageCount)
        assertEquals(emptyList<String>(), dao.withSummaryForAssistant("as1").map { it.id })
    }
}

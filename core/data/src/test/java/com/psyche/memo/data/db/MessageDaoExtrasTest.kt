package com.psyche.memo.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.data.model.ChatMessage
import com.psyche.memo.data.model.TextPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `message_rows.extras_json` 里的 `text_stream_ms` 往返。
 *
 * 生成速度要用「正文实际在流」的时长做分母，而 schema 是 drift 生成的、不许加列 ⇒
 * 只能住进这个 JSON 列。三件事必须钉住：写了能读回来、没写时保持 `{}`（备份/合并
 * 按这个列做字节比较，凭空多出键会报假差异），以及**坏 JSON 不能让一条消息读不出来**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageDaoExtrasTest {

    private lateinit var db: SQLiteDatabase
    private lateinit var dao: MessageDao

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = SQLiteDatabase.create(null)
        for (statement in loadSchemaStatements(context)) db.execSQL(statement)
        dao = MessageDao(db)
    }

    private fun message(id: String, textStreamMs: Long?) = ChatMessage(
        id = id,
        role = "assistant",
        parts = listOf(TextPart("正文")),
        timestamp = 1_700_000_000_000_000L,
        conversationId = "c1",
        groupId = id,
        messageOrder = 0,
        durationMs = 9_000L,
        textStreamMs = textStreamMs,
    )

    @Test
    fun `first token timing survives a round trip`() {
        dao.insert(message("m1", textStreamMs = 1_234L))
        val read = dao.get("m1")
        assertEquals(1_234L, read?.textStreamMs)
        assertEquals(9_000L, read?.durationMs)
    }

    @Test
    fun `an unmeasured turn keeps the column empty`() {
        dao.insert(message("m2", textStreamMs = null))
        assertNull(dao.get("m2")?.textStreamMs)
        db.rawQuery("SELECT extras_json FROM message_rows WHERE id = 'm2'", null).use {
            it.moveToFirst()
            assertEquals("{}", it.getString(0))
        }
    }

    /** 别的字段/坏数据都在别人的列里，不该让我们读不出这一条。 */
    @Test
    fun `garbage or foreign keys in extras_json degrade to null`() {
        dao.insert(message("m3", textStreamMs = 10L))
        db.execSQL(
            "UPDATE message_rows SET extras_json = '{oops' WHERE id = 'm3'",
        )
        assertEquals("坏 JSON 只丢这一个字段，消息本身要能读出来", "m3", dao.get("m3")?.id)
        assertNull(dao.get("m3")?.textStreamMs)

        db.execSQL(
            "UPDATE message_rows SET extras_json = '{\"other\":1}' WHERE id = 'm3'",
        )
        assertNull(dao.get("m3")?.textStreamMs)

        db.execSQL(
            "UPDATE message_rows SET extras_json = '{\"text_stream_ms\":\"80\"}' WHERE id = 'm3'",
        )
        assertNull("字符串数字不算采到", dao.get("m3")?.textStreamMs)
    }
}

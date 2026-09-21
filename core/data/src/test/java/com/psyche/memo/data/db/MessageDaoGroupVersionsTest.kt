package com.psyche.memo.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 分支选择器的版本表（`ChatViewModel.reloadTail` → `_versionInfo`）必须是**按需查**：
 * 只查当前窗口里出现过 `version > 0` 的那几个组，而不是整会话 `message_rows` 全表扫
 * —— 用户 2026-09-15「点击历史对话加载对话好卡呀 / 对话还是卡到爆」，原版同一判据
 * （`chat_controller.dart:180-198`：只收 `versionCount > 1 || version > 0 || 已在
 * versionSelections 里` 的组才预载）。
 *
 * 这里钉住过滤的语义：给 `groups` 时**只返回这些组**（一次 `IN (?,?,…)`）、仍按
 * conversation 隔离、版本号升序去重；给空集合时**一次库都不查**直接返回空表；
 * 不给 `groups` 时行为与从前一致（整会话全表扫）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageDaoGroupVersionsTest {

    private lateinit var db: SQLiteDatabase
    private lateinit var dao: MessageDao

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = SQLiteDatabase.create(null)
        for (statement in loadSchemaStatements(context)) db.execSQL(statement)
        db.beginTransaction()
        dao = MessageDao(db)
    }

    @After
    fun tearDown() {
        runCatching { db.endTransaction() }
        db.close()
    }

    private fun insert(conversationId: String, groupId: String, version: Int, order: Int) {
        // 显式 `Array<Any?>`：混排 String/Long/Int 时 `arrayOf` 会推断成交集类型
        // （KT-71420，未来会变成错误）。
        val args: Array<Any?> = arrayOf(
            "$conversationId-$groupId-$version",
            conversationId,
            1_700_000_000_000_000L + order,
            groupId,
            version,
            order,
        )
        db.execSQL(
            "INSERT INTO message_rows (id, conversation_id, role, timestamp, group_id, version, message_order) " +
                "VALUES (?, ?, 'assistant', ?, ?, ?, ?)",
            args,
        )
    }

    @Test
    fun `unfiltered scan returns every group in the conversation, versions ascending`() {
        insert("c1", "g1", 2, 0)
        insert("c1", "g1", 0, 1)
        insert("c1", "g1", 1, 2)
        insert("c1", "g2", 0, 3)
        insert("c2", "g3", 0, 4) // 别的会话不能串进来

        val versions = dao.groupVersions("c1")

        assertEquals(setOf("g1", "g2"), versions.keys)
        assertEquals(listOf(0, 1, 2), versions["g1"])
        assertEquals(listOf(0), versions["g2"])
    }

    @Test
    fun `group filter returns only the requested groups with all of their versions`() {
        insert("c1", "g1", 0, 0)
        insert("c1", "g1", 1, 1)
        insert("c1", "g2", 0, 2)
        insert("c1", "g2", 1, 3)
        insert("c1", "g3", 7, 4)

        val versions = dao.groupVersions("c1", listOf("g1", "g3"))

        assertEquals(setOf("g1", "g3"), versions.keys)
        assertFalse(versions.containsKey("g2"))
        assertEquals(listOf(0, 1), versions["g1"])
        // g3 在窗口里只见到 version 7，但它的全部版本都要取回来给选择器。
        assertEquals(listOf(7), versions["g3"])
    }

    @Test
    fun `empty group filter skips the query entirely`() {
        insert("c1", "g1", 0, 0)
        insert("c1", "g1", 1, 1)

        // 窗口里没有任何多版本组 → 调用方直接跳过查询，不能退化成全表扫。
        assertTrue(dao.groupVersions("c1", emptyList()).isEmpty())
    }

    @Test
    fun `group filter still scopes to the conversation`() {
        insert("c1", "g1", 0, 0)
        insert("c2", "g1", 1, 1)

        val versions = dao.groupVersions("c1", listOf("g1"))

        assertEquals(listOf(0), versions["g1"])
    }
}

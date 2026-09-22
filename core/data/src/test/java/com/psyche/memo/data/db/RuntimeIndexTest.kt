package com.psyche.memo.data.db

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 运行时自建索引（`message_rows(timestamp)`）必须真的建出来。
 *
 * 它不在 drift 导出里（那 17 条索引是上游的），所以没有生成物能替它兜底 —— 只能真开一次库查
 * `sqlite_master`。**故意不在这里断言 `PRAGMA journal_mode` 是不是 wal**：Robolectric 用的是模拟
 * SQLite，WAL 的支持与否取决于实现，拿它做断言会变成"环境决定成败"的坏测试（见 PORTING §5.40）。
 * WAL 在真机上的效果靠实测，不靠这里。
 */
@RunWith(RobolectricTestRunner::class)
class RuntimeIndexTest {

    @Test
    fun creatingTheDatabaseAddsTheTimestampIndex() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getDatabasePath(MemoSchema.DB_NAME).delete()
        val db = MemoDatabase(context).writableDatabase
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='index' AND name = ?",
            arrayOf(MemoSchema.INDEX_MESSAGE_TIMESTAMP),
        ).use { cursor ->
            assertTrue(
                "message_rows(timestamp) 的索引没建出来：${MemoSchema.INDEX_MESSAGE_TIMESTAMP}",
                cursor.moveToFirst(),
            )
        }
        db.close()
    }
}

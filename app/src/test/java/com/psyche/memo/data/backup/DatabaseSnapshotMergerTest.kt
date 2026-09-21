package com.psyche.memo.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.db.loadSchemaStatements
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The chat half of merge restore (`chat_database_repository.dart:5057
 * mergeBackupSnapshot`): identical conversations dedupe, conflicting ones land
 * under a deterministic remapped id with their messages/groups intact, and
 * conversations with a corrupt message order are skipped with a count.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseSnapshotMergerTest {

    private lateinit var context: Context
    private lateinit var container: AppContainerImpl
    private lateinit var snapshotFile: File
    private lateinit var snapshot: SQLiteDatabase

    private val insertConversation = { db: SQLiteDatabase, id: String, title: String ->
        db.execSQL(
            "INSERT INTO conversation_rows (id, title, created_at, updated_at) VALUES (?, ?, ?, ?)",
            arrayOf<Any>(id, title, 1_700_000_000_000_000L, 1_700_000_000_000_000L),
        )
    }

    private val insertMessage = { db: SQLiteDatabase, id: String, conversationId: String, order: Long, text: String ->
        db.execSQL(
            "INSERT INTO message_rows (id, conversation_id, role, timestamp, message_order) " +
                "VALUES (?, ?, 'user', ?, ?)",
            arrayOf<Any>(id, conversationId, 1_700_000_000_100_000L + order, order),
        )
        db.execSQL(
            "INSERT INTO message_part_rows (conversation_id, revision_id, ordinal, kind, payload, created_at, updated_at) " +
                "VALUES (?, ?, 0, 'text', ?, ?, ?)",
            arrayOf<Any>(conversationId, id, """{"text":"$text"}""", 1_700_000_000_000_000L, 1_700_000_000_000_000L),
        )
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = AppContainerImpl(context)
        snapshotFile = File(context.cacheDir, "merge_snapshot_${System.nanoTime()}.db")
        snapshot = SQLiteDatabase.openOrCreateDatabase(snapshotFile, null)
        for (statement in com.psyche.memo.data.db.loadSchemaStatements(context)) {
            snapshot.execSQL(statement)
        }
    }

    /**
     * Opens a snapshot whose message_rows lost the order uniqueness, the exact
     * "bypassed the database constraints" state the order validator guards
     * against. Returns the file so it can be merged and deleted.
     */
    private fun openCorruptSnapshot(): Pair<SQLiteDatabase, File> {
        val file = File(context.cacheDir, "corrupt_${System.nanoTime()}.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        for (statement in com.psyche.memo.data.db.loadSchemaStatements(context)) {
            db.execSQL(statement.replace(", UNIQUE (\"conversation_id\", \"message_order\")", ""))
        }
        return db to file
    }

    @After
    fun tearDown() {
        snapshot.close()
        snapshotFile.delete()
        container.database.close()
    }

    private fun liveDb(): SQLiteDatabase = container.database.writableDatabase

    private fun conversationIds(db: SQLiteDatabase): List<String> =
        db.rawQuery("SELECT id FROM conversation_rows ORDER BY id", null).use { cursor ->
            val out = mutableListOf<String>()
            while (cursor.moveToNext()) out.add(cursor.getString(0))
            out
        }

    @Test
    fun `a backup-only conversation is imported with its messages and parts`() {
        insertConversation(snapshot, "c1", "From backup")
        insertMessage(snapshot, "m1", "c1", 0, "hello")
        insertMessage(snapshot, "m2", "c1", 1, "world")

        val report = DatabaseSnapshotMerger(liveDb()).merge(snapshotFile)

        assertEquals(1, report.importedConversations)
        assertEquals(0, report.deduplicatedConversations)
        assertEquals(listOf("c1"), conversationIds(liveDb()))
        assertEquals(report.importedConversationIds, listOf("c1"))
        liveDb().rawQuery(
            "SELECT p.payload FROM message_part_rows p WHERE p.conversation_id = 'c1' ORDER BY p.ordinal",
            null,
        ).use { cursor ->
            val texts = mutableListOf<String>()
            while (cursor.moveToNext()) texts.add(cursor.getString(0))
            assertEquals(listOf("""{"text":"hello"}""", """{"text":"world"}"""), texts)
        }
    }

    @Test
    fun `an identical conversation dedupes instead of importing`() {
        insertConversation(liveDb(), "c1", "Same")
        insertMessage(liveDb(), "m1", "c1", 0, "hello")
        insertConversation(snapshot, "c1", "Same")
        insertMessage(snapshot, "m1", "c1", 0, "hello")

        val report = DatabaseSnapshotMerger(liveDb()).merge(snapshotFile)

        assertEquals(0, report.importedConversations)
        assertEquals(1, report.deduplicatedConversations)
        assertEquals(listOf("c1"), conversationIds(liveDb()))
    }

    @Test
    fun `a conflicting conversation is remapped to a deterministic id`() {
        insertConversation(liveDb(), "c1", "Local version")
        insertMessage(liveDb(), "m1", "c1", 0, "local text")
        insertConversation(snapshot, "c1", "Backup version")
        insertMessage(snapshot, "m1", "c1", 0, "backup text")

        val report = DatabaseSnapshotMerger(liveDb()).merge(snapshotFile)

        assertEquals(1, report.importedConversations)
        val remappedId = report.importedConversationIds.single()
        assertTrue("remapped id shape, got: $remappedId", remappedId.startsWith("merge-"))
        assertEquals(remappedId, report.remappedConversationIds["c1"])
        // Both conversations exist: the local one untouched, the backup's copy
        // under the remapped id.
        val ids = conversationIds(liveDb())
        assertEquals(2, ids.size)
        assertTrue(ids.contains("c1"))
        assertTrue(ids.contains(remappedId))
        liveDb().rawQuery(
            "SELECT p.payload FROM message_part_rows p WHERE p.conversation_id = 'c1'",
            null,
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.getString(0).contains("local text"))
        }
        // The imported copy carries the backup's text under the new id.
        liveDb().rawQuery(
            "SELECT p.payload FROM message_part_rows p WHERE p.conversation_id = ?",
            arrayOf(remappedId),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.getString(0).contains("backup text"))
        }
    }

    @Test
    fun `a message id collision alone forces the whole conversation to remap`() {
        insertConversation(liveDb(), "local-only", "Local only")
        insertMessage(liveDb(), "shared-message", "local-only", 0, "local")
        insertConversation(snapshot, "backup-only", "Backup only")
        insertMessage(snapshot, "shared-message", "backup-only", 0, "backup")

        val report = DatabaseSnapshotMerger(liveDb()).merge(snapshotFile)

        assertEquals(1, report.importedConversations)
        val remappedId = report.importedConversationIds.single()
        assertTrue(remappedId.startsWith("merge-"))
        assertTrue(conversationIds(liveDb()).contains("local-only"))
        liveDb().rawQuery(
            "SELECT id FROM message_rows WHERE conversation_id = ?",
            arrayOf(remappedId),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.getString(0).startsWith("merge-"))
        }
    }

    @Test
    fun `a second merge of the same snapshot is a no-op`() {
        insertConversation(snapshot, "c1", "From backup")
        insertMessage(snapshot, "m1", "c1", 0, "hello")

        val merger = DatabaseSnapshotMerger(liveDb())
        val first = merger.merge(snapshotFile)
        val second = merger.merge(snapshotFile)

        assertEquals(1, first.importedConversations)
        assertEquals(1, second.deduplicatedConversations)
        assertEquals(0, second.importedConversations)
        assertEquals(listOf("c1"), conversationIds(liveDb()))
    }

    @Test
    fun `a conversation with a broken message order is skipped`() {
        val (corrupt, corruptFile) = openCorruptSnapshot()
        try {
            corrupt.execSQL(
                "INSERT INTO conversation_rows (id, title, created_at, updated_at) VALUES ('bad', 'Broken', 1, 1)",
            )
            insertMessage(corrupt, "b1", "bad", 2, "one")
            insertMessage(corrupt, "b2", "bad", 2, "two")
            corrupt.execSQL(
                "INSERT INTO conversation_rows (id, title, created_at, updated_at) VALUES ('good', 'Fine', 1, 1)",
            )
            insertMessage(corrupt, "g1", "good", 0, "fine")

            val report = DatabaseSnapshotMerger(liveDb()).merge(corruptFile)

            assertEquals(1, report.importedConversations)
            assertEquals(1, report.skippedConversations)
            assertEquals(listOf("good"), conversationIds(liveDb()))
        } finally {
            corrupt.close()
            corruptFile.delete()
        }
    }
}

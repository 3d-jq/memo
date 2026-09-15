package com.psyche.memo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Covers [AssetDirMigration]: files written by earlier builds into
 * `assistant_avatars` / `user_avatars` / `assistant_backgrounds` must end up in
 * the original `avatars` / `images` directories (which the backup packs and the
 * storage page classifies), with the paths recorded in the database rewritten.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssetDirMigrationTest {

    private lateinit var context: Context
    private lateinit var container: AppContainerImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = AppContainerImpl(context)
        container.database.writableDatabase.execSQL("DELETE FROM assistant_rows")
        container.database.writableDatabase.execSQL("DELETE FROM preference_rows")
        File(context.filesDir, "assistant_avatars").deleteRecursively()
        File(context.filesDir, "user_avatars").deleteRecursively()
        File(context.filesDir, "assistant_backgrounds").deleteRecursively()
    }

    @Test
    fun `moves assistant avatar file and rewrites its payload path`() {
        val legacyDir = File(context.filesDir, "assistant_avatars").apply { mkdirs() }
        val legacyFile = File(legacyDir, "assistant_a1_1.jpg").apply { writeText("img") }

        val id = container.assistantStore.add("A")
        container.assistantStore.update(
            container.assistantStore.get(id)!!.copy(avatar = legacyFile.absolutePath),
        )

        AssetDirMigration.run(context, container.database, container.preferenceRepository)

        val moved = File(AppDirs.avatars(context), "assistant_a1_1.jpg")
        assertTrue("file should be moved into avatars/", moved.isFile)
        assertFalse("legacy dir should be gone", legacyDir.exists())
        assertEquals(moved.absolutePath, container.assistantStore.get(id)?.avatar)
    }

    @Test
    fun `merges user avatar dir into avatars and rewrites preference`() {
        val legacyDir = File(context.filesDir, "user_avatars").apply { mkdirs() }
        val legacyFile = File(legacyDir, "avatar_1.jpg").apply { writeText("img") }
        container.preferenceRepository.writeJson("avatar_value", legacyFile.absolutePath)

        AssetDirMigration.run(context, container.database, container.preferenceRepository)

        val moved = File(AppDirs.avatars(context), "avatar_1.jpg")
        assertTrue(moved.isFile)
        assertEquals(moved.absolutePath, container.preferenceRepository.readJson("avatar_value"))
    }

    @Test
    fun `moves assistant background into images`() {
        val legacyDir = File(context.filesDir, "assistant_backgrounds").apply { mkdirs() }
        File(legacyDir, "background_1.jpg").writeText("img")

        AssetDirMigration.run(context, container.database, container.preferenceRepository)

        assertTrue(File(AppDirs.images(context), "background_1.jpg").isFile)
        assertFalse(legacyDir.exists())
    }

    @Test
    fun `running with no legacy dirs does nothing`() {
        AssetDirMigration.run(context, container.database, container.preferenceRepository)

        // Nothing to move, nothing to rewrite - must not throw.
        assertTrue(AppDirs.avatars(context).isDirectory)
    }
}

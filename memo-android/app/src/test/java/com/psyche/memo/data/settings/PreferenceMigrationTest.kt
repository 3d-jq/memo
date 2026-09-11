package com.psyche.memo.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.AppContainerImpl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers [PreferenceRepository.migrateLegacyLocalSettings]: `display_*` and the
 * three user-profile keys used to live in SharedPreferences while the backup
 * exporter only scans preference_rows, so a backup silently lost them. The
 * migration moves any leftovers into the DB and clears the local slot; a DB
 * value already present (written after the switch) must win. LOCAL_ONLY keys
 * must never be moved either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreferenceMigrationTest {

    private lateinit var container: AppContainerImpl
    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        container = AppContainerImpl(ApplicationProvider.getApplicationContext())
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        prefs = ctx.getSharedPreferences("memo_preferences", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        container.database.writableDatabase.execSQL("DELETE FROM preference_rows")
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `display switch migrates from local into preference_rows`() {
        prefs.edit().putString("display_show_user_avatar_v1", "1").apply()

        container.preferenceRepository.migrateLegacyLocalSettings()

        assertEquals("1", container.preferenceRepository.readJson("display_show_user_avatar_v1"))
        assertNull(prefs.getString("display_show_user_avatar_v1", null))
    }

    @Test
    fun `existing db value wins over the local leftover`() {
        container.preferenceRepository.writeJson("display_show_user_avatar_v1", "0")
        prefs.edit().putString("display_show_user_avatar_v1", "1").apply()

        container.preferenceRepository.migrateLegacyLocalSettings()

        // The freshly written DB value must not be clobbered by the stale local one.
        assertEquals("0", container.preferenceRepository.readJson("display_show_user_avatar_v1"))
        assertNull(prefs.getString("display_show_user_avatar_v1", null))
    }

    @Test
    fun `user profile keys migrate too`() {
        prefs.edit()
            .putString("user_name", "Alice")
            .putString("avatar_type", "emoji")
            .putString("avatar_value", "😀")
            .apply()

        container.preferenceRepository.migrateLegacyLocalSettings()

        assertEquals("Alice", container.preferenceRepository.readJson("user_name"))
        assertEquals("emoji", container.preferenceRepository.readJson("avatar_type"))
        assertEquals("😀", container.preferenceRepository.readJson("avatar_value"))
        assertNull(prefs.getString("user_name", null))
        assertNull(prefs.getString("avatar_type", null))
        assertNull(prefs.getString("avatar_value", null))
    }

    @Test
    fun `unrelated local keys are left alone`() {
        prefs.edit().putString("log_save_output_v1", "1").apply()

        container.preferenceRepository.migrateLegacyLocalSettings()

        // Not a display_* or user key; the local value must survive untouched.
        assertEquals("1", prefs.getString("log_save_output_v1", null))
    }

    @Test
    fun `empty local value is dropped instead of migrated`() {
        prefs.edit().putString("display_show_user_name_v1", "").apply()

        container.preferenceRepository.migrateLegacyLocalSettings()

        assertNull(container.preferenceRepository.readJson("display_show_user_name_v1"))
        assertNull(prefs.getString("display_show_user_name_v1", null))
    }

    @Test
    fun `migration is idempotent`() {
        prefs.edit().putString("display_show_user_avatar_v1", "1").apply()

        container.preferenceRepository.migrateLegacyLocalSettings()
        container.preferenceRepository.migrateLegacyLocalSettings()

        assertEquals("1", container.preferenceRepository.readJson("display_show_user_avatar_v1"))
    }

    @Test
    fun `local-only display key is left untouched`() {
        // display_chat_font_scale_v1 is classified LOCAL_ONLY; the migration
        // must not move it into preference_rows (it belongs in prefs).
        prefs.edit().putString("display_chat_font_scale_v1", "1.15").apply()

        container.preferenceRepository.migrateLegacyLocalSettings()

        // Still readable through the unified readJson (LOCAL_ONLY falls back to
        // SharedPreferences) and never present in the DB.
        assertEquals("1.15", prefs.getString("display_chat_font_scale_v1", null))
        assertEquals("1.15", container.preferenceRepository.readJson("display_chat_font_scale_v1"))
        assertEquals(false, container.preferenceRepository.readAllRows().containsKey("display_chat_font_scale_v1"))
    }

    @Test
    fun `misplaced local-only key returns to prefs`() {
        // An earlier migration version wrongly moved LOCAL_ONLY keys (font scale)
        // into preference_rows; the migration must hand the value back to prefs
        // and drop the DB row.
        container.preferenceRepository.writeJson("display_chat_font_scale_v1", "1.15")

        container.preferenceRepository.migrateLegacyLocalSettings()

        assertEquals("1.15", prefs.getString("display_chat_font_scale_v1", null))
        assertEquals("1.15", container.preferenceRepository.readJson("display_chat_font_scale_v1"))
        assertEquals(false, container.preferenceRepository.readAllRows().containsKey("display_chat_font_scale_v1"))
    }
}

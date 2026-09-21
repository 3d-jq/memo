package com.psyche.memo.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyClassificationTest {

    @Test
    fun entityKeysClassifyAsEntity() {
        assertEquals(KeyDisposition.ENTITY, classifyBusinessKey("assistants_v1"))
        assertEquals(KeyDisposition.ENTITY, classifyBusinessKey("provider_configs_v1"))
        assertEquals(KeyDisposition.ENTITY, classifyBusinessKey("mcp_servers_v1"))
    }

    @Test
    fun providerOrderIsSpecial() {
        assertEquals(KeyDisposition.PROVIDER_ORDER, classifyBusinessKey("providers_order_v1"))
    }

    @Test
    fun localOnlyKeysAndRestorePrefixAreLocal() {
        assertEquals(KeyDisposition.LOCAL_ONLY, classifyBusinessKey("window_width_v1"))
        assertEquals(KeyDisposition.LOCAL_ONLY, classifyBusinessKey("restore_staging_v1"))
        assertEquals(KeyDisposition.LOCAL_ONLY, classifyBusinessKey("display_chat_font_scale_v1"))
    }

    @Test
    fun discardedKeysAreDiscarded() {
        assertEquals(KeyDisposition.DISCARDED, classifyBusinessKey("pinned_chat_ids"))
        assertEquals(KeyDisposition.DISCARDED, classifyBusinessKey("migrations_version_v1"))
    }

    @Test
    fun preferencesAndDisplayPrefixArePreference() {
        assertEquals(KeyDisposition.PREFERENCE, classifyBusinessKey("current_assistant_id_v1"))
        assertEquals(KeyDisposition.PREFERENCE, classifyBusinessKey("theme_mode_v1"))
        assertEquals(KeyDisposition.PREFERENCE, classifyBusinessKey("display_app_font_family_v1"))
    }

    @Test
    fun unknownKeysPassthroughAsUnknown() {
        assertEquals(KeyDisposition.UNKNOWN, classifyBusinessKey("future_setting_v9"))
    }

    @Test
    fun registrySetsDoNotOverlap() {
        val a = SettingsKeyRegistry.LOCAL_ONLY_KEYS
        val b = SettingsKeyRegistry.DISCARDED_KEYS
        val c = SettingsKeyRegistry.PREFERENCE_KEYS
        val d = SettingsKeyRegistry.ENTITY_SOURCE_KEYS
        assertTrue(a.intersect(b).isEmpty())
        assertTrue(a.intersect(c).isEmpty())
        assertTrue(b.intersect(c).isEmpty())
        assertTrue(d.intersect(a + b + c).isEmpty())
    }

    @Test
    fun generatedSetCountsMatchDartSource() {
        assertEquals(9, SettingsKeyRegistry.LOCAL_ONLY_KEYS.size)
        assertEquals(6, SettingsKeyRegistry.DISCARDED_KEYS.size)
        assertEquals(131, SettingsKeyRegistry.PREFERENCE_KEYS.size)
        assertEquals(13, SettingsKeyRegistry.ENTITY_SOURCE_KEYS.size)
    }
}

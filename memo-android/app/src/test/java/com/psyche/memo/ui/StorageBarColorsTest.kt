package com.psyche.memo.ui

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.psyche.memo.ui.theme.AppSemanticColors
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Storage usage bar / legend color mapping regression (user-reported: images
 * and cache rendered the same blue). barColorFor walks a fixed category order
 * and indexes into chartSeries with a modulo — with 10 non-other categories
 * but only 8 series colors, CACHE wrapped to slot 0 (same as IMAGES) and LOGS
 * to slot 1 (same as FILES). These tests pin the palette to at least the
 * category count and pin the exact distinct colors per category.
 */
class StorageBarColorsTest {

    /** Mirrors the private barColorFor order list in StorageSpace.kt. */
    private val categoryOrder = listOf(
        StorageCategoryKey.IMAGES, StorageCategoryKey.FILES, StorageCategoryKey.CHAT_DATA,
        StorageCategoryKey.LEGACY_CHAT_DATA, StorageCategoryKey.RESTORE_TRACES,
        StorageCategoryKey.DISPLACED_DATABASES, StorageCategoryKey.LOCAL_SNAPSHOTS,
        StorageCategoryKey.ASSISTANT_DATA, StorageCategoryKey.CACHE, StorageCategoryKey.LOGS,
    )

    private val series = AppSemanticColors.light(lightColorScheme()).chartSeries

    private fun colorFor(key: StorageCategoryKey): Color {
        if (key == StorageCategoryKey.OTHER) return Color.White.copy(alpha = 0.22f)
        return series[categoryOrder.indexOf(key).mod(series.size)]
    }

    @Test
    fun chartSeriesCoversEveryNonOtherStorageCategory() {
        // The modulo wrap-around that caused the images/cache collision can
        // only happen when the palette is shorter than the category list.
        assertEquals(categoryOrder.size, series.size)
    }

    @Test
    fun everyStorageCategoryGetsADistinctColor() {
        val colors = categoryOrder.map { colorFor(it) }
        assertEquals(categoryOrder.size, colors.toSet().size)
    }

    @Test
    fun imagesAndCacheDoNotCollide() {
        // The exact user-reported pair (both rendered 0xFF2563EB).
        assertEquals(false, colorFor(StorageCategoryKey.IMAGES) == colorFor(StorageCategoryKey.CACHE))
    }
}

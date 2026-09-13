package com.psyche.memo.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 压缩模型回退链：compress → summary → title → assistant → current。 */
class CompressModelTest {

    @Test
    fun `compress slot wins`() {
        assertEquals(
            "compress" to "m1",
            CompressModel.resolve("compress" to "m1", null, null, null, null),
        )
    }

    @Test
    fun `summary and title are the next fallbacks`() {
        assertEquals(
            "summary" to "m2",
            CompressModel.resolve(null, "summary" to "m2", "title" to "m3", null, null),
        )
        assertEquals(
            "title" to "m3",
            CompressModel.resolve(null, null, "title" to "m3", null, "current" to "m5"),
        )
    }

    @Test
    fun `assistant then current model close the chain`() {
        assertEquals(
            "assistant" to "m4",
            CompressModel.resolve(null, null, null, "assistant" to "m4", "current" to "m5"),
        )
        assertEquals(
            "current" to "m5",
            CompressModel.resolve(null, null, null, null, "current" to "m5"),
        )
    }

    @Test
    fun `nothing configured resolves to null`() {
        assertNull(CompressModel.resolve(null, null, null, null, null))
        assertNull(CompressModel.resolve("" to "", null, null, null, null))
    }
}

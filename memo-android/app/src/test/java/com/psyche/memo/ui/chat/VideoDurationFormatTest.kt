package com.psyche.memo.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** 视频卡右下角的时长文案。 */
class VideoDurationFormatTest {

    @Test
    fun formatsUnderAnHour() {
        assertEquals("0:00", formatVideoDuration(0))
        assertEquals("0:00", formatVideoDuration(-5_000))
        assertEquals("0:07", formatVideoDuration(7_000))
        assertEquals("0:59", formatVideoDuration(59_999))
        assertEquals("1:03", formatVideoDuration(63_000))
        assertEquals("59:59", formatVideoDuration(3_599_000))
    }

    @Test
    fun formatsPastAnHour() {
        assertEquals("1:00:00", formatVideoDuration(3_600_000))
        assertEquals("2:05:09", formatVideoDuration(7_509_000))
    }
}

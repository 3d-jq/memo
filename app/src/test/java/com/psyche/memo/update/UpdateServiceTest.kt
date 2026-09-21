package com.psyche.memo.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 一次响应到结论的判定，不碰网络。 */
class UpdateServiceTest {

    private val newer = """{"tag_name":"9.9.9","html_url":"https://x/releases/tag/9.9.9"}"""

    @Test
    fun missingReleaseReadsAsUpToDateInsteadOfAnError() {
        // GitHub 在仓库还没发过 release 时返回 404，报红太难看（用户 2026-09-21）。
        assertEquals(UpdateService.Outcome.UpToDate, UpdateService.outcomeFor(404, "", "1.0.0"))
    }

    @Test
    fun newerTagBecomesAnAvailableUpdate() {
        val outcome = UpdateService.outcomeFor(200, newer, "1.0.0")
        assertTrue(
            "expected Available, got $outcome",
            outcome is UpdateService.Outcome.Available && outcome.info.version == "9.9.9",
        )
    }

    @Test
    fun sameOrOlderTagIsUpToDate() {
        assertEquals(UpdateService.Outcome.UpToDate, UpdateService.outcomeFor(200, newer, "9.9.9"))
        assertEquals(UpdateService.Outcome.UpToDate, UpdateService.outcomeFor(200, newer, "10.0.0"))
    }

    @Test
    fun otherHttpFailuresAreReported() {
        assertEquals(
            UpdateService.Outcome.Failed("HTTP 500"),
            UpdateService.outcomeFor(500, "", "1.0.0"),
        )
    }

    @Test
    fun unparsablePayloadIsAFailureNotACrash() {
        assertTrue(
            UpdateService.outcomeFor(200, "<html>", "1.0.0") is UpdateService.Outcome.Failed,
        )
    }
}

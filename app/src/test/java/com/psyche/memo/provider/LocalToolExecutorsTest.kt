package com.psyche.memo.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port coverage of the local-tool executors' pure parts. */
class LocalToolExecutorsTest {

    private fun args(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    // ---- calculate ----

    @Test
    fun `calculate evaluates arithmetic and keeps dart number formatting`() {
        val result = Json.parseToJsonElement(
            LocalToolExecutors.calculate(args("""{"expression":"(15 + 3) * 2"}""")),
        ) as JsonObject
        assertEquals("(15 + 3) * 2", (result["expression"] as JsonPrimitive).content)
        assertEquals("36", (result["result"] as JsonPrimitive).content)

        val decimal = Json.parseToJsonElement(
            LocalToolExecutors.calculate(args("""{"expression":"10 / 4"}""")),
        ) as JsonObject
        assertEquals("2.5", (decimal["result"] as JsonPrimitive).content)
    }

    @Test
    fun `calculate supports functions and constants`() {
        val sqrt = Json.parseToJsonElement(
            LocalToolExecutors.calculate(args("""{"expression":"sqrt(16) + 2^3"}""")),
        ) as JsonObject
        assertEquals("12", (sqrt["result"] as JsonPrimitive).content)
    }

    @Test
    fun `empty expression reports the upstream error`() {
        val result = Json.parseToJsonElement(LocalToolExecutors.calculate(args("""{"expression":"  "}"""))) as JsonObject
        assertEquals("empty_expression", (result["error"] as JsonPrimitive).content)
    }

    @Test
    fun `parse failure reports parse_error with detail`() {
        val result = Json.parseToJsonElement(
            LocalToolExecutors.calculate(args("""{"expression":"1 +"}""")),
        ) as JsonObject
        assertEquals("parse_error", (result["error"] as JsonPrimitive).content)
        assertTrue(result.containsKey("detail"))
    }

    // ---- screen time foreground model ----

    private fun fg(ts: Long, pkg: String) = DeviceLocalTools.UsageEvent(ts, pkg, true)
    private fun bg(ts: Long, pkg: String) = DeviceLocalTools.UsageEvent(ts, pkg, false)

    @Test
    fun `foreground time settles the previous app on switch`() {
        val result = DeviceLocalTools.computeForegroundTime(
            events = listOf(fg(0, "a"), fg(1_000, "b"), bg(1_500, "b")),
            startMs = 0,
            endMs = 2_000,
        )
        assertEquals(1_000L, result["a"])
        assertEquals(500L, result["b"])
    }

    @Test
    fun `a segment already running before the window is clipped into range`() {
        val result = DeviceLocalTools.computeForegroundTime(
            events = listOf(fg(-5_000, "a"), bg(3_000, "a")),
            startMs = 0,
            endMs = 10_000,
        )
        assertEquals(3_000L, result["a"])
    }

    @Test
    fun `excluded launcher packages do not accumulate time`() {
        val result = DeviceLocalTools.computeForegroundTime(
            events = listOf(fg(0, "launcher"), fg(1_000, "app")),
            startMs = 0,
            endMs = 2_000,
            excludedPackages = setOf("launcher"),
        )
        assertTrue(!result.containsKey("launcher"))
        assertEquals(1_000L, result["app"])
    }

    // ---- calendar reminders parsing ----

    @Test
    fun `reminder minutes accept arrays strings negatives and dedupe`() {
        val raw = Json.parseToJsonElement("[10,\"20\",10,-30,\"abc\",null,40320,99999]")
        assertEquals(listOf(10, 20, 30, 40320), LocalToolExecutors.parseReminderMinutes(raw))
    }

    @Test
    fun `reminder minutes accept a single scalar and cap at five entries`() {
        assertEquals(listOf(15), LocalToolExecutors.parseReminderMinutes(JsonPrimitive(15)))
        assertEquals(listOf(15), LocalToolExecutors.parseReminderMinutes(JsonPrimitive("15")))
        val many = Json.parseToJsonElement("[1,2,3,4,5,6,7]")
        assertEquals(listOf(1, 2, 3, 4, 5), LocalToolExecutors.parseReminderMinutes(many))
        assertEquals(emptyList<Int>(), LocalToolExecutors.parseReminderMinutes(null))
    }

    @Test
    fun `an unterminated segment is settled at the window end`() {
        val result = DeviceLocalTools.computeForegroundTime(
            events = listOf(fg(500, "a")),
            startMs = 0,
            endMs = 4_000,
        )
        assertEquals(3_500L, result["a"])
    }
}

package com.psyche.memo.provider.tool

import com.psyche.memo.provider.chart.MermaidTools
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具执行骨架（照 deepseek-harness 的 execute() 契约，`docs/ENGINEERING_HARNESS.md` §8）。
 *
 * 三条被守的东西：**统一 deadline**（卡住不挂死）、**抛错 ⇒ error 不吞**、**结果有上限且标明截断**。
 * 反例（照 §4「护栏要证明能拒绝无效输入」）：超时/抛错/超长都必须被正确判红，而不是原样返回。
 */
class ToolRunnerTest {

    private fun parse(raw: String) = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun `a normal result passes through unchanged`() = runBlocking {
        val out = ToolRunner.run("demo_tool") { """{"status":"ok"}""" }
        assertEquals("""{"status":"ok"}""", out)
    }

    /** 反例：body 说「不是我负责」时，必须原样返回 null，不能被误判成超时或错误。 */
    @Test
    fun `declining the tool stays null`() = runBlocking {
        assertNull(ToolRunner.run("demo_tool") { null })
    }

    /** 反例：抛异常必须归一成 tool_error（不吞、不上抛）。 */
    @Test
    fun `a throwing body becomes a canonical error`() = runBlocking {
        val out = ToolRunner.run("demo_tool") { error("disk on fire") }
        val obj = parse(out!!)
        assertEquals("tool_error", obj["type"]?.jsonPrimitive?.content)
        assertEquals("error", obj["status"]?.jsonPrimitive?.content)
        assertEquals("tool_crashed", obj["error"]?.jsonPrimitive?.content)
        assertTrue(obj["message"]!!.jsonPrimitive.content.contains("disk on fire"))
        assertEquals("demo_tool", obj["tool"]?.jsonPrimitive?.content)
    }

    /** 反例：挂住的工具到点必须回超时错误，而不是把整轮对话拖死。 */
    @Test
    fun `a hanging body hits the deadline`() = runBlocking {
        val started = System.currentTimeMillis()
        val out = ToolRunner.run("demo_tool", timeoutMs = 40L) {
            delay(5_000)
            """{"never":true}"""
        }
        val elapsed = System.currentTimeMillis() - started
        val obj = parse(out!!)
        assertEquals("tool_timeout", obj["error"]?.jsonPrimitive?.content)
        assertEquals("error", obj["status"]?.jsonPrimitive?.content)
        assertTrue("必须在 deadline 附近返回（实际 ${elapsed}ms）", elapsed < 2_000)
    }

    /** 反例：超长结果必须被截断**并标明**，不许伪装成完整结果。 */
    @Test
    fun `an oversized result is truncated and labelled`() {
        val huge = "x".repeat(ToolRunner.MAX_RESULT_CHARS + 500)
        val obj = parse(ToolRunner.cap(huge))
        assertEquals(true, obj["truncated"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("ok_truncated", obj["status"]?.jsonPrimitive?.content)
        assertEquals(
            (huge.length).toString(),
            obj["originalChars"]?.jsonPrimitive?.content,
        )
        assertEquals(ToolRunner.MAX_RESULT_CHARS, obj["content"]!!.jsonPrimitive.content.length)

        // 没超限的必须原样（不许无脑加壳）
        assertEquals("short", ToolRunner.cap("short"))
    }

    @Test
    fun `per-tool deadlines come from the policy table, not the tools`() {
        assertTrue(ToolRunner.timeoutFor("whatever") == ToolRunner.DEFAULT_TIMEOUT_MS)
        assertTrue(
            "mermaid 内部就有 25s 超时，外面必须更宽（策略集中在 ToolRunner）",
            ToolRunner.timeoutFor(MermaidTools.TOOL_NAME) > ToolRunner.DEFAULT_TIMEOUT_MS,
        )
    }

    @Test
    fun `the canonical success shape carries status, tool and the tool's own fields`() {
        val obj = parse(
            com.psyche.memo.provider.tool.ToolResults.ok(
                tool = "render_visual",
                type = "chart_result",
                fields = buildJsonObject { put("rendered", true) },
            ),
        )
        assertEquals("ok", obj["status"]?.jsonPrimitive?.content)
        assertEquals("chart_result", obj["type"]?.jsonPrimitive?.content)
        assertEquals("render_visual", obj["tool"]?.jsonPrimitive?.content)
        assertEquals(true, obj["rendered"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `the canonical error shape keeps the established keys`() {
        val obj = parse(
            com.psyche.memo.provider.tool.ToolResults.error(
                code = "svg_invalid",
                message = "root must be <svg>",
                tool = "render_visual",
                instruction = "只画基础形状",
            ),
        )
        assertEquals("tool_error", obj["type"]?.jsonPrimitive?.content)
        assertEquals("error", obj["status"]?.jsonPrimitive?.content)
        assertEquals("svg_invalid", obj["error"]?.jsonPrimitive?.content)
        assertEquals("只画基础形状", obj["instruction"]?.jsonPrimitive?.content)
    }
}

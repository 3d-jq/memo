package com.psyche.memo.provider.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 参数校验器（B8）：一次报全、可选参数不误伤、错误形状带 `violations` 数组。
 *
 * 三条各自对应一种坏行为：
 * - 只报第一条 → 模型修一处撞一处，一轮对话反复在同一颗调用上打转；
 * - 可选参数当必填 → 合法的 `workspace_list`（不传 path）被拒，工具直接不可用；
 * - 没有 violations 数组 → 模型只能从散文里猜是哪颗参数。
 */
class ToolArgsTest {

    private fun args(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) =
        JsonObject(pairs.toMap())

    @Test
    fun `missing required path is reported with a fix`() {
        val a = ToolArgs(args())
        a.absolutePath("path")
        val violation = a.violations.single()
        assertEquals("path", violation.param)
        assertTrue(violation.constraint.contains("absolute", ignoreCase = true))
        assertTrue("fix 要给出能照抄的样子", violation.fix.contains("/workspace/"))
    }

    @Test
    fun `relative path is refused and shown back`() {
        val a = ToolArgs(args("path" to JsonPrimitive("notes/a.md")))
        assertEquals(null, a.absolutePath("path"))
        val violation = a.violations.single()
        assertTrue(violation.constraint.contains("notes/a.md"))
        assertTrue(violation.fix.contains("/workspace/notes/a.md"))
    }

    @Test
    fun `backslashes are normalised and NUL refused`() {
        // 反斜杠先归一，但归一完还是相对路径 ⇒ 照样拒（模型常从 Windows 那边抄路径）。
        val rel = ToolArgs(args("path" to JsonPrimitive("notes\\a.md")))
        assertEquals(null, rel.absolutePath("path"))
        assertTrue(rel.violations.single().fix.contains("/workspace/notes/a.md"))

        val ok = ToolArgs(args("path" to JsonPrimitive("/workspace/a.md")))
        assertEquals("/workspace/a.md", ok.absolutePath("path"))
        assertTrue(ok.violations.isEmpty())

        val nul = ToolArgs(args("path" to JsonPrimitive("/workspace/a\u0000b")))
        assertEquals(null, nul.absolutePath("path"))
        assertEquals("path", nul.violations.single().param)
    }

    @Test
    fun `optional parameters are not violations when absent`() {
        val a = ToolArgs(args("pattern" to JsonPrimitive("*.md")))
        a.string("pattern")
        a.absolutePath("path", required = false)
        a.string("glob", required = false)
        assertTrue("可选参数缺了不该报", a.violations.isEmpty())
    }

    /** 一次调用报全，而不是修一个撞一个。 */
    @Test
    fun `every problem in one call is reported together`() {
        val a = ToolArgs(args("path" to JsonPrimitive("relative.md")))
        a.absolutePath("path")
        a.string("old_text")
        a.string("new_text")
        assertEquals(listOf("path", "old_text", "new_text"), a.violations.map { it.param })
        assertFalse(a.isValid)
    }

    @Test
    fun `boolean and integer shapes are checked without throwing`() {
        val a = ToolArgs(
            args(
                "replace_all" to JsonPrimitive("true"),
                "timeout" to JsonPrimitive(9999),
            ),
        )
        assertTrue("字符串 \"true\" 仍按 true 收下", a.boolean("replace_all", default = false))
        assertEquals(30, a.int("timeout", default = 30, minimum = 1, maximum = 600))
        assertEquals(
            "超上限的 timeout 要点名区间，并回落到默认值",
            listOf("timeout"),
            a.violations.map { it.param },
        )
        val b = ToolArgs(args("timeout" to JsonPrimitive("abc")))
        assertEquals(30, b.int("timeout", default = 30, minimum = 1, maximum = 600))
        assertEquals(1, b.violations.size)
        assertTrue(b.violations.single().constraint.contains("1 and 600"))
    }

    @Test
    fun `the error payload carries the violations array`() {
        val json = Json.parseToJsonElement(
            ToolResults.error(
                code = "invalid_arguments",
                message = "`workspace_edit_file` was not executed: 2 argument problem(s).",
                tool = "workspace_edit_file",
                instruction = "Fix the arguments listed in `violations` and call again.",
                violations = listOf(
                    ArgViolation("path", "must start with \"/\"", "prefix it with /workspace/"),
                    ArgViolation("old_text", "required, non-empty text", "call again with \"old_text\" filled in"),
                ),
            ),
        ) as JsonObject
        assertEquals("error", (json["status"] as JsonPrimitive).contentOrNull)
        assertEquals("invalid_arguments", (json["error"] as JsonPrimitive).contentOrNull)
        val violations = json["violations"] as JsonArray
        assertEquals(2, violations.size)
        val first = violations[0] as JsonObject
        assertEquals("path", (first["param"] as JsonPrimitive).contentOrNull)
        assertEquals(
            "prefix it with /workspace/",
            (first["fix"] as JsonPrimitive).contentOrNull,
        )
    }

    /** 没有违规时不许出现 `violations` 键 —— 错误形状要保持原样，别给模型多一个 null 字段。 */
    @Test
    fun `the violations key is omitted when there are none`() {
        val json = Json.parseToJsonElement(
            ToolResults.error(
                code = "not_read",
                message = "x",
                tool = "workspace_edit_file",
                instruction = "y",
            ),
        ) as JsonObject
        assertFalse("不许凭空多一个 violations 键", json.containsKey("violations"))
    }
}

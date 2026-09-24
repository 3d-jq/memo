package com.psyche.memo.provider.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连续重复调用检测（照 deepseek-harness `guard/repeat-tool-reminder`）。
 *
 * 守三件实事：**只有连着同样参数才提醒**（换参数 / 换工具 / 夹一颗别的调用就断链）、
 * **属性顺序不同但内容相同要算同一次**（模型每轮重排 key 是常态）、提醒**分级**
 * （首次温和、后面点名）。反例：把无关调用误判成循环，或者参数顺序一变就漏判。
 */
class RepeatCallGuardTest {

    private val guard = RepeatCallGuard()

    private fun observe(tool: String = "workspace_read_file", args: String = """{"path":"/a"}""") =
        guard.observe(tool, args)

    @Test
    fun `the first two identical calls stay quiet and the third nudges`() {
        assertNull(observe())
        assertNull(observe())
        assertEquals(RepeatCallGuard.GENTLE_REMINDER, observe())
    }

    /** 反例：换了参数的正常推进不能被当成循环。 */
    @Test
    fun `different arguments break the chain`() {
        assertNull(observe(args = """{"path":"/a"}"""))
        assertNull(observe(args = """{"path":"/b"}"""))
        assertNull(observe(args = """{"path":"/c"}"""))
    }

    /** 反例（最容易写错的一处）：模型把 key 重排了，内容其实完全一样。 */
    @Test
    fun `argument key order does not hide a repeat`() {
        assertNull(observe(args = """{"path":"/a","offset":0}"""))
        assertNull(observe(args = """{"offset":0,"path":"/a"}"""))
        assertEquals(RepeatCallGuard.GENTLE_REMINDER, observe(args = """{"path":"/a","offset":0}"""))
    }

    /** 嵌套对象里的键序也要归一。 */
    @Test
    fun `nested keys are sorted too`() {
        assertEquals(
            RepeatCallGuard.canonicalize("""{"a":{"x":1,"y":2}}"""),
            RepeatCallGuard.canonicalize("""{"a":{"y":2,"x":1}}"""),
        )
    }

    /** 一颗别的调用插在中间 —— 链断了，重新从 1 数。 */
    @Test
    fun `an interleaved call resets the chain`() {
        assertNull(observe())
        assertNull(observe())
        assertNull(observe(tool = "get_time_info", args = "{}"))
        assertNull(observe())
        assertNull(observe())
        assertEquals(RepeatCallGuard.GENTLE_REMINDER, observe())
    }

    /** 第二个阈值起改成点名式：工具名、连击次数、参数摘要都要在。 */
    @Test
    fun `later thresholds name the tool and the run length`() {
        repeat(4) { observe(args = """{"path":"/loop"}""") }
        val fifth = observe(args = """{"path":"/loop"}""")!!
        assertTrue(fifth.contains("workspace_read_file"))
        assertTrue(fifth.contains("consecutive_calls: 5"))
        assertTrue(fifth.contains("/loop"))
        assertTrue(fifth.contains("Do not call this tool with these exact arguments again"))
    }

    /** 大参数（写文件的正文）只截断**展示**，比较仍用完整串。 */
    @Test
    fun `quoted arguments are capped but full arguments are compared`() {
        val body = """{"content":"${"x".repeat(900)}"}"""
        assertTrue(RepeatCallGuard.preview(body, 500).contains("more chars"))
        repeat(2) { guard.observe("workspace_write_file", body) }
        assertEquals(RepeatCallGuard.GENTLE_REMINDER, guard.observe("workspace_write_file", body))
        assertNull(guard.observe("workspace_write_file", body))
        val detailed = guard.observe("workspace_write_file", body)!!
        assertTrue(detailed.contains("more chars"))
        assertTrue(detailed.contains("consecutive_calls: 5"))
        // 比较用完整串：内容不同就不能算同一次（哪怕前 500 字符一模一样）
        assertNull(guard.observe("workspace_write_file", """{"content":"${"y".repeat(900)}"}"""))
    }

    /** 畸形参数不许静默成「没重复」（上游：落到原始字符串）。 */
    @Test
    fun `malformed arguments still count as the same call`() {
        assertNull(observe(args = "{not json"))
        assertNull(observe(args = "{not json"))
        assertEquals(RepeatCallGuard.GENTLE_REMINDER, observe(args = "{not json"))
    }

    /** 没有参数（空 body 的工具，如 get_current_location）也要能识别重复。 */
    @Test
    fun `absent arguments canonicalize to one identity`() {
        val local = RepeatCallGuard()
        assertNull(local.observe("get_current_location", null))
        assertNull(local.observe("get_current_location", null))
        assertEquals(RepeatCallGuard.GENTLE_REMINDER, local.observe("get_current_location", null))
    }

    /** 实例即作用域：新一生成（= 用户又说了话）从 1 重新数。 */
    @Test
    fun `a fresh guard restarts the chain`() {
        repeat(3) { observe() }
        assertNull(RepeatCallGuard().observe("workspace_read_file", "{}"))
    }

    /** 配错要炸（阈值 1 会让第一次正常调用就被提醒）。 */
    @Test(expected = IllegalArgumentException::class)
    fun `a threshold below two is rejected`() {
        RepeatCallGuard(thresholds = listOf(1, 3))
    }
}

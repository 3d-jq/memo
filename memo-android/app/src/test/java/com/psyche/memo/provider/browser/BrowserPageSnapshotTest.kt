package com.psyche.memo.provider.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览器寻址契约（spec §3）：模型手里只有 `[index]` 与 `generation`，**看不到 CSS
 * selector**。代次不匹配就不执行 —— Eta 那套 selector 直寻的坑是「同一个 selector
 * 静悄悄点到另一个元素」，这里用一条纯逻辑把它挡住。
 */
class BrowserPageSnapshotTest {

    private fun el(i: Int, label: String) = BrowserElement(
        index = i, tag = "button", role = null, text = label, placeholder = null,
        type = null, href = null, selector = "body > button:nth-of-type($i)",
        bounds = Bounds(i, i, 100, 40),
    )

    private fun snapshot(gen: Int) = BrowserPageSnapshot(
        generation = gen, url = "https://example.com", title = "示例",
        elements = (1..25).map { el(it, "按钮 $it") },
    )

    @Test
    fun staleGenerationIsNeverExecuted() {
        assertEquals(GenerationCheck.Stale(seen = 3, now = 4), checkGeneration(3, current = 4))
        assertEquals(GenerationCheck.Current, checkGeneration(4, current = 4))
        assertTrue(
            "模型漏传 generation 也不能猜，要单独一路补救话",
            checkGeneration(null, current = 4) is GenerationCheck.UnknownGeneration,
        )
    }

    @Test
    fun findBlockIsCappedAndIndexIsTheHandle() {
        val text = renderFindBlock(snapshot(4))
        val rows = text.lines().count { it.startsWith("[") }
        assertTrue("上限 $FIND_LIMIT 条，实际 $rows", rows <= FIND_LIMIT)
        assertTrue(text.contains("[1] "))
        assertTrue("越界的 21 号不许出现", !text.contains("[21]"))
        assertTrue("必须告诉模型当前代次号", text.contains("generation=4"))
        assertTrue("截断要说明还剩多少", text.contains("还有 5 个"))
        assertTrue("绝不能把 selector 漏给模型", !text.contains("nth-of-type"))
    }

    @Test
    fun indexesResolveToTheirSelector() {
        assertEquals("body > button:nth-of-type(7)", snapshot(1).find(7)?.selector)
        assertNull("0 号不存在（编号从 1 开始）", snapshot(1).find(0))
        assertNull(snapshot(1).find(26))
    }
}

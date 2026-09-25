package com.psyche.memo.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览器开关的解码：**没写过键就是开**（用户 2026-09-25「改成默认开着的」）。
 *
 * `decodeBool` 是纯函数，所以这里只钉三件事：缺失 → true、`"0"` → false、
 * 早期可能存在的裸 `false` 也要认（同一个键历史上可能出现过两种存储形态）。
 */
class AgentCapabilityGateTest {

    @Test
    fun missingKeyMeansOn() {
        assertTrue(DisplayPrefs.decodeBool(null, default = true))
        assertFalse(DisplayPrefs.decodeBool("0", default = true))
        assertFalse(DisplayPrefs.decodeBool("false", default = true))
        assertTrue(DisplayPrefs.decodeBool("1", default = false))
    }
}

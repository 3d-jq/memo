package com.psyche.memo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * API 端点三选一（用户 2026-09-12 点名：把「Response API 开关 + 手填路径」合成
 * 一个选择面板）。`useResponseApi` 必须与路径同步 —— 模型检测与导入都读这个标记。
 */
class ApiPathOptionTest {

    @Test
    fun threeOptionsMatchTheRequestedLabelsAndPaths() {
        assertEquals(
            listOf("/v1/messages", "/chat/completions", "/responses"),
            API_PATH_OPTIONS.map { it.path },
        )
        assertEquals(
            listOf("Anthropic Messages", "Chat Completions", "Responses"),
            API_PATH_OPTIONS.map { it.label },
        )
    }

    @Test
    fun currentOptionFollowsTheStoredPath() {
        assertEquals("/chat/completions", apiPathOptionFor("/chat/completions", null).path)
        assertEquals("/v1/messages", apiPathOptionFor("/v1/messages", false).path)
        assertEquals("/responses", apiPathOptionFor("/responses", true).path)
        // 没存路径时按 useResponseApi 推默认（原版 model_provider.dart L439）。
        assertEquals("/responses", apiPathOptionFor(null, true).path)
        assertEquals("/chat/completions", apiPathOptionFor(null, null).path)
    }

    @Test
    fun customPathIsShownVerbatim() {
        val option = apiPathOptionFor("/v1/chat/completions", null)
        assertEquals("/v1/chat/completions", option.path)
        assertEquals("/v1/chat/completions", option.label)
    }

    @Test
    fun useResponseApiStaysInSyncWithThePath() {
        assertEquals(true, useResponseApiFor("/responses"))
        assertNull(useResponseApiFor("/chat/completions"))
        assertNull(useResponseApiFor("/v1/messages"))
    }
}

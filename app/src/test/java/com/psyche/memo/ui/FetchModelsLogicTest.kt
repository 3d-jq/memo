package com.psyche.memo.ui

import com.psyche.memo.data.model.ProviderConfig
import com.psyche.memo.llm.client.LlmModelInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 获取模型选择面板的纯逻辑（`_showModelPicker`，provider_detail_page
 * L3341-4019）：家族分组、搜索过滤、整组/单行 加·减、全选与反选（都只作用于
 * **当前可见**的模型）、SiliconFlow 免费模型限制。
 */
class FetchModelsLogicTest {

    private val embeddings = "嵌入模型"
    private val other = "其他模型"

    private fun info(id: String) = LlmModelInfo(id = id, displayName = id)

    @Test
    fun groupsFollowTheFlutterFamilyTable() {
        assertEquals("嵌入模型", ModelGrouping.groupFor("text-embedding-3-large", embeddings, other))
        assertEquals("GPT", ModelGrouping.groupFor("gpt-4o", embeddings, other))
        assertEquals("GPT", ModelGrouping.groupFor("o3-mini", embeddings, other))
        assertEquals("GPT", ModelGrouping.groupFor("o1-preview", embeddings, other))
        assertEquals("Gemini 3", ModelGrouping.groupFor("gemini-3-pro-preview", embeddings, other))
        assertEquals("Gemini 2.5", ModelGrouping.groupFor("gemini-2.5-flash", embeddings, other))
        assertEquals("Gemini", ModelGrouping.groupFor("gemini-2.0-flash", embeddings, other))
        assertEquals("Claude 4", ModelGrouping.groupFor("claude-4-opus", embeddings, other))
        assertEquals("Claude Sonnet", ModelGrouping.groupFor("claude-sonnet-4", embeddings, other))
        assertEquals("Claude Opus", ModelGrouping.groupFor("claude-opus-3", embeddings, other))
        assertEquals("Claude Haiku", ModelGrouping.groupFor("claude-haiku-3.5", embeddings, other))
        assertEquals("Claude 3.5", ModelGrouping.groupFor("claude-3.5-sonnet", embeddings, other))
        assertEquals("Claude 3", ModelGrouping.groupFor("claude-3-opus", embeddings, other))
        assertEquals("DeepSeek", ModelGrouping.groupFor("deepseek-chat", embeddings, other))
        assertEquals("Kimi", ModelGrouping.groupFor("kimi-k2-0905", embeddings, other))
        assertEquals("Qwen", ModelGrouping.groupFor("qwen3-max", embeddings, other))
        assertEquals("Qwen", ModelGrouping.groupFor("dashscope/qwq-plus", embeddings, other))
        assertEquals("Doubao", ModelGrouping.groupFor("doubao-seed-1.6", embeddings, other))
        assertEquals("Doubao", ModelGrouping.groupFor("volc/ark-model", embeddings, other))
        assertEquals("GLM", ModelGrouping.groupFor("glm-4.6", embeddings, other))
        assertEquals("GLM", ModelGrouping.groupFor("zhipu-air", embeddings, other))
        assertEquals("Mistral", ModelGrouping.groupFor("mistral-large", embeddings, other))
        assertEquals("MiniMax", ModelGrouping.groupFor("minimax-m2", embeddings, other))
        assertEquals("Grok", ModelGrouping.groupFor("grok-4", embeddings, other))
        assertEquals("Grok", ModelGrouping.groupFor("xai-grok", embeddings, other))
        assertEquals("KAT", ModelGrouping.groupFor("kat-coder", embeddings, other))
        assertEquals("其他模型", ModelGrouping.groupFor("some-local-model", embeddings, other))
    }

    @Test
    fun groupsAreSortedByNameAndKeepServerOrderInside() {
        val groups = groupFetchedModels(
            items = listOf(
                info("gpt-4o"),
                info("deepseek-chat"),
                info("gpt-4o-mini"),
                info("zzz-unknown"),
            ),
            embeddingsLabel = embeddings,
            otherLabel = other,
        )
        // 组名按小写排序：DeepSeek < GPT < 其他模型（'其' 的 code point 比字母大）。
        assertEquals(listOf("DeepSeek", "GPT", "其他模型"), groups.map { it.first })
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), groups[1].second.map { it.id })
    }

    @Test
    fun filterMatchesIdOrDisplayName() {
        val items = listOf(
            LlmModelInfo(id = "gpt-4o", displayName = "GPT-4o 旗舰"),
            info("deepseek-chat"),
        )
        assertEquals(listOf("deepseek-chat"), filterFetchedModels(items, "DEEP").map { it.id })
        assertEquals(listOf("gpt-4o"), filterFetchedModels(items, "旗舰").map { it.id })
        assertEquals(items.size, filterFetchedModels(items, "  ").size)
    }

    @Test
    fun groupAndRowTogglesAddAndRemove() {
        assertEquals(
            listOf("a", "b", "c"),
            toggleGroupSelection(models = listOf("a"), groupIds = listOf("b", "c"), allAdded = false),
        )
        assertEquals(
            listOf("a"),
            toggleGroupSelection(models = listOf("a", "b", "c"), groupIds = listOf("b", "c"), allAdded = true),
        )
        assertEquals(listOf("a", "b"), toggleModelSelection(listOf("a"), "b", added = false))
        assertEquals(listOf("a"), toggleModelSelection(listOf("a", "b"), "b", added = true))
    }

    @Test
    fun selectAllAndInvertOnlyTouchTheVisibleOnes() {
        val models = listOf("keep", "x", "y")
        // 全选（可见 x/y）→ 已有的 keep 不动、顺序保持。
        assertEquals(
            listOf("keep", "x", "y"),
            selectAllVisible(models, visibleIds = listOf("x", "y"), allSelected = false),
        )
        // 可见的全都已在列表里 → 再次点击 = 清空这些（keep 留下）。
        assertEquals(
            listOf("keep"),
            selectAllVisible(models, visibleIds = listOf("x", "y"), allSelected = true),
        )
        // 反选：x 在 → 去掉，z（可见但没加过）→ 加上。
        assertEquals(
            listOf("keep", "y", "z"),
            invertVisible(models, visibleIds = listOf("x", "z")),
        )
        assertEquals(models, invertVisible(models, visibleIds = emptyList()))
    }

    @Test
    fun siliconflowWithoutAUserKeyOnlyOffersTheFreePair() {
        val builtin = ProviderConfig(id = "SiliconFlow", name = "SiliconFlow", apiKey = "")
        assertTrue(restrictToFreeModels(builtin))
        assertEquals(
            listOf("THUDM/GLM-4-9B-0414", "Qwen/Qwen3-8B"),
            SILICONFLOW_FREE_MODEL_IDS,
        )
        // 有 key（单 key 或多 Key）就不限制。
        assertFalse(restrictToFreeModels(builtin.copy(apiKey = "sk-x")))
        assertFalse(
            restrictToFreeModels(
                builtin.copy(
                    multiKeyEnabled = true,
                    apiKeys = listOf(
                        com.psyche.memo.data.model.ApiKeyConfig(id = "k1", key = "sk-y"),
                    ),
                ),
            ),
        )
        // 别的供应商永远不限制。
        assertFalse(restrictToFreeModels(builtin.copy(id = "OpenAI")))
    }
}

package com.psyche.memo.provider.prompt

import com.psyche.memo.llm.prompt.PromptTransformer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「可用变量」清单的**单一所有者**护栏（`docs/ENGINEERING_HARNESS.md` §1）。
 *
 * 事实存在两处：core:llm 的变量表（真做替换的那份）与助手编辑页给用户看的清单。
 * 原先两边各写一份、没人管 —— 这里把它们钉成**等价**：谁少写一个、多写一个，测试就红。
 */
class PromptVariableCatalogTest {

    @Test
    fun `the UI catalog and the transformer agree in both directions`() {
        val supported = PromptTransformer.supportedKeys()
        val listed = PromptVariableCatalog.keys

        assertEquals(
            "页面列出但实现不支持的变量（点了会原样留在提示词里）",
            emptySet<String>(),
            listed - supported,
        )
        assertEquals(
            "实现支持但页面没列出的变量（用户根本不知道能用）",
            emptySet<String>(),
            supported - listed,
        )
        assertEquals(12, listed.size)
    }

    @Test
    fun `time sensitive keys are a subset of the listed keys`() {
        assertTrue(
            PromptVariableCatalog.timeSensitiveKeys.all { it in PromptVariableCatalog.keys },
        )
        assertTrue(PromptVariableCatalog.timeSensitiveKeys.isNotEmpty())
    }

    /** 页面顺序稳定（下拉/插入顺序依赖它）。 */
    @Test
    fun `entries keep a stable order starting with the date variables`() {
        assertEquals(
            listOf("{cur_date}", "{cur_time}", "{cur_datetime}"),
            PromptVariableCatalog.entries.take(3).map { it.second },
        )
    }
}

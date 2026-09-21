package com.psyche.memo.ui

import com.psyche.memo.provider.LocalToolExecutors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地工具的**三道闸必须一致**：
 *
 * 1. [BuiltInToolCatalog.isAvailableOnThisPlatform] —— 平台有没有这个能力
 * 2. [BuiltInToolCatalog.offeredLocalToolNames] —— 递不递给模型（`ChatViewModel.offeredTools`）
 * 3. [LocalToolExecutors.EXECUTABLE] —— 模型真调了，有没有人执行
 *
 * 2026-09-21 加定位工具时我只开了 1 和 3、漏了 2（那时 2 还是 `ChatViewModel` 里手抄的
 * `setOf(...)`），结果工具被静默滤掉，模型答「我没有这个工具」，而门禁/编译全绿。
 * 现在 2 从 3 推导，这份测试钉住三者不再各自漂移。
 */
class LocalToolOfferingTest {

    private val names = BuiltInToolCatalog.LocalToolNames
    private val offered = BuiltInToolCatalog.offeredLocalToolNames()

    @Test
    fun locationToolPassesAllThreeGates() {
        assertTrue("平台闸没开", BuiltInToolCatalog.isAvailableOnThisPlatform(names.CURRENT_LOCATION))
        assertTrue("没递给模型（就是上次那个 bug）", names.CURRENT_LOCATION in offered)
        assertTrue(
            "没有执行器，模型一调就 execution_error",
            names.CURRENT_LOCATION in LocalToolExecutors.EXECUTABLE,
        )
    }

    /** 递给模型的名字都必须平台可用且有执行器（`TIME_INFO`/`ASK_USER` 在 ToolHandler 里自理）。 */
    @Test
    fun everyOfferedToolIsAvailableAndExecutableSomewhere() {
        val handledElsewhere = setOf(names.TIME_INFO, names.ASK_USER)
        for (name in offered) {
            assertTrue("$name 平台闸是 false", BuiltInToolCatalog.isAvailableOnThisPlatform(name))
            assertTrue(
                "$name 递给了模型却没有执行器",
                name in LocalToolExecutors.EXECUTABLE || name in handledElsewhere,
            )
        }
    }

    /** 反方向：平台说「有」的名字，必须真的递给模型，否则等于功能不存在。 */
    @Test
    fun everyAvailableLocalToolIsOffered() {
        val invisible = names.all.filter {
            BuiltInToolCatalog.isAvailableOnThisPlatform(it) && it !in offered
        }
        assertEquals("开了平台闸却没递给模型", emptyList<String>(), invisible)
    }

    /**
     * `localDefinition` 的 `else` 分支会返回**空描述**的定义，所以「描述非空 + 名字对得上」
     * 就是漏写分支的探针（`get_current_location` 一开始就差这一支）。
     */
    @Test
    fun everyOfferedToolHasARealDefinition() {
        for (name in offered) {
            val fn = (BuiltInToolCatalog.localDefinition(name)["function"] as? JsonObject)
                ?: throw AssertionError("$name 的定义里没有 function")
            assertEquals(name, (fn["name"] as? JsonPrimitive)?.content)
            val description = (fn["description"] as? JsonPrimitive)?.content.orEmpty()
            assertTrue("$name 的描述是空的（localDefinition 漏分支）", description.isNotBlank())
            assertNotNull("$name 缺 parameters", fn["parameters"])
        }
    }
}

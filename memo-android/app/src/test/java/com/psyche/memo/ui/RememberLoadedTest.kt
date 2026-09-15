package com.psyche.memo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * `rememberLoaded` 的两条契约（用户 2026-09-15「组合期别干重活」那一批的回归闸门）：
 *
 * 1. `load` 必须跑在**非组合线程**上 —— 这正是它存在的理由；退回同步 `remember`
 *    就会在这里红。
 * 2. 语义必须还是 `remember(key)`：key 不变时跨重组只读一次，key 变了才重读。
 *    这条防的是「用 produceState 改着改着变成每次重组都查库」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RememberLoadedTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun loadRunsOffTheCompositionThread() {
        val compositionThread = AtomicReference<Thread>()
        val loadThread = AtomicReference<Thread>()

        compose.setContent {
            compositionThread.set(Thread.currentThread())
            val value = rememberLoaded("…", "k") {
                loadThread.set(Thread.currentThread())
                "loaded"
            }
            Text(value)
        }

        compose.waitUntil(10_000) { loadThread.get() != null }
        // 值晚一次重组到，但一定会到。
        compose.waitUntil(10_000) { compose.hasText("loaded") }
        assertNotSame(
            "rememberLoaded 的 load 跑在了组合线程上 —— 组合期又在做同步读写了",
            compositionThread.get(),
            loadThread.get(),
        )
    }

    @Test
    fun loadRunsOncePerKeySetAcrossRecompositions() {
        val calls = AtomicInteger(0)

        compose.setContent {
            var unrelated by remember { mutableIntStateOf(0) }
            Column {
                val value = rememberLoaded("init", "stable-key") {
                    calls.incrementAndGet()
                    "loaded"
                }
                Text(value)
                // 改这个状态会让上面同一个组合作用域重组（key 却不变）。
                Text("tick=$unrelated", modifier = Modifier.clickable { unrelated++ })
            }
        }

        compose.waitUntil(10_000) { calls.get() == 1 }
        compose.onNodeWithText("tick=0").performClick()
        compose.onNodeWithText("tick=1").assertExists()
        compose.waitForIdle()
        assertEquals("key 没变却重读了 —— 不是 remember 语义", 1, calls.get())
    }

    @Test
    fun keyChangeReloads() {
        val calls = AtomicInteger(0)

        compose.setContent {
            var n by remember { mutableIntStateOf(0) }
            Column {
                val value = rememberLoaded("v0", n) {
                    calls.incrementAndGet()
                    "v$n"
                }
                Text(value)
                // 点它把 key 从 0 改到 1（`n=0` 也在屏上，能证明重组确实发生了）。
                Text("n=$n", modifier = Modifier.clickable { n++ })
            }
        }

        compose.waitUntil(10_000) { calls.get() == 1 }
        compose.onNodeWithText("n=0").performClick()
        // 先证明重组确实发生了，再看 producer 有没有跟着 key 重启。
        compose.waitUntil(10_000) { compose.hasText("n=1") }
        compose.waitUntil(10_000) { calls.get() >= 2 }
        compose.waitUntil(10_000) { compose.hasText("v1") }
        assertEquals(2, calls.get())
    }
}

/** `waitUntil` 的哨兵：节点树里有没有这段文字。 */
private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.hasText(text: String): Boolean =
    onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

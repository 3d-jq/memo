package com.psyche.memo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [androidx.compose.runtime.remember] 的「IO 版」：保持「一组 key 读一次」的语义，
 * 但把读取放到 [Dispatchers.IO]，组合期不再碰 SQLite。
 *
 * 为什么需要它：本项目里大量「组合期读一次」的写法是 `remember { dao.getAll() }`，
 * 那是**同步**的 —— 读取直接发生在跑组合的那一帧上。用户 2026-09-15 报的「点击统计
 * 会卡一下」就是同一类（聚合在组合期兜底算了一遍）。`rememberLoaded` 用 `produceState`
 * 承载同样的 `remember(key)` 形状（key 不变不重读，见测试），代价是值晚一次重组才到。
 *
 * 只适合「读一次、缓存在组合里」的场景，且调用方要能接受首帧拿到 [initial]：
 * 典型消费者是用户点开的面板（模型选择 sheet 等），点开时早已就位，看不见差别。
 * **不要**用它承载「每次重组都要重新求值」的东西。
 */
@Composable
internal fun <T> rememberLoaded(initial: T, vararg keys: Any?, load: () -> T): T =
    produceState(initial, *keys) {
        value = withContext(Dispatchers.IO) { load() }
    }.value

package com.psyche.memo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * 把 `Dispatchers.Main` 换成 unconfined 的测试调度器。
 *
 * **为什么必须有这个**：Robolectric 下 `Dispatchers.Main` 走的是被暂停的 Looper，
 * `viewModelScope.launch { … }` 里每跨一次 `withContext(Dispatchers.IO)` 都要"回到主线程"，
 * 而主线程就是测试线程 —— 于是测试只能一边 `Thread.sleep` 一边 `shadowOf(Looper).idle()`，
 * 赌协程在超时前被推进。这个赌局在 GitHub 的 2 核 runner 上会输（2026-09-21 连续五轮
 * CI 红，每轮挂的用例还不一样），本机多核则永远赢，所以本地复现不了。
 *
 * 换成 unconfined 之后：`viewModelScope` 的协程不再依赖 Looper 调度，`withContext(IO)`
 * 完成后**在 IO 线程上原地继续**，测试只要等一个真实信号（例如 `vm.tailLoaded`）即可 ——
 * "泵 Looper 与后台线程赛跑"这一层彻底消失。
 *
 * 注意：**只给非 Compose 的 Robolectric 测试用**。Compose UI 测试（`ComposeUiTest`）自己
 * 会接管 Main 调度器，两边同时设置会打架。
 */
class MainDispatcherRule : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
